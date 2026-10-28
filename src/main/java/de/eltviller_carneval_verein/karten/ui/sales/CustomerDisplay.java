package de.eltviller_carneval_verein.karten.ui.sales;

import java.util.List;

import de.eltviller_carneval_verein.karten.MainApp;
import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.repository.ForeignBatch;
import de.eltviller_carneval_verein.karten.repository.RepositoryListener;
import de.eltviller_carneval_verein.karten.repository.StoreEventRepository;
import de.eltviller_carneval_verein.karten.repository.StoreHallRepository;
import de.eltviller_carneval_verein.karten.tracking.ChangeTracker;
import de.eltviller_carneval_verein.karten.ui.UiColors;
import de.eltviller_carneval_verein.karten.util.MoneyFormat;
import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.beans.value.ChangeListener;
import javafx.collections.SetChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.image.Image;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Shape;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

/**
 * Das Kundenfenster ("Verkaufsmodus"): Die Saalübersicht der im Hauptfenster gewählten Vorstellung als reine Anzeige,
 * ohne Bedienelemente. Sitze sind nur rot (nicht frei) oder grün (frei); ausgewählte Sitze, der sichtbare Ausschnitt
 * (Zoom, Verschieben) und "Reihen anzeigen" folgen dem Hauptfenster über den {@link SalesViewState}.
 */
public final class CustomerDisplay {

	private static final String LOGO = "/de/eltviller_carneval_verein/karten/ui/images/harlekin_logo.png";

	private final SalesViewState state;
	private final StoreHallRepository hallRepository = StoreHallRepository.getInstance();
	// Der Vollbildmodus ist ein randloses Fenster in Bildschirmgröße und kein JavaFX-Vollbild: Ein Vollbildfenster wird
	// minimiert, sobald das Hauptfenster den Fokus bekommt. Die Scene wandert beim Umschalten zwischen den Fenstern.
	private final Stage windowStage = new Stage();
	private Stage borderlessStage;
	private Scene scene;
	private BorderPane contentLayout;
	private BorderPane body;
	private VBox summaryPanel;
	private Side summarySide;
	private Runnable onClosed;
	private boolean fullscreen = false;
	private boolean switching = false;
	private final Label titleLabel = new Label();
	private final Label hintLabel = new Label(CustomerDisplayText.WAITING_HINT);
	private final HallPlanPane planPane = new HallPlanPane();
	// Seitenleiste: Was gerade gekauft wird (Anzahl, eine Zeile je Karte, Gesamtpreis)
	private final Label countLabel = new Label();
	private final VBox lineBox = new VBox(6);
	private final Label totalLabel = new Label();
	private final HBox totalRow = new HBox();
	private boolean attached = false;

	// Eine Auswahl von hundert Sitzen meldet hundert Änderungen: Der Plan wird trotzdem nur einmal neu gezeichnet
	private final CoalescedAction redraw = new CoalescedAction(Platform::runLater, () -> {
		if (attached) {
			refresh();
			applyView();
		}
	});
	private final ChangeListener<Object> contentChanged = (obs, oldValue, newValue) -> redraw.request();
	private final ChangeListener<Rectangle2D> viewChanged = (obs, oldValue, newValue) -> applyView();
	private final InvalidationListener placementInvalidated = observable -> placeSummary();
	private final SetChangeListener<Booking> selectionChanged = change -> redraw.request();
	// Ein Verkauf im Hauptfenster ändert Buchungen: Die Änderungserkennung meldet es kurz nach der Bedienung
	private final ChangeTracker.Listener<Event> dataChanged = (changed, before, after) -> onDataChanged(changed);
	// Fremde Änderungen führt der Tracker still nach (rebase meldet nichts): Das Fenster erfährt sie vom Repository
	private final RepositoryListener<Event> foreignEventChanges = new RepositoryListener<>() {
		@Override
		public void changedByOthers(Event event, ForeignBatch<Event> batch) {
			onDataChanged(event);
		}
	};
	private final RepositoryListener<Hall> foreignHallChanges = new RepositoryListener<>() {
		@Override
		public void changedByOthers(Hall hall, ForeignBatch<Hall> batch) {
			onHallChanged(hall);
		}

		@Override
		public void addedByOthers(Hall hall) {
			onHallChanged(hall);
		}

		@Override
		public void removedByOthers(Hall hall) {
			onHallChanged(hall);
		}
	};

