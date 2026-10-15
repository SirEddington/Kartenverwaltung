package de.eltviller_carneval_verein.karten.ui.event;

import de.eltviller_carneval_verein.karten.MainApp;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.Table;
import de.eltviller_carneval_verein.karten.repository.JsonHallRepository;
import de.eltviller_carneval_verein.karten.ui.EventSaver;
import de.eltviller_carneval_verein.karten.ui.StatusMessage;
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
	private Spinner<Integer> spnTablePerPres;
	@FXML
	private Spinner<Double> spnDoubleTableWidth;
	@FXML
	private Spinner<Double> spnDoubleTableHeight;
	@FXML
	private Spinner<Integer> spnSeatsPerTable;
	@FXML
	private Spinner<Double> spnDoubleSeatWidth;
	@FXML
	private Spinner<Double> spnDoubleSeatHeight;
	@FXML
	private Spinner<Double> spnDoublePrice;

	@FXML
	public void initialize() {
		// Optional: null = keine Halle (kann später je Vorstellung in den Event-Details zugeordnet werden)
		cmbHall.getItems().add(null);
		cmbHall.getItems().addAll(JsonHallRepository.getInstance().loadHalls());
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
		// Gibt es genau eine Halle, ist sie vorausgewählt
		cmbHall.getSelectionModel().select(cmbHall.getItems().size() == 2 ? 1 : 0);
	}

	@FXML
	private void handleSave() {
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

		// Vorstellungen
		for (int i = 0; i < spnPresCount.getValue(); i++) {
			Presentation pres = event.addPresentation();
			if (cmbHall.getValue() != null) {
				pres.setHallId(cmbHall.getValue().getId());
			}
			pres.setDefaultTableHeight(spnDoubleTableHeight.getValue());
			pres.setDefaultTableWidth(spnDoubleTableWidth.getValue());
			pres.setDefaultSeatHeight(spnDoubleSeatHeight.getValue());
			pres.setDefaultSeatWidth(spnDoubleSeatWidth.getValue());
			// Tische
			for (int j = 0; j < spnTablePerPres.getValue(); j++) {
				Table table = pres.addTable();
				// Sitzplätze
				for (int k = 0; k < spnSeatsPerTable.getValue(); k++) {
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
	}

	@FXML
	private void handleCancel() {
		MainApp.showEventOverviewView();
	}
}
