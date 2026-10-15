package de.eltviller_carneval_verein.karten.ui.sales;

import java.util.Locale;
import java.util.OptionalDouble;
import java.util.function.Consumer;

import de.eltviller_carneval_verein.karten.ui.input.InputIssue;
import de.eltviller_carneval_verein.karten.ui.input.NumberInput;
import de.eltviller_carneval_verein.karten.validation.SeatValidator;
import de.eltviller_carneval_verein.karten.validation.ValidationIssue;
import javafx.scene.control.SpinnerValueFactory;
import javafx.util.StringConverter;

/**
 * Preisfeld in Euro für den Sitz-Dialog. Anders als {@code DoubleSpinnerValueFactory} darf das Feld leer sein (im
 * Sammel-Dialog: "verschieden"), ohne dass die Pfeiltasten eine NullPointerException auslösen. Eingaben werden mit
 * Komma oder Punkt gelesen ({@link NumberInput}). Unlesbarer Text und Preise außerhalb von 0 bis zur Obergrenze der
 * Plausibilitätsprüfung werden <strong>nicht übernommen</strong>: Der bisherige Wert bleibt, und der
 * {@linkplain #setRejectionHandler Handler} bekommt die Meldung (INP-001 bzw. INP-004). Die Pfeiltasten begrenzen am
 * Rand ohne Meldung.
 *
 * <p>
 * Der Converter meldet nur beim Bestätigen des Spinners. Wer den Text laufend auswertet (Gesamtpreis beim Tippen),
 * nimmt {@link #parseQuietly(String)}, sonst käme bei jedem halb getippten Preis eine Meldung.
 */
public class PriceSpinnerValueFactory extends SpinnerValueFactory<Double> {

	private static final double MIN = 0.0;
	private static final double MAX = SeatValidator.MAX_PRICE_CENTS / 100.0;
	private static final double STEP = 0.5;

	private String fieldLabel = "Preis";
	private Consumer<ValidationIssue> rejectionHandler = issue -> {
	};

	/** @param initial Startwert in Euro, {@code null} für ein leeres Feld */
	public PriceSpinnerValueFactory(Double initial) {
		setConverter(new StringConverter<>() {
			@Override
			public String toString(Double value) {
				return value == null ? "" : String.format(Locale.GERMANY, "%.2f", value);
			}

			@Override
			public Double fromString(String text) {
				return read(text, true);
			}
		});
		setValue(initial);
	}

	/** Name des Feldes in den Meldungen, Standard "Preis". */
	public void setFieldLabel(String fieldLabel) {
		this.fieldLabel = fieldLabel;
	}

	/** Wohin Meldungen über nicht übernommene Eingaben gehen; ohne Handler bleiben sie still (Tests, Vorschau). */
	public void setRejectionHandler(Consumer<ValidationIssue> rejectionHandler) {
		this.rejectionHandler = rejectionHandler;
	}

	/**
	 * Liest den Text wie der Converter, meldet aber nie: Bei leer {@code null}, bei unlesbarem oder unzulässigem
	 * Preis der bisherige Wert. Für Auswertungen, die bei jedem Tastendruck laufen.
	 */
	public Double parseQuietly(String text) {
		return read(text, false);
	}

	private Double read(String text, boolean report) {
		if (text == null || text.isBlank()) {
			return null;
		}
		OptionalDouble parsed = NumberInput.parseDecimal(text);
		if (parsed.isEmpty()) {
			if (report) {
				rejectionHandler.accept(InputIssue.NOT_A_NUMBER.toIssue(fieldLabel, text.trim()));
			}
			return getValue();
		}
		double euros = round(parsed.getAsDouble());
		if (euros < MIN || euros > MAX) {
			if (report) {
				rejectionHandler.accept(InputIssue.OUT_OF_RANGE.toIssue(fieldLabel, text.trim(), NumberInput.format(MIN), NumberInput.format(MAX)));
			}
			return getValue();
		}
		return euros;
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
		return Math.max(MIN, Math.min(MAX, round(value)));
	}

	/** Auf ganze Cent gerundet. */
	private static double round(double value) {
		return Math.round(value * 100) / 100.0;
	}
}
