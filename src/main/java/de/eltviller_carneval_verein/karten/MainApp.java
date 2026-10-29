package de.eltviller_carneval_verein.karten;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.HallObject;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.repository.ConflictPolicies;
import de.eltviller_carneval_verein.karten.repository.StoreEventRepository;
import de.eltviller_carneval_verein.karten.repository.StoreHallRepository;
import de.eltviller_carneval_verein.karten.settings.SettingsStore;
import de.eltviller_carneval_verein.karten.store.SyncService;
import de.eltviller_carneval_verein.karten.tracking.ChangeTracker;
import de.eltviller_carneval_verein.karten.ui.ChangeTrigger;
import de.eltviller_carneval_verein.karten.ui.ConflictActions;
import de.eltviller_carneval_verein.karten.ui.ConflictDialog;
import de.eltviller_carneval_verein.karten.ui.ConflictIndicator;
import de.eltviller_carneval_verein.karten.ui.ConflictLabels;
import de.eltviller_carneval_verein.karten.ui.ConflictList;
import de.eltviller_carneval_verein.karten.ui.ContextMenuGuard;
import de.eltviller_carneval_verein.karten.ui.EditingUnits;
import de.eltviller_carneval_verein.karten.ui.EnterOnButtons;
import de.eltviller_carneval_verein.karten.ui.EventSaver;
import de.eltviller_carneval_verein.karten.ui.HallSaver;
import de.eltviller_carneval_verein.karten.ui.LeaveGuard;
import de.eltviller_carneval_verein.karten.ui.LiveUpdates;
import de.eltviller_carneval_verein.karten.ui.LiveValidator;
import de.eltviller_carneval_verein.karten.ui.PopupWindows;
import de.eltviller_carneval_verein.karten.ui.StatusMessage;
import de.eltviller_carneval_verein.karten.ui.event.EventEditController;
import de.eltviller_carneval_verein.karten.ui.hall.HallEditController;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.stage.Stage;

public class MainApp extends Application {

	private static final Logger LOG = Logger.getLogger(MainApp.class.getName());

	/**
	 * Systemeigenschaft nur für den Handtest mit zwei Instanzen: der gemeinsame Ordner, mit dem verbunden gestartet wird
	 * (leer oder nicht gesetzt: Einzelbetrieb). Entfällt mit KARV-87, das den Ordner als Einstellung einführt.
	 */
	static final String SHARED_DIR_PROPERTY = "karten.sharedDir";

