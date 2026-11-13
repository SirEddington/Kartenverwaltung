package de.eltviller_carneval_verein.karten.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.eltviller_carneval_verein.karten.repository.JsonMapperFactory;

/**
 * Form und Ausrichtungen vertragen einen unbekannten Namen (KARV-135): Es gilt ein Rückfallwert, der Originaltext bleibt
 * beim Speichern erhalten, bis jemand den Wert ändert. Erfundene Namen beginnen mit {@code x-test-}.
 */
class TolerantEnumTest {

	private static final String UNKNOWN_SHAPE = "x-test-ellipse";
	private static final String UNKNOWN_ALIGN = "x-test-blocksatz";

	private final ObjectMapper mapper = JsonMapperFactory.create();

	private HallObject readObject(String json) throws Exception {
		return mapper.readValue(json, HallObject.class);
	}

	private Table readTable(String json) throws Exception {
		return mapper.readValue(json, Table.class);
	}

	private JsonNode write(Object value) {
		return mapper.valueToTree(value);
	}

	@Test
	void unknownShapeFallsBackAndIsWrittenBack() throws Exception {
		HallObject object = readObject("{\"id\":\"h1\",\"shape\":\"" + UNKNOWN_SHAPE + "\"}");

		assertEquals(Shape.RECTANGLE, object.getShape());
		assertEquals(UNKNOWN_SHAPE, write(object).get("shape").asText());
		assertTrue(object.getUnknown().isEmpty(), "shape ist ein bekanntes Feld und landet nicht bei den unbekannten");
	}

	@Test
	void unknownAlignmentsOnHallObjectFallBackToCenter() throws Exception {
		HallObject object = readObject(
				"{\"id\":\"h1\",\"textAlignH\":\"" + UNKNOWN_ALIGN + "\",\"textAlignV\":\"" + UNKNOWN_ALIGN + "\"}");

		assertEquals(TextAlignH.CENTER, object.getTextAlignH());
		assertEquals(TextAlignV.CENTER, object.getTextAlignV());
		assertEquals(UNKNOWN_ALIGN, write(object).get("textAlignH").asText());
		assertEquals(UNKNOWN_ALIGN, write(object).get("textAlignV").asText());
		assertTrue(object.getUnknown().isEmpty());
	}

	@Test
	void unknownAlignmentsOnTableFallBackToCenter() throws Exception {
		Table table = readTable(
				"{\"id\":\"t1\",\"textAlignH\":\"" + UNKNOWN_ALIGN + "\",\"textAlignV\":\"" + UNKNOWN_ALIGN + "\"}");

		assertEquals(TextAlignH.CENTER, table.getTextAlignH());
		assertEquals(TextAlignV.CENTER, table.getTextAlignV());
		assertEquals(UNKNOWN_ALIGN, write(table).get("textAlignH").asText());
		assertEquals(UNKNOWN_ALIGN, write(table).get("textAlignV").asText());
		assertTrue(table.getUnknown().isEmpty());
	}

	@Test
	void changingTheValueForgetsTheOriginal() throws Exception {
		HallObject object = readObject("{\"id\":\"h1\",\"shape\":\"" + UNKNOWN_SHAPE + "\"}");

		object.setShape(Shape.CIRCLE);

		assertEquals("CIRCLE", write(object).get("shape").asText());
	}

	@Test
	void settingTheSameValueKeepsTheOriginal() throws Exception {
		HallObject object = readObject("{\"id\":\"h1\",\"shape\":\"" + UNKNOWN_SHAPE + "\"}");

		object.setShape(Shape.RECTANGLE);

		assertEquals(UNKNOWN_SHAPE, write(object).get("shape").asText());
	}

