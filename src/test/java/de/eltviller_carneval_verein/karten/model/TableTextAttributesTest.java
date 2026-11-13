package de.eltviller_carneval_verein.karten.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.eltviller_carneval_verein.karten.repository.JsonMapperFactory;

/** Die Textfelder eines Tischs (KARV-128): Standardwerte, Speichern/Laden, alte Dateien und Kopie der Bestuhlung. */
class TableTextAttributesTest {

	private final ObjectMapper mapper = JsonMapperFactory.create();

	private static Table manualText(Table table) {
		table.setTextAuto(false);
		table.setTextSize(35.5);
		table.setTextAlignH(TextAlignH.RIGHT);
		table.setTextAlignV(TextAlignV.BOTTOM);
		table.setTextRotation(90);
		return table;
	}

	private static void assertManualText(Table table) {
		assertFalse(table.isTextAuto());
		assertEquals(35.5, table.getTextSize());
		assertEquals(TextAlignH.RIGHT, table.getTextAlignH());
		assertEquals(TextAlignV.BOTTOM, table.getTextAlignV());
		assertEquals(90, table.getTextRotation());
	}

	@Test
	void newTable_hasAutoTextWithDefaults() {
		Table table = new Table();
		assertTrue(table.isTextAuto());
		assertEquals(20, table.getTextSize());
		assertEquals(TextAlignH.CENTER, table.getTextAlignH());
		assertEquals(TextAlignV.CENTER, table.getTextAlignV());
		assertEquals(0, table.getTextRotation());
	}

	@Test
	void tableFromAnOldFileWithoutTextFields_isLabelledAutomatically() throws IOException {
		Table table = mapper.readValue("{\"id\":\"t1\",\"tableNumber\":3,\"width\":70.0,\"height\":300.0}", Table.class);

		assertTrue(table.isTextAuto());
		assertEquals(TextAlignH.CENTER, table.getTextAlignH());
		assertEquals(TextAlignV.CENTER, table.getTextAlignV());
	}

	@Test
	void textFields_surviveSavingAndLoading() throws IOException {
		Table loaded = mapper.readValue(mapper.writeValueAsBytes(manualText(new Table("t2"))), Table.class);

		assertManualText(loaded);
	}

	@Test
	void textSettings_bundleTheFiveFields() {
		assertEquals(new TextSettings(false, 35.5, TextAlignH.RIGHT, TextAlignV.BOTTOM, 90), manualText(new Table()).getTextSettings());
	}

	@Test
	void copyOfThePlan_keepsTheTableTexts() {
		SeatingPlan plan = new Hall().addSeatingPlan();
		manualText(plan.addTable(plan.addRow()));

		SeatingPlan copy = plan.copy();

		assertManualText(copy.getTables().get(0));
	}
}
