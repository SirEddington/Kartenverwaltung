package de.eltviller_carneval_verein.karten;

import java.io.IOException;
import java.util.List;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.Table;
import de.eltviller_carneval_verein.karten.repository.JsonTicketRepository;
import de.eltviller_carneval_verein.karten.ui.EventEditController;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Font;
import javafx.stage.Stage;

public class MainApp extends Application {

	private static Stage primaryStage;
	private static double width = 1300.0;
	private static double height = 780.0;

	@SuppressWarnings("exports")
	@Override
	public void start(Stage stage) throws Exception {
		Font.loadFont(MainApp.class.getResourceAsStream("/de/eltviller_carneval_verein/karten/ui/fonts/Yanone Kaffeesatz Bold.otf"), 12);

		primaryStage = stage;
		primaryStage.setWidth(width);
		primaryStage.setHeight(height);
		primaryStage.setMinWidth(width);
		primaryStage.setMinHeight(height);

		stage.setTitle("ECV Kartenverwaltung");
		stage.getIcons().add(new Image(MainApp.class.getResourceAsStream("/de/eltviller_carneval_verein/karten/ui/images/harlekin_logo.png")));

		// Events einmalig beim Start laden, damit unlesbare/doppelte Dateien sofort
		// sichtbar gemeldet werden, statt beim ersten Öffnen einer Liste lautlos zu fehlen.
		JsonTicketRepository repository = JsonTicketRepository.getInstance();
		repository.loadEvents();
		List<String> loadWarnings = repository.getAndClearLoadWarnings();
		if (!loadWarnings.isEmpty()) {
			showAlert("Warnung beim Laden der Events", String.join("\n\n", loadWarnings), AlertType.WARNING);
		}

		// Startet direkt im Hauptmenü
		showMenuView();
		primaryStage.show();
	}

	public static void showMenuView() {
		loadScene("/de/eltviller_carneval_verein/karten/ui/MenuView.fxml");
	}

	public static void showTicketShellView() {
		loadScene("/de/eltviller_carneval_verein/karten/ui/TicketShellView.fxml");
	}

	public static void showAccountingShellView() {
		loadScene("/de/eltviller_carneval_verein/karten/ui/AccountingShellView.fxml");
	}

	public static void showEventOverviewView() {
		loadScene("/de/eltviller_carneval_verein/karten/ui/EventOverviewView.fxml");
	}

	public static void showEventCreateView() {
		loadScene("/de/eltviller_carneval_verein/karten/ui/EventCreateView.fxml");
	}

	public static void showEventEditView(Event selectedEvent, Presentation selectedPres, Table selectedTable, Seat selectedSeat, boolean editable) {
		String fxmlPath = "/de/eltviller_carneval_verein/karten/ui/EventEditView.fxml";
		try {

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
			Scene scene = new Scene(wrapWithBackground(root), width, height);
			String css = MainApp.class.getResource("/de/eltviller_carneval_verein/karten/ui/style.css").toExternalForm();
			scene.getStylesheets().add(css);
			primaryStage.setScene(scene);
		} catch (IOException e) {
			e.printStackTrace();
			showAlert("Fehler", "Ansicht konnte nicht geladen werden: " + e.getMessage(), AlertType.ERROR);
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
			Scene scene = new Scene(wrapWithBackground(root), width, height);
			String css = MainApp.class.getResource("/de/eltviller_carneval_verein/karten/ui/style.css").toExternalForm();
			scene.getStylesheets().add(css);
			primaryStage.setScene(scene);
		} catch (IOException e) {
			e.printStackTrace();
			showAlert("Fehler", "Ansicht konnte nicht geladen werden: " + e.getMessage(), AlertType.ERROR);
		}
	}

	/**
	 * Legt das ECV-Wappen groß und dezent transparent hinter den eigentlichen
	 * Bildschirminhalt, damit es auf jedem Screen einheitlich im Hintergrund
	 * erscheint, statt es in jeder FXML einzeln nachzubauen. Skaliert mit der
	 * tatsächlichen Fenstergröße mit.
	 */
	private static Parent wrapWithBackground(Parent content) {
		ImageView background = new ImageView(new Image(MainApp.class.getResourceAsStream("/de/eltviller_carneval_verein/karten/ui/images/wappen.png")));
		background.setPreserveRatio(true);
		background.setOpacity(0.12);
		background.setMouseTransparent(true);

		StackPane wrapper = new StackPane(background, content);
		background.fitHeightProperty().bind(wrapper.heightProperty().multiply(0.85));
		StackPane.setAlignment(background, Pos.CENTER);
		return wrapper;
	}

	/**
	 * Setzt das App-Icon auf die Dialog-Stage eines Alerts, damit auch
	 * Bestätigungen/Fehlermeldungen das Harlekin-Icon statt des
	 * Standard-Java-Symbols zeigen.
	 */
	@SuppressWarnings("exports")
	public static void applyAppIcon(Alert alert) {
		Stage alertStage = (Stage) alert.getDialogPane().getScene().getWindow();
		alertStage.getIcons().add(new Image(MainApp.class.getResourceAsStream("/de/eltviller_carneval_verein/karten/ui/images/harlekin_logo.png")));
	}

	@SuppressWarnings("exports")
	public static void showAlert(String title, String content, AlertType alertType) {
		Alert alert = new Alert(alertType);
		alert.setTitle(title);
		alert.setHeaderText(null);
		alert.setContentText(content);
		applyAppIcon(alert);
		alert.showAndWait();
	}

	public static void main(String[] args) {
		launch(args);
	}

}
