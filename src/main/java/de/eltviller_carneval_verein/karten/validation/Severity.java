package de.eltviller_carneval_verein.karten.validation;

/**
 * Schweregrad einer Meldung, absteigend nach Wichtigkeit sortiert (ERROR zuerst).
 * Plausibilitätsprüfungen ({@link ValidationIssue}) nutzen nur ERROR und WARNING;
 * INFO und SUCCESS gibt es zusätzlich für reine Rückmeldungen in der Fußzeile.
 */
public enum Severity {
	/** Aktion nicht möglich bzw. Eingabe wird abgelehnt. */
	ERROR,
	/** Eingabe ist möglich, aber auffällig. */
	WARNING,
	/** Neutrale Information. */
	INFO,
	/** Aktion erfolgreich abgeschlossen. */
	SUCCESS
}