	private final HallPlanPane.Decorator decorator = new HallPlanPane.Decorator() {
		@Override
		public void decorateSeat(Booking booking, Seat seat, Node seatNode) {
			if (seatNode instanceof Shape shape) {
				shape.setFill((CustomerDisplayText.isTaken(booking) ? UiColors.STATUS_DANGER : UiColors.STATUS_SUCCESS).getFxColor());
				shape.setStroke(Color.BLACK);
				shape.setStrokeWidth(1);
			}
			if (state.selectedBookings().contains(booking)) {
				seatNode.getProperties().put(HallPlanPane.SELECTED_KEY, Boolean.TRUE);
			}
			seatNode.setMouseTransparent(true);
		}
	};

	public CustomerDisplay(SalesViewState state) {
		this.state = state;
	}

	/**
	 * Zeigt das Fenster, wenn möglich auf einem anderen Bildschirm als dem des Hauptfensters.
	 *
	 * @param mainWindow das Hauptfenster
	 * @param onClosed   wird aufgerufen, wenn das Fenster geschlossen wurde (auch vom Anwender)
	 */
	public void show(Window mainWindow, Runnable onClosed) {
		// Wie die Titel im Hauptmenü: Yanone Kaffeesatz Bold in ECV-Blau
		titleLabel.getStyleClass().add("header-label");
		titleLabel.setWrapText(true);
		hintLabel.getStyleClass().add("subheader-label");
		planPane.setInteractive(false);
		planPane.setMouseTransparent(true);

		contentLayout = new BorderPane();
		contentLayout.setTop(titleLabel);
		BorderPane.setAlignment(titleLabel, Pos.CENTER);
		BorderPane.setMargin(titleLabel, new Insets(16));
		// Der Titel steht immer ganz oben; die Kartenübersicht hängt an einer der vier Seiten des Plans darunter
		body = new BorderPane();
		body.setCenter(new StackPane(planPane, hintLabel));
		contentLayout.setCenter(body);
		summaryPanel = createSummaryPanel();
		placeSummary(Side.RIGHT);

		// Ändert sich die Form der Fläche, passt der Ausschnitt neu: Der relative Zoom hängt vom Seitenverhältnis ab
		InvalidationListener refit = observable -> applyView();
		planPane.contentPane().widthProperty().addListener(refit);
		planPane.contentPane().heightProperty().addListener(refit);

		// Hintergrund, Wappen und Stylesheet wie in allen Ansichten der App
		scene = MainApp.createScene(contentLayout);
		// Automatisch steht die Kartenübersicht im Hochformat unter dem Plan, sonst rechts; die Wahl im Menü "Anzeige" geht vor
		scene.widthProperty().addListener(placementInvalidated);
		scene.heightProperty().addListener(placementInvalidated);
		// F11 und Doppelklick schalten den Vollbildmodus um, Esc beendet ihn
		scene.setOnKeyPressed(event -> {
			if (event.getCode() == KeyCode.F11 || (event.getCode() == KeyCode.ESCAPE && fullscreen)) {
				toggleFullscreen();
			}
		});
		scene.setOnMouseClicked(event -> {
			if (event.getClickCount() == 2) {
				toggleFullscreen();
			}
		});

		this.onClosed = onClosed;
		configure(windowStage);
		windowStage.setScene(scene);
		place(mainWindow);

		attach();
		placeSummary(); // Eine vor dem Öffnen gewählte Platzierung gilt sofort
		windowStage.show();
		// Ein neues Fenster nimmt den Fokus: Der Verkäufer soll nach dem Haken weitertippen können (Suche, Tabelle)
		mainWindow.requestFocus();
		refresh();
		applyView();
	}

