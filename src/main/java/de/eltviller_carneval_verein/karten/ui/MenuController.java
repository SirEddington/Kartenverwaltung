package de.eltviller_carneval_verein.karten.ui;

import de.eltviller_carneval_verein.karten.MainApp;
import javafx.fxml.FXML;

public class MenuController {

	@FXML
	private void handleOpenSales() {
		MainApp.showTicketShellView();
	}

	@FXML
	private void handleOpenAccounting() {
		MainApp.showAccountingShellView();
	}

	@FXML
	private void handleOpenEventManagement() {
		MainApp.showEventOverviewView();
	}
}