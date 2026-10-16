package de.eltviller_carneval_verein.karten.ui.event;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.PaymentStatus;
import de.eltviller_carneval_verein.karten.model.Presentation;

/**
 * Die Buchungen einer Vorstellung für den Baum der Event-Übersicht: gruppiert nach Tisch (aus der Momentaufnahme der
 * Buchung, die Bestuhlung wird nicht gebraucht) und die Buchungen ohne Platz. Ohne JavaFX prüfbar; funktioniert auch
 * für Vorstellungen, deren Bestuhlung es nicht mehr gibt.
 *
 * <p>
 * Die Knoten des Baums sind {@code Event}, {@code Presentation}, {@link TableGroup} (ein Tisch mit Platz),
 * {@link UnassignedGroup} (der Knoten "Ohne Platz" einer Vorstellung) und {@link Booking}.
 */
final class BookingTree {

	/** Text des Knotens der Buchungen ohne Platz. */
	static final String UNASSIGNED_TEXT = "Ohne Platz";

	private BookingTree() {
	}

	/**
	 * Ein Tisch mit seinen Buchungen (Sitzen) in Reihenfolge der Plätze.
	 *
	 * @param label    "Tisch A3" bzw. "Tisch 3"
	 * @param bookings die Buchungen des Tisches
	 */
	record TableGroup(String label, List<Booking> bookings) {
	}

	/**
	 * Der Knoten "Ohne Platz" einer Vorstellung. Er trägt die Vorstellung, damit Bearbeiten und Hallen-Editor ihn wie
	 * einen Tisch behandeln können.
	 *
	 * @param presentation die Vorstellung, zu der die Buchungen gehören
	 * @param bookings     ihre Buchungen ohne Platz
	 */
	record UnassignedGroup(Presentation presentation, List<Booking> bookings) {
		String label() {
			return UNASSIGNED_TEXT;
		}
	}

	/**
	 * Die Buchungen mit Platz, gruppiert nach (Reihenbuchstabe, Tischnummer) in der Reihenfolge
	 * {@link Booking#PLACE_ORDER}; die Bezeichnung ist {@link Booking#getTableLabel()}. Buchungen ohne Platz sind nicht
	 * dabei.
	 */
	static List<TableGroup> groups(Presentation p) {
		Map<String, List<Booking>> byTable = new LinkedHashMap<>();
		for (Booking booking : sorted(p)) {
			if (!booking.isUnassigned()) {
				byTable.computeIfAbsent(booking.getTableLabel(), label -> new ArrayList<>()).add(booking);
			}
		}
		List<TableGroup> groups = new ArrayList<>();
		byTable.forEach((label, bookings) -> groups.add(new TableGroup(label, bookings)));
		return groups;
	}

	/** Die Buchungen ohne Platz in der Reihenfolge ihres bisherigen Platzes. */
	static List<Booking> unassigned(Presentation p) {
		return sorted(p).stream().filter(Booking::isUnassigned).toList();
	}

	/** Anzeigetext einer Buchung: "Sitz 2", ohne Platz "Ohne Platz (bisher Tisch A3, Sitz 2)". */
	static String bookingLabel(Booking booking) {
		return booking.isUnassigned() ? UNASSIGNED_TEXT + " (bisher " + booking.getPlaceLabel() + ")" : "Sitz " + booking.getSeatNumber();
	}

	/** Suchtext einer Buchung: Sitz, Vor- und Nachname, Kommentar (fehlende Teile bleiben weg, nie das Wort "null"). */
	static String bookingSearchText(Booking booking) {
		StringBuilder text = new StringBuilder("Sitz ").append(booking.getSeatNumber());
		for (String part : new String[] { booking.getFirstName(), booking.getLastName(), booking.getComment() }) {
			if (part != null) {
				text.append(' ').append(part);
			}
		}
		return text.toString();
	}

	/** Status einer Buchung wie bisher: "Bezahlt (Bar)", "Reserviert", "Frei", dazu " und abgeholt". */
	static String statusText(Booking booking) {
		String status = switch (Objects.requireNonNullElse(booking.getPaymentStatus(), PaymentStatus.NONE)) {
		case CASH -> "Bezahlt (Bar)";
		case CARD -> "Bezahlt (Karte)";
		case TRANSFER -> "Bezahlt (Überweisung)";
		case NONE -> booking.isReserved() ? "Reserviert" : "Frei";
		};
		return booking.isCollected() ? status + " und abgeholt" : status;
	}

	private static List<Booking> sorted(Presentation p) {
		if (p.getBookings() == null) {
			return List.of();
		}
		return p.getBookings().stream().sorted(Booking.PLACE_ORDER).toList();
	}
}
