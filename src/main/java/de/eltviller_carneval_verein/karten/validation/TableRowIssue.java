package de.eltviller_carneval_verein.karten.validation;

/**
 * Plausibilitätsfehler an der Tischreihe (Kürzel ROW). Codes sind stabil und werden nie wiederverwendet.
 */
public enum TableRowIssue implements Issue {
	/** Ein Reihenbuchstabe gehört mehreren Reihen. &1 = Bestuhlung (siehe {@link EntityLabels#plan}), &2 = Buchstabe. */
	LABEL_DUPLICATE("ROW-001", Severity.ERROR, "Der Buchstabe &2 wird in &1 für mehrere Reihen verwendet."),
	/**
	 * Winkel, Tischdrehung, Position, Tischabstand, Gangbreite oder Gang nach Tisch außerhalb des Bereichs.
	 * &1 = was ("Winkel"), &2 = Reihe (siehe {@link EntityLabels#row}), &3 = Wert, &4 = Minimum, &5 = Maximum.
	 */
	VALUE_OUT_OF_RANGE("ROW-002", Severity.ERROR, RangeCheck.TEMPLATE);

	private final String code;
	private final Severity severity;
	private final String template;

	TableRowIssue(String code, Severity severity, String template) {
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
