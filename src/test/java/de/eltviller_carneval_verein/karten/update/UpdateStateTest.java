package de.eltviller_carneval_verein.karten.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class UpdateStateTest {

	private static final Instant T = Instant.parse("2026-11-07T13:05:00Z");
	private static final UpdateState.Available AVAILABLE = new UpdateState.Available(
			new UpdateInfo(AppVersion.parse("1.1.0", "2").orElseThrow(), "EcvKarten_x", false), T);

	private static UpdateState.CheckFailed failed(Grund grund) {
		return new UpdateState.CheckFailed(grund, T, Optional.empty());
	}

	@Test
	void effectiveUnwrapsChecking() {
		assertEquals(AVAILABLE, new UpdateState.Checking(AVAILABLE).effective());
		assertEquals(AVAILABLE, AVAILABLE.effective());
	}

	@Test
	void checkingNeverNests() {
		UpdateState.Checking inner = new UpdateState.Checking(AVAILABLE);
		assertEquals(AVAILABLE, new UpdateState.Checking(inner).vorher());
	}

	@Test
	void availableStaysVisibleWhileChecking() {
		assertEquals(Optional.of(AVAILABLE), new UpdateState.Checking(AVAILABLE).knownUpdate());
		assertEquals(Optional.empty(), new UpdateState.Checking(new UpdateState.UpToDate(T)).knownUpdate());
		assertEquals(Optional.empty(), new UpdateState.NotChecked().knownUpdate());
	}

	@Test
	void checkFailedKeepsKnown() {
		UpdateState failed = new UpdateState.CheckFailed(Grund.NETWORK, T, Optional.of(AVAILABLE));
		assertEquals(Optional.of(AVAILABLE), failed.knownUpdate());
	}

	@Test
	void checkImpossibleOnlyForSystematicReasonsWithoutKnownUpdate() {
		for (Grund g : new Grund[] { Grund.CERTIFICATE, Grund.PROXY, Grund.SERVER, Grund.INVALID, Grund.UNKNOWN }) {
			assertTrue(failed(g).checkImpossible(), g.name());
		}
		assertFalse(failed(Grund.NETWORK).checkImpossible());
		assertFalse(failed(Grund.TIMEOUT).checkImpossible());
		assertFalse(new UpdateState.CheckFailed(Grund.CERTIFICATE, T, Optional.of(AVAILABLE)).checkImpossible());
		assertTrue(new UpdateState.Checking(failed(Grund.CERTIFICATE)).checkImpossible());
		assertFalse(new UpdateState.Unsupported().checkImpossible());
	}
}
