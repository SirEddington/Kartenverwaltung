package de.eltviller_carneval_verein.karten.mirror;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import de.eltviller_carneval_verein.karten.validation.SettingsIssue;
import de.eltviller_carneval_verein.karten.validation.ValidationResult;

/**
 * Die Logik, die beim Speichern eines <em>neuen</em> Spiegelordners läuft: {@link #check} prüft und legt den Ordner
 * an, {@link #decide} vergleicht lokale Daten und Spiegelordner, {@link #execute} überträgt in die gewählte
 * Richtung. Die Rückfragen an den Nutzer und die Anzeige gehören dem Aufrufer; hier gibt es kein JavaFX.
 * <p>
 * Das Einrichten ist die einzige Stelle, an der der Spiegelordner die lokalen Daten ersetzen darf
 * ({@link Action#ADOPT_TARGET}). Damit dabei nichts verloren geht, steht vor jeder Änderung eine vollständige
 * Sicherung der lokalen Daten, siehe {@link #execute}.
 */
public final class MirrorSetup {

	/** Was beim Einrichten mit den Daten geschehen soll. */
	public enum Action {
		/** Nichts zu übertragen. */
		NOTHING,
		/** Die lokalen Daten in den Spiegelordner schreiben. */
		PUSH_LOCAL,
		/** Die Daten des Spiegelordners übernehmen und die lokalen ersetzen (nach einer Sicherung). */
		ADOPT_TARGET,
		/** Beide Seiten haben unterschiedliche Daten: der Nutzer muss entscheiden. */
		ASK
	}

	/** Die Antwort des Nutzers auf {@link Action#ASK}. */
	public enum Decision {
		ADOPT_TARGET, WRITE_LOCAL, CANCEL
	}

	/** Der Nutzer hat das Einrichten abgebrochen; es wurde nichts geändert. */
	public static final class CancelledException extends RuntimeException {

		private static final long serialVersionUID = 1L;

		public CancelledException() {
			super("Das Einrichten des Spiegelordners wurde abgebrochen.");
		}
	}

	/** Anfang des Ordnernamens der Sicherung, die vor dem Übernehmen im lokalen Basisordner angelegt wird. */
	public static final String SAFETY_PREFIX = "vor-Übernahme-";

	private static final DateTimeFormatter SAFETY_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
	private static final String UNFINISHED_SUFFIX = ".unvollstaendig";
	private static final List<String> DATA_DIRS = List.of(MirrorSync.EVENTS, MirrorSync.HALLS);

	private MirrorSetup() {
	}

	/**
	 * Prüft den Spiegelordner und legt ihn bei Bedarf an. Nur hier wird der Basisordner angelegt, zur Laufzeit der
	 * Spiegelung nie. Leerzeichen am Rand zählen nicht; leere Eingabe meldet SET-002 (die Spiegelung auszuschalten
	 * ist Sache des Aufrufers).
	 *
	 * @return SET-002, wenn der Pfad unbrauchbar ist oder der Ordner sich nicht anlegen lässt oder kein Ordner ist;
	 *         SET-003, wenn sich darin keine Datei anlegen lässt
	 */
	public static ValidationResult check(String mirrorDir) {
		String trimmed = mirrorDir == null ? "" : mirrorDir.trim();
		if (trimmed.isEmpty()) {
			return ValidationResult.of(SettingsIssue.MIRROR_DIR_UNREACHABLE.toIssue(trimmed));
		}
		Path dir;
		try {
			dir = Path.of(trimmed);
			Files.createDirectories(dir);
		} catch (IOException | RuntimeException e) {
			// Ungültiger Pfad, Datei im Weg, Laufwerk fehlt oder kein Recht zum Anlegen
			return ValidationResult.of(SettingsIssue.MIRROR_DIR_UNREACHABLE.toIssue(trimmed));
		}
		if (!Files.isDirectory(dir)) {
			return ValidationResult.of(SettingsIssue.MIRROR_DIR_UNREACHABLE.toIssue(trimmed));
		}
		try {
			// Schreibprobe: Eine Datei anlegen und wieder löschen sagt mehr als Files.isWritable
			Path probe = Files.createTempFile(dir, "mirror-probe", ".tmp");
			Files.delete(probe);
		} catch (IOException | RuntimeException e) {
			return ValidationResult.of(SettingsIssue.MIRROR_DIR_NOT_WRITABLE.toIssue(trimmed));
		}
		return ValidationResult.ok();
	}

	/**
	 * Entscheidet anhand der Datenordner, was beim Einrichten zu tun ist. Ändert nichts.
	 * <ul>
	 * <li>Spiegelordner ohne Entitätsdateien: lokale Daten hineinschreiben ({@link Action#PUSH_LOCAL}), außer es
	 * gibt auch lokal keine ({@link Action#NOTHING}).</li>
	 * <li>Nur der Spiegelordner hat Entitätsdateien: {@link Action#ADOPT_TARGET}.</li>
	 * <li>Beide haben welche: gleich ({@link Action#NOTHING}) oder verschieden ({@link Action#ASK}).</li>
	 * </ul>
	 */
	public static Action decide(Path local, Path target) throws IOException {
		boolean localHasData = MirrorSync.hasEntityFiles(local);
		boolean targetHasData = MirrorSync.hasEntityFiles(target);
		if (!targetHasData) {
			return localHasData ? Action.PUSH_LOCAL : Action.NOTHING;
		}
		if (!localHasData) {
			return Action.ADOPT_TARGET;
		}
		return MirrorSync.compare(local, target).isEmpty() ? Action.NOTHING : Action.ASK;
	}

