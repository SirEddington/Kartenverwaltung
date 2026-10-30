package de.eltviller_carneval_verein.karten.ui.shared;

/**
 * Die Texte der Oberfläche für den gemeinsamen Ordner, gesammelt an einer Stelle (Spec "Texte"). Es sind Vorlagen mit den
 * Platzhaltern {@code &1} und {@code &2}; eingesetzt wird über {@code MessageTemplate.format}.
 */
public final class SharedFolderTexts {

	private SharedFolderTexts() {
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
