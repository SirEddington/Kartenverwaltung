package de.eltviller_carneval_verein.karten.ui.shared;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import de.eltviller_carneval_verein.karten.store.SyncState;
import de.eltviller_carneval_verein.karten.validation.MessageTemplate;

/**
 * Die Texte der Oberfläche für den gemeinsamen Ordner, gesammelt an einer Stelle (Spec "Texte"). Es sind Vorlagen mit den
 * Platzhaltern {@code &1} und {@code &2}; eingesetzt wird über {@code MessageTemplate.format}. Wo eine Vorlage je nach Lage
 * anders zusammengesetzt wird (Fortschritt, Hinweis bei Ausfall, Statuszeile), tun das die Methoden unten.
 */
public final class SharedFolderTexts {

	/** Zeitpunkt des letzten Abgleichs in der Statuszeile. */
	private static final DateTimeFormatter SYNC_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");

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
	public static final String OUTAGE_HINT = "&1 Der Abgleich geht weiter, sobald es wieder geht. Öffnest du die"
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

	// --- Zusammengesetzte Texte

	/**
	 * Die Fußzeile für einen übersprungenen Eintrag.
	 *
	 * @param label   die Bezeichnung des Eintrags (oder sein Pfad)
	 * @param subtree ob auch alles darunter übersprungen ist
	 */
	public static String skippedFooter(String label, boolean subtree) {
		return MessageTemplate.format(SKIPPED_FOOTER, subtree ? label + " " + WITH_EVERYTHING_BELOW : label);
	}

	/** Die erste Zeile der Ladeansicht: ohne Prozent, solange der Lauf nicht begonnen hat ({@code null}). */
	public static String progressText(Double progress) {
		if (progress == null) {
			return PROGRESS.substring(0, PROGRESS.indexOf(" (&1"));
		}
		return MessageTemplate.format(PROGRESS, (int) Math.floor(progress * 100));
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
				lastSync == null ? NO_SYNC_YET : lastSync.atZone(zone).format(SYNC_TIME));
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
