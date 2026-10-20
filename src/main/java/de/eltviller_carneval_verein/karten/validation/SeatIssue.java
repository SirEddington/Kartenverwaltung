package de.eltviller_carneval_verein.karten.validation;

/**
 * Plausibilitätsfehler am Sitz (Kürzel SEA). Codes sind stabil und werden nie
 * wiederverwendet; neue Fehler bekommen die nächste freie Nummer.
 *
 * <p>Die Layoutregeln ({@code SEA-008}, {@code SEA-009}) prüft der {@link SeatValidator} am Sitz der Bestuhlung, dort
 * steht {@code &1} für die Beschreibung des Sitzes (siehe {@link EntityLabels#seat}). Die Verkaufsregeln
 * ({@code SEA-001}, {@code SEA-003} bis {@code SEA-007}, {@code SEA-010}, {@code SEA-011}, {@code SEA-012} bis
 * {@code SEA-019}) prüfen
 * {@link BookingValidator} und {@link PresentationValidator} an der Buchung, dort steht {@code &1} für deren
 * Beschreibung (siehe {@link EntityLabels#booking}).
 *
 * <p>{@code SEA-002} war für "Preis ist keine Zahl" vorgesehen, wurde nie ausgegeben und bleibt unvergeben: Nicht
 * lesbare Eingaben meldet {@code INP-001} (siehe {@code ui.input.InputIssue}).
 */
public enum SeatIssue implements Issue {
	/** &1 = Buchung. */
	PRICE_NEGATIVE("SEA-001", Severity.ERROR, "Der Preis für &1 darf nicht negativ sein."),
	/** &1 = Buchung, &2 = eingegebener Preis, &3 = höchster erlaubter Preis. */
	PRICE_TOO_HIGH("SEA-003", Severity.ERROR, "Der Preis &2 für &1 ist zu hoch (maximal &3)."),
	/** &1 = Buchung. */
	COLLECTED_NOT_PAID("SEA-004", Severity.WARNING, "&1 ist als abgeholt markiert, aber nicht bezahlt."),
	/** &1 = Buchung, &2 = eingegebene E-Mail-Adresse. */
	EMAIL_INVALID("SEA-005", Severity.WARNING, "Die E-Mail-Adresse &2 bei &1 ist nicht gültig."),
	/** &1 = Buchung, &2 = Preis. */
	PAID_WITHOUT_PRICE("SEA-006", Severity.WARNING, "&1 ist als bezahlt markiert, der Preis beträgt aber &2."),
	/** &1 = Buchung. */
	PAID_WITHOUT_NAME("SEA-007", Severity.WARNING, "&1 ist als bezahlt markiert, es ist aber kein Nachname eingetragen."),
	/** &1 = Sitz (mit der ungültigen Nummer). */
	NUMBER_TOO_LOW("SEA-008", Severity.ERROR, "Die Sitznummer von &1 muss mindestens 1 sein."),
	/** Breite oder Höhe außerhalb von 0 bis 100. &1 = "Breite" oder "Höhe", &2 = Sitz, &3 = Wert, &4 = Minimum, &5 = Maximum. */
	SIZE_OUT_OF_RANGE("SEA-009", Severity.ERROR, RangeCheck.TEMPLATE),
	/** Die Buchung hat keinen Platz mehr in der Bestuhlung. &1 = Buchung (siehe {@link EntityLabels#booking}). */
	NO_PLACE("SEA-010", Severity.WARNING, "&1 hat keinen Platz in der Bestuhlung und muss neu zugewiesen werden."),
	/** Mehrere Buchungen tragen denselben Sitz. &1 = erste Buchung dieses Sitzes. */
	SEAT_DOUBLE("SEA-011", Severity.ERROR, "Der Sitz von &1 ist noch einer zweiten Buchung zugeordnet."),
	/** Ein Reservierungsdatum, aber keine Reservierung (Nachname leer). &1 = Buchung. */
	RESERVATION_DATE_WITHOUT_ACTION("SEA-012", Severity.WARNING,
			"Bei &1 ist ein Reservierungsdatum eingetragen, die Buchung ist aber nicht reserviert."),
	/** Ein Bezahldatum, aber keine Zahlung. &1 = Buchung. */
	PAYMENT_DATE_WITHOUT_ACTION("SEA-013", Severity.WARNING, "Bei &1 ist ein Bezahldatum eingetragen, die Buchung ist aber nicht bezahlt."),
	/** Ein Abholdatum, aber nicht abgeholt. &1 = Buchung. */
	COLLECTION_DATE_WITHOUT_ACTION("SEA-014", Severity.WARNING,
			"Bei &1 ist ein Abholdatum eingetragen, die Buchung ist aber nicht als abgeholt markiert."),
	/** Reserviert, aber ohne Reservierungsdatum. &1 = Buchung. */
	RESERVATION_DATE_MISSING("SEA-015", Severity.WARNING, "&1 ist reserviert, es fehlt aber das Reservierungsdatum."),
	/** Bezahlt, aber ohne Bezahldatum. &1 = Buchung. */
	PAYMENT_DATE_MISSING("SEA-016", Severity.WARNING, "&1 ist als bezahlt markiert, es fehlt aber das Bezahldatum."),
	/** Abgeholt, aber ohne Abholdatum. &1 = Buchung. */
	COLLECTION_DATE_MISSING("SEA-017", Severity.WARNING, "&1 ist als abgeholt markiert, es fehlt aber das Abholdatum."),
	/** Ein Datum liegt nach dem heutigen Tag. &1 = Buchung, &2 = Bezeichnung des Datums, &3 = Datum (dd.MM.yyyy). */
	DATE_IN_FUTURE("SEA-018", Severity.WARNING, "Das &2 (&3) bei &1 liegt in der Zukunft."),
	/** Die Reihenfolge Reservierung, Bezahlung, Abholung ist verletzt. &1 = Buchung, &2 = das Datum, das später liegen müsste, &3 = das Datum, vor dem es liegt. */
	DATE_ORDER("SEA-019", Severity.WARNING, "Bei &1 liegt das &2 vor dem &3.");

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
