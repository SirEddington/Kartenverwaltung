package de.eltviller_carneval_verein.karten.ui.shared;

import java.time.Instant;
import java.time.ZoneId;

import de.eltviller_carneval_verein.karten.store.SyncState;
import de.eltviller_carneval_verein.karten.ui.backup.AbstractBackupCatalog;
import de.eltviller_carneval_verein.karten.validation.MessageTemplate;

/**
 * Die Texte der Oberfläche für den gemeinsamen Ordner, gesammelt an einer Stelle (Spec "Texte"). Es sind Vorlagen mit den
 * Platzhaltern {@code &1} und {@code &2}; eingesetzt wird über {@code MessageTemplate.format}. Wo eine Vorlage je nach Lage
 * anders zusammengesetzt wird (Fortschritt, Hinweis bei Ausfall, Statuszeile), tun das die Methoden unten.
 */
public final class SharedFolderTexts {

	private SharedFolderTexts() {
	}

	// --- Einstellungen: Abschnitt "Gemeinsamer Ordner"

	/** Überschrift des Abschnitts und Beschriftung der Zeile mit dem Pfad. */
	public static final String SECTION_TITLE = "Gemeinsamer Ordner";

	/** Hinweis des Abschnitts. */
	public static final String SECTION_HINT = "Mehrere Rechner können gleichzeitig verkaufen, wenn sie denselben Ordner"
			+ " nutzen (Nextcloud, OneDrive oder Netzlaufwerk). Empfohlen sind bis zu 4 Rechner. Bei Nextcloud und OneDrive"
			+ " muss der Ordner immer lokal verfügbar sein, nicht nur bei Bedarf.";

	/** Knopf: Ordner wählen. */
	public static final String BROWSE = "Durchsuchen …";

	/** Knopf: verbinden. */
	public static final String CONNECT = "Verbinden";

	/** Knopf: trennen (auch in der Rückfrage). */
	public static final String DISCONNECT = "Trennen";

	/** Statuszeile im Einzelbetrieb. */
	public static final String STATUS_OFF = "Nicht verbunden. Dieser Rechner arbeitet allein.";

	/** Statuszeile AKTUELL (&1 = Ordner, &2 = letzter Abgleich). */
	public static final String STATUS_CURRENT = "Verbunden mit &1. Letzter Abgleich: &2.";

	/** Statuszeile AUSSTEHEND (&1 = Ordner). */
	public static final String STATUS_PENDING = "Verbunden mit &1. Änderungen werden hochgeladen.";

	/** Statuszeile OFFLINE (&1 = Ordner). */
	public static final String STATUS_OFFLINE = "Offline: &1 ist nicht erreichbar. Verkäufe sind vorläufig.";

	/** Statuszeile FEHLER (&1 = Ursache). */
	public static final String STATUS_ERROR = "Abgleich gestört: &1";

	/** Statuszeile ENTSCHEIDUNG (&1 = Ordner). */
	public static final String STATUS_DECISION = "Der Ordner &1 ist erreichbar. Entscheide, was mit den Daten dieses Rechners"
			+ " passiert.";

	/** Statt des Zeitpunkts, solange kein Abgleich gelang. */
	public static final String NO_SYNC_YET = "noch keiner";

	/** Beschriftung der Zeile "Rechnername". */
	public static final String MACHINE_NAME = "Rechnername";

	/** Hinweis zum Rechnernamen. */
	public static final String MACHINE_NAME_HINT = "So erscheint dieser Rechner bei den anderen, zum Beispiel im"
			+ " Konflikt-Dialog.";

	/** Der Rechnername ist leer. */
	public static final String MACHINE_NAME_EMPTY = "Der Rechnername darf nicht leer sein.";

	/** Knopf: Rechnernamen ändern. */
	public static final String RENAME = "Ändern";

	/** Beschriftung der Zeile "Bediener". */
	public static final String OPERATOR = "Bediener";

	/** Hinweis zum Bediener (&1 = Name). */
	public static final String OPERATOR_HINT = "Angemeldet als &1.";

	/** Knopf: Bediener wechseln. */
	public static final String OPERATOR_SWITCH = "Bediener wechseln";

	/** Titel des Bediener-Dialogs. */
	public static final String OPERATOR_TITLE = "Wer verkauft?";

	/** Text des Bediener-Dialogs. */
	public static final String OPERATOR_TEXT = "Dein Name steht bei jeder Änderung, damit an allen Rechnern zu sehen ist,"
			+ " wer verkauft oder geändert hat. Ein Passwort gibt es nicht.";