	private static Stage primaryStage;
	// Der Sammel-Dialog für Konflikte (gesetzt, sobald der Bestand geladen ist) und der Hinweis in der Fußzeile des aktuellen Fensterinhalts
	private static SyncService conflictSync;
	private static ConflictList conflictList;
	private static ConflictActions conflictActions;
	private static ConflictIndicator conflictIndicator;
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
			failStart("Kartenverwaltung läuft bereits", e.getMessage());
			return;
		} catch (UncheckedIOException e) {
			LOG.log(Level.SEVERE, "Rechner-Kennung nicht lesbar", e);
			failStart("Start nicht möglich", "Die Rechner-Kennung konnte nicht gelesen werden: " + e.getCause().getMessage());
			return;
		}
		// Die Fachregeln für Konflikte vor dem Start: Schon der Start löst verlorene Änderungen mit ihnen auf
		ConflictPolicies.install(sync);

		// Das Fenster steht sofort da, mit Ladeansicht; Abgleich und Laden laufen im Hintergrund (vorläufig ohne
		// gemeinsamen Ordner, außer im Handtest: karten.sharedDir). Hallen zuerst: Die Events beziehen sich auf sie. Geladen wird einmalig beim Start, damit
		// unlesbare Einheiten sofort sichtbar gemeldet werden, statt beim ersten Öffnen einer Liste lautlos zu fehlen.
		Task<Void> loading = new Task<>() {
			@Override
			protected Void call() {
				String sharedDir = System.getProperty(SHARED_DIR_PROPERTY);
				warnIfLocalDataExists(sharedDir);
				sync.startup(sharedDir, fraction -> updateProgress(fraction, 1.0));
				StoreHallRepository.getInstance().loadHalls();
				// schon vor dem Laden der Events: Beim Laden bleiben Buchungen, die nicht zur Bestuhlung ihrer Vorstellung passen, draußen
				StoreEventRepository.getInstance().setHallLookup(StoreHallRepository.getInstance()::findById);
				StoreEventRepository.getInstance().loadEvents();
				return null;
			}
		};
		loading.setOnSucceeded(e -> onLoaded(settingsStore));
		loading.setOnFailed(e -> {
			Throwable failure = loading.getException();
			LOG.log(Level.SEVERE, "Bestand konnte nicht geladen werden", failure);
			failStart("Start nicht möglich", "Der Bestand konnte nicht geladen werden: " + failureMessage(failure));
		});

		primaryStage.setScene(createScene(loadingView(loading)));
		primaryStage.show();

		Thread loader = new Thread(loading, "start-laden");
		loader.setDaemon(true);
		loader.start();
	}

	/**
	 * Warnt im Log, wenn der Handtest einen gemeinsamen Ordner vorgibt, der lokale Bestand aber nicht leer ist: Die
	 * Ansprüche aus dem Einzelbetrieb würden dann nie in den gemeinsamen Ordner hochgeladen (Befund M4). Der Handtest
	 * gehört auf frische Datenordner.
	 */
	private static void warnIfLocalDataExists(String sharedDir) {
		if (sharedDir == null || sharedDir.isBlank()) {
			return;
		}
		Path data = AppPaths.appDataDir().toPath().resolve("data");
		try (Stream<Path> entries = Files.isDirectory(data) ? Files.list(data) : Stream.empty()) {
			if (entries.findAny().isPresent()) {
				LOG.warning("karten.sharedDir ist gesetzt, aber der lokale Bestand " + data + " ist nicht leer: Ansprüche aus dem Einzelbetrieb werden nie in den gemeinsamen Ordner hochgeladen (Befund M4). Den Handtest mit einem frischen Datenordner starten.");
			}
		} catch (IOException e) {
			LOG.log(Level.FINE, "Lokaler Bestand nicht prüfbar", e);
		}
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
		return failureMessage(failure, shown -> shown.getClass().getSimpleName());
	}

	/**
	 * Die Meldung eines Fehlers für den Nutzer; bei einem {@link UncheckedIOException} die der eigentlichen Ursache.
	 *
	 * @param fallback der Text, wenn die Ursache keine Meldung hat
	 */
	public static String failureMessage(Throwable failure, Function<Throwable, String> fallback) {
		Throwable shown = failure instanceof UncheckedIOException && failure.getCause() != null ? failure.getCause() : failure;
		return shown.getMessage() != null ? shown.getMessage() : fallback.apply(shown);
	}

	/** Läuft auf dem FX-Thread, sobald der Bestand geladen ist: der Rest des Starts und dann das Hauptmenü. */
	private static void onLoaded(SettingsStore settingsStore) {
		try {
			finishStart(settingsStore);
		} catch (RuntimeException e) {
			LOG.log(Level.SEVERE, "Oberfläche konnte nicht geladen werden", e);
			failStart("Start nicht möglich", "Die Oberfläche konnte nicht geladen werden: " + failureMessage(e));
		}
	}

	/** Meldet, dass der Start scheitert, und beendet die Anwendung. */
	private static void failStart(String title, String text) {
		showAlert(title, text, AlertType.ERROR);
		Platform.exit();
	}

	private static void finishStart(SettingsStore settingsStore) {
		StoreHallRepository halls = StoreHallRepository.getInstance();
		StoreEventRepository repository = StoreEventRepository.getInstance();

		// Jedes Speichern und Löschen führt den gespeicherten Stand der Änderungserkennung nach, egal von wo aus
		repository.addListener(ChangeTracker.events().repositoryUpdater());
		halls.addListener(ChangeTracker.halls().repositoryUpdater());
		// Der Sammel-Dialog für Konflikte: Fußzeile und Dialog hängen an denselben Konflikten des Versionsspeichers
		SyncService sync = SyncService.getInstance();
		ConflictActions conflictActions = new ConflictActions(repository, halls, ChangeTracker.events(), ChangeTracker.halls(),
				EditingUnits.getInstance(), sync, EventSaver::save, HallSaver::save);
		MainApp.conflictActions = conflictActions;
		conflictList = new ConflictList(sync::openConflicts, ConflictLabels.of(repository, halls), conflictActions::isAdopted);
		conflictSync = sync;
		// Fremde Änderungen und die Rückmeldungen des Versionsspeichers; nach dem Aktualisierer, damit dessen "gespeichert" zuerst läuft
		LiveUpdates.install(sync, MainApp::conflictsChanged);
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
		// Konflikte, die schon beim Start aufgelöst wurden (der Versionsspeicher meldete sie, bevor jemand zuhörte)
		conflictsChanged();
	}

	/**
	 * Die offenen Konflikte können sich geändert haben (FX-Thread): Die Fußzeile zeigt die Zahl neu; kam ein Konflikt dazu, den
	 * der Nutzer noch nie gesehen hat, öffnet sich der Sammel-Dialog, sonst wird ein offener nur aufgefrischt.
	 */
	private static void conflictsChanged() {
		if (conflictIndicator != null) {
			conflictIndicator.refresh();
		}
		if (conflictList != null && conflictList.hasUnseen()) {
			openConflictDialog();
		} else {
			ConflictDialog.refreshIfOpen();
		}
	}

	/** Zeigt den Sammel-Dialog (oder holt ihn nach vorn): per Klick auf die Fußzeile und bei neuen Konflikten. */
	private static void openConflictDialog() {
		if (conflictList == null) {
			return;
		}
		ConflictDialog.show(primaryStage, conflictList, conflictActions);
		conflictList.markSeen();
	}

	/** Die Zahl der offenen Konflikte für die Fußzeile; vor dem Start des Versionsspeichers keine. */
	private static int openConflictCount() {
		return conflictSync == null ? 0 : conflictSync.openConflicts().size();
	}

	/**
	 * Beim Beenden den Index schreiben und die Marke "sauber beendet" anlegen (Fehler dabei werden nur geloggt), falls der
	 * Start den Dienst überhaupt erzeugt hat.
	 */
	@Override
	public void stop() {
		SyncService.instanceIfCreated().ifPresent(SyncService::closeIndex);
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
	 * Die Scene einer Ansicht des Hauptfensters: Hintergrund, Stylesheet und darunter der Hinweis auf offene Konflikte,
	 * den {@link #conflictsChanged()} auffrischt.
	 */
	public static Scene createScene(Parent content) {
		return createScene(content, true);
	}

	/**
	 * Die Scene eines weiteren Fensters (Kundenfenster): Hintergrund und Stylesheet wie im Hauptfenster, aber ohne den
	 * Hinweis auf offene Konflikte. Der Hinweis gehört nur ins Hauptfenster; Kunden sollen ihn nicht sehen, und der
	 * Hinweis des Hauptfensters bleibt der, den die App auffrischt.
	 */
	public static Scene createSceneWithoutConflictHint(Parent content) {
		return createScene(content, false);
	}

	/** Die einzige Stelle, an der die Scenes der App entstehen. */
	private static Scene createScene(Parent content, boolean conflictHint) {
		Scene scene = new Scene(wrapWithBackground(content, conflictHint), width, height);
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
	 * tatsächlichen Fenstergröße mit. Mit {@code conflictHint} steht unter dem Inhalt der Hinweis auf offene Konflikte,
	 * und er wird der Hinweis, den die App auffrischt.
	 */
	private static Parent wrapWithBackground(Parent content, boolean conflictHint) {
		ImageView background = new ImageView(new Image(MainApp.class.getResourceAsStream("/de/eltviller_carneval_verein/karten/ui/images/wappen.png")));
		background.setPreserveRatio(true);
		background.setOpacity(0.12);
		background.setMouseTransparent(true);

		VBox.setVgrow(content, Priority.ALWAYS);
		VBox screen = new VBox(content);
		if (conflictHint) {
			// unter dem Inhalt die Zeile mit dem Hinweis auf offene Konflikte; ohne Konflikte nimmt sie keinen Platz
			ConflictIndicator indicator = new ConflictIndicator(MainApp::openConflictCount, MainApp::openConflictDialog);
			conflictIndicator = indicator;
			HBox indicatorBar = new HBox(indicator);
			indicatorBar.setAlignment(Pos.CENTER_LEFT);
			indicatorBar.setPadding(new Insets(0, 20, 8, 20));
			indicatorBar.visibleProperty().bind(indicator.visibleProperty());
			indicatorBar.managedProperty().bind(indicator.managedProperty());
			screen.getChildren().add(indicatorBar);
		}

		StackPane wrapper = new StackPane(background, screen);
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
