package de.eltviller_carneval_verein.karten.validation;

/** Plausibilitätsfehler an der Halle (Kürzel HLL). Codes sind stabil und werden nie wiederverwendet. */
public enum HallIssue implements Issue {
	/** Der Name der Halle ist leer. */
	NAME_EMPTY("HLL-001", Severity.ERROR, "Der Name einer Halle darf nicht leer sein."),
	/** Breite oder Höhe der Halle außerhalb von 0 bis 100.000. &1 = "Breite" oder "Höhe", &2 = Halle, &3 = Wert, &4 = Minimum, &5 = Maximum. */
	SIZE_OUT_OF_RANGE("HLL-002", Severity.ERROR, RangeCheck.TEMPLATE);

	private final String code;
	private final Severity severity;
	private final String template;

	HallIssue(String code, Severity severity, String template) {
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
