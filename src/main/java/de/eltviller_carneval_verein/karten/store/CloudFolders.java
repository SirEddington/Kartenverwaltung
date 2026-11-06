package de.eltviller_carneval_verein.karten.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Erkennt, ob ein Ordner zu einem Cloud-Client gehört, und nennt dann den Server, dessen Erreichbarkeit zählt (KARV-127).
 * Ein Sync-Ordner liegt lokal und ist deshalb immer "da"; ob der Rechner wirklich mit den anderen austauscht, zeigt erst
 * der Server. Erkannt werden:
 * <ul>
 * <li><b>Nextcloud</b>: die Konfiguration des Clients ({@code %APPDATA%\Nextcloud\nextcloud.cfg}), Abschnitt
 * {@code [Accounts]}: je Konto die URL (<code>N&#92;url</code>) und dessen Ordner (<code>N&#92;Folders…&#92;M&#92;localPath</code>, auch
 * {@code FoldersWithPlaceholders} und {@code Multifolders}).</li>
 * <li><b>OneDrive</b>: die Umgebungsvariablen {@code OneDriveCommercial} (geschäftlich), {@code OneDriveConsumer} und
 * {@code OneDrive} (privat, außer sie nennt denselben Ordner wie die geschäftliche). Geprüft wird ein Server von
 * Microsoft, also nur, ob das Internet geht.</li>
 * </ul>
 * Pfade werden als Text verglichen (Trenner vereinheitlicht, ohne Groß- und Kleinschreibung, nur an Ordnergrenzen); die
 * längste passende Wurzel gewinnt, bei gleicher Länge die zuerst eingetragene. Die Konfiguration von Nextcloud wird bei
 * jeder Abfrage neu gelesen; fehlt sie oder ist sie unlesbar, gilt der Ordner als keiner (das zweite einmal im Log). Die
 * Umgebung ändert sich während des Programmlaufs nicht und wird einmal gelesen. Ohne JavaFX.
 */
public final class CloudFolders {

	private static final Logger LOGGER = Logger.getLogger(CloudFolders.class.getName());

	/** Der geprüfte Server für private OneDrive-Ordner. */
	static final String ONEDRIVE_PERSONAL_HOST = "onedrive.live.com";

	/** Der geprüfte Server für geschäftliche OneDrive-Ordner (die Anmeldung von Microsoft 365). */
	static final String ONEDRIVE_BUSINESS_HOST = "login.microsoftonline.com";

	private static final char BYTE_ORDER_MARK = 0xFEFF;
	private static final int HTTPS_PORT = 443;
	private static final int HTTP_PORT = 80;

	private static final Pattern URL_KEY = Pattern.compile("(\\d+)\\\\url");
	private static final Pattern FOLDER_KEY = Pattern.compile("(\\d+)\\\\\\w*[Ff]olders\\w*\\\\\\d+\\\\localPath");

	/** Der Server, dessen Erreichbarkeit für einen Cloud-Ordner zählt. */
	public record CloudTarget(String host, int port) {
	}

	private record Root(String path, CloudTarget target) {
	}

	private final Supplier<Optional<String>> nextcloudConfig;
	private final List<Root> oneDriveRoots;
	private final AtomicBoolean warned = new AtomicBoolean();

	/**
	 * @param nextcloudConfig liefert den Inhalt von {@code nextcloud.cfg}, leer, wenn es sie nicht gibt; darf werfen
	 * @param environment     Umgebungsvariablen nach Namen ({@code null}, wenn nicht gesetzt); nur hier gelesen
	 */
	public CloudFolders(Supplier<Optional<String>> nextcloudConfig, Function<String, String> environment) {
		this.nextcloudConfig = nextcloudConfig;
		this.oneDriveRoots = oneDriveRoots(environment);
	}

	/** Mit der Konfiguration und der Umgebung dieses Rechners. */
	public static CloudFolders system() {
		String appData = System.getenv("APPDATA");
		return new CloudFolders(() -> readNextcloudConfig(appData), System::getenv);
	}

	/** Der Server des Cloud-Clients, zu dem {@code folder} gehört; leer, wenn er zu keinem erkannten gehört. */
	public Optional<CloudTarget> find(String folder) {
		if (folder == null || folder.isBlank()) {
			return Optional.empty();
		}
		String path = normalize(folder);
		Root best = null;
		for (Root root : roots()) {
			if (contains(root.path, path) && (best == null || root.path.length() > best.path.length())) {
				best = root;
			}
		}
		return best == null ? Optional.empty() : Optional.of(best.target);
	}

	private List<Root> roots() {
		List<Root> roots = new ArrayList<>();
		try {
			nextcloudConfig.get().ifPresent(cfg -> roots.addAll(nextcloudRoots(cfg)));
		} catch (RuntimeException e) {
			if (warned.compareAndSet(false, true)) {
				LOGGER.log(Level.WARNING, "Die Konfiguration von Nextcloud ist nicht lesbar", e);
			}
		}
		roots.addAll(oneDriveRoots);
		return roots;
	}

