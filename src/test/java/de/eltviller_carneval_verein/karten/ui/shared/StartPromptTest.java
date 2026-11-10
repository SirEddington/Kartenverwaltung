package de.eltviller_carneval_verein.karten.ui.shared;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class StartPromptTest {

	private static final Instant T = Instant.parse("2026-11-10T13:00:00Z");

	private static final class TestClock extends Clock {
		Instant now = T;

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}

	private final TestClock clock = new TestClock();
	private final StartPrompt prompt = new StartPrompt(clock);

	@Test
	void dueWhenAllConditionsHold() {
		prompt.menuShown();
		assertTrue(prompt.due(true, true, true, true));
	}

	@Test
	void notDueBeforeMenuShown() {
		assertFalse(prompt.due(true, true, true, true));
		assertFalse(prompt.expired());
	}

	@Test
	void notDueAfterTwoMinutes() {
		prompt.menuShown();
		clock.now = T.plus(Duration.ofMinutes(2)).plusSeconds(1);
		assertFalse(prompt.due(true, true, true, true));
		assertTrue(prompt.expired());
	}

	@Test
	void windowStartsAtFirstMenu() {
		prompt.menuShown();
		clock.now = T.plusSeconds(90);
		prompt.menuShown();
		clock.now = T.plusSeconds(125);
		assertFalse(prompt.due(true, true, true, true));
	}

	@Test
	void notDueTwice() {
		prompt.menuShown();
		prompt.asked();
		assertFalse(prompt.due(true, true, true, true));
		assertTrue(prompt.expired());
	}

	@Test
	void waitsForOfferFirstCheckWindowAndFlow() {
		prompt.menuShown();
		assertFalse(prompt.due(false, true, true, true));
		assertFalse(prompt.due(true, false, true, true));
		assertFalse(prompt.due(true, true, false, true));
		assertFalse(prompt.due(true, true, true, false));
		assertFalse(prompt.expired());
	}
}
