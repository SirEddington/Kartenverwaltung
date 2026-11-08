package de.eltviller_carneval_verein.karten.ui.sales;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.PaymentStatus;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.SeatStatus;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.model.Syncs;
import de.eltviller_carneval_verein.karten.model.Table;
import de.eltviller_carneval_verein.karten.model.TableRow;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.ui.LiveViews;

/** KARV-111: Ein Sitz, dessen Buchung gesperrt ist und frei wirkt, gilt in der Verkaufsansicht als gesperrt. */
class LockedSeatsTest {

	private final SeatingPlan plan = new Hall().addSeatingPlan();
	private final Event event = new Event();
	private final Presentation presentation = event.addPresentation("Samstag");
	private final Seat seat;
	private final Seat other;

	LockedSeatsTest() {
		TableRow row = plan.addRow();
		row.setLabel("A");
		Table table = plan.addTable(row);
		seat = table.addSeat(1);
		other = table.addSeat(2);
		presentation.setSeatingPlanId(plan.getId());
		Syncs.sync(presentation, plan);
	}

	@AfterEach
	void forget() {
		LockedSeats.reset();
	}

	private Booking bookingOf(Seat target) {
		return presentation.getBookings().stream().filter(b -> target.getId().equals(b.getSeatId())).findFirst().orElseThrow();
	}

	private UnitKey keyOf(Seat target) {
		return UnitKey.booking(event.getId(), presentation.getId(), target.getId());
	}

	private void lock(Seat target) {
		LockedSeats.install(() -> Set.of(keyOf(target)), () -> {
		});
	}

	@Test
	void emptyPlaceholderOfALockedUnitIsLocked() {
		lock(seat);

		assertTrue(LockedSeats.isLocked(bookingOf(seat)));
	}

	@Test
	void seatWithLeftoversOfALockedUnitIsLocked() {
		lock(seat);
		bookingOf(seat).setComment("storniert");

		assertTrue(LockedSeats.isLocked(bookingOf(seat)), "Reste lassen den Sitz frei wirken (KARV-121)");
	}

	@Test
	void soldOrReservedBookingOfALockedUnitIsNotLocked() {
		lock(seat);
		Booking booking = bookingOf(seat);
		booking.setLastName("Meier");
		assertFalse(LockedSeats.isLocked(booking), "reserviert: die ältere lesbare Version zeigt den Sitz belegt");

		booking.setLastName(null);
		booking.setPaymentStatus(PaymentStatus.CASH);
		assertFalse(LockedSeats.isLocked(booking), "bezahlt");
	}

	@Test
	void unassignedBookingIsNotLocked() {
		lock(seat);
		Booking booking = bookingOf(seat);
		booking.setUnassigned(true);

		assertFalse(LockedSeats.isLocked(booking));
	}

	@Test
	void placeholderOutsideTheListIsNotLocked() {
		lock(seat);

		assertFalse(LockedSeats.isLocked(bookingOf(other)));
	}

	@Test
	void bookingWithoutPresentationOrEventIsNotLocked() {
		lock(seat);
		Booking orphan = new Booking();
		orphan.setSeatId(seat.getId());
		assertFalse(LockedSeats.isLocked(orphan), "ohne Vorstellung");

		Booking withoutEvent = new Presentation().addBooking();
		withoutEvent.setSeatId(seat.getId());
		assertFalse(LockedSeats.isLocked(withoutEvent), "Vorstellung ohne Event");
	}

	@Test
	void nothingIsLockedWithoutInstall() {
		assertFalse(LockedSeats.isLocked(bookingOf(seat)));
	}

	@Test
	void unlockedAgainWhenTheListNoLongerHasTheUnit() {
		Set<UnitKey> locked = new HashSet<>(Set.of(keyOf(seat)));
		LockedSeats.install(() -> locked, () -> {
		});
		assertTrue(LockedSeats.isLocked(bookingOf(seat)));

		locked.remove(keyOf(seat));
		assertFalse(LockedSeats.isLocked(bookingOf(seat)), "nach dem Zurücksetzen fällt die Einheit aus der Liste");
	}

