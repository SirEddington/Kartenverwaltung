package de.eltviller_carneval_verein.karten.ui.sales;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import de.eltviller_carneval_verein.karten.MainApp;
import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.repository.RepositoryListener;
import de.eltviller_carneval_verein.karten.repository.StoreEventRepository;
import de.eltviller_carneval_verein.karten.repository.StoreHallRepository;
import de.eltviller_carneval_verein.karten.tracking.ChangeTracker;
import de.eltviller_carneval_verein.karten.ui.BookingSyncService;
import de.eltviller_carneval_verein.karten.ui.ContentController;
import de.eltviller_carneval_verein.karten.ui.DirtyIndicator;
import de.eltviller_carneval_verein.karten.ui.DisplayPreferences;
import de.eltviller_carneval_verein.karten.ui.EventSaver;
import de.eltviller_carneval_verein.karten.ui.EventSelectionSync;
import de.eltviller_carneval_verein.karten.ui.LeaveGuard;
import de.eltviller_carneval_verein.karten.ui.LeaveGuard.Outcome;
import de.eltviller_carneval_verein.karten.ui.LiveViews;
import de.eltviller_carneval_verein.karten.ui.LiveViews.Registration;
import de.eltviller_carneval_verein.karten.ui.PopupWindows;
import de.eltviller_carneval_verein.karten.ui.SaveShortcut;
import de.eltviller_carneval_verein.karten.ui.StatusMessage;
import de.eltviller_carneval_verein.karten.ui.hall.PlanLabelsMenu;
import de.eltviller_carneval_verein.karten.validation.EntityLabels;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.RadioButton;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.StackPane;
import javafx.util.StringConverter;

public class TicketShellController {

	private static final Logger LOG = Logger.getLogger(TicketShellController.class.getName());
	private static final String ALL_PRESENTATIONS = "Alle Vorstellungen";

	private final StoreEventRepository eventRepository = StoreEventRepository.getInstance();
	private final StoreHallRepository hallRepository = StoreHallRepository.getInstance();
	// Gemeinsamer Zustand mit dem Kundenfenster: Die Shell und ihre Inhalte schreiben ihn, das Kundenfenster liest ihn.
	// Das Menü "Anzeige" teilt er mit dem Hallen-Editor (KARV-76).
	private final SalesViewState state = new SalesViewState(DisplayPreferences.instance());

	@FXML
	private ComboBox<Event> eventComboBox;
	@FXML
	private ComboBox<Presentation> presComboBox;
	@FXML
	private Label lblFreeSeats;
	@FXML
	private TextField searchField;
	@FXML
	private Label lblSearchHits;
	@FXML
	private ToggleGroup viewToggleGroup;
	@FXML
	private RadioButton btnTableView;
	@FXML
	private RadioButton btnHallView;
	@FXML
	private Button btnToggleEdit;
	@FXML
	private Button btnSave;
	@FXML
	private MenuButton menuDisplay;
	@FXML
	private CheckBox chkSalesMode;
	@FXML
	private StackPane contentArea;
	@FXML
	private Label lblHeader;

	// Wird gerade die Vorstellungsliste neu gefüllt, soll die Auswahl nicht an den Inhalt gemeldet werden
	private boolean updatingPresentations = false;
	// Führt die Boxen nach Änderungen anderer Rechner nach; entsteht in initialize, wenn die Boxen da sind
	private EventSelectionSync selectionSync;
	// Fremde Änderungen an Events: Die Liste und die Auswahl folgen dem Cache, danach die freien Karten
	private final RepositoryListener<Event> foreignChanges = LiveViews.onFxThread(LiveViews.ALL_UNITS, () -> {
		selectionSync.sync();
		refreshFreeSeats();
	});
	// "frei: n von m" (KARV-215) folgt außerdem eigenen Änderungen am gezeigten Event. Der Tracker meldet sie nach dem
	// Vergleich des ChangeTrigger; wird vorher gespeichert (Strg+S aus einer offenen Zelle), meldet er nichts mehr, dann
	// meldet das Repository das Speichern.
	private final ChangeTracker.Listener<Event> ownChanges = (changed, before, after) -> refreshFreeSeatsOf(changed);
	private final RepositoryListener<Event> ownSaves = new RepositoryListener<>() {
		@Override
		public void saved(Event saved) {
			refreshFreeSeatsOf(saved);
		}
	};
	// Eine fremd geänderte Bestuhlung gleicht LiveUpdates still mit den Buchungen ab: Weder Tracker noch Event-Repository
	// melden das. Der gebündelte Lauf kommt nach dem Abgleich.
	private final RepositoryListener<Hall> foreignHallChanges = LiveViews.onFxThread(LiveViews.ALL_UNITS, this::refreshFreeSeats);
	private final Tooltip freeSeatsTooltip = new Tooltip();
	private String searchText = "";
	// Auswahl der verlassenen Ansicht, bis die neue sie zurückbekommt (restorePendingSelection)
	private List<Booking> pendingSelection = List.of();
	private ContentController activeContentController;
	private boolean editMode = false;
	// Speichert Buchungsänderungen sofort (Einstellung "Buchungen sofort speichern"), solange die Shell zu sehen ist und der Bearbeitungsmodus an
	private final BookingAutoSave autoSave = new BookingAutoSave(ChangeTracker.events(), EventSaver::save, Platform::runLater,
			() -> editMode && BookingAutoSave.settingOn());
	private CustomerDisplay customerDisplay;
	// Anzahl und Preis der Auswahl am Mauszeiger, über Tabelle und Saalansicht (KARV-194)
	private SelectionHint selectionHint;
	private final ChangeListener<Boolean> windowShowing = (obs, wasShowing, isShowing) -> {
		if (!isShowing) {
			closeCustomerDisplay();
		}
	};

