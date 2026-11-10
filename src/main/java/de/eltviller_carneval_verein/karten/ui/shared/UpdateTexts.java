package de.eltviller_carneval_verein.karten.ui.shared;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import de.eltviller_carneval_verein.karten.update.AppVersion;
import de.eltviller_carneval_verein.karten.update.Grund;
import de.eltviller_carneval_verein.karten.update.Plattform;
import de.eltviller_carneval_verein.karten.update.UpdateState;
import de.eltviller_carneval_verein.karten.validation.MessageTemplate;

/** Die Texte zu Version und Updates (KARV-131), mit Platzhaltern {@code &1}, {@code &2} für {@code MessageTemplate}. */
public final class UpdateTexts {

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

	/** Fußzeile: eine neuere Version ist bekannt. */
	private static final String INDICATOR = "Update &1 verfügbar";
	public static final String INDICATOR_TOOLTIP_UPDATE = "Klicken, um die Kartenverwaltung zu aktualisieren";
	public static final String INDICATOR_TOOLTIP_DETAILS = "Klicken für Details";
	public static final String CHECK_IMPOSSIBLE = "Update-Prüfung nicht möglich";
	public static final String CHECK_IMPOSSIBLE_TOOLTIP = "Die Kartenverwaltung kann nicht nach Updates suchen. Klicken für Details.";

	public static final String SECTION_TITLE = "Info und Updates";
	public static final String ROW_VERSION = "Version";
	public static final String ROW_UPDATES = "Updates";
	public static final String ROW_UPDATE_FILE = "Update-Datei";
	public static final String UPDATE_FILE_HINT = "Vor dem Öffnen der Datei die Kartenverwaltung schließen.";
	public static final String DEVELOPMENT_VERSION = "Entwicklungsversion";
	public static final String BUTTON_CHECK_NOW = "Jetzt prüfen";
	public static final String BUTTON_UPDATE_NOW = "Jetzt aktualisieren";
	public static final String BUTTON_UPDATE_BROWSER = "Update im Browser laden";

	private static final String NOT_CHECKED = "Noch nicht geprüft";
	private static final String CHECKING = "Wird geprüft …";
	private static final String UP_TO_DATE = "Aktuell (geprüft am &1)";
	private static final String AVAILABLE = "Version &1 verfügbar (geprüft am &2)";
	private static final String FAILED = "Prüfung fehlgeschlagen am &1: &2";
	private static final String FAILED_KNOWN = ". Version &1 ist verfügbar.";
	private static final String UNSUPPORTED_DEVELOPMENT = "In der Entwicklungsversion nicht verfügbar";
	private static final String UNSUPPORTED_INSTALLATION = "Für diese Installation nicht verfügbar";

	private static final String PLATFORM_LINUX = "Bitte so aktualisieren, wie die Kartenverwaltung installiert wurde"
			+ " (z. B. über apt), oder die neue Version herunterladen.";
	private static final String PLATFORM_DOWNLOAD = "Bitte die neue Version herunterladen und installieren.";
	private static final String PLATFORM_REINSTALL = "Für diese Version ist eine Neuinstallation nötig.";

	public static final String CONFIRM_DIRECT_TITLE = "Kartenverwaltung aktualisieren";
	public static final String BUTTON_UPDATE = "Aktualisieren";
	private static final String CONFIRM_DIRECT = "Version &1 ist verfügbar. Die Kartenverwaltung schließt sich jetzt, und"
			+ " Windows öffnet ein Fenster zum Installieren (falls nicht, sieh in der Taskleiste nach). Klicke dort auf"
			+ " „Aktualisieren“; ist „Starten, wenn bereit“ angehakt, startet die Kartenverwaltung danach von selbst. Willst"
			+ " du doch nicht aktualisieren oder öffnet sich etwas anderes, schließe das Fenster und starte die"
			+ " Kartenverwaltung wieder.";
	public static final String CONFIRM_BROWSER_TITLE = "Update im Browser laden";
	public static final String BUTTON_BROWSER = "Im Browser laden";
	private static final String CONFIRM_BROWSER = "Die Kartenverwaltung schließt sich jetzt, und der Browser lädt die"
			+ " Update-Datei. Er kann dabei in einem eigenen Fenster ohne deine gewohnten Einstellungen starten; schließe es"
			+ " danach. Fragt der Browser nach, wähle „Behalten“. Öffne die Datei im Browser (Downloads). Das Windows-Fenster"
			+ " zeigt dann die Version; ist sie neuer, klicke auf „Aktualisieren“. Lösche die Datei danach. Klappt das nicht,"
			+ " starte die Kartenverwaltung einfach wieder.";
	private static final String SHARED_FOLDER = " Aktualisiere alle Rechner, die denselben gemeinsamen Ordner nutzen, zur"
			+ " selben Zeit. Fehlt dort der Hinweis noch, findest du das Update unter Einstellungen → Info und Updates →"
			+ " Jetzt prüfen.";

