package de.eltviller_carneval_verein.karten.ui.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.store.StoreMessages;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.UnitState.Status;

class LockedUnitsTest {

	private static final UnitKey BOOKING = UnitKey.booking("e1", "p1", "s1");
	private static final UnitKey PRESENTATION = UnitKey.presentation("e1", "p1");
	private static final UnitKey HALL = UnitKey.hall("h1");

	/** Der Versionsspeicher nennt die Buchung zunächst als zurücksetzbar (die Reparatur aus dem Ordner ist gescheitert). */
	private final FakeService service = new FakeService(BOOKING);
	/** Die Aufgaben für den Hintergrund-Thread und den FX-Thread, die der Test von Hand abarbeitet. */
	private final Queue<Runnable> background = new ArrayDeque<>();
	private final Queue<Runnable> fx = new ArrayDeque<>();
	private final List<Set<UnitKey>> applied = new ArrayList<>();
	private final List<String> failures = new ArrayList<>();
	private int changed;
	private Map<UnitKey, String> names = new HashMap<>(Map.of(BOOKING, "Gala › Samstag › Tisch A3, Sitz 2",
			PRESENTATION, "Gala › Samstag", HALL, "Festhalle"));
	private final Function<UnitKey, String> labels = unit -> names.get(unit);

	private final LockedUnits locked = new LockedUnits(service, background::add, fx::add, applied::add,
			(unit, cause) -> failures.add(unit.relativePath() + ": " + cause), () -> changed++);

	private void runBackground() {
		while (!background.isEmpty()) {
			background.poll().run();
		}
	}

	private void runFx() {
		while (!fx.isEmpty()) {
			fx.poll().run();
		}
	}

	private void runAll() {
		while (!background.isEmpty() || !fx.isEmpty()) {
			runBackground();
			runFx();
		}
	}

	@Test
	void lockMessageIsStaleOnceItsUnitIsNoLongerLocked() {
		locked.add(BOOKING, Status.LOCKED_DAMAGED);
		String booking = StoreMessages.damaged("Gala › Samstag › Tisch A3, Sitz 2");
		String hall = StoreMessages.newerFormat("Festhalle");

		assertFalse(locked.isStaleLockMessage(booking, labels));
		assertFalse(locked.isStaleLockMessage("Nicht gespeichert: " + booking, labels));
		assertFalse(locked.isStaleLockMessage(StoreMessages.damaged(BOOKING.relativePath()), labels), "auch mit Pfad");
		assertTrue(locked.isStaleLockMessage(hall, labels), "die Halle ist nicht gesperrt");
		assertFalse(locked.isStaleLockMessage("Gespeichert", labels), "keine Meldung einer Sperre");
		assertFalse(locked.isStaleLockMessage(StoreMessages.syncFailed("D:\\x", "IOException"), labels));

		names.clear();
		assertFalse(locked.isStaleLockMessage(StoreMessages.damaged(BOOKING.relativePath()), labels),
				"ohne Bezeichnung zählt der Pfad");
	}

	@Test
	void damagedResettableUnitOffersReset() {
		locked.add(BOOKING, Status.LOCKED_DAMAGED);

		List<LockedUnits.Row> rows = locked.rows(labels, Set.of(BOOKING));

		assertEquals(List.of(new LockedUnits.Row(BOOKING, "Gala › Samstag › Tisch A3, Sitz 2",
				"Beschädigt, keine heile Kopie", true)), rows);
	}

	@Test
	void damagedButRepairPendingOffersNoReset() {
		locked.add(BOOKING, Status.LOCKED_DAMAGED);

		List<LockedUnits.Row> rows = locked.rows(labels, Set.of());

		assertEquals(1, rows.size());
		assertEquals("Beschädigt, keine heile Kopie", rows.get(0).cause());
		assertFalse(rows.get(0).resettable(), "die Reparatur aus dem Ordner läuft noch oder fehlt: kein Knopf");
	}

	@Test
	void newerFormatOffersNoResetAndNamesUpdate() {
		locked.add(PRESENTATION, Status.LOCKED_FORMAT);

		// selbst wenn der Versionsspeicher die Einheit nennte: Ein neueres Format braucht ein neueres Programm
		List<LockedUnits.Row> rows = locked.rows(labels, Set.of(PRESENTATION));

		assertEquals(List.of(new LockedUnits.Row(PRESENTATION, "Gala › Samstag",
				"Neuere Programmversion: Kartenverwaltung aktualisieren", false)), rows);
	}