	/** Beschriftung des Feldes im Bediener-Dialog. */
	public static final String OPERATOR_FIELD = "Name";

	/** Knopf beim Start: mit dem Namen weiter. */
	public static final String OPERATOR_CONTINUE = "Weiter";

	/** Knopf beim Start: die Kartenverwaltung beenden. */
	public static final String OPERATOR_QUIT = "Beenden";

	/** Knopf beim Wechsel: den Namen übernehmen. */
	public static final String OPERATOR_APPLY = "Übernehmen";

	/** Beschriftung der Zeile "Buchungen sofort speichern". */
	public static final String SAVE_IMMEDIATELY = "Buchungen sofort speichern";

	/** Hinweis zu "Buchungen sofort speichern". */
	public static final String SAVE_IMMEDIATELY_HINT = "Jede Änderung an einer Buchung wird sofort gespeichert, und andere"
			+ " Rechner sehen den Platz sofort als belegt. Ohne diese Einstellung gilt eine Buchung erst nach „Speichern“.";

	// --- Verbinden und Trennen

	/** Titel der Frage beim Verbinden. */
	public static final String DECISION_TITLE = "Daten dieses Rechners mitnehmen?";

	/** Text der Frage beim Verbinden. */
	public static final String DECISION_TEXT = "Auf diesem Rechner und im gemeinsamen Ordner liegen schon Daten.";

	/** Erklärung zu "Mitnehmen". */
	public static final String MERGE_EXPLANATION = "Die Daten dieses Rechners kommen zum gemeinsamen Bestand dazu. Ist ein"
			+ " Platz auf beiden Seiten verkauft, gilt der Verkauf im gemeinsamen Ordner, und deiner erscheint als Konflikt.";

	/** Erklärung zu "Beiseitelegen". */
	public static final String SET_ASIDE_EXPLANATION = "Die Daten dieses Rechners werden in einen Ordner „data-beiseite-…“"
			+ " verschoben (nicht gelöscht) und durch den gemeinsamen Bestand ersetzt.";

	/** Statt "Beiseitelegen", wenn die Schutzregel es verbietet. */
	public static final String SET_ASIDE_LOCKED = "Beiseitelegen ist nicht mehr möglich: Auf diesem Rechner wurde seit dem"
			+ " Start schon verkauft oder geändert, und diese Änderungen würden sonst aus der Kartenverwaltung verschwinden."
			+ " Willst du nur Testdaten beiseitelegen, starte die Kartenverwaltung neu und verbinde dann.";

	/** Knopf: Mitnehmen. */
	public static final String MERGE = "Mitnehmen";

	/** Knopf: Beiseitelegen. */
	public static final String SET_ASIDE = "Beiseitelegen";

	/** Knopf: Abbrechen. */
	public static final String CANCEL = "Abbrechen";

	/** Ladeansicht (&1 = Prozent). */
	public static final String PROGRESS = "Verbinde mit dem gemeinsamen Ordner … (&1 %)";

	/** Ladeansicht, zweite Zeile. */
	public static final String PROGRESS_NOTE = "Bei vielen Daten kann das einige Minuten dauern.";

	/** Fußzeile nach dem Verbinden (&1 = Ordner). */
	public static final String CONNECTED = "Verbunden mit &1.";

	/** Ladeansicht, Hinweis bei Ausfall (&1 = Text von SYS-005 bzw. SYS-006). */
	public static final String OUTAGE_HINT = "&1 Der Abgleich geht weiter, sobald der Ordner wieder erreichbar ist. Öffnest du die"
			+ " Kartenverwaltung jetzt, fehlen eventuell Verkäufe anderer Rechner; ein Platz, den du dann verkaufst, kann zum"
			+ " Konflikt werden.";

	/** Zusatz nach Beiseitelegen (&1 = Ordner "data-beiseite-…"). */
	public static final String SET_ASIDE_NOTE = "Deine bisherigen Daten liegen in &1.";

	/** Knopf: Trotzdem öffnen. */
	public static final String OPEN_ANYWAY = "Trotzdem öffnen";

	/** Titel der Rückfrage beim Trennen. */
	public static final String DISCONNECT_TITLE = "Vom gemeinsamen Ordner trennen?";

