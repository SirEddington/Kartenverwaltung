package de.eltviller_carneval_verein.karten.ui.sales;

import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.BookingMove;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.ui.StatusMessage;
import de.eltviller_carneval_verein.karten.validation.Severity;
import javafx.scene.control.MenuItem;
import javafx.stage.Window;

/**
 * Der Ablauf des Verschiebens von Buchungen (KARV-140) für Saalansicht und Kartentabelle: nachfragen, ausführen, die
 * Abholmarkierung zurücknehmen, melden und speichern. Die Regeln stehen in {@link BookingMove}, die Texte in
 * {@link MoveTexts}.
 */
final class MoveAction {

	/** Wie der Vorgang ausging. */
	enum Outcome {
		/** Verschoben. */
		DONE,
		/** Die Kasse hat abgebrochen; nichts hat sich geändert. */
		CANCELLED,
		/** Während der Rückfrage hat sich etwas geändert; nichts hat sich geändert. */
		STALE
	}

	private MoveAction() {
	}

	/** Beschriftet und sperrt den Eintrag: ohne Bearbeitungsmodus oder ohne belegende Buchung gesperrt. */
	static void prepare(MenuItem item, List<Booking> selection, boolean editMode) {
		item.setText(MoveTexts.menuText(selection));
		item.setDisable(MoveTexts.menuDisabled(selection, editMode));
	}

	/**
	 * Kern ohne Meldung und ohne Speichern: Antwort holen, ausführen, gegebenenfalls "abgeholt" zurücknehmen.
	 *
	 * @param shown        der Plan, den die Kasse bestätigt
	 * @param stillCurrent nach der Antwort gefragt: ob die Ansicht noch dieses Modell zeigt. Sie kann während der
	 *                     Rückfrage auf ein anderes Event oder eine andere Vorstellung gewechselt haben; das alte Modell
	 *                     ist dann abgehängt und würde nie gespeichert. Bei {@code false} ändert sich nichts
	 *                     ({@link Outcome#STALE}).
	 * @param answer       die Antwort auf die Rückfrage: leer bei "Abbrechen", sonst ob "Abgeholt" bleibt
	 */
	static Outcome execute(Presentation presentation, SeatingPlan plan, List<BookingMove.Source> sources,
			List<String> targetSeatIds, BookingMove.Plan shown, BooleanSupplier stillCurrent,
			Supplier<Optional<Boolean>> answer) {
		Optional<Boolean> keepCollected = answer.get();
		if (keepCollected.isEmpty()) {
			return Outcome.CANCELLED;
		}
		if (!stillCurrent.getAsBoolean()) {
			return Outcome.STALE;
		}
		if (!BookingMove.apply(presentation, plan, sources, targetSeatIds, LockedSeats.lockedSeatIds(presentation),
				shown)) {
			return Outcome.STALE;
		}
		if (!keepCollected.get()) {
			BookingMove.clearCollected(shown);
		}
		return Outcome.DONE;
	}

	/**
	 * Fragt mit dem Dialog nach, führt aus, meldet in der Fußzeile und speichert bei "Buchungen sofort speichern". Die
	 * Erfolgsmeldung kommt vor dem Speichern, damit eine Meldung des Speichers sie ersetzt und ein "Nicht gespeichert"
	 * sichtbar bleibt.
	 *
	 * @param stillCurrent ob die Ansicht nach der Rückfrage noch dieses Modell zeigt (siehe {@link #execute})
	 */
	static Outcome run(Presentation presentation, SeatingPlan plan, List<BookingMove.Source> sources,
			List<String> targetSeatIds, BookingMove.Plan shown, BooleanSupplier stillCurrent, Window owner) {
		Outcome outcome = execute(presentation, plan, sources, targetSeatIds, shown, stillCurrent,
				() -> MoveConfirmDialog.ask(shown, owner));
		if (outcome == Outcome.STALE) {
			StatusMessage.getInstance().show(Severity.ERROR, MoveTexts.STALE);
		} else if (outcome == Outcome.DONE) {
			StatusMessage.getInstance().show(Severity.SUCCESS, MoveTexts.success(shown));
			if (BookingAutoSave.settingOn()) {
				BookingAutoSave.flushActive();
			}
		}
		return outcome;
	}
}
