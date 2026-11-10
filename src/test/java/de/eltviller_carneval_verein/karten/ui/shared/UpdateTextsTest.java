package de.eltviller_carneval_verein.karten.ui.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.update.AppVersion;
import de.eltviller_carneval_verein.karten.update.Grund;
import de.eltviller_carneval_verein.karten.update.Plattform;
import de.eltviller_carneval_verein.karten.update.UpdateInfo;
import de.eltviller_carneval_verein.karten.update.UpdateState;

class UpdateTextsTest {

	private static final ZoneId UTC = ZoneOffset.UTC;
	private static final Instant T = Instant.parse("2026-11-07T14:05:00Z");
	private static final AppVersion REV2 = AppVersion.parse("1.1.0", "2").orElseThrow();
	private static final UpdateState.Available AVAILABLE = new UpdateState.Available(new UpdateInfo(REV2, "F", false), T);
	private static final Optional<AppVersion> INSTALLED = AppVersion.parse("1.1.0", "1");

	@Test
	void indicatorTexts() {
		assertEquals("Update 1.1.0 (Rev. 2) verfügbar", UpdateTexts.indicatorText(REV2));
		assertEquals("Klicken, um die Kartenverwaltung zu aktualisieren", UpdateTexts.INDICATOR_TOOLTIP_UPDATE);
		assertEquals("Klicken für Details", UpdateTexts.INDICATOR_TOOLTIP_DETAILS);
		assertEquals("Update-Prüfung nicht möglich", UpdateTexts.CHECK_IMPOSSIBLE);
		assertEquals("Die Kartenverwaltung kann nicht nach Updates suchen. Klicken für Details.",
				UpdateTexts.CHECK_IMPOSSIBLE_TOOLTIP);
	}

	@Test
	void folderTexts() {
		AppVersion rel = AppVersion.parse("1.1.1", "0").orElseThrow();
		assertEquals("Update 1.1.0 (Rev. 2) im gemeinsamen Ordner", UpdateTexts.folderIndicatorText(REV2));
		assertEquals("Update 1.1.1 im gemeinsamen Ordner", UpdateTexts.folderIndicatorText(rel));
		assertEquals("Klicken, um die Kartenverwaltung aus dem gemeinsamen Ordner zu aktualisieren", UpdateTexts.FOLDER_TOOLTIP);
		assertEquals("„Kasse 2“ nutzt 1.1.1 – bitte aktualisieren", UpdateTexts.newerStationText("Kasse 2", rel));
		assertEquals("Klicken für Details", UpdateTexts.NEWER_STATION_TOOLTIP);
		assertEquals("Update im gemeinsamen Ordner", UpdateTexts.START_TITLE);
		assertEquals("Version 1.1.1 liegt im gemeinsamen Ordner. Jetzt aktualisieren? Die Kartenverwaltung schließt sich dafür;"
				+ " bricht die Installation ab, starte sie von Hand neu.", UpdateTexts.startText(rel));
		assertEquals("Später", UpdateTexts.BUTTON_LATER);
		assertEquals("Version 1.1.0 (Rev. 2) aus dem gemeinsamen Ordner installieren? Die Kartenverwaltung schließt sich"
				+ " dafür; bricht die Installation ab, starte sie von Hand neu.", UpdateTexts.confirmFolderText(REV2));
		assertEquals("Die Datei im gemeinsamen Ordner ist unvollständig oder verändert und wird nicht installiert.",
				UpdateTexts.ERROR_FOLDER_FILE);
		assertEquals("Das Update wird gerade auch für die anderen Kassen in den gemeinsamen Ordner geladen; das geht nach"
				+ " dem Neustart weiter.", UpdateTexts.DOWNLOAD_RUNNING_NOTE);
		assertEquals("Update wird in den gemeinsamen Ordner geladen …", UpdateTexts.LOADING);
		assertEquals("Im gemeinsamen Ordner liegt Version 1.1.1, neuer als die Update-Quelle (zurückgezogen?)",
				UpdateTexts.withdrawnText(rel));
		assertEquals("An einer Kasse mit Internet „Jetzt prüfen“ – dann liegt das Update bald im gemeinsamen Ordner.",
				UpdateTexts.NEXT_STEP_PACKAGE);
		assertEquals("Von Hand aktualisieren.", UpdateTexts.NEXT_STEP_MANUAL);
		assertEquals("Kassen am gemeinsamen Ordner", UpdateTexts.STATIONS_TITLE);
	}

