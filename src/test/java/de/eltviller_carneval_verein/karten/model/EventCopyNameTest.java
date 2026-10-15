package de.eltviller_carneval_verein.karten.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Der automatische Name der Kopie beim "Event kopieren". */
class EventCopyNameTest {

	@Test
	void copyGetsTheNameWithCopySuffix() {
		assertEquals("Kampagne (Kopie)", Event.createCopyName("Kampagne", List.of("Kampagne")));
	}

	@Test
	void takenNamesAreNumberedFromTwo() {
		assertEquals("Kampagne (Kopie 2)", Event.createCopyName("Kampagne", List.of("Kampagne", "Kampagne (Kopie)")));
		assertEquals("Kampagne (Kopie 3)", Event.createCopyName("Kampagne", List.of("Kampagne (Kopie)", "Kampagne (Kopie 2)")));
	}

	@Test
	void comparisonIgnoresCaseAndSpacesAtTheEdges() {
		assertEquals("Kampagne (Kopie 2)", Event.createCopyName("Kampagne", List.of(" kampagne (kopie) ")));
	}

	@Test
	void copyOfAnUnnamedEventStillGetsAName() {
		assertEquals("Event (Kopie)", Event.createCopyName(null, List.of()));
		assertEquals("Event (Kopie)", Event.createCopyName("  ", List.of()));
	}
}
