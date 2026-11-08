package de.eltviller_carneval_verein.karten.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javafx.application.Platform;

/** Der Hinweis der Fußzeile auf offene Konflikte und wartende eigene Änderungen (KARV-135). */
class ConflictIndicatorTest {

	@BeforeAll
	static void startFx() {
		try {
			Platform.startup(() -> {
			});
		} catch (IllegalStateException alreadyStarted) {
			// der Toolkit läuft schon (anderer Test im selben Lauf)
		}
		Platform.setImplicitExit(false);
	}

	private static <T> T onFx(Supplier<T> action) throws Exception {
		CompletableFuture<T> result = new CompletableFuture<>();
		Platform.runLater(() -> {
			try {
				result.complete(action.get());
			} catch (Throwable e) {
				result.completeExceptionally(e);
			}
		});
		return result.get(10, TimeUnit.SECONDS);
	}

	private static ConflictIndicator indicator(int open, int waiting) throws Exception {
		return onFx(() -> new ConflictIndicator(() -> open, () -> waiting, () -> {
		}));
	}

	@Test
	void indicatorShowsWaitingChanges() throws Exception {
		ConflictIndicator indicator = indicator(0, 2);

		assertTrue(indicator.isVisible());
		assertTrue(indicator.isManaged());
		assertEquals("2 Änderungen warten", indicator.getText());
	}

	@Test
	void indicatorCombinesConflictsAndWaiting() throws Exception {
		assertEquals("1 Konflikt offen · 1 Änderung wartet", indicator(1, 1).getText());
	}

	@Test
	void conflictsAloneAsBefore() throws Exception {
		ConflictIndicator indicator = indicator(3, 0);

		assertTrue(indicator.isVisible());
		assertEquals("3 Konflikte offen", indicator.getText());
	}

	@Test
	void hiddenWithoutConflictsAndWaiting() throws Exception {
		ConflictIndicator indicator = indicator(0, 0);

		assertFalse(indicator.isVisible());
		assertFalse(indicator.isManaged());
	}

	/** Lässt sich die Zahl der wartenden Änderungen nicht lesen, bleiben die Konflikte zu sehen. */
	@Test
	void failingWaitingCountKeepsConflicts() throws Exception {
		// die erwartete Warnung samt Stacktrace nicht in die Ausgabe des Laufs
		Logger log = Logger.getLogger(ConflictIndicator.class.getName());
		Level before = log.getLevel();
		log.setLevel(Level.OFF);
		try {
			ConflictIndicator indicator = onFx(() -> new ConflictIndicator(() -> 1, () -> {
				throw new IllegalStateException("x-test-kaputt");
			}, () -> {
			}));

			assertEquals("1 Konflikt offen", indicator.getText());
		} finally {
			log.setLevel(before);
		}
	}
}
