package de.eltviller_carneval_verein.karten;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.HallObject;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.repository.RepositoryListener;
import de.eltviller_carneval_verein.karten.repository.StoreEventRepository;
import de.eltviller_carneval_verein.karten.repository.StoreHallRepository;
import de.eltviller_carneval_verein.karten.settings.SettingsStore;
import de.eltviller_carneval_verein.karten.store.SyncService;
import de.eltviller_carneval_verein.karten.tracking.ChangeTracker;
import de.eltviller_carneval_verein.karten.ui.ChangeTrigger;
import de.eltviller_carneval_verein.karten.ui.ContextMenuGuard;
import de.eltviller_carneval_verein.karten.ui.EnterOnButtons;
import de.eltviller_carneval_verein.karten.ui.LeaveGuard;
import de.eltviller_carneval_verein.karten.ui.LiveValidator;
import de.eltviller_carneval_verein.karten.ui.PopupWindows;
import de.eltviller_carneval_verein.karten.ui.StatusMessage;
import de.eltviller_carneval_verein.karten.ui.event.EventEditController;
import de.eltviller_carneval_verein.karten.ui.hall.HallEditController;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.stage.Stage;

public class MainApp extends Application {

	private static final Logger LOG = Logger.getLogger(MainApp.class.getName());

	private static Stage primaryStage;
	// Mindestbreite des Hauptfensters: So schmal darf es werden, die Kopfzeilen der Bildschirme sind darauf abgestimmt
	private static final double MIN_WIDTH = 1250.0;
	private static double width = 1300.0;
	private static double height = 780.0;

	@SuppressWarnings("exports")
	@Override
	public void start(Stage stage) throws Exception {
		Font.loadFont(MainApp.class.getResourceAsStream("/de/eltviller_carneval_verein/karten/ui/fonts/YanoneKaffeesatzBold.otf"), 12);
		Font.loadFont(MainApp.class.getResourceAsStream("/de/eltviller_carneval_verein/karten/ui/fonts/YanoneKaffeesatzRegular.otf"), 12);

		primaryStage = stage;
		// Jedes Kontextmenü schließt, wenn die Maus weit davon wegbewegt wird
		ContextMenuGuard.install();
		// Popups gehören zum Fenster, nicht zur Scene: Sonst bliebe z. B. ein offener Sitz-Dialog über der nächsten Ansicht stehen
		stage.sceneProperty().addListener((obs, oldScene, newScene) -> PopupWindows.hideOwnedBy(stage));
		primaryStage.setWidth(width);
		primaryStage.setHeight(height);
		primaryStage.setMinWidth(MIN_WIDTH);
		primaryStage.setMinHeight(height);

		stage.setTitle("ECV Kartenverwaltung");
		stage.getIcons().add(new Image(MainApp.class.getResourceAsStream("/de/eltviller_carneval_verein/karten/ui/images/harlekin_logo.png")));

		// Die Einstellungen zuerst: Alles Weitere kann sich nach ihnen richten. Jedes Speichern führt den gespeicherten Stand der Änderungserkennung nach.
		SettingsStore settingsStore = SettingsStore.getInstance();
		settingsStore.addListener(ChangeTracker.settings()::markSaved);

		// Der Versionsspeicher nimmt zuerst die Sperre gegen einen zweiten Start und liest die Kennung des Rechners;
		// scheitert das, geht es ohne Fenster nicht weiter
		SyncService sync;
		try {
			sync = SyncService.getInstance();
		} catch (IllegalStateException e) {
			showAlert("Kartenverwaltung läuft bereits", e.getMessage(), AlertType.ERROR);
			Platform.exit();
			return;
		} catch (UncheckedIOException e) {
			LOG.log(Level.SEVERE, "Rechner-Kennung nicht lesbar", e);
			showAlert("Start nicht möglich", "Die Rechner-Kennung konnte nicht gelesen werden: " + e.getCause().getMessage(), AlertType.ERROR);
			Platform.exit();
			return;
		}

		// Das Fenster steht sofort da, mit Ladeansicht; Abgleich und Laden laufen im Hintergrund (vorläufig ohne
		// gemeinsamen Ordner). Hallen zuerst: Die Events beziehen sich auf sie. Geladen wird einmalig beim Start, damit
		// unlesbare Einheiten sofort sichtbar gemeldet werden, statt beim ersten Öffnen einer Liste lautlos zu fehlen.
		Task<Void> loading = new Task<>() {
			@Override
			protected Void call() {
				sync.startup(null, fraction -> updateProgress(fraction, 1.0));
				StoreHallRepository.getInstance().loadHalls();
				StoreEventRepository.getInstance().loadEvents();
				return null;
			}
		};
		loading.setOnSucceeded(e -> onLoaded(settingsStore));
		loading.setOnFailed(e -> {
			Throwable failure = loading.getException();
			LOG.log(Level.SEVERE, "Bestand konnte nicht geladen werden", failure);
			showAlert("Start nicht möglich", "Der Bestand konnte nicht geladen werden: " + failureMessage(failure), AlertType.ERROR);
			Platform.exit();
		});

		primaryStage.setScene(createScene(loadingView(loading)));
		primaryStage.show();

		Thread loader = new Thread(loading, "start-laden");
		loader.setDaemon(true);
		loader.start();
	}