	/**
	 * Die geschäftliche zuerst: Nennt {@code OneDrive} denselben Ordner, gewinnt sie bei gleicher Länge ({@link #find}).
	 */
	private static List<Root> oneDriveRoots(Function<String, String> environment) {
		List<Root> roots = new ArrayList<>();
		addOneDrive(roots, environment.apply("OneDriveCommercial"), ONEDRIVE_BUSINESS_HOST);
		addOneDrive(roots, environment.apply("OneDriveConsumer"), ONEDRIVE_PERSONAL_HOST);
		addOneDrive(roots, environment.apply("OneDrive"), ONEDRIVE_PERSONAL_HOST);
		return List.copyOf(roots);
	}

	private static void addOneDrive(List<Root> roots, String folder, String host) {
		if (folder != null && !folder.isBlank()) {
			roots.add(new Root(normalize(folder), new CloudTarget(host, HTTPS_PORT)));
		}
	}

	/** Die Ordner im Abschnitt {@code [Accounts]}, jeder mit dem Server seines Kontos; ohne gültige URL keiner. */
	private static List<Root> nextcloudRoots(String cfg) {
		Map<String, CloudTarget> servers = new HashMap<>();
		Map<String, List<String>> folders = new HashMap<>();
		boolean accounts = false;
		// ein BOM (etwa nach dem Speichern in einem Editor) ist kein Leerraum und hielte "[Accounts]" in der ersten Zeile verborgen
		String content = !cfg.isEmpty() && cfg.charAt(0) == BYTE_ORDER_MARK ? cfg.substring(1) : cfg;
		for (String raw : content.split("\\R")) {
			String line = raw.strip();
			if (line.startsWith("[")) {
				accounts = line.equals("[Accounts]");
				continue;
			}
			int eq = line.indexOf('=');
			if (!accounts || eq < 0) {
				continue;
			}
			String key = line.substring(0, eq).strip();
			String value = unescape(line.substring(eq + 1).strip());
			Matcher url = URL_KEY.matcher(key);
			Matcher folder = FOLDER_KEY.matcher(key);
			if (url.matches()) {
				server(value).ifPresent(target -> servers.put(url.group(1), target));
			} else if (folder.matches() && !value.isBlank()) {
				folders.computeIfAbsent(folder.group(1), account -> new ArrayList<>()).add(value);
			}
		}
		List<Root> roots = new ArrayList<>();
		folders.forEach((account, paths) -> {
			CloudTarget target = servers.get(account);
			if (target != null) {
				paths.forEach(path -> roots.add(new Root(normalize(path), target)));
			}
		});
		return roots;
	}

	private static Optional<CloudTarget> server(String url) {
		try {
			URI uri = new URI(url);
			if (uri.getHost() == null) {
				// eine Domain mit Umlauten kennt URI nicht als Host; in ASCII (Punycode) schon
				uri = new URI(IDN.toASCII(url));
			}
			String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
			if (uri.getHost() == null || !(scheme.equals("https") || scheme.equals("http"))) {
				return Optional.empty();
			}
			int port = uri.getPort() != -1 ? uri.getPort() : scheme.equals("https") ? HTTPS_PORT : HTTP_PORT;
			return Optional.of(new CloudTarget(uri.getHost(), port));
		} catch (URISyntaxException | IllegalArgumentException e) {
			return Optional.empty();
		}
	}

	/**
	 * Ein Wert im INI-Format von Qt: Anführungszeichen fallen weg, {@code \xHHHH} ist ein Zeichen (Qt 5 schreibt so
	 * Umlaute), {@code \\} und {@code \"} stehen für sich.
	 */
	static String unescape(String value) {
		StringBuilder out = new StringBuilder(value.length());
		int i = 0;
		while (i < value.length()) {
			char c = value.charAt(i);
			if (c == '"') {
				i++;
			} else if (c == '\\' && i + 1 < value.length()) {
				char next = value.charAt(i + 1);
				if (next == 'x') {
					int end = i + 2;
					while (end < value.length() && end < i + 6 && Character.digit(value.charAt(end), 16) >= 0) {
						end++;
					}
					if (end > i + 2) {
						out.append((char) Integer.parseInt(value.substring(i + 2, end), 16));
					}
					i = end;
				} else {
					out.append(next);
					i += 2;
				}
			} else {
				out.append(c);
				i++;
			}
		}
		return out.toString();
	}

	// Trenner vereinheitlicht, ohne abschließenden Trenner, klein geschrieben: als Text vergleichbar, auch unter Linux
	private static String normalize(String path) {
		String unified = path.strip().replace('\\', '/');
		while (unified.length() > 1 && unified.endsWith("/")) {
			unified = unified.substring(0, unified.length() - 1);
		}
		return unified.toLowerCase(Locale.ROOT);
	}

	// nur an Ordnergrenzen; eine Wurzel, die auf "/" endet, ist nach normalize die Unix-Wurzel selbst
	private static boolean contains(String root, String path) {
		return path.equals(root) || path.startsWith(root.endsWith("/") ? root : root + "/");
	}

	private static Optional<String> readNextcloudConfig(String appData) {
		if (appData == null || appData.isBlank()) {
			return Optional.empty();
		}
		try {
			return new NioFileLayer().read(Path.of(appData, "Nextcloud", "nextcloud.cfg"))
					.map(bytes -> new String(bytes, StandardCharsets.UTF_8));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
