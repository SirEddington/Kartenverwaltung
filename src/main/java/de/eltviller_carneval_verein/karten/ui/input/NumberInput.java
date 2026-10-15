package de.eltviller_carneval_verein.karten.ui.input;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.regex.Pattern;

/**
 * Liest und schreibt Zahlen, wie sie an der Abendkasse getippt werden (ohne JavaFX, mit JUnit testbar). Anders als
 * die Standard-Konverter von JavaFX übernimmt es nie etwas Unverstandenes: Was nicht eindeutig lesbar ist, ergibt
 * ein leeres Ergebnis (zuvor wurde "12.5" still zu 12 und "1e9" still zum Minimum).
 *
 * <p>
 * Regeln für Dezimalzahlen: Das Komma ist der Dezimaltrenner. Ein Punkt gilt auch als Dezimaltrenner, wenn kein
 * Komma vorkommt. Kommen beide vor, ist der Punkt Tausendertrenner und steht in Dreiergruppen vor dem Komma
 * ("1.234,5"). Leerzeichen am Rand zählen nicht. Exponenten, "NaN" und mehrere Dezimaltrenner sind nicht lesbar.
 */
public final class NumberInput {

	private static final Pattern DIGITS = Pattern.compile("\\d+");
	private static final Pattern GROUPED_DIGITS = Pattern.compile("\\d{1,3}(\\.\\d{3})+");

	private NumberInput() {
	}

	/** Liest eine Dezimalzahl; leer, wenn der Text leer oder nicht eindeutig lesbar ist. */
	public static OptionalDouble parseDecimal(String text) {
		if (text == null) {
			return OptionalDouble.empty();
		}
		String trimmed = text.trim();
		boolean negative = trimmed.startsWith("-");
		String body = negative ? trimmed.substring(1) : trimmed;

		String integerPart;
		String fractionPart = null;
		int commas = count(body, ',');
		if (commas > 1) {
			return OptionalDouble.empty();
		} else if (commas == 1) {
			int comma = body.indexOf(',');
			String left = body.substring(0, comma);
			fractionPart = body.substring(comma + 1);
			if (!DIGITS.matcher(fractionPart).matches()) {
				return OptionalDouble.empty();
			}
			if (left.contains(".")) {
				if (!GROUPED_DIGITS.matcher(left).matches()) {
					return OptionalDouble.empty();
				}
				integerPart = left.replace(".", "");
			} else {
				integerPart = left;
			}
		} else {
			int dots = count(body, '.');
			if (dots > 1) {
				return OptionalDouble.empty();
			} else if (dots == 1) {
				int dot = body.indexOf('.');
				integerPart = body.substring(0, dot);
				fractionPart = body.substring(dot + 1);
				if (!DIGITS.matcher(fractionPart).matches()) {
					return OptionalDouble.empty();
				}
			} else {
				integerPart = body;
			}
		}
		if (!DIGITS.matcher(integerPart).matches()) {
			return OptionalDouble.empty();
		}

		double value = Double.parseDouble(integerPart + (fractionPart == null ? "" : "." + fractionPart));
		if (Double.isInfinite(value)) {
			return OptionalDouble.empty();
		}
		return OptionalDouble.of(negative ? -value : value);
	}

	/** Liest eine ganze Zahl (nur Ziffern, optional mit Minus); leer, wenn der Text nicht so lesbar ist. */
	public static OptionalInt parseInteger(String text) {
		if (text == null) {
			return OptionalInt.empty();
		}
		String trimmed = text.trim();
		String digits = trimmed.startsWith("-") ? trimmed.substring(1) : trimmed;
		if (!DIGITS.matcher(digits).matches()) {
			return OptionalInt.empty();
		}
		try {
			return OptionalInt.of(Integer.parseInt(trimmed));
		} catch (NumberFormatException e) {
			return OptionalInt.empty();
		}
	}

	/** Deutsche Schreibweise ohne überflüssige Nachkommastellen: 1000.0 ergibt "1000", 0.5 ergibt "0,5". */
	public static String format(double value) {
		return new DecimalFormat("0.####", DecimalFormatSymbols.getInstance(Locale.GERMANY)).format(value);
	}

	private static int count(String text, char c) {
		int count = 0;
		for (int i = 0; i < text.length(); i++) {
			if (text.charAt(i) == c) {
				count++;
			}
		}
		return count;
	}
}
