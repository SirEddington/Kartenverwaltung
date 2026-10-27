package de.eltviller_carneval_verein.karten.ui.event;

import de.eltviller_carneval_verein.karten.MainApp;
import de.eltviller_carneval_verein.karten.model.BookingSync;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.repository.StoreHallRepository;
import de.eltviller_carneval_verein.karten.ui.EventSaver;
import de.eltviller_carneval_verein.karten.ui.StatusMessage;
import de.eltviller_carneval_verein.karten.ui.input.SpinnerInput;
import de.eltviller_carneval_verein.karten.util.MoneyFormat;
import de.eltviller_carneval_verein.karten.validation.EntityLabels;
import de.eltviller_carneval_verein.karten.validation.Severity;
import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.util.StringConverter;

public class EventCreateController {

	@FXML
	private TextField txtEventName;
	@FXML
	private Spinner<Integer> spnPresCount;
	@FXML
	private ComboBox<Hall> cmbHall;
	@FXML
	private ComboBox<SeatingPlan> cmbSeatingPlan;
	@FXML
	private Spinner<Double> spnDoublePrice;

	@FXML
	public void initialize() {
		// Startwerte der Spinner; sie haben keine Grenzen, die Anzahl im Dialog bekommt keine Regel, den Standardpreis prüft der Validator (PRS-008)
		spnPresCount.setValueFactory(SpinnerInput.integerFactory(3, 1));
		spnDoublePrice.setValueFactory(SpinnerInput.doubleFactory(17.0, 1.0));
		// Tippfehler in den Spinnern: alter Wert bleibt, Meldung in der Fußzeile
		SpinnerInput.installInteger(spnPresCount, "Anzahl Vorstellungen", null);
		SpinnerInput.install(spnDoublePrice, "Standardpreis", null);

		// Optional: null = keine Halle (kann später je Vorstellung in den Event-Details zugeordnet werden)
		cmbHall.getItems().add(null);
		cmbHall.getItems().addAll(StoreHallRepository.getInstance().loadHalls());
		cmbHall.setConverter(new StringConverter<Hall>() {
			@Override
			public String toString(Hall hall) {
				return hall == null ? "– keine Halle –" : EntityLabels.nameOrPlaceholder(hall.getName());
			}

			@Override
			public Hall fromString(String string) {
				return null; // Bei fixer Auswahl nicht erforderlich
			}
		});

		// Bestuhlung: nur die der gewählten Halle, vorausgewählt ist die erste
		cmbSeatingPlan.setConverter(new StringConverter<SeatingPlan>() {
			@Override
			public String toString(SeatingPlan plan) {
				return plan == null ? "" : EntityLabels.nameOrPlaceholder(plan.getName());
			}

			@Override
			public SeatingPlan fromString(String string) {
				return null; // Bei fixer Auswahl nicht erforderlich
			}
		});
		cmbHall.valueProperty().addListener((obs, oldHall, newHall) -> showPlansOf(newHall));

		// Gibt es genau eine Halle, ist sie vorausgewählt
		cmbHall.getSelectionModel().select(cmbHall.getItems().size() == 2 ? 1 : 0);
		showPlansOf(cmbHall.getValue());
	}

	/** Füllt die Bestuhlungsauswahl mit den Bestuhlungen der Halle und wählt die erste (ohne Halle: leer und gesperrt). */
	private void showPlansOf(Hall hall) {
		if (hall == null) {
			cmbSeatingPlan.getItems().clear();
		} else {
			cmbSeatingPlan.getItems().setAll(hall.getSeatingPlans());
		}
		cmbSeatingPlan.setValue(cmbSeatingPlan.getItems().isEmpty() ? null : cmbSeatingPlan.getItems().get(0));
		cmbSeatingPlan.setDisable(cmbSeatingPlan.getItems().isEmpty());
		cmbSeatingPlan.setPromptText(hall == null ? "– keine Halle –" : "Keine Bestuhlung");
	}

	@FXML
	private void handleSave() {
		// Der Klick auf Speichern nimmt einem Spinner den Fokus: Wurde seine Eingabe abgelehnt, nicht stillschweigend mit dem alten Wert anlegen
		if (SpinnerInput.blockSaveIfScreenInputRejected(txtEventName)) {
			return;
		}
		String name = txtEventName.getText().trim();
		if (name.isEmpty()) {
			StatusMessage.getInstance().show(Severity.ERROR, "Bitte gib einen Namen für das Event ein.");
			return;
		}

		// Das Event wird bei jedem Versuch aus den aktuellen Feldern gebaut und nicht gemerkt: Scheitert das
		// Speichern, soll der nächste Versuch die inzwischen geänderten Eingaben übernehmen
		if (!EventSaver.save(buildNewEvent(name))) {
			return;
		}
		MainApp.showEventOverviewView();
	}

	private Event buildNewEvent(String name) {
		// Die ID bleibt eine UUID: Sie bestimmt den Dateinamen, der Name darf beliebige Zeichen enthalten
		Event event = new Event();
		event.setName(name);

		Hall hall = cmbHall.getValue();
		SeatingPlan plan = hall != null ? cmbSeatingPlan.getValue() : null;
		int priceCents = MoneyFormat.toCents(spnDoublePrice.getValue());

		// Vorstellungen: Halle, Bestuhlung und Standardpreis, dann je Sitz eine leere Buchung. Ohne Halle oder
		// Bestuhlung entsteht die Vorstellung ohne Bestuhlung (die Prüfregeln warnen wie gewohnt)
		for (int i = 0; i < spnPresCount.getValue(); i++) {
			Presentation pres = event.addPresentation();
			if (hall != null) {
				pres.setHallId(hall.getId());
			}
			if (plan != null) {
				pres.setSeatingPlanId(plan.getId());
			}
			// Neue Vorstellung ohne Buchungen mit Daten: Es gibt keinen Preis festzuhalten, daher setPrice statt changePrice
			pres.setPrice(priceCents);
			BookingSync.sync(pres, plan);
		}
		return event;
	}

	@FXML
	private void handleBackToEventOverview() {
		MainApp.showEventOverviewView();
	}

	@FXML
	private void handleCancel() {
		MainApp.showEventOverviewView();
	}
}