	@Test
	void stationLine() {
		de.eltviller_carneval_verein.karten.update.StationEntry e = new de.eltviller_carneval_verein.karten.update.StationEntry(
				"bbbb2222", "Kasse 2", "ecv-karten", REV2, "WINDOWS_PAKET", T);
		assertEquals("Kasse 2 – 1.1.0 (Rev. 2) – zuletzt gestartet 07.11.2026 14:05 – bbbb2222",
				UpdateTexts.stationLine(e, false, UTC));
		assertEquals("Kasse 2 – 1.1.0 (Rev. 2) – zuletzt gestartet 07.11.2026 14:05 – bbbb2222 (diese Kasse)",
				UpdateTexts.stationLine(e, true, UTC));
	}

	@Test
	void confirmDirectWithRunningDownload() {
		assertTrue(UpdateTexts.confirmDirectText(REV2, false, true).endsWith(" " + UpdateTexts.DOWNLOAD_RUNNING_NOTE));
		assertEquals(UpdateTexts.confirmDirectText(REV2, true), UpdateTexts.confirmDirectText(REV2, true, false));
	}

	@Test
	void reasons() {
		assertEquals("keine Verbindung zum Update-Server", UpdateTexts.reason(Grund.NETWORK));
		assertEquals("Zeitüberschreitung", UpdateTexts.reason(Grund.TIMEOUT));
		assertEquals("Zertifikat abgelehnt (Virenschutz oder Proxy?)", UpdateTexts.reason(Grund.CERTIFICATE));
		assertEquals("vom Proxy abgelehnt", UpdateTexts.reason(Grund.PROXY));
		assertEquals("Serverfehler", UpdateTexts.reason(Grund.SERVER));
		assertEquals("ungültige Update-Daten", UpdateTexts.reason(Grund.INVALID));
		assertEquals("unbekannter Fehler, siehe Protokoll", UpdateTexts.reason(Grund.UNKNOWN));
	}

	@Test
	void stateTexts() {
		assertEquals("Noch nicht geprüft", UpdateTexts.stateText(new UpdateState.NotChecked(), INSTALLED, UTC));
		assertEquals("Wird geprüft …", UpdateTexts.stateText(new UpdateState.Checking(AVAILABLE), INSTALLED, UTC));
		assertEquals("Aktuell (geprüft am 07.11.2026 14:05)", UpdateTexts.stateText(new UpdateState.UpToDate(T), INSTALLED, UTC));
		assertEquals("Version 1.1.0 (Rev. 2) verfügbar (geprüft am 07.11.2026 14:05)",
				UpdateTexts.stateText(AVAILABLE, INSTALLED, UTC));
		assertEquals("Prüfung fehlgeschlagen am 07.11.2026 14:05: Zeitüberschreitung",
				UpdateTexts.stateText(new UpdateState.CheckFailed(Grund.TIMEOUT, T, Optional.empty()), INSTALLED, UTC));
		assertEquals("In der Entwicklungsversion nicht verfügbar",
				UpdateTexts.stateText(new UpdateState.Unsupported(), Optional.empty(), UTC));
		assertEquals("Für diese Installation nicht verfügbar",
				UpdateTexts.stateText(new UpdateState.Unsupported(), INSTALLED, UTC));
	}

	@Test
	void stateCheckFailedWithKnownUpdate() {
		assertEquals("Prüfung fehlgeschlagen am 07.11.2026 14:05: keine Verbindung zum Update-Server. "
				+ "Version 1.1.0 (Rev. 2) ist verfügbar.", UpdateTexts.stateText(
						new UpdateState.CheckFailed(Grund.NETWORK, T, Optional.of(AVAILABLE)), INSTALLED, UTC));
	}

	@Test
	void platformTexts() {
		assertEquals("Bitte so aktualisieren, wie die Kartenverwaltung installiert wurde (z. B. über apt), oder die neue"
				+ " Version herunterladen.", UpdateTexts.platformText(Plattform.LINUX, false));
		assertEquals("Bitte die neue Version herunterladen und installieren.",
				UpdateTexts.platformText(Plattform.WINDOWS_OHNE_PAKET, false));
		assertEquals("Bitte die neue Version herunterladen und installieren.",
				UpdateTexts.platformText(Plattform.SONSTIGE, false));
		assertEquals("Für diese Version ist eine Neuinstallation nötig.",
				UpdateTexts.platformText(Plattform.WINDOWS_PAKET, true));
	}

