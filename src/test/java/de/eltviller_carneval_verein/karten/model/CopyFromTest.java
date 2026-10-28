package de.eltviller_carneval_verein.karten.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.eltviller_carneval_verein.karten.repository.JsonMapperFactory;

/**
 * {@code copyFrom} übernimmt jedes gespeicherte Feld eines fremden Stands in ein vorhandenes Objekt, ohne Kinderlisten,
 * Elternverweis und Laufzeitmarken anzufassen; {@code clearCustomerData} leert eine Buchung bis auf den Platz.
 */
class CopyFromTest {

	private final ObjectMapper mapper = JsonMapperFactory.createCompact();

	private ObjectNode tree(Object value) {
		return (ObjectNode) mapper.valueToTree(value);
	}

	private ObjectNode treeWithout(Object value, String field) {
		ObjectNode tree = tree(value);
		tree.remove(field);
		return tree;
	}

	private static Booking filledBooking(String id) {
		Booking booking = new Booking();
		booking.setId(id);
		booking.setSeatId("seat-1");
		booking.setRowLabel("A");
		booking.setTableNumber(1);
		booking.setSeatNumber(3);
		booking.setCategory("Kategorie 1");
		booking.setWheelchairAccessible(true);
		booking.setPrice(2500);
		booking.setPaymentStatus(PaymentStatus.CARD);
		booking.setCollected(true);
		booking.setReservationDate(LocalDate.of(2026, 10, 1));
		booking.setPaymentDate(LocalDate.of(2026, 10, 2));
		booking.setCollectionDate(LocalDate.of(2026, 10, 3));
		booking.setLastName("Muster");
		booking.setFirstName("Max");
		booking.setEMail("max@example.org");
		booking.setComment("am Gang");
		booking.setUnassigned(true);
		return booking;
	}

	@Test
	void eventCopyFromCopiesEveryStoredFieldButPresentations() {
		Event source = new Event("evt-1");
		source.setName("Kampagne 2027");
		source.setDescription("Sitzungen im Saal");
		source.setArchived(true);
		source.addPresentation("Fremdsitzung");

		Event target = new Event("evt-1");
		target.setName("Alt");
		target.setDescription("alte Beschreibung");
		target.setArchived(false);
		Presentation own = target.addPresentation("Eigene");

		target.copyFrom(source);

		assertEquals(treeWithout(source, "presentations"), treeWithout(target, "presentations"));
		assertEquals(List.of(own), target.getPresentations());
		assertSame(own, target.getPresentations().get(0));
		assertSame(target, own.getParent());
	}

	@Test
	void presentationCopyFromKeepsBookingsParentAndPlanMissing() {
		Presentation source = new Presentation("prs-1");
		source.setName("Prunksitzung");
		source.setDate(LocalDate.of(2027, 2, 6));
		source.setTime(LocalTime.of(19, 11));
		source.setDescription("mit Pause");
		source.setHallId("hall-2");
		source.setSeatingPlanId("plan-2");
		source.setPrice(3000);
		source.setActualCashAmount(12345);
		source.setCashDifferenceComment("Wechselgeld fehlt");
		source.addBooking().setLastName("Fremd");

		Event event = new Event("evt-1");
		Presentation target = new Presentation("prs-1");
		event.getPresentations().add(target);
		target.setParent(event);
		target.setName("Alt");
		target.setDate(LocalDate.of(2026, 1, 1));
		target.setTime(LocalTime.of(10, 0));
		target.setDescription("alt");
		target.setHallId("hall-1");
		target.setSeatingPlanId("plan-1");
		target.setPrice(100);
		target.setActualCashAmount(1);
		target.setCashDifferenceComment("alt");
		Booking own = target.addBooking();
		own.setLastName("Eigen");
		target.setPlanMissing(true);

		target.copyFrom(source);

		assertEquals(treeWithout(source, "bookings"), treeWithout(target, "bookings"));
		assertEquals(1, target.getBookings().size());
		assertSame(own, target.getBookings().get(0));
		assertEquals("Eigen", own.getLastName());
		assertSame(event, target.getParent());
		assertTrue(target.isPlanMissing());
	}

	@Test
	void bookingCopyFromCopiesIdSeatAndCustomerData() {
		Booking source = filledBooking("bkg-fremd");

		Presentation parent = new Presentation("prs-1");
		Booking target = parent.addBooking();
		target.setId("bkg-eigen");
		target.setSeatId("seat-9");
		target.setLastName("Eigen");
		target.setUnassigned(false);

		target.copyFrom(source);

		assertEquals(tree(source), tree(target));
		assertEquals("bkg-fremd", target.getId());
		assertSame(parent, target.getParent());
	}

	@Test
	void clearCustomerDataKeepsSeatSnapshotAndAssignsNewId() {
		Booking booking = filledBooking("bkg-1");
		assertTrue(booking.hasData());

		booking.clearCustomerData();

		assertFalse(booking.hasData());
		assertEquals("seat-1", booking.getSeatId());
		assertEquals("A", booking.getRowLabel());
		assertEquals(1, booking.getTableNumber());
		assertEquals(3, booking.getSeatNumber());
		assertEquals("Kategorie 1", booking.getCategory());
		assertTrue(booking.isWheelchairAccessible());
		assertFalse(booking.isUnassigned());
		assertNotNull(booking.getId());
		assertNotEquals("bkg-1", booking.getId());
	}
}