	/**
	 * Führt die Entscheidung aus. {@link Action#NOTHING} und {@link Action#ASK} tun nichts ({@code ASK} muss der
	 * Aufrufer vorher mit {@link #resolve} in {@code PUSH_LOCAL} oder {@code ADOPT_TARGET} umwandeln).
	 * <p>
	 * Reihenfolge bei {@link Action#ADOPT_TARGET}: erst die Sicherung der lokalen Daten nach
	 * {@code <local>/vor-Übernahme-<yyyy-MM-dd_HH-mm-ss>}, vollständig angelegt und nachgeprüft, erst danach wird
	 * {@code local} durch {@code target} ersetzt. Scheitert die Sicherung, bricht die Methode mit einer
	 * {@link IOException} ab, bevor lokal irgendetwas verändert wurde. Die Sicherung wird nie automatisch gelöscht;
	 * sie liegt neben {@code events_data} und {@code halls_data} und zählt bei späteren Vergleichen nicht als
	 * Datenbestand.
	 * <p>
	 * Gesichert wird alles, was in {@code events_data} und {@code halls_data} liegt und beim Übernehmen verschwinden
	 * würde, also auch Backups ohne eine einzige Entitätsdatei. Liegt lokal nichts, entsteht keine Sicherung.
	 *
	 * @param now Zeitpunkt für den Namen der Sicherung
	 */
	public static void execute(Action action, Path local, Path target, LocalDateTime now) throws IOException {
		switch (action) {
		case PUSH_LOCAL -> MirrorSync.copyAll(local, target);
		case ADOPT_TARGET -> {
			if (hasAnyData(local)) {
				backUpLocal(local, now);
			}
			MirrorSync.copyAll(target, local);
		}
		case NOTHING, ASK -> {
		}
		}
	}

	/** Übersetzt die Antwort des Nutzers in die auszuführende Aktion; {@link Decision#CANCEL} bricht ab. */
	public static Action resolve(Decision decision) {
		return switch (decision) {
		case ADOPT_TARGET -> Action.ADOPT_TARGET;
		case WRITE_LOCAL -> Action.PUSH_LOCAL;
		case CANCEL -> throw new CancelledException();
		};
	}

	// Ob in events_data oder halls_data irgendetwas liegt, das beim Übernehmen gelöscht würde (ohne *.tmp wie im Abgleich)
	private static boolean hasAnyData(Path root) throws IOException {
		for (String kind : DATA_DIRS) {
			Path dir = root.resolve(kind);
			if (!Files.isDirectory(dir)) {
				continue;
			}
			boolean[] found = { false };
			Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
					if (!file.getFileName().toString().endsWith(".tmp")) {
						found[0] = true;
						return FileVisitResult.TERMINATE;
					}
					return FileVisitResult.CONTINUE;
				}
			});
			if (found[0]) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Legt die Sicherung an: erst unter einem Namen mit {@value #UNFINISHED_SUFFIX} kopieren und mit dem lokalen
	 * Stand vergleichen, dann umbenennen. Der endgültige Name steht damit nur für eine vollständige Sicherung. Bei
	 * einem Fehler wird die unfertige Sicherung wieder entfernt.
	 */
	private static void backUpLocal(Path local, LocalDateTime now) throws IOException {
		Path safety = local.resolve(SAFETY_PREFIX + SAFETY_STAMP.format(now));
		if (Files.exists(safety, LinkOption.NOFOLLOW_LINKS)) {
			// Nie in eine vorhandene Sicherung schreiben: Der Abgleich würde ihren Inhalt löschen
			throw new FileAlreadyExistsException(safety.toString());
		}
		Path unfinished = local.resolve(safety.getFileName() + UNFINISHED_SUFFIX);
		deleteQuietly(unfinished);
		try {
			MirrorSync.copyAll(local, unfinished);
			if (!MirrorSync.compare(local, unfinished).isEmpty()) {
				throw new IOException("Die Sicherung " + unfinished + " ist unvollständig.");
			}
			Files.move(unfinished, safety, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException | RuntimeException e) {
			deleteQuietly(unfinished);
			throw e;
		}
	}

	// Räumt eine unfertige Sicherung weg; Fehler dabei sind unwichtig, die ursprüngliche Ausnahme zählt
	private static void deleteQuietly(Path path) {
		if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		try {
			Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					Files.delete(file);
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
					Files.delete(dir);
					return FileVisitResult.CONTINUE;
				}
			});
		} catch (IOException e) {
			// bewusst ignoriert
		}
	}
}