	/** Schließt das Fenster; mehrfaches Aufrufen schadet nicht. */
	public void close() {
		windowStage.close();
		if (borderlessStage != null) {
			borderlessStage.close();
		}
	}

	/** Hängt die Kartenübersicht dorthin, wo die Wahl im Menü "Anzeige" und die Form des Fensters sie hinstellen. */
	private void placeSummary() {
		placeSummary(state.summaryPlacementProperty().get().sideFor(scene.getWidth(), scene.getHeight()));
	}

	/** Setzt die Kartenübersicht an die Seite des Plans: links oder rechts als Spalte, oben oder unten als Leiste. */
	private void placeSummary(Side side) {
		if (summarySide == side) {
			return; // Beim Ziehen am Fensterrand kommen viele Größenänderungen: Die Leiste wird nur bei Wechsel umgehängt
		}
		summarySide = side;
		body.setTop(null);
		body.setBottom(null);
		body.setLeft(null);
		body.setRight(null);
		boolean column = side == Side.LEFT || side == Side.RIGHT;
		if (column) {
			summaryPanel.setMinWidth(380);
			summaryPanel.setPrefWidth(380);
			summaryPanel.setMinHeight(Region.USE_COMPUTED_SIZE);
			summaryPanel.setPrefHeight(Region.USE_COMPUTED_SIZE);
		} else {
			summaryPanel.setMinWidth(0);
			summaryPanel.setPrefWidth(Region.USE_COMPUTED_SIZE);
			summaryPanel.setMinHeight(320);
			summaryPanel.setPrefHeight(320);
		}
		switch (side) {
		case TOP -> {
			BorderPane.setMargin(summaryPanel, new Insets(0, 16, 8, 16));
			body.setTop(summaryPanel);
		}
		case BOTTOM -> {
			BorderPane.setMargin(summaryPanel, new Insets(0, 16, 16, 16));
			body.setBottom(summaryPanel);
		}
		case LEFT -> {
			BorderPane.setMargin(summaryPanel, new Insets(0, 8, 16, 16));
			body.setLeft(summaryPanel);
		}
		case RIGHT -> {
			BorderPane.setMargin(summaryPanel, new Insets(0, 16, 16, 8));
			body.setRight(summaryPanel);
		}
		}
	}

	/** Die feste Spalte (Querformat) bzw. Leiste (Hochformat): Anzahl oben, scrollende Liste, Gesamtpreis unten. */
	private VBox createSummaryPanel() {
		countLabel.getStyleClass().add("header-label");
		totalLabel.getStyleClass().add("header-label");
		Label totalCaption = new Label("Gesamt");
		totalCaption.getStyleClass().add("subheader-label");
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		totalRow.getChildren().setAll(totalCaption, spacer, totalLabel);
		totalRow.setAlignment(Pos.BASELINE_LEFT);

		ScrollPane lines = new ScrollPane(lineBox);
		lines.setFitToWidth(true);
		lines.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
		lines.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
		VBox.setVgrow(lines, Priority.ALWAYS);

		VBox panel = new VBox(12, countLabel, lines, new Separator(), totalRow);
		panel.setPadding(new Insets(16));
		panel.setStyle("-fx-background-color: white; -fx-border-color: #cccccc; -fx-border-radius: 5; -fx-background-radius: 5;");
		return panel;
	}

	private Node lineRow(SelectionSummary.Line line) {
		Label text = new Label(line.text());
		text.getStyleClass().add("subheader-label");
		Label price = new Label(MoneyFormat.formatCents(line.cents()));
		price.getStyleClass().add("subheader-label");
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		return new HBox(text, spacer, price);
	}

