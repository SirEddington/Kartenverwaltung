package de.eltviller_carneval_verein.karten.store;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Die einzige Stelle, über die der Versionsspeicher auf Platten zugreift, sowohl auf den lokalen Speicher als auch auf
 * den gemeinsamen Ordner im Netz. Die Schicht ist eine Schnittstelle, damit Tests Fehler und Abstürze zwischen
 * einzelnen Schritten nachstellen können.
 * <p>
 * Temporäre Dateien heißen {@code ~karv-<UUID>.tmp}, mit Kennung des schreibenden Rechners {@code ~karv-<Kennung>-<UUID>.tmp}
 * (siehe {@link #tempTagOf}). Alles, was mit {@code ~karv-} beginnt, ist für {@link #listNames} unsichtbar. Die Schicht
 * löscht nie Daten, sondern nur eigene temporäre Dateien.
 */
public interface FileLayer {

	/** Präfix aller temporären Dateien dieser Schicht. */
	String TEMP_PREFIX = "~karv-";

	/** Endung aller temporären Dateien dieser Schicht. */
	String TEMP_SUFFIX = ".tmp";

	/** Die Länge einer UUID als Text ({@code 8-4-4-4-12}). */
	int UUID_LENGTH = 36;

	/**
	 * Legt die Datei nur an, wenn sie noch nicht vorhanden ist (die Grundlage der Anspruchsdateien). Der Inhalt erscheint
	 * vollständig oder gar nicht, nie halb geschrieben.
	 * <p>
	 * Ohne {@code anchor} werden fehlende Elternordner mit {@link java.nio.file.Files#createDirectories} angelegt. Mit
	 * {@code anchor} (dem gemeinsamen Ordner selbst) darf die Datei nur darunter liegen; fehlende Ordner zwischen Anker und
	 * Datei werden einzeln angelegt, der Anker nie: Ist er verschwunden, scheitert der Aufruf mit
	 * {@link java.nio.file.NoSuchFileException}, und es wird nichts angelegt. Beide Pfade werden absolut und normalisiert
	 * verglichen.
	 *
	 * @param anchor  der Ordner, unter dem angelegt wird und der selbst nie angelegt wird; {@code null} für keinen Anker
	 * @param tempTag die Kennung im Namen der temporären Datei ({@code ~karv-<Kennung>-<UUID>.tmp}); {@code null} für eine
	 *                Datei ohne Kennung ({@code ~karv-<UUID>.tmp})
	 * @return {@code true}, wenn diese Schicht die Datei angelegt hat; {@code false}, wenn sie schon vorhanden war (der
	 *         vorhandene Inhalt bleibt unverändert)
	 * @throws IllegalArgumentException wenn {@code anchor} gesetzt ist und die Datei nicht darunter liegt
	 */
	boolean createNew(Path file, byte[] content, Path anchor, String tempTag) throws IOException;

	/** Wie {@link #createNew(Path, byte[], Path, String)} ohne Anker und ohne Kennung: Fehlende Elternordner werden angelegt. */
	default boolean createNew(Path file, byte[] content) throws IOException {
		return createNew(file, content, null, null);
	}

	/** Der Inhalt der Datei; leer, wenn die Datei fehlt. Andere Fehler werden als {@link IOException} gemeldet. */
	Optional<byte[]> read(Path file) throws IOException;

	/** {@code true}, wenn es den Pfad gibt (Datei oder Ordner). */
	boolean exists(Path file);

	/**
	 * Die Namen (nicht Pfade) der direkten Einträge des Ordners, Dateien wie Ordner, sortiert und ohne {@code ~karv-*}.
	 * Ist der Ordner nicht vorhanden, ist die Liste leer.
	 */
	List<String> listNames(Path dir) throws IOException;

	/** Ein Eintrag eines Ordners: der Name (nicht der Pfad) und ob es ein Ordner ist (sonst eine Datei oder etwas anderes). */
	record DirEntry(String name, boolean directory) {
	}

	/**
	 * Wie {@link #listNames}, aber mit der Angabe je Eintrag, ob er ein Ordner ist, aus demselben Durchlauf: Auf einer
	 * Netzfreigabe kostet ein Zugriff je Eintrag ({@link #isDirectory}) ein Vielfaches des Auflistens. Sortiert nach Namen,
	 * ohne {@code ~karv-*}; ist der Ordner nicht vorhanden, ist die Liste leer.
	 * <p>
	 * Die Standardfassung fragt {@link #listNames} und dann {@link #isDirectory} je Eintrag; eine Schicht überschreibt sie,
	 * wenn sie beides in einem Durchlauf liefern kann.
	 */
	default List<DirEntry> listEntries(Path dir) throws IOException {
		List<DirEntry> entries = new ArrayList<>();
		for (String name : listNames(dir)) {
			entries.add(new DirEntry(name, isDirectory(dir.resolve(name))));
		}
		return entries;
	}

	/** Wie {@link #listEntries}, aber mit den {@code ~karv-*}-Einträgen: die temporären Dateien eingeschlossen. */
	List<DirEntry> listEntriesWithTemps(Path dir) throws IOException;

	/**
	 * Der ganze Verzeichnisbaum unter {@code root} in einem Durchlauf: jeder Ordner, auch ein leerer und {@code root}
	 * selbst (Schlüssel {@code ""}), mit den Namen der regulären Dateien darin, sortiert und ohne Unterordner. Die
	 * Schlüssel sind die Pfade relativ zu {@code root}, getrennt durch {@code /}. Anders als {@link #listNames} blendet
	 * der Durchlauf {@code ~karv-*} nicht aus. Fehlt {@code root}, ist die Map leer.
	 */
	Map<String, List<String>> listTree(Path root) throws IOException;

	/** {@code true}, wenn der Pfad ein vorhandener Ordner ist. */
	boolean isDirectory(Path path);

	/**
	 * Ersetzt eine beschädigte Datei durch eine gute Fassung und behält die beschädigte. Die gute Fassung wird zuerst
	 * als temporäre Datei geschrieben, dann wird die beschädigte in {@code corruptName} umbenannt und zuletzt die
	 * temporäre an den Platz der Datei verschoben. Scheitert der letzte Schritt, wird die beschädigte Datei
	 * zurückbenannt; keine der beiden wird gelöscht.
	 *
	 * @param corruptName Dateiname (kein Pfad) im Ordner der Datei, unter dem die beschädigte Fassung bleibt
	 * @param tempTag     die Kennung im Namen der temporären Datei ({@code ~karv-<Kennung>-<UUID>.tmp}); {@code null} für
	 *                    eine Datei ohne Kennung ({@code ~karv-<UUID>.tmp}). Im gemeinsamen Ordner trägt sie die Kennung
	 *                    des schreibenden Rechners, damit ein Rest dort seinem Urheber zuzuordnen ist.
	 */
	void replaceDamaged(Path file, byte[] good, String corruptName, String tempTag) throws IOException;

	/** Wie {@link #replaceDamaged(Path, byte[], String, String)} ohne Kennung (für lokale Dateien). */
	default void replaceDamaged(Path file, byte[] good, String corruptName) throws IOException {
		replaceDamaged(file, good, corruptName, null);
	}

	/** Benennt die Datei um. Ein vorhandenes Ziel wird nicht ersetzt. */
	void rename(Path from, Path to) throws IOException;

	/**
	 * Löscht eigene temporäre Dateien ({@code ~karv-*.tmp}) im Ordner und allen Unterordnern, die seit mehr als
	 * {@code olderThan} nicht geändert wurden. Andere Dateien bleiben unberührt. Ein fehlender Ordner ist kein Fehler.
	 */
	void deleteOwnTemps(Path dir, Duration olderThan) throws IOException;

	/**
	 * Löscht aus dem Ordner nur die genannten Dateien, und nur wenn es eigene temporäre Dateien sind (Name beginnt mit
	 * {@code ~karv-} und endet auf {@code .tmp}) und sie seit mehr als {@code olderThan} nicht geändert wurden. Alle
	 * anderen Namen (auch solche mit Pfadtrennern) bleiben unberührt. Eine fehlende Datei oder ein fehlender Ordner ist
	 * kein Fehler.
	 *
	 * @param names Dateinamen (keine Pfade) im Ordner
	 */
	void deleteOldTemps(Path dir, Collection<String> names, Duration olderThan) throws IOException;

	/**
	 * Die Kennung aus dem Namen einer temporären Datei {@code ~karv-<Kennung>-<UUID>.tmp}. Eine UUID hat 36 Zeichen; die
	 * Kennung ist der Teil zwischen {@code ~karv-} und {@code -<UUID>.tmp} und darf selbst {@code -} enthalten.
	 *
	 * @return die Kennung; {@code null} für {@code ~karv-<UUID>.tmp} (ohne Kennung) und für fremde Namen
	 */
	static String tempTagOf(String fileName) {
		if (fileName == null || !fileName.startsWith(TEMP_PREFIX) || !fileName.endsWith(TEMP_SUFFIX)) {
			return null;
		}
		int end = fileName.length() - TEMP_SUFFIX.length();
		int uuidStart = end - UUID_LENGTH;
		int tagEnd = uuidStart - 1;
		if (tagEnd <= TEMP_PREFIX.length() || fileName.charAt(tagEnd) != '-' || !isUuid(fileName, uuidStart, end)) {
			return null;
		}
		return fileName.substring(TEMP_PREFIX.length(), tagEnd);
	}

	/** Ob {@code text[from, to)} wie eine UUID aussieht: {@code 8-4-4-4-12} kleingeschriebene Hexadezimalziffern. */
	private static boolean isUuid(String text, int from, int to) {
		if (to - from != UUID_LENGTH) {
			return false;
		}
		for (int i = 0; i < UUID_LENGTH; i++) {
			char c = text.charAt(from + i);
			boolean dash = i == 8 || i == 13 || i == 18 || i == 23;
			if (dash ? c != '-' : !(c >= '0' && c <= '9' || c >= 'a' && c <= 'f')) {
				return false;
			}
		}
		return true;
	}
}
