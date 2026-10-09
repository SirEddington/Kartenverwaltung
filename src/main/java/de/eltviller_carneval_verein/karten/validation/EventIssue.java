package de.eltviller_carneval_verein.karten.validation;

/** Plausibilitätsfehler am Event (Kürzel EVT). Codes sind stabil und werden nie wiederverwendet. */
public enum EventIssue implements Issue {
	/** &1 = Name der Vorstellung, die mehrfach vorkommt. */
	PRESENTATION_NAME_DUPLICATE("EVT-001", Severity.WARNING, "Es gibt mehrere Vorstellungen mit dem Namen &1.");

	private final String code;
	private final Severity severity;
	private final String template;

	EventIssue(String code, Severity severity, String template) {
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
