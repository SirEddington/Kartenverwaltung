package de.eltviller_carneval_verein.karten.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.eltviller_carneval_verein.karten.repository.JsonMapperFactory;

/**
 * Listen mit {@code null} in einer Datei laden als leere Liste (die Elternverweise der übrigen Ebenen bleiben
 * erhalten), und die Methoden, die über Listen laufen, werfen bei einer fehlenden Liste nicht.
 */
class NullListsTest {

	private final ObjectMapper mapper = JsonMapperFactory.create();

	@Test
	void event_mit_presentations_null_laedt_mit_leerer_Liste() throws Exception {
		Event event = mapper.readValue("{\"id\":\"evt-1\",\"name\":\"Kampagne\",\"presentations\":null}", Event.class);

		assertNotNull(event.getPresentations());
		assertTrue(event.getPresentations().isEmpty());
	}

	@Test
	void presentation_mit_bookings_null_laedt_mit_leerer_Liste_und_Elternverweis() throws Exception {
		Event event = mapper.readValue(
				"{\"id\":\"evt-1\",\"name\":\"Kampagne\",\"presentations\":[{\"id\":\"prs-1\",\"name\":\"Samstag\",\"bookings\":null}]}",
				Event.class);

		Presentation presentation = event.getPresentations().get(0);
		assertSame(event, presentation.getParent());
		assertNotNull(presentation.getBookings());
		assertTrue(presentation.getBookings().isEmpty());

		Presentation alone = mapper.readValue("{\"id\":\"prs-2\",\"bookings\":null}", Presentation.class);
		assertNotNull(alone.getBookings());
		assertTrue(alone.getBookings().isEmpty());
	}

	@Test
	void hall_mit_seatingPlans_null_laedt_mit_leerer_Liste() throws Exception {
		Hall hall = mapper.readValue("{\"id\":\"hal-1\",\"name\":\"Festhalle\",\"seatingPlans\":null}", Hall.class);

		assertNotNull(hall.getSeatingPlans());
		assertTrue(hall.getSeatingPlans().isEmpty());
		assertNull(hall.findSeatingPlan("spl-1"));
	}

	@Test
	void seatingPlan_mit_rows_und_tables_null_laedt_mit_leeren_Listen_und_Elternverweis() throws Exception {
		Hall hall = mapper.readValue(
				"{\"id\":\"hal-1\",\"seatingPlans\":[{\"id\":\"spl-1\",\"name\":\"Prunksitzung\",\"rows\":null,\"tables\":null}]}",
				Hall.class);

		SeatingPlan plan = hall.getSeatingPlans().get(0);
		assertSame(hall, plan.getParent());
		assertNotNull(plan.getRows());
		assertTrue(plan.getRows().isEmpty());
		assertNotNull(plan.getTables());
		assertTrue(plan.getTables().isEmpty());
		assertTrue(plan.getSeats().isEmpty());
	}

	@Test
	void table_mit_seats_null_laedt_mit_leerer_Liste_und_Elternverweis() throws Exception {
		SeatingPlan plan = mapper.readValue(
				"{\"id\":\"spl-1\",\"rows\":[],\"tables\":[{\"id\":\"tbl-1\",\"tableNumber\":3,\"seats\":null}]}", SeatingPlan.class);

		Table table = plan.getTables().get(0);
		assertSame(plan, table.getPlan());
		assertNotNull(table.getSeats());
		assertTrue(table.getSeats().isEmpty());

		Table alone = mapper.readValue("{\"id\":\"tbl-2\",\"seats\":null}", Table.class);
		assertNotNull(alone.getSeats());
		assertTrue(alone.getSeats().isEmpty());
	}

	@Test
	void table_mit_unbekanntem_Feld_laedt() throws Exception {
		Table table = mapper.readValue("{\"id\":\"tbl-1\",\"tableNumber\":4,\"gibtEsNicht\":true,\"seats\":[]}", Table.class);

		assertEquals(4, table.getTableNumber());
	}

	@Test
	void findSeatingPlan_ohne_Liste_liefert_null() {
		Hall hall = new Hall();
		hall.setSeatingPlans(null);

		assertNull(hall.findSeatingPlan("spl-1"));
	}

	@Test
	void changePrice_und_addBooking_ohne_Liste_werfen_nicht() {
		Presentation presentation = new Presentation();
		presentation.setBookings(null);

		assertDoesNotThrow(() -> presentation.changePrice(2000));
		assertEquals(2000, presentation.getPrice());

		Booking booking = presentation.addBooking();
		assertEquals(1, presentation.getBookings().size());
		assertSame(presentation, booking.getParent());
	}
}
