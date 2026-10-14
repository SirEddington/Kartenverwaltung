package de.eltviller_carneval_verein.karten.repository;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Legt vor dem Überschreiben einer Event- oder Hallen-Datei zeitgestempelte Kopien an und verwaltet sie. Die Kopien
 * liegen im Unterordner {@code backups/<id>/} des Datenordners; die Ladeschicht der Repositories liest nur
 * {@code *.json} im Datenordner selbst und ignoriert ihn damit.
 *
 * <p>
 * Eine neue Kopie entsteht nur, wenn die Datei byteweise von der neuesten Kopie abweicht: Jeder tatsächlich
 * überschriebene Stand bleibt erhalten, identische Kopien entstehen nie. Ein gescheitertes Backup wird nur
 * geloggt, damit es das Speichern nicht verhindert.
 */
public final class BackupStore {

	private static final Logger LOG = Logger.getLogger(BackupStore.class.getName());

	private static final String BACKUP_DIR_NAME = "backups";
	/** Alle Kopien dieses Zeitraums bleiben erhalten. */
	private static final Duration KEEP_ALL = Duration.ofHours(24);
	/** Ältere Kopien werden gelöscht. */
	private static final Duration MAX_AGE = Duration.ofDays(90);
	/** Ab diesem Alter gilt eine {@code *.tmp} im Datenordner als verwaist. */
	private static final Duration STALE_TEMP_AGE = Duration.ofDays(1);
	/** Endung der früheren einzelnen Sicherung. */
	private static final String LEGACY_SUFFIX = ".json.bak";
	private static final DateTimeFormatter NAME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS");
	/** Dateiname einer Kopie: Zeitstempel, optional ein Zähler ab 2 bei gleicher Millisekunde. */
	private static final Pattern NAME_PATTERN = Pattern.compile("(\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}-\\d{3})(?:-(\\d+))?\\.json");

	/**
	 * Eine Kopie.
	 *
	 * @param entityId  ID des Events bzw. der Halle
	 * @param file      die Kopie
	 * @param timestamp Zeitpunkt des Backups (aus dem Dateinamen, lokale Zeit)
	 * @param size      Größe in Bytes
	 */
	public record Backup(String entityId, Path file, LocalDateTime timestamp, long size) {
	}

	private final File dataDir;
	private final File backupsDir;
	private final Clock clock;

	public BackupStore(File dataDir) {
		this(dataDir, Clock.systemDefaultZone());
	}

	/** Mit eigener Uhr, damit Tests die Zeit stellen können. */
	public BackupStore(File dataDir, Clock clock) {
		this.dataDir = dataDir;
		this.backupsDir = new File(dataDir, BACKUP_DIR_NAME);
		this.clock = clock;
	}

	/**
	 * Sichert die Datei vor dem Überschreiben, falls sie sich von der neuesten Kopie unterscheidet.
	 *
	 * @return {@code true}, wenn eine neue Kopie entstand; wirft nie
	 */
	public boolean backupBeforeOverwrite(String id, File currentFile) {
		return backup(id, currentFile) == Outcome.CREATED;
	}

	/**
	 * Stellt sicher, dass der aktuelle Stand der Datei gesichert ist: {@code true}, wenn es nichts zu sichern gab
	 * (Datei fehlt), sie schon der neuesten Kopie entspricht oder eine neue Kopie entstand; {@code false}, wenn das
	 * Sichern scheiterte. Für Fälle, in denen der aktuelle Stand nicht ohne Sicherung überschrieben werden darf
	 * (Wiederherstellen); wirft nie.
	 */
	public boolean ensureBackedUp(String id, File currentFile) {
		return backup(id, currentFile) != Outcome.FAILED;
	}

	/** Ergebnis eines Sicherungsversuchs. */
	private enum Outcome {
		NOT_NEEDED, CREATED, FAILED
	}

	private Outcome backup(String id, File currentFile) {
		try {
			if (!currentFile.isFile()) {
				return Outcome.NOT_NEEDED;
			}
			List<Backup> existing = list(id);
			if (!existing.isEmpty() && Files.mismatch(currentFile.toPath(), existing.get(0).file()) == -1L) {
				return Outcome.NOT_NEEDED;
			}
			Path idDir = new File(backupsDir, id).toPath();
			Files.createDirectories(idDir);
			Path target = freeTarget(idDir, LocalDateTime.now(clock));
			// Erst unter einem Namen kopieren, den list() nicht kennt: Ein Absturz mitten im Kopieren hinterlässt keine halbe "Kopie"
			Path partial = idDir.resolve(target.getFileName() + ".part");
			Files.copy(currentFile.toPath(), partial, StandardCopyOption.REPLACE_EXISTING);
			Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
			prune(id);
			return Outcome.CREATED;
		} catch (IOException | RuntimeException e) {
			LOG.log(Level.SEVERE, "Backup von " + currentFile.getName() + " konnte nicht angelegt werden", e);
			return Outcome.FAILED;
		}
	}

	private static Path freeTarget(Path idDir, LocalDateTime now) {
		String base = NAME_FORMAT.format(now);
		Path target = idDir.resolve(base + ".json");
		for (int counter = 2; Files.exists(target); counter++) {
			target = idDir.resolve(base + "-" + counter + ".json");
		}
		return target;
	}

