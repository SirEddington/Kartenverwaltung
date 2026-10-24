package de.eltviller_carneval_verein.karten.mirror;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MirrorMessagesTest {

	@Test
	void codesAreDistinctAndFormatted() {
		assertEquals("SYS-002", MirrorMessages.SYS_UNREACHABLE);
		assertEquals("SYS-003", MirrorMessages.SYS_SYNC_FAILED);
		assertEquals("SYS-004", MirrorMessages.SYS_ABORTED);
		assertNotEquals(MirrorMessages.SYS_UNREACHABLE, MirrorMessages.SYS_SYNC_FAILED);
		assertNotEquals(MirrorMessages.SYS_SYNC_FAILED, MirrorMessages.SYS_ABORTED);
		assertNotEquals(MirrorMessages.SYS_UNREACHABLE, MirrorMessages.SYS_ABORTED);

		String unreachable = MirrorMessages.unreachable("E:\\ECV");
		String failed = MirrorMessages.syncFailed("E:\\ECV", "Platte voll");
		String aborted = MirrorMessages.aborted("E:\\ECV");
		assertTrue(unreachable.startsWith("SYS-002: "), unreachable);
		assertTrue(failed.startsWith("SYS-003: "), failed);
		assertTrue(aborted.startsWith("SYS-004: "), aborted);
		for (String message : new String[] { unreachable, failed, aborted }) {
			assertTrue(message.matches("SYS-\\d{3}: .+"), message);
		}
	}

	@Test
	void textsContainPathAndCause() {
		String failed = MirrorMessages.syncFailed("E:\\ECV", "Platte voll");
		assertTrue(failed.contains("E:\\ECV"), failed);
		assertTrue(failed.contains("Platte voll"), failed);
		assertFalse(failed.contains("&1") || failed.contains("&2"), failed);

		String unreachable = MirrorMessages.unreachable("E:\\ECV");
		assertTrue(unreachable.contains("E:\\ECV"), unreachable);
		assertFalse(unreachable.contains("&"), unreachable);

		String aborted = MirrorMessages.aborted("E:\\ECV");
		assertTrue(aborted.contains("E:\\ECV"), aborted);
		assertFalse(aborted.contains("&"), aborted);
	}

	@Test
	void textsMatchTheSpecification() {
		assertEquals("SYS-002: Der Spiegelordner X ist nicht erreichbar. Du kannst lokal weiterarbeiten; "
				+ "die Änderungen werden übertragen, sobald er wieder erreichbar ist.", MirrorMessages.unreachable("X"));
		assertEquals("SYS-003: Der Abgleich mit dem Spiegelordner X ist fehlgeschlagen: Y",
				MirrorMessages.syncFailed("X", "Y"));
		assertEquals("SYS-004: Der Abgleich mit dem Spiegelordner X wurde abgebrochen: Lokal sind keine Events "
				+ "und Hallen vorhanden, im Spiegelordner würde alles gelöscht.", MirrorMessages.aborted("X"));
	}
}
