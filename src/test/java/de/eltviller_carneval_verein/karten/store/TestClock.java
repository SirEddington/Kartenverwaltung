package de.eltviller_carneval_verein.karten.store;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Test-Hilfe: eine Uhr, die der Test von Hand stellt. Ein Thread stellt sie vor (etwa der Worker über einen Haken der
 * Dateischicht), andere dürfen sie dabei lesen.
 */
public final class TestClock extends Clock {

	private volatile Instant now = Instant.parse("2026-10-28T10:00:00Z");

	/** Stellt die Uhr um {@code duration} vor. */
	public void advance(Duration duration) {
		now = now.plus(duration);
	}

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
