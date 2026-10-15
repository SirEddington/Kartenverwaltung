package de.eltviller_carneval_verein.karten.validation;

/**
 * Plausibilitätsfehler an einem Hallenobjekt (Kürzel HOB). Codes sind stabil und werden nie wiederverwendet.
 *
 * <p>{@code &1} steht für die Beschreibung der Halle (siehe {@link EntityLabels#hall}).
 */
public enum HallObjectIssue implements Issue {
	/** &1 = Halle, in der das Objekt ohne Namen liegt. */
	NAME_EMPTY("HOB-001", Severity.ERROR, "&1 enthält ein Objekt ohne Namen.");

	private final String code;
	private final Severity severity;
	private final String template;

	HallObjectIssue(String code, Severity severity, String template) {
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