	/** Die Ansicht, die der Start zeigt, bis der Bestand geladen ist. */
	private static Parent loadingView(Task<Void> loading) {
		Label label = new Label("Bestand wird geladen …");
		ProgressBar bar = new ProgressBar();
		bar.setPrefWidth(320);
		bar.progressProperty().bind(loading.progressProperty());
		VBox box = new VBox(16, label, bar);
		box.setAlignment(Pos.CENTER);
		return box;
	}

	/** Die Meldung eines gescheiterten Ladens; bei einem {@link UncheckedIOException} die der eigentlichen Ursache. */
	private static String failureMessage(Throwable failure) {
		Throwable shown = failure instanceof UncheckedIOException && failure.getCause() != null ? failure.getCause() : failure;
		return shown.getMessage() != null ? shown.getMessage() : shown.getClass().getSimpleName();
	}

	/** Läuft auf dem FX-Thread, sobald der Bestand geladen ist: der Rest des Starts und dann das Hauptmenü. */
	private static void onLoaded(SettingsStore settingsStore) {
		StoreHallRepository halls = StoreHallRepository.getInstance();
		StoreEventRepository repository = StoreEventRepository.getInstance();

		// Jedes Speichern und Löschen führt den gespeicherten Stand der Änderungserkennung nach, egal von wo aus
		repository.addListener(trackerUpdater(ChangeTracker.events()));
		halls.addListener(trackerUpdater(ChangeTracker.halls()));
		ChangeTrigger.install();
		LiveValidator.install();

		// Schließen der App mit ungespeicherten Änderungen: erst nachfragen
		primaryStage.setOnCloseRequest(e -> {
			if (!LeaveGuard.confirmLeave()) {
				e.consume();
			}
		});

		List<String> eventWarnings = repository.getAndClearLoadWarnings();
		if (!eventWarnings.isEmpty()) {
			showAlert("Warnung beim Laden der Events", String.join("\n\n", eventWarnings), AlertType.WARNING);
		}

		List<String> hallWarnings = halls.getAndClearLoadWarnings();
		if (!hallWarnings.isEmpty()) {
			showAlert("Warnung beim Laden der Hallen", String.join("\n\n", hallWarnings), AlertType.WARNING);
		}

		List<String> settingsWarnings = settingsStore.getAndClearLoadWarnings();
		if (!settingsWarnings.isEmpty()) {
			showAlert("Warnung beim Laden der Einstellungen", String.join("\n\n", settingsWarnings), AlertType.WARNING);
		}

		showMenuView();
	}

	/**
	 * Beim Beenden den Index schreiben und die Marke "sauber beendet" anlegen (Fehler dabei werden nur geloggt), falls der
	 * Start den Dienst überhaupt erzeugt hat.
	 */
	@Override
	public void stop() {
		SyncService.instanceIfCreated().ifPresent(SyncService::closeIndex);
	}

