package de.eltviller_carneval_verein.karten.ui.sales;

import java.util.Locale;
import java.util.OptionalDouble;
import java.util.function.Consumer;

import de.eltviller_carneval_verein.karten.ui.input.InputIssue;
import de.eltviller_carneval_verein.karten.ui.input.NumberInput;
import de.eltviller_carneval_verein.karten.validation.ValidationIssue;
import javafx.scene.control.SpinnerValueFactory;
import javafx.util.StringConverter;

/**
 * Preisfeld in Euro für den Sitz-Dialog. Anders als {@code DoubleSpinnerValueFactory} darf das Feld leer sein (im
 * Sammel-Dialog: "verschieden"), ohne dass die Pfeiltasten eine NullPointerException auslösen. Eingaben werden mit
 * Komma oder Punkt gelesen ({@link NumberInput}). Unlesbarer Text wird <strong>nicht übernommen</strong>: Der bisherige
 * Wert bleibt, und der {@linkplain #setRejectionHandler Handler} bekommt die Meldung (INP-001). Einen Wertebereich
 * kennt das Feld nicht, auch die Pfeiltasten haben keinen Anschlag: Zu kleine oder zu große Preise meldet der
 * {@code SeatValidator} (SEA-001, SEA-003).
 *
 * <p>
 * Der Converter meldet nur beim Bestätigen des Spinners. Wer den Text laufend auswertet (Gesamtpreis beim Tippen),
 * nimmt {@link #parseQuietly(String)}, sonst käme bei jedem halb getippten Preis eine Meldung.
 */
public class PriceSpinnerValueFactory extends SpinnerValueFactory<Double> {

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
				return read(text, rejectionHandler);
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
	 * Liest den Text wie der Converter, meldet aber nie: Bei leer {@code null}, bei unlesbarem Preis der bisherige Wert.
	 * Für Auswertungen, die bei jedem Tastendruck laufen.
	 */
	public Double parseQuietly(String text) {
		return read(text, issue -> {
		});
	}

	/** Liest den Text; bei einer abgelehnten Eingabe geht die Meldung an {@code onReject} und der bisherige Wert bleibt. */
	private Double read(String text, Consumer<ValidationIssue> onReject) {
		if (text == null || text.isBlank()) {
			return null;
		}
		OptionalDouble parsed = NumberInput.parseDecimal(text);
		if (parsed.isEmpty()) {
			onReject.accept(InputIssue.NOT_A_NUMBER.toIssue(fieldLabel, text.trim()));
			return getValue();
		}
		return round(parsed.getAsDouble());
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
		double current = getValue() == null ? 0.0 : getValue();
		setValue(round(current + steps * STEP));
	}

	/** Auf ganze Cent gerundet. */
	private static double round(double value) {
		return Math.round(value * 100) / 100.0;
	}
}
