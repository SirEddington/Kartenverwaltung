package de.eltviller_carneval_verein.karten.ui.input;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.function.DoubleUnaryOperator;
import java.util.function.Function;
import java.util.function.Supplier;

import de.eltviller_carneval_verein.karten.validation.ValidationIssue;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.util.StringConverter;

/**
 * Macht einen editierbaren Spinner tolerant gegenüber Tippfehlern, überall gleich. Der Standard-Konverter von JavaFX
 * wirft bei "abc" eine Exception, macht aus "12.5" still 12, aus "1e9" still das Minimum, aus einem leeren Feld
 * {@code null} und begrenzt zu große Werte still.
 *
 * <p>
 * Mit {@code install} liest der Spinner Dezimalzahlen mit Komma und Punkt ({@link NumberInput}). Ein leeres Feld oder ein
 * unlesbarer Text (INP-001) wird <strong>nicht übernommen</strong>: Der bisherige Wert bleibt, der Editor-Text wird auf
 * ihn zurückgesetzt, die Fußzeile nennt Code und Grund, und der Spinner wird kurz rot ({@link InputFeedback}).
 *
 * <p>
 * Der Spinner kennt <strong>keinen Wertebereich</strong>: {@code install} hebt Minimum und Maximum der Value-Factory
 * auf, und die Pfeiltasten haben keinen Anschlag. Zu kleine oder zu große Werte meldet der Validator (z. B. HLL-002,
 * HOB-002, TBL-003, SEA-003) live und beim Speichern.
 *
 * <p>
 * Aufruf nach dem Setzen der Value-Factory. Nur im JavaFX-Thread.
 */
public final class SpinnerInput {

	/** Schlüssel in den Eigenschaften des Spinners: die zuletzt abgelehnte Eingabe, bis eine gültige bestätigt wird. */
	private static final String PENDING_REJECTION = SpinnerInput.class.getName() + ".pendingRejection";
	/**
	 * Schlüssel: der Text, auf den {@link #reject} das Feld zurückgesetzt hat. Beim Fokuswechsel bestätigt der Spinner
	 * zweimal; das zweite Mal liest er diesen zurückgesetzten (gültigen) Text und darf den Merker nicht löschen.
	 */
	private static final String RESTORED_TEXT = SpinnerInput.class.getName() + ".restoredText";

	private SpinnerInput() {
	}

	/** Value-Factory ohne Wertebereich für Dezimalzahlen, Startwert {@code initial}, Pfeiltasten um {@code step}. */
	public static SpinnerValueFactory.DoubleSpinnerValueFactory doubleFactory(double initial, double step) {
		return new SpinnerValueFactory.DoubleSpinnerValueFactory(-Double.MAX_VALUE, Double.MAX_VALUE, initial, step);
	}

	/** Value-Factory ohne Wertebereich für ganze Zahlen, Startwert {@code initial}, Pfeiltasten um {@code step}. */
	public static SpinnerValueFactory.IntegerSpinnerValueFactory integerFactory(int initial, int step) {
		return new SpinnerValueFactory.IntegerSpinnerValueFactory(Integer.MIN_VALUE, Integer.MAX_VALUE, initial, step);
	}

	/** Für einen {@code Spinner<Double>} mit {@link SpinnerValueFactory.DoubleSpinnerValueFactory}. */
	public static void install(Spinner<Double> spinner, String fieldName, Supplier<String> rowLabel) {
		install(spinner, fieldName, rowLabel, DoubleUnaryOperator.identity());
	}

	/**
	 * Wie {@link #install(Spinner, String, Supplier)}, eine gelesene Zahl wird vor der Übernahme mit {@code adjust}
	 * umgerechnet (z. B. eine Drehung auf 0 bis unter 360, {@link RotationInput}). So erreicht nur der umgerechnete Wert
	 * den Spinner und die Listener.
	 */
	public static void install(Spinner<Double> spinner, String fieldName, Supplier<String> rowLabel, DoubleUnaryOperator adjust) {
		if (!(spinner.getValueFactory() instanceof SpinnerValueFactory.DoubleSpinnerValueFactory factory)) {
			throw new IllegalArgumentException("Der Spinner braucht eine DoubleSpinnerValueFactory (zuerst setValueFactory aufrufen).");
		}
		// Wertebereiche prüft der Validator, nicht der Spinner: Die Grenzen der Factory fallen weg
		factory.setMin(-Double.MAX_VALUE);
		factory.setMax(Double.MAX_VALUE);
		install(spinner, factory, text -> {
			OptionalDouble parsed = NumberInput.parseDecimal(text);
			if (parsed.isEmpty()) {
				return Optional.empty();
			}
			double adjusted = adjust.applyAsDouble(parsed.getAsDouble());
			// Bleibt der Wert durch die Umrechnung gleich (0 und getippt 360), schreibt der Spinner den Text nicht neu
			if (adjusted != parsed.getAsDouble() && Double.valueOf(adjusted).equals(factory.getValue())) {
				restoreText(spinner);
			}
			return Optional.of(adjusted);
		}, NumberInput::format, fieldName, rowLabel);
	}

