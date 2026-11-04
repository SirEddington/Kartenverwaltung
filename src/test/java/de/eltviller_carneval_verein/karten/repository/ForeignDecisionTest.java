package de.eltviller_carneval_verein.karten.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

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
	/** Die Zusammenführung nach der Niederlage von P: P steht nicht in der Kette, ist aber in M aufgegangen. */
	private static final VersionId M = VersionId.create(3_000L, "aaaaaaaa", 2);

	/** Der Stand mit einem Inhalt, der nur dieser Version gehört; ohne Version ohne Inhalt. */
	private static UnitSnapshot snapshot(VersionId version, UnitState.Status status) {
		return new UnitSnapshot(UNIT, version, status, false, version == null ? null : payload(version.value()));
	}

	private static ObjectNode payload(String name) {
		return JsonNodeFactory.instance.objectNode().put("name", name);
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

	// Zusammenführungen -->

	@Test
	void baselineAbsorbedIntoCurrentIsASuccessor() {
		List<ChainStep> chain = List.of(step(V1), step(W), step(M));

		assertEquals(ForeignDecision.APPLY, ForeignDecision.decide(ok(P), ok(M), chain, Set.of(P), false));
		assertEquals(ForeignDecision.DEFER, ForeignDecision.decide(ok(P), ok(M), chain, Set.of(P), true));
		assertEquals(ForeignDecision.APPLY, ForeignDecision.decideUnblocked(ok(P), ok(M), chain, Set.of(P)));
	}

	@Test
	void baselineNeitherInChainNorAbsorbedIsAFork() {
		List<ChainStep> chain = List.of(step(V1), step(W), step(M));
		VersionId other = VersionId.create(2_500L, "cccccccc", 0);

		assertEquals(ForeignDecision.FORK, ForeignDecision.decide(ok(P), ok(M), chain, Set.of(other), false));
		assertEquals(ForeignDecision.FORK, ForeignDecision.decideUnblocked(ok(P), ok(M), chain, Set.of()));
	}

	@Test
	void successorWithSamePayloadIsSame() {
		UnitSnapshot baseline = new UnitSnapshot(UNIT, W, UnitState.Status.OK, false, payload("Anna"));
		UnitSnapshot merged = new UnitSnapshot(UNIT, M, UnitState.Status.OK, false, payload("Anna"));
		List<ChainStep> chain = List.of(step(V1), step(W), step(M));

		assertEquals(ForeignDecision.SAME, ForeignDecision.decide(baseline, merged, chain, Set.of(P), false));
		assertEquals(ForeignDecision.SAME, ForeignDecision.decideUnblocked(baseline, merged, chain, Set.of(P)));
		// über "aufgegangen" ebenso
		UnitSnapshot lost = new UnitSnapshot(UNIT, P, UnitState.Status.OK, false, payload("Anna"));
		assertEquals(ForeignDecision.SAME, ForeignDecision.decide(lost, merged, chain, Set.of(P), false));
		// andere Löschmarke oder anderer Inhalt: kein gleicher Stand
		UnitSnapshot deleted = new UnitSnapshot(UNIT, M, UnitState.Status.OK, true, payload("Anna"));
		assertEquals(ForeignDecision.APPLY, ForeignDecision.decide(baseline, deleted, chain, Set.of(), false));
		UnitSnapshot other = new UnitSnapshot(UNIT, M, UnitState.Status.OK, false, payload("Bert"));
		assertEquals(ForeignDecision.APPLY, ForeignDecision.decide(baseline, other, chain, Set.of(), false));
		// ohne Vergleichsstand gibt es nichts Gleiches
		assertEquals(ForeignDecision.APPLY, ForeignDecision.decide(null, merged, chain, Set.of(), false));
	}

	@Test
	void sameWinsOverBlocked() {
		UnitSnapshot baseline = new UnitSnapshot(UNIT, W, UnitState.Status.OK, false, payload("Anna"));
		UnitSnapshot merged = new UnitSnapshot(UNIT, M, UnitState.Status.OK, false, payload("Anna"));

		assertEquals(ForeignDecision.SAME,
				ForeignDecision.decide(baseline, merged, List.of(step(V1), step(W), step(M)), Set.of(P), true));
	}

	@Test
	void forkWinsOverSame() {
		UnitSnapshot baseline = new UnitSnapshot(UNIT, P, UnitState.Status.OK, false, payload("Anna"));
		UnitSnapshot winner = new UnitSnapshot(UNIT, W, UnitState.Status.OK, false, payload("Anna"));

		assertEquals(ForeignDecision.FORK,
				ForeignDecision.decide(baseline, winner, List.of(step(V1), step(W)), Set.of(), true));
	}

	@Test
	void lockedSuccessorWithSamePayloadIsAppliedForItsWarning() {
		UnitSnapshot baseline = new UnitSnapshot(UNIT, V1, UnitState.Status.OK, false, payload("Anna"));
		UnitSnapshot locked = new UnitSnapshot(UNIT, V2, UnitState.Status.LOCKED_DAMAGED, false, payload("Anna"));

		assertEquals(ForeignDecision.APPLY,
				ForeignDecision.decide(baseline, locked, List.of(step(V1), step(V2)), Set.of(), false));
	}

	// <-- Zusammenführungen
}
