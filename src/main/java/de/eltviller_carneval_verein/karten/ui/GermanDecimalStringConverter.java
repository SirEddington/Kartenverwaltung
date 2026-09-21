package de.eltviller_carneval_verein.karten.ui;

import java.util.Locale;

import javafx.util.StringConverter;

/**
 * Akzeptiert beim Parsen sowohl "12,50" als auch "12.50" - an der Abendkasse wird deutsch
 * mit Komma getippt, das eingebaute DoubleStringConverter kannte vorher nur den Punkt.
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
		return Double.parseDouble(text.trim().replace(',', '.'));
	}
}
