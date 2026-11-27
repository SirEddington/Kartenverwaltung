package de.eltviller_carneval_verein.karten.ui.accounting;

import java.util.List;
import java.util.Map;

import de.eltviller_carneval_verein.karten.model.CashRegister;
import de.eltviller_carneval_verein.karten.model.CashRegisters;
import de.eltviller_carneval_verein.karten.model.CashRegisters.CashState;
import de.eltviller_carneval_verein.karten.model.CashRegisters.VisibleCashRegister;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.repository.LockedCashRegister;
import de.eltviller_carneval_verein.karten.repository.StoreEventRepository;
import de.eltviller_carneval_verein.karten.util.MoneyFormat;
import de.eltviller_carneval_verein.karten.validation.EntityLabels;

/**
 * Die Logik des Kassenabgleichs für Ist-Betrag und Kommentar (KARV-144), ohne JavaFX: was die Felder zeigen, ob sie sich
 * bearbeiten lassen, der Hinweis darunter und der zweite Ist-Betrag. Die Oberfläche dazu ist
 * {@link CashReconciliationController}. Die Regeln stehen im Spec „Kassen am Event", Abschnitt „Oberfläche:
 * Kassenabgleich".
 */
final class CashFigures {

	/** Der Hinweis, solange für das Event eine gemeinsame Kasse gilt (vorläufig bis KARV-146). */
	static final String SHARED_HINT = "Für dieses Event gilt eine gemeinsame Kasse. Der Kassensturz je Kasse folgt.";
	/** Der Hinweis der Gesamtbilanz. */
	static final String TOTAL_HINT = "Summe der Ist-Beträge aller Kassen des Events, hier nicht direkt bearbeitbar.";
	private static final String SECOND_AMOUNT = "Zweiter Ist-Betrag aus einer älteren Programmversion: ";

	/**
	 * Was Ist-Betrag und Kommentar zeigen.
	 *
	 * @param actualCents      der Ist-Betrag in Cent; {@code null} heißt „–" für Ist-Betrag und Differenz (und leer in CSV
	 *                         und PDF)
	 * @param comment          der Kommentar; {@code null} heißt leer
	 * @param editable         ob sich Ist-Betrag und Kommentar jetzt ändern lassen
	 * @param hint             der Hinweis unter dem Feld; {@code null} heißt keiner
	 * @param secondAmountHint der Hinweis auf einen zweiten Ist-Betrag; {@code null} heißt keiner
	 * @param discardable      ob „Verwerfen" für den zweiten Ist-Betrag angeboten wird
	 */
	record Figures(Long actualCents, String comment, boolean editable, String hint, String secondAmountHint, boolean discardable) {
	}

	private CashFigures() {
	}

	/**
	 * Ist-Betrag und Kommentar der Vorstellung, ohne Vorstellung die Gesamtbilanz. Im Zustand „Kassen noch nicht
	 * abgeglichen" zeigen beide „–". Die Vorstellung zeigt den wirksamen Stand ihrer Kasse ({@link CashRegisters#effective});
	 * gesperrt ist er, wenn ihre Kasse gesperrt ist oder wartet (ohne lesbaren Stand: „–") oder eine gemeinsame Kasse gilt
	 * (ohne Zählung: „–"). Der zweite Ist-Betrag und „Verwerfen" erscheinen auch dann. Die Gesamtbilanz ist die Summe über
	 * die sichtbaren Kassen ({@link CashRegisters#visible}) ohne die gesperrten ohne lesbaren Stand.
	 *
	 * @param presentation die gewählte Vorstellung; {@code null} für die Gesamtbilanz
	 * @param locked       die gesperrten und wartenden Kassen je Kennung ({@link StoreEventRepository#lockedCashRegisters})
	 * @param pending      ob „Kassen noch nicht abgeglichen" gilt ({@link StoreEventRepository#cashRegistersPending})
	 * @param editMode     ob die Ansicht im Bearbeiten-Modus ist
	 */
	static Figures of(Event event, Presentation presentation, Map<String, LockedCashRegister> locked, boolean pending, boolean editMode) {
		if (pending) {
			return new Figures(null, null, false, StoreEventRepository.CASH_PENDING, null, false);
		}
		if (presentation == null) {
			return new Figures(total(event, locked), null, false, TOTAL_HINT, null, false);
		}
		String second = secondAmountHint(event, presentation);
		boolean discardable = editMode && second != null;
		CashState state = CashRegisters.effective(event, presentation);
		Figures shown = new Figures((long) state.actualCashAmount(), state.comment(), editMode, null, second, discardable);
		Figures unknown = new Figures(null, null, false, null, second, discardable);
		LockedCashRegister lock = locked.get(presentation.getId());
		if (lock != null) {
			String hint = lock.message(EntityLabels.cashRegister(displayName(event, presentation)));
			return withHint(lock.readable() ? shown : unknown, hint);
		}
		if (sharedSelected(event, locked)) {
			// Eine vorher gezählte Vorstellungskasse zeigt ihren Stand, gesperrt
			return withHint(state.isEmpty() ? unknown : shown, SHARED_HINT);
		}
		return shown;
	}

