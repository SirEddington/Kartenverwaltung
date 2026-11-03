package de.eltviller_carneval_verein.karten.ui.sales;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.SeatStatus;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.validation.EntityLabels;

/**
 * Regeln und Texte zum Zuweisen eines Platzes, ohne JavaFX: welche Sitze frei sind, wie ein Sitz heißt und warum sich kein
 * Platz zuweisen lässt. Verkaufstabelle, Konflikt-Dialog und der Dialog "Platz zuweisen…" ({@link AssignPlaceDialog})
 * nutzen dieselben Texte (KARV-109, KARV-116).
 */
public final class PlaceAssignment {

	/** Der Grund, wenn die Bestuhlung keinen freien Sitz hat. */
	static final String NO_FREE_SEAT ="Kein Platz frei: In dieser Bestuhlung ist jeder Sitz belegt.";

	private PlaceAssignment() {
	}

	/**
	 * Warum sich der Buchung ohne Platz kein Sitz zuweisen lässt: Die Vorstellung hat keine Bestuhlung ({@link #noPlanText})
	 * oder kein Sitz ist frei ({@link #NO_FREE_SEAT}).
	 *
	 * @param plan die Bestuhlung der Vorstellung ({@code SalesPlan.of}), {@code null} ohne Bestuhlung
	 * @return der Grund; {@code null}, wenn ein Sitz frei ist
	 */
	public static String refusal(Presentation presentation, SeatingPlan plan) {
		if (plan == null) {
			return noPlanText(presentation);
		}
		Set<String> taken = takenSeatIds(presentation);
		return plan.getSeats().stream().allMatch(seat -> taken.contains(seat.getId())) ? NO_FREE_SEAT : null;
	}

	/** Der Grund, wenn die Vorstellung keine Bestuhlung hat, der eine Buchung zugewiesen werden könnte. */
	public static String noPlanText(Presentation presentation) {
		return EntityLabels.presentation(presentation) + " hat keine Bestuhlung; ein Platz lässt sich nicht zuweisen.";
	}

	/** Der Grund, wenn der gewählte Sitz inzwischen eine Buchung mit Daten hat. */
	public static String takenText(Seat seat) {
		return label(seat) + " ist inzwischen belegt. Wähle einen anderen Platz.";
	}

	/**
	 * Die freien Sitze der Bestuhlung, sortiert nach Reihenbuchstabe ({@link Booking#ROW_LABEL_ORDER}), Tisch- und
	 * Sitznummer. Frei ist ein Sitz, wenn keine Buchung der Vorstellung auf ihn zeigt, die reserviert oder bezahlt ist
	 * ({@link Booking#getStatus()}), wie in der Saalansicht. Reste einer alten Buchung ({@link Booking#hasLeftovers()})
	 * und ein bloßer Einzelpreis belegen ihn nicht (KARV-121).
	 */
	public static List<Seat> freeSeats(Presentation presentation, SeatingPlan plan) {
		if (plan == null) {
			return List.of();
		}
		Set<String> taken = takenSeatIds(presentation);
		Comparator<Seat> order = Comparator.comparing((Seat seat) -> seat.getParent().getRowLabel(), Booking.ROW_LABEL_ORDER)
				.thenComparingInt(seat -> seat.getParent().getTableNumber()).thenComparingInt(Seat::getSeatNumber);
		return plan.getSeats().stream().filter(seat -> !taken.contains(seat.getId())).sorted(order).toList();
	}

	/** Ob der Sitz frei ist wie bei {@link #freeSeats}, ohne alle freien Sitze zu sammeln und zu sortieren. */
	public static boolean isFree(Presentation presentation, Seat seat) {
		return !takenSeatIds(presentation).contains(seat.getId());
	}

	/** Die Sitze, auf die eine reservierte oder bezahlte Buchung der Vorstellung zeigt. */
	private static Set<String> takenSeatIds(Presentation presentation) {
		Set<String> taken = new HashSet<>();
		if (presentation != null && presentation.getBookings() != null) {
			for (Booking booking : presentation.getBookings()) {
				if (booking.getSeatId() != null && booking.getStatus() != SeatStatus.FREE) {
					taken.add(booking.getSeatId());
				}
			}
		}
		return taken;
	}

	/** "Tisch A3, Sitz 2" bzw. "Tisch 3, Sitz 2" ohne Reihenbuchstaben. */
	public static String label(Seat seat) {
		return seat.getParent().getDisplayName() + ", Sitz " + seat.getSeatNumber();
	}
}