	private void updateSummary(Presentation presentation) {
		SelectionSummary.Summary summary = SelectionSummary.of(state.selectedBookings(), presentation);
		countLabel.setText(SelectionSummary.countText(summary.count()));
		lineBox.getChildren().setAll(summary.lines().stream().map(this::lineRow).toList());
		totalLabel.setText(MoneyFormat.formatCents(summary.totalCents()));
		// Unsichtbar statt entfernt: Die Spalte behält ihre Höhe, nichts springt
		totalRow.setVisible(summary.count() > 0);
	}

	private void configure(Stage target) {
		target.setTitle("ECV Kartenverwaltung – Verkaufsmodus");
		target.getIcons().add(new Image(CustomerDisplay.class.getResourceAsStream(LOGO)));
		target.setOnHidden(event -> {
			// Beim Umschalten verschwindet das eine Fenster, das andere erscheint: Das ist kein Schließen
			if (!switching) {
				detach();
				if (onClosed != null) {
					Runnable notify = onClosed;
					onClosed = null;
					notify.run();
				}
			}
		});
	}

	/** Schaltet zwischen dem Fenster und dem randlosen Fenster in Bildschirmgröße um; die Scene zieht dabei um. */
	private void toggleFullscreen() {
		switching = true;
		try {
			if (!fullscreen) {
				Rectangle2D screen = Screen.getScreensForRectangle(windowStage.getX(), windowStage.getY(), windowStage.getWidth(), windowStage.getHeight()).get(0).getBounds();
				if (borderlessStage == null) {
					borderlessStage = new Stage(StageStyle.UNDECORATED);
					configure(borderlessStage);
				}
				windowStage.setScene(null);
				borderlessStage.setScene(scene);
				borderlessStage.setX(screen.getMinX());
				borderlessStage.setY(screen.getMinY());
				borderlessStage.setWidth(screen.getWidth());
				borderlessStage.setHeight(screen.getHeight());
				borderlessStage.show();
				windowStage.hide();
			} else {
				borderlessStage.setScene(null);
				windowStage.setScene(scene);
				windowStage.show();
				borderlessStage.hide();
			}
			fullscreen = !fullscreen;
		} finally {
			switching = false;
		}
	}

	private void place(Window mainWindow) {
		List<Screen> screens = Screen.getScreens();
		// Gewählt wird nach den ganzen Bildschirmen: Liegt die Mitte des Hauptfensters in der Taskleiste, ist es trotzdem
		// derselbe Bildschirm. Platziert wird dann in den Sichtbereich ohne Taskleiste.
		List<Rectangle2D> screenBounds = screens.stream().map(Screen::getBounds).toList();
		Rectangle2D main = new Rectangle2D(mainWindow.getX(), mainWindow.getY(), mainWindow.getWidth(), mainWindow.getHeight());
		int other = ScreenChoice.indexOfOtherScreen(screenBounds, main);
		if (other >= 0) {
			Rectangle2D bounds = screens.get(other).getVisualBounds();
			windowStage.setX(bounds.getMinX());
			windowStage.setY(bounds.getMinY());
			windowStage.setWidth(bounds.getWidth());
			windowStage.setHeight(bounds.getHeight());
		} else {
			windowStage.setWidth(1024);
			windowStage.setHeight(768);
		}
	}

	private void attach() {
		attached = true;
		state.eventProperty().addListener(contentChanged);
		state.presentationProperty().addListener(contentChanged);
		state.planLabels().addListener(contentChanged);
		state.visibleHallRectProperty().addListener(viewChanged);
		state.summaryPlacementProperty().addListener(placementInvalidated);
		state.selectedBookings().addListener(selectionChanged);
		ChangeTracker.events().addListener(dataChanged);
		StoreEventRepository.getInstance().addListener(foreignEventChanges);
		hallRepository.addListener(foreignHallChanges);
	}

