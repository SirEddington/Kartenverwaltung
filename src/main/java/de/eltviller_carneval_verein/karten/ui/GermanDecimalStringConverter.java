package de.eltviller_carneval_verein.karten.ui;

import java.util.Locale;

import de.eltviller_carneval_verein.karten.ui.input.NumberInput;
import javafx.util.StringConverter;

/**
 * Akzeptiert beim Parsen sowohl "12,50" als auch "12.50" - an der Abendkasse wird deutsch
 * mit Komma getippt, das eingebaute DoubleStringConverter kannte vorher nur den Punkt. Gelesen wird mit
 * {@link NumberInput#parseDecimal(String)} (auch "1.234,50"); ein leerer Text ergibt 0, unlesbarer Text eine
 * {@link NumberFormatException}, die die {@code ParsingTextFieldTableCell} an der Eingabestelle meldet.
 */
public class GermanDecimalStringConverter extends StringConverter<Double> {

	@Override
	public String toString(Double value) {
		return value == null ? "" : String.format(Locale.GERMANY, "%.2f", value);
	}

	@Override
	public Double fromString(String text) {
		if (text == null || text.isBlank()) {
			return 0.0;
		}
		return NumberInput.parseDecimal(text).orElseThrow(() -> new NumberFormatException("Keine Zahl: " + text));
	}
}