	@Test
	void confirmDirect() {
		assertEquals("Kartenverwaltung aktualisieren", UpdateTexts.CONFIRM_DIRECT_TITLE);
		assertEquals("Aktualisieren", UpdateTexts.BUTTON_UPDATE);
		assertEquals("Version 1.1.0 (Rev. 2) ist verfügbar. Die Kartenverwaltung schließt sich jetzt, und Windows öffnet ein"
				+ " Fenster zum Installieren (falls nicht, sieh in der Taskleiste nach). Klicke dort auf „Aktualisieren“; ist"
				+ " „Starten, wenn bereit“ angehakt, startet die Kartenverwaltung danach von selbst. Willst du doch nicht"
				+ " aktualisieren oder öffnet sich etwas anderes, schließe das Fenster und starte die Kartenverwaltung wieder.",
				UpdateTexts.confirmDirectText(REV2, false));
	}

	@Test
	void confirmDirectAddsSharedFolderSentence() {
		String text = UpdateTexts.confirmDirectText(REV2, true);
		assertTrue(text.endsWith(" Aktualisiere alle Rechner, die denselben gemeinsamen Ordner nutzen, zur selben Zeit."
				+ " Fehlt dort der Hinweis noch, findest du das Update unter Einstellungen → Info und Updates → Jetzt prüfen."), text);
		assertFalse(UpdateTexts.confirmDirectText(REV2, false).contains("gemeinsamen Ordner"));
	}

	@Test
	void browserConfirmMentionsForeignProfileAndKeep() {
		assertEquals("Update im Browser laden", UpdateTexts.CONFIRM_BROWSER_TITLE);
		assertEquals("Im Browser laden", UpdateTexts.BUTTON_BROWSER);
		assertEquals("Die Kartenverwaltung schließt sich jetzt, und der Browser lädt die Update-Datei. Er kann dabei in einem"
				+ " eigenen Fenster ohne deine gewohnten Einstellungen starten; schließe es danach. Fragt der Browser nach,"
				+ " wähle „Behalten“. Öffne die Datei im Browser (Downloads). Das Windows-Fenster zeigt dann die Version; ist"
				+ " sie neuer, klicke auf „Aktualisieren“. Lösche die Datei danach. Klappt das nicht, starte die"
				+ " Kartenverwaltung einfach wieder.", UpdateTexts.confirmBrowserText(false));
		assertTrue(UpdateTexts.confirmBrowserText(true).contains("gemeinsamen Ordner"));
	}

	@Test
	void errors() {
		assertEquals("Update konnte nicht gestartet werden: Zeitüberschreitung. Versuche es später erneut oder über"
				+ " Einstellungen → Info und Updates → Update im Browser laden.", UpdateTexts.errorDirect(Grund.TIMEOUT));
		assertEquals("Update nicht gestartet: Es gibt keine neuere Version mehr.", UpdateTexts.ERROR_NO_NEWER);
		assertEquals("Update nicht gestartet: Für diese Version ist eine Neuinstallation nötig.", UpdateTexts.ERROR_REINSTALL);
		assertEquals("Der Browser konnte nicht geöffnet werden. Die Adresse der Update-Datei steht unter Einstellungen →"
				+ " Info und Updates. Kopiere sie in einen Browser und lade die Datei; schließe dann die Kartenverwaltung und"
				+ " öffne erst danach die Datei.", UpdateTexts.ERROR_BROWSER);
	}

	@Test
	void busyAndSection() {
		assertEquals("Update wird vorbereitet …", UpdateTexts.BUSY);
		assertEquals("Dauert das länger als eine Minute, schließe das Fenster und starte die Kartenverwaltung neu.",
				UpdateTexts.BUSY_HINT);
		assertEquals("Info und Updates", UpdateTexts.SECTION_TITLE);
		assertEquals("Version", UpdateTexts.ROW_VERSION);
		assertEquals("Updates", UpdateTexts.ROW_UPDATES);
		assertEquals("Update-Datei", UpdateTexts.ROW_UPDATE_FILE);
		assertEquals("Vor dem Öffnen der Datei die Kartenverwaltung schließen.", UpdateTexts.UPDATE_FILE_HINT);
		assertEquals("Entwicklungsversion", UpdateTexts.DEVELOPMENT_VERSION);
		assertEquals("Jetzt prüfen", UpdateTexts.BUTTON_CHECK_NOW);
		assertEquals("Jetzt aktualisieren", UpdateTexts.BUTTON_UPDATE_NOW);
		assertEquals("Update im Browser laden", UpdateTexts.BUTTON_UPDATE_BROWSER);
	}
}
