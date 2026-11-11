package de.eltviller_carneval_verein.karten.update;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Der Ordner {@code updates/} neben {@code data/} im gemeinsamen Ordner (KARV-132) und seine strengen Namensregeln.
 * Alles, was den Mustern nicht entspricht, wird ignoriert, insbesondere Namen mit {@code .} oder {@code ~} am Anfang
 * und Konfliktkopien des Nextcloud-Clients.
 */
public final class UpdatesDir {

	/** Der Ordner neben {@code data/}. */
	public static final String UPDATES = "updates";
	/** Der Unterordner der Kasseneinträge. */
	public static final String STATIONS = "kassen";

	private static final Logger LOG = Logger.getLogger(UpdatesDir.class.getName());
	private static final Pattern INSTALLER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*\\.msix");
	private static final Pattern CLAIM = Pattern.compile("laden-(\\d+\\.\\d+\\.\\d+\\.\\d+)-([A-Za-z0-9-]+)\\.json");
	private static final Pattern STATION = Pattern.compile("[A-Za-z0-9-]+\\.json");
	private static final String DESCRIPTION_SUFFIX = ".json";

	private UpdatesDir() {
	}

	/**
	 * Legt {@code updates/} (und darunter {@code sub}) an, wenn nötig, aber nur, wenn {@code folder} selbst ein
	 * Verzeichnis ist; den gemeinsamen Ordner legt es nie an.
	 *
	 * @return der tiefste Ordner, leer, wenn {@code folder} fehlt oder das Anlegen scheitert
	 */
	public static Optional<Path> ensure(Path folder, String... sub) {
		if (!Files.isDirectory(folder)) {
			return Optional.empty();
		}
		Path dir = folder.resolve(UPDATES);
		try {
			createDirectory(dir);
			for (String s : sub) {
				dir = dir.resolve(s);
				createDirectory(dir);
			}
			return Optional.of(dir);
		} catch (IOException | RuntimeException e) {
			LOG.log(Level.WARNING, "Ordner im gemeinsamen Ordner ließ sich nicht anlegen: " + dir, e);
			return Optional.empty();
		}
	}

	private static void createDirectory(Path dir) throws IOException {
		if (Files.isDirectory(dir)) {
			return;
		}
		try {
			Files.createDirectory(dir);
		} catch (FileAlreadyExistsException e) {
			if (!Files.isDirectory(dir)) {
				throw e;
			}
		}
	}

	/** Ob der Name ein Installer ist ({@code ^[A-Za-z0-9][A-Za-z0-9._-]*\.msix$}). */
	public static boolean isInstaller(String name) {
		return INSTALLER.matcher(name).matches();
	}

	/** Ob der Name die Beschreibung eines Installers ist ({@code <Installer>.json}). */
	public static boolean isDescription(String name) {
		return name.endsWith(DESCRIPTION_SUFFIX) && isInstaller(installerOf(name));
	}

	/** Der Name des Installers zu einer Beschreibung. */
	public static String installerOf(String descriptionName) {
		return descriptionName.substring(0, descriptionName.length() - DESCRIPTION_SUFFIX.length());
	}

	/** Der Name der Beschreibung zu einem Installer. */
	public static String descriptionOf(String installerName) {
		return installerName + DESCRIPTION_SUFFIX;
	}

	/** Ob der Name eine Ladedatei ist ({@code laden-<a.b.c.d>-<Rechner-ID>.json}). */
	public static boolean isClaim(String name) {
		return CLAIM.matcher(name).matches();
	}

	/** Die Paketversion im Namen einer Ladedatei. */
	public static Optional<String> claimVersion(String name) {
		Matcher m = CLAIM.matcher(name);
		return m.matches() ? Optional.of(m.group(1)) : Optional.empty();
	}

	/** Die Rechner-ID im Namen einer Ladedatei. */
	public static Optional<String> claimMachine(String name) {
		Matcher m = CLAIM.matcher(name);
		return m.matches() ? Optional.of(m.group(2)) : Optional.empty();
	}

	/** Der Name der Ladedatei einer Kasse für eine Paketversion. */
	public static String claimName(String packageVersion, String machineId) {
		return "laden-" + packageVersion + "-" + machineId + ".json";
	}

	/** Ob der Name ein Kasseneintrag ist ({@code <Rechner-ID>.json} in {@code kassen/}). */
	public static boolean isStation(String name) {
		return STATION.matcher(name).matches();
	}

	/** Ob der Name eine Nextcloud-Konfliktkopie eines Installers oder einer Beschreibung ist. */
	public static boolean isConflictCopy(String name) {
		return name.contains("(conflicted copy") && (name.endsWith(".msix") || name.endsWith(".json"));
	}

	/** Der Name einer eigenen temporären Datei ({@code ~karv-<Rechner-ID>-<UUID>.tmp}). */
	public static String tempName(String machineId) {
		return "~karv-" + machineId + "-" + UUID.randomUUID() + ".tmp";
	}

	/** Die Kennung im Namen einer temporären Datei {@code ~karv-<Kennung>-<UUID>.tmp}, leer bei anderen Namen. */
	public static Optional<String> tempTag(String name) {
		if (!name.startsWith("~karv-") || !name.endsWith(".tmp") || name.length() < "~karv-".length() + 37 + 4) {
			return Optional.empty();
		}
		String inner = name.substring("~karv-".length(), name.length() - ".tmp".length());
		// die UUID hat 36 Zeichen, davor ein Bindestrich
		int cut = inner.length() - 37;
		return cut > 0 && inner.charAt(cut) == '-' ? Optional.of(inner.substring(0, cut)) : Optional.empty();
	}
}
