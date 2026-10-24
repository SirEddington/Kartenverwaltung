package de.eltviller_carneval_verein.karten.mirror;

import de.eltviller_carneval_verein.karten.validation.MessageTemplate;

/**
 * Codes und Texte der technischen Meldungen der Spiegelung ({@code SYS-002} bis {@code SYS-004}). Die Texte sind
 * Vorlagen mit den Platzhaltern {@code &1} (Pfad des Spiegelordners) und {@code &2} (Ursache); fertig formatiert
 * lauten sie {@code "<Code>: <Text>"}. Nummern werden nie wiederverwendet.
 */
public final class MirrorMessages {

	/** Der Spiegelordner ist nicht erreichbar. */
	public static final String SYS_UNREACHABLE = "SYS-002";

	/** Der Abgleich ist an einem Fehler gescheitert. */
	public static final String SYS_SYNC_FAILED = "SYS-003";

	/** Der Abgleich wurde abgebrochen, weil er im Spiegelordner alles gelöscht hätte. */
	public static final String SYS_ABORTED = "SYS-004";

	private static final String UNREACHABLE_TEXT = "Der Spiegelordner &1 ist nicht erreichbar. Du kannst lokal "
			+ "weiterarbeiten; die Änderungen werden übertragen, sobald er wieder erreichbar ist.";

	private static final String SYNC_FAILED_TEXT = "Der Abgleich mit dem Spiegelordner &1 ist fehlgeschlagen: &2";

	private static final String ABORTED_TEXT = "Der Abgleich mit dem Spiegelordner &1 wurde abgebrochen: Lokal sind "
			+ "keine Events und Hallen vorhanden, im Spiegelordner würde alles gelöscht.";

	private MirrorMessages() {
	}

	/** SYS-002: Der Spiegelordner {@code path} ist nicht erreichbar. */
	public static String unreachable(String path) {
		return SYS_UNREACHABLE + ": " + MessageTemplate.format(UNREACHABLE_TEXT, path);
	}

	/** SYS-003: Der Abgleich mit {@code path} ist aus dem Grund {@code cause} fehlgeschlagen. */
	public static String syncFailed(String path, String cause) {
		return SYS_SYNC_FAILED + ": " + MessageTemplate.format(SYNC_FAILED_TEXT, path, cause);
	}

	/** SYS-004: Der Abgleich mit {@code path} wurde abgebrochen, weil lokal alles fehlt. */
	public static String aborted(String path) {
		return SYS_ABORTED + ": " + MessageTemplate.format(ABORTED_TEXT, path);
	}

	/**
	 * Die Ursache eines Fehlers für die Meldungstexte, zum Beispiel {@code "AccessDeniedException: E:\ECV\E1.json"}.
	 * Ohne Meldung steht nur der Klassenname da: Manche Ausnahmen haben keine, und der Text endete sonst auf "null".
	 */
	public static String describe(Throwable e) {
		String message = e.getMessage();
		return message == null || message.isBlank() ? e.getClass().getSimpleName()
				: e.getClass().getSimpleName() + ": " + message;
	}
}
