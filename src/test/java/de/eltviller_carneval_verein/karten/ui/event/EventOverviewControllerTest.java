package de.eltviller_carneval_verein.karten.ui.event;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Hall;

class EventOverviewControllerTest {

	@Test
	void hallDisplayName_missingHall_returnsDash() {
		assertEquals("–", EventOverviewController.hallDisplayName(null));
	}

	@Test
	void hallDisplayName_returnsName() {
		Hall hall = new Hall();
		hall.changeName("Stadthalle");

		assertEquals("Stadthalle", EventOverviewController.hallDisplayName(hall));
	}
}
