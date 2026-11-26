package de.eltviller_carneval_verein.karten.ui.sales;

import java.util.List;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.BookingMove;
import de.eltviller_carneval_verein.karten.model.BookingMove.Step;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.validation.EntityLabels;

/**
 * Texte und Auswahl der Quellen beim Verschieben von Buchungen auf andere Plätze (KARV-140), ohne JavaFX. Saalansicht,
 * Kartentabelle und Bestätigungsdialog nutzen dieselben Texte.
 */
final class MoveTexts {

	static final String MULTIPLE_PRESENTATIONS = "Verschieben geht nur innerhalb einer Vorstellung. Wähle Buchungen einer Vorstellung.";
	static final String STALE = "Die Plätze haben sich inzwischen geändert. Nichts wurde verschoben, bitte neu wählen.";
	static final String NOTHING_TO_DO = "Die Buchungen sitzen schon auf diesen Plätzen.";
	static final String NO_NAME = "Buchung ohne Namen";

	private MoveTexts() {
	}

	/** Die belegenden Buchungen mit Platz, in Platzreihenfolge. */
	static List<Booking> movable(List<Booking> selection) {
		return selection.stream().filter(b -> b.getSeatId() != null && !b.isUnassigned() && b.isTaken())
				.sorted(Booking.PLACE_ORDER).toList();
	}

	/** "Buchung verschieben…", bei mehreren belegenden Buchungen "Ausgewählte Buchungen verschieben… (n)". */
	static String menuText(List<Booking> selection) {
		int count = movable(selection).size();
		return count <= 1 ? "Buchung verschieben…" : "Ausgewählte Buchungen verschieben… (" + count + ")";
	}

	/** Gesperrt ohne Bearbeitungsmodus und ohne belegende Buchung mit Platz. */
	static boolean menuDisabled(List<Booking> selection, boolean editMode) {
		return !editMode || movable(selection).isEmpty();
	}

	/** Text der Leiste (und der Platzliste) für die Quellen und die Zahl der gewählten Ziele. */
	static String barText(List<BookingMove.Source> sources, int chosen) {
		if (sources.size() == 1) {
			Booking booking = sources.get(0).booking();
			return "Ziel wählen für " + customerOf(booking) + " (" + booking.getPlaceLabel() + "): Platz anklicken.";
		}
		return "Ziele wählen für " + sources.size() + " Buchungen: " + chosen + " von " + sources.size()
				+ " Plätzen gewählt.";
	}

	static String tooMany(int n) {
		return "Es sind schon " + n + " Plätze gewählt. Wähle zuerst einen ab.";
	}

	static String locked(Seat seat) {
		return EntityLabels.placeOf(seat)
				+ " ist gesperrt (siehe „Gesperrte Einträge“) und lässt sich nicht verschieben oder belegen.";
	}

	/** "Buchung verschieben", bei mehr als einem Zug "Buchungen verschieben". */
	static String title(BookingMove.Plan plan) {
		return plan.steps().size() > 1 ? "Buchungen verschieben" : "Buchung verschieben";
	}

	/** Je Zug eine Zeile "Kunde: bisheriger Platz → neuer Platz", bei einem Gegenzug mit " (Tausch)". */
	static List<String> confirmLines(BookingMove.Plan plan) {
		return plan.steps().stream().map(step -> customerOf(step.booking()) + ": " + EntityLabels.placeOf(step.from())
				+ " → " + EntityLabels.placeOf(step.to()) + (step.counterMove() ? " (Tausch)" : "")).toList();
	}

	/** Die Zeile über der Frage nach der Abholmarkierung; {@code null}, wenn keine Buchung der Züge abgeholt ist. */
	static String collectedLine(BookingMove.Plan plan) {
		int collected = BookingMove.collectedCount(plan);
		if (collected == 0) {
			return null;
		}
		if (plan.steps().size() == 1) {
			return "Die Buchung ist abgeholt.";
		}
		return collected + " der Buchungen " + (collected == 1 ? "ist" : "sind") + " abgeholt.";
	}

	/** Erfolgsmeldung; sie richtet sich nach den Zügen des Plans, nicht nach der Zahl der Quellen. */
	static String success(BookingMove.Plan plan) {
		List<Step> steps = plan.steps();
		Step first = steps.get(0);
		if (steps.size() == 1) {
			return "Buchung von " + EntityLabels.placeOf(first.from()) + " auf " + EntityLabels.placeOf(first.to())
					+ " verschoben";
		}
		if (steps.size() == 2 && steps.get(1).counterMove()) {
			return "Buchungen auf " + EntityLabels.placeOf(first.from()) + " und " + EntityLabels.placeOf(first.to())
					+ " getauscht";
		}
		long counter = steps.stream().filter(Step::counterMove).count();
		String text = (steps.size() - counter) + " Buchungen verschoben";
		return counter == 0 ? text : text + ", " + counter + " weitere im Tausch";
	}

	/**
	 * Text der Ablehnung; "Anzahl" meldet wie "Auswahl veraltet" {@link #STALE}.
	 *
	 * @throws IllegalArgumentException bei einem ausführbaren Plan
	 */
	static String refusal(BookingMove.Plan plan, Presentation presentation) {
		if (!plan.refused()) {
			throw new IllegalArgumentException("Der Plan ist ausführbar.");
		}
		return switch (plan.refusal()) {
		case NO_PLAN -> PlaceAssignment.noPlanText(presentation);
		case STALE, COUNT -> STALE;
		case LOCKED -> locked(plan.lockedSeat());
		case NOTHING_TO_DO -> NOTHING_TO_DO;
		};
	}

	/** Zeile der Platzliste: "Platz – frei", "Platz – Kunde", bei einem Quellsitz mit " (wird verschoben)". */
	static String targetRow(Seat seat, Booking occupant, boolean sourceSeat) {
		String what = occupant == null ? "frei" : customerOf(occupant);
		return EntityLabels.placeOf(seat) + " – " + what + (sourceSeat ? " (wird verschoben)" : "");
	}

	private static String customerOf(Booking booking) {
		String customer = EntityLabels.customer(booking);
		return customer.isEmpty() ? NO_NAME : customer;
	}
}
