package de.eltviller_carneval_verein.karten.validation;

/**
 * Plausibilitätsfehler an der Vorstellung (Kürzel PRS). Codes sind stabil und werden nie wiederverwendet.
 *
 * <p>{@code &1} steht für die Beschreibung der Vorstellung (siehe {@link EntityLabels#presentation}).
 */
public enum PresentationIssue implements Issue {
	/** &1 = Vorstellung, &2 = mehrfach vergebene Tischnummer. */
	TABLE_NUMBER_DUPLICATE("PRS-001", Severity.ERROR, "Tischnummer &2 kommt in &1 mehrfach vor."),
	/** &1 = Vorstellung. */
	DATE_OR_TIME_MISSING("PRS-002", Severity.WARNING, "Für &1 fehlen Datum oder Uhrzeit."),
	/** &1 = Vorstellung. */
	HALL_MISSING("PRS-003", Severity.WARNING, "Für &1 ist keine Halle ausgewählt.");

	private final String code;
	private final Severity severity;
	private final String template;

	PresentationIssue(String code, Severity severity, String template) {
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
