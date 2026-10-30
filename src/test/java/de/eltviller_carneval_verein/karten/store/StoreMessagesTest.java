package de.eltviller_carneval_verein.karten.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
	void textsMatchSpec() {
		assertEquals("SYS-005: Der gemeinsame Ordner \\\\server\\karten ist nicht erreichbar. Du kannst weiterarbeiten; "
				+ "neue Verkäufe und Änderungen sind vorläufig, bis sie hochgeladen sind.",
				StoreMessages.unreachable("\\\\server\\karten"));
		assertEquals("SYS-006: Der Abgleich mit dem gemeinsamen Ordner E:\\ECV ist fehlgeschlagen: Platte voll",
				StoreMessages.syncFailed("E:\\ECV", "Platte voll"));
		assertEquals("SYS-007: Die beschädigte Datei E:\\ECV\\data\\halls\\h1\\v.json wurde durch eine geprüfte Kopie "
				+ "ersetzt; die beschädigte liegt als v.json.corrupt-2026-10-26_19-04-11 daneben.",
				StoreMessages.repaired("E:\\ECV\\data\\halls\\h1\\v.json", "v.json.corrupt-2026-10-26_19-04-11"));
		assertEquals("SYS-008: halls/h1 ist beschädigt, und es gibt keine unbeschädigte Kopie. Änderungen daran sind "
				+ "gesperrt, bis sie auf den letzten lesbaren Stand zurückgesetzt wird.", StoreMessages.damaged("halls/h1"));
		assertEquals("SYS-009: halls/h1 wurde mit einer neueren Version der Kartenverwaltung gespeichert. Bitte "
				+ "aktualisiere die Kartenverwaltung auf diesem Rechner.", StoreMessages.newerFormat("halls/h1"));
		assertEquals("SYS-010: Die Kartenverwaltung läuft auf diesem Rechner bereits.", StoreMessages.alreadyRunning());
		assertEquals("SYS-011: Ein anderer Rechner verwendet dieselbe Rechner-Kennung (wahrscheinlich eine kopierte "
				+ "Installation). Dieser Rechner hat eine neue Kennung erhalten.", StoreMessages.duplicateMachine());
		assertEquals("SYS-012: Im gemeinsamen Ordner liegt events/e1/v.json mit anderem Inhalt als lokal. Es wurde "
				+ "nichts überschrieben.", StoreMessages.contentMismatch("events/e1/v.json"));
		assertEquals("SYS-013: halls/h1 ließ sich wiederholt nicht mit dem gemeinsamen Ordner abgleichen und wurde "
				+ "übersprungen: AccessDeniedException: E:\\ECV\\data\\halls\\h1. Der nächste Abgleich versucht es erneut.",
				StoreMessages.skipped("halls/h1", "AccessDeniedException: E:\\ECV\\data\\halls\\h1"));
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
