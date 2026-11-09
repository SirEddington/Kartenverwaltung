package de.eltviller_carneval_verein.karten.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.store.SyncService;
import de.eltviller_carneval_verein.karten.store.TestSyncServices;
import de.eltviller_carneval_verein.karten.store.UnitKey;

/** Die Hilfen des Repositorys für den Konflikt-Dialog: Felder im Modell setzen (ohne zu speichern), Buchungen finden. */
class StoreEventRepositoryApplyFieldsTest {

	@TempDir
	Path appData;

	private SyncService sync;
	private StoreEventRepository repository;
	private Event event;
	private Presentation show;
	private Booking sale;
	private UnitKey saleUnit;

	@BeforeEach
	void setUp() {
		sync = TestSyncServices.singleMode(appData);
		sync.startup(null, p -> {
		});
		repository = TestRepositories.events(sync);
		event = new Event();
		event.setName("Prunksitzung");
		show = event.addPresentation("Freitag");
		sale = show.addBooking();
		sale.setSeatId("seat-1");
		sale.setRowLabel("A");
		sale.setTableNumber(1);
		sale.setSeatNumber(1);
		sale.setLastName("Müller");
		sale.setFirstName("Anna");
		sale.setComment("Notiz");
		repository.saveEvent(event);
		saleUnit = UnitKey.booking(event.getId(), show.getId(), "seat-1");
	}

	private static ObjectNode node() {
		return JsonNodeFactory.instance.objectNode();
	}

	@Test
	void applyGroupChangesTheModelButNothingElse() throws IOException {
		String stored = sync.unitState(saleUnit).current().payload().get("lastName").asText();

		repository.applyGroup(saleUnit, "customer", node().put("lastName", "Meier"));

		assertSame(sale, repository.modelObject(saleUnit));
		assertEquals("Meier", sale.getLastName());
		assertNull(sale.getFirstName(), "Felder der Gruppe, die der Ausschnitt nicht nennt, werden geleert");
		assertEquals("Notiz", sale.getComment(), "andere Gruppen bleiben");
		assertEquals("seat-1", sale.getSeatId());
		assertEquals(stored, sync.unitState(saleUnit).current().payload().get("lastName").asText(), "nichts gespeichert");
		assertEquals(Set.of(saleUnit), repository.locallyChangedUnits());
	}

	@Test
	void applyFieldsKeepsEveryFieldItDoesNotName() {
		repository.applyFields(saleUnit, node().put("comment", "Neu"));

		assertEquals("Neu", sale.getComment());
		assertEquals("Müller", sale.getLastName());
		assertEquals("Anna", sale.getFirstName());
	}

	@Test
	void applyFieldsWithNullRemovesUnknownKey() {
		sale.getUnknown().put("x-test-a", IntNode.valueOf(1));
		sale.getUnknown().put("x-test-b", IntNode.valueOf(2));
		assertTrue(repository.payloadOf(saleUnit).has("x-test-a"));

		repository.applyFields(saleUnit, node().putNull("x-test-a"));

		ObjectNode payload = repository.payloadOf(saleUnit);
		assertFalse(payload.has("x-test-a"));
		assertEquals(2, payload.get("x-test-b").asInt(), "andere unbekannte Schlüssel bleiben");
		assertFalse(sale.getUnknown().has("x-test-a"));
		assertEquals("Notiz", sale.getComment());
	}

	@Test
	void applyFieldsWithNullOnKnownPropertyKeepsTheModelMeaning() {
		repository.applyFields(saleUnit, node().putNull("comment"));

		assertNull(sale.getComment());
		assertEquals("Müller", sale.getLastName());
	}

	@Test
	void applyFieldsWithNullRemovesUnknownKeyOfPresentationAndEventHead() {
		show.getUnknown().put("x-test-a", IntNode.valueOf(1));
		event.getUnknown().put("x-test-a", IntNode.valueOf(1));

		repository.applyFields(UnitKey.presentation(event.getId(), show.getId()), node().putNull("x-test-a"));
		repository.applyFields(UnitKey.event(event.getId()), node().putNull("x-test-a"));

		assertFalse(show.getUnknown().has("x-test-a"));
		assertFalse(event.getUnknown().has("x-test-a"));
		assertEquals(1, show.getBookings().size(), "die Buchungen bleiben");
	}

