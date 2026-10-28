package de.eltviller_carneval_verein.karten.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.ObjectNode;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.PaymentStatus;
import de.eltviller_carneval_verein.karten.model.Presentation;

/** Die Feldgruppen müssen genau die Felder abdecken, die {@link UnitMapper} speichert. */
class FieldGroupsTest {

	private static Set<String> grouped(Map<String, List<String>> groups) {
		Set<String> fields = new HashSet<>();
		groups.values().forEach(fields::addAll);
		return fields;
	}

	private static Set<String> names(ObjectNode node) {
		Set<String> names = new HashSet<>();
		node.fieldNames().forEachRemaining(names::add);
		return names;
	}

	private static Booking fullBooking() {
		Booking booking = new Booking();
		booking.setSeatId("seat-1");
		booking.setRowLabel("A");
		booking.setTableNumber(2);
		booking.setSeatNumber(3);
		booking.setCategory("Parkett");
		booking.setWheelchairAccessible(true);
		booking.setPrice(1500);
		booking.setPaymentStatus(PaymentStatus.CASH);
		booking.setCollected(true);
		booking.setReservationDate(LocalDate.of(2026, 10, 1));
		booking.setPaymentDate(LocalDate.of(2026, 10, 2));
		booking.setCollectionDate(LocalDate.of(2026, 10, 3));
		booking.setLastName("Müller");
		booking.setFirstName("Anna");
		booking.setEMail("anna@example.org");
		booking.setComment("Hinweis");
		booking.setUnassigned(true);
		return booking;
	}

	private static Presentation fullPresentation() {
		Presentation presentation = new Presentation();
		presentation.setName("Freitag");
		presentation.setDate(LocalDate.of(2026, 11, 6));
		presentation.setTime(LocalTime.of(19, 11));
		presentation.setDescription("Prunksitzung");
		presentation.setHallId("hall-1");
		presentation.setSeatingPlanId("plan-1");
		presentation.setPrice(2000);
		presentation.setActualCashAmount(5000);
		presentation.setCashDifferenceComment("Wechselgeld");
		presentation.setBookings(new ArrayList<>(List.of(fullBooking())));
		return presentation;
	}

	private static Event fullEvent() {
		Event event = new Event();
		event.setName("Session");
		event.setDescription("Beschreibung");
		event.setArchived(true);
		event.setPresentations(new ArrayList<>(List.of(fullPresentation())));
		return event;
	}

	@Test
	void everyStoredBookingFieldBelongsToAGroup() {
		Set<String> stored = names(UnitMapper.normalize(fullBooking()));

		Set<String> missing = new HashSet<>(stored);
		missing.removeAll(grouped(FieldGroups.booking()));
		assertTrue(missing.isEmpty(), "Felder ohne Gruppe: " + missing);
	}

	@Test
	void everyStoredPresentationFieldBelongsToAGroup() {
		ObjectNode stored = UnitMapper.normalize(fullPresentation());
		stored.remove("bookings");

		Set<String> missing = new HashSet<>(names(stored));
		missing.removeAll(grouped(FieldGroups.presentation()));
		assertTrue(missing.isEmpty(), "Felder ohne Gruppe: " + missing);
	}

	@Test
	void everyStoredEventHeadFieldBelongsToAGroup() {
		ObjectNode stored = UnitMapper.normalize(fullEvent());
		stored.remove("presentations");

		Set<String> missing = new HashSet<>(names(stored));
		missing.removeAll(grouped(FieldGroups.eventHead()));
		assertTrue(missing.isEmpty(), "Felder ohne Gruppe: " + missing);
	}

	@Test
	void everyGroupFieldIsStored() {
		ObjectNode presentation = UnitMapper.normalize(fullPresentation());
		presentation.remove("bookings");
		ObjectNode head = UnitMapper.normalize(fullEvent());
		head.remove("presentations");

		assertEquals(names(UnitMapper.normalize(fullBooking())), grouped(FieldGroups.booking()));
		assertEquals(names(presentation), grouped(FieldGroups.presentation()));
		assertEquals(names(head), grouped(FieldGroups.eventHead()));
	}

	@Test
	void noFieldBelongsToTwoGroups() {
		for (Map<String, List<String>> groups : List.of(FieldGroups.booking(), FieldGroups.presentation(),
				FieldGroups.eventHead())) {
			Collection<List<String>> lists = groups.values();
			int total = lists.stream().mapToInt(List::size).sum();
			assertEquals(total, grouped(groups).size(), "Ein Feld steht in zwei Gruppen: " + groups);
		}
	}
}
