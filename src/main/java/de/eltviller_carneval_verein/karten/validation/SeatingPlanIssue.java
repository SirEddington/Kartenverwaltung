package de.eltviller_carneval_verein.karten.validation;

/**
 * Plausibilitätsfehler an der Bestuhlung (Kürzel SPL). Codes sind stabil und werden nie wiederverwendet.
 */
public enum SeatingPlanIssue implements Issue {
	/** Der Name der Bestuhlung ist leer. &1 = Halle (siehe {@link EntityLabels#hall}). */
	NAME_EMPTY("SPL-001", Severity.ERROR, "Der Name einer Bestuhlung in &1 darf nicht leer sein."),
	/** Buchstabe und Nummer eines Tisches kommen mehrfach vor. &1 = Bestuhlung (siehe {@link EntityLabels#plan}), &2 = Tisch, z. B. "Tisch A3". */
	TABLE_DUPLICATE("SPL-002", Severity.ERROR, "&2 kommt in &1 mehrfach vor.");

	private final String code;
	private final Severity severity;
	private final String template;

	SeatingPlanIssue(String code, Severity severity, String template) {
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
