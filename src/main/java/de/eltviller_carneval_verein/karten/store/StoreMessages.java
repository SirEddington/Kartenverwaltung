package de.eltviller_carneval_verein.karten.store;

import de.eltviller_carneval_verein.karten.validation.MessageTemplate;

/**
 * Codes und Texte der technischen Meldungen des Versionsspeichers ({@code SYS-005} bis {@code SYS-012}). Die Texte sind
 * Vorlagen mit den Platzhaltern {@code &1} und {@code &2}; fertig formatiert lauten sie {@code "<Code>: <Text>"}.
 * Nummern werden nie wiederverwendet ({@code SYS-002} bis {@code SYS-004} gehören zur Spiegelung).
 */
public final class StoreMessages {

	/** Der gemeinsame Ordner ist nicht erreichbar. */
	public static final String SYS_UNREACHABLE = "SYS-005";

	/** Der Abgleich mit dem gemeinsamen Ordner ist an einem anderen Fehler gescheitert. */
	public static final String SYS_SYNC_FAILED = "SYS-006";

	/** Eine beschädigte Datei wurde durch eine geprüfte Kopie ersetzt. */
	public static final String SYS_REPAIRED = "SYS-007";

	/** Eine Einheit ist beschädigt, und es gibt keine unbeschädigte Kopie. */
	public static final String SYS_DAMAGED = "SYS-008";

	/** Eine Einheit wurde mit einer neueren Programmversion gespeichert. */
	public static final String SYS_NEWER_FORMAT = "SYS-009";

	/** Die Anwendung läuft auf diesem Rechner schon. */
	public static final String SYS_ALREADY_RUNNING = "SYS-010";

	/** Ein anderer Rechner verwendet dieselbe Rechner-Kennung. */
	public static final String SYS_DUPLICATE_MACHINE = "SYS-011";

	/** Im gemeinsamen Ordner liegt eine Versionsdatei gleichen Namens mit anderem Inhalt. */
	public static final String SYS_CONTENT_MISMATCH = "SYS-012";

	private static final String UNREACHABLE_TEXT = "Der gemeinsame Ordner &1 ist nicht erreichbar. Du kannst "
			+ "weiterarbeiten; neue Verkäufe und Änderungen sind vorläufig, bis sie hochgeladen sind.";

	private static final String SYNC_FAILED_TEXT = "Der Abgleich mit dem gemeinsamen Ordner &1 ist fehlgeschlagen: &2";

	private static final String REPAIRED_TEXT = "Die beschädigte Datei &1 wurde durch eine geprüfte Kopie ersetzt; die "
			+ "beschädigte liegt als &2 daneben.";

	private static final String DAMAGED_TEXT = "&1 ist beschädigt, und es gibt keine unbeschädigte Kopie. Änderungen "
			+ "daran sind gesperrt, bis sie auf den letzten lesbaren Stand zurückgesetzt wird.";

	private static final String NEWER_FORMAT_TEXT = "&1 wurde mit einer neueren Version der Kartenverwaltung gespeichert. "
			+ "Bitte aktualisiere die Kartenverwaltung auf diesem Rechner.";

	private static final String ALREADY_RUNNING_TEXT = "Die Kartenverwaltung läuft auf diesem Rechner bereits.";

	private static final String DUPLICATE_MACHINE_TEXT = "Ein anderer Rechner verwendet dieselbe Rechner-Kennung "
			+ "(wahrscheinlich eine kopierte Installation). Dieser Rechner hat eine neue Kennung erhalten.";

	private static final String CONTENT_MISMATCH_TEXT = "Im gemeinsamen Ordner liegt &1 mit anderem Inhalt als lokal. Es "
			+ "wurde nichts überschrieben.";

	private StoreMessages() {
	}

	/** SYS-005: Der gemeinsame Ordner {@code path} ist nicht erreichbar. */
	public static String unreachable(String path) {
		return SYS_UNREACHABLE + ": " + MessageTemplate.format(UNREACHABLE_TEXT, path);
	}

	/** SYS-006: Der Abgleich mit {@code path} ist aus dem Grund {@code cause} fehlgeschlagen. */
	public static String syncFailed(String path, String cause) {
		return SYS_SYNC_FAILED + ": " + MessageTemplate.format(SYNC_FAILED_TEXT, path, cause);
	}

	/** SYS-007: Die Datei {@code file} wurde ersetzt; die beschädigte liegt als {@code corruptFile} daneben. */
	public static String repaired(String file, String corruptFile) {
		return SYS_REPAIRED + ": " + MessageTemplate.format(REPAIRED_TEXT, file, corruptFile);
	}

	/** SYS-008: Die Einheit {@code unit} ist beschädigt und ohne unbeschädigte Kopie. */
	public static String damaged(String unit) {
		return SYS_DAMAGED + ": " + MessageTemplate.format(DAMAGED_TEXT, unit);
	}

	/** SYS-009: Die Einheit {@code unit} wurde mit einer neueren Programmversion gespeichert. */
	public static String newerFormat(String unit) {
		return SYS_NEWER_FORMAT + ": " + MessageTemplate.format(NEWER_FORMAT_TEXT, unit);
	}

	/** SYS-010: Die Anwendung läuft auf diesem Rechner schon. */
	public static String alreadyRunning() {
		return SYS_ALREADY_RUNNING + ": " + ALREADY_RUNNING_TEXT;
	}

	/** SYS-011: Ein anderer Rechner verwendet dieselbe Kennung; dieser hat eine neue bekommen. */
	public static String duplicateMachine() {
		return SYS_DUPLICATE_MACHINE + ": " + DUPLICATE_MACHINE_TEXT;
	}

	/** SYS-012: Im gemeinsamen Ordner liegt {@code file} mit anderem Inhalt als lokal. */
	public static String contentMismatch(String file) {
		return SYS_CONTENT_MISMATCH + ": " + MessageTemplate.format(CONTENT_MISMATCH_TEXT, file);
	}

	/**
	 * Die Ursache eines Fehlers für die Meldungstexte, zum Beispiel {@code "AccessDeniedException: E:\ECV\data"}. Ohne
	 * Meldung steht nur der Klassenname da: Manche Ausnahmen haben keine, und der Text endete sonst auf "null".
	 */
	public static String describe(Throwable e) {
		String message = e.getMessage();
		return message == null || message.isBlank() ? e.getClass().getSimpleName()
				: e.getClass().getSimpleName() + ": " + message;
	}
}
