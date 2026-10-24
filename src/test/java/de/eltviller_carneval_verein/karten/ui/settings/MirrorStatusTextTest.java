package de.eltviller_carneval_verein.karten.ui.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.mirror.MirrorState;

class MirrorStatusTextTest {

	private static final ZoneId UTC = ZoneId.of("UTC");
	private static final Instant AT_1405 = Instant.parse("2026-10-24T14:05:30Z");

	@Test
	void textsOfStatesWithoutTime() {
		assertEquals("Aus", MirrorStatusText.of(MirrorState.AUS, null, UTC));
		assertEquals("Abgleich läuft …", MirrorStatusText.of(MirrorState.ABGLEICH, null, UTC));
		assertEquals("Änderungen ausstehend", MirrorStatusText.of(MirrorState.AUSSTEHEND, null, UTC));
		assertEquals("Ordner nicht erreichbar", MirrorStatusText.of(MirrorState.NICHT_ERREICHBAR, null, UTC));
		assertEquals("Fehler", MirrorStatusText.of(MirrorState.FEHLER, null, UTC));
	}

	@Test
	void currentShowsLastSyncTimeInGivenZone() {
		assertEquals("Aktuell (zuletzt 14:05)", MirrorStatusText.of(MirrorState.AKTUELL, AT_1405, UTC));
		assertEquals("Aktuell (zuletzt 16:05)", MirrorStatusText.of(MirrorState.AKTUELL, AT_1405, ZoneId.of("Europe/Berlin")));
	}

	@Test
	void currentWithoutTimeShowsOnlyCurrent() {
		assertEquals("Aktuell", MirrorStatusText.of(MirrorState.AKTUELL, null, UTC));
	}

	@Test
	void timeIsIgnoredForOtherStates() {
		assertEquals("Aus", MirrorStatusText.of(MirrorState.AUS, AT_1405, UTC));
		assertEquals("Fehler", MirrorStatusText.of(MirrorState.FEHLER, AT_1405, UTC));
	}
}
