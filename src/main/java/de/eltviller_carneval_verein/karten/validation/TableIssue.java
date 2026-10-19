package de.eltviller_carneval_verein.karten.validation;

/**
 * Plausibilitätsfehler am Tisch (Kürzel TBL). Codes sind stabil und werden nie wiederverwendet.
 *
 * <p>{@code &1} steht für die Beschreibung des Tisches (siehe {@link EntityLabels#table}).
 */
public enum TableIssue implements Issue {
	/** &1 = Tisch, &2 = mehrfach vergebene Sitznummer. */
	SEAT_NUMBER_DUPLICATE("TBL-001", Severity.ERROR, "Sitznummer &2 kommt an &1 mehrfach vor."),
	/** &1 = Tisch (mit der ungültigen Nummer). */
	NUMBER_TOO_LOW("TBL-002", Severity.ERROR, "Die Tischnummer von &1 muss mindestens 1 sein."),
	/** Breite oder Höhe außerhalb von 0 bis 1.000. &1 = "Breite" oder "Höhe", &2 = Tisch, &3 = Wert, &4 = Minimum, &5 = Maximum. */
	SIZE_OUT_OF_RANGE("TBL-003", Severity.ERROR, RangeCheck.TEMPLATE),
	/** Position X oder Y außerhalb von -10.000 bis 10.000 (Platzhalter wie bei {@link #SIZE_OUT_OF_RANGE}). */
	POSITION_OUT_OF_RANGE("TBL-004", Severity.ERROR, RangeCheck.TEMPLATE),
	/** Der Tisch einer Bestuhlung hat keine Reihe oder zeigt auf eine Reihe, die es nicht gibt. &1 = Tisch. */
	ROW_MISSING("TBL-005", Severity.ERROR, "&1 gehört zu keiner vorhandenen Reihe.");

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
