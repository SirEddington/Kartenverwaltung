package de.eltviller_carneval_verein.karten.ui.input;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.function.Function;

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
		return optional(DateTimeInput::formatDate, DateTimeInput::parseDate, text -> new DateTimeParseException("Kein gültiges Datum", text, 0));
	}

	public static StringConverter<LocalTime> time() {
		return optional(DateTimeInput::formatTime, DateTimeInput::parseTime, text -> new DateTimeParseException("Keine gültige Uhrzeit", text, 0));
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

	/** Converter für einen Wert, den man leer lassen darf: leerer Text ist {@code null}, unlesbarer wirft {@code error}. */
	private static <T> StringConverter<T> optional(Function<T, String> format, Function<String, Optional<T>> parse,
			Function<String, RuntimeException> error) {
		return new StringConverter<>() {
			@Override
			public String toString(T value) {
				return value == null ? "" : format.apply(value);
			}

			@Override
			public T fromString(String text) {
				if (text == null || text.isBlank()) {
					return null;
				}
				return parse.apply(text).orElseThrow(() -> error.apply(text));
			}
		};
	}
}
