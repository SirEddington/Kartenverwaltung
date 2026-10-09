package de.eltviller_carneval_verein.karten.validation;

/**
 * Plausibilitätsfehler am Tisch (Kürzel TBL). Codes sind stabil und werden nie wiederverwendet.
 *
 * <p>{@code &1} steht für die Beschreibung des Tisches (siehe {@link EntityLabels#table}).
 */
public enum TableIssue implements Issue {
	/** &1 = Tisch, &2 = mehrfach vergebene Sitznummer. */
	SEAT_NUMBER_DUPLICATE("TBL-001", Severity.ERROR, "Sitznummer &2 kommt an &1 mehrfach vor.");

	private final String code;
	private final Severity severity;
	private final String template;

	TableIssue(String code, Severity severity, String template) {
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
