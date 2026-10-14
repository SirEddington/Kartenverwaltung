package de.eltviller_carneval_verein.karten.repository;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

	private final File backupsDir;
	private final Clock clock;

	public BackupStore(File dataDir) {
		this(dataDir, Clock.systemDefaultZone());
	}

	/** Mit eigener Uhr, damit Tests die Zeit stellen können. */
	public BackupStore(File dataDir, Clock clock) {
		this.backupsDir = new File(dataDir, BACKUP_DIR_NAME);
		this.clock = clock;
	}

	/**
	 * Sichert die Datei vor dem Überschreiben, falls sie sich von der neuesten Kopie unterscheidet.
	 *
	 * @return {@code true}, wenn eine neue Kopie entstand; wirft nie
	 */
	public boolean backupBeforeOverwrite(String id, File currentFile) {
		try {
			if (!currentFile.isFile()) {
				return false;
			}
			List<Backup> existing = list(id);
			if (!existing.isEmpty() && Files.mismatch(currentFile.toPath(), existing.get(0).file()) == -1L) {
				return false;
			}
			Path idDir = new File(backupsDir, id).toPath();
			Files.createDirectories(idDir);
			Path target = freeTarget(idDir, LocalDateTime.now(clock));
			// Erst unter einem Namen kopieren, den list() nicht kennt: Ein Absturz mitten im Kopieren hinterlässt keine halbe "Kopie"
			Path partial = idDir.resolve(target.getFileName() + ".part");
			Files.copy(currentFile.toPath(), partial, StandardCopyOption.REPLACE_EXISTING);
			Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
			return true;
		} catch (IOException | RuntimeException e) {
			LOG.log(Level.SEVERE, "Backup von " + currentFile.getName() + " konnte nicht angelegt werden", e);
			return false;
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
		List<Backup> result = new ArrayList<>();
		if (files == null) {
			return result;
		}
		for (File file : files) {
			Matcher matcher = NAME_PATTERN.matcher(file.getName());
			if (matcher.matches()) {
				result.add(new Backup(id, file.toPath(), LocalDateTime.parse(matcher.group(1), NAME_FORMAT), file.length()));
			}
		}
		result.sort(Comparator.comparing(Backup::timestamp).thenComparingInt(BackupStore::counterOf).reversed());
		return result;
	}

	private static int counterOf(Backup backup) {
		Matcher matcher = NAME_PATTERN.matcher(backup.file().getFileName().toString());
		return matcher.matches() && matcher.group(2) != null ? Integer.parseInt(matcher.group(2)) : 1;
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
}
