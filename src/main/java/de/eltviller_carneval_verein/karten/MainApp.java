package de.eltviller_carneval_verein.karten;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.HallObject;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.repository.ConflictPolicies;
import de.eltviller_carneval_verein.karten.repository.ContentRules;
import de.eltviller_carneval_verein.karten.repository.LockedUnitException;
import de.eltviller_carneval_verein.karten.repository.StoreEventRepository;
import de.eltviller_carneval_verein.karten.repository.StoreHallRepository;
import de.eltviller_carneval_verein.karten.settings.AppSettings;
import de.eltviller_carneval_verein.karten.settings.SettingsStore;
import de.eltviller_carneval_verein.karten.store.ConnectMode;
import de.eltviller_carneval_verein.karten.store.OpenConflict;
import de.eltviller_carneval_verein.karten.store.Reachability;
import de.eltviller_carneval_verein.karten.store.StoreMessages;
import de.eltviller_carneval_verein.karten.store.SyncService;
import de.eltviller_carneval_verein.karten.store.SyncState;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.UnitState;
import de.eltviller_carneval_verein.karten.tracking.ChangeTracker;
import de.eltviller_carneval_verein.karten.ui.ChangeTrigger;
import de.eltviller_carneval_verein.karten.ui.ConfirmDialog;
import de.eltviller_carneval_verein.karten.ui.ConflictActions;
import de.eltviller_carneval_verein.karten.ui.ConflictDialog;
import de.eltviller_carneval_verein.karten.ui.ConflictIndicator;
import de.eltviller_carneval_verein.karten.ui.ConflictLabels;
import de.eltviller_carneval_verein.karten.ui.ConflictList;
import de.eltviller_carneval_verein.karten.ui.ContextMenuGuard;
import de.eltviller_carneval_verein.karten.ui.DisplayPreferences;
import de.eltviller_carneval_verein.karten.ui.EditingUnits;
import de.eltviller_carneval_verein.karten.ui.EnterOnButtons;
import de.eltviller_carneval_verein.karten.ui.EventSaver;
import de.eltviller_carneval_verein.karten.ui.HallSaver;
import de.eltviller_carneval_verein.karten.ui.LeaveGuard;
import de.eltviller_carneval_verein.karten.ui.LiveUpdates;
import de.eltviller_carneval_verein.karten.ui.LiveValidator;
import de.eltviller_carneval_verein.karten.ui.PopupWindows;
import de.eltviller_carneval_verein.karten.ui.StatusLabel;
import de.eltviller_carneval_verein.karten.ui.StatusMessage;
import de.eltviller_carneval_verein.karten.ui.event.EventEditController;
import de.eltviller_carneval_verein.karten.ui.hall.HallEditController;
import de.eltviller_carneval_verein.karten.ui.sales.BookingAutoSave;
import de.eltviller_carneval_verein.karten.ui.sales.LockedSeats;
import de.eltviller_carneval_verein.karten.ui.shared.DataReload;
import de.eltviller_carneval_verein.karten.ui.shared.DialogConfirmation;
import de.eltviller_carneval_verein.karten.ui.shared.LockedUnits;
import de.eltviller_carneval_verein.karten.ui.shared.LockedUnitsDialog;
import de.eltviller_carneval_verein.karten.ui.shared.LockedUnitsIndicator;
import de.eltviller_carneval_verein.karten.ui.shared.OperatorDialog;
import de.eltviller_carneval_verein.karten.ui.shared.PatchNotesWindow;
import de.eltviller_carneval_verein.karten.ui.shared.SharedFolderConnection;
import de.eltviller_carneval_verein.karten.ui.shared.SharedFolderEvents;
import de.eltviller_carneval_verein.karten.ui.shared.SharedFolderStart;
import de.eltviller_carneval_verein.karten.ui.shared.SharedFolderStart.StartResult;
import de.eltviller_carneval_verein.karten.ui.shared.SharedFolderTexts;
import de.eltviller_carneval_verein.karten.ui.shared.SharedFolderUi;
import de.eltviller_carneval_verein.karten.ui.shared.SyncIndicator;
import de.eltviller_carneval_verein.karten.ui.shared.SyncInfo;
import de.eltviller_carneval_verein.karten.ui.shared.SyncInfoWindow;
import de.eltviller_carneval_verein.karten.ui.shared.SyncStatus;
import de.eltviller_carneval_verein.karten.ui.shared.UpdateFlow;
import de.eltviller_carneval_verein.karten.ui.shared.UpdateIndicator;
import de.eltviller_carneval_verein.karten.ui.shared.UpdateTexts;
import de.eltviller_carneval_verein.karten.update.AppVersion;
import de.eltviller_carneval_verein.karten.update.AwtDesktopOps;
import de.eltviller_carneval_verein.karten.update.ExecutorScheduler;
import de.eltviller_carneval_verein.karten.update.HttpUpdateSource;
import de.eltviller_carneval_verein.karten.update.Installation;
import de.eltviller_carneval_verein.karten.update.PatchNotes;
import de.eltviller_carneval_verein.karten.update.PatchNotesOnStart;
import de.eltviller_carneval_verein.karten.update.UpdateChecker;
import de.eltviller_carneval_verein.karten.update.UpdateSource;
import de.eltviller_carneval_verein.karten.update.UpdateState;
import de.eltviller_carneval_verein.karten.validation.PresentationValidator;
import de.eltviller_carneval_verein.karten.validation.Severity;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.binding.BooleanExpression;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.concurrent.Task;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import javafx.util.Duration;

public class MainApp extends Application {

	private static final Logger LOG = Logger.getLogger(MainApp.class.getName());

