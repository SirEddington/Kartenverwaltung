package de.eltviller_carneval_verein.karten.ui.shared;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import de.eltviller_carneval_verein.karten.store.SkippedUnit;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.validation.MessageTemplate;

/**
 * Was das Info-Fenster zum Abgleich zeigt (Spec "Fußzeile und Info-Fenster"), als fertige Zeilen, ohne JavaFX.
 *
 * @param folderLine    "Ordner: …"
 * @param lastSyncLine  "Letzter Abgleich: …" bzw. "… noch keiner"
 * @param causeLine     "Ursache: …"; leer, wenn keine Ursache bekannt ist (das Fenster zeigt dann keine Zeile)
 * @param firstRunLine  "Erster Abgleich: vollständig / läuft (n %) / unvollständig"
 * @param rows          die nicht abgeglichenen Einträge in der Reihenfolge von {@code skippedUnits()}
 * @param listHint      unter der Liste: der Hinweis auf den nächsten Versuch, bei leerer Liste "Keine"
 */
public record SyncInfo(String folderLine, String lastSyncLine, String causeLine, String firstRunLine, List<Row> rows,
		String listHint) {

	private static final Logger LOG = Logger.getLogger(SyncInfo.class.getName());

	/**
	 * Eine Zeile der Liste.
	 *
	 * @param entry die lesbare Bezeichnung, bei Event oder Vorstellung mit dem Zusatz "mit allem darunter"
	 * @param cause die Ursache
	 */
	public record Row(String entry, String cause) {
	}

	/** Wie {@link #of(String, Instant, String, boolean, Double, List, Function, ZoneId)} in der Zeitzone des Rechners. */
	public static SyncInfo of(String dir, Instant lastSync, String cause, boolean complete, Double progress,
			List<SkippedUnit> skipped, Function<UnitKey, String> labels) {
		return of(dir, lastSync, cause, complete, progress, skipped, labels, ZoneId.systemDefault());
	}

	/**
	 * @param dir      der gemeinsame Ordner
	 * @param lastSync der letzte erfolgreiche Takt, {@code null} solange keiner gelang
	 * @param cause    die Ursache der Störung (Text von SYS-006), sonst {@code null}
	 * @param complete {@code connectionComplete()}
	 * @param progress {@code fullSyncProgress()}, {@code null} ohne laufenden Lauf
	 * @param skipped  {@code skippedUnits()}
	 * @param labels   die lesbare Bezeichnung einer Einheit ({@code ConflictLabels}); darf {@code null} liefern oder
	 *                 scheitern, dann gilt der Pfad der Einheit
	 */
	public static SyncInfo of(String dir, Instant lastSync, String cause, boolean complete, Double progress,
			List<SkippedUnit> skipped, Function<UnitKey, String> labels, ZoneId zone) {
		String folder = MessageTemplate.format(SharedFolderTexts.INFO_FOLDER, dir == null ? "" : dir);
		String last = MessageTemplate.format(SharedFolderTexts.INFO_LAST_SYNC,
				lastSync == null ? SharedFolderTexts.NO_SYNC_YET : SharedFolderTexts.syncTime(lastSync, zone));
		String causeLine = cause == null || cause.isBlank() ? ""
				: MessageTemplate.format(SharedFolderTexts.INFO_CAUSE, cause);
		String firstRun = complete ? SharedFolderTexts.INFO_FIRST_RUN_COMPLETE
				: progress == null ? SharedFolderTexts.INFO_FIRST_RUN_INCOMPLETE
						: MessageTemplate.format(SharedFolderTexts.INFO_FIRST_RUN_RUNNING,
								SharedFolderTexts.percent(progress));
		List<Row> rows = new ArrayList<>();
		for (SkippedUnit unit : skipped) {
			rows.add(new Row(SharedFolderTexts.withSubtree(label(labels, unit.unit()), unit.subtree()),
					unit.cause() == null ? "" : unit.cause()));
		}
		String hint = rows.isEmpty() ? SharedFolderTexts.INFO_LIST_EMPTY : SharedFolderTexts.INFO_LIST_HINT;
		return new SyncInfo(folder, last, causeLine, firstRun, List.copyOf(rows), hint);
	}

	/** Die Bezeichnung der Einheit; ihr Pfad, wenn sie sich nicht benennen lässt. */
	private static String label(Function<UnitKey, String> labels, UnitKey unit) {
		try {
			String label = labels.apply(unit);
			if (label != null) {
				return label;
			}
		} catch (RuntimeException e) {
			LOG.log(Level.FINE, "Keine Bezeichnung für " + unit.relativePath(), e);
		}
		return unit.relativePath();
	}
}
