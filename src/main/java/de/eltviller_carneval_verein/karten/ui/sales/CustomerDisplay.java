package de.eltviller_carneval_verein.karten.ui.sales;

import java.util.List;

import de.eltviller_carneval_verein.karten.MainApp;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.Table;
import de.eltviller_carneval_verein.karten.repository.JsonHallRepository;
import de.eltviller_carneval_verein.karten.tracking.ChangeTracker;
import de.eltviller_carneval_verein.karten.ui.UiColors;
import de.eltviller_carneval_verein.karten.util.MoneyFormat;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.collections.SetChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
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
import javafx.scene.shape.Circle;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

/**
 * Das Kundenfenster ("Verkaufsmodus"): Die Saalübersicht der im Hauptfenster gewählten Vorstellung als reine Anzeige,
 * ohne Bedienelemente. Sitze sind nur rot (nicht frei) oder grün (frei); ausgewählte Sitze und der sichtbare Ausschnitt
 * (Zoom, Verschieben) folgen dem Hauptfenster über den {@link SalesViewState}.
 */
public final class CustomerDisplay {

	private static final String LOGO = "/de/eltviller_carneval_verein/karten/ui/images/harlekin_logo.png";

	private final SalesViewState state;
	private final JsonHallRepository hallRepository = JsonHallRepository.getInstance();
	// Der Vollbildmodus ist ein randloses Fenster in Bildschirmgröße und kein JavaFX-Vollbild: Ein Vollbildfenster wird
	// minimiert, sobald das Hauptfenster den Fokus bekommt. Die Scene wandert beim Umschalten zwischen den Fenstern.
	private final Stage windowStage = new Stage();
	private Stage borderlessStage;
	private Scene scene;
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
	private final SetChangeListener<Seat> selectionChanged = change -> redraw.request();
	// Ein Verkauf im Hauptfenster ändert Sitze: Die Änderungserkennung meldet es kurz nach der Bedienung
	private final ChangeTracker.Listener<Event> dataChanged = (event, before, after) -> redraw.request();

	private final HallPlanPane.Decorator decorator = new HallPlanPane.Decorator() {
		@Override
		public void decorateTable(Table table, Node tableNode) {
			tableNode.setMouseTransparent(true);
		}

		@Override
		public void decorateSeat(Seat seat, Circle seatNode) {
			seatNode.setFill((CustomerDisplayText.isTaken(seat) ? UiColors.STATUS_DANGER : UiColors.STATUS_SUCCESS).getFxColor());
			boolean selected = state.selectedSeats().contains(seat);
			seatNode.setStroke(selected ? HallPlanPane.SELECTED_SEAT_STROKE : Color.BLACK);
			seatNode.setStrokeWidth(selected ? 4 : 1);
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
		planPane.setRefitOnResize(true);
		planPane.setMouseTransparent(true);

		BorderPane content = new BorderPane();
		StackPane top = new StackPane(titleLabel);
		top.setPadding(new Insets(16));
		StackPane.setAlignment(titleLabel, Pos.CENTER);
		content.setTop(top);
		content.setCenter(new StackPane(planPane, hintLabel));
		Node summaryPanel = createSummaryPanel();
		BorderPane.setMargin(summaryPanel, new Insets(0, 16, 16, 8));
		content.setRight(summaryPanel);

		// Hintergrund und Wappen wie in allen Ansichten der App
		scene = new Scene(MainApp.wrapWithBackground(content));
		scene.getStylesheets().add(HallOverviewController.APP_STYLESHEET);
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
		windowStage.show();
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

	public boolean isShowing() {
		return windowStage.isShowing() || (borderlessStage != null && borderlessStage.isShowing());
	}

	/** Ob das Fenster gerade den ganzen Bildschirm einnimmt. */
	boolean isFullscreen() {
		return fullscreen;
	}

	/** Die feste Spalte rechts neben dem Plan: Anzahl oben, scrollende Liste, Gesamtpreis unten. */
	private Node createSummaryPanel() {
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
		panel.setPrefWidth(380);
		panel.setMinWidth(380);
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
		SelectionSummary.Summary summary = SelectionSummary.of(state.selectedSeats(), presentation);
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
				Stage from = windowStage;
				Rectangle2D screen = Screen.getScreensForRectangle(from.getX(), from.getY(), from.getWidth(), from.getHeight()).get(0).getBounds();
				if (borderlessStage == null) {
					borderlessStage = new Stage(StageStyle.UNDECORATED);
					configure(borderlessStage);
				}
				from.setScene(null);
				borderlessStage.setScene(scene);
				borderlessStage.setX(screen.getMinX());
				borderlessStage.setY(screen.getMinY());
				borderlessStage.setWidth(screen.getWidth());
				borderlessStage.setHeight(screen.getHeight());
				borderlessStage.show();
				from.hide();
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
		List<Rectangle2D> visualBounds = screens.stream().map(Screen::getVisualBounds).toList();
		Rectangle2D main = new Rectangle2D(mainWindow.getX(), mainWindow.getY(), mainWindow.getWidth(), mainWindow.getHeight());
		int other = ScreenChoice.indexOfOtherScreen(visualBounds, main);
		if (other >= 0) {
			Rectangle2D bounds = visualBounds.get(other);
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
		state.visibleHallRectProperty().addListener(viewChanged);
		state.selectedSeats().addListener(selectionChanged);
		ChangeTracker.events().addListener(dataChanged);
	}

	private void detach() {
		if (!attached) {
			return;
		}
		attached = false;
		state.eventProperty().removeListener(contentChanged);
		state.presentationProperty().removeListener(contentChanged);
		state.visibleHallRectProperty().removeListener(viewChanged);
		state.selectedSeats().removeListener(selectionChanged);
		ChangeTracker.events().removeListener(dataChanged);
	}

	/** Zeichnet Titel und Plan der gewählten Vorstellung neu (Zoom und Verschiebung bleiben). */
	private void refresh() {
		Presentation presentation = state.presentationProperty().get();
		titleLabel.setText(CustomerDisplayText.title(state.eventProperty().get(), presentation));
		hintLabel.setVisible(presentation == null);
		Hall hall = presentation == null ? null : hallRepository.findById(presentation.getHallId());
		planPane.render(presentation, hall, decorator);
		updateSummary(presentation);
	}

	/**
	 * Wählt den gezeigten Ausschnitt: den des Hauptfensters; ohne ihn (Tabellenansicht) die ganze Halle, ohne Halle die
	 * Grenzen der gezeichneten Inhalte.
	 */
	private void applyView() {
		Rectangle2D rect = state.visibleHallRectProperty().get();
		if (rect != null) {
			planPane.fit(rect, 1.0);
			return;
		}
		Presentation presentation = state.presentationProperty().get();
		Hall hall = presentation == null ? null : hallRepository.findById(presentation.getHallId());
		if (hall != null && hall.getHallWidth() > 0 && hall.getHallHeight() > 0) {
			planPane.fit(new Rectangle2D(0, 0, hall.getHallWidth(), hall.getHallHeight()), 0.95);
			return;
		}
		Rectangle2D bounds = planPane.contentBounds();
		if (bounds != null) {
			planPane.fit(bounds, 0.95);
		}
	}
}
