package de.eltviller_carneval_verein.karten;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class MainApp extends Application {

	@Override
	public void start(Stage primaryStage) throws Exception {
		FXMLLoader loader = new FXMLLoader(getClass().getResource("/de/eltviller_carneval_verein/karten/ui/MainView.fxml"));
		Parent root = loader.load();

		primaryStage.setTitle("ECV Kartenverwaltung");
		primaryStage.setScene(new Scene(root, 1200, 675));
		primaryStage.show();
	}

	public static void main(String[] args) {
		launch(args);
	}

}
