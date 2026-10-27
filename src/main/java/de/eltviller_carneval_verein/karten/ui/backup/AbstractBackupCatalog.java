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
import de.eltviller_carneval_verein.karten.repository.RestorePlan;
import de.eltviller_carneval_verein.karten.repository.Restored;
import de.eltviller_carneval_verein.karten.repository.SavePoint;

/**
 * Das Gemeinsame von {@link EventBackupCatalog} und {@link HallBackupCatalog}: Einträge und Speicherstände aus dem
 * Repository anzeigen und einen Speicherstand über das Repository wiederherstellen, in zwei Schritten: {@link #prepare}
 * liest und setzt den Zielstand zusammen (Hintergrund-Thread), {@link #commit} frischt auf, passt an, speichert und
 * tauscht den Cache (Oberflächen-Thread).
 *
 * @param <T> {@code Event} oder {@code Hall}
 */
abstract class AbstractBackupCatalog<T> implements BackupCatalog {

	private static final Logger LOG = Logger.getLogger(AbstractBackupCatalog.class.getName());
	/** Das Anzeigeformat eines Zeitpunkts in Rückfrage und Bestätigung. */
	private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");

	/** Bereitet das Wiederherstellen über das Repository vor: {@code StoreEventRepository::prepareRestoreEvent} bzw. {@code StoreHallRepository::prepareRestoreHall}. */
	@FunctionalInterface
	interface Preparer<T> {
		RestorePlan<T> prepare(String id, SavePoint point) throws IOException;
	}

	/** Übernimmt das Vorbereitete über das Repository: {@code StoreEventRepository::commitRestore} bzw. {@code StoreHallRepository::commitRestore}. */
	@FunctionalInterface
	interface Committer<T> {
		Restored<T> commit(RestorePlan<T> plan, Function<T, List<String>> adjust);
	}

	/** Das Vorbereitete samt Eintrag und Speicherstand; bei gescheiterter Vorbereitung statt des Plans der Fehler. */
	private record PreparedRestore<T>(Entry entry, SavePoint point, RestorePlan<T> plan, RestoreResult failure)
			implements Prepared {
	}

	/** Liest die Speicherstände einer Entität; wirft {@link IOException}, wenn der Versionsspeicher nicht lesbar ist. */
	@FunctionalInterface
	interface SavePointLoader {
		List<SavePoint> load(String id) throws IOException;
	}

	private final String label;
	private final Supplier<List<Entry>> entries;
	private final Function<String, List<SavePoint>> savePoints;
	private final Preparer<T> preparer;
	private final Committer<T> committer;
	private final Function<T, String> nameOf;

	/**
	 * @param entries    liefert die Einträge, alphabetisch nach Name (auch gelöschte)
	 * @param savePoints liefert die Speicherstände einer Entität, neueste zuerst (wirft {@link UncheckedIOException})
	 * @param preparer   bereitet das Wiederherstellen eines Speicherstands vor (Hintergrund-Thread)
	 * @param committer  übernimmt das Vorbereitete (Oberflächen-Thread)
	 * @param nameOf     der Name einer Entität für die Bestätigung
	 */
	AbstractBackupCatalog(String label, Supplier<List<Entry>> entries, Function<String, List<SavePoint>> savePoints, Preparer<T> preparer,
			Committer<T> committer, Function<T, String> nameOf) {
		this.label = label;
		this.entries = entries;
		this.savePoints = savePoints;
		this.preparer = preparer;
		this.committer = committer;
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
	public Prepared prepare(Entry entry, SavePoint point) {
		try {
			return new PreparedRestore<>(entry, point, preparer.prepare(entry.id(), point), null);
		} catch (IOException | RuntimeException e) {
			return new PreparedRestore<T>(entry, point, null, failure(entry, point, e));
		}
	}

	@Override
	@SuppressWarnings("unchecked")
	public RestoreResult commit(Prepared prepared) {
		PreparedRestore<T> restore = (PreparedRestore<T>) prepared;
		if (restore.failure() != null) {
			return restore.failure();
		}
		try {
			Restored<T> restored = committer.commit(restore.plan(), this::adjustForRestore);
			return RestoreResult.success(label + " \"" + nameOf.apply(restored.restored()) + "\" auf den Stand vom "
					+ timeText(restore.point().at(), ZoneId.systemDefault()) + " zurückgesetzt.", restored.notes());
		} catch (RuntimeException e) {
			return failure(restore.entry(), restore.point(), e);
		}
	}

	/** Das Ergebnis eines gescheiterten Schritts: Der Text einer Sperre bleibt, alles andere bekommt den allgemeinen Vorspann. */
	private RestoreResult failure(Entry entry, SavePoint point, Exception failure) {
		if (failure instanceof LockedUnitException) {
			return RestoreResult.failure(failure.getMessage());
		}
		LOG.log(Level.WARNING, "Wiederherstellen von " + label + " " + entry.id() + " aus " + point.saveId() + " gescheitert", failure);
		return RestoreResult.failure("Der Speicherstand konnte nicht wiederhergestellt werden: " + failure.getMessage());
	}
}