	@Test
	void applyGroupWithoutValueRemovesUnknownKey() {
		sale.getUnknown().put("x-test-a", IntNode.valueOf(1));

		repository.applyGroup(saleUnit, "x-test-a", node());

		assertFalse(repository.payloadOf(saleUnit).has("x-test-a"));
		assertFalse(sale.getUnknown().has("x-test-a"));
	}

	@Test
	void applyGroupWithValueSetsUnknownKey() {
		repository.applyGroup(saleUnit, "x-test-a", node().put("x-test-a", 7));

		assertEquals(7, repository.payloadOf(saleUnit).get("x-test-a").asInt());
	}

	@Test
	void restoreFieldsRemovesKeyAddedMeanwhile() {
		ObjectNode before = repository.payloadOf(saleUnit);
		assertFalse(before.has("x-test-a"));

		repository.applyGroup(saleUnit, "x-test-a", node().put("x-test-a", 7));
		repository.applyGroup(saleUnit, "comment", node().put("comment", "Anders"));
		assertTrue(repository.payloadOf(saleUnit).has("x-test-a"));

		repository.restoreFields(saleUnit, before);

		assertEquals(before, repository.payloadOf(saleUnit));
		assertEquals("Notiz", sale.getComment());
	}

	@Test
	void restoreFieldsBringsBackAnUnknownKeyRemovedMeanwhile() {
		sale.getUnknown().put("x-test-a", IntNode.valueOf(1));
		ObjectNode before = repository.payloadOf(saleUnit);

		repository.applyGroup(saleUnit, "x-test-a", node());
		assertFalse(repository.payloadOf(saleUnit).has("x-test-a"));

		repository.restoreFields(saleUnit, before);

		assertEquals(before, repository.payloadOf(saleUnit));
	}

	@Test
	void applyFieldsWorksForThePresentation() {
		repository.applyGroup(UnitKey.presentation(event.getId(), show.getId()), "name", node().put("name", "Samstag"));

		assertEquals("Samstag", show.getName());
		assertEquals(1, show.getBookings().size(), "die Buchungen bleiben");
	}

	@Test
	void applyFieldsToAMissingUnitFails() {
		UnitKey missing = UnitKey.booking(event.getId(), show.getId(), "seat-9");

		assertThrows(IllegalStateException.class, () -> repository.applyFields(missing, node().put("comment", "x")));
		assertThrows(IllegalStateException.class,
				() -> repository.applyFields(UnitKey.event("gibt-es-nicht"), node().put("name", "x")));
	}

	@Test
	void bookingLivesInTheModelOrInTheStore() {
		String id = sale.getId();
		assertTrue(repository.bookingLives(event.getId(), show.getId(), id));
		assertFalse(repository.bookingLives(event.getId(), show.getId(), "anderer-verkauf"));
		assertFalse(repository.bookingLives(event.getId(), "andere-vorstellung", id), "nur unter derselben Vorstellung");

		// aus dem Modell genommen, aber noch nicht gespeichert: Der Speicher kennt sie noch
		show.getBookings().remove(sale);
		assertTrue(repository.bookingLives(event.getId(), show.getId(), id));
		repository.saveEvent(event);
		assertFalse(repository.bookingLives(event.getId(), show.getId(), id), "gelöscht");
	}

	@Test
	void readBookingParsesAPayloadAndRejectsGarbage() throws IOException {
		Booking read = repository.readBooking(sync.unitState(saleUnit).current().payload());

		assertEquals(sale.getId(), read.getId());
		assertEquals("Müller", read.getLastName());
		assertNull(repository.readBooking(node().put("tableNumber", "keine Zahl")));
	}
}