	@Test
	void resetAppliesTheUnitAndRemovesTheRow() {
		locked.add(BOOKING, Status.LOCKED_DAMAGED);
		service.resettable.add(BOOKING);

		locked.reset(BOOKING);

		assertEquals(List.of(), service.resets, "nichts auf dem Aufrufer-Thread");
		runBackground();
		assertEquals(List.of(BOOKING), service.resets);
		assertEquals(List.of(), applied, "das Einarbeiten gehört auf den FX-Thread");
		assertEquals(1, locked.count());

		runFx();

		assertEquals(List.of(Set.of(BOOKING)), applied);
		assertEquals(0, locked.count());
		assertEquals(List.of(), locked.rows(labels, Set.of()));
		assertEquals(1, changed, "Link und Dialog erfahren davon");
		assertEquals(List.of(), failures);
	}

	@Test
	void unitsLockedAtLoadAreListed() {
		Map<UnitKey, Status> atLoad = new LinkedHashMap<>();
		atLoad.put(HALL, Status.LOCKED_DAMAGED);
		atLoad.put(PRESENTATION, Status.LOCKED_FORMAT);

		locked.addAll(atLoad);

		assertEquals(2, locked.count());
		List<LockedUnits.Row> rows = locked.rows(labels, Set.of(HALL));
		assertEquals(List.of("Festhalle", "Gala › Samstag"), rows.stream().map(LockedUnits.Row::entry).toList());
		assertTrue(rows.get(0).resettable());
		assertFalse(rows.get(1).resettable());
	}

	@Test
	void sameUnitReportedTwiceCountsOnce() {
		locked.add(BOOKING, Status.LOCKED_DAMAGED);
		locked.add(BOOKING, Status.LOCKED_DAMAGED);

		assertEquals(1, locked.count());
	}

	@Test
	void unitsThatAreNotLockedAreNotListed() {
		locked.add(BOOKING, Status.OK);
		locked.add(HALL, Status.WAITING);

		assertEquals(0, locked.count());
	}

	@Test
	void entryFallsBackToThePathWithoutAName() {
		names = new HashMap<>();
		locked.add(BOOKING, Status.LOCKED_DAMAGED);

		assertEquals(BOOKING.relativePath(), locked.rows(labels, Set.of()).get(0).entry());
	}

	@Test
	void entryFallsBackToThePathWhenTheNameCannotBeDerived() {
		locked.add(BOOKING, Status.LOCKED_DAMAGED);

		Function<UnitKey, String> failing = unit -> {
			throw new IllegalStateException("kein Verlauf");
		};

		assertEquals(BOOKING.relativePath(), locked.rows(failing, Set.of()).get(0).entry());
	}

	@Test
	void warningNamesTheUnitByItsLabel() {
		assertEquals(StoreMessages.damaged("Festhalle"), LockedUnits.warning(labels, HALL, Status.LOCKED_DAMAGED));
		assertEquals(StoreMessages.newerFormat("Festhalle"), LockedUnits.warning(labels, HALL, Status.LOCKED_FORMAT));
		assertEquals(StoreMessages.damaged(BOOKING.relativePath()),
				LockedUnits.warning(unit -> null, BOOKING, Status.LOCKED_DAMAGED), "ohne Bezeichnung steht der Pfad da");
		assertNull(LockedUnits.warning(labels, HALL, Status.OK));
	}

	@Test
	void refreshAsksTheServiceForEveryUnitInTheBackgroundAndDropsHealedOnes() {
		locked.add(BOOKING, Status.LOCKED_DAMAGED);
		locked.add(HALL, Status.LOCKED_DAMAGED);
		service.states.put(BOOKING, Status.LOCKED_DAMAGED);
		service.states.put(HALL, Status.OK);
		List<Integer> counts = new ArrayList<>();

		locked.refresh(() -> counts.add(locked.count()));

		assertEquals(List.of(), service.asked, "nicht auf dem Aufrufer-Thread");
		runBackground();
		assertEquals(List.of(BOOKING, HALL), service.asked);
		assertEquals(List.of(), counts, "das Ergebnis wird erst auf dem FX-Thread eingetragen");

		runFx();

		assertEquals(List.of(1), counts);
		assertEquals(1, locked.count());
		assertEquals(1, changed);
	}

	@Test
	void refreshTakesOverAChangedCause() {
		locked.add(BOOKING, Status.LOCKED_DAMAGED);
		service.states.put(BOOKING, Status.LOCKED_FORMAT);

		locked.refresh(() -> {
		});
		runAll();

		assertEquals(Status.LOCKED_FORMAT, locked.status(BOOKING));
	}

	@Test
	void refreshKeepsAUnitWhoseStateCannotBeRead() {
		locked.add(BOOKING, Status.LOCKED_DAMAGED);
		service.states.put(BOOKING, null);

		locked.refresh(() -> {
		});
		runAll();

		assertEquals(1, locked.count(), "ein Lesefehler heißt nicht, dass die Sperre weg ist");
	}

