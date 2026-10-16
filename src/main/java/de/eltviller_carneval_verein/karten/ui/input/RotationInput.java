package de.eltviller_carneval_verein.karten.ui.input;

import java.util.function.Supplier;

import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;

/**
 * Eingabe von Drehungen in Grad, überall gleich: Der Wert läuft im Kreis von 0 bis unter 360. Die Pfeiltasten gehen
 * von 345 auf 0 und von 0 auf 345 (der Standard-Umlauf von JavaFX würde bei 0 nach unten auf 360 springen), getippte
 * Werte außerhalb davon werden auf den Kreis gebracht (360 = 0, 400 = 40, -10 = 350).
 *
 * <p>
 * Aufruf wie bei den übrigen Spinnern: erst {@code setValueFactory(RotationInput.factory(step))}, dann
 * {@link #install}. Die Factory ist eine {@link SpinnerValueFactory.DoubleSpinnerValueFactory} ohne Wertebereich;
 * {@code install} bringt die Fehlerbehandlung für Tippfehler von {@link SpinnerInput} mit und rechnet getippte Werte
 * schon beim Lesen um, sodass die Listener nur Werte von 0 bis unter 360 sehen.
 */
public final class RotationInput {

	private static final double FULL_CIRCLE = 360.0;
	// Auf vier Stellen gerundet, damit sich keine Rechenreste (14.990000000000009) anhäufen
	private static final double ROUNDING = 10000.0;

	private RotationInput() {
	}

	/** Drehung in Grad auf 0 bis unter 360 bringen (nie -0 und nie 360). */
	public static double normalize(double degrees) {
		double normalized = degrees % FULL_CIRCLE;
		if (normalized < 0) {
			normalized += FULL_CIRCLE;
		}
		// Ein winziger negativer Rest ergibt beim Aufaddieren genau 360; + 0.0 macht aus -0 eine 0
		return normalized >= FULL_CIRCLE ? 0.0 : normalized + 0.0;
	}

	/** Der Wert nach {@code steps} Pfeiltasten-Schritten um {@code step} Grad, auf dem Kreis und gerundet. */
	static double turn(Double value, int steps, double step) {
		double next = normalize((value == null ? 0.0 : value) + steps * step);
		return normalize(Math.round(next * ROUNDING) / ROUNDING);
	}

	/**
	 * Wie {@link SpinnerInput#install(Spinner, String, Supplier)}, getippte Werte werden auf 0 bis unter 360 gebracht.
	 * Aufruf nach dem Setzen der Value-Factory ({@link #factory}).
	 */
	public static void install(Spinner<Double> spinner, String fieldName, Supplier<String> rowLabel) {
		SpinnerInput.install(spinner, fieldName, rowLabel, RotationInput::normalize);
	}

	/**
	 * Value-Factory für Drehungen mit Startwert 0, Pfeiltasten um {@code step} Grad im Kreis. Von außen gesetzte Werte
	 * außerhalb (z. B. ein gespeichertes -10) zeigt sie umgerechnet an.
	 */
	public static SpinnerValueFactory.DoubleSpinnerValueFactory factory(double step) {
		SpinnerValueFactory.DoubleSpinnerValueFactory factory = new SpinnerValueFactory.DoubleSpinnerValueFactory(-Double.MAX_VALUE, Double.MAX_VALUE,
				0.0, step) {
			@Override
			public void decrement(int steps) {
				setValue(turn(getValue(), -steps, getAmountToStepBy()));
			}

			@Override
			public void increment(int steps) {
				setValue(turn(getValue(), steps, getAmountToStepBy()));
			}
		};
		factory.valueProperty().addListener((obs, oldValue, newValue) -> {
			if (newValue != null && (newValue < 0.0 || newValue >= FULL_CIRCLE)) {
				factory.setValue(normalize(newValue));
			}
		});
		return factory;
	}
}
