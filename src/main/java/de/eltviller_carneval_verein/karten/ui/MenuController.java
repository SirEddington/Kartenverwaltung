package de.eltviller_carneval_verein.karten.ui;

import de.eltviller_carneval_verein.karten.MainApp;
import javafx.fxml.FXML;

public class MenuController {

	@FXML
	private void handleOpenTable() {
		MainApp.showTicketShellView();
	}

	@FXML
	private void handleCreateEvent() {
		MainApp.showEventOverviewView();
	}
}