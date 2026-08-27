package de.eltviller_carneval_verein.karten.ui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
	private TextField txtPresentations;
	@FXML
	private Spinner<Integer> spnTableCount;
	@FXML
	private Spinner<Integer> spnSeatsPerTable;
	@FXML
	private TextField txtCategory;

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
		List<Presentation> presentations = new ArrayList<>();

		// Vorstellungen aus Kommaliste lesen
		String rawPres = txtPresentations.getText().trim();
		List<String> presNames = rawPres.isEmpty() ? List.of("Hauptvorstellung") : Arrays.asList(rawPres.split("\\s*,\\s*"));

		for (String presName : presNames) {
			Presentation pres = new Presentation(presName);
			pres.setTables(generateTables());
			presentations.add(pres);
		}

		event.setPresentations(presentations);
		return event;
	}

	private List<Table> generateTables() {
		List<Table> tables = new ArrayList<>();
		int tableCount = spnTableCount.getValue();
		int seatsPerTable = spnSeatsPerTable.getValue();
		String category = txtCategory.getText().trim();

		for (int t = 1; t <= tableCount; t++) {
			Table table = new Table(t);
			table.setCategory(category.isEmpty() ? "Standard" : category);

			List<Seat> seats = new ArrayList<>();
			for (int s = 1; s <= seatsPerTable; s++) {
				Seat seat = new Seat(s);
				seat.setPaid(false);
				seat.setCollected(false);
				seat.setWheelchairAccessible(false);
				seats.add(seat);
			}
			table.setSeats(seats);
			tables.add(table);
		}
		return tables;
	}

	@FXML
	private void handleBackToMenu() {
		MainApp.showMenuView();
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
