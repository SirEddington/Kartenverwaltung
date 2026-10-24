package de.eltviller_carneval_verein.karten.mirror;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Die reine Dateilogik der Spiegelung: gleicht die Datenordner {@code events_data} und {@code halls_data}
 * (samt {@code backups/<id>/}) eines Basisordners mit dem eines anderen ab. Ohne Zustand, ohne Threads und ohne
 * JavaFX; wann und auf welchem Thread aufgerufen wird, entscheidet der aufrufende Dienst.
 * <p>
 * Angefasst wird ausschließlich der Inhalt dieser beiden Ordner. Alles andere im Zielordner (auch eigene
 * Dateien des Nutzers) bleibt unberührt, ebenso {@code *.tmp}-Dateien: Sie sind Zwischenstände eines
 * Schreibvorgangs, werden weder verglichen noch kopiert noch gelöscht.
 * <p>
 * Zwei Entscheidungen sind hier begründet:
 * <ul>
 * <li><b>Zeittoleranz:</b> Eine Datei gilt als geändert, wenn die Größe abweicht oder die Änderungszeit um mehr
 * als {@link #MTIME_TOLERANCE_MS} abweicht. USB-Sticks und exFAT stempeln gröber (zwei Sekunden); ohne Toleranz
 * würde bei jedem Abgleich alles neu kopiert. Nach dem Kopieren wird deshalb die Änderungszeit der Quelle
 * übernommen.</li>
 * <li><b>Sicherung gegen leer:</b> {@link #reconcile} löscht nichts, wenn lokal keine einzige Entitätsdatei
 * liegt, im Zielordner aber schon (zum Beispiel, weil die lokalen Daten verloren gingen oder der falsche Ordner
 * eingestellt ist). Es würde sonst die letzte vorhandene Kopie vernichten und wirft stattdessen eine
 * {@link MirrorAbortedException}.</li>
 * </ul>
 * Kopiert wird atomar: erst in eine temporäre Datei im Zielordner, dann umbenennen. Ein Abbruch mittendrin
 * hinterlässt nie eine halbe Zieldatei, und bei einem Fehler wird die temporäre Datei wieder gelöscht.
 */
public final class MirrorSync {

	/** Ordnername der Events unterhalb des Basisordners. */
	public static final String EVENTS = "events_data";

	/** Ordnername der Hallen unterhalb des Basisordners. */
	public static final String HALLS = "halls_data";

	/** Bis zu diesem Unterschied der Änderungszeit (in Millisekunden) gelten zwei Dateien als gleich. */
	public static final long MTIME_TOLERANCE_MS = 2000;

	private static final List<String> KINDS = List.of(EVENTS, HALLS);

	private static final String BACKUPS = "backups";
	private static final String TMP_SUFFIX = ".tmp";
	private static final String JSON_SUFFIX = ".json";

	/**
	 * Der Unterschied zweier Basisordner. Die Einträge sind relative Pfade mit {@code /}, unabhängig vom
	 * Betriebssystem (zum Beispiel {@code events_data/E1.json}).
	 *
	 * @param missing im Zielordner fehlende Dateien
	 * @param changed im Zielordner abweichende Dateien (auch, wenn dort ein Ordner statt einer Datei liegt)
	 * @param extra im Zielordner überzählige Dateien
	 */
	public record Differences(List<String> missing, List<String> changed, List<String> extra) {

		public boolean isEmpty() {
			return missing.isEmpty() && changed.isEmpty() && extra.isEmpty();
		}
	}

	// Ein Eintrag aus einem der beiden Datenordner: Größe und Änderungszeit sind bei Ordnern ohne Bedeutung
	private record Entry(boolean directory, long size, long mtimeMillis) {
	}

	private MirrorSync() {
	}

	/**
	 * Vergleicht die Datenordner unterhalb von {@code local} mit denen unterhalb von {@code target}. Fehlende
	 * Ordner zählen als leer; {@code *.tmp} wird ignoriert. Ändert nichts.
	 */
	public static Differences compare(Path local, Path target) throws IOException {
		List<String> missing = new ArrayList<>();
		List<String> changed = new ArrayList<>();
		List<String> extra = new ArrayList<>();

		SortedMap<String, Entry> localEntries = new TreeMap<>();
		SortedMap<String, Entry> targetEntries = new TreeMap<>();
		for (String kind : KINDS) {
			localEntries.putAll(scan(local, kind));
			targetEntries.putAll(scan(target, kind));
		}

		for (Map.Entry<String, Entry> e : localEntries.entrySet()) {
			if (e.getValue().directory()) {
				continue;
			}
			Entry other = targetEntries.get(e.getKey());
			if (other == null) {
				missing.add(e.getKey());
			} else if (other.directory() || differs(e.getValue(), other)) {
				changed.add(e.getKey());
			}
		}
		for (Map.Entry<String, Entry> e : targetEntries.entrySet()) {
			if (e.getValue().directory()) {
				continue;
			}
			Entry other = localEntries.get(e.getKey());
			if (other == null || other.directory()) {
				extra.add(e.getKey());
			}
		}
		return new Differences(missing, changed, extra);
	}

	/**
	 * Ob unterhalb von {@code root} mindestens eine Entitätsdatei liegt, also eine {@code *.json} direkt in
	 * {@code events_data} oder {@code halls_data}. Backups und {@code *.tmp} zählen nicht.
	 */
	public static boolean hasEntityFiles(Path root) throws IOException {
		for (String kind : KINDS) {
			Path dir = root.resolve(kind);
			if (!Files.isDirectory(dir)) {
				continue;
			}
			try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*" + JSON_SUFFIX)) {
				for (Path file : files) {
					if (Files.isRegularFile(file)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/**
	 * Gleicht {@code target} an {@code local} an: Fehlende und abweichende Dateien werden kopiert, überzählige
	 * gelöscht, nur innerhalb von {@code events_data} und {@code halls_data}.
	 *
	 * @throws MirrorAbortedException (bevor etwas geändert wurde) wenn lokal keine Entitätsdatei liegt, im Ziel aber
	 *         schon; siehe Klassenbeschreibung
	 */
	public static void reconcile(Path local, Path target) throws IOException {
		// Die Prüfung steht vor jeder Änderung: Wer hier abbricht, hat das Ziel nicht angefasst.
		if (!hasEntityFiles(local) && hasEntityFiles(target)) {
			throw new MirrorAbortedException("Lokal sind keine Events und Hallen vorhanden, im Zielordner "
					+ target + " würde alles gelöscht.");
		}
		copyAll(local, target);
	}

	/**
	 * Wie {@link #reconcile(Path, Path)} mit {@code from} als Quelle, aber ohne die Sicherung gegen leer. Für das
	 * Einrichten, wo der Nutzer die Richtung ausdrücklich gewählt hat.
	 */
	public static void copyAll(Path from, Path to) throws IOException {
		for (String kind : KINDS) {
			syncTree(from, to, kind, false);
		}
	}

	/**
	 * Überträgt eine Entität: {@code <kind>/<id>.json} und den Teilbaum {@code <kind>/backups/<id>/} (kopieren,
	 * überschreiben, überzählige Dateien im Teilbaum löschen). Fehlt die Datei lokal, wird sie im Ziel gelöscht.
	 * Die Datei der Entität wird immer kopiert und nicht erst verglichen: Zwei Speichervorgänge kurz
	 * hintereinander können bei gleicher Größe innerhalb der Zeittoleranz liegen und dürften nicht verloren gehen.
	 *
	 * @param kind {@link #EVENTS} oder {@link #HALLS}
	 */
	public static void pushEntity(Path local, Path target, String kind, String id) throws IOException {
		checkEntity(kind, id);

		String fileRel = kind + "/" + id + JSON_SUFFIX;
		Path source = local.resolve(fileRel);
		Path destination = target.resolve(fileRel);
		if (Files.isRegularFile(source)) {
			copyFile(source, destination, Files.getLastModifiedTime(source));
		} else {
			Files.deleteIfExists(destination);
		}

		syncTree(local, target, kind + "/" + BACKUPS + "/" + id, true);
	}

	/**
	 * Wie {@link #pushEntity}, gedacht für eine gelöschte Entität: Die Datei verschwindet im Ziel, der Teilbaum
	 * der Backups (beim Löschen entsteht ein Backup) wird übertragen. Das Löschen der letzten Entität ist hier
	 * erlaubt; die Sicherung gegen leer gilt nur für {@link #reconcile}.
	 */
	public static void deleteEntity(Path local, Path target, String kind, String id) throws IOException {
		pushEntity(local, target, kind, id);
	}

	// Kind und Id werden zu Pfaden und führen zu Löschungen im Zielordner; nichts darf aus den beiden Ordnern ausbrechen.
	private static void checkEntity(String kind, String id) {
		if (!KINDS.contains(kind)) {
			throw new IllegalArgumentException("Unbekannter Datenordner: " + kind);
		}
		if (id == null || id.isBlank() || id.contains("/") || id.contains("\\") || id.contains(":") || id.contains("..")) {
			throw new IllegalArgumentException("Ungültige Id: " + id);
		}
	}

	// Vergleich "geändert" für zwei Dateien: Größe oder Änderungszeit außerhalb der Toleranz
	private static boolean differs(Entry source, Entry destination) {
		return source.size() != destination.size()
				|| Math.abs(source.mtimeMillis() - destination.mtimeMillis()) > MTIME_TOLERANCE_MS;
	}

	/**
	 * Alle Einträge unterhalb von {@code base/rel} (ohne den Ordner selbst), Schlüssel sind relative Pfade mit
	 * {@code /} ab {@code base}. Fehlt der Ordner, ist das Ergebnis leer. {@code *.tmp}-Dateien fehlen darin.
	 * Durch die Sortierung stehen Elternordner vor ihren Inhalten.
	 */
	private static SortedMap<String, Entry> scan(Path base, String rel) throws IOException {
		SortedMap<String, Entry> entries = new TreeMap<>();
		Path start = base.resolve(rel);
		if (!Files.isDirectory(start)) {
			return entries;
		}
		Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
				if (!dir.equals(start)) {
					entries.put(key(start, rel, dir), new Entry(true, 0, 0));
				}
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
				if (!file.getFileName().toString().endsWith(TMP_SUFFIX)) {
					entries.put(key(start, rel, file), new Entry(false, attrs.size(), attrs.lastModifiedTime().toMillis()));
				}
				return FileVisitResult.CONTINUE;
			}
		});
		return entries;
	}

	// Relativer Pfad ab dem Basisordner, immer mit "/"
	private static String key(Path start, String rel, Path path) {
		return rel + "/" + start.relativize(path).toString().replace(File.separatorChar, '/');
	}

	/**
	 * Gleicht den Teilbaum {@code rel} (relativ zu den Basisordnern) von {@code to} an {@code from} an. Zuerst
	 * wird kopiert und erst danach gelöscht, damit ein Fehler dazwischen nicht schon Daten gekostet hat.
	 *
	 * @param removeRoot ob der Ordner {@code rel} selbst im Ziel verschwindet, wenn er in der Quelle fehlt (für
	 *        den Backup-Teilbaum einer Entität; die beiden Datenordner bleiben immer stehen)
	 */
	private static void syncTree(Path from, Path to, String rel, boolean removeRoot) throws IOException {
		Path sourceRoot = from.resolve(rel);
		Path destinationRoot = to.resolve(rel);

		if (!Files.isDirectory(sourceRoot)) {
			// Lokal gibt es nichts: im Ziel alles darunter entfernen (und bei Bedarf den Ordner selbst)
			SortedMap<String, Entry> targetEntries = scan(to, rel);
			for (String key : reversed(targetEntries)) {
				deleteTree(to.resolve(key));
			}
			if (removeRoot) {
				deleteTree(destinationRoot);
			}
			return;
		}
		if (Files.exists(destinationRoot, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(destinationRoot)) {
			Files.delete(destinationRoot);
		}

		SortedMap<String, Entry> sourceEntries = scan(from, rel);
		SortedMap<String, Entry> targetEntries = scan(to, rel);

		// Wo lokal ein Ordner sein muss, im Ziel aber eine Datei liegt, muss die Datei zuerst weichen
		for (Map.Entry<String, Entry> e : sourceEntries.entrySet()) {
			Entry other = targetEntries.get(e.getKey());
			if (e.getValue().directory() && other != null && !other.directory()) {
				Files.delete(to.resolve(e.getKey()));
			}
		}

		for (Map.Entry<String, Entry> e : sourceEntries.entrySet()) {
			Path destination = to.resolve(e.getKey());
			Entry source = e.getValue();
			if (source.directory()) {
				Files.createDirectories(destination);
				continue;
			}
			Entry other = targetEntries.get(e.getKey());
			if (other == null || other.directory() || differs(source, other)) {
				Path sourceFile = from.resolve(e.getKey());
				copyFile(sourceFile, destination, Files.getLastModifiedTime(sourceFile));
			}
		}

		// Überzählige zuletzt; rückwärts, damit Inhalte vor ihren Ordnern drankommen
		for (String key : reversed(targetEntries)) {
			if (!sourceEntries.containsKey(key)) {
				deleteTree(to.resolve(key));
			}
		}
	}

	private static List<String> reversed(SortedMap<String, Entry> entries) {
		List<String> keys = new ArrayList<>(entries.keySet());
		Collections.reverse(keys);
		return keys;
	}

	/**
	 * Kopiert atomar: in eine temporäre Datei im Zielordner, dann umbenennen. Die Änderungszeit der Quelle wird
	 * schon auf der temporären Datei gesetzt, das Ziel zeigt sie also vom ersten Augenblick an. Die temporäre
	 * Datei verschwindet auch bei einem Fehler.
	 * <p>
	 * Liegt im Ziel an dieser Stelle ein Ordner, wird ein leerer ersetzt; ein nicht leerer führt zu einer
	 * {@link IOException}, statt dass hier ein ganzer Ordner des Nutzers gelöscht wird.
	 */
	private static void copyFile(Path source, Path destination, FileTime mtime) throws IOException {
		Files.createDirectories(destination.getParent());
		Path temp = Files.createTempFile(destination.getParent(), "mirror", TMP_SUFFIX);
		try {
			Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING);
			Files.setLastModifiedTime(temp, mtime);
			if (Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)) {
				Files.delete(destination);
			}
			Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} finally {
			Files.deleteIfExists(temp);
		}
	}

	// Löscht eine Datei oder einen Ordner samt Inhalt; ein nicht vorhandener Pfad ist kein Fehler
	private static void deleteTree(Path path) throws IOException {
		if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				Files.delete(file);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
				if (exc != null) {
					throw exc;
				}
				Files.delete(dir);
				return FileVisitResult.CONTINUE;
			}
		});
	}
}
