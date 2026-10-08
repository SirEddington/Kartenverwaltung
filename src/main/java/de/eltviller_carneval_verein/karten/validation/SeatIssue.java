package de.eltviller_carneval_verein.karten.validation;

/**
 * Plausibilitätsfehler am Sitz (Kürzel SEA). Codes sind stabil und werden nie
 * wiederverwendet; neue Fehler bekommen die nächste freie Nummer.
 *
 * <p>In allen Vorlagen steht {@code &1} für die Beschreibung des Sitzes (siehe {@link EntityLabels#seat}).
 */
public enum SeatIssue implements Issue {
	/** &1 = Sitz. */
	PRICE_NEGATIVE("SEA-001", Severity.ERROR, "Der Preis für &1 darf nicht negativ sein."),
	/** &1 = Sitz. */
	PRICE_NOT_A_NUMBER("SEA-002", Severity.ERROR, "Bitte für &1 einen gültigen Preis eingeben, z. B. 12,50."),
	/** &1 = Sitz, &2 = eingegebener Preis, &3 = höchster erlaubter Preis. */
	PRICE_TOO_HIGH("SEA-003", Severity.ERROR, "Der Preis &2 für &1 ist zu hoch (maximal &3)."),
	/** &1 = Sitz. */
	COLLECTED_NOT_PAID("SEA-004", Severity.WARNING, "&1 ist als abgeholt markiert, aber nicht bezahlt."),
	/** &1 = Sitz, &2 = eingegebene E-Mail-Adresse. */
	EMAIL_INVALID("SEA-005", Severity.WARNING, "Die E-Mail-Adresse &2 bei &1 ist nicht gültig."),
	/** &1 = Sitz, &2 = Preis. */
	PAID_WITHOUT_PRICE("SEA-006", Severity.WARNING, "&1 ist als bezahlt markiert, der Preis beträgt aber &2.");

	private final String code;
	private final Severity severity;
	private final String template;

	SeatIssue(String code, Severity severity, String template) {
		this.code = code;
		this.severity = severity;
		this.template = template;
	}

	@Override
	public String getCode() {
		return code;
	}

	@Override
	public Severity getSeverity() {
		return severity;
	}

	@Override
	public String getTemplate() {
		return template;
	}
}
