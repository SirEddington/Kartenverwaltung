package de.eltviller_carneval_verein.karten;

import java.io.IOException;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.ui.EventEditController;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class MainApp extends Application {

	private static Stage primaryStage;

	@Override
	public void start(Stage stage) throws Exception {
		primaryStage = stage;

		stage.setTitle("ECV Kartenverwaltung");

		// Startet direkt im Hauptmenü
		showMenuView();
		primaryStage.show();
	}

	public static void showMenuView() {
		loadScene("/de/eltviller_carneval_verein/karten/ui/MenuView.fxml", 600, 400);
	}

	public static void showTableView() {
		loadScene("/de/eltviller_carneval_verein/karten/ui/MainView.fxml", 1000, 600);
	}

	public static void showEventEditView(Event eventToEdit) {
		try {
			FXMLLoader loader = new FXMLLoader(MainApp.class.getResource("/de/eltviller_carneval_verein/karten/ui/EventEditView.fxml"));
			Parent root = loader.load();

			EventEditController controller = loader.getController();
			controller.setEventToEdit(eventToEdit);

			primaryStage.setScene(new Scene(root, 700, 550));
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private static void loadScene(String fxmlPath, double width, double height) {
		try {
			FXMLLoader loader = new FXMLLoader(MainApp.class.getResource(fxmlPath));
			Parent root = loader.load();
			Scene scene = new Scene(root, width, height);
			primaryStage.setScene(scene);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public static void main(String[] args) {
		launch(args);
	}

}
