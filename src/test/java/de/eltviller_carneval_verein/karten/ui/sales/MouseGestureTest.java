package de.eltviller_carneval_verein.karten.ui.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Die Mausbedienung der Kartentabelle (KARV-193, Spec Abschnitt 14.5): vom Druck in einer Zeile bis zum Loslassen plus
 * Nachlaufzeit. Uhr und Planer sind gestellt, keine echten 500 ms.
 */
class MouseGestureTest {

	/** Eine geplante Prüfung: fällig zur Zeit {@code due}. */
	private record Planned(long due, Runnable task) {
	}

	private long now;
	private final List<Planned> planned = new ArrayList<>();
	private int ended;
	private final MouseGesture gesture = new MouseGesture(() -> now, (delay, task) -> planned.add(new Planned(now + delay, task)),
			() -> ended++);

	/** Stellt die Uhr vor und führt aus, was bis dahin fällig ist, in der Reihenfolge der Fälligkeit. */
	private void advance(long millis) {
		long target = now + millis;
		while (true) {
			Planned next = planned.stream().filter(p -> p.due() <= target).min(Comparator.comparingLong(Planned::due)).orElse(null);
			if (next == null) {
				break;
			}
			planned.remove(next);
			now = Math.max(now, next.due());
			next.task().run();
		}
		now = target;
	}

	@Test
	void wartetBisLoslassenUndNachlauf() {
		assertFalse(gesture.active(), "ohne Druck keine Bedienung");
		gesture.pressed();
		assertTrue(gesture.active());

		advance(2000);
		assertTrue(gesture.active(), "solange die Taste gedrückt ist, ohne Ende");

		gesture.released();
		advance(MouseGesture.AFTERGLOW_MILLIS - 1);
		assertTrue(gesture.active(), "in der Nachlaufzeit läuft sie weiter");
		assertEquals(0, ended);

		advance(1);
		assertFalse(gesture.active(), "nach Loslassen und 500 ms ist sie beendet");
		assertEquals(1, ended);
	}

	@Test
	void neuerDruckVerlaengert() {
		gesture.pressed();
		gesture.released();
		advance(300);
		gesture.pressed(); // zweiter Klick eines Doppelklicks
		advance(300);
		assertTrue(gesture.active(), "die Prüfung des ersten Loslassens gilt nicht mehr");
		gesture.released();
		advance(MouseGesture.AFTERGLOW_MILLIS - 1);
		assertTrue(gesture.active());
		assertEquals(0, ended);

		advance(1);
		assertFalse(gesture.active());
		assertEquals(1, ended, "eine Bedienung, ein Ende");
	}

	@Test
	void fruehePruefungWartetDenRestAb() {
		// jede Prüfung kommt bis zu 100 ms zu früh, mindestens aber 1 ms später
		MouseGesture early = new MouseGesture(() -> now, (delay, task) -> planned.add(new Planned(now + Math.max(1, delay - 100), task)),
				() -> ended++);
		early.pressed();
		early.released();

		advance(MouseGesture.AFTERGLOW_MILLIS - 100);
		assertTrue(early.active(), "kommt die Prüfung zu früh, gilt die Uhr");

		advance(100);
		assertFalse(early.active());
		assertEquals(1, ended);
	}

	@Test
	void ereignisOhneTasteZaehltAlsLoslassen() {
		gesture.pressed();
		gesture.mouseWithoutButton(); // das Loslassen ging verloren
		advance(MouseGesture.AFTERGLOW_MILLIS - 1);
		assertTrue(gesture.active(), "wie ein Loslassen: mit Nachlaufzeit");
		gesture.mouseWithoutButton(); // weitere Bewegung ohne Taste verlängert nicht
		advance(1);
		assertFalse(gesture.active());
		assertEquals(1, ended);
	}

	@Test
	void ereignisOhneTasteOhneDruckTutNichts() {
		gesture.mouseWithoutButton();
		gesture.released();
		advance(1000);
		assertFalse(gesture.active());
		assertEquals(0, ended, "ohne Druck beginnt und endet keine Bedienung");
		assertTrue(planned.isEmpty());
	}

	@Test
	void fokusverlustEndetSofort() {
		gesture.pressed();
		gesture.focusLost();
		assertFalse(gesture.active(), "ohne Nachlaufzeit");
		assertEquals(1, ended);

		gesture.pressed();
		gesture.released();
		gesture.focusLost(); // in der Nachlaufzeit
		assertFalse(gesture.active());
		assertEquals(2, ended);
		advance(1000);
		assertEquals(2, ended, "die geplante Prüfung beendet nichts mehr");
	}

	@Test
	void abhaengenEndetSofort() {
		gesture.pressed();
		gesture.detached();
		assertFalse(gesture.active());
		assertEquals(1, ended);
		gesture.released(); // das Loslassen kommt nach dem Abhängen
		advance(1000);
		assertEquals(1, ended);
	}

	@Test
	void endedGenauEinmal() {
		gesture.pressed();
		gesture.released();
		gesture.released();
		advance(MouseGesture.AFTERGLOW_MILLIS);
		gesture.focusLost();
		gesture.detached();
		advance(1000);
		assertEquals(1, ended, "genau einmal je beendeter Bedienung");

		gesture.focusLost();
		assertEquals(1, ended, "ohne Bedienung kein Ende");
	}
}
