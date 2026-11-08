package de.eltviller_carneval_verein.karten.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.eltviller_carneval_verein.karten.store.ChainStep;
import de.eltviller_carneval_verein.karten.store.StoreCodec;
import de.eltviller_carneval_verein.karten.store.StoreMessages;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.UnitSnapshot;
import de.eltviller_carneval_verein.karten.store.UnitState;
import de.eltviller_carneval_verein.karten.store.UnitState.Status;
import de.eltviller_carneval_verein.karten.store.Version;
import de.eltviller_carneval_verein.karten.store.VersionId;

class RestorePlannerTest {

	private static final Instant T0 = Instant.parse("2026-10-27T10:00:00Z");

	private static final UnitKey EVENT = UnitKey.event("e1");
	private static final UnitKey PRESENTATION = UnitKey.presentation("e1", "p1");
	private static final UnitKey BOOKING = UnitKey.booking("e1", "p1", "seat-1");

	/** Der Zeitpunkt {@code minutes} Minuten nach {@link #T0}. */
	private static Instant at(int minutes) {
		return T0.plusSeconds(60L * minutes);
	}

	/** Eine Version mit Nummer (bestimmt die ID), Speichervorgang und Zeitpunkt; der Inhalt trägt die Nummer. */
	private static Version version(int n, String saveId, Instant at, boolean deleted) {
		return version(n, saveId, at, deleted, "Kasse " + n, "Anna");
	}

	private static Version version(int n, String saveId, Instant at, boolean deleted, String machineName,
			String operator) {
		ObjectNode payload = deleted ? null : new ObjectMapper().createObjectNode().put("n", n);
		return new Version(StoreCodec.FORMAT, VersionId.create(n * 1000L, "aaaaaaaa", 0), List.of(), "aaaaaaaa",
				machineName, operator, at, saveId, deleted, payload);
	}

	private static UnitState state(UnitKey unit, Status status, Version... chain) {
		return new UnitState(unit, status, List.of(chain), List.of(), List.of(), null, Set.of());
	}

	private static UnitState ok(UnitKey unit, Version... chain) {
		return state(unit, Status.OK, chain);
	}

	/** Die Prüfung gegen den Zielstand zum Zeitpunkt {@code t}, wie beim Wiederherstellen. */
	private static void requireRestorable(List<UnitState> states, Instant t) {
		RestorePlanner.requireRestorable(states, RestorePlanner.targetsAt(states, t));
	}

	/** Die Prüfung gegen den neuesten Stand als Ziel. */
	private static void requireRestorable(List<UnitState> states) {
		requireRestorable(states, at(10_000));
	}

	/** Die Speicherstände aus den Gliedern der Ketten, wie der Index sie liefert. */
	private static List<SavePoint> savePoints(UnitState... states) {
		return RestorePlanner.savePoints(
				Stream.of(states).map(state -> state.chain().stream().map(ChainStep::of).toList()).toList());
	}

	@Test
	void savePointsGroupBySaveIdNewestFirst() {
		UnitState event = ok(EVENT, version(1, "s1", at(1), false), version(4, "s3", at(30), false));
		UnitState booking = ok(BOOKING, version(2, "s1", at(2), false), version(3, "s2", at(10), false));

		List<SavePoint> points = savePoints(event, booking);

		assertEquals(List.of("s3", "s2", "s1"), points.stream().map(SavePoint::saveId).toList());
		assertEquals(at(30), points.get(0).at());
		assertEquals(at(10), points.get(1).at());
	}

	@Test
	void savePointTakesTimeMachineAndOperatorFromItsLatestVersion() {
		UnitState event = ok(EVENT, version(1, "s1", at(1), false, "Kasse 1", "Anna"));
		UnitState booking = ok(BOOKING, version(2, "s1", at(5), false, "Kasse 2", "Bernd"));

		SavePoint point = savePoints(event, booking).get(0);

		assertEquals(at(5), point.at());
		assertEquals("Kasse 2", point.machineName());
		assertEquals("Bernd", point.operator());
	}

	@Test
	void savePointsWithTheSameTimeAreOrderedBySaveId() {
		UnitState event = ok(EVENT, version(1, "b", at(1), false));
		UnitState booking = ok(BOOKING, version(2, "a", at(1), false));

		List<SavePoint> points = savePoints(event, booking);

		assertEquals(List.of("a", "b"), points.stream().map(SavePoint::saveId).toList());
	}

