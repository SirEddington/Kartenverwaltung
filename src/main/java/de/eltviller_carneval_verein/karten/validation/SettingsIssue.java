package de.eltviller_carneval_verein.karten.validation;

/** Plausibilitätsfehler an den Einstellungen (Kürzel SET). Codes sind stabil und werden nie wiederverwendet. */
public enum SettingsIssue implements Issue {
	/** Der Spiegelordner ist der Standardordner oder liegt darin oder darum. &1 = eingegebener Pfad. */
	MIRROR_DIR_NESTED("SET-001", Severity.ERROR,
			"Der Spiegelordner &1 darf nicht der Standardordner sein und nicht darin oder darum liegen."),
	/** Der Spiegelordner ist nicht erreichbar oder lässt sich nicht anlegen. &1 = eingegebener Pfad. */
	MIRROR_DIR_UNREACHABLE("SET-002", Severity.ERROR,
			"Der Spiegelordner &1 ist nicht erreichbar oder kann nicht angelegt werden."),
	/** In den Spiegelordner kann nicht geschrieben werden. &1 = eingegebener Pfad. */
	MIRROR_DIR_NOT_WRITABLE("SET-003", Severity.ERROR, "In den Spiegelordner &1 kann nicht geschrieben werden.");

	private final String code;
	private final Severity severity;
	private final String template;

	SettingsIssue(String code, Severity severity, String template) {
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
