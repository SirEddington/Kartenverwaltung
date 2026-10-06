package de.eltviller_carneval_verein.karten.validation;

/**
 * Eine einzelne Beanstandung einer Plausibilitätsprüfung.
 *
 * @param code     stabiler Fehlercode, z. B. "SLS-001" (Kürzel des Fachgebiets + laufende Nummer)
 * @param severity Schweregrad
 * @param message  verständliche Meldung für den Nutzer
 */
public record ValidationIssue(String code, Severity severity, String message) {

	/** Darstellung für Fußzeile und Log: "SLS-001: Der Preis darf nicht negativ sein." */
	public String toDisplayText() {
		return code + ": " + message;
	}
}
