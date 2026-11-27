package de.eltviller_carneval_verein.karten.validation;

/** Plausibilitätsfehler an einer Kasse des Events (Kürzel KAS). Codes sind stabil und werden nie wiederverwendet. */
public enum CashRegisterIssue implements Issue {
	/** Eine freie Kasse hat keinen Namen. */
	NAME_EMPTY("KAS-001", Severity.ERROR, "Der Name einer Kasse darf nicht leer sein."),
	/** &1 = der Name, der im Event mehrfach vorkommt. */
	NAME_DUPLICATE("KAS-002", Severity.WARNING, "Der Kassenname &1 kommt im Event mehrfach vor."),
	/** Eine freie Kasse gilt weder für alle noch für eine vorhandene Vorstellung. &1 = Kasse. */
	NO_PRESENTATION("KAS-003", Severity.WARNING, "&1 gilt für keine Vorstellung."),
	/** Ist-Betrag außerhalb von 0 bis 1.000.000 €. &1 = "Ist-Betrag", &2 = Kasse, &3 = Betrag, &4 = Minimum, &5 = Maximum (in Euro). */
	CASH_OUT_OF_RANGE("KAS-004", Severity.ERROR, RangeCheck.TEMPLATE),
	/** Neben dem Stand der Kasse steht in den alten Feldern der Vorstellung ein zweiter Betrag. &1 = Kasse, &2 = Betrag in Euro. */
	SECOND_AMOUNT("KAS-005", Severity.WARNING,
			"Für &1 liegt aus einer älteren Programmversion ein zweiter Ist-Betrag von &2 vor. Es gilt der Stand der Kasse.");

	private final String code;
	private final Severity severity;
	private final String template;

	CashRegisterIssue(String code, Severity severity, String template) {
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
