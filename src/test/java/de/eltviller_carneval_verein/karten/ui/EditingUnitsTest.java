package de.eltviller_carneval_verein.karten.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Booking;

/**
 * Die Registratur der Eingaben, die offen sind. {@code watch} braucht eine Tabelle und damit das JavaFX-Toolkit (das
 * Erzeugen eines {@code TableView} scheitert ohne); es wird von Hand geprüft.
 */
class EditingUnitsTest {

	private EditingUnits editing;
	private final AtomicInteger ended = new AtomicInteger();

	@BeforeEach
	void setUp() {
		editing = new EditingUnits();
		editing.addEndListener(ended::incrementAndGet);
	}

	@Test
	void modelIsEditingBetweenBeginAndEnd() {
		Booking booking = new Booking();
		Object owner = new Object();

		assertFalse(editing.isEditing(booking));
		editing.begin(owner, List.of(booking));
		assertTrue(editing.isEditing(booking));
		editing.end(owner);

		assertFalse(editing.isEditing(booking));
		assertEquals(1, ended.get());
	}

	@Test
	void editingCountsByIdentityNotByEquality() {
		Booking edited = new Booking();
		Booking copy = new Booking();
		copy.setId(edited.getId());
		editing.begin(new Object(), List.of(edited));

		assertTrue(editing.isEditing(edited));
		assertFalse(editing.isEditing(copy));
		assertFalse(editing.isEditing(null));
	}

	@Test
	void modelStaysEditingWhileAnotherOwnerStillHoldsIt() {
		Booking booking = new Booking();
		Object popup = new Object();
		Object table = new Object();
		editing.begin(popup, List.of(booking));
		editing.begin(table, List.of(booking));

		editing.end(popup);

		assertTrue(editing.isEditing(booking));
		editing.end(table);
		assertFalse(editing.isEditing(booking));
		assertEquals(2, ended.get());
	}

	@Test
	void endOfAnOwnerWithoutRegistrationIsSilent() {
		editing.end(new Object());

		assertEquals(0, ended.get());
	}

	@Test
	void beginAgainReplacesWhatTheOwnerHeldAndAnnouncesTheEndOfTheDroppedOnes() {
		Booking first = new Booking();
		Booking second = new Booking();
		Object table = new Object();
		editing.begin(table, List.of(first));

		editing.begin(table, List.of(second));

		assertFalse(editing.isEditing(first));
		assertTrue(editing.isEditing(second));
		assertEquals(1, ended.get(), "die Bearbeitung der ersten Zeile ist zu Ende");
	}

	@Test
	void beginWithTheSameModelsAnnouncesNothing() {
		Booking booking = new Booking();
		Object popup = new Object();
		editing.begin(popup, List.of(booking));

		editing.begin(popup, List.of(booking));

		assertTrue(editing.isEditing(booking));
		assertEquals(0, ended.get());
	}

	@Test
	void failingEndListenerDoesNotHideTheEnd() {
		EditingUnits other = new EditingUnits();
		AtomicInteger later = new AtomicInteger();
		other.addEndListener(() -> {
			throw new IllegalStateException("kaputt");
		});
		other.addEndListener(later::incrementAndGet);
		Booking booking = new Booking();
		Object owner = new Object();
		other.begin(owner, List.of(booking));

		other.end(owner);

		assertFalse(other.isEditing(booking));
		assertEquals(1, later.get());
	}
}
