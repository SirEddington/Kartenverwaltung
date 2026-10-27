package de.eltviller_carneval_verein.karten.ui.backup;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Uhr, die der Test von Hand stellt: Jeder Speicherstand bekommt so seinen eigenen Zeitpunkt. */
final class SteppedClock extends Clock {

	private Instant now = Instant.parse("2026-10-27T10:00:00Z");

	/** Stellt die Uhr {@code minutes} Minuten vor. */
	void advance(int minutes) {
		now = now.plusSeconds(60L * minutes);
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