	/** Text der Rückfrage beim Trennen. */
	public static final String DISCONNECT_TEXT = "Dieser Rechner arbeitet danach allein weiter. Seine Daten bleiben erhalten,"
			+ " aber Verkäufe anderer Rechner kommen nicht mehr an, und seine kommen nicht mehr bei ihnen an.";

	/** Knopf der Rückfrage beim Trennen: nicht trennen. */
	public static final String STAY_CONNECTED = "Verbunden bleiben";

	// --- Popups und Fußzeile (Rückmeldungen des Versionsspeichers)

	/** Titel des Popups, wenn der gemeinsame Ordner nicht erreichbar ist (SYS-005). */
	public static final String UNREACHABLE_TITLE = "Gemeinsamer Ordner nicht erreichbar";

	/** Knopf dieses Popups. */
	public static final String CONTINUE_WORKING = "Weiterarbeiten";

	/** Titel des Popups bei einer neuen Rechner-Kennung (SYS-011). */
	public static final String DUPLICATE_MACHINE_TITLE = "Neue Rechner-Kennung";

	/** Knopf dieses Popups. */
	public static final String OK = "OK";

	/** Fußzeile, wenn der Ordner wieder erreichbar ist. */
	public static final String RECONNECTED = "Wieder verbunden. Vorläufige Verkäufe werden hochgeladen.";

	/** Fußzeile für einen übersprungenen Eintrag, SYS-013 (&1 = Bezeichnung des Eintrags). */
	public static final String SKIPPED_FOOTER = "Nicht abgeglichen: &1";

	/** Zusatz zur Bezeichnung, wenn auch alles darunter übersprungen ist. */
	public static final String WITH_EVERYTHING_BELOW = "mit allem darunter";

	// --- Fußzeile und Info-Fenster

	/** Hinweis OFFLINE. */
	public static final String HINT_OFFLINE = "Offline: Verkäufe vorläufig";

	/** Hinweis FEHLER. */
	public static final String HINT_ERROR = "Abgleich gestört: Verkäufe vorläufig";

	/** Hinweis AUSSTEHEND ab 30 s. */
	public static final String HINT_PENDING = "Hochladen verzögert: Verkäufe vorläufig";

	/** Hinweis ENTSCHEIDUNG. */
	public static final String HINT_DECISION = "Gemeinsamer Ordner: Entscheidung nötig";

	/** Hinweis erster Abgleich (&1 = Prozent): seit dem Verbinden ging noch kein Lauf vollständig durch. */
	public static final String HINT_FIRST_RUN = "Noch nicht vollständig abgeglichen (&1 %)";

	/** Hinweis erster Abgleich, Lauf zu Ende. */
	public static final String HINT_FIRST_RUN_ENDED = "Noch nicht vollständig abgeglichen: Einträge übersprungen";

	/** Hinweis: Die Änderungslisten anderer Rechner sind noch nicht alle gelesen (etwa nach dem Mitnehmen). */
	public static final String HINT_CATCHING_UP = "Verkäufe anderer Rechner kommen noch an";

	/** Hinweis übersprungene Einträge, einer. */
	public static final String HINT_SKIPPED_ONE = "1 Eintrag nicht abgeglichen";

	/** Hinweis übersprungene Einträge, mehrere (&1 = Zahl). */
	public static final String HINT_SKIPPED_MANY = "&1 Einträge nicht abgeglichen";

	/** Titel des Info-Fensters. */
	public static final String INFO_TITLE = "Abgleich mit dem gemeinsamen Ordner";

	/** Info-Fenster: Ordner (&1 = Ordner). */
	public static final String INFO_FOLDER = "Ordner: &1";

	/** Info-Fenster: letzter Abgleich (&1 = Zeitpunkt bzw. "noch keiner"). */
	public static final String INFO_LAST_SYNC = "Letzter Abgleich: &1";

	/** Info-Fenster: Ursache (&1 = Ursache der Störung). */
	public static final String INFO_CAUSE = "Ursache: &1";

	/** Info-Fenster: erster Abgleich vollständig. */
	public static final String INFO_FIRST_RUN_COMPLETE = "Vollständig abgeglichen: ja";

	/** Info-Fenster: erster Abgleich läuft (&1 = Prozent). */
	public static final String INFO_FIRST_RUN_RUNNING = "Vollständig abgeglichen: läuft (&1 %)";

	/** Info-Fenster: erster Abgleich unvollständig. */
	public static final String INFO_FIRST_RUN_INCOMPLETE = "Vollständig abgeglichen: nein";

	/** Info-Fenster: Überschrift der Liste. */
	public static final String INFO_LIST_TITLE = "Nicht abgeglichene Einträge";