	/** Für einen {@code Spinner<Integer>} mit {@link SpinnerValueFactory.IntegerSpinnerValueFactory}. */
	public static void installInteger(Spinner<Integer> spinner, String fieldName, Supplier<String> rowLabel) {
		if (!(spinner.getValueFactory() instanceof SpinnerValueFactory.IntegerSpinnerValueFactory factory)) {
			throw new IllegalArgumentException("Der Spinner braucht eine IntegerSpinnerValueFactory (zuerst setValueFactory aufrufen).");
		}
		factory.setMin(Integer.MIN_VALUE);
		factory.setMax(Integer.MAX_VALUE);
		install(spinner, factory, text -> {
			OptionalInt parsed = NumberInput.parseInteger(text);
			return parsed.isPresent() ? Optional.of(parsed.getAsInt()) : Optional.empty();
		}, String::valueOf, fieldName, rowLabel);
	}

	/**
	 * Meldet eine nicht übernommene Eingabe (Fußzeile, rote Markierung) und setzt den Editor-Text auf den aktuellen
	 * Wert zurück. Das Zurücksetzen ist nötig, weil der Spinner den Text nur bei geändertem Wert selbst
	 * aktualisiert: Bleibt der Wert gleich, stünde sonst weiter "abc" im Feld.
	 */
	public static void reject(Spinner<?> spinner, ValidationIssue issue) {
		spinner.getProperties().put(PENDING_REJECTION, issue);
		InputFeedback.report(issue, spinner);
		restoreText(spinner);
		spinner.getProperties().put(RESTORED_TEXT, spinner.getEditor().getText());
	}

	/**
	 * Für die Speichern-Handler der Screens mit Spinnern: Wurde in einem der Spinner eine Eingabe abgelehnt und seither
	 * keine gültige bestätigt, geht das Speichern <strong>nicht</strong> weiter. Der Klick auf "Speichern" nimmt dem
	 * Spinner den Fokus und löst die Ablehnung aus, bevor die Speichern-Aktion läuft; ohne diese Abfrage würde "Gespeichert"
	 * die Meldung überschreiben und der Nutzer merkt nicht, dass seine Eingabe (z. B. 150 bei Maximum 100) nicht galt.
	 * Die Meldung wird wieder angezeigt, der Merker ist damit verbraucht: Der nächste Klick speichert.
	 *
	 * @return {@code true}, wenn nicht gespeichert werden soll
	 */
	public static boolean blockSaveIfInputRejected(Spinner<?>... spinners) {
		boolean rejected = false;
		for (Spinner<?> spinner : spinners) {
			Object pending = spinner.getProperties().remove(PENDING_REJECTION);
			if (pending instanceof ValidationIssue issue) {
				InputFeedback.report(issue, spinner);
				rejected = true;
			}
		}
		return rejected;
	}

	/**
	 * Wie {@link #blockSaveIfInputRejected(Spinner...)} für alle Spinner des Screens, in dem {@code anyNodeOfScreen}
	 * liegt (auch eingebundene Teilansichten wie der Hallenplan). Ein neuer Spinner ist damit automatisch abgedeckt, ohne
	 * dass jeder Speichern-Handler seine Spinner aufzählt.
	 */
	public static boolean blockSaveIfScreenInputRejected(Node anyNodeOfScreen) {
		Scene scene = anyNodeOfScreen.getScene();
		if (scene == null) {
			return false;
		}
		Spinner<?>[] spinners = scene.getRoot().lookupAll(".spinner").stream().filter(Spinner.class::isInstance).map(node -> (Spinner<?>) node)
				.toArray(Spinner<?>[]::new);
		return blockSaveIfInputRejected(spinners);
	}

	private static <T extends Number> void install(Spinner<T> spinner, SpinnerValueFactory<T> factory, Function<String, Optional<T>> reader,
			Function<T, String> formatter, String fieldName, Supplier<String> rowLabel) {
		factory.setConverter(new StringConverter<T>() {
			@Override
			public String toString(T value) {
				return value == null ? "" : formatter.apply(value);
			}

			@Override
			public T fromString(String text) {
				String label = InputFeedback.label(fieldName, rowLabel == null ? null : rowLabel.get());
				String typed = InputFeedback.displayText(text == null ? null : text.trim());
				Optional<T> parsed = reader.apply(text);
				if (parsed.isEmpty()) {
					reject(spinner, InputIssue.NOT_A_NUMBER.toIssue(label, typed));
					return factory.getValue();
				}
				// Nur eine neue gültige Eingabe löscht den Merker, nicht das erneute Lesen des zurückgesetzten Textes
				if (text == null || !text.trim().equals(spinner.getProperties().get(RESTORED_TEXT))) {
					spinner.getProperties().remove(PENDING_REJECTION);
				}
				return parsed.get();
			}
		});
		// Der Spinner übernimmt den Text nicht von selbst, wenn nur der Converter getauscht wird
		restoreText(spinner);
	}

	private static <T> void restoreText(Spinner<T> spinner) {
		SpinnerValueFactory<T> factory = spinner.getValueFactory();
		if (factory == null) {
			return;
		}
		StringConverter<T> converter = factory.getConverter();
		spinner.getEditor().setText(converter == null ? "" : converter.toString(factory.getValue()));
	}
}
