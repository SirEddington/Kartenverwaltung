package de.eltviller_carneval_verein.karten.store;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Die einzige Stelle, über die der Versionsspeicher auf Platten zugreift, sowohl auf den lokalen Speicher als auch auf
 * den gemeinsamen Ordner im Netz. Die Schicht ist eine Schnittstelle, damit Tests Fehler und Abstürze zwischen
 * einzelnen Schritten nachstellen können.
 * <p>
 * Temporäre Dateien heißen {@code ~karv-<UUID>.tmp}. Alles, was mit {@code ~karv-} beginnt, ist für {@link #listNames}
 * unsichtbar. Die Schicht löscht nie Daten, sondern nur eigene temporäre Dateien.
 */
public interface FileLayer {

	/** Präfix aller temporären Dateien dieser Schicht. */
	String TEMP_PREFIX = "~karv-";

	/** Endung aller temporären Dateien dieser Schicht. */
	String TEMP_SUFFIX = ".tmp";

	/**
	 * Legt die Datei nur an, wenn sie noch nicht vorhanden ist (die Grundlage der Anspruchsdateien). Fehlende
	 * Elternordner werden angelegt. Der Inhalt erscheint vollständig oder gar nicht, nie halb geschrieben.
	 *
	 * @return {@code true}, wenn diese Schicht die Datei angelegt hat; {@code false}, wenn sie schon vorhanden war (der
	 *         vorhandene Inhalt bleibt unverändert)
	 */
	boolean createNew(Path file, byte[] content) throws IOException;

	/** Der Inhalt der Datei; leer, wenn die Datei fehlt. Andere Fehler werden als {@link IOException} gemeldet. */
	Optional<byte[]> read(Path file) throws IOException;

	/** {@code true}, wenn es den Pfad gibt (Datei oder Ordner). */
	boolean exists(Path file);

	/**
	 * Die Namen (nicht Pfade) der direkten Einträge des Ordners, Dateien wie Ordner, sortiert und ohne {@code ~karv-*}.
	 * Ist der Ordner nicht vorhanden, ist die Liste leer.
	 */
	List<String> listNames(Path dir) throws IOException;

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
	 */
	void replaceDamaged(Path file, byte[] good, String corruptName) throws IOException;

	/** Benennt die Datei um. Ein vorhandenes Ziel wird nicht ersetzt. */
	void rename(Path from, Path to) throws IOException;

	/**
	 * Löscht eigene temporäre Dateien ({@code ~karv-*.tmp}) im Ordner und allen Unterordnern, die seit mehr als
	 * {@code olderThan} nicht geändert wurden. Andere Dateien bleiben unberührt. Ein fehlender Ordner ist kein Fehler.
	 */
	void deleteOwnTemps(Path dir, Duration olderThan) throws IOException;
}
