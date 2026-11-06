package de.eltviller_carneval_verein.karten.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.eltviller_carneval_verein.karten.repository.JsonMapperFactory;

/**
 * Die Schlüssel, die Hallenobjekt, Tisch, Sitz und Bestuhlung in die Datei schreiben (KARV-128). Rechner mit v1.0.x lesen
 * dieselben Dateien: Ein zusätzlicher Schlüssel am Hallenobjekt (dort ohne {@code ignoreUnknown}) machte die Halle für
 * sie unlesbar, und Hilfswerte wie {@code textSettings} gehören nicht in die Datei.
 */
class JsonKeysTest {

	private final ObjectMapper mapper = JsonMapperFactory.create();

	private Set<String> keys(Object value) {
		Set<String> keys = new TreeSet<>();
		JsonNode node = mapper.valueToTree(value);
		node.fieldNames().forEachRemaining(keys::add);
		return keys;
	}

	@Test
	void hallObject_writesExactlyTheKeysOfVersion1_0() {
		assertEquals(new TreeSet<>(Set.of("id", "name", "desc", "posX", "posY", "width", "height", "color", "shape", "textAuto", "textSize",
				"textAlignH", "textAlignV", "textRotation")), keys(new HallObject()));
	}

	@Test
	void seat_writesTheWidthAsDiameterAndNoHeight() {
		assertEquals(new TreeSet<>(Set.of("id", "seatNumber", "wheelchairAccessible", "manualPos", "posX", "posY", "width")), keys(new Seat()));
	}

	@Test
	void table_writesTheTextFieldsButNoTextSettings() {
		Set<String> keys = keys(new Table());
		assertTrue(keys.containsAll(Set.of("textAuto", "textSize", "textAlignH", "textAlignV", "textRotation")), keys.toString());
		assertFalse(keys.contains("textSettings"), keys.toString());
	}

	@Test
	void seatingPlan_writesTheSeatWidthButNoSeatHeight() {
		Set<String> keys = keys(new SeatingPlan());
		assertTrue(keys.contains("defaultSeatWidth"), keys.toString());
		assertFalse(keys.contains("defaultSeatHeight"), keys.toString());
	}
}
