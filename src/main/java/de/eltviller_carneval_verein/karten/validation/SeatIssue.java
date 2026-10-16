package de.eltviller_carneval_verein.karten.validation;

/**
 * Plausibilitätsfehler am Sitz (Kürzel SEA). Codes sind stabil und werden nie
 * wiederverwendet; neue Fehler bekommen die nächste freie Nummer.
 *
 * <p>In allen Vorlagen steht {@code &1} für die Beschreibung des Sitzes (siehe {@link EntityLabels#seat}); bei Meldungen
 * zu einer Buchung für deren Beschreibung (siehe {@link EntityLabels#booking}).
 *
 * <p>{@code SEA-002} war für "Preis ist keine Zahl" vorgesehen, wurde nie ausgegeben und bleibt unvergeben: Nicht
 * lesbare Eingaben meldet {@code INP-001} (siehe {@code ui.input.InputIssue}).
 */
public enum SeatIssue implements Issue {
	/** &1 = Sitz. */
	PRICE_NEGATIVE("SEA-001", Severity.ERROR, "Der Preis für &1 darf nicht negativ sein."),
	/** &1 = Sitz, &2 = eingegebener Preis, &3 = höchster erlaubter Preis. */
	PRICE_TOO_HIGH("SEA-003", Severity.ERROR, "Der Preis &2 für &1 ist zu hoch (maximal &3)."),
	/** &1 = Sitz. */
	COLLECTED_NOT_PAID("SEA-004", Severity.WARNING, "&1 ist als abgeholt markiert, aber nicht bezahlt."),
	/** &1 = Sitz, &2 = eingegebene E-Mail-Adresse. */
	EMAIL_INVALID("SEA-005", Severity.WARNING, "Die E-Mail-Adresse &2 bei &1 ist nicht gültig."),
	/** &1 = Sitz, &2 = Preis. */
	PAID_WITHOUT_PRICE("SEA-006", Severity.WARNING, "&1 ist als bezahlt markiert, der Preis beträgt aber &2."),
	/** &1 = Sitz. */
	PAID_WITHOUT_NAME("SEA-007", Severity.WARNING, "&1 ist als bezahlt markiert, es ist aber kein Nachname eingetragen."),
	/** &1 = Sitz (mit der ungültigen Nummer). */
	NUMBER_TOO_LOW("SEA-008", Severity.ERROR, "Die Sitznummer von &1 muss mindestens 1 sein."),
	/** Breite oder Höhe außerhalb von 0 bis 100. &1 = "Breite" oder "Höhe", &2 = Sitz, &3 = Wert, &4 = Minimum, &5 = Maximum. */
	SIZE_OUT_OF_RANGE("SEA-009", Severity.ERROR, RangeCheck.TEMPLATE),
	/** Die Buchung hat keinen Platz mehr in der Bestuhlung. &1 = Buchung (siehe {@link EntityLabels#booking}). */
	NO_PLACE("SEA-010", Severity.WARNING, "&1 hat keinen Platz in der Bestuhlung und muss neu zugewiesen werden."),
	/** Mehrere Buchungen tragen denselben Sitz. &1 = erste Buchung dieses Sitzes. */
	SEAT_DOUBLE("SEA-011", Severity.ERROR, "Der Sitz von &1 ist noch einer zweiten Buchung zugeordnet.");

	private final String code;
	private final Severity severity;
	private final String template;

	SeatIssue(String code, Severity severity, String template) {
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