	private static <T> RepositoryListener<T> trackerUpdater(ChangeTracker<T> tracker) {
		return new RepositoryListener<>() {
			@Override
			public void saved(T entity) {
				tracker.markSaved(entity);
			}

			@Override
			public void deleted(T entity) {
				tracker.untrack(entity);
			}
		};
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

	public static void showSettingsView() {
		loadScene("/de/eltviller_carneval_verein/karten/ui/SettingsView.fxml");
	}

	public static void showEventOverviewView() {
		loadScene("/de/eltviller_carneval_verein/karten/ui/EventOverviewView.fxml");
	}

	public static void showHallManagementView() {
		loadScene("/de/eltviller_carneval_verein/karten/ui/HallManagementView.fxml");
	}

	public static void showHallEditView(Hall selectedHall, HallObject selectedObject, boolean editable) {
		openHallEditView(selectedHall, selectedObject, null, editable, true);
	}

	/** Wie {@link #showHallEditView(Hall, HallObject, boolean)}, mit einer Bestuhlung, die im Editor gleich gewählt ist. */
	public static void showHallEditView(Hall selectedHall, HallObject selectedObject, SeatingPlan selectedPlan, boolean editable) {
		openHallEditView(selectedHall, selectedObject, selectedPlan, editable, true);
	}

	/**
	 * Öffnet den Hallen-Editor, ohne wegen ungespeicherter Änderungen nachzufragen, und nimmt sie mit: Eine Halle, ein
	 * Hallenobjekt oder eine Bestuhlung, die gerade in der Übersicht hinzugefügt wurde, ist noch nicht gespeichert und
	 * soll im Editor mit "Speichern *" erscheinen. Die Änderungserkennung bleibt dabei bestehen (kein Zurücksetzen).
	 */
	public static void showHallEditViewWithChanges(Hall selectedHall, HallObject selectedObject, SeatingPlan selectedPlan, boolean editable) {
		openHallEditView(selectedHall, selectedObject, selectedPlan, editable, false);
	}

	private static void openHallEditView(Hall selectedHall, HallObject selectedObject, SeatingPlan selectedPlan, boolean editable, boolean askToLeave) {
		String fxmlPath = "/de/eltviller_carneval_verein/karten/ui/HallEditView.fxml";
		try {
			if (askToLeave && !LeaveGuard.confirmLeave()) {
				return;
			}
			StatusMessage.getInstance().clear();
			width = primaryStage.getWidth();
			height = primaryStage.getHeight();

			FXMLLoader loader = new FXMLLoader(MainApp.class.getResource(fxmlPath));
			Parent root = loader.load();

			HallEditController controller = loader.getController();
			controller.initData(selectedHall, selectedObject, selectedPlan, editable);

			primaryStage.setScene(createScene(root));
		} catch (IOException e) {
			LOG.log(Level.SEVERE, "Ansicht konnte nicht geladen werden: " + fxmlPath, e);
			showAlert("Fehler", "Ansicht konnte nicht geladen werden: " + e.getMessage(), AlertType.ERROR);
		}
	}

	public static void showEventCreateView() {
		loadScene("/de/eltviller_carneval_verein/karten/ui/EventCreateView.fxml");
	}

	public static void showEventEditView(Event selectedEvent, Presentation selectedPres, boolean editable) {
		openEventEditView(selectedEvent, selectedPres, editable, true);
	}

	/**
	 * Öffnet das Event-Detail, ohne wegen ungespeicherter Änderungen nachzufragen, und nimmt sie mit: Ein Event, das
	 * gerade in der Übersicht hinzugefügt oder als Vorlage angelegt wurde, ist noch nicht gespeichert und soll im Detail
	 * mit "Speichern *" erscheinen. Die Änderungserkennung bleibt dabei bestehen (kein Zurücksetzen).
	 */
	public static void showEventEditViewWithChanges(Event selectedEvent, Presentation selectedPres, boolean editable) {
		openEventEditView(selectedEvent, selectedPres, editable, false);
	}

	private static void openEventEditView(Event selectedEvent, Presentation selectedPres, boolean editable, boolean askToLeave) {
		String fxmlPath = "/de/eltviller_carneval_verein/karten/ui/EventEditView.fxml";
		try {

			// Aktuelle Fenstergröße holen
			if (askToLeave && !LeaveGuard.confirmLeave()) {
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
			controller.initData(selectedEvent, selectedPres, editable);

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
	 * Die einzige Stelle, an der die Scenes der App entstehen: Hintergrund und Stylesheet.
	 */
	public static Scene createScene(Parent content) {
		Scene scene = new Scene(wrapWithBackground(content), width, height);
		scene.getStylesheets().add(stylesheetUrl());
		EnterOnButtons.install(scene);
		return scene;
	}

	private static String stylesheetUrl() {
		return MainApp.class.getResource("/de/eltviller_carneval_verein/karten/ui/style.css").toExternalForm();
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
	 * Gibt einem Alert das Aussehen der App: das App-Icon auf der Dialog-Stage (statt des
	 * Standard-Java-Symbols) und das Stylesheet der App, damit Dialoge nicht im
	 * Standard-Theme erscheinen. Der Alert muss schon alle Buttons haben, bevor der
	 * Aufrufer ihnen Stilklassen gibt.
	 */
	@SuppressWarnings("exports")
	public static void applyAppIcon(Alert alert) {
		alert.getDialogPane().getStylesheets().add(stylesheetUrl());
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
