package de.eltviller_carneval_verein.karten.ui.hall;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import de.eltviller_carneval_verein.karten.model.TextAlignH;
import de.eltviller_carneval_verein.karten.model.TextAlignV;
import de.eltviller_carneval_verein.karten.model.TextSettings;
import de.eltviller_carneval_verein.karten.ui.input.RotationInput;
import de.eltviller_carneval_verein.karten.ui.input.SpinnerInput;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Spinner;
import javafx.util.StringConverter;

/**
 * Die fünf Eingaben einer Beschriftung ({@link TextSettings}: Auto, Größe, horizontal, vertikal, Drehung) für
 * Hallenobjekte und Tische, eingerichtet und befüllt an einer Stelle. Mit "Text automatisch" sind die übrigen Felder
 * gesperrt und zeigen die berechneten Werte. Was eine Eingabe bewirkt, hängt der Aufrufer an die Felder.
 */
record TextFields(CheckBox auto, Spinner<Double> size, ComboBox<TextAlignH> alignH, ComboBox<TextAlignV> alignV, Spinner<Double> rotation) {

	/**
	 * Value-Factories, Eingabeprüfung und Auswahllisten; sperrt die Felder bei Auto.
	 *
	 * @param rotationName Name der Drehung in Fehlermeldungen der Fußzeile
	 * @param ownerLabel   das bearbeitete Objekt für die Fehlermeldungen
	 */
	void setup(String rotationName, Supplier<String> ownerLabel) {
		// Wertebereiche prüft der Validator, die Spinner haben keine Grenzen; die Drehung läuft im Kreis (360 = 0, -90 = 270)
		size.setValueFactory(SpinnerInput.doubleFactory(PlanText.DEFAULT_SIZE, 1));
		rotation.setValueFactory(RotationInput.factory(15));
		size.setEditable(true);
		rotation.setEditable(true);
		SpinnerInput.install(size, "Schriftgröße", ownerLabel);
		RotationInput.install(rotation, rotationName, ownerLabel);
		alignH.getItems().setAll(TextAlignH.values());
		alignH.setConverter(displayNames(TextAlignH::getDisplayName));
		alignV.getItems().setAll(TextAlignV.values());
		alignV.setConverter(displayNames(TextAlignV::getDisplayName));
		for (Node field : List.of(size, alignH, alignV, rotation)) {
			field.disableProperty().bind(auto.selectedProperty());
		}
	}

	/**
	 * Zeigt eine Beschriftung: bei Auto die berechneten Werte {@code resolved} (auf eine Nachkommastelle), sonst die
	 * gespeicherten. Ohne {@code resolved} (nichts zu messen) die gespeicherten.
	 */
	void fill(TextSettings stored, PlanText.Layout resolved) {
		auto.setSelected(stored.auto());
		boolean calculated = stored.auto() && resolved != null;
		size.getValueFactory().setValue(calculated ? PlanText.roundForDisplay(resolved.fontSize()) : stored.size());
		rotation.getValueFactory().setValue(calculated ? PlanText.roundForDisplay(resolved.rotation()) : stored.rotation());
		alignH.setValue(calculated ? resolved.alignH() : stored.alignH());
		alignV.setValue(calculated ? resolved.alignV() : stored.alignV());
	}

	/** Zeigt Werte einer festen Auswahl mit ihrem Anzeigenamen. */
	static <T> StringConverter<T> displayNames(Function<T, String> displayName) {
		return new StringConverter<>() {
			@Override
			public String toString(T value) {
				return value == null ? "" : displayName.apply(value);
			}

			@Override
			public T fromString(String string) {
				return null; // Bei fixer ComboBox-Auswahl nicht erforderlich
			}
		};
	}
}
