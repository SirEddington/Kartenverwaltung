package de.eltviller_carneval_verein.karten.ui.backup;

import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.eltviller_carneval_verein.karten.repository.BackupStore;
import de.eltviller_carneval_verein.karten.repository.BackupStore.Backup;
import de.eltviller_carneval_verein.karten.repository.JsonMapperFactory;

/**
 * Das Gemeinsame von {@link EventBackupCatalog} und {@link HallBackupCatalog}: Einträge aus den Backup-Ordnern
 * und dem aktuellen Bestand bilden, eine Sicherung lesen, speichern und erst danach den Cache austauschen.
 *
 * @param <T> {@code Event} oder {@code Hall}
 */
abstract class AbstractBackupCatalog<T> implements BackupCatalog {

	private static final Logger LOG = Logger.getLogger(AbstractBackupCatalog.class.getName());
	private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

	private final BackupStore store;
	private final Class<T> type;
	private final String label;
	private final Supplier<List<T>> currentEntities;
	private final Consumer<T> save;
	private final Consumer<T> replaceInCache;
	private final ObjectMapper mapper = JsonMapperFactory.create();

	/**
	 * @param save           speichert die wiederhergestellte Entität (wirft bei Fehlern)
	 * @param replaceInCache tauscht das Objekt im Repository-Cache aus; wird erst nach erfolgreichem Speichern aufgerufen
	 */
	AbstractBackupCatalog(BackupStore store, Class<T> type, String label, Supplier<List<T>> currentEntities, Consumer<T> save, Consumer<T> replaceInCache) {
		this.store = store;
		this.type = type;
		this.label = label;
		this.currentEntities = currentEntities;
		this.save = save;
		this.replaceInCache = replaceInCache;
	}

	abstract String idOf(T entity);

	abstract String nameOf(T entity);

	/**
	 * Passt die gelesene Entität vor dem Speichern an (z. B. nicht mehr vorhandene Verweise entfernen).
	 *
	 * @return Hinweise für den Nutzer, leer wenn nichts geändert wurde
	 */
	List<String> adjustForRestore(T entity) {
		return List.of();
	}

	@Override
	public String entityLabel() {
		return label;
	}

	@Override
	public List<Entry> entries() {
		Map<String, T> currentById = new HashMap<>();
		for (T entity : currentEntities.get()) {
			currentById.put(idOf(entity), entity);
		}
		List<Entry> entries = new ArrayList<>();
		for (String id : store.idsWithBackups()) {
			T existing = currentById.get(id);
			String name = existing != null ? nameOf(existing) : nameFromNewestCopy(id);
			entries.add(new Entry(id, name == null || name.isBlank() ? id : name, existing == null));
		}
		entries.sort(Comparator.comparing(entry -> entry.name().toLowerCase()));
		return entries;
	}

	private String nameFromNewestCopy(String id) {
		List<Backup> backups = store.list(id);
		if (backups.isEmpty()) {
			return null;
		}
		try {
			return nameOf(mapper.readValue(store.read(backups.get(0)), type));
		} catch (IOException | RuntimeException e) {
			LOG.log(Level.WARNING, "Name aus der Sicherung " + backups.get(0).file().getFileName() + " nicht lesbar", e);
			return null;
		}
	}

	@Override
	public List<Backup> versions(String id) {
		return store.list(id);
	}

	@Override
	public RestoreResult restore(Entry entry, Backup backup) {
		String fileName = backup.file().getFileName().toString();
		T restored;
		try {
			restored = mapper.readValue(store.read(backup), type);
		} catch (IOException | RuntimeException e) {
			LOG.log(Level.WARNING, "Sicherung nicht lesbar: " + backup.file(), e);
			return RestoreResult.failure("Die Sicherung " + fileName + " konnte nicht gelesen werden: " + e.getMessage());
		}
		if (restored == null || !entry.id().equals(idOf(restored))) {
			return RestoreResult.failure("Die Sicherung " + fileName + " gehört nicht zu \"" + entry.name() + "\".");
		}

		List<String> notes = adjustForRestore(restored);
		try {
			save.accept(restored);
		} catch (RuntimeException e) {
			LOG.log(Level.SEVERE, "Wiederherstellen aus " + backup.file() + " konnte nicht gespeichert werden", e);
			return RestoreResult.failure("Die Sicherung " + fileName + " konnte nicht gespeichert werden: " + e.getMessage());
		}
		replaceInCache.accept(restored);
		return RestoreResult.success(label + " \"" + nameOf(restored) + "\" auf den Stand vom " + backup.timestamp().format(TIME_FORMAT) + " zurückgesetzt.", notes);
	}
}
