package de.eltviller_carneval_verein.karten.ui.input;

import de.eltviller_carneval_verein.karten.validation.Issue;
import de.eltviller_carneval_verein.karten.validation.Severity;

/**
 * Fehler bei der Eingabe in Tabellenzellen und Spinnern (Kürzel INP): Der getippte Text ist keine Zahl, kein Datum,
 * keine Uhrzeit oder liegt außerhalb des erlaubten Bereichs. Anders als die Plausibilitätsfehler der Entitäten
 * erreichen diese Werte das Modell nie: Die Eingabe wird nicht übernommen, der alte Wert bleibt. Codes sind stabil
 * und werden nie wiederverwendet.
 *
 * <p>
 * {@code &1} steht für die Bezeichnung des Feldes samt Zeile (siehe {@link InputFeedback#label}), {@code &2} für den
 * getippten Text.
 */
public enum InputIssue implements Issue {
	/** &1 = Feld, &2 = getippter Text. */
	NOT_A_NUMBER("INP-001", Severity.ERROR, "&1: „&2“ ist keine gültige Zahl."),
	/** &1 = Feld, &2 = getippter Text. */
	NOT_A_DATE("INP-002", Severity.ERROR, "&1: „&2“ ist kein gültiges Datum, z. B. 31.12.2026."),
	/** &1 = Feld, &2 = getippter Text. */
	NOT_A_TIME("INP-003", Severity.ERROR, "&1: „&2“ ist keine gültige Uhrzeit, z. B. 19:30."),
	/** &1 = Feld, &2 = getippter Wert, &3 = kleinster, &4 = größter erlaubter Wert. */
	OUT_OF_RANGE("INP-004", Severity.ERROR, "&1: &2 liegt nicht zwischen &3 und &4.");

	private final String code;
	private final Severity severity;
	private final String template;

	InputIssue(String code, Severity severity, String template) {
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
