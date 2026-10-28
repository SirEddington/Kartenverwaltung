package de.eltviller_carneval_verein.karten.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.store.ChainStep;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.UnitSnapshot;
import de.eltviller_carneval_verein.karten.store.UnitState;
import de.eltviller_carneval_verein.karten.store.VersionId;

class ForeignDecisionTest {

	private static final UnitKey UNIT = UnitKey.event("e1");
	private static final Instant AT = Instant.parse("2026-10-28T10:00:00Z");

	private static final VersionId V1 = VersionId.create(1_000L, "aaaaaaaa", 0);
	private static final VersionId V2 = VersionId.create(2_000L, "bbbbbbbb", 0);
	/** Eine eigene Version, die im Wettlauf unterlegen ist und deshalb nicht in der gültigen Kette steht. */
	private static final VersionId P = VersionId.create(2_000L, "aaaaaaaa", 1);
	private static final VersionId W = VersionId.create(2_000L, "bbbbbbbb", 1);

	private static UnitSnapshot snapshot(VersionId version, UnitState.Status status) {
		return new UnitSnapshot(UNIT, version, status, false, null);
	}

	private static UnitSnapshot ok(VersionId version) {
		return snapshot(version, UnitState.Status.OK);
	}

	private static ChainStep step(VersionId version) {
		return new ChainStep(version, "save-" + version, AT, "Kasse", "Anna", false);
	}

	@Test
	void sameVersionIsKnown() {
		assertEquals(ForeignDecision.KNOWN, ForeignDecision.decide(ok(V1), ok(V1), List.of(step(V1)), false));
	}

	@Test
	void bothWithoutVersionAreKnown() {
		assertEquals(ForeignDecision.KNOWN, ForeignDecision.decide(snapshot(null, UnitState.Status.OK),
				snapshot(null, UnitState.Status.OK), List.of(), false));
	}

	@Test
	void waitingUnitIsWaiting() {
		assertEquals(ForeignDecision.WAITING, ForeignDecision.decide(ok(V1),
				snapshot(V2, UnitState.Status.WAITING), List.of(step(V1), step(V2)), false));
	}

	@Test
	void successorIsApplied() {
		assertEquals(ForeignDecision.APPLY,
				ForeignDecision.decide(ok(V1), ok(V2), List.of(step(V1), step(V2)), false));
	}

	@Test
	void newUnitIsApplied() {
		assertEquals(ForeignDecision.APPLY, ForeignDecision.decide(null, ok(V1), List.of(step(V1)), false));
	}

	@Test
	void unitWithoutBaselineVersionIsApplied() {
		assertEquals(ForeignDecision.APPLY,
				ForeignDecision.decide(snapshot(null, UnitState.Status.OK), ok(V1), List.of(step(V1)), false));
	}

	@Test
	void forkIsNotApplied() {
		assertEquals(ForeignDecision.FORK, ForeignDecision.decide(ok(P), ok(W), List.of(step(V1), step(W)), false));
	}

	@Test
	void forkWinsOverBlocked() {
		assertEquals(ForeignDecision.FORK, ForeignDecision.decide(ok(P), ok(W), List.of(step(V1), step(W)), true));
	}

	@Test
	void blockedSuccessorIsDeferred() {
		assertEquals(ForeignDecision.DEFER,
				ForeignDecision.decide(ok(V1), ok(V2), List.of(step(V1), step(V2)), true));
	}

	@Test
	void waitingWinsOverFork() {
		assertEquals(ForeignDecision.WAITING,
				ForeignDecision.decide(ok(P), snapshot(W, UnitState.Status.WAITING), List.of(step(V1)), true));
	}
}
