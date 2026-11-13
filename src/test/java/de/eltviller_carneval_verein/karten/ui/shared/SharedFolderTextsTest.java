package de.eltviller_carneval_verein.karten.ui.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.store.SyncState;

class SharedFolderTextsTest {

	private static final ZoneId UTC = ZoneOffset.UTC;
	private static final String DIR = "D:\\Nextcloud\\Karten";

	@Test
	void progressTextWithoutValueHasNoPercentage() {
		assertEquals("Verbinde mit dem gemeinsamen Ordner …", SharedFolderTexts.progressText(null));
	}

	@Test
	void progressTextShowsWholePercentRoundedDown() {
		assertEquals("Verbinde mit dem gemeinsamen Ordner … (0 %)", SharedFolderTexts.progressText(0.0));
		assertEquals("Verbinde mit dem gemeinsamen Ordner … (42 %)", SharedFolderTexts.progressText(0.429));
		assertEquals("Verbinde mit dem gemeinsamen Ordner … (99 %)", SharedFolderTexts.progressText(0.999));
		assertEquals("Verbinde mit dem gemeinsamen Ordner … (100 %)", SharedFolderTexts.progressText(1.0));
	}

	@Test
	void connectedNamesTheFolder() {
		assertEquals("Verbunden mit E:\\ECV\\Kartenverwaltung.", SharedFolderTexts.connected("E:\\ECV\\Kartenverwaltung"));
	}

	@Test
	void outageHintStartsWithTheCause() {
		assertEquals("Der Ordner fehlt. Der Abgleich geht weiter, sobald der Ordner wieder erreichbar ist. Öffnest du die Kartenverwaltung"
				+ " jetzt, fehlen eventuell Verkäufe anderer Rechner; ein Platz, den du dann verkaufst, kann zum Konflikt werden.",
				SharedFolderTexts.outageHint("Der Ordner fehlt."));
	}

	@Test
	void outageHintWithoutCauseLeavesThePlaceholderOut() {
		assertEquals("Der Abgleich geht weiter, sobald der Ordner wieder erreichbar ist. Öffnest du die Kartenverwaltung jetzt, fehlen"
				+ " eventuell Verkäufe anderer Rechner; ein Platz, den du dann verkaufst, kann zum Konflikt werden.",
				SharedFolderTexts.outageHint(null));
		assertEquals(SharedFolderTexts.outageHint(null), SharedFolderTexts.outageHint("  "));
	}

	@Test
	void statusFollowsTheSyncState() {
		Instant at = Instant.parse("2026-10-31T14:05:09Z");

		assertEquals("Nicht verbunden. Dieser Rechner arbeitet allein.",
				SharedFolderTexts.status(SyncState.AUS, null, null, null, UTC));
		assertEquals("Verbunden mit " + DIR + ". Letzter Abgleich: 31.10.2026 14:05:09.",
				SharedFolderTexts.status(SyncState.AKTUELL, DIR, at, null, UTC));
		assertEquals("Verbunden mit " + DIR + ". Änderungen werden hochgeladen.",
				SharedFolderTexts.status(SyncState.AUSSTEHEND, DIR, at, null, UTC));
		assertEquals("Offline: " + DIR + " ist nicht erreichbar. Verkäufe sind vorläufig.",
				SharedFolderTexts.status(SyncState.OFFLINE, DIR, at, null, UTC));
		assertEquals("Abgleich gestört: Zugriff verweigert",
				SharedFolderTexts.status(SyncState.FEHLER, DIR, at, "Zugriff verweigert", UTC));
		assertEquals("Der Ordner " + DIR + " ist erreichbar. Entscheide, was mit den Daten dieses Rechners passiert.",
				SharedFolderTexts.status(SyncState.ENTSCHEIDUNG, DIR, at, null, UTC));
	}

	@Test
	void statusWithoutSyncYetSaysSo() {
		assertEquals("Verbunden mit " + DIR + ". Letzter Abgleich: noch keiner.",
				SharedFolderTexts.status(SyncState.AKTUELL, DIR, null, null, UTC));
	}

	@Test
	void errorStatusWithoutKnownCauseEndsAfterTheLabel() {
		assertEquals("Abgleich gestört", SharedFolderTexts.status(SyncState.FEHLER, DIR, null, null, UTC));
		assertEquals("Abgleich gestört", SharedFolderTexts.status(SyncState.FEHLER, DIR, null, " ", UTC));
	}

	@Test
	void missingStateCountsAsNotConnected() {
		assertEquals("Nicht verbunden. Dieser Rechner arbeitet allein.",
				SharedFolderTexts.status(null, null, null, null, UTC));
	}

	@Test
	void lockedLinkCountsTheEntries() {
		assertEquals("1 gesperrter Eintrag", SharedFolderTexts.lockedLink(1));
		assertEquals("2 gesperrte Einträge", SharedFolderTexts.lockedLink(2));
		assertEquals("12 gesperrte Einträge", SharedFolderTexts.lockedLink(12));
	}

	@Test
	void lockedConfirmationNamesTheEntry() {
		assertEquals("„Gala › Samstag“ auf den letzten lesbaren Stand zurücksetzen? Die eine beschädigte Änderung geht"
				+ " dabei verloren, alles davor bleibt. Stammt der Stand davor von einer neueren Programmversion, bleibt der"
				+ " Eintrag gesperrt, bis die Kartenverwaltung aktualisiert ist.", SharedFolderTexts.lockedConfirm("Gala › Samstag"));
	}

	@Test
	void lockedResetFailureNamesEntryAndCause() {
		assertEquals("„Gala › Samstag“ ließ sich nicht zurücksetzen: Platte voll",
				SharedFolderTexts.lockedResetFailed("Gala › Samstag", "Platte voll"));
	}

	@Test
	void skippedFooterNamesTheEntryAndTheSubtree() {
		assertEquals("Nicht abgeglichen: Gala › Samstag", SharedFolderTexts.skippedFooter("Gala › Samstag", false));
		assertEquals("Nicht abgeglichen: Gala › Samstag mit allem darunter",
				SharedFolderTexts.skippedFooter("Gala › Samstag", true));
	}
}