	private void detach() {
		if (!attached) {
			return;
		}
		attached = false;
		state.eventProperty().removeListener(contentChanged);
		state.presentationProperty().removeListener(contentChanged);
		state.planLabels().removeListener(contentChanged);
		state.visibleHallRectProperty().removeListener(viewChanged);
		state.summaryPlacementProperty().removeListener(placementInvalidated);
		state.selectedBookings().removeListener(selectionChanged);
		ChangeTracker.events().removeListener(dataChanged);
		StoreEventRepository.getInstance().removeListener(foreignEventChanges);
		hallRepository.removeListener(foreignHallChanges);
	}

	/** Nur Änderungen am gezeigten Event zeichnen neu; die Änderungserkennung meldet alle verfolgten Events. */
	private void onDataChanged(Event changed) {
		if (changed.equals(state.eventProperty().get())) {
			redraw.request();
		}
	}

	/** Ersetzt oder ändert ein anderer Rechner die Halle der gezeigten Vorstellung, zeichnet das Fenster neu (die Halle wird über die ID gesucht). */
	private void onHallChanged(Hall hall) {
		Presentation presentation = state.presentationProperty().get();
		if (presentation != null && hall.getId().equals(presentation.getHallId())) {
			redraw.request();
		}
	}

	/** Die Halle der Vorstellung, {@code null} ohne Vorstellung oder ohne zugeordnete Halle. */
	private Hall hallOf(Presentation presentation) {
		return presentation == null ? null : hallRepository.findById(presentation.getHallId());
	}

	/** Zeichnet Titel und Plan der gewählten Vorstellung neu (Zoom und Verschiebung bleiben). */
	private void refresh() {
		Presentation presentation = state.presentationProperty().get();
		titleLabel.setText(CustomerDisplayText.title(state.eventProperty().get(), presentation));
		Hall hall = hallOf(presentation);
		SeatingPlan plan = SalesPlan.of(presentation, hall);
		boolean waiting = CustomerDisplayText.showsWaitingHint(presentation, plan);
		hintLabel.setVisible(waiting);
		// Ohne Plan bleibt die Fläche leer wie ohne Vorstellung: Den internen Hinweis zur Bestuhlung zeigt nur das Hauptfenster
		planPane.render(hall, plan, waiting ? null : presentation, state.planLabels().snapshot(), decorator);
		updateSummary(presentation);
	}

	/**
	 * Wählt den gezeigten Ausschnitt. Mit Halle zeigt das Kundenfenster dieselbe Mitte und denselben Zoom <em>relativ zur
	 * eingepassten Halle</em> wie das Hauptfenster ({@link HallViewport#relativeMirror}): Die Halle behält ihre Größe,
	 * auch wenn Kopf- und Fußzeile des Hauptfensters dessen Fläche anders formen als die des Kundenfensters. Ohne
	 * Ausschnitt (Tabellenansicht) ist es die ganze Halle, ohne Halle die Grenzen der gezeichneten Inhalte.
	 */
	private void applyView() {
		Rectangle2D rect = state.visibleHallRectProperty().get();
		Rectangle2D hallRect = HallViewport.hallRect(hallOf(state.presentationProperty().get()));
		if (rect != null) {
			double width = planPane.contentPane().getWidth();
			double height = planPane.contentPane().getHeight();
			if (hallRect != null && width > 0 && height > 0) {
				planPane.fit(HallViewport.relativeMirror(rect, hallRect, width / height), 1.0);
			} else {
				planPane.fit(rect, 1.0);
			}
			return;
		}
		if (hallRect != null) {
			planPane.fit(hallRect, HallViewport.HALL_FIT_MARGIN);
			return;
		}
		Rectangle2D bounds = planPane.contentBounds();
		if (bounds != null) {
			planPane.fit(bounds, HallViewport.HALL_FIT_MARGIN);
		}
	}
}