	private static final String ERROR_DIRECT = "Update konnte nicht gestartet werden: &1. Versuche es später erneut oder"
			+ " über Einstellungen → Info und Updates → Update im Browser laden.";
	public static final String ERROR_NO_NEWER = "Update nicht gestartet: Es gibt keine neuere Version mehr.";
	public static final String ERROR_REINSTALL = "Update nicht gestartet: Für diese Version ist eine Neuinstallation nötig.";
	public static final String ERROR_BROWSER = "Der Browser konnte nicht geöffnet werden. Die Adresse der Update-Datei"
			+ " steht unter Einstellungen → Info und Updates. Kopiere sie in einen Browser und lade die Datei; schließe dann"
			+ " die Kartenverwaltung und öffne erst danach die Datei.";

	/** Das Update ist gestartet, die App blieb aber offen, weil der Nutzer beim zweiten Sichern abbrach. */
	public static final String INSTALLER_OPEN = "Das Windows-Fenster zum Installieren ist offen. Speichere zuerst und klicke"
			+ " erst dann dort auf „Aktualisieren“; die Kartenverwaltung wird dabei geschlossen.";

	public static final String BUSY = "Update wird vorbereitet …";
	public static final String BUSY_HINT = "Dauert das länger als eine Minute, schließe das Fenster und starte die"
			+ " Kartenverwaltung neu.";

	// --- Gemeinsamer Ordner (KARV-132)

	private static final String FOLDER_INDICATOR = "Update &1 im gemeinsamen Ordner";
	public static final String FOLDER_TOOLTIP = "Klicken, um die Kartenverwaltung aus dem gemeinsamen Ordner zu aktualisieren";
	private static final String NEWER_STATION = "„&1“ nutzt &2 – bitte aktualisieren";
	public static final String NEWER_STATION_TOOLTIP = "Klicken für Details";
	public static final String START_TITLE = "Update im gemeinsamen Ordner";
	private static final String RESTART_BY_HAND = " Die Kartenverwaltung schließt sich dafür; bricht die Installation ab,"
			+ " starte sie von Hand neu.";
	private static final String START = "Version &1 liegt im gemeinsamen Ordner. Jetzt aktualisieren?";
	public static final String BUTTON_LATER = "Später";
	private static final String CONFIRM_FOLDER = "Version &1 aus dem gemeinsamen Ordner installieren?";
	public static final String ERROR_FOLDER_FILE = "Die Datei im gemeinsamen Ordner ist unvollständig oder verändert und wird"
			+ " nicht installiert.";
	public static final String DOWNLOAD_RUNNING_NOTE = "Das Update wird gerade auch für die anderen Kassen in den gemeinsamen"
			+ " Ordner geladen; das geht nach dem Neustart weiter.";
	public static final String LOADING = "Update wird in den gemeinsamen Ordner geladen …";
	private static final String WITHDRAWN = "Im gemeinsamen Ordner liegt Version &1, neuer als die Update-Quelle"
			+ " (zurückgezogen?)";
	public static final String NEXT_STEP_PACKAGE = "An einer Kasse mit Internet „Jetzt prüfen“ – dann liegt das Update bald"
			+ " im gemeinsamen Ordner.";
	public static final String NEXT_STEP_MANUAL = "Von Hand aktualisieren.";
	public static final String STATIONS_TITLE = "Kassen am gemeinsamen Ordner";
	public static final String ROW_FOLDER_UPDATE = "Gemeinsamer Ordner";
	private static final String STATION_LINE = "&1 – &2 – zuletzt gestartet &3 – &4";
	private static final String OWN_STATION = " (diese Kasse)";

	private UpdateTexts() {
	}

	/** „Update 1.1.1 im gemeinsamen Ordner“. */
	public static String folderIndicatorText(AppVersion version) {
		return MessageTemplate.format(FOLDER_INDICATOR, version.display());
	}

	/** „„Kasse 2“ nutzt 1.1.1 – bitte aktualisieren“. */
	public static String newerStationText(String name, AppVersion version) {
		return MessageTemplate.format(NEWER_STATION, name, version.display());
	}

	/** Die Rückfrage beim Start. */
	public static String startText(AppVersion version) {
		return MessageTemplate.format(START, version.display()) + RESTART_BY_HAND;
	}

