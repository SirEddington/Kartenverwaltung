package de.eltviller_carneval_verein.karten.ui.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import javafx.beans.property.SimpleObjectProperty;

class FirstRunWatchTest {

	private final SimpleObjectProperty<Double> progress = new SimpleObjectProperty<>();

	@Test
	void nullBeforeTheRunBeganDoesNotCount() {
		FirstRunWatch watch = new FirstRunWatch(progress);
		int[] ended = { 0 };
		watch.onEnded(() -> ended[0]++);

		progress.set(null);
		assertFalse(watch.ended(), "null ohne Wert davor beendet nichts");
		assertEquals(0, ended[0]);

		// 0.0 ist ein Wert; erst der Wechsel danach auf null beendet den Lauf
		progress.set(0.0);
		assertFalse(watch.ended());
		progress.set(null);
		assertTrue(watch.ended());
		assertEquals(1, ended[0]);
	}

	@Test
	void valueThenNullEndsTheRun() {
		FirstRunWatch watch = new FirstRunWatch(progress);
		int[] ended = { 0 };
		watch.onEnded(() -> ended[0]++);

		progress.set(0.4);
		assertFalse(watch.ended());
		progress.set(1.0);
		assertFalse(watch.ended());
		progress.set(null);

		assertTrue(watch.ended());
		assertEquals(1, ended[0]);

		progress.set(0.2);
		progress.set(null);
		assertEquals(1, ended[0], "der Rückruf kommt nur einmal");
	}

	@Test
	void outageKeepingTheValueDoesNotEndTheRun() {
		FirstRunWatch watch = new FirstRunWatch(progress);
		progress.set(0.5);

		// Ausfall des Ordners: der Versionsspeicher behält den Fortschritt, es gibt keine Änderung
		progress.set(0.5);

		assertFalse(watch.ended());
	}

	@Test
	void valueAlreadyPresentWhenCreatedCountsAsBegun() {
		progress.set(0.5);
		FirstRunWatch watch = new FirstRunWatch(progress);

		progress.set(null);

		assertTrue(watch.ended());
	}

	@Test
	void callbackRegisteredAfterTheEndRunsAtOnce() {
		FirstRunWatch watch = new FirstRunWatch(progress);
		progress.set(0.5);
		progress.set(null);
		int[] ended = { 0 };

		watch.onEnded(() -> ended[0]++);

		assertEquals(1, ended[0]);
	}

	@Test
	void disposedWatchIgnoresFurtherChanges() {
		FirstRunWatch watch = new FirstRunWatch(progress);
		int[] ended = { 0 };
		watch.onEnded(() -> ended[0]++);
		progress.set(0.5);

		watch.dispose();
		progress.set(null);

		assertFalse(watch.ended());
		assertEquals(0, ended[0]);
	}
}
