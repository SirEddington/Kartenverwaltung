package de.eltviller_carneval_verein.karten;

import java.io.IOException;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.Table;
import de.eltviller_carneval_verein.karten.ui.EventEditController;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.stage.Stage;

public class MainApp extends Application {

	private static Stage primaryStage;
	private static double width;
	private static double height;

	@SuppressWarnings("exports")
	@Override
	public void start(Stage stage) throws Exception {
		primaryStage = stage;
		width = 1000;
		height = 600;
		primaryStage.setWidth(width);
		primaryStage.setHeight(height);

		stage.setTitle("ECV Kartenverwaltung");

		// Startet direkt im Hauptmenü
		showMenuView();
		primaryStage.show();
	}

	public static void showMenuView() {
		loadScene("/de/eltviller_carneval_verein/karten/ui/MenuView.fxml");
	}

	public static void showTableView() {
		loadScene("/de/eltviller_carneval_verein/karten/ui/MainView.fxml");
	}

	public static void showEventOverviewView() {
		loadScene("/de/eltviller_carneval_verein/karten/ui/EventOverviewView.fxml");
	}

	public static void showEventCreateView() {
		loadScene("/de/eltviller_carneval_verein/karten/ui/EventCreateView.fxml");
	}

	public static void showEventEditView(Event selectedEvent, Presentation selectedPres, Table selectedTable, Seat selectedSeat, boolean editable) {
		try {
			String fxmlPath = "/de/eltviller_carneval_verein/karten/ui/EventEditView.fxml";

			// Aktuelle Fenstergröße holen
			width = primaryStage.getWidth();
			height = primaryStage.getHeight();

			// 1. FXMLLoader mit dem Pfad zur FXML instanziieren
			FXMLLoader loader = new FXMLLoader(MainApp.class.getResource(fxmlPath));

			// 2. Layout laden (erzeugt auch die Controller-Instanz)
			Parent root = loader.load();

			// 3. Controller-Instanz von JavaFX anfordern
			EventEditController controller = loader.getController();

			// 4. Parameter direkt an den Controller übergeben
			controller.initData(selectedEvent, selectedPres, selectedTable, selectedSeat, editable);

			// 5. Scene setzen
			Scene scene = new Scene(root, width, height);
			primaryStage.setScene(scene);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private static void loadScene(String fxmlPath) {
		try {
			// Aktuelle Fenstergröße holen
			width = primaryStage.getWidth();
			height = primaryStage.getHeight();

			// 1. FXMLLoader mit dem Pfad zur FXML instanziieren
			FXMLLoader loader = new FXMLLoader(MainApp.class.getResource(fxmlPath));

			// 2. Layout laden (erzeugt auch die Controller-Instanz)
			Parent root = loader.load();

			// 3. Scene setzen
			Scene scene = new Scene(root, width, height);
			primaryStage.setScene(scene);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	@SuppressWarnings("exports")
	public static void showAlert(String title, String content, AlertType alertType) {
		Alert alert = new Alert(alertType);
		alert.setTitle(title);
		alert.setHeaderText(null);
		alert.setContentText(content);
		alert.showAndWait();
	}

	public static void main(String[] args) {
		launch(args);
	}

}