	@Test
	void openCallsTheInstalledAction() {
		AtomicInteger opened = new AtomicInteger();
		LockedSeats.install(Set::of, opened::incrementAndGet);

		LockedSeats.open();
		assertEquals(1, opened.get());

		LockedSeats.reset();
		assertDoesNotThrow(LockedSeats::open);
	}

	/**
	 * Die App meldet jede Sekunde (Takt des Abgleichs): Die Ansichten zeichnen nur neu, wenn sich die gesperrten Einheiten
	 * seit der letzten Meldung geändert haben, sonst baute der Saalplan jede Sekunde alle Sitze neu.
	 */
	@Test
	void changedNotifiesOnlyWhenTheLockedUnitsChanged() {
		Set<UnitKey> locked = new HashSet<>();
		LockedSeats.install(() -> locked, () -> {
		});
		AtomicInteger calls = new AtomicInteger();
		LockedSeats.addListener(calls::incrementAndGet);

		LockedSeats.changed();
		assertEquals(0, calls.get(), "nichts gesperrt, nichts geändert");

		locked.add(keyOf(seat));
		LockedSeats.changed();
		LockedSeats.changed();
		assertEquals(1, calls.get(), "eine Änderung, eine Meldung");

		locked.remove(keyOf(seat));
		LockedSeats.changed();
		assertEquals(2, calls.get(), "die Sperre ist weg");
	}

	@Test
	void changedCallsEveryListenerEvenIfOneThrows() {
		Set<UnitKey> locked = new HashSet<>();
		LockedSeats.install(() -> locked, () -> {
		});
		AtomicInteger calls = new AtomicInteger();
		Runnable second = calls::incrementAndGet;
		LockedSeats.addListener(() -> {
			throw new IllegalStateException("kaputt");
		});
		LockedSeats.addListener(second);

		locked.add(keyOf(seat));
		LockedSeats.changed();
		assertEquals(1, calls.get());

		LockedSeats.removeListener(second);
		locked.add(keyOf(other));
		LockedSeats.changed();
		assertEquals(1, calls.get());
	}

	@Test
	void whileShownAttachesAndDetachesTheListener() {
		Set<UnitKey> locked = new HashSet<>();
		LockedSeats.install(() -> locked, () -> {
		});
		AtomicInteger calls = new AtomicInteger();
		LiveViews.Registration registration = LockedSeats.whileShown(calls::incrementAndGet);

		registration.attach().run();
		locked.add(keyOf(seat));
		LockedSeats.changed();
		assertEquals(1, calls.get());

		registration.detach().run();
		locked.add(keyOf(other));
		LockedSeats.changed();
		assertEquals(1, calls.get());
	}

	/** Eine ID, die UnitKey ablehnt, kann zu keiner Einheit gehören: nicht gesperrt, und das Zeichnen läuft weiter. */
	@Test
	void seatIdThatNoUnitCanHaveIsNotLockedAndDoesNotThrow() {
		lock(seat);
		Booking booking = bookingOf(seat);
		booking.setSeatId("A1_1");

		assertFalse(assertDoesNotThrow(() -> LockedSeats.isLocked(booking)));
	}

	/** Ein gesperrter Sitz bleibt nicht in einer Auswahl (Saalplan, gemeinsamer Verkaufszustand). */
	@Test
	void dropFromRemovesLockedSeatsOnly() {
		lock(seat);
		Set<Booking> selection = new LinkedHashSet<>(List.of(bookingOf(seat), bookingOf(other)));

		LockedSeats.dropFrom(selection);

		assertEquals(Set.of(bookingOf(other)), selection);
	}

	@Test
	void blockedStatusIsShownAsGesperrt() {
		assertEquals("Gesperrt", SeatStatus.BLOCKED.getDisplayName());
	}
}
