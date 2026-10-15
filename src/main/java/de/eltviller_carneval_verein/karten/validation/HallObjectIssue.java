package de.eltviller_carneval_verein.karten.validation;

/**
 * Plausibilitätsfehler an einem Hallenobjekt (Kürzel HOB). Codes sind stabil und werden nie wiederverwendet.
 *
 * <p>{@code &1} steht für die Beschreibung der Halle (siehe {@link EntityLabels#hall}).
 */
public enum HallObjectIssue implements Issue {
	/** &1 = Halle, in der das Objekt ohne Namen liegt. */
	NAME_EMPTY("HOB-001", Severity.ERROR, "&1 enthält ein Objekt ohne Namen."),
	/** Breite oder Höhe außerhalb von 1 bis 10.000. &1 = "Breite" oder "Höhe", &2 = Objekt, &3 = Wert, &4 = Minimum, &5 = Maximum. */
	SIZE_OUT_OF_RANGE("HOB-002", Severity.ERROR, RangeCheck.TEMPLATE),
	/** Schriftgröße außerhalb von 1 bis 1.000 (Platzhalter wie bei {@link #SIZE_OUT_OF_RANGE}). */
	TEXT_SIZE_OUT_OF_RANGE("HOB-003", Severity.ERROR, RangeCheck.TEMPLATE),
	/** Position X oder Y außerhalb von -100.000 bis 100.000 (Platzhalter wie bei {@link #SIZE_OUT_OF_RANGE}). */
	POSITION_OUT_OF_RANGE("HOB-004", Severity.ERROR, RangeCheck.TEMPLATE);

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
