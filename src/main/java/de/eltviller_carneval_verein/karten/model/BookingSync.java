package de.eltviller_carneval_verein.karten.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Gleicht die {@link Booking Buchungen} einer {@link Presentation Vorstellung} mit einer {@link SeatingPlan
 * Bestuhlung} ab. Der Abgleich verliert nie eine Buchung mit Daten ({@link Booking#hasData()}) und ordnet einen Sitz
 * nie zwei Buchungen zu.
 *
 * <p>
 * Reihenfolge des Abgleichs:
 * <ol>
 * <li>Buchungen, deren {@code seatId} einen Sitz der Bestuhlung trifft: Die Momentaufnahme wird aufgefrischt.
 * Zeigen mehrere Buchungen auf denselben Sitz (nur bei beschädigten Daten), gewinnt die erste mit Daten, ohne solche
 * die erste in der Liste. Leere Verlierer werden entfernt, Verlierer mit Daten laufen weiter.
 * <li>Übrige Buchungen: exakter Treffer auf (Reihenbuchstabe, Tischnummer, Sitznummer) mit einem noch unbelegten
 * Sitz; die {@code seatId} wird neu gesetzt.
 * <li>Buchungen ohne Treffer: mit Daten bleiben sie als {@link Booking#isUnassigned() ohne Platz} erhalten, ohne
 * Daten (auch mit nur einem Einzelpreis) werden sie entfernt. Ein Verlierer aus Schritt 1 verliert dabei seine
 * {@code seatId} (die Momentaufnahme bleibt), damit nicht zwei Buchungen auf denselben Sitz zeigen.
 * <li>Sitze ohne Buchung bekommen eine leere Buchung mit Momentaufnahme.
 * </ol>
 * Ein zweiter Abgleich ändert nichts. Die Klasse hat nur statische Methoden.
 */
public final class BookingSync {

	/**
	 * Ergebnis eines Abgleichs.
	 *
	 * @param matched      Buchungen, die einem Sitz zugeordnet sind (über Id oder Momentaufnahme)
	 * @param created      neu angelegte leere Buchungen
	 * @param removedEmpty entfernte leere Buchungen ohne Treffer
	 * @param unassigned   Buchungen mit Daten ohne Platz
	 */
	public record Report(int matched, int created, int removedEmpty, int unassigned) {
	}

	private static final Report NOTHING = new Report(0, 0, 0, 0);

	private BookingSync() {
	}

	/**
	 * Gleicht die Buchungen der Vorstellung mit der Bestuhlung ab, auf die {@code seatingPlanId} zeigt.
	 *
	 * <p>
	 * Ohne {@code seatingPlanId} (auch leer, etwa nach einem Hallenwechsel) gibt es keine Plätze: Leere Buchungen
	 * werden entfernt, Buchungen mit Daten bleiben {@link Booking#isUnassigned() ohne Platz} erhalten, ihre
	 * {@code seatId} und Momentaufnahme bleiben unverändert ({@code planMissing} wird {@code false}). Wählt man die
	 * alte Bestuhlung wieder, findet der nächste Abgleich jede Buchung über ihre {@code seatId} und legt die leeren neu
	 * an. Ist die {@code seatingPlanId} gesetzt, die Bestuhlung aber {@code null} (nicht mehr in der Halle), wird
	 * {@code planMissing} gesetzt und nichts geändert: Die Buchungen bleiben, wie sie sind, auch ihre gespeicherte
	 * Markierung {@link Booking#isUnassigned() ohne Platz} aus dem letzten Abgleich mit der Bestuhlung. Mit Bestuhlung
	 * läuft der Abgleich wie in der Klassenbeschreibung; mehrere Buchungen auf demselben Sitz löst er auf.
	 *
	 * @param plan die Bestuhlung der Vorstellung oder {@code null}, wenn sie nicht gefunden wurde
	 */
	public static Report sync(Presentation p, SeatingPlan plan) {
		if (p.getSeatingPlanId() == null || p.getSeatingPlanId().isBlank()) {
			p.setPlanMissing(false);
			return withoutPlan(p.getBookings());
		}
		if (plan == null) {
			p.setPlanMissing(true);
			return NOTHING;
		}
		p.setPlanMissing(false);

		List<Booking> bookings = p.getBookings();
		boolean replaced = false;
		if (bookings == null) {
			bookings = new ArrayList<>();
			replaced = true;
		}
		Report report = reconcile(bookings, plan, p);
		if (replaced) {
			p.setBookings(bookings);
		}
		return report;
	}

	/** Weist der Vorstellung die Bestuhlung zu und gleicht dann ab ({@link #sync}). */
	public static Report switchPlan(Presentation p, SeatingPlan plan) {
		p.setSeatingPlanId(plan != null ? plan.getId() : null);
		return sync(p, plan);
	}

	/**
	 * Rechnet den Wechsel auf die Bestuhlung durch, ohne die Vorstellung zu verändern (der Abgleich läuft auf Kopien
	 * der Buchungen). Der Report ist derselbe wie bei {@link #switchPlan}.
	 */
	public static Report preview(Presentation p, SeatingPlan plan) {
		if (plan == null) {
			return NOTHING;
		}
		List<Booking> copies = new ArrayList<>();
		if (p.getBookings() != null) {
			for (Booking booking : p.getBookings()) {
				copies.add(booking.copy());
			}
		}
		return reconcile(copies, plan, null);
	}

	/**
	 * Ordnet die Buchung dem Sitz zu (zum Beispiel eine Buchung ohne Platz). Hat die bisherige Buchung des Sitzes
	 * Daten, passiert nichts und das Ergebnis ist {@code false}; sonst wird diese leere Buchung entfernt und
	 * {@code booking} bekommt die Momentaufnahme des Sitzes und {@code unassigned = false}.
	 *
	 * @return {@code true}, wenn die Buchung dem Sitz zugeordnet wurde
	 */
	public static boolean assign(Presentation p, SeatingPlan plan, Booking booking, Seat seat) {
		if (plan == null || booking == null || seat == null || plan.findSeat(seat.getId()) != seat) {
			return false;
		}
		List<Booking> others = new ArrayList<>();
		for (Booking other : p.getBookings()) {
			if (other != booking && seat.getId().equals(other.getSeatId())) {
				if (other.hasData()) {
					return false;
				}
				others.add(other);
			}
		}
		p.getBookings().removeAll(others);
		booking.takeSnapshot(seat.getParent(), seat);
		booking.setUnassigned(false);
		return true;
	}

	/** Abgleich ohne Bestuhlung: leere Buchungen entfernen, Buchungen mit Daten ohne Platz behalten. */
	private static Report withoutPlan(List<Booking> bookings) {
		if (bookings == null) {
			return NOTHING;
		}
		int removedEmpty = 0;
		int unassigned = 0;
		for (Iterator<Booking> iterator = bookings.iterator(); iterator.hasNext();) {
			Booking booking = iterator.next();
			if (booking.hasData()) {
				booking.setUnassigned(true);
				unassigned++;
			} else {
				iterator.remove();
				removedEmpty++;
			}
		}
		return new Report(0, 0, removedEmpty, unassigned);
	}

	/**
	 * Der eigentliche Abgleich auf einer Buchungsliste (Schritte siehe Klassenbeschreibung). Doppelte Sitzkennungen
	 * werden aufgelöst: Danach zeigt höchstens eine Buchung auf jeden Sitz der Bestuhlung.
	 *
	 * @param owner die Vorstellung für neue Buchungen (setzt deren Elternteil); {@code null} bei der Vorschau
	 */
	private static Report reconcile(List<Booking> bookings, SeatingPlan plan, Presentation owner) {
		List<Seat> seats = plan.getSeats();
		Map<String, Seat> seatsById = new HashMap<>();
		Map<String, List<Seat>> seatsByKey = new HashMap<>();
		for (Seat seat : seats) {
			seatsById.putIfAbsent(seat.getId(), seat);
			seatsByKey.computeIfAbsent(key(seat), k -> new ArrayList<>()).add(seat);
		}

		// (1) auflösbare seatId: je Sitz gewinnt die erste Buchung mit Daten, ohne solche die erste überhaupt
		Map<Seat, Booking> winners = new HashMap<>();
		for (Booking booking : bookings) {
			Seat seat = resolve(booking, seatsById);
			if (seat != null) {
				Booking winner = winners.get(seat);
				if (winner == null || (!winner.hasData() && booking.hasData())) {
					winners.put(seat, booking);
				}
			}
		}
		Set<Seat> taken = new HashSet<>();
		List<Booking> leftovers = new ArrayList<>();
		int matched = 0;
		int removedEmpty = 0;
		for (Iterator<Booking> iterator = bookings.iterator(); iterator.hasNext();) {
			Booking booking = iterator.next();
			Seat seat = resolve(booking, seatsById);
			if (seat != null && winners.get(seat) == booking) {
				taken.add(seat);
				booking.takeSnapshot(seat.getParent(), seat);
				booking.setUnassigned(false);
				matched++;
			} else if (seat != null && !booking.hasData()) {
				// leerer Verlierer auf einem Sitz: es gehen keine Daten verloren
				iterator.remove();
				removedEmpty++;
			} else {
				leftovers.add(booking);
			}
		}

		// (2) übrige Buchungen: exakter Treffer auf einen noch unbelegten Sitz
		List<Booking> unmatched = new ArrayList<>();
		for (Booking booking : leftovers) {
			Seat seat = firstFree(seatsByKey.get(key(booking)), taken);
			if (seat != null) {
				taken.add(seat);
				booking.takeSnapshot(seat.getParent(), seat);
				booking.setUnassigned(false);
				matched++;
			} else {
				unmatched.add(booking);
			}
		}

		// (3) ohne Treffer: mit Daten ohne Platz behalten, sonst entfernen
		int unassigned = 0;
		for (Booking booking : unmatched) {
			if (booking.hasData()) {
				if (resolve(booking, seatsById) != null) {
					// Verlierer auf einem Sitz, den eine andere Buchung trägt: die Sitzkennung entfällt (sonst SEA-011)
					booking.setSeatId(null);
				}
				booking.setUnassigned(true);
				unassigned++;
			} else {
				bookings.remove(booking);
				removedEmpty++;
			}
		}

		// (4) Sitze ohne Buchung
		int created = 0;
		for (Seat seat : seats) {
			if (taken.add(seat)) {
				Booking booking = new Booking();
				booking.setParent(owner);
				booking.takeSnapshot(seat.getParent(), seat);
				bookings.add(booking);
				created++;
			}
		}
		return new Report(matched, created, removedEmpty, unassigned);
	}

	/** Der Sitz der Bestuhlung, auf den die {@code seatId} der Buchung zeigt, sonst {@code null}. */
	private static Seat resolve(Booking booking, Map<String, Seat> seatsById) {
		return booking.getSeatId() != null ? seatsById.get(booking.getSeatId()) : null;
	}

	private static Seat firstFree(List<Seat> candidates, Set<Seat> taken) {
		if (candidates == null) {
			return null;
		}
		for (Seat candidate : candidates) {
			if (!taken.contains(candidate)) {
				return candidate;
			}
		}
		return null;
	}

	/** Schlüssel eines Sitzes aus Reihenbuchstabe, Tisch- und Sitznummer. */
	private static String key(Seat seat) {
		Table table = seat.getParent();
		return key(table.getRowLabel(), table.getTableNumber(), seat.getSeatNumber());
	}

	/** Schlüssel der Momentaufnahme einer Buchung. */
	private static String key(Booking booking) {
		return key(booking.getRowLabel(), booking.getTableNumber(), booking.getSeatNumber());
	}

	/** Ein fehlender oder leerer Reihenbuchstabe zählt als ""; die Schreibweise wird exakt verglichen. */
	private static String key(String rowLabel, int tableNumber, int seatNumber) {
		String label = rowLabel != null ? rowLabel.trim() : "";
		return label + "|" + tableNumber + "|" + seatNumber;
	}
}
