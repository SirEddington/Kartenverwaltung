package de.eltviller_carneval_verein.karten.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

/**
 * Kopien und Zurücksetzen behandeln die gesammelten Felder einer neueren Version: {@code copyFrom} ersetzt die Map,
 * {@code SeatingPlan.copy} nimmt sie auf allen Ebenen mit, und eine Buchung, die nur ein solches Feld trägt, gilt als
 * Rest.
 */
class UnknownFieldsCopyTest {

	private static TextNode text(String value) {
		return TextNode.valueOf(value);
	}

	@Test
	void copyFromReplacesTheMap() {
		Event target = new Event();
		target.getUnknown().put("x-test-a", text("alt"));
		Event source = new Event();
		source.getUnknown().put("x-test-c", text("c"));
		source.getUnknown().put("x-test-b", text("b"));

		target.copyFrom(source);

		assertEquals(List.of("x-test-c", "x-test-b"), List.copyOf(target.getUnknown().asMap().keySet()),
				"Nur die Schlüssel der Quelle, in deren Reihenfolge");
		assertFalse(target.getUnknown().has("x-test-a"));
	}

	@Test
	void copyFromReplacesTheMapOfPresentationAndBooking() {
		Presentation presentation = new Presentation();
		presentation.getUnknown().put("x-test-a", text("alt"));
		Presentation presentationSource = new Presentation();
		presentationSource.getUnknown().put("x-test-b", text("b"));
		presentation.copyFrom(presentationSource);
		assertEquals(List.of("x-test-b"), List.copyOf(presentation.getUnknown().asMap().keySet()));

		Booking booking = new Booking();
		booking.getUnknown().put("x-test-a", text("alt"));
		Booking bookingSource = new Booking();
		bookingSource.getUnknown().put("x-test-b", text("b"));
		booking.copyFrom(bookingSource);
		assertEquals(List.of("x-test-b"), List.copyOf(booking.getUnknown().asMap().keySet()));

		Booking copy = bookingSource.copy();
		assertEquals(bookingSource.getUnknown().asMap(), copy.getUnknown().asMap());
	}

	@Test
	void copyFromDeepCopies() {
		Event source = new Event();
		ObjectNode nested = JsonNodeFactory.instance.objectNode();
		nested.put("inner", 1);
		source.getUnknown().put("x-test-obj", nested);
		Event target = new Event();

		target.copyFrom(source);
		((ObjectNode) target.getUnknown().asMap().get("x-test-obj")).put("inner", 2);

		assertEquals(1, ((ObjectNode) source.getUnknown().asMap().get("x-test-obj")).get("inner").asInt(),
				"Ändern im Ziel darf die Quelle nicht ändern");
		assertNotSame(source.getUnknown().asMap().get("x-test-obj"), target.getUnknown().asMap().get("x-test-obj"));
	}

	@Test
	void seatingPlanCopyCarriesUnknownFieldsOfAllLevels() {
		SeatingPlan plan = new SeatingPlan();
		plan.getUnknown().put("x-test-plan", JsonNodeFactory.instance.objectNode().put("inner", 1));
		TableRow row = plan.addRow();
		row.getUnknown().put("x-test-row", text("r"));
		Table table = plan.addTable(row);
		table.getUnknown().put("x-test-table", text("t"));
		Seat seat = table.addSeat();
		seat.getUnknown().put("x-test-seat", text("s"));

		SeatingPlan copy = plan.copy();

		assertEquals(plan.getUnknown().asMap().get("x-test-plan"), copy.getUnknown().asMap().get("x-test-plan"));
		assertEquals(text("r"), copy.getRows().get(0).getUnknown().asMap().get("x-test-row"));
		Table copiedTable = copy.getTables().get(0);
		assertEquals(text("t"), copiedTable.getUnknown().asMap().get("x-test-table"));
		assertEquals(text("s"), copiedTable.getSeats().get(0).getUnknown().asMap().get("x-test-seat"));
		assertNotSame(plan.getUnknown().asMap().get("x-test-plan"), copy.getUnknown().asMap().get("x-test-plan"),
				"Die Kopie trägt tiefe Kopien");
	}

	@Test
	void bookingWithOnlyUnknownFieldHasData() {
		Booking booking = new Booking();
		assertFalse(booking.hasData());

		booking.getUnknown().put("x-test-feld", text("wert"));

		assertTrue(booking.hasData());
		assertEquals(List.of("weitere Angaben"), booking.dataLabels());
	}

	@Test
	void clearCustomerDataClearsUnknownFields() {
		Booking booking = new Booking();
		booking.setLastName("Meier");
		booking.getUnknown().put("x-test-feld", text("wert"));

		booking.clearCustomerData();

		assertTrue(booking.getUnknown().isEmpty());
		assertFalse(booking.hasData());
	}

	@Test
	void clearLeftoversClearsUnknownFields() {
		Booking booking = new Booking();
		booking.getUnknown().put("x-test-feld", text("wert"));
		assertTrue(booking.hasLeftovers(), "Ein freier Sitz mit nur einem unbekannten Feld trägt einen Rest");

		booking.clearLeftovers();

		assertTrue(booking.getUnknown().isEmpty());
		assertFalse(booking.hasLeftovers());
	}
}