	/** Die Rückfrage beim Klick auf das Update aus dem Ordner. */
	public static String confirmFolderText(AppVersion version) {
		return MessageTemplate.format(CONFIRM_FOLDER, version.display()) + RESTART_BY_HAND;
	}

	/** „Im gemeinsamen Ordner liegt Version 1.1.1, neuer als die Update-Quelle (zurückgezogen?)“. */
	public static String withdrawnText(AppVersion version) {
		return MessageTemplate.format(WITHDRAWN, version.display());
	}

	/** Eine Zeile der Liste „Kassen am gemeinsamen Ordner“. */
	public static String stationLine(de.eltviller_carneval_verein.karten.update.StationEntry e, boolean own, ZoneId zone) {
		return MessageTemplate.format(STATION_LINE, e.name(), e.version().display(), time(e.gestartet(), zone), e.machineId())
				+ (own ? OWN_STATION : "");
	}

	/** Die Rückfrage auf dem direkten Weg; mit Hinweis, wenn diese Kasse gerade in den gemeinsamen Ordner lädt. */
	public static String confirmDirectText(AppVersion version, boolean sharedFolder, boolean folderDownloadRunning) {
		return confirmDirectText(version, sharedFolder) + (folderDownloadRunning ? " " + DOWNLOAD_RUNNING_NOTE : "");
	}

	/** „Update 1.1.0 (Rev. 2) verfügbar“. */
	public static String indicatorText(AppVersion version) {
		return MessageTemplate.format(INDICATOR, version.display());
	}

	/** Der Grund einer gescheiterten Prüfung, klein geschrieben zum Einsetzen. */
	public static String reason(Grund grund) {
		return switch (grund) {
		case NETWORK -> "keine Verbindung zum Update-Server";
		case TIMEOUT -> "Zeitüberschreitung";
		case CERTIFICATE -> "Zertifikat abgelehnt (Virenschutz oder Proxy?)";
		case PROXY -> "vom Proxy abgelehnt";
		case SERVER -> "Serverfehler";
		case INVALID -> "ungültige Update-Daten";
		case UNKNOWN -> "unbekannter Fehler, siehe Protokoll";
		};
	}

	/**
	 * Die Zeile „Updates“ in den Einstellungen.
	 *
	 * @param installed die installierte Version; nur für {@code Unsupported} (Entwicklung oder nicht verfügbar)
	 */
	public static String stateText(UpdateState state, Optional<AppVersion> installed, ZoneId zone) {
		if (state instanceof UpdateState.NotChecked) {
			return NOT_CHECKED;
		}
		if (state instanceof UpdateState.Checking) {
			return CHECKING;
		}
		if (state instanceof UpdateState.UpToDate u) {
			return MessageTemplate.format(UP_TO_DATE, time(u.geprueft(), zone));
		}
		if (state instanceof UpdateState.Available a) {
			return MessageTemplate.format(AVAILABLE, a.neu().version().display(), time(a.geprueft(), zone));
		}
		if (state instanceof UpdateState.CheckFailed f) {
			String text = MessageTemplate.format(FAILED, time(f.zeitpunkt(), zone), reason(f.grund()));
			return f.zuletztBekannt().map(a -> text + MessageTemplate.format(FAILED_KNOWN, a.neu().version().display()))
					.orElse(text);
		}
		return installed.isPresent() ? UNSUPPORTED_INSTALLATION : UNSUPPORTED_DEVELOPMENT;
	}

	/** Was zu tun ist, wenn die App sich nicht selbst aktualisieren kann. */
	public static String platformText(Plattform plattform, boolean needsReinstall) {
		if (plattform == Plattform.WINDOWS_PAKET) {
			return needsReinstall ? PLATFORM_REINSTALL : null;
		}
		return plattform == Plattform.LINUX ? PLATFORM_LINUX : PLATFORM_DOWNLOAD;
	}

	/** Der Text der Rückfrage auf dem direkten Weg. */
	public static String confirmDirectText(AppVersion version, boolean sharedFolder) {
		return MessageTemplate.format(CONFIRM_DIRECT, version.display()) + (sharedFolder ? SHARED_FOLDER : "");
	}

	/** Der Text der Rückfrage auf dem Browser-Weg. */
	public static String confirmBrowserText(boolean sharedFolder) {
		return CONFIRM_BROWSER + (sharedFolder ? SHARED_FOLDER : "");
	}

	/** Der Fehler nach einem technisch gescheiterten direkten Weg. */
	public static String errorDirect(Grund grund) {
		return MessageTemplate.format(ERROR_DIRECT, reason(grund));
	}

	private static String time(Instant instant, ZoneId zone) {
		return TIME.withZone(zone).format(instant);
	}
}