	@Test
	void setTextSettingsWithOnlySizeChangedKeepsUnknownAlignment() throws Exception {
		Table table = readTable("{\"id\":\"t1\",\"textAlignH\":\"" + UNKNOWN_ALIGN + "\"}");
		TextSettings settings = table.getTextSettings();

		table.setTextSettings(new TextSettings(settings.auto(), 33, settings.alignH(), settings.alignV(), settings.rotation()));

		assertEquals(33, table.getTextSize());
		assertEquals(UNKNOWN_ALIGN, write(table).get("textAlignH").asText());

		HallObject object = readObject("{\"id\":\"h1\",\"textAlignV\":\"" + UNKNOWN_ALIGN + "\"}");
		TextSettings objectSettings = object.getTextSettings();

		object.setTextSettings(new TextSettings(false, objectSettings.size(), objectSettings.alignH(), objectSettings.alignV(), 90));

		assertEquals(90, object.getTextRotation());
		assertEquals(UNKNOWN_ALIGN, write(object).get("textAlignV").asText());
	}

	@Test
	void setTextSettingsWithChangedAlignmentForgetsTheOriginal() throws Exception {
		Table table = readTable("{\"id\":\"t1\",\"textAlignH\":\"" + UNKNOWN_ALIGN + "\"}");
		TextSettings settings = table.getTextSettings();

		table.setTextSettings(new TextSettings(settings.auto(), settings.size(), TextAlignH.LEFT, settings.alignV(), settings.rotation()));

		assertEquals("LEFT", write(table).get("textAlignH").asText());
	}

	@Test
	void seatingPlanCopyKeepsOriginalTexts() throws Exception {
		SeatingPlan plan = new SeatingPlan();
		Table table = readTable(
				"{\"id\":\"t1\",\"textAlignH\":\"" + UNKNOWN_ALIGN + "\",\"textAlignV\":\"" + UNKNOWN_ALIGN + "\",\"textSize\":44}");
		table.setPlan(plan);
		plan.getTables().add(table);

		Table copied = plan.copy().getTables().get(0);

		assertEquals(TextAlignH.CENTER, copied.getTextAlignH());
		assertEquals(44, copied.getTextSize());
		assertEquals(UNKNOWN_ALIGN, write(copied).get("textAlignH").asText());
		assertEquals(UNKNOWN_ALIGN, write(copied).get("textAlignV").asText());
	}

	@Test
	void knownValuesAreWrittenByExactName() throws Exception {
		for (Shape shape : Shape.values()) {
			HallObject object = readObject("{\"id\":\"h1\",\"shape\":\"" + shape.name() + "\"}");
			assertEquals(shape, object.getShape());
			assertEquals(shape.name(), write(object).get("shape").asText());
		}
		for (TextAlignH align : TextAlignH.values()) {
			Table table = readTable("{\"id\":\"t1\",\"textAlignH\":\"" + align.name() + "\"}");
			assertEquals(align, table.getTextAlignH());
			assertEquals(align.name(), write(table).get("textAlignH").asText());
		}
		for (TextAlignV align : TextAlignV.values()) {
			HallObject object = readObject("{\"id\":\"h1\",\"textAlignV\":\"" + align.name() + "\"}");
			assertEquals(align, object.getTextAlignV());
			assertEquals(align.name(), write(object).get("textAlignV").asText());
		}
	}

	@Test
	void nullStaysNull() throws Exception {
		HallObject object = readObject("{\"id\":\"h1\",\"shape\":null,\"textAlignH\":null}");

		assertNull(object.getShape());
		assertNull(object.getTextAlignH());
		assertTrue(write(object).get("shape").isNull());
		assertTrue(write(object).get("textAlignH").isNull());
	}

	@Test
	void missingFieldsKeepTheDefaults() throws Exception {
		HallObject object = readObject("{\"id\":\"h1\"}");

		assertEquals(Shape.RECTANGLE, object.getShape());
		assertEquals("RECTANGLE", write(object).get("shape").asText());
		assertEquals("CENTER", write(object).get("textAlignH").asText());
	}
}