	/** Gesperrt, mit dem Hinweis. */
	private static Figures withHint(Figures figures, String hint) {
		return new Figures(figures.actualCents(), figures.comment(), false, hint, figures.secondAmountHint(), figures.discardable());
	}

	/** Die Summe über die sichtbaren Kassen; eine gesperrte ohne lesbaren Stand zählt nicht, auch nicht mit alten Feldern. */
	private static long total(Event event, Map<String, LockedCashRegister> locked) {
		long sum = 0;
		for (VisibleCashRegister line : CashRegisters.visible(event)) {
			LockedCashRegister lock = locked.get(line.id());
			if (lock == null || lock.readable()) {
				sum += line.cash().actualCashAmount();
			}
		}
		return sum;
	}

	/**
	 * Wie in der Event-Verwaltung ({@code CashRegisterSection.sharedSelected}): Die gemeinsame Kasse gilt als gewählt, wenn
	 * sie im Modell steht oder gesperrt ist.
	 */
	private static boolean sharedSelected(Event event, Map<String, LockedCashRegister> locked) {
		return CashRegisters.sharedSelected(event) || locked.containsKey(CashRegister.SHARED_ID);
	}

	private static String displayName(Event event, Presentation presentation) {
		CashRegister register = event.findCashRegister(presentation.getId());
		return CashRegisters.displayName(event, register != null ? register : new CashRegister(presentation.getId()));
	}

	/** „Zweiter Ist-Betrag aus einer älteren Programmversion: <Betrag>", mit Kommentar dahinter; {@code null}, wenn keiner. */
	private static String secondAmountHint(Event event, Presentation presentation) {
		return CashRegisters.secondAmount(event, presentation).map(old -> SECOND_AMOUNT + MoneyFormat.formatCents(old.actualCashAmount())
				+ (old.comment() == null || old.comment().isBlank() ? "" : " („" + old.comment() + "“)")).orElse(null);
	}

	/** Die Vorstellungen, deren Verkäufe das Soll bilden: die gewählte, sonst alle des Events (ohne Vorstellung: keine). */
	static List<Presentation> scope(Event event, Presentation presentation) {
		if (presentation != null) {
			return List.of(presentation);
		}
		return event != null ? event.getPresentations() : List.of();
	}

	/**
	 * Ob die Ansicht etwas zu zeigen hat: ein Event mit einer Vorstellung oder einer sichtbaren Kasse. Ein Event ohne
	 * Vorstellung, aber mit sichtbarer Kasse zeigt die Gesamtbilanz mit einem Soll von 0.
	 */
	static boolean shown(Event event) {
		return event != null && (!event.getPresentations().isEmpty() || !CashRegisters.visible(event).isEmpty());
	}
}