	/** Info-Fenster: Spalte der Liste. */
	public static final String INFO_COLUMN_ENTRY = "Eintrag";

	/** Info-Fenster: Spalte der Liste. */
	public static final String INFO_COLUMN_CAUSE = "Ursache";

	/** Info-Fenster: Hinweis unter der Liste. */
	public static final String INFO_LIST_HINT = "Die Kartenverwaltung versucht es beim nächsten Abgleich erneut, spätestens"
			+ " nach 10 Minuten.";

	/** Info-Fenster: Liste leer. */
	public static final String INFO_LIST_EMPTY = "Keine";

	/** Knopf des Info-Fensters. */
	public static final String SYNC_NOW = "Jetzt abgleichen";

	/** Knopf des Info-Fensters. */
	public static final String CLOSE = "Schließen";

	// --- Gesperrte Einträge

	/** Fußzeile: Link, einer. */
	public static final String LOCKED_LINK_ONE = "1 gesperrter Eintrag";

	/** Fußzeile: Link, mehrere (&1 = Zahl). */
	public static final String LOCKED_LINK_MANY = "&1 gesperrte Einträge";

	/** Titel des Dialogs. */
	public static final String LOCKED_TITLE = "Gesperrte Einträge";

	/** Einleitung des Dialogs. */
	public static final String LOCKED_INTRO = "Diese Einträge lassen sich nicht ändern, weil ihre Daten beschädigt sind oder"
			+ " von einer neueren Version der Kartenverwaltung stammen.";

	/** Spalte des Dialogs. */
	public static final String LOCKED_COLUMN_ENTRY = "Eintrag";

	/** Spalte des Dialogs. */
	public static final String LOCKED_COLUMN_CAUSE = "Ursache";

	/** Spalte des Dialogs. */
	public static final String LOCKED_COLUMN_ACTION = "Aktion";

	/** Ursache: beschädigt. */
	public static final String LOCKED_CAUSE_DAMAGED = "Beschädigt, keine heile Kopie";

	/** Ursache: neueres Format. */
	public static final String LOCKED_CAUSE_FORMAT = "Neuere Programmversion: Kartenverwaltung aktualisieren";

	/** Knopf: auf den letzten lesbaren Stand zurücksetzen. */
	public static final String LOCKED_RESET = "Auf letzten lesbaren Stand zurücksetzen";

	/** Rückfrage vor dem Zurücksetzen (&1 = Bezeichnung des Eintrags). */
	public static final String LOCKED_CONFIRM = "„&1“ auf den letzten lesbaren Stand zurücksetzen? Die eine beschädigte"
			+ " Änderung geht dabei verloren, alles davor bleibt.";

	/** Knopf der Rückfrage: zurücksetzen. */
	public static final String LOCKED_CONFIRM_YES = "Zurücksetzen";

	/** Der Dialog hat nichts zu zeigen. */
	public static final String LOCKED_EMPTY = "Keine gesperrten Einträge.";

	/** Fußzeile, wenn das Zurücksetzen scheitert (&1 = Bezeichnung des Eintrags, &2 = Ursache). */
	public static final String LOCKED_RESET_FAILED = "„&1“ ließ sich nicht zurücksetzen: &2";

	// --- Zusammengesetzte Texte

	/** Der Link in der Fußzeile: "1 gesperrter Eintrag" bzw. "n gesperrte Einträge". */
	public static String lockedLink(int count) {
		return count == 1 ? LOCKED_LINK_ONE : MessageTemplate.format(LOCKED_LINK_MANY, count);
	}

	/** Die Fußzeile, wenn das Zurücksetzen scheitert. */
	public static String lockedResetFailed(String label, String cause) {
		return MessageTemplate.format(LOCKED_RESET_FAILED, label, cause);
	}

	/** Die Rückfrage vor dem Zurücksetzen. */
	public static String lockedConfirm(String label) {
		return MessageTemplate.format(LOCKED_CONFIRM, label);
	}

	/** Die Bezeichnung eines Eintrags, bei Event oder Vorstellung mit dem Zusatz "mit allem darunter". */
	public static String withSubtree(String label, boolean subtree) {
		return subtree ? label + " " + WITH_EVERYTHING_BELOW : label;
	}

	/** Zeitpunkt wie in der Statuszeile und im Konflikt-Dialog: {@code dd.MM.yyyy HH:mm:ss}. */
	public static String syncTime(Instant time, ZoneId zone) {
		return AbstractBackupCatalog.timeText(time, zone);
	}

