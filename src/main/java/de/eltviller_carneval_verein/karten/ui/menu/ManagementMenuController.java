package de.eltviller_carneval_verein.karten.ui.menu;

import de.eltviller_carneval_verein.karten.MainApp;
import javafx.fxml.FXML;

public class ManagementMenuController {

	@FXML
	private void handleOpenEventManagement() {
		MainApp.showEventOverviewView();
	}

	@FXML
	private void handleOpenHallManagement() {
		MainApp.showHallShellView();
	}

	@FXML
	private void handleBackToMenu() {
		MainApp.showMenuView();
	}
}