	@Test
	void savePointCountsUnits() {
		UnitState event = ok(EVENT, version(1, "s1", at(1), false), version(4, "s2", at(20), false));
		UnitState presentation = ok(PRESENTATION, version(2, "s1", at(1), false));
		UnitState booking = ok(BOOKING, version(3, "s1", at(1), false), version(5, "s1", at(2), false));

		Map<String, Integer> counts = new HashMap<>();
		for (SavePoint point : savePoints(event, presentation, booking)) {
			counts.put(point.saveId(), point.unitCount());
		}

		// Eine Einheit zählt je Speichervorgang einmal, auch mit mehreren Versionen darin
		assertEquals(Map.of("s1", 3, "s2", 1), counts);
	}

	@Test
	void targetIsLastChainVersionNotAfterT() {
		Version first = version(1, "s1", at(1), false);
		Version second = version(2, "s2", at(10), false);
		Version third = version(3, "s3", at(20), false);
		UnitState event = ok(EVENT, first, second, third);

		UnitSnapshot target = RestorePlanner.targetsAt(List.of(event), at(10)).get(EVENT);

		assertEquals(second.id(), target.version());
		assertEquals(Status.OK, target.status());
		assertFalse(target.deleted());
		assertEquals(second.payload(), target.payload());
	}

	@Test
	void targetCanBeADeletion() {
		UnitState event = ok(EVENT, version(1, "s1", at(1), false), version(2, "s2", at(10), true));

		UnitSnapshot target = RestorePlanner.targetsAt(List.of(event), at(15)).get(EVENT);

		assertTrue(target.deleted());
	}

	@Test
	void unitCreatedAfterTHasNoTarget() {
		UnitState event = ok(EVENT, version(1, "s1", at(1), false));
		UnitState booking = ok(BOOKING, version(2, "s2", at(10), false));

		Map<UnitKey, UnitSnapshot> targets = RestorePlanner.targetsAt(List.of(event, booking), at(5));

		assertEquals(Set.of(EVENT), targets.keySet());
		assertNull(targets.get(BOOKING));
	}

	@Test
	void lostVersionsAreNeverTargets() {
		Version valid = version(1, "s1", at(1), false);
		Version lost = version(2, "s2", at(5), false);
		UnitState event = new UnitState(EVENT, Status.OK, List.of(valid), List.of(), List.of(List.of(lost.id())), null,
				Set.of());

		UnitSnapshot target = RestorePlanner.targetsAt(List.of(event), at(10)).get(EVENT);
		List<SavePoint> points = savePoints(event);

		assertEquals(valid.id(), target.version());
		assertEquals(1, points.size());
		assertEquals("s1", points.get(0).saveId());
	}

	@Test
	void lockedOrWaitingUnitBlocksRestore() {
		Version version = version(1, "s1", at(1), false);
		UnitState fine = ok(EVENT, version);

		LockedUnitException damaged = assertThrows(LockedUnitException.class,
				() -> requireRestorable(List.of(fine, state(BOOKING, Status.LOCKED_DAMAGED, version))));
		LockedUnitException format = assertThrows(LockedUnitException.class,
				() -> requireRestorable(List.of(fine, state(BOOKING, Status.LOCKED_FORMAT, version))));
		LockedUnitException waiting = assertThrows(LockedUnitException.class,
				() -> requireRestorable(List.of(fine, state(BOOKING, Status.WAITING, version))));

		String path = BOOKING.relativePath();
		assertEquals(StoreMessages.damaged(path), damaged.getMessage());
		assertEquals(StoreMessages.newerFormat(path), format.getMessage());
		assertEquals(path + " wartet noch auf eine Datei aus dem gemeinsamen Ordner. Es wurde nichts gespeichert.",
				waiting.getMessage());
		requireRestorable(List.of(fine));
	}

	/**
	 * Der aktuelle Stand der Vorstellung ist inhaltlich zu neu (ein ausstehendes rohes Zurücksetzen, die Kette selbst ist
	 * {@code OK}): Eine Buchung darunter lässt sich nicht wiederherstellen (SYS-009 mit der Vorstellung, KARV-135).
	 */
	@Test
	void unitUnderContentLockedAncestorBlocksRestore() {
		Version version = version(1, "s1", at(1), false);
		Version reset = version(2, "s2", at(2), false).withNewerContent(true);
		UnitState presentation = new UnitState(PRESENTATION, Status.OK, List.of(version), List.of(reset), List.of(), null,
				Set.of());
		assertTrue(presentation.contentLocked());

		LockedUnitException e = assertThrows(LockedUnitException.class,
				() -> requireRestorable(List.of(ok(BOOKING, version), presentation)));

		assertEquals(StoreMessages.newerFormat(PRESENTATION.relativePath()), e.getMessage());
		requireRestorable(List.of(ok(BOOKING, version), ok(PRESENTATION, version)));
	}

