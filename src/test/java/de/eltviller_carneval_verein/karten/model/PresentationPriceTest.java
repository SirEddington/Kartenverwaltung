package de.eltviller_carneval_verein.karten.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.eltviller_carneval_verein.karten.repository.JsonMapperFactory;

class PresentationPriceTest {

	private final Booking reserved;
	private final Booking empty;
	private final Booking paid;
	private final Presentation presentation;

	PresentationPriceTest() {
		presentation = new Presentation();
		presentation.setPrice(2500);

		reserved = presentation.addBooking();
		reserved.setLastName("Müller");

		empty = presentation.addBooking();

		paid = presentation.addBooking();
		paid.setPaymentStatus(PaymentStatus.CASH);
		paid.setPrice(3000);
	}

	@Test
	void changePrice_friert_Preis_belegter_Buchungen_ein() {
		presentation.changePrice(3000);

		assertEquals(3000, presentation.getPrice());
		assertEquals(Integer.valueOf(2500), reserved.getPrice());
		assertEquals(2500, reserved.getEffectivePrice());
		assertNull(empty.getPrice());
		assertEquals(3000, empty.getEffectivePrice());
		assertEquals(Integer.valueOf(3000), paid.getPrice());
		assertEquals(3000, paid.getEffectivePrice());
	}

	@Test
	void zweite_Preisaenderung_laesst_eingefrorene_Buchung_bei_2500() {
		presentation.changePrice(3000);
		presentation.changePrice(3500);

		assertEquals(3500, presentation.getPrice());
		assertEquals(2500, reserved.getEffectivePrice());
		assertEquals(3500, empty.getEffectivePrice());
		assertEquals(3000, paid.getEffectivePrice());
	}

	@Test
	void changePrice_auf_denselben_Wert_aendert_nichts() {
		presentation.changePrice(2500);

		assertEquals(2500, presentation.getPrice());
		assertNull(reserved.getPrice());
		assertNull(empty.getPrice());
		assertEquals(Integer.valueOf(3000), paid.getPrice());
	}

	@Test
	void setPrice_setzt_nur_das_Feld() {
		presentation.setPrice(4000);

		assertEquals(4000, presentation.getPrice());
		assertNull(reserved.getPrice());
		assertEquals(4000, reserved.getEffectivePrice());
	}

	@Test
	void addBooking_setzt_Parent_und_haengt_an() {
		Booking booking = presentation.addBooking();

		assertSame(presentation, booking.getParent());
		assertTrue(presentation.getBookings().contains(booking));
	}

	@Test
	void setBookings_setzt_den_Parent() {
		Presentation other = new Presentation();
		Booking booking = new Booking();
		other.setBookings(new ArrayList<>(List.of(booking)));

		assertSame(other, booking.getParent());
	}

	@Test
	void Event_getBookings_sammelt_alle_Vorstellungen() {
		Event event = new Event();
		Presentation first = event.addPresentation();
		Presentation second = event.addPresentation();
		Booking a = first.addBooking();
		Booking b = second.addBooking();

		assertEquals(List.of(a, b), event.getBookings());
	}

	@Test
	void planMissing_ist_standardmaessig_falsch_und_aenderbar() {
		assertFalse(presentation.isPlanMissing());
		presentation.setPlanMissing(true);
		assertTrue(presentation.isPlanMissing());
	}

	@Test
	void Round_Trip_ueber_JsonMapperFactory_erhaelt_Buchungen_und_setzt_Parent() throws IOException {
		presentation.setSeatingPlanId("plan-1");
		reserved.setSeatId("seat-1");
		reserved.setRowLabel("A");
		reserved.setTableNumber(3);
		reserved.setSeatNumber(2);
		reserved.setUnassigned(true);
		presentation.setPlanMissing(true);

		ObjectMapper mapper = JsonMapperFactory.create();
		String json = mapper.writeValueAsString(presentation);

		// "Ohne Platz" wird gespeichert (bleibt auch, wenn die Bestuhlung später fehlt), planMissing nicht
		assertTrue(mapper.readTree(json).path("bookings").path(0).path("unassigned").asBoolean(), json);
		assertFalse(json.contains("planMissing"), json);
		assertFalse(json.contains("effectivePrice"), json);
		assertFalse(json.contains("placeLabel"), json);

		Presentation loaded = mapper.readValue(json, Presentation.class);

		assertEquals("plan-1", loaded.getSeatingPlanId());
		assertEquals(2500, loaded.getPrice());
		assertEquals(3, loaded.getBookings().size());
		assertFalse(loaded.isPlanMissing());

		Booking loadedReserved = loaded.getBookings().get(0);
		assertSame(loaded, loadedReserved.getParent());
		assertEquals("Müller", loadedReserved.getLastName());
		assertEquals("seat-1", loadedReserved.getSeatId());
		assertEquals("Tisch A3, Sitz 2", loadedReserved.getPlaceLabel());
		assertTrue(loadedReserved.isUnassigned());
		assertFalse(loaded.getBookings().get(1).isUnassigned());
		assertNull(loadedReserved.getPrice());
		assertEquals(2500, loadedReserved.getEffectivePrice());

		Booking loadedPaid = loaded.getBookings().get(2);
		assertSame(loaded, loadedPaid.getParent());
		assertEquals(Integer.valueOf(3000), loadedPaid.getPrice());
		assertEquals(PaymentStatus.CASH, loadedPaid.getPaymentStatus());
	}
}
