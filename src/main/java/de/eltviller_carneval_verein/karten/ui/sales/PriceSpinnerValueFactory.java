package de.eltviller_carneval_verein.karten.ui.sales;

import java.util.Locale;

import de.eltviller_carneval_verein.karten.validation.SeatValidator;
import javafx.scene.control.SpinnerValueFactory;
import javafx.util.StringConverter;

/**
 * Preisfeld in Euro für den Sitz-Dialog. Anders als {@code DoubleSpinnerValueFactory} darf das Feld leer sein (im
 * Sammel-Dialog: "verschieden"), ohne dass die Pfeiltasten eine NullPointerException auslösen. Eingaben werden mit
 * Komma oder Punkt gelesen und auf 0 bis zur Obergrenze der Plausibilitätsprüfung begrenzt; unlesbarer Text behält
 * den bisherigen Wert.
 */
public class PriceSpinnerValueFactory extends SpinnerValueFactory<Double> {

	private static final double MIN = 0.0;
	private static final double MAX = SeatValidator.MAX_PRICE_CENTS / 100.0;
	private static final double STEP = 0.5;

	/** @param initial Startwert in Euro, {@code null} für ein leeres Feld */
	public PriceSpinnerValueFactory(Double initial) {
		setConverter(new StringConverter<>() {
			@Override
			public String toString(Double value) {
				return value == null ? "" : String.format(Locale.GERMANY, "%.2f", value);
			}

			@Override
			public Double fromString(String text) {
				if (text == null || text.isBlank()) {
					return null;
				}
				try {
					return clamp(Double.parseDouble(text.trim().replace(',', '.')));
				} catch (NumberFormatException e) {
					return getValue();
				}
			}
		});
		setValue(initial);
	}

	@Override
	public void decrement(int steps) {
		step(-steps);
	}

	@Override
	public void increment(int steps) {
		step(steps);
	}

	private void step(int steps) {
		double current = getValue() == null ? MIN : getValue();
		setValue(clamp(current + steps * STEP));
	}

	private static double clamp(double value) {
		double rounded = Math.round(value * 100) / 100.0;
		return Math.max(MIN, Math.min(MAX, rounded));
	}
}
