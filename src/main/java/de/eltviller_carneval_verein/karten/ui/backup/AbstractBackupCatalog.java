package de.eltviller_carneval_verein.karten.ui.backup;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import de.eltviller_carneval_verein.karten.repository.KnownEntity;
import de.eltviller_carneval_verein.karten.repository.LockedUnitException;
import de.eltviller_carneval_verein.karten.repository.Restored;
import de.eltviller_carneval_verein.karten.repository.SavePoint;

/**
 * Das Gemeinsame von {@link EventBackupCatalog} und {@link HallBackupCatalog}: Einträge und Speicherstände aus dem
 * Repository anzeigen und einen Speicherstand über das Repository wiederherstellen (das Repository frischt auf,
 * passt an, speichert und tauscht den Cache in einem Ablauf).
 *
 * @param <T> {@code Event} oder {@code Hall}
 */
abstract class AbstractBackupCatalog<T> implements BackupCatalog {

	private static final Logger LOG = Logger.getLogger(AbstractBackupCatalog.class.getName());
	/** Das Anzeigeformat eines Zeitpunkts in Rückfrage und Bestätigung. */
	private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");

	/** Stellt einen Speicherstand über das Repository wieder her: {@code StoreEventRepository::restoreEvent} bzw. {@code StoreHallRepository::restoreHall}. */
	@FunctionalInterface
	interface Restorer<T> {
		Restored<T> restore(String id, SavePoint point, Function<T, List<String>> adjust) throws IOException;
	}

	/** Liest die Speicherstände einer Entität; wirft {@link IOException}, wenn der Versionsspeicher nicht lesbar ist. */
	@FunctionalInterface
	interface SavePointLoader {
		List<SavePoint> load(String id) throws IOException;
	}

	private final String label;
	private final Supplier<List<Entry>> entries;
	private final Function<String, List<SavePoint>> savePoints;
	private final Restorer<T> restorer;
	private final Function<T, String> nameOf;

	/**
	 * @param entries    liefert die Einträge, alphabetisch nach Name (auch gelöschte)
	 * @param savePoints liefert die Speicherstände einer Entität, neueste zuerst (wirft {@link UncheckedIOException})
	 * @param restorer   stellt einen Speicherstand wieder her
	 * @param nameOf     der Name einer Entität für die Bestätigung
	 */
	AbstractBackupCatalog(String label, Supplier<List<Entry>> entries, Function<String, List<SavePoint>> savePoints, Restorer<T> restorer,
			Function<T, String> nameOf) {
		this.label = label;
		this.entries = entries;
		this.savePoints = savePoints;
		this.restorer = restorer;
		this.nameOf = nameOf;
	}

	/** Macht aus einem Leser, der {@link IOException} wirft, eine Funktion, die sie als {@link UncheckedIOException} weitergibt. */
	static Function<String, List<SavePoint>> unchecked(SavePointLoader loader) {
		return id -> {
			try {
				return loader.load(id);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		};
	}

	/** Der Anzeigeeintrag zu einer Entität, die das Repository kennt. */
	static Entry entry(KnownEntity known) {
		return new Entry(known.id(), known.name(), known.deleted());
	}

	/** Ein Zeitpunkt für die Anzeige in Rückfrage und Bestätigung. */
	static String timeText(Instant at, ZoneId zone) {
		return at.atZone(zone).format(TIME_FORMAT);
	}

	/**
	 * Passt die wiederhergestellte Entität vor dem Speichern an (z. B. nicht mehr vorhandene Verweise entfernen).
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
		return entries.get();
	}

	@Override
	public List<SavePoint> versions(String id) {
		return savePoints.apply(id);
	}

	@Override
	public RestoreResult restore(Entry entry, SavePoint point) {
		try {
			Restored<T> restored = restorer.restore(entry.id(), point, this::adjustForRestore);
			return RestoreResult.success(label + " \"" + nameOf.apply(restored.restored()) + "\" auf den Stand vom "
					+ timeText(point.at(), ZoneId.systemDefault()) + " zurückgesetzt.", restored.notes());
		} catch (LockedUnitException e) {
			return RestoreResult.failure(e.getMessage());
		} catch (IOException | RuntimeException e) {
			LOG.log(Level.WARNING, "Wiederherstellen von " + label + " " + entry.id() + " aus " + point.saveId() + " gescheitert", e);
			return RestoreResult.failure("Der Speicherstand konnte nicht wiederhergestellt werden: " + e.getMessage());
		}
	}
}
