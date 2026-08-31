package de.eltviller_carneval_verein.karten.ui;

import de.eltviller_carneval_verein.karten.MainApp;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.Table;
import de.eltviller_carneval_verein.karten.repository.JsonTicketRepository;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;

public class EventCreateController {

	@FXML
	private Label lblTitle;
	@FXML
	private TextField txtEventName;
	@FXML
	private Spinner<Integer> spnPresCount;
	@FXML
	private Spinner<Integer> spnTablePerPres;
	@FXML
	private Spinner<Integer> spnSeatsPerTable;
	@FXML
	private Spinner<Double> spnDoublePrice;

	private final JsonTicketRepository repository = new JsonTicketRepository();
	private Event currentEvent;

	public void setEventToEdit(Event event) {
		this.currentEvent = event;
		if (event != null) {
			lblTitle.setText("Event bearbeiten: " + event.getName());
			txtEventName.setText(event.getName());
			// Falls das Event bereits existiert, können die Generierungsfelder deaktiviert oder vorbelegt werden
		}
	}

	@FXML
	private void handleSave() {
		String name = txtEventName.getText().trim();
		if (name.isEmpty()) {
			showAlert("Fehler", "Bitte gib einen Namen für das Event ein.");
			return;
		}

		if (currentEvent == null) {
			currentEvent = buildNewEvent(name);
		} else {
			currentEvent.changeName(name);
		}

		repository.saveEvent(currentEvent);
		MainApp.showMenuView();
	}

	private Event buildNewEvent(String name) {
		Event event = new Event(name);

		// Vorstellungen
		for (int i = 0; i < spnPresCount.getValue(); i++) {
			Presentation pres = event.addPresentation();
			// Tische
			for (int j = 0; j < spnTablePerPres.getValue(); j++) {
				Table table = pres.addTable();
				// Sitzplätze
				for (int k = 0; k < spnTablePerPres.getValue(); k++) {
					Seat seat = table.addSeat();
					seat.setPriceDouble(spnDoublePrice.getValue());
				}
			}
		}
		return event;
	}

	@FXML
	private void handleBackToEventOverview() {
		MainApp.showEventOverviewView();
		;
	}

	@FXML
	private void handleCancel() {
		MainApp.showMenuView();
	}

	private void showAlert(String title, String content) {
		Alert alert = new Alert(Alert.AlertType.WARNING);
		alert.setTitle(title);
		alert.setHeaderText(null);
		alert.setContentText(content);
		alert.showAndWait();
	}
}