	private static Stage primaryStage;
	// Fremde Änderungen und Rückmeldungen des Versionsspeichers; nach dem Neuladen werden ihre Merklisten geleert
	private static LiveUpdates liveUpdates;
	/**
	 * Der gemeinsame Ordner, der beim Start nicht erreichbar war ({@code null}: erreichbar oder Einzelbetrieb). Das Popup
	 * SYS-005 dazu erscheint nach dem Hauptmenü ({@link #showUnreachableAtStart()}).
	 */
	private static String unreachableAtStart;
	// Popups, Frage im Betrieb und Fußzeile für die Rückmeldungen des Versionsspeichers (SharedFolderEvents)
	private static final SharedFolderUi sharedFolderUi = new SharedFolderUi();
	// Der Sammel-Dialog für Konflikte (gesetzt, sobald der Bestand geladen ist) und der Hinweis in der Fußzeile des aktuellen Fensterinhalts
	private static SyncService conflictSync;
	private static ConflictList conflictList;
	private static ConflictActions conflictActions;
	private static ConflictIndicator conflictIndicator;
	// Der Stand des Abgleichs, den alle Hinweise in den Fußzeilen teilen (er merkt sich den der aktuellen Ansicht)
	private static SyncStatus syncStatus;
	// Schlüssel in den Properties einer Ladeansicht (markLoadingView)
	private static final Object LOADING_VIEW = new Object();
	// Die Bezeichnungen der Einträge für das Info-Fenster (gesetzt, sobald der Bestand geladen ist; bis dahin der Pfad)
	private static Function<UnitKey, String> syncLabels = unit -> null;
	// Die gesperrten Einträge (gesetzt, sobald der Bestand geladen ist) und der Link in der Fußzeile des aktuellen Fensterinhalts
	private static LockedUnits lockedUnits;
	private static LockedUnitsIndicator lockedIndicator;
	// Die Zahl der offenen Konflikte nach der letzten Rückmeldung; jede neue Fußzeile übernimmt sie
	private static int lastOpenCount;

	/** Die Update-Prüfung samt Ablauf (KARV-131); fehlt außerhalb eines Pakets. */
	public record Updates(Installation installation, UpdateSource source, UpdateChecker checker, UpdateFlow flow) {
	}

	// Schlüssel in den Properties der Ansicht "Update wird vorbereitet …" (markBusyView)
	private static final Object BUSY_VIEW = new Object();
	private static final ObjectProperty<UpdateState> updateState = new SimpleObjectProperty<>(new UpdateState.NotChecked());
	private static Updates updates;
	private static UpdateIndicator updateIndicator;
	private static Parent busyView;
	// das Fenster wird geschlossen (der Close-Handler ließ es zu)
	private static boolean closing;
	// gerade ist die Einstellungsansicht zu sehen (Klick auf den Update-Hinweis öffnet sie dann nicht neu)
	private static boolean settingsViewShown;
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
		// Teil-Speichern (z. B. der gemeinsame Ordner beim Verbinden): Ist der Einstellungsbildschirm offen, wird nur der
		// Vergleichsstand nachgeführt, eine noch nicht gespeicherte Änderung daneben bleibt "geändert"
		settingsStore.addPartialListener(saved -> {
			AppSettings current = settingsStore.current();
			if (ChangeTracker.settings().baseline(current) != null) {
				ChangeTracker.settings().rebase(current, ChangeTracker.settings().snapshotOf(saved));
			}
		});
		// KARV-127: Die Prüfung des Cloud-Servers folgt dem gespeicherten Stand, nicht einem noch ungespeicherten Häkchen
		Reachability.setCloudCheck(settingsStore.current().isCheckCloudServer());
		settingsStore.addListener(saved -> Reachability.setCloudCheck(saved.isCheckCloudServer()));
		// KARV-76: Das Menü "Anzeige" übernimmt den Stand beim Start, nicht den beim ersten Öffnen einer Ansicht
		DisplayPreferences.install(settingsStore);

		// Der Versionsspeicher nimmt zuerst die Sperre gegen einen zweiten Start und liest die Kennung des Rechners;
		// scheitert das, geht es ohne Fenster nicht weiter
		SyncService sync;
		installContentRules();
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
		// Der Stand für die Hinweise zum Abgleich in der Fußzeile, schon für die Ladeansicht des Starts
		syncStatus = new SyncStatus(sync, Clock.systemUTC(), MainApp::syncChanged);
		// Der eine 1-s-Takt der App: frischt den Hinweis und das Info-Fenster auf (Fortschritt, 30 s von AUSSTEHEND)
		Timeline syncTicker = new Timeline(new KeyFrame(Duration.seconds(1), event -> syncChanged()));
		syncTicker.setCycleCount(Animation.INDEFINITE);
		syncTicker.play();
		// Das Verbinden mit dem gemeinsamen Ordner (Knopf in den Einstellungen und Start mit neuem Ordner)
		SharedFolderConnection connection = SharedFolderConnection.install(sync, MainApp::reloadAfterConnect);

		// Das Fenster steht sofort da, mit Ladeansicht; Start des Versionsspeichers und Laden laufen im Hintergrund. Den
		// gemeinsamen Ordner gibt die Einstellung vor, verglichen mit der Marke "verbunden" (SharedFolderStart). Ist
		// danach zu verbinden, lädt erst das Ende des ersten Laufs den Bestand: Niemand arbeitet auf einem halb
		// abgeglichenen Bestand.
		String sharedDir = settingsStore.current().getSharedDir();
		Task<StartResult> starting = new Task<>() {
			@Override
			protected StartResult call() {
				StartResult result = SharedFolderStart.run(sync, sharedDir, fraction -> updateProgress(fraction, 1.0));
				if (result.plan().connectDir() == null) {
					loadRepositories();
				}
				return result;
			}
		};
		starting.setOnSucceeded(e -> started(settingsStore, connection, starting.getValue()));
		starting.setOnFailed(e -> loadFailed(starting.getException()));