	@Test
	void lockedUnitIsNamedByItsLabelWhenOneIsInstalled() {
		Version version = version(1, "s1", at(1), false);
		LockedUnitException.useLabels(unit -> unit.equals(BOOKING) ? "Gala › Samstag › Tisch 1, Sitz 2" : null);
		try {
			LockedUnitException damaged = assertThrows(LockedUnitException.class,
					() -> requireRestorable(List.of(state(BOOKING, Status.LOCKED_DAMAGED, version))));
			LockedUnitException format = assertThrows(LockedUnitException.class,
					() -> requireRestorable(List.of(state(EVENT, Status.LOCKED_FORMAT, version))));

			assertEquals(StoreMessages.damaged("Gala › Samstag › Tisch 1, Sitz 2"), damaged.getMessage());
			assertEquals(StoreMessages.newerFormat(EVENT.relativePath()), format.getMessage(), "ohne Bezeichnung der Pfad");

			LockedUnitException.useLabels(unit -> {
				throw new IllegalStateException("kein Verlauf");
			});
			LockedUnitException failing = assertThrows(LockedUnitException.class,
					() -> requireRestorable(List.of(state(BOOKING, Status.LOCKED_DAMAGED, version))));
			assertEquals(StoreMessages.damaged(BOOKING.relativePath()), failing.getMessage());
		} finally {
			LockedUnitException.useLabels(null);
		}
	}

	// Gesperrte Einheiten nach dem Zielstand (KARV-135) -->

	/** Kopf; Vorstellung bis Minute 10 lebend, dann gelöscht; darunter eine Buchung mit neuerem Format. */
	private static List<UnitState> lockedBookingUnderPresentationDeletedAt10() {
		return List.of(ok(EVENT, version(1, "s1", at(1), false)),
				ok(PRESENTATION, version(2, "s1", at(1), false), version(3, "s2", at(10), true)),
				state(BOOKING, Status.LOCKED_FORMAT, version(4, "s1", at(1), false)));
	}

	/**
	 * Unter einer gelöschten Vorstellung zeigt das Programm nichts. Bleibt sie auch im Zielstand gelöscht, fasst das
	 * Wiederherstellen ihre gesperrte Buchung nicht an, und die Buchung blockiert es nicht.
	 */
	@Test
	void lockedBookingUnderPresentationDeletedAlsoInTargetIsPassedOver() {
		requireRestorable(lockedBookingUnderPresentationDeletedAt10(), at(15));
	}

	/** Ein Wiederherstellen, das die Vorstellung zurückbringt, brächte die gesperrte Buchung mit: SYS-009. */
	@Test
	void revivingPresentationWithLockedBookingIsRefused() {
		LockedUnitException e = assertThrows(LockedUnitException.class,
				() -> requireRestorable(lockedBookingUnderPresentationDeletedAt10(), at(5)));

		assertEquals(StoreMessages.newerFormat(BOOKING.relativePath()), e.getMessage());
	}

	/**
	 * Lebt die Vorstellung jetzt und ist sie nur im Zielstand gelöscht, löschte das Wiederherstellen sie samt der gesperrten
	 * Buchung darunter (wie das Löschen einer Vorstellung): abgelehnt.
	 */
	@Test
	void lockedBookingUnderPresentationDeletedOnlyInTargetIsRefused() {
		List<UnitState> states = List.of(ok(EVENT, version(1, "s1", at(1), false)),
				ok(PRESENTATION, version(2, "s1", at(1), false), version(3, "s2", at(10), true),
						version(5, "s3", at(20), false)),
				state(BOOKING, Status.LOCKED_DAMAGED, version(4, "s1", at(1), false)));

		LockedUnitException e = assertThrows(LockedUnitException.class, () -> requireRestorable(states, at(15)));

		assertEquals(StoreMessages.damaged(BOOKING.relativePath()), e.getMessage());
	}

	/**
	 * Das Ziel-Glied einer Einheit ist inhaltlich zu neu (ein späteres Glied ist wieder lesbar, die Kette also {@code OK}):
	 * Es ins Modell zu lesen schriebe einen Inhalt, den die anderen sperren. SYS-009 mit der Einheit.
	 */
	@Test
	void restoreToContentTargetFails() {
		Version newer = version(2, "s2", at(5), false).withNewerContent(true);
		List<UnitState> states = List.of(ok(EVENT, version(1, "s1", at(1), false)),
				ok(BOOKING, version(3, "s1", at(1), false), newer, version(4, "s3", at(10), false)));

		LockedUnitException e = assertThrows(LockedUnitException.class, () -> requireRestorable(states, at(7)));

		assertEquals(StoreMessages.newerFormat(BOOKING.relativePath()), e.getMessage());
		requireRestorable(states, at(12));
		requireRestorable(states, at(2));
	}

	// <-- Gesperrte Einheiten nach dem Zielstand
}
