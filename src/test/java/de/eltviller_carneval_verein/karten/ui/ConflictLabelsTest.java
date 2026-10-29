package de.eltviller_carneval_verein.karten.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.model.TableRow;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.UnitType;
import de.eltviller_carneval_verein.karten.store.Version;
import de.eltviller_carneval_verein.karten.store.VersionId;

/** Die Anzeigenamen der Einheiten im Sammel-Dialog. */
class ConflictLabelsTest {

	private final Hall hall = new Hall();
	private final Event event = new Event();
	private final Presentation friday;
	private final Seat seat;
	private final Booking seatBooking;
	private final Booking unassigned;

	ConflictLabelsTest() {
		hall.setName("Stadthalle");
		SeatingPlan plan = hall.addSeatingPlan();
		TableRow row = plan.addRow();
		row.setLabel("A");
		plan.fillRow(row, 1, 2);
		seat = plan.getSeats().get(1);
		event.setName("Prunksitzung");
		friday = event.addPresentation("Freitag");
		friday.setHallId(hall.getId());
		friday.setSeatingPlanId(plan.getId());
		seatBooking = friday.addBooking();
		seatBooking.takeSnapshot(seat.getParent(), seat);
		unassigned = friday.addBooking();
		unassigned.setUnassigned(true);
		unassigned.setFirstName("Anna");
		unassigned.setLastName("Müller");
	}

	private Function<UnitKey, String> labels() {
		return ConflictLabels.of(id -> event.getId().equals(id) ? event : null, unit -> {
			if (unit.type() != UnitType.BOOKING) {
				return null;
			}
			if (unit.bookingDir().equals(seat.getId())) {
				return seatBooking;
			}
			return unit.bookingDir().equals("_" + unassigned.getId()) ? unassigned : null;
		}, id -> hall.getId().equals(id) ? hall : null);
	}

	@Test
	void eventAndPresentationAreNamed() {
		assertEquals("Prunksitzung", labels().apply(UnitKey.event(event.getId())));
		assertEquals("Prunksitzung › Freitag", labels().apply(UnitKey.presentation(event.getId(), friday.getId())));
	}

	@Test
	void hallIsNamedByItsName() {
		assertEquals("Stadthalle", labels().apply(UnitKey.hall(hall.getId())));
	}

	@Test
	void bookingOnASeatEndsWithThePlace() {
		assertEquals("Prunksitzung › Freitag › " + seatBooking.getPlaceLabel(),
				labels().apply(UnitKey.booking(event.getId(), friday.getId(), seat.getId())));
	}

	@Test
	void bookingWithoutPlaceEndsWithTheCustomer() {
		assertEquals("Prunksitzung › Freitag › Anna Müller",
				labels().apply(UnitKey.unassignedBooking(event.getId(), friday.getId(), unassigned.getId())));
	}

	@Test
	void unknownEventGivesNoLabel() {
		assertNull(labels().apply(UnitKey.event("gone")));
		assertNull(labels().apply(UnitKey.presentation("gone", "p1")));
		assertNull(labels().apply(UnitKey.hall("gone")));
	}

	@Test
	void deletedEventAndPresentationAreNamedByTheirLastVersion() {
		Function<UnitKey, String> former = unit -> switch (unit.type()) {
		case EVENT -> "Kappensitzung";
		case PRESENTATION -> "Vorstellung 4";
		default -> null;
		};
		Function<UnitKey, String> labels = ConflictLabels.of(id -> event.getId().equals(id) ? event : null, unit -> null,
				id -> null, former);

		assertEquals("Prunksitzung › Vorstellung 4", labels.apply(UnitKey.presentation(event.getId(), "gone")));
		assertEquals("Prunksitzung › Freitag", labels.apply(UnitKey.presentation(event.getId(), friday.getId())),
				"was das Modell kennt, geht vor");
		assertEquals("Kappensitzung", labels.apply(UnitKey.event("gone")));
		assertEquals("Kappensitzung › Vorstellung 4", labels.apply(UnitKey.presentation("gone", "p1")));
	}

	@Test
	void formerNameIsTheNameOfTheNewestLiveVersion() {
		UnitKey unit = UnitKey.presentation("ev", "pr");
		List<Version> history = List.of(version(1, false, "Vorstellung 3"), version(2, false, "Vorstellung 4"),
				version(3, true, null));

		assertEquals("Vorstellung 4", ConflictLabels.formerName(key -> history).apply(unit));
		assertNull(ConflictLabels.formerName(key -> List.of(version(1, true, null))).apply(unit), "nie mit Namen gespeichert");
		assertNull(ConflictLabels.formerName(key -> {
			throw new UncheckedIOException(new IOException("weg"));
		}).apply(unit), "unlesbar heißt: kein Name");
	}

	private static Version version(int n, boolean deleted, String name) {
		ObjectNode payload = deleted ? null : JsonNodeFactory.instance.objectNode().put("name", name);
		return new Version(1, VersionId.of(String.format("%011d-abcdefgh-0001", n)), List.of(), "m", "Kasse", null,
				Instant.ofEpochMilli(n), "save-" + n, deleted, payload);
	}

	@Test
	void unknownPresentationAndBookingFallBackToWhatIsKnown() {
		assertNull(labels().apply(UnitKey.presentation(event.getId(), "gone")));
		assertEquals("Prunksitzung › Freitag › (Kunde unbekannt)",
				labels().apply(UnitKey.unassignedBooking(event.getId(), friday.getId(), "other")));
		assertEquals("Prunksitzung › Freitag › (Platz unbekannt)",
				labels().apply(UnitKey.booking(event.getId(), friday.getId(), "other-seat")));
	}
}