		primaryStage.setScene(createScene(loadingView(starting)));
		primaryStage.show();
		startInBackground(starting);
	}

	/**
	 * Lädt Hallen und Events (im Ladethread). Hallen zuerst: Die Events beziehen sich auf sie. Geladen wird einmalig beim
	 * Start, damit unlesbare Einheiten sofort sichtbar gemeldet werden, statt beim ersten Öffnen einer Liste lautlos zu
	 * fehlen.
	 */
	private static void loadRepositories() {
		StoreHallRepository.getInstance().loadHalls();
		// schon vor dem Laden der Events: Beim Laden bleiben Buchungen, die nicht zur Bestuhlung ihrer Vorstellung passen, draußen
		StoreEventRepository.getInstance().setHallLookup(StoreHallRepository.getInstance()::findById);
		StoreEventRepository.getInstance().setHallCurrent(StoreHallRepository.getInstance()::isCurrent);
		StoreEventRepository.getInstance().loadEvents();
	}

	private static void startInBackground(Task<?> task) {
		Thread loader = new Thread(task, "start-laden");
		loader.setDaemon(true);
		loader.start();
	}

	private static void loadFailed(Throwable failure) {
		LOG.log(Level.SEVERE, "Bestand konnte nicht geladen werden", failure);
		failStart("Start nicht möglich", "Der Bestand konnte nicht geladen werden: " + failureMessage(failure));
	}

	/**
	 * Der Versionsspeicher ist gestartet (FX-Thread). Ohne zu verbindenden Ordner ist der Bestand schon geladen; sonst
	 * folgt die Ladeansicht des Verbindens, und erst deren Ende lädt den Bestand.
	 */
	private static void started(SettingsStore settingsStore, SharedFolderConnection connection, StartResult result) {
		SharedFolderStart.Plan plan = result.plan();
		if (!result.reachable()) {
			unreachableAtStart = plan.startupDir();
		}
		if (plan.connectDir() == null) {
			onLoaded(settingsStore, () -> {
			});
			return;
		}
		SyncService sync = connection.sync();
		connection.connectAtStart(plan.connectDir(), plan.mode(), new SharedFolderConnection.StartHandler() {
			@Override
			public void finished() {
				// Modus ASK und Ordner nicht erreichbar: Der Start läuft offline weiter, das Verbinden steht aus
				// Nach MERGE stand der Hinweis schon in der Ladeansicht ("Trotzdem öffnen"): kein Popup dazu (Entscheidung 15)
				if (plan.mode() == ConnectMode.ASK && sync.syncState().get() == SyncState.OFFLINE) {
					unreachableAtStart = plan.connectDir();
				}
				loadAfterConnect(settingsStore, MainApp::showConnected);
			}

			@Override
			public void cancelled() {
				loadAfterConnect(settingsStore, MainApp::showSettingsView);
			}
		});
	}

	/** Nach dem Verbinden beim Start: den Bestand laden, den Start abschließen, dann {@code then}. */
	private static void loadAfterConnect(SettingsStore settingsStore, Runnable then) {
		Task<Void> loading = new Task<>() {
			@Override
			protected Void call() {
				loadRepositories();
				return null;
			}
		};
		loading.setOnSucceeded(e -> onLoaded(settingsStore, then));
		loading.setOnFailed(e -> loadFailed(loading.getException()));
		showContent(loadingView(loading));
		startInBackground(loading);
	}

	/** Die Ansicht, die der Start zeigt, bis der Bestand geladen ist. */
	private static Parent loadingView(Task<?> loading) {
		Label label = new Label("Bestand wird geladen …");
		ProgressBar bar = new ProgressBar();
		bar.setPrefWidth(320);
		bar.progressProperty().bind(loading.progressProperty());
		VBox box = new VBox(16, label, bar);
		box.setAlignment(Pos.CENTER);
		return markLoadingView(box);
	}

	/** Kennzeichnet eine Ladeansicht: Ihre Fußzeile bekommt keinen Hinweis zum Abgleich ({@link #wrapWithBackground}). */
	private static Parent markLoadingView(Parent content) {
		content.getProperties().put(LOADING_VIEW, Boolean.TRUE);
		return content;
	}

	private static boolean isLoadingView(Parent content) {
		return Boolean.TRUE.equals(content.getProperties().get(LOADING_VIEW));
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

	/**
	 * Läuft auf dem FX-Thread, sobald der Bestand geladen ist: der Bediener-Dialog, der Rest des Starts, das Hauptmenü und
	 * dann {@code then} (etwa die Einstellungen nach einem abgebrochenen Verbinden). Alle Wege des Starts (ohne Verbinden,
	 * nach dem Verbinden, nach dessen Abbruch) enden hier, der Dialog kommt also genau einmal je Start; "Beenden" und das
	 * Schließen des Dialogs beenden die Anwendung.
	 */
	private static void onLoaded(SettingsStore settingsStore, Runnable then) {
		try {
			if (!askOperator(settingsStore)) {
				Platform.exit();
				return;
			}
			finishStart(settingsStore);
			then.run();
			// KARV-125: nach einem Update einmal „Was ist neu“; vor SYS-005, damit die Warnung obenauf liegt
			PatchNotesOnStart.run(AppVersion.current(), settingsStore.current().getPatchNotesSeen(),
					settingsStore.existedAtLoad(), PatchNotes::bundled,
					releases -> PatchNotesWindow.show(primaryStage, releases),
					version -> settingsStore.saveOnly(s -> s.setPatchNotesSeen(version)));
			showUnreachableAtStart();
		} catch (RuntimeException e) {
			LOG.log(Level.SEVERE, "Oberfläche konnte nicht geladen werden", e);
			failStart("Start nicht möglich", "Die Oberfläche konnte nicht geladen werden: " + failureMessage(e));
		}
	}

	/**
	 * Das Popup SYS-005, wenn der gemeinsame Ordner beim Start nicht erreichbar war: nach dem Hauptmenü (und einem
	 * abgebrochenen Verbinden, das die Einstellungen zeigt), nicht blockierend. Der Versionsspeicher meldet diesen Ausfall nicht
	 * selbst ({@code unreachable} kommt nicht), er gilt dort als gemeldet. Kam der Ordner während des Ladens zurück, ist der
	 * Ausfall überholt: Dann erscheint kein Popup.
	 */
	private static void showUnreachableAtStart() {
		String dir = unreachableAtStart;
		unreachableAtStart = null;
		if (dir != null && SyncService.getInstance().syncState().get() == SyncState.OFFLINE
				&& !sharedFolderUi.unreachableOpen()) {
			sharedFolderUi.showUnreachable(StoreMessages.unreachable(dir));
		}
	}

	/**
	 * Der Abgleich, sein Zustand oder die Liste der übersprungenen Einträge können sich geändert haben (FX-Thread): Der
	 * Hinweis in der Fußzeile der aktuellen Ansicht und ein offenes Info-Fenster lesen den Stand neu. Auch der 1-s-Takt der
	 * App ruft es auf. Die Hinweise älterer Ansichten sind nicht mehr im Fenster und werden nicht gehalten.
	 */
	private static void syncChanged() {
		if (syncStatus != null) {
			syncStatus.refreshTracked();
		}
		SyncInfoWindow.refreshIfOpen();
		// Ob eine eigene Änderung auf eine Sperre wartet, meldet der Versionsspeicher nicht (es wird nichts geschrieben): Der
		// Hinweis auf Konflikte liest die Zahl im Takt neu (die offenen Konflikte nur aus dem gemerkten Stand)
		if (conflictIndicator != null) {
			conflictIndicator.refresh();
		}
		// Die Reparatur einer gesperrten Einheit kann gelungen oder gescheitert sein (Knopf "zurücksetzen" fällt weg bzw. kommt
		// dazu); die Zeilen liest {@code resettableUnits()} ohne Datei-Ein-/Ausgabe
		lockedViewChanged();
	}

	/** Öffnet das Info-Fenster zum Abgleich (Klick auf den Hinweis in der Fußzeile). */
	private static void openSyncInfo() {
		SyncInfoWindow.show(primaryStage, MainApp::syncInfo, SyncService.getInstance()::syncNow);
	}

	/** Was das Info-Fenster zeigt, nach dem Stand von jetzt; die übersprungenen Einträge liest es jedes Mal neu. */
	private static SyncInfo syncInfo() {
		SyncService sync = SyncService.getInstance();
		String cause = SyncInfo.cause(sync.syncState().get(), sync.sharedDir(),
				SharedFolderEvents.instance().map(SharedFolderEvents::lastFailure).orElse(null));
		return SyncInfo.of(sync.sharedDir(), sync.lastSync().get(), cause, sync.connectionComplete().get(),
				sync.fullSyncProgress().get(), sync.skippedUnits(), syncLabels);
	}

	/**
	 * Klick auf den Hinweis bei Vorrang 1: die Frage "Mitnehmen" / "Beiseitelegen" noch einmal stellen. Weiß die App von keiner
	 * wartenden Entscheidung, öffnet es das Info-Fenster, damit der Klick nicht ins Leere geht.
	 */
	private static void askDecisionAgain() {
		Optional<SharedFolderEvents> events = SharedFolderEvents.instance().filter(SharedFolderEvents::decisionPending);
		if (events.isPresent()) {
			events.get().askDecisionAgain();
		} else {
			openSyncInfo();
		}
	}

	/**
	 * Eine Einheit wurde gesperrt gemeldet (FX-Thread): Sie kommt in die Liste der gesperrten Einträge, der Link in der Fußzeile
	 * und ein offener Dialog lesen sie neu.
	 */
	private static void lockedReported(UnitKey unit, UnitState.Status status) {
		if (lockedUnits != null) {
			lockedUnits.add(unit, status);
		}
		lockedViewChanged();
	}

	/**
	 * Die Liste der gesperrten Einträge hat sich nach einer Abfrage oder einem Zurücksetzen geändert (FX-Thread): Anzeige wie
	 * {@link #lockedViewChanged()}; eine Meldung SYS-008 bzw. SYS-009 zu einem Eintrag, der nicht mehr gesperrt ist, verschwindet
	 * aus der Fußzeile.
	 */
	private static void lockedUnitsChanged() {
		lockedViewChanged();
		LockedUnits units = lockedUnits;
		Function<UnitKey, String> labels = syncLabels;
		if (units != null) {
			StatusMessage.getInstance().clearIf(text -> units.isStaleLockMessage(text, labels));
		}
	}

	/**
	 * Die gesperrten Einträge haben sich geändert (FX-Thread): der Link der aktuellen Ansicht, ein offener Dialog und die
	 * Verkaufsansichten (gesperrte Sitze, KARV-111) lesen neu.
	 */
	private static void lockedViewChanged() {
		if (lockedIndicator != null) {
			lockedIndicator.refresh();
		}
		LockedUnitsDialog.refreshIfOpen();
		LockedSeats.changed();
	}

	/** Die Zahl der gesperrten Einträge für den Link in der Fußzeile; vor dem Laden des Bestands keine. */
	private static int lockedCount() {
		return lockedUnits == null ? 0 : lockedUnits.count();
	}

	/**
	 * Klick auf den Link "gesperrte Einträge": Erst fragt der Versionsspeicher im Hintergrund den Zustand jeder Einheit ab
	 * (im gemeinsamen Betrieb stößt das den Reparaturversuch an, im Einzelbetrieb führt es den Stand nach), damit der Dialog den
	 * Knopf "Auf letzten lesbaren Stand zurücksetzen" genau den Zeilen gibt, die der Versionsspeicher jetzt als zurücksetzbar
	 * nennt; dann öffnet der Dialog.
	 */
	private static void openLockedUnits() {
		LockedUnits units = lockedUnits;
		if (units == null) {
			return;
		}
		units.refresh(() -> {
			if (units.count() > 0) {
				LockedUnitsDialog.show(primaryStage, () -> units.rows(syncLabels), units::reset);
			}
		});
	}

	/** Das Einarbeiten zurückgesetzter Einheiten ins Modell, wie bei fremden Änderungen ({@code LiveUpdates.catchUp}). */
	private static void applyReset(Set<UnitKey> units) {
		if (liveUpdates != null) {
			liveUpdates.catchUp(units);
		}
	}

	/**
	 * Fragt beim Start nach dem Bediener, auch im Einzelbetrieb (Pflicht; die zuletzt verwendeten Namen sind Vorschläge), und
	 * übernimmt den Namen.
	 *
	 * @return {@code false}, wenn der Nutzer den Start abgebrochen hat ("Beenden" oder Fenster geschlossen)
	 */
	private static boolean askOperator(SettingsStore settingsStore) {
		Optional<String> name = OperatorDialog.ask(primaryStage, settingsStore.current().getRecentOperators(), true);
		name.ifPresent(chosen -> OperatorDialog.adopt(SyncService.getInstance(), chosen));
		return name.isPresent();
	}

	/**
	 * Richtet die Inhaltsprüfung der Anwendung ein ({@link ContentRules#GATE}); sie muss vor dem ersten
	 * {@link SyncService#getInstance()} geschehen.
	 */
	static void installContentRules() {
		SyncService.installContentGate(ContentRules.GATE);
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
		Function<UnitKey, String> labels = ConflictLabels.of(repository, halls, sync);
		syncLabels = labels;
		// SYS-008 und SYS-009 beim Speichern und Einarbeiten nennen den Eintrag wie Fußzeile und Dialog, nicht seinen Pfad
		LockedUnitException.useLabels(labels);
		// Eine inhaltlich gesperrte Halle friert ihre Vorstellungen ein: Die Prüfung nennt die Sperre statt PRS-007 (KARV-135)
		PresentationValidator.useHallLocks(halls::lockTextOfHall);
		// der Dialog nennt auch die eigenen Änderungen, die auf eine Sperre warten (KARV-135; der Hinweis zählt sie nur)
		conflictList = new ConflictList(sync::openConflicts, labels, conflictActions::isAdopted, halls::findById,
				sync::waitingChanges);
		conflictSync = sync;
		// Fremde Änderungen und die Rückmeldungen des Versionsspeichers; nach dem Aktualisierer, damit dessen "gespeichert" zuerst läuft
		// Die übrigen Rückmeldungen (nicht erreichbar, Rückkehr, Frage beim Verbinden, übersprungen, gesperrt) verteilt SharedFolderEvents
		SharedFolderConnection connection = SharedFolderConnection.instance().orElseThrow();
		// Die gesperrten Einträge (Link in der Fußzeile, Dialog): vor den Rückmeldungen des Versionsspeichers, die sie füllen
		lockedUnits = new LockedUnits(LockedUnits.of(sync), connection.background(), Platform::runLater, MainApp::applyReset,
				(unit, cause) -> StatusMessage.getInstance().show(Severity.ERROR,
						SharedFolderTexts.lockedResetFailed(LockedUnits.label(labels, unit), cause)),
				MainApp::lockedUnitsChanged);
		// Die Verkaufsansicht zeigt einen Sitz, dessen Buchung gesperrt ist und frei wirkt, als gesperrt (KARV-111)
		LockedSeats.install(lockedUnits::units, MainApp::openLockedUnits);
		liveUpdates = LiveUpdates.install(sync, MainApp::conflictsChanged, live -> SharedFolderEvents.install(
				new SharedFolderEvents(live, labels, sharedFolderUi, connection.flow(), MainApp::syncChanged, MainApp::lockedReported)));
		SharedFolderEvents.instance().ifPresent(events -> events.watch(sync));
		ChangeTrigger.install();
		LiveValidator.install();

		// Schließen der App mit ungespeicherten Änderungen: erst nachfragen
		primaryStage.setOnCloseRequest(e -> {
			if (confirmClose()) {
				closing = true;
			} else {
				e.consume();
			}
		});
		startUpdates(settingsStore);

		showLoadWarnings(repository.getAndClearLoadWarnings(), halls.getAndClearLoadWarnings());

		showWarnings("Warnung beim Laden der Einstellungen", settingsStore.getAndClearLoadWarnings());

		showMenuView();
		// Konflikte, die schon beim Start aufgelöst wurden (der Versionsspeicher meldete sie, bevor jemand zuhörte)
		conflictsChanged();
	}

	/**
	 * Wie beim Schließen des Fensters: erst speichern, was in der Verkaufsansicht noch aussteht (sonst fragt der LeaveGuard
	 * bei einer Eingabe der letzten 300 ms), dann nach ungespeicherten Änderungen fragen. Nutzen der Close-Handler und das
	 * Update (KARV-131).
	 *
	 * @return {@code false}, wenn der Nutzer abbricht
	 */
	private static boolean confirmClose() {
		BookingAutoSave.flushActive();
		return LeaveGuard.confirmLeave();
	}

	/**
	 * Richtet die Update-Prüfung ein (KARV-131): außerhalb eines Pakets keine; sonst Prüfung 10 s nach dem Start, alle 4 h
	 * und auf Wunsch, und der Ablauf des Updates. Scheitert der Aufbau, startet die App trotzdem, nur ohne Update-Prüfung.
	 */
	private static void startUpdates(SettingsStore settingsStore) {
		try {
			Optional<Installation> inst = Installation.current();
			LOG.info("Version " + AppVersion.current().map(AppVersion::display).orElse("Entwicklungsversion") + ", Plattform "
					+ inst.map(i -> i.plattform() + ", Paketfamilie " + i.packageFamilyName().orElse("-")).orElse("ohne Update-Prüfung")
					+ ", Datenordner " + AppPaths.appDataDir() + ", java.io.tmpdir " + System.getProperty("java.io.tmpdir")
					+ ", " + appProperties());
			if (inst.isEmpty()) {
				updateState.set(new UpdateState.Unsupported());
				return;
			}
			Installation installation = inst.get();
			HttpUpdateSource source = new HttpUpdateSource(installation, java.time.Duration.ofSeconds(10), 65536,
					Path.of(System.getProperty("java.io.tmpdir")), Clock.systemUTC());
			UpdateChecker checker = new UpdateChecker(installation, source, new ExecutorScheduler(), Platform::runLater,
					Clock.systemUTC());
			UpdateFlow flow = new UpdateFlow(installation,
					new DialogConfirmation(ConfirmDialog::confirm, primaryStage),
					() -> settingsStore.current().getSharedDir() != null, MainApp::confirmClose, MainApp::showBusyView,
					source, new AwtDesktopOps(), Platform::exit, MainApp::showMenuView, MainApp::conflictsChanged,
					checker::checkNow, text -> StatusMessage.getInstance().show(Severity.ERROR, text), MainApp::busyViewShown,
					() -> SharedFolderConnection.instance().map(SharedFolderConnection::connecting).orElse(false),
					Platform::runLater, task -> {
						Thread t = new Thread(task, "update-start");
						t.setDaemon(true);
						t.start();
					});
			checker.setListener(state -> {
				updateState.set(state);
				if (updateIndicator != null) {
					updateIndicator.refresh();
				}
			});
			updates = new Updates(installation, source, checker, flow);
			checker.start();
		} catch (RuntimeException e) {
			LOG.log(Level.SEVERE, "Update-Prüfung ließ sich nicht einrichten", e);
			updates = null;
			updateState.set(new UpdateState.Unsupported());
		}
	}

	private static String appProperties() {
		return System.getProperties().stringPropertyNames().stream().filter(name -> name.startsWith("app.")).sorted()
				.map(name -> name + "=" + System.getProperty(name)).toList().toString();
	}

	/** Die Update-Prüfung, leer außerhalb eines Pakets oder wenn ihr Aufbau scheiterte (KARV-131). */
	public static Optional<Updates> updates() {
		return Optional.ofNullable(updates);
	}

	/**
	 * „Patchnotes anzeigen“ in den Einstellungen (KARV-125): alle Versionen bis zur laufenden (in der Entwicklung alle).
	 * Eine ungültige Datei und eine leere Liste meldet die Fußzeile statt eines Fensters.
	 */
	public static void showPatchNotes() {
		PatchNotes notes;
		try {
			notes = PatchNotes.bundled();
		} catch (RuntimeException e) {
			LOG.log(Level.WARNING, "Patchnotes ließen sich nicht lesen", e);
			StatusMessage.getInstance().show(Severity.ERROR, UpdateTexts.PATCH_NOTES_UNREADABLE);
			return;
		}
		List<PatchNotes.Release> releases = notes.forDisplay(AppVersion.current());
		if (releases.isEmpty()) {
			StatusMessage.getInstance().show(Severity.INFO, UpdateTexts.PATCH_NOTES_NONE);
			return;
		}
		PatchNotesWindow.show(primaryStage, releases);
	}

	/** Der Zustand der Update-Prüfung für Fußzeile und Einstellungen (FX-Thread). */
	@SuppressWarnings("exports")
	public static ReadOnlyObjectProperty<UpdateState> updateStateProperty() {
		return updateState;
	}

	/** Die Ansicht „Update wird vorbereitet …“: unbestimmter Fortschritt, Fußzeile nur mit Meldungen (KARV-131). */
	private static void showBusyView(String text) {
		Label label = new Label(text);
		ProgressBar bar = new ProgressBar();
		bar.setPrefWidth(320);
		Label hint = new Label(UpdateTexts.BUSY_HINT);
		hint.setWrapText(true);
		VBox box = new VBox(16, label, bar, hint);
		box.setAlignment(Pos.CENTER);
		box.setMaxWidth(520);
		VBox centered = new VBox(box);
		centered.setAlignment(Pos.CENTER);
		centered.getProperties().put(BUSY_VIEW, Boolean.TRUE);
		busyView = centered;
		showContent(centered);
	}

	private static boolean isBusyView(Parent content) {
		return Boolean.TRUE.equals(content.getProperties().get(BUSY_VIEW));
	}

	/** Ob die Busy-Ansicht noch die aktuelle ist und das Fenster nicht geschlossen wird. */
	private static boolean busyViewShown() {
		return !closing && busyView != null && busyView.getScene() != null && busyView.getScene() == primaryStage.getScene();
	}

	/** Klick auf den Update-Hinweis ohne direktes Update: die Einstellungen, außer sie sind schon offen. */
	private static void openSettingsFromHint() {
		if (!settingsViewShown) {
			showSettingsView();
		}
	}

	/**
	 * Die Ladewarnungen des Starts, je Repository ein Fenster. Beim Laden gesperrte Einheiten kommen als Schlüssel mit
	 * Zustand: Sie landen in der Liste der gesperrten Einträge (Link in der Fußzeile) und stehen mit ihrer Bezeichnung in der
	 * Warnung ({@link LockedUnits#warning}).
	 */
	private static void showLoadWarnings(List<String> eventLoadWarnings, List<String> hallLoadWarnings) {
		List<String> eventWarnings = new ArrayList<>(eventLoadWarnings);
		eventWarnings.addAll(lockedAtLoad(StoreEventRepository.getInstance().getAndClearLockedAtLoad()));
		showWarnings("Warnung beim Laden der Events", eventWarnings);

		List<String> hallWarnings = new ArrayList<>(hallLoadWarnings);
		hallWarnings.addAll(lockedAtLoad(StoreHallRepository.getInstance().getAndClearLockedAtLoad()));
		showWarnings("Warnung beim Laden der Hallen", hallWarnings);
	}

	/**
	 * Nimmt die beim Laden gesperrten Einheiten in die Liste der gesperrten Einträge auf und liefert ihre Warntexte mit der
	 * lesbaren Bezeichnung; fehlt sie, steht der Pfad da.
	 */
	private static List<String> lockedAtLoad(Map<UnitKey, UnitState.Status> locked) {
		if (lockedUnits != null) {
			lockedUnits.addAll(locked);
		}
		Function<UnitKey, String> labels = syncLabels;
		List<String> texts = new ArrayList<>();
		locked.forEach((unit, status) -> {
			String text = LockedUnits.warning(labels, unit, status);
			if (text != null) {
				texts.add(text);
			}
		});
		return texts;
	}

	private static void showWarnings(String title, List<String> warnings) {
		if (!warnings.isEmpty()) {
			showAlert(title, String.join("\n\n", warnings), AlertType.WARNING);
		}
	}

	/**
	 * Nach dem Verbinden über den Knopf (FX-Thread): Der erste Lauf ist zu Ende oder "Trotzdem öffnen". Ungespeichertes gibt
	 * es nicht mehr (der Ablauf fragte vor dem Verbinden danach, die Ladeansicht lässt nichts bearbeiten). Lädt den Bestand
	 * neu, zeigt die Ladewarnungen und das Hauptmenü; scheitert das Laden, erscheint der Fehler.
	 */
	private static void reloadAfterConnect() {
		DataReload reload = new DataReload(StoreHallRepository.getInstance(), StoreEventRepository.getInstance(),
				() -> {
					if (liveUpdates != null) {
						liveUpdates.reset();
					}
				}, MainApp::conflictsChanged);
		List<String> warnings;
		try {
			warnings = reload.reload();
		} catch (RuntimeException e) {
			LOG.log(Level.SEVERE, "Bestand konnte nach dem Verbinden nicht neu geladen werden", e);
			showMenuView();
			showAlert("Fehler", "Der Bestand konnte nicht geladen werden: " + failureMessage(e), AlertType.ERROR);
			return;
		}
		// vor dem Hauptmenü: Dessen Fußzeile zeigt den Link "gesperrte Einträge" gleich mit der richtigen Zahl
		List<String> all = new ArrayList<>(warnings);
		all.addAll(lockedAtLoad(StoreHallRepository.getInstance().getAndClearLockedAtLoad()));
		all.addAll(lockedAtLoad(StoreEventRepository.getInstance().getAndClearLockedAtLoad()));
		showMenuView();
		// DataReload liefert die Warnungen beider Repositories zusammen (Hallen zuerst), deshalb ein Fenster für alle
		showWarnings("Warnung beim Laden", all);
		// nach den Warnungen: Die Meldung verschwindet nach einigen Sekunden und soll nicht hinter dem Fenster ablaufen
		showConnected();
		// Einträge, die schon vor dem Neuladen gesperrt gemeldet waren und es nicht mehr sind, fallen so aus der Liste
		if (lockedUnits != null) {
			lockedUnits.refresh(null);
		}
	}

	/**
	 * Das Ergebnis des Verbindens in der Fußzeile (FX-Thread, nach dem Hauptmenü): "Verbunden mit …", wenn der Abgleich
	 * läuft. Nach "Trotzdem öffnen" oder bei einem erneuten Ausfall sagt das schon der Hinweis daneben.
	 */
	private static void showConnected() {
		SyncService sync = SyncService.getInstance();
		SyncState state = sync.syncState().get();
		if (state == SyncState.AKTUELL || state == SyncState.AUSSTEHEND) {
			StatusMessage.getInstance().show(Severity.INFO, SharedFolderTexts.connected(sync.sharedDir()));
		}
	}

	/**
	 * Die offenen Konflikte können sich geändert haben (FX-Thread): Die Fußzeile zeigt die Zahl neu; kam ein Konflikt dazu, den
	 * der Nutzer noch nie gesehen hat, öffnet sich der Sammel-Dialog, sonst wird ein offener nur aufgefrischt. Während der
	 * Ladeansicht des Verbindens öffnet er sich nicht von selbst; das Neuladen danach ruft diese Methode noch einmal.
	 */
	private static void conflictsChanged() {
		// einmal je Rückmeldung abgeleitet, für Fußzeile, "neu?" und Dialog
		List<OpenConflict> open;
		try {
			open = conflictSync == null ? List.of() : conflictSync.openConflicts();
		} catch (RuntimeException e) {
			lastOpenCount = 0;
			if (conflictIndicator != null) {
				conflictIndicator.refresh();
			}
			throw e;
		}
		lastOpenCount = open.size();
		if (conflictIndicator != null) {
			conflictIndicator.refresh();
		}
		boolean connecting = SharedFolderConnection.instance().map(SharedFolderConnection::connecting).orElse(false);
		// während eines Updates (KARV-131) nicht von selbst: Die App endet gleich, oder der Ablauf ruft diese Methode danach
		boolean updating = updates != null && updates.flow().isRunning();
		if (!connecting && !updating && conflictList != null && conflictList.hasUnseen(open)) {
			ConflictDialog.show(primaryStage, conflictList, conflictActions, open);
		} else {
			ConflictDialog.refreshIfOpen(open);
		}
	}

	/**
	 * Zeigt den Sammel-Dialog (oder holt ihn nach vorn) per Klick auf die Fußzeile. Was er zeigt, gilt danach als gesehen
	 * (das merkt der Dialog selbst).
	 */
	private static void openConflictDialog() {
		if (conflictList == null) {
			return;
		}
		ConflictDialog.show(primaryStage, conflictList, conflictActions);
	}

	/**
	 * Die Zahl der offenen Konflikte für die Fußzeile nach der letzten Rückmeldung ({@link #conflictsChanged}); vor der
	 * ersten keine. Eine neue Fußzeile (bei jedem Wechsel der Ansicht) leitet so nicht neu ab.
	 */
	private static int openConflictCount() {
		return lastOpenCount;
	}

	/**
	 * Beim Beenden den Index schreiben und die Marke "sauber beendet" anlegen (Fehler dabei werden nur geloggt), falls der
	 * Start den Dienst überhaupt erzeugt hat. Ein laufendes Verbinden zählt danach nicht mehr.
	 */
	@Override
	public void stop() {
		SharedFolderConnection.instance().ifPresent(SharedFolderConnection::close);
		SyncService.instanceIfCreated().ifPresent(SyncService::closeIndex);
		if (updates != null) {
			updates.checker().close();
		}
	}

	/** Das Hauptfenster. */
	@SuppressWarnings("exports")
	public static Stage primaryStage() {
		return primaryStage;
	}

	/**
	 * Zeigt einen Inhalt im Hauptfenster, ohne nach ungespeicherten Änderungen zu fragen: Der Aufrufer hat das getan oder
	 * weiß, dass es nichts zu fragen gibt (etwa die Ladeansicht des Verbindens).
	 */
	public static void showContent(Parent content) {
		settingsViewShown = false;
		StatusMessage.getInstance().clear();
		width = primaryStage.getWidth();
		height = primaryStage.getHeight();
		primaryStage.setScene(createScene(content));
	}

	/**
	 * Zeigt eine Ladeansicht wie {@link #showContent}, aber ohne den Hinweis zum Abgleich in der Fußzeile: Die Ansicht zeigt
	 * Fortschritt und Ursache selbst (etwa die Ladeansicht des Verbindens). Hinweise auf Konflikte und gesperrte Einträge
	 * bleiben.
	 */
	public static void showLoadingView(Parent content) {
		showContent(markLoadingView(content));
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
			settingsViewShown = false;
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
			settingsViewShown = false;
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
			settingsViewShown = fxmlPath.endsWith("/SettingsView.fxml");
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
	 * tatsächlichen Fenstergröße mit. Mit {@code conflictHint} steht der Hinweis auf offene Konflikte in der Fußzeile vor
	 * den Meldungen ({@link StatusLabel}; bei "Anzeige" also zwischen beiden); er wird der Hinweis, den die App auffrischt.
	 * Eine Ansicht ohne eigene Fußzeile (Hauptmenü, Menü der Verwaltung, Übersichten, Ladeansichten) bekommt unter dem
	 * Inhalt eine Fußzeile ohne Trennlinie mit Hinweisen und Meldungen. Ohne beides ist sie leer, hält aber ihre Höhe frei,
	 * damit der Inhalt bei einer Meldung nicht springt.
	 */
	private static Parent wrapWithBackground(Parent content, boolean conflictHint) {
		ImageView background = new ImageView(new Image(MainApp.class.getResourceAsStream("/de/eltviller_carneval_verein/karten/ui/images/wappen.png")));
		background.setPreserveRatio(true);
		background.setOpacity(0.12);
		background.setMouseTransparent(true);

		VBox.setVgrow(content, Priority.ALWAYS);
		VBox screen = new VBox(content);
		if (conflictHint) {
			List<Hyperlink> hints = new ArrayList<>();
			if (isBusyView(content)) {
				// "Update wird vorbereitet …" (KARV-131): keine anklickbaren Hinweise, nur Meldungen; die Felder der Hinweise
				// dürfen auf die vorige Ansicht zeigen, nur der Takt des Abgleichs folgt keinem Hinweis mehr
				if (syncStatus != null) {
					syncStatus.track(null);
				}
			} else {
				// ohne Konflikte nimmt der Hinweis keinen Platz, weder in der Fußzeile noch als eigene Zeile
				ConflictIndicator indicator = new ConflictIndicator(MainApp::openConflictCount,
						() -> SyncService.getInstance().waitingResolutions(), MainApp::openConflictDialog);
				conflictIndicator = indicator;
				// der Hinweis zum Abgleich steht neben dem auf Konflikte, mit derselben Einfügeregel; nicht in einer
				// Ladeansicht, die Fortschritt und Ursache selbst zeigt (sonst stünde die Prozentzahl doppelt da)
				SyncIndicator syncHint = syncStatus == null || isLoadingView(content) ? null
						: new SyncIndicator(syncStatus, MainApp::askDecisionAgain, MainApp::openSyncInfo);
				if (syncStatus != null) {
					// ersetzt den Hinweis der vorigen Ansicht; der 1-s-Takt frischt nur diesen auf
					syncStatus.track(syncHint == null ? null : syncHint::refresh);
				}
				// der Link auf gesperrte Einträge steht gleich hinter dem auf Konflikte; wie dieser ersetzt er den der vorigen
				// Ansicht, und die App frischt nur den der aktuellen auf
				LockedUnitsIndicator lockedHint = new LockedUnitsIndicator(MainApp::lockedCount, MainApp::openLockedUnits);
				lockedIndicator = lockedHint;
				hints.add(indicator);
				hints.add(lockedHint);
				if (syncHint != null) {
					hints.add(syncHint);
				}
				// der Update-Hinweis (KARV-131) steht zuletzt; nicht in Ladeansichten, damit kein Update mitten im Verbinden startet
				if (!isLoadingView(content)) {
					UpdateIndicator updateHint = new UpdateIndicator(updateState::get, () -> updates().map(Updates::installation),
							available -> updates().ifPresent(u -> u.flow().update(available.neu())), MainApp::openSettingsFromHint);
					updateIndicator = updateHint;
					hints.add(updateHint);
				}
			}
			StatusLabel messages = findStatusLabel(content);
			if (messages != null && messages.getParent() instanceof Pane footer) {
				footer.getChildren().addAll(footer.getChildren().indexOf(messages), hints);
			} else {
				// Meldungen gehen damit in diese Fußzeile statt in einen Dialog (StatusMessage)
				List<Node> items = new ArrayList<>(hints);
				items.add(new StatusLabel());
				HBox footer = new HBox(12);
				footer.getChildren().addAll(items);
				footer.setAlignment(Pos.CENTER_LEFT);
				footer.setPadding(new Insets(0, 20, 8, 20));
				// Die Fußzeile hält immer die Höhe einer Meldung frei: Käme sie erst mit der Meldung, rückte der Inhalt (etwa das
				// zentrierte Hauptmenü) bei jeder Meldung hoch und danach wieder herunter. Der Platzhalter ist unsichtbar und
				// ohne Breite, gibt der Zeile aber die Höhe einer Meldung.
				Label icon = new Label("⚠");
				icon.getStyleClass().add("status-icon");
				Label placeholder = new Label(" ", icon);
				placeholder.getStyleClass().add("status-label");
				placeholder.setMinWidth(0);
				placeholder.setPrefWidth(0);
				placeholder.setMaxWidth(0);
				placeholder.setVisible(false);
				footer.getChildren().add(placeholder);
				// zu sehen ist sie nur, solange ein Link oder eine Meldung zu sehen ist
				BooleanExpression anyVisible = items.get(0).visibleProperty();
				for (Node item : items.subList(1, items.size())) {
					anyVisible = anyVisible.or(item.visibleProperty());
				}
				footer.visibleProperty().bind(anyVisible);
				screen.getChildren().add(footer);
			}
		}

		StackPane wrapper = new StackPane(background, screen);
		background.fitHeightProperty().bind(wrapper.heightProperty().multiply(0.85));
		StackPane.setAlignment(background, Pos.CENTER);
		return wrapper;
	}

	/** Die Meldungen der Fußzeile im Inhalt; {@code null}, wenn die Ansicht keine Fußzeile mit Meldungen hat. */
	private static StatusLabel findStatusLabel(Parent parent) {
		for (Node child : parent.getChildrenUnmodifiable()) {
			if (child instanceof StatusLabel label) {
				return label;
			}
			if (child instanceof Parent nested) {
				StatusLabel found = findStatusLabel(nested);
				if (found != null) {
					return found;
				}
			}
		}
		return null;
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
		// launch kehrt erst nach stop() zurück; ein übrig gebliebener Nicht-Daemon-Thread (etwa "AWT-Shutdown" nach
		// Desktop.open beim Update, KARV-131) soll die JVM nicht am Leben halten
		System.exit(0);
	}

}
