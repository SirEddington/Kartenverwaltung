package de.eltviller_carneval_verein.karten.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.util.List;

import org.junit.jupiter.api.Test;

class StoreMessagesTest {

	@Test
	void codesAreTheNumbersFiveToThirteen() {
		assertEquals(List.of("SYS-005", "SYS-006", "SYS-007", "SYS-008", "SYS-009", "SYS-010", "SYS-011", "SYS-012",
				"SYS-013"),
				List.of(StoreMessages.SYS_UNREACHABLE, StoreMessages.SYS_SYNC_FAILED, StoreMessages.SYS_REPAIRED,
						StoreMessages.SYS_DAMAGED, StoreMessages.SYS_NEWER_FORMAT, StoreMessages.SYS_ALREADY_RUNNING,
						StoreMessages.SYS_DUPLICATE_MACHINE, StoreMessages.SYS_CONTENT_MISMATCH, StoreMessages.SYS_SKIPPED));
	}

	@Test
	void unreachableTextSaysChangesAreUploadedLater() {
		assertEquals("SYS-005: Der gemeinsame Ordner \\\\server\\karten ist nicht erreichbar. Du kannst weiterarbeiten: "
				+ "Neue Verkäufe und Änderungen sind vorläufig und werden hochgeladen, sobald der Ordner wieder erreichbar ist.",
				StoreMessages.unreachable("\\\\server\\karten"));
	}

	@Test
	void syncFailedTextPutsTheCauseInParenthesesAndPromisesRetries() {
		assertEquals("SYS-006: Der Abgleich mit dem gemeinsamen Ordner E:\\ECV ist gescheitert (Platte voll). Neue Verkäufe "
				+ "bleiben vorläufig; die Kartenverwaltung versucht es weiter.",
				StoreMessages.syncFailed("E:\\ECV", "Platte voll"));
	}

	@Test
	void repairedTextIsUnchanged() {
		assertEquals("SYS-007: Die beschädigte Datei E:\\ECV\\data\\halls\\h1\\v.json wurde durch eine geprüfte Kopie "
				+ "ersetzt; die beschädigte liegt als v.json.corrupt-2026-10-26_19-04-11 daneben.",
				StoreMessages.repaired("E:\\ECV\\data\\halls\\h1\\v.json", "v.json.corrupt-2026-10-26_19-04-11"));
	}

	@Test
	void damagedTextPointsToTheLockedEntriesInTheFooter() {
		assertEquals("SYS-008: halls/h1 ist beschädigt, und es gibt keine heile Kopie. Der Eintrag ist gesperrt; über "
				+ "„gesperrte Einträge“ in der Fußzeile kannst du ihn auf den letzten lesbaren Stand zurücksetzen.",
				StoreMessages.damaged("halls/h1"));
	}

	@Test
	void newerFormatTextSaysTheEntryIsLockedHere() {
		assertEquals("SYS-009: halls/h1 wurde mit einer neueren Version der Kartenverwaltung gespeichert und ist hier "
				+ "gesperrt. Aktualisiere die Kartenverwaltung unter Einstellungen → Info und Updates → Jetzt prüfen.",
				StoreMessages.newerFormat("halls/h1"));
	}

	@Test
	void alreadyRunningTextTellsToSwitchToTheOpenWindow() {
		assertEquals("SYS-010: Die Kartenverwaltung ist auf diesem Rechner schon geöffnet. Wechsle zum offenen Fenster.",
				StoreMessages.alreadyRunning());
	}

	@Test
	void duplicateMachineTextSaysNothingHasToBeDone() {
		assertEquals("SYS-011: Ein anderer Rechner verwendet dieselbe Rechner-Kennung, wahrscheinlich weil die Installation "
				+ "kopiert wurde. Dieser Rechner hat deshalb eine neue Kennung bekommen. Du musst nichts tun.",
				StoreMessages.duplicateMachine());
	}

	@Test
	void contentMismatchTextAsksToPassTheHintOn() {
		assertEquals("SYS-012: Im gemeinsamen Ordner liegt events/e1/v.json mit anderem Inhalt als auf diesem Rechner. Es "
				+ "wurde nichts überschrieben. Gib den Hinweis bitte an die Betreuung der Kartenverwaltung weiter.",
				StoreMessages.contentMismatch("events/e1/v.json"));
	}

	@Test
	void skippedTextNamesMissingForeignChanges() {
		assertEquals("SYS-013: halls/h1 ließ sich nicht abgleichen (AccessDeniedException: E:\\ECV\\data\\halls\\h1) und "
				+ "wurde übersprungen. Änderungen anderer Rechner daran fehlen hier, bis ein Abgleich gelingt.",
				StoreMessages.skipped("halls/h1", "AccessDeniedException: E:\\ECV\\data\\halls\\h1"));
	}

	@Test
	void lockedTextFollowsTheStatus() {
		UnitKey unit = UnitKey.hall("h1");

		assertEquals(StoreMessages.damaged("halls/h1"), StoreMessages.locked(unit, UnitState.Status.LOCKED_DAMAGED));
		assertEquals(StoreMessages.newerFormat("halls/h1"), StoreMessages.locked(unit, UnitState.Status.LOCKED_FORMAT));
		assertNull(StoreMessages.locked(unit, UnitState.Status.OK));
		assertNull(StoreMessages.locked(unit, UnitState.Status.WAITING));
	}

	/** KARV-135: Löschen und Umstellen mit gesperrten Einträgen darunter; die Meldung richtet sich nach der Ursache. */
	@Test
	void lockedBelowNamesTheObjectAndTheCause() {
		assertEquals("SYS-009: \"Freitag\" enthält Einträge, die mit einer neueren Programmversion gespeichert wurden. "
				+ "Löschen und Umstellen sind erst möglich, wenn sie wieder lesbar sind (Kartenverwaltung aktualisieren).",
				StoreMessages.lockedBelow("Freitag", UnitState.Status.LOCKED_FORMAT));
	}

	@Test
	void damagedCauseGivesSys008Text() {
		assertEquals("SYS-008: \"Freitag\" enthält beschädigte Einträge. Erst unter „Gesperrte Einträge“ zurücksetzen, "
				+ "dann löschen oder umstellen.", StoreMessages.lockedBelow("Freitag", UnitState.Status.LOCKED_DAMAGED));
		// ein Name mit Platzhalter-Zeichen bleibt, wie er ist
		assertEquals("SYS-008: \"A &1\" enthält beschädigte Einträge. Erst unter „Gesperrte Einträge“ zurücksetzen, "
				+ "dann löschen oder umstellen.", StoreMessages.lockedBelow("A &1", UnitState.Status.LOCKED_DAMAGED));
	}

	@Test
	void placeholdersAreAlwaysFilled() {
		for (String text : List.of(StoreMessages.unreachable("a"), StoreMessages.syncFailed("a", "b"),
				StoreMessages.repaired("a", "b"), StoreMessages.damaged("a"), StoreMessages.newerFormat("a"),
				StoreMessages.contentMismatch("a"), StoreMessages.skipped("a", "b"))) {
			assertFalse(text.matches("(?s).*&[1-9].*"), text);
		}
	}

	@Test
	void describeFallsBackToTheClassNameWithoutAMessage() {
		assertEquals("IOException", StoreMessages.describe(new IOException()));
		assertEquals("IllegalStateException", StoreMessages.describe(new IllegalStateException(" ")));
		assertEquals("AccessDeniedException: E:\\ECV\\data", StoreMessages.describe(new AccessDeniedException("E:\\ECV\\data")));
	}
}
