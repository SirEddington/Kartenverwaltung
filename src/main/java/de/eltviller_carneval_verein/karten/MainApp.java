package de.eltviller_carneval_verein.karten;

import java.io.IOException;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class MainApp extends Application {

	private static Stage primaryStage;
	private static int width;
	private static int height;

	@Override
	public void start(Stage stage) throws Exception {
		primaryStage = stage;
		width = 1000;
		height = 600;

		stage.setTitle("ECV Kartenverwaltung");

		// Startet direkt im Hauptmenü
		showMenuView();
		primaryStage.show();
	}

	public static void showMenuView() {
		loadScene("/de/eltviller_carneval_verein/karten/ui/MenuView.fxml", width, height);
	}

	public static void showTableView() {
		loadScene("/de/eltviller_carneval_verein/karten/ui/MainView.fxml", width, height);
	}
	
	public static void showEventOverviewView() {
	    loadScene("/de/eltviller_carneval_verein/karten/ui/EventOverviewView.fxml", width, height);
	}

	public static void showEventCreateView() {
	    loadScene("/de/eltviller_carneval_verein/karten/ui/EventCreateView.fxml", width, height);
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