	@Test
	void failedResetKeepsTheRowAndReports() {
		locked.add(BOOKING, Status.LOCKED_DAMAGED);
		service.resetFailure = new IOException("Platte voll");
		service.states.put(BOOKING, Status.LOCKED_DAMAGED);

		locked.reset(BOOKING);
		runAll();

		assertEquals(1, locked.count());
		assertEquals(List.of(), applied);
		assertEquals(List.of(BOOKING.relativePath() + ": IOException: Platte voll"), failures);
	}

	@Test
	void resetOfAUnitThatIsNotDamagedAnymoreChecksItsStateAgain() {
		locked.add(BOOKING, Status.LOCKED_DAMAGED);
		service.resetFailure = new IllegalStateException("nicht mehr gesperrt");
		service.states.put(BOOKING, Status.OK);

		locked.reset(BOOKING);
		runAll();

		assertEquals(0, locked.count(), "die Sperre ist inzwischen weg");
		assertEquals(List.of(), applied);
		assertEquals(List.of(), failures, "nichts ist schiefgegangen, nur der Stand hat sich geändert");
	}

	@Test
	void resetIsSkippedWhenTheUnitIsNoLongerResettable() {
		locked.add(BOOKING, Status.LOCKED_DAMAGED);
		service.resettable.add(BOOKING);
		List<LockedUnits.Row> drawn = locked.rows(labels);
		assertTrue(drawn.get(0).resettable());

		// zwischen Zeichnen und Klick wird die Einheit wieder reparierbar (Vollabgleich, Abholen)
		service.resettable.remove(BOOKING);
		service.states.put(BOOKING, Status.LOCKED_DAMAGED);
		locked.reset(BOOKING);
		runAll();

		assertEquals(List.of(), service.resets, "eine heile Kopie im Ordner dürfte nicht überschrieben werden");
		assertEquals(List.of(), applied);
		assertEquals(List.of(), failures, "nichts ist schiefgegangen, die Zeile wird nur neu berechnet");
		assertEquals(1, locked.count());
		assertFalse(locked.rows(labels).get(0).resettable());
		assertTrue(service.asked.contains(BOOKING), "der Zustand wurde neu abgefragt");
	}

	@Test
	void failureCauseNamesTheExceptionType() {
		locked.add(BOOKING, Status.LOCKED_DAMAGED);
		service.resetFailure = new IOException("Platte voll");
		service.states.put(BOOKING, Status.LOCKED_DAMAGED);

		locked.reset(BOOKING);
		runAll();

		assertEquals(List.of(BOOKING.relativePath() + ": IOException: Platte voll"), failures);
	}

	@Test
	void secondResetWhileOneIsRunningIsIgnored() {
		locked.add(BOOKING, Status.LOCKED_DAMAGED);

		locked.reset(BOOKING);
		locked.reset(BOOKING);
		runAll();

		assertEquals(List.of(BOOKING), service.resets);
		assertEquals(List.of(Set.of(BOOKING)), applied);
	}

	@Test
	void failingApplyStillRemovesTheRow() {
		LockedUnits failing = new LockedUnits(service, background::add, fx::add, units -> {
			throw new IllegalStateException("Einarbeiten");
		}, (unit, cause) -> failures.add(cause), () -> changed++);
		failing.add(BOOKING, Status.LOCKED_DAMAGED);

		failing.reset(BOOKING);
		runAll();

		assertEquals(0, failing.count(), "zurückgesetzt ist die Einheit im Speicher, egal was das Modell macht");
		assertEquals(List.of(BOOKING), service.resets);
	}

	/** Der Versionsspeicher: was {@code unitState} meldet, was sich zurücksetzen lässt. */
	private static final class FakeService implements LockedUnits.Service {

		final Map<UnitKey, Status> states = new HashMap<>();
		final Set<UnitKey> resettable = new HashSet<>();
		final List<UnitKey> asked = new ArrayList<>();
		final List<UnitKey> resets = new ArrayList<>();
		Exception resetFailure;

		FakeService(UnitKey... resettableAtStart) {
			resettable.addAll(List.of(resettableAtStart));
		}

		@Override
		public Status status(UnitKey unit) throws IOException {
			asked.add(unit);
			Status state = states.get(unit);
			if (state == null) {
				throw new IOException("nicht lesbar");
			}
			return state;
		}

		@Override
		public Set<UnitKey> resettableUnits() {
			return Set.copyOf(resettable);
		}

		@Override
		public void resetToLastReadable(UnitKey unit) throws IOException {
			resets.add(unit);
			if (resetFailure instanceof IOException io) {
				throw io;
			}
			if (resetFailure instanceof RuntimeException runtime) {
				throw runtime;
			}
			resettable.remove(unit);
		}
	}
}
