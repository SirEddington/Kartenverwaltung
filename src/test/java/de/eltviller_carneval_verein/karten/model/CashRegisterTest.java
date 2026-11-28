package de.eltviller_carneval_verein.karten.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.eltviller_carneval_verein.karten.repository.JsonMapperFactory;

/** Die Kasse eines Events (KARV-144): Art aus der Kennung, JSON, unbekannte Felder. */
class CashRegisterTest {

	private final ObjectMapper mapper = JsonMapperFactory.create();

	@Test
	void kindFollowsId() {
		assertEquals(CashRegister.Kind.PRESENTATION, new CashRegister("p1").kind());
		assertEquals(CashRegister.Kind.SHARED, new CashRegister("_event").kind());
		assertEquals(CashRegister.Kind.FREE, new CashRegister(CashRegister.newFreeId()).kind());
		assertTrue(CashRegister.newFreeId().startsWith("_"));
	}

	@Test
	void jsonRoundTripKeepsUnknownFields() throws Exception {
		String json = "{\"id\":\"_x\",\"name\":\"V\",\"allPresentations\":true,\"presentationIds\":[\"p1\"],"
				+ "\"actualCashAmount\":125000,\"cashDifferenceComment\":\"k\",\"neu\":1}";
		CashRegister register = mapper.readValue(json, CashRegister.class);
		assertEquals("_x", register.getId());
		JsonNode written = mapper.valueToTree(register);
		assertEquals(mapper.readTree(json), written);
		assertNotNull(written.get("neu"));
	}

	@Test
	void nullPresentationIdsLoadAsEmpty() throws Exception {
		CashRegister register = mapper.readValue("{\"id\":\"_x\",\"presentationIds\":null}", CashRegister.class);
		assertEquals(java.util.List.of(), register.getPresentationIds());
	}
}
