package de.eltviller_carneval_verein.karten.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Test-Doppel für einen Nextcloud-Ordner: ein Server-Ordner und je Rechner ein lokal synchronisierter Client-Ordner.
 * Jeder Dienst im Test nimmt seinen Client-Ordner als "gemeinsamen Ordner"; {@link #step} spielt den Sync-Client
 * dieses Rechners ab, und zwar nur, wenn der Test es verlangt, damit er die Reihenfolge der Ankunft genau bestimmt.
 * <p>
 * Regeln eines Durchlaufs:
 * <ol>
 * <li>Hochladen: Jede lokale Datei, die der Server nicht kennt, kommt auf den Server. Kennt er eine andere Fassung und
 * hat der Rechner die Datei seit dem letzten Abgleich geändert, gewinnt, wer zuerst hochgeladen hat: Hat auch der
 * Server die Datei seit dem letzten Abgleich dieses Rechners geändert (ein anderer Rechner war zuerst), wird die lokale
 * Fassung in {@code <Name> (conflicted copy <Rechner>).<Endung>} umbenannt und die Server-Fassung an den Originalpfad
 * geschrieben. Hat nur der Rechner geändert, überschreibt er den Server.</li>
 * <li>Herunterladen: Jede Server-Datei, die dem Rechner fehlt oder die der Server geändert hat, während der Rechner
 * sie nicht änderte, kommt in den Client-Ordner.</li>
 * </ol>
 * Wie bei Nextcloud bleiben Dateien unberücksichtigt, deren Name mit {@code ~karv-} beginnt und auf {@code .tmp}
 * endet (Ausschlussregel {@code ~*.tmp}), und Konfliktkopien werden nie hochgeladen. Löschungen werden nicht
 * übertragen. Der Sync-Client darf in den Client-Ordnern schreiben, wie er will; er ist kein Produktivcode.
 */
public final class SimulatedCloud {

	private static final String TEMP_PREFIX = "~karv-";
	private static final String TEMP_SUFFIX = ".tmp";
	private static final String CONFLICT_MARK = "(conflicted copy";

	private final Path server;
	private final Map<String, Path> clients = new HashMap<>();
	/** Je Rechner und relativem Pfad: der SHA-256 des Inhalts, auf den sich Rechner und Server zuletzt einigten. */
	private final Map<String, Map<String, String>> agreed = new HashMap<>();

	/**
	 * @param server der Server-Ordner; die Client-Ordner entstehen als Geschwister davon
	 */
	public SimulatedCloud(Path server) {
		this.server = server;
		try {
			Files.createDirectories(server);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * Legt den leeren Client-Ordner eines Rechners an.
	 *
	 * @return der lokal synchronisierte Ordner, den der Dienst dieses Rechners als gemeinsamen Ordner nimmt
	 */
	public Path addClient(String name) {
		Path folder = server.resolveSibling("client-" + name);
		try {
			Files.createDirectories(folder);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		clients.put(name, folder);
		agreed.put(name, new HashMap<>());
		return folder;
	}

	/** Ein vollständiger Sync-Durchlauf dieses Rechners. */
	public void step(String client) {
		sync(client, rel -> true);
	}

	/**
	 * Ein Sync-Durchlauf, der nur Dateien überträgt, deren Pfad (relativ zum Client-Ordner, mit {@code /}) passt: So
	 * kommt eine Liste vor ihrer Versionsdatei an, oder Version und Liste vor dem Anspruch.
	 */
	public void stepFilesOnly(String client, Predicate<String> rel) {
		sync(client, rel);
	}

	private void sync(String client, Predicate<String> selected) {
		Path folder = clients.get(client);
		Map<String, String> known = agreed.get(client);
		if (folder == null) {
			throw new IllegalArgumentException("Unbekannter Rechner: " + client);
		}
		try {
			for (String rel : files(folder)) {
				if (selected.test(rel)) {
					upload(client, folder, known, rel);
				}
			}
			for (String rel : files(server)) {
				if (selected.test(rel)) {
					download(folder, known, rel);
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private void upload(String client, Path folder, Map<String, String> known, String rel) throws IOException {
		Path mine = folder.resolve(rel);
		Path theirs = server.resolve(rel);
		byte[] mineBytes = Files.readAllBytes(mine);
		String mineHash = sha256(mineBytes);
		if (!Files.exists(theirs)) {
			write(theirs, mineBytes);
			known.put(rel, mineHash);
			return;
		}
		byte[] theirBytes = Files.readAllBytes(theirs);
		String theirHash = sha256(theirBytes);
		if (theirHash.equals(mineHash)) {
			known.put(rel, mineHash);
			return;
		}
		String base = known.get(rel);
		if (mineHash.equals(base)) {
			return;
		}
		if (theirHash.equals(base)) {
			write(theirs, mineBytes);
			known.put(rel, mineHash);
			return;
		}
		// beide haben geändert (oder die Datei ist hier neu und dort schon da): der Rechner kam zu spät
		Files.move(mine, conflictCopy(mine, client), StandardCopyOption.REPLACE_EXISTING);
		write(mine, theirBytes);
		known.put(rel, theirHash);
	}

	private void download(Path folder, Map<String, String> known, String rel) throws IOException {
		Path mine = folder.resolve(rel);
		byte[] theirBytes = Files.readAllBytes(server.resolve(rel));
		String theirHash = sha256(theirBytes);
		if (!Files.exists(mine)) {
			write(mine, theirBytes);
			known.put(rel, theirHash);
			return;
		}
		String mineHash = sha256(Files.readAllBytes(mine));
		if (!mineHash.equals(theirHash) && mineHash.equals(known.get(rel))) {
			write(mine, theirBytes);
			known.put(rel, theirHash);
		}
	}

	private static Path conflictCopy(Path file, String client) {
		String name = file.getFileName().toString();
		int dot = name.lastIndexOf('.');
		String stem = dot < 0 ? name : name.substring(0, dot);
		String extension = dot < 0 ? "" : name.substring(dot);
		return file.resolveSibling(stem + " " + CONFLICT_MARK + " " + client + ")" + extension);
	}

	/** Alle Dateien, die ein Sync-Client überträgt: relativ, mit {@code /}, sortiert. */
	private static List<String> files(Path folder) throws IOException {
		try (Stream<Path> walk = Files.walk(folder)) {
			return walk.filter(Files::isRegularFile).map(p -> folder.relativize(p).toString().replace('\\', '/'))
					.filter(SimulatedCloud::synced).sorted().toList();
		}
	}

	private static boolean synced(String rel) {
		String name = rel.substring(rel.lastIndexOf('/') + 1);
		boolean temp = name.startsWith(TEMP_PREFIX) && name.endsWith(TEMP_SUFFIX);
		return !temp && !name.contains(CONFLICT_MARK);
	}

	private static void write(Path file, byte[] bytes) throws IOException {
		Files.createDirectories(file.getParent());
		Files.write(file, bytes);
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
