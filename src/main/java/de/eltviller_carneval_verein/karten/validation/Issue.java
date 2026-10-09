package de.eltviller_carneval_verein.karten.validation;

/**
 * Gemeinsame Form der Issue-Enums je Entität (z. B. {@link SeatIssue}): ein stabiler Code, ein
 * Schweregrad und eine Meldungsvorlage mit Platzhaltern {@code &1} bis {@code &9}
 * (siehe {@link MessageTemplate}).
 */
public interface Issue {

	/** Stabiler Code, z. B. "SEA-001". */
	String getCode();

	Severity getSeverity();

	/** Meldungstext mit Platzhaltern {@code &1}, {@code &2}, ... */
	String getTemplate();

	/** Erzeugt die Beanstandung; die Parameter ersetzen {@code &1}, {@code &2}, ... in der Vorlage. */
	default ValidationIssue toIssue(Object... params) {
		return new ValidationIssue(getCode(), getSeverity(), MessageTemplate.format(getTemplate(), params));
	}
}
