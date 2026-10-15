package de.eltviller_carneval_verein.karten.ui.input;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;

import javafx.util.StringConverter;

/**
 * Converter für Tabellenzellen, die Datum, Uhrzeit und Ganzzahlen lesen. {@code fromString} wirft bei nicht lesbarem
 * Text eine {@link DateTimeParseException} bzw. {@link NumberFormatException}; die
 * {@link ParsingTextFieldTableCell} fängt sie und meldet den Fehler an der Eingabestelle. Ein leeres Datum oder eine
 * leere Uhrzeit bedeutet "keins" ({@code null}), eine leere Ganzzahl ist ein Fehler (ein Feld für eine Nummer braucht
 * eine Zahl).
 */
public final class InputConverters {

	private InputConverters() {
	}

	public static StringConverter<LocalDate> date() {
		return new StringConverter<>() {
			@Override
			public String toString(LocalDate date) {
				return date == null ? "" : DateTimeInput.formatDate(date);
			}

			@Override
			public LocalDate fromString(String text) {
				if (text == null || text.isBlank()) {
					return null;
				}
				return DateTimeInput.parseDate(text).orElseThrow(() -> new DateTimeParseException("Kein gültiges Datum", text, 0));
			}
		};
	}

	public static StringConverter<LocalTime> time() {
		return new StringConverter<>() {
			@Override
			public String toString(LocalTime time) {
				return time == null ? "" : DateTimeInput.formatTime(time);
			}

			@Override
			public LocalTime fromString(String text) {
				if (text == null || text.isBlank()) {
					return null;
				}
				return DateTimeInput.parseTime(text).orElseThrow(() -> new DateTimeParseException("Keine gültige Uhrzeit", text, 0));
			}
		};
	}

	public static StringConverter<Integer> integer() {
		return new StringConverter<>() {
			@Override
			public String toString(Integer value) {
				return value == null ? "" : String.valueOf(value);
			}

			@Override
			public Integer fromString(String text) {
				return NumberInput.parseInteger(text).orElseThrow(() -> new NumberFormatException("Keine ganze Zahl: " + text));
			}
		};
	}
}
