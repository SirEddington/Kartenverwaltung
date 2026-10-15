package de.eltviller_carneval_verein.karten.ui.sales;

import java.util.List;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.Table;
import de.eltviller_carneval_verein.karten.repository.JsonHallRepository;
import de.eltviller_carneval_verein.karten.tracking.ChangeTracker;
import de.eltviller_carneval_verein.karten.ui.UiColors;
import javafx.beans.value.ChangeListener;
import javafx.collections.SetChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.Screen;
import javafx.stage.Stage;
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
	private final Stage stage = new Stage();
	private final Label titleLabel = new Label();
	private final Label hintLabel = new Label(CustomerDisplayText.WAITING_HINT);
	private final HallPlanPane planPane = new HallPlanPane();
	private boolean attached = false;

	private final ChangeListener<Object> contentChanged = (obs, oldValue, newValue) -> {
		refresh();
		applyView();
	};
	private final ChangeListener<Rectangle2D> viewChanged = (obs, oldValue, newValue) -> applyView();
	private final SetChangeListener<Seat> selectionChanged = change -> refresh();
	// Ein Verkauf im Hauptfenster ändert Sitze: Die Änderungserkennung meldet es kurz nach der Bedienung
	private final ChangeTracker.Listener<Event> dataChanged = (event, before, after) -> refresh();

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
		titleLabel.setStyle("-fx-font-size: 30px; -fx-font-weight: bold;");
		titleLabel.setWrapText(true);
		hintLabel.setStyle("-fx-font-size: 28px;");
		planPane.setInteractive(false);
		planPane.setRefitOnResize(true);
		planPane.setMouseTransparent(true);

		BorderPane root = new BorderPane();
		root.setStyle("-fx-background-color: white;");
		StackPane top = new StackPane(titleLabel);
		top.setPadding(new Insets(16));
		StackPane.setAlignment(titleLabel, Pos.CENTER);
		root.setTop(top);
		root.setCenter(new StackPane(planPane, hintLabel));

		Scene scene = new Scene(root);
		scene.getStylesheets().add(HallOverviewController.APP_STYLESHEET);
		scene.setOnKeyPressed(event -> {
			if (event.getCode() == KeyCode.F11) {
				stage.setFullScreen(!stage.isFullScreen());
			}
		});
		scene.setOnMouseClicked(event -> {
			if (event.getClickCount() == 2) {
				stage.setFullScreen(!stage.isFullScreen());
			}
		});

		stage.setTitle("ECV Kartenverwaltung – Verkaufsmodus");
		stage.getIcons().add(new Image(CustomerDisplay.class.getResourceAsStream(LOGO)));
		stage.setFullScreenExitHint("");
		stage.setScene(scene);
		place(mainWindow);
		stage.setOnHidden(event -> {
			detach();
			onClosed.run();
		});

		attach();
		stage.show();
		refresh();
		applyView();
	}

	/** Schließt das Fenster; mehrfaches Aufrufen schadet nicht. */
	public void close() {
		stage.close();
	}

	public boolean isShowing() {
		return stage.isShowing();
	}

	private void place(Window mainWindow) {
		List<Screen> screens = Screen.getScreens();
		List<Rectangle2D> visualBounds = screens.stream().map(Screen::getVisualBounds).toList();
		Rectangle2D main = new Rectangle2D(mainWindow.getX(), mainWindow.getY(), mainWindow.getWidth(), mainWindow.getHeight());
		int other = ScreenChoice.indexOfOtherScreen(visualBounds, main);
		if (other >= 0) {
			Rectangle2D bounds = visualBounds.get(other);
			stage.setX(bounds.getMinX());
			stage.setY(bounds.getMinY());
			stage.setWidth(bounds.getWidth());
			stage.setHeight(bounds.getHeight());
		} else {
			stage.setWidth(1024);
			stage.setHeight(768);
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