	@FXML
	public void initialize() {
		DirtyIndicator.bind(btnSave, ChangeTracker.events());
		SaveShortcut.install(btnSave);
		btnSave.setDisable(!editMode); // Im Anzeigemodus gibt es nichts zu speichern
		// Immer aktiv, auch in der Tabelle: Die Einstellungen gelten für die Saalansicht und lassen sich vorher treffen
		PlanLabelsMenu.bind(menuDisplay, state.planLabels());
		// Sitzstatus nicht nur über die Farbe (KARV-26); nur hier, der Hallen-Editor zeigt keinen Status
		menuDisplay.getItems().addAll(new SeparatorMenuItem(), PlanLabelsMenu.item("Barrierefreie Sitzdarstellung", state.seatPatternsProperty()),
				SelectionHintMenu.create(state.selectionHintProperty(), state.selectionHintPlacementProperty(),
						state.selectionHintAvoidEdgesProperty()));
		SummaryPlacementMenu.append(menuDisplay, state.summaryPlacementProperty());
		// Gehört zur Kartentabelle, steht aber wie die übrigen Einstellungen im Menü "Anzeige" (KARV-193)
		menuDisplay.getItems().addAll(new SeparatorMenuItem(),
				PlanLabelsMenu.item("Gruppen verschachteln", DisplayPreferences.instance().ticketGroupingNestedProperty()));
		// Vor der ersten Ansicht (showTicketTableView unten): loadContentView legt den Hinweis über jede Ansicht
		selectionHint = new SelectionHint(contentArea, state.selectedBookings(), state.selectionHintProperty(),
				state.selectionHintPlacementProperty(), state.selectionHintAvoidEdgesProperty());

		// Das Kundenfenster verschwindet mit der Ansicht: Wird die Szene ersetzt (Menü, anderer Bildschirm) oder das
		// Hauptfenster geschlossen, schließt es mit (sonst hielte es die App am Leben). Die Szene bleibt an contentArea
		// hängen, auch wenn das Fenster eine andere zeigt: Deshalb wird ihr Fenster beobachtet, nicht contentArea.
		contentArea.sceneProperty().addListener((obs, oldScene, newScene) -> {
			if (newScene != null) {
				newScene.windowProperty().addListener((o, oldWindow, newWindow) -> {
					if (oldWindow != null) {
						oldWindow.showingProperty().removeListener(windowShowing);
					}
					if (newWindow == null) {
						closeCustomerDisplay();
					} else {
						newWindow.showingProperty().addListener(windowShowing);
					}
				});
			}
		});

		selectionSync = new EventSelectionSync(eventRepository, eventComboBox, presComboBox, event -> !event.isArchived(), () -> state.eventProperty().get(),
				() -> state.presentationProperty().get(), this::applyEventSelection, presentation -> {
					state.presentationProperty().set(presentation);
					if (activeContentController != null) {
						activeContentController.setPresentation(presentation);
					}
				});

		// Fremde Änderungen an Events nur, solange die Shell zu sehen ist (danach hielte das Repository sie am Leben)
		// Ebenso das sofortige Speichern: nach dem Verlassen der Ansicht hört es nicht mehr am Tracker zu
		LiveViews.whileShown(contentArea, Registration.of(eventRepository::addListener, eventRepository::removeListener, foreignChanges),
				new Registration(() -> autoSave.setActive(true), () -> autoSave.setActive(false)),
				new Registration(() -> ChangeTracker.events().addListener(ownChanges), () -> ChangeTracker.events().removeListener(ownChanges)),
				Registration.of(eventRepository::addListener, eventRepository::removeListener, ownSaves),
				Registration.of(hallRepository::addListener, hallRepository::removeListener, foreignHallChanges),
				LockedSeats.whileShown(this::refreshFreeSeats));

		// "Alle Vorstellungen" ist als echter (null-)Eintrag Teil der Liste, wie in der Abrechnung: So lässt sich die
		// Auswahl einer Vorstellung wieder aufheben.
		presComboBox.setConverter(new StringConverter<Presentation>() {
			@Override
			public String toString(Presentation presentation) {
				return presentation == null ? ALL_PRESENTATIONS : EntityLabels.nameOrPlaceholder(presentation.getName());
			}

			@Override
			public Presentation fromString(String string) {
				return null; // Bei fixer ComboBox-Auswahl nicht erforderlich
			}
		});

		// Events in ComboBox laden (Tabelle bleibt initial leer)
		eventComboBox.getItems().setAll(eventRepository.loadEvents().stream().filter(event -> !event.isArchived()).toList());

		// Event- & Vorstellungs-Listener einrichten
		eventComboBox.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, selectedEvent) -> {
			if (!selectionSync.updatingEvents()) {
				applyEventSelection(selectedEvent);
			}
		});

		presComboBox.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, selectedPresentation) -> {
			if (updatingPresentations || selectionSync.updatingPresentations()) {
				return;
			}
			state.presentationProperty().set(selectedPresentation);
			if (activeContentController != null) {
				activeContentController.setPresentation(selectedPresentation);
			}
		});

		// Freitext-Suche auf den geladenen Event-Daten
		searchField.textProperty().addListener((obs, oldVal, newValue) -> {
			searchText = (newValue == null) ? "" : newValue.toLowerCase().trim();
			activeContentController.filter(searchText);
		});

		bindSearchHits();
		bindFreeSeats();

		viewToggleGroup.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
			if (newToggle == btnTableView) {
				showTicketTableView();
			} else {
				showHallView();
			}
		});

		// Standardsicht laden
		showTicketTableView();

		if (eventComboBox.getItems().size() == 1) {
			eventComboBox.getSelectionModel().select(0);
		}

		// Hat das einzige Event nur eine Vorstellung, ist sie gleich gewählt (Eintrag 0 ist "Alle Vorstellungen")
		if (presComboBox.getItems().size() == 2) {
			presComboBox.getSelectionModel().select(1);
		}
	}

	/**
	 * "n Treffer" über dem Suchfeld (KARV-192): zählt, was die aktive Ansicht als Treffer zeigt. Der Tooltip wird mit
	 * jeder neuen Trefferliste neu gebaut und zusätzlich beim Anzeigen (Namen können sich ohne neue Trefferliste ändern).
	 */
	private void bindSearchHits() {
		Tooltip tooltip = new Tooltip(SearchHits.NO_HITS);
		tooltip.setOnShowing(event -> updateSearchHitsTooltip(tooltip));
		lblSearchHits.setTooltip(tooltip);
		state.searchHits().addListener((ListChangeListener<Booking>) change -> refreshSearchHits(tooltip));
		refreshSearchHits(tooltip);
	}

	/** Zahl und Tooltip nach neuen Treffern; der Text steht so schon fest, bevor der Tooltip sich bemisst. */
	private void refreshSearchHits(Tooltip tooltip) {
		lblSearchHits.setText(SearchHits.countText(state.searchHits().size()));
		updateSearchHitsTooltip(tooltip);
	}

	private void updateSearchHitsTooltip(Tooltip tooltip) {
		Event event = state.eventProperty().get();
		List<Presentation> presentations = event == null || event.getPresentations() == null ? List.of() : event.getPresentations();
		tooltip.setText(SearchHits.tooltipText(state.searchHits(), presentations, state.presentationProperty().get() == null));
	}

	/**
	 * "frei: n von m" über dem Auswahlfeld "Vorstellung" (KARV-215), siehe {@link FreeSeats}. Hier hängt nur der Wechsel der
	 * Vorstellung; die übrigen Auslöser stehen an den Feldern oben und am Ende von {@link #applyEventSelection}.
	 */
	private void bindFreeSeats() {
		freeSeatsTooltip.setOnShowing(event -> refreshFreeSeats());
		state.presentationProperty().addListener((obs, oldPresentation, newPresentation) -> refreshFreeSeats());
		refreshFreeSeats();
	}

	/** Die Meldungen von Tracker und Repository gelten für alle Events; neu gerechnet wird nur für das gezeigte. */
	private void refreshFreeSeatsOf(Event changed) {
		if (changed.equals(state.eventProperty().get())) {
			refreshFreeSeats();
		}
	}

	/** Zahl und Tooltip aus dem aktuellen Stand; ohne Event steht nichts da. */
	private void refreshFreeSeats() {
		Event event = state.eventProperty().get();
		if (event == null) {
			lblFreeSeats.setText("");
			lblFreeSeats.setTooltip(null);
			return;
		}
		List<Presentation> counted = FreeSeats.counted(event, state.presentationProperty().get());
		lblFreeSeats.setText(FreeSeats.labelText(FreeSeats.count(counted)));
		freeSeatsTooltip.setText(FreeSeats.tooltipText(counted));
		lblFreeSeats.setTooltip(freeSeatsTooltip);
	}

	/** Das gewählte Event gilt für die Shell und den Inhalt; die Vorstellung steht danach auf "Alle Vorstellungen". */
	private void applyEventSelection(Event selectedEvent) {
		state.eventProperty().set(selectedEvent);
		state.presentationProperty().set(null);
		if (selectedEvent != null) {
			// Buchungen mit den Bestuhlungen abgleichen, bevor das Event angezeigt wird (gilt nicht als Änderung)
			BookingSyncService.prepare(selectedEvent);
		}
		// Das Neufüllen der Liste meldet der Inhalt nicht einzeln: setEvent unten setzt ihn auf "Alle Vorstellungen"
		updatingPresentations = true;
		try {
			List<Presentation> presentationItems = new ArrayList<>();
			presentationItems.add(null); // "Alle Vorstellungen"
			if (selectedEvent != null) {
				presentationItems.addAll(selectedEvent.getPresentations());
			}
			presComboBox.getItems().setAll(presentationItems);
			presComboBox.getSelectionModel().selectFirst();
		} finally {
			updatingPresentations = false;
		}
		if (activeContentController != null) {
			activeContentController.setEvent(selectedEvent);
		}
		// Erst jetzt: Vor dem Abgleich oben fehlen den Sitzen ohne Buchung noch ihre leeren Buchungen (KARV-215)
		refreshFreeSeats();
	}

	@FXML
	private void showTicketTableView() {
		StatusMessage.getInstance().clear();
		lblHeader.setText("Kartentabelle");
		loadContentView("/de/eltviller_carneval_verein/karten/ui/TicketTableView.fxml");
		activeContentController.filter(searchText);
		restorePendingSelection();
	}

	@FXML
	private void showHallView() {
		StatusMessage.getInstance().clear();
		lblHeader.setText("Saalübersicht");
		loadContentView("/de/eltviller_carneval_verein/karten/ui/HallOverviewView.fxml");
		activeContentController.filter(searchText);
		restorePendingSelection();
	}

	/**
	 * Gibt der neuen Ansicht die Auswahl der verlassenen zurück. Erst nach dem Suchfilter: Er baut die Auswahl der
	 * Tabelle um und würde sie sonst auf eine Zeile verkleinern.
	 */
	private void restorePendingSelection() {
		List<Booking> selection = pendingSelection;
		pendingSelection = List.of();
		if (activeContentController instanceof SalesStateAware aware) {
			aware.restoreSelection(selection);
		}
	}

	private void loadContentView(String fxmlPath) {
		try {
			FXMLLoader loader = new FXMLLoader(getClass().getResource(fxmlPath));
			Node view = loader.load();

			// Aktiven Inhalts-Controller merken
			this.activeContentController = loader.getController();

			// Inhalt im mittleren Bereich austauschen; ein offener Dialog der alten Ansicht würde sonst stehen bleiben
			PopupWindows.hideOwnedBy(contentArea.getScene() == null ? null : contentArea.getScene().getWindow());
			// Der Hinweis zur Auswahl liegt über der Ansicht (KARV-194)
			contentArea.getChildren().setAll(view, selectionHint.node());

			// Der Ausschnitt der alten Ansicht gilt nicht mehr, die Auswahl bleibt: Die neue Ansicht bekommt sie unten zurück
			List<Booking> keptSelection = new ArrayList<>(state.selectedBookings());
			// Ab jetzt zählen nur die Treffer der neuen Ansicht; die alte kann noch zeichnen (KARV-192)
			state.setActiveView(activeContentController);
			state.resetView();
			if (activeContentController instanceof SalesStateAware aware) {
				aware.setSalesState(state);
			}

			// Aktuelles Event und Vorstellung direkt an den neuen Inhalt übergeben
			activeContentController.setEvent(state.eventProperty().get());
			activeContentController.setPresentation(state.presentationProperty().get());
			// Der Bearbeitungsmodus gilt für die ganze Shell, nicht nur für die Ansicht, in der er eingeschaltet wurde
			activeContentController.setEditMode(editMode);
			pendingSelection = keptSelection;
		} catch (IOException e) {
			LOG.log(Level.SEVERE, "Ansicht konnte nicht geladen werden: " + fxmlPath, e);
			MainApp.showAlert("Fehler", "Ansicht konnte nicht geladen werden: " + e.getMessage(), AlertType.ERROR);
		}
	}

	@FXML
	private void toggleEditMode() {
		// Keine ungespeicherten Änderungen im Anzeigemodus: erst nachfragen
		if (editMode) {
			autoSave.flush(); // Sonst fragt der LeaveGuard nach Ungespeichertem, wenn jemand schneller als 300 ms wechselt
			Outcome outcome = LeaveGuard.confirmSwitchToViewMode();
			if (outcome == Outcome.STAY) {
				return;
			}
			if (outcome == Outcome.LEAVE_RESTORED) {
				rebindToRestoredData();
			}
		}
		this.editMode = !this.editMode;
		btnSave.setDisable(!editMode);

		// Button-Text anpassen
		if (btnToggleEdit != null) {
			btnToggleEdit.setText(editMode ? "Anzeigen" : "Bearbeiten");
		}

		// Edit-Status an die aktive Inhaltsansicht (Tabelle oder Saalplan) durchreichen
		if (activeContentController != null) {
			activeContentController.setEditMode(editMode);
		}
	}

	/**
	 * Nach "Nicht speichern" sind Event und Vorstellungen neue Objekte (der gespeicherte Stand wurde wiederhergestellt):
	 * Die Auswahl wird mit ihnen neu belegt, die Inhalte laden sich daraus neu.
	 */
	private void rebindToRestoredData() {
		Event previousEvent = state.eventProperty().get();
		Presentation previousPresentation = state.presentationProperty().get();
		String eventId = previousEvent == null ? null : previousEvent.getId();
		String presentationId = previousPresentation == null ? null : previousPresentation.getId();

		eventComboBox.getItems().setAll(eventRepository.loadEvents().stream().filter(event -> !event.isArchived()).toList());
		eventComboBox.getItems().stream().filter(event -> event.getId().equals(eventId)).findFirst().ifPresent(event -> {
			eventComboBox.getSelectionModel().select(event);
			presComboBox.getItems().stream().filter(presentation -> presentation != null && presentation.getId().equals(presentationId)).findFirst()
					.ifPresent(presentation -> presComboBox.getSelectionModel().select(presentation));
		});
	}

	/** Die Checkbox "Verkaufsmodus" öffnet und schließt das Kundenfenster. */
	@FXML
	private void toggleSalesMode() {
		if (chkSalesMode.isSelected()) {
			openCustomerDisplay();
		} else {
			closeCustomerDisplay();
		}
	}

	private void openCustomerDisplay() {
		if (customerDisplay != null) {
			return;
		}
		CustomerDisplay display = new CustomerDisplay(state);
		customerDisplay = display;
		display.show(contentArea.getScene().getWindow(), () -> {
			// Schließt der Anwender das Fenster selbst, ist der Verkaufsmodus aus
			if (customerDisplay == display) {
				customerDisplay = null;
			}
			chkSalesMode.setSelected(false);
		});
	}

	private void closeCustomerDisplay() {
		CustomerDisplay display = customerDisplay;
		customerDisplay = null;
		if (display != null) {
			display.close();
		}
	}

	@FXML
	private void handleBackToMenu() {
		autoSave.flush(); // Vor dem Verlassen: Der LeaveGuard soll nur nach dem fragen, was wirklich nicht gespeichert werden konnte
		MainApp.showMenuView();
	}

	@FXML
	private void handleSave() {
		if (activeContentController != null) {
			// Speichere den Zustand der aktuell aktiven Sicht
			activeContentController.save();
		}
	}
}