package de.eltviller_carneval_verein.karten.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.repository.JsonMapperFactory;

/** Die Liste der Kassen am Event (KARV-144): sortiert nach Kennung, ersetzen, finden, entfernen. */
class EventCashRegistersTest {

	private static List<String> ids(Event event) {
		return event.getCashRegisters().stream().map(CashRegister::getId).toList();
	}

	@Test
	void listStaysSortedById() {
		Event event = new Event("e1");
		event.putCashRegister(new CashRegister("_b"));
		event.putCashRegister(new CashRegister("_a"));
		event.putCashRegister(new CashRegister("p1"));
		assertEquals(List.of("_a", "_b", "p1"), ids(event));

		CashRegister replacement = new CashRegister("_b");
		replacement.setName("neu");
		event.putCashRegister(replacement);
		assertEquals(List.of("_a", "_b", "p1"), ids(event));
		assertSame(replacement, event.findCashRegister("_b"));
	}

	@Test
	void findAndRemove() {
		Event event = new Event("e1");
		event.putCashRegister(new CashRegister("_a"));
		assertNull(event.findCashRegister("x"));
		assertTrue(event.removeCashRegister("_a"));
		assertFalse(event.removeCashRegister("_a"));
		assertEquals(List.of(), ids(event));
	}

	@Test
	void loadedListIsSortedAndNullLoadsAsEmpty() throws Exception {
		var mapper = JsonMapperFactory.create();
		Event event = mapper.readValue("{\"id\":\"e1\",\"cashRegisters\":[{\"id\":\"p1\"},{\"id\":\"_a\"}]}", Event.class);
		assertEquals(List.of("_a", "p1"), ids(event));
		event = mapper.readValue("{\"id\":\"e1\",\"cashRegisters\":null}", Event.class);
		assertEquals(List.of(), ids(event));
	}
}
