package de.eltviller_carneval_verein.karten.ui.hall;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import javafx.beans.value.ChangeListener;
import javafx.scene.Group;

class PlanLabelOptionsTest {

	private final PlanLabelOptions options = new PlanLabelOptions();

	@Test
	void everythingIsShownAtTheStart() {
		assertEquals(PlanLabels.ALL, options.snapshot());
	}

	@Test
	void snapshotFollowsEachSwitch() {
		options.rowsProperty().set(false);
		options.seatNumbersProperty().set(false);

		PlanLabels labels = options.snapshot();

		assertFalse(labels.rows());
		assertTrue(labels.tables());
		assertFalse(labels.seatNumbers());
		assertTrue(labels.hallObjects());
	}

	@Test
	void listenerHearsEverySwitchUntilItIsRemoved() {
		List<Boolean> seen = new ArrayList<>();
		ChangeListener<Boolean> listener = (obs, oldValue, newValue) -> seen.add(newValue);
		options.addListener(listener);

		options.tablesProperty().set(false);
		options.hallObjectsProperty().set(false);
		options.removeListener(listener);
		options.rowsProperty().set(false);

		assertEquals(List.of(false, false), seen);
	}

	@Test
	void followCallsBackOnEverySwitchAndTheOwnerHoldsTheListener() {
		Group owner = new Group();
		List<String> calls = new ArrayList<>();
		options.follow(owner, () -> calls.add("neu"));

		options.rowsProperty().set(false);
		options.hallObjectsProperty().set(false);

		assertEquals(List.of("neu", "neu"), calls);
		// Nur der Knoten hält den Listener stark; die Optionen kennen ihn schwach
		assertEquals(1, owner.getProperties().size());
	}
}
