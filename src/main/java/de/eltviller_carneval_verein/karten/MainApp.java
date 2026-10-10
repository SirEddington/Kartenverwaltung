package de.eltviller_carneval_verein.karten;

import java.io.IOException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.HallObject;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.Table;
import de.eltviller_carneval_verein.karten.repository.EventRepositoryListener;
import de.eltviller_carneval_verein.karten.repository.JsonEventRepository;
import de.eltviller_carneval_verein.karten.tracking.ChangeTracker;
import de.eltviller_carneval_verein.karten.ui.ChangeTrigger;
import de.eltviller_carneval_verein.karten.ui.LeaveGuard;
import de.eltviller_carneval_verein.karten.ui.StatusMessage;
import de.eltviller_carneval_verein.karten.ui.event.EventEditController;
import de.eltviller_carneval_verein.karten.ui.hall.HallEditController;
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

	private static final Logger LOG = Logger.getLogger(MainApp.class.getName());

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
		JsonEventRepository repository = JsonEventRepository.getInstance();
		repository.loadEvents();

		// Jedes Speichern und Löschen führt den gespeicherten Stand der Änderungserkennung nach, egal von wo aus
		ChangeTracker tracker = ChangeTracker.getInstance();
		repository.addListener(new EventRepositoryListener() {
			@Override
			public void eventSaved(Event event) {
				tracker.markSaved(event);
			}

			@Override
			public void eventDeleted(Event event) {
				tracker.untrack(event);
			}
		});

		// Schließen der App mit ungespeicherten Änderungen: erst nachfragen
		primaryStage.setOnCloseRequest(e -> {
			if (!LeaveGuard.confirmLeave()) {
				e.consume();
			}
		});

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

	public static void showManagementMenuView() {
		loadScene("/de/eltviller_carneval_verein/karten/ui/ManagementMenuView.fxml");
	}

	public static void showEventOverviewView() {
		loadScene("/de/eltviller_carneval_verein/karten/ui/EventOverviewView.fxml");
	}

	public static void showHallManagementView() {
		loadScene("/de/eltviller_carneval_verein/karten/ui/HallManagementView.fxml");
	}

	public static void showHallEditView(Hall selectedHall, HallObject selectedObject, boolean editable) {
		String fxmlPath = "/de/eltviller_carneval_verein/karten/ui/HallEditView.fxml";
		try {
			if (!LeaveGuard.confirmLeave()) {
				return;
			}
			StatusMessage.getInstance().clear();
			width = primaryStage.getWidth();
			height = primaryStage.getHeight();

			FXMLLoader loader = new FXMLLoader(MainApp.class.getResource(fxmlPath));
			Parent root = loader.load();

			HallEditController controller = loader.getController();
			controller.initData(selectedHall, selectedObject, editable);

			primaryStage.setScene(createScene(root));
		} catch (IOException e) {
			LOG.log(Level.SEVERE, "Ansicht konnte nicht geladen werden: " + fxmlPath, e);
			showAlert("Fehler", "Ansicht konnte nicht geladen werden: " + e.getMessage(), AlertType.ERROR);
		}
	}

	public static void showEventCreateView() {
		loadScene("/de/eltviller_carneval_verein/karten/ui/EventCreateView.fxml");
	}

	public static void showEventEditView(Event selectedEvent, Presentation selectedPres, Table selectedTable, Seat selectedSeat, boolean editable) {
		String fxmlPath = "/de/eltviller_carneval_verein/karten/ui/EventEditView.fxml";
		try {

			// Aktuelle Fenstergröße holen
			if (!LeaveGuard.confirmLeave()) {
				return;
			}
			StatusMessage.getInstance().clear();
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
			primaryStage.setScene(createScene(root));
		} catch (IOException e) {
			LOG.log(Level.SEVERE, "Ansicht konnte nicht geladen werden: " + fxmlPath, e);
			showAlert("Fehler", "Ansicht konnte nicht geladen werden: " + e.getMessage(), AlertType.ERROR);
		}
	}

	private static void loadScene(String fxmlPath) {
		try {
			// Aktuelle Fenstergröße holen
			if (!LeaveGuard.confirmLeave()) {
				return;
			}
			StatusMessage.getInstance().clear();
			width = primaryStage.getWidth();
			height = primaryStage.getHeight();

			// 1. FXMLLoader mit dem Pfad zur FXML instanziieren
			FXMLLoader loader = new FXMLLoader(MainApp.class.getResource(fxmlPath));

			// 2. Layout laden (erzeugt auch die Controller-Instanz)
			Parent root = loader.load();

			// 3. Scene setzen
			primaryStage.setScene(createScene(root));
		} catch (IOException e) {
			LOG.log(Level.SEVERE, "Ansicht konnte nicht geladen werden: " + fxmlPath, e);
			showAlert("Fehler", "Ansicht konnte nicht geladen werden: " + e.getMessage(), AlertType.ERROR);
		}
	}

	/**
	 * Die einzige Stelle, an der die Scenes der App entstehen: Hintergrund, Stylesheet und der Filter, der die
	 * Änderungserkennung auslöst ({@link ChangeTrigger}).
	 */
	private static Scene createScene(Parent content) {
		Scene scene = new Scene(wrapWithBackground(content), width, height);
		String css = MainApp.class.getResource("/de/eltviller_carneval_verein/karten/ui/style.css").toExternalForm();
		scene.getStylesheets().add(css);
		ChangeTrigger.install(scene);
		return scene;
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
		AppLogging.init();
		GlobalErrorHandler.install();
		launch(args);
	}

}
