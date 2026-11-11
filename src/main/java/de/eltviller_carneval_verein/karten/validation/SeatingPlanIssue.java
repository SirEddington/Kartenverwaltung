package de.eltviller_carneval_verein.karten.validation;

/**
 * Plausibilitätsfehler an der Bestuhlung (Kürzel SPL). Codes sind stabil und werden nie wiederverwendet.
 */
public enum SeatingPlanIssue implements Issue {
	/** Der Name der Bestuhlung ist leer. &1 = Halle (siehe {@link EntityLabels#hall}). */
	NAME_EMPTY("SPL-001", Severity.ERROR, "Der Name einer Bestuhlung in &1 darf nicht leer sein."),
	/** Buchstabe und Nummer eines Tisches kommen mehrfach vor. &1 = Bestuhlung (siehe {@link EntityLabels#plan}), &2 = Tisch, z. B. "Tisch A3". */
	TABLE_DUPLICATE("SPL-002", Severity.ERROR, "&2 kommt in &1 mehrfach vor."),
	/** Mehrere Bestuhlungen einer Halle heißen gleich. &1 = Halle (siehe {@link EntityLabels#hall}), &2 = Name der Bestuhlung. */
	NAME_DUPLICATE("SPL-003", Severity.WARNING, "Es gibt mehrere Bestuhlungen mit dem Namen &2 in &1."),
	/** Zwei Tische (Platten oder Stühle) überlappen sich. &1 = Bestuhlung (siehe {@link EntityLabels#plan}), &2 und &3 = die Tische in Listenreihenfolge, z. B. "Tisch A3". */
	TABLES_OVERLAP("SPL-004", Severity.WARNING, "&2 und &3 überlappen sich in &1 (Tischplatten oder Stühle)."),
	/** Ein Tisch (Platte oder Stühle) überlappt teilweise ein Hallenobjekt. &1 = Tisch (siehe {@link EntityLabels#table}), &2 = Hallenobjekt (siehe {@link EntityLabels#hallObjectName}). */
	TABLE_OVERLAPS_OBJECT("SPL-005", Severity.WARNING, "&1 überlappt teilweise mit &2 (Tischplatte oder Stühle).");

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
