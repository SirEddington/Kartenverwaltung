package de.eltviller_carneval_verein.karten.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.eltviller_carneval_verein.karten.repository.JsonMapperFactory;

/** Die Textfelder eines Hallenobjekts: Standardwerte und Speichern/Laden. */
class HallObjectTextAttributesTest {

	private final ObjectMapper mapper = JsonMapperFactory.create();

	@Test
	void newObject_hasAutoTextWithDefaults() {
		HallObject object = new HallObject();
		assertTrue(object.isTextAuto());
		assertEquals(20, object.getTextSize());
		assertEquals(TextAlignH.CENTER, object.getTextAlignH());
		assertEquals(TextAlignV.CENTER, object.getTextAlignV());
		assertEquals(0, object.getTextRotation());
	}

	@Test
	void objectFromAnOldFileWithoutTextFields_getsTheDefaults() throws IOException {
		String oldJson = "{\"id\":\"h1\",\"name\":\"Bühne\",\"posX\":10.0,\"posY\":20.0,\"width\":300.0,\"height\":100.0,\"shape\":\"RECTANGLE\"}";

		HallObject object = mapper.readValue(oldJson, HallObject.class);

		assertEquals("Bühne", object.getName());
		assertTrue(object.isTextAuto(), "Alte Objekte sollen automatisch beschriftet werden");
		assertEquals(TextAlignH.CENTER, object.getTextAlignH());
		assertEquals(TextAlignV.CENTER, object.getTextAlignV());
	}

	@Test
	void textFields_surviveSavingAndLoading() throws IOException {
		HallObject object = new HallObject("h2");
		object.changeName("Bar");
		object.setTextAuto(false);
		object.setTextSize(35.5);
		object.setTextAlignH(TextAlignH.RIGHT);
		object.setTextAlignV(TextAlignV.BOTTOM);
		object.setTextRotation(90);

		HallObject loaded = mapper.readValue(mapper.writeValueAsBytes(object), HallObject.class);

		assertEquals(false, loaded.isTextAuto());
		assertEquals(35.5, loaded.getTextSize());
		assertEquals(TextAlignH.RIGHT, loaded.getTextAlignH());
		assertEquals(TextAlignV.BOTTOM, loaded.getTextAlignV());
		assertEquals(90, loaded.getTextRotation());
	}
}