	/** Ganze Prozent des Fortschritts (abgerundet, 0 bis 100). */
	public static int percent(double progress) {
		return Math.max(0, Math.min(100, (int) Math.floor(progress * 100)));
	}

	/**
	 * Die Fußzeile für einen übersprungenen Eintrag.
	 *
	 * @param label   die Bezeichnung des Eintrags (oder sein Pfad)
	 * @param subtree ob auch alles darunter übersprungen ist
	 */
	public static String skippedFooter(String label, boolean subtree) {
		return MessageTemplate.format(SKIPPED_FOOTER, withSubtree(label, subtree));
	}

	/** Die erste Zeile der Ladeansicht: ohne Prozent, solange der Lauf nicht begonnen hat ({@code null}). */
	public static String progressText(Double progress) {
		if (progress == null) {
			return PROGRESS.substring(0, PROGRESS.indexOf(" (&1"));
		}
		return MessageTemplate.format(PROGRESS, percent(progress));
	}

	/** Die Fußzeile nach dem Verbinden. */
	public static String connected(String dir) {
		return MessageTemplate.format(CONNECTED, dir);
	}

	/** Der Hinweis bei Ausfall; ohne bekannte Ursache beginnt er gleich mit dem festen Teil. */
	public static String outageHint(String cause) {
		if (cause == null || cause.isBlank()) {
			return OUTAGE_HINT.substring("&1 ".length());
		}
		return MessageTemplate.format(OUTAGE_HINT, cause);
	}

	/**
	 * Die Statuszeile des Abschnitts. Ohne bekannte Ursache endet der Text bei FEHLER nach "Abgleich gestört".
	 *
	 * @param state    {@code syncState()}; {@code null} gilt als {@link SyncState#AUS}
	 * @param dir      der gemeinsame Ordner
	 * @param lastSync der letzte erfolgreiche Takt, {@code null} solange keiner gelang
	 * @param cause    die Ursache einer Störung, sonst {@code null}
	 */
	public static String status(SyncState state, String dir, Instant lastSync, String cause, ZoneId zone) {
		if (state == null) {
			return STATUS_OFF;
		}
		return switch (state) {
		case AUS -> STATUS_OFF;
		case AKTUELL -> MessageTemplate.format(STATUS_CURRENT, dir,
				lastSync == null ? NO_SYNC_YET : syncTime(lastSync, zone));
		case AUSSTEHEND -> MessageTemplate.format(STATUS_PENDING, dir);
		case OFFLINE -> MessageTemplate.format(STATUS_OFFLINE, dir);
		case FEHLER -> cause == null || cause.isBlank() ? STATUS_ERROR.substring(0, STATUS_ERROR.indexOf(':'))
				: MessageTemplate.format(STATUS_ERROR, cause);
		case ENTSCHEIDUNG -> MessageTemplate.format(STATUS_DECISION, dir);
		};
	}

	// --- Prüfungen vor dem Verbinden (&1 = der eingegebene Pfad)

	/** Das Pfadfeld ist leer. */
	public static final String CHECK_EMPTY = "Wähle zuerst einen Ordner.";

	/** Der Ordner existiert nicht. */
	public static final String CHECK_MISSING = "Den Ordner &1 gibt es nicht. Wähle einen vorhandenen Ordner;"
			+ " die Kartenverwaltung legt ihn nicht selbst an.";

	/** Der Pfad ist kein Ordner. */
	public static final String CHECK_NOT_A_FOLDER = "&1 ist kein Ordner.";

	/** Der Ordner ist nicht erreichbar. */
	public static final String CHECK_UNREACHABLE = "Der Ordner &1 ist nicht erreichbar. Prüfe das Netzlaufwerk bzw. ob"
			+ " Nextcloud oder OneDrive läuft, und versuche es erneut.";

	/** Der Ordner überschneidet sich mit dem eigenen Datenordner. */
	public static final String CHECK_OWN_DATA_DIR = "&1 überschneidet sich mit dem Datenordner dieses Rechners."
			+ " Wähle einen Ordner außerhalb davon.";

	/** Der Ordner enthält die Daten direkt: vermutlich wurde der Unterordner {@code data} gewählt. */
	public static final String CHECK_DATA_SUBFOLDER = "In &1 liegen die Daten direkt."
			+ " Wähle den Ordner darüber, also den, der „data“ enthält.";
}
