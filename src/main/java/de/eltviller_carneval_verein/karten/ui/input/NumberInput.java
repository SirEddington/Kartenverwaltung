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

	/** Mit Tausenderpunkten und Komma: "1.234,5", "-1.234.567,0". */
	private static final Pattern GROUPED = Pattern.compile("-?\\d{1,3}(\\.\\d{3})+,\\d+");
	/** Ganzzahl oder Dezimalzahl mit genau einem Komma oder Punkt: "12", "12,5", "12.5", "-5". */
	private static final Pattern PLAIN = Pattern.compile("-?\\d+([.,]\\d+)?");
	private static final Pattern INTEGER = Pattern.compile("-?\\d+");

	// DecimalFormat ist nicht threadsicher und teuer zu bauen; format läuft u. a. bei jeder Mausbewegung im Hallenplan
	private static final ThreadLocal<DecimalFormat> FORMAT = ThreadLocal
			.withInitial(() -> new DecimalFormat("0.####", DecimalFormatSymbols.getInstance(Locale.GERMANY)));

	private NumberInput() {
	}

	/** Liest eine Dezimalzahl; leer, wenn der Text leer oder nicht eindeutig lesbar ist. */
	public static OptionalDouble parseDecimal(String text) {
		if (text == null) {
			return OptionalDouble.empty();
		}
		String trimmed = text.trim();
		String normalized;
		if (GROUPED.matcher(trimmed).matches()) {
			normalized = trimmed.replace(".", "").replace(',', '.');
		} else if (PLAIN.matcher(trimmed).matches()) {
			normalized = trimmed.replace(',', '.');
		} else {
			return OptionalDouble.empty();
		}
		double value = Double.parseDouble(normalized);
		return Double.isInfinite(value) ? OptionalDouble.empty() : OptionalDouble.of(value);
	}

	/** Liest eine ganze Zahl (nur Ziffern, optional mit Minus); leer, wenn der Text nicht so lesbar ist. */
	public static OptionalInt parseInteger(String text) {
		if (text == null) {
			return OptionalInt.empty();
		}
		String trimmed = text.trim();
		if (!INTEGER.matcher(trimmed).matches()) {
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
		return FORMAT.get().format(value);
	}
}
