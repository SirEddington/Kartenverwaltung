package de.eltviller_carneval_verein.karten.validation;

/**
 * Plausibilitätsfehler im Kartenverkauf (Kürzel SLS). Codes sind stabil und werden nie
 * wiederverwendet; neue Fehler bekommen die nächste freie Nummer.
 */
public enum SalesIssue {
	PRICE_NEGATIVE("SLS-001", Severity.ERROR, "Der Preis darf nicht negativ sein."),
	PRICE_NOT_A_NUMBER("SLS-002", Severity.ERROR, "Bitte einen gültigen Preis eingeben, z. B. 12,50."),
	PRICE_TOO_HIGH("SLS-003", Severity.ERROR, "Der Preis ist zu hoch (maximal 1.000,00 €).");

	private final String code;
	private final Severity severity;
	private final String message;

	SalesIssue(String code, Severity severity, String message) {
		this.code = code;
		this.severity = severity;
		this.message = message;
	}

	public String getCode() {
		return code;
	}

	public ValidationIssue toIssue() {
		return new ValidationIssue(code, severity, message);
	}
}
