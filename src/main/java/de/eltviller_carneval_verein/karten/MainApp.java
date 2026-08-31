package de.eltviller_carneval_verein.karten;

import java.io.IOException;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class MainApp extends Application {

	private static Stage primaryStage;
	private static double width;
	private static double height;

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

	public static void showEventEditView() {
		loadScene("/de/eltviller_carneval_verein/karten/ui/EventEditView.fxml");
	}

	private static void loadScene(String fxmlPath) {
		try {
			width = primaryStage.getWidth();
			height = primaryStage.getHeight();
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