	/** Die Kopien einer ID, neueste zuerst. Dateien mit anderem Namensmuster werden ignoriert. */
	public List<Backup> list(String id) {
		File[] files = new File(backupsDir, id).listFiles(File::isFile);
		if (files == null) {
			return new ArrayList<>();
		}
		List<Parsed> parsed = new ArrayList<>();
		for (File file : files) {
			Matcher matcher = NAME_PATTERN.matcher(file.getName());
			if (!matcher.matches()) {
				continue;
			}
			try {
				LocalDateTime timestamp = LocalDateTime.parse(matcher.group(1), NAME_FORMAT);
				int counter = matcher.group(2) == null ? 1 : Integer.parseInt(matcher.group(2));
				parsed.add(new Parsed(new Backup(id, file.toPath(), timestamp, file.length()), counter));
			} catch (DateTimeParseException | NumberFormatException e) {
				// Passt zum Muster, ist aber kein gültiger Zeitstempel (z. B. Monat 13): wie jede fremde Datei ignorieren
				LOG.log(Level.WARNING, "Datei im Backup-Ordner ignoriert: " + file);
			}
		}
		parsed.sort(Comparator.comparing((Parsed p) -> p.backup().timestamp()).thenComparingInt(Parsed::counter).reversed());
		return parsed.stream().map(Parsed::backup).collect(Collectors.toCollection(ArrayList::new));
	}

	/** Eine gelesene Kopie samt Zähler aus dem Dateinamen (1 ohne Zähler), nur zum Sortieren. */
	private record Parsed(Backup backup, int counter) {
	}

	/** Alle IDs, zu denen es einen Backup-Ordner gibt (auch von gelöschten Events und Hallen), alphabetisch. */
	public List<String> idsWithBackups() {
		File[] dirs = backupsDir.listFiles(File::isDirectory);
		if (dirs == null) {
			return List.of();
		}
		return Arrays.stream(dirs).map(File::getName).sorted().toList();
	}

	public byte[] read(Backup backup) throws IOException {
		return Files.readAllBytes(backup.file());
	}

	/**
	 * Räumt die Kopien einer ID auf: Alles der letzten 24 Stunden bleibt, aus jedem älteren Kalendertag nur die
	 * letzte Kopie, und was älter als 90 Tage ist, wird gelöscht. Zeitstempel in der Zukunft zählen als "letzte
	 * 24 Stunden". Wirft nie.
	 */
	public void prune(String id) {
		try {
			LocalDateTime now = LocalDateTime.now(clock);
			LocalDateTime recentCutoff = now.minus(KEEP_ALL);
			LocalDateTime maxAgeCutoff = now.minus(MAX_AGE);
			Set<LocalDate> daysKept = new HashSet<>();
			// Neueste zuerst: Die erste Kopie eines älteren Tages ist die letzte dieses Tages
			for (Backup backup : list(id)) {
				if (!backup.timestamp().isBefore(recentCutoff)) {
					continue;
				}
				if (backup.timestamp().isBefore(maxAgeCutoff) || !daysKept.add(backup.timestamp().toLocalDate())) {
					delete(backup);
				}
			}
		} catch (RuntimeException e) {
			LOG.log(Level.SEVERE, "Backups von " + id + " konnten nicht aufgeräumt werden", e);
		}
	}

	/** Räumt alle IDs auf, auch die von gelöschten Events und Hallen. */
	public void pruneAll() {
		idsWithBackups().forEach(this::prune);
	}

	/**
	 * Wartung beim App-Start: übernimmt alte {@code <id>.json.bak} als erste Kopie, entfernt verwaiste
	 * {@code *.tmp}-Reste von abgebrochenen Schreibvorgängen und räumt alle Backups auf. Jeder Schritt fängt seine
	 * Fehler selbst (Log), ein Fehler stoppt die anderen nicht.
	 */
	public void runStartupMaintenance() {
		adoptLegacyBackups();
		deleteStaleTempFiles();
		pruneAll();
	}

	/** Die früheren einzelnen {@code <id>.json.bak} (nicht {@code *.duplicate-*.bak}) wandern in den Backup-Ordner. */
	private void adoptLegacyBackups() {
		File[] legacyFiles = dataDir.listFiles((dir, name) -> name.endsWith(LEGACY_SUFFIX));
		if (legacyFiles == null) {
			return;
		}
		for (File legacy : legacyFiles) {
			String id = legacy.getName().substring(0, legacy.getName().length() - LEGACY_SUFFIX.length());
			try {
				Path idDir = new File(backupsDir, id).toPath();
				Files.createDirectories(idDir);
				LocalDateTime modified = LocalDateTime.ofInstant(Instant.ofEpochMilli(legacy.lastModified()), clock.getZone());
				Files.move(legacy.toPath(), freeTarget(idDir, modified));
			} catch (IOException | RuntimeException e) {
				LOG.log(Level.WARNING, "Alte Sicherung konnte nicht übernommen werden: " + legacy.getName(), e);
			}
		}
	}

	/** Die Repositories schreiben erst in eine {@code *.tmp}; nach einem Abbruch bleibt sie liegen und gilt ab einem Tag als verwaist. */
	private void deleteStaleTempFiles() {
		File[] tempFiles = dataDir.listFiles((dir, name) -> name.endsWith(".tmp"));
		if (tempFiles == null) {
			return;
		}
		long cutoff = clock.millis() - STALE_TEMP_AGE.toMillis();
		for (File tempFile : tempFiles) {
			if (tempFile.lastModified() < cutoff && !tempFile.delete()) {
				LOG.log(Level.WARNING, "Verwaiste Datei konnte nicht gelöscht werden: " + tempFile.getName());
			}
		}
	}

	private static void delete(Backup backup) {
		try {
			Files.deleteIfExists(backup.file());
		} catch (IOException e) {
			LOG.log(Level.WARNING, "Backup konnte nicht gelöscht werden: " + backup.file(), e);
		}
	}
}
