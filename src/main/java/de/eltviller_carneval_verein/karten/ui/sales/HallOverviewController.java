package de.eltviller_carneval_verein.karten.ui.sales;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.SeatStatus;
import de.eltviller_carneval_verein.karten.model.Table;
import de.eltviller_carneval_verein.karten.repository.JsonHallRepository;
import de.eltviller_carneval_verein.karten.ui.ContentController;
import de.eltviller_carneval_verein.karten.ui.EventSaver;
import de.eltviller_carneval_verein.karten.ui.UiColors;
import de.eltviller_carneval_verein.karten.ui.input.SpinnerInput;
import de.eltviller_carneval_verein.karten.validation.EntityLabels;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.geometry.BoundingBox;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.stage.Popup;
import javafx.stage.Window;

public class HallOverviewController implements ContentController, SalesStateAware {

	private final JsonHallRepository hallRepository = JsonHallRepository.getInstance();
	private Event selectedEvent;
	private Presentation selectedPres;
	private boolean editMode = false;
	private String currentQuery = "";

	private Popup activePopup;

	// Mehrfachauswahl (nur im Bearbeitungsmodus): Strg-Klick auf Sitze oder Aufziehen eines Rahmens mit der linken Taste
	private static final double RUBBER_BAND_THRESHOLD = 4;
	private static final String SEAT_STYLE_CLASS = "hall-seat";
	private static final String TABLE_STYLE_CLASS = "hall-table";
	private static final Color SELECTED_SEAT_STROKE = HallPlanPane.SELECTED_SEAT_STROKE;
	// Mit gesetztem Verkaufszustand ist es dessen Auswahl (so sieht das Kundenfenster sie)
	private Set<Seat> selectedSeats = new LinkedHashSet<>();
	private final Map<Node, Seat> seatNodes = new HashMap<>();
	private final Rectangle rubberBand = new Rectangle();
	private ContextMenu selectionMenu;
	private double bandStartSceneX;
	private double bandStartSceneY;
	private boolean bandPressed = false;
	private boolean bandActive = false;
	private boolean bandAdditive = false;

	// Einpassen auf die Halle erfolgt nur, wenn sich die Halle ändert (Zoom bleibt beim Vorstellungswechsel)
	private String fittedHallId = null;

	private Table placingTable;
	private final EventHandler<MouseEvent> placingMoveHandler = this::handlePlacingMouseMoved;
	private final EventHandler<MouseEvent> placingClickHandler = this::handlePlacingMouseClicked;

	@FXML
	private HallPlanPane viewportPane;

	// Die Zeichenfläche des Plans (Hallenkoordinaten in cm), gesetzt in initialize()
	private Pane hallPane;

	@FXML
	private Label lblHint;

	@FXML
	public void initialize() {
		hallPane = viewportPane.contentPane();

		viewportPane.setOnMousePressed(this::handleDragStart);
		viewportPane.setOnMouseDragged(this::handleDrag);
		viewportPane.setOnMouseReleased(this::handleMouseReleased);
		rubberBand.setManaged(false);
		rubberBand.setMouseTransparent(true);
		rubberBand.setVisible(false);
		rubberBand.setFill(SELECTED_SEAT_STROKE.deriveColor(0, 1, 1, 0.15));
		rubberBand.setStroke(SELECTED_SEAT_STROKE);
		viewportPane.getChildren().add(rubberBand);
		updateHint();
	}

	// Verschoben wird der Plan von der HallPlanPane mit der rechten Maustaste (ein Klick ohne Ziehen öffnet das
	// Auswahlmenü); die linke Taste klickt Tisch/Sitz und zieht im Bearbeitungsmodus einen Auswahlrahmen auf
	private void handleDragStart(MouseEvent event) {
		if (event.getButton() != MouseButton.PRIMARY) {
			return;
		}
		// Bei offenem Dialog bleibt die Auswahl unberührt: Ein Rahmen würde den Dialog samt Eingaben schließen
		bandPressed = editMode && placingTable == null && !isDialogOpen();
		bandActive = false;
		bandAdditive = event.isShortcutDown();
		bandStartSceneX = event.getSceneX();
		bandStartSceneY = event.getSceneY();
	}

	private void handleDrag(MouseEvent event) {
		if (event.getButton() == MouseButton.PRIMARY && bandPressed) {
			updateRubberBand(event);
		}
	}

	private void handleMouseReleased(MouseEvent event) {
		if (event.getButton() == MouseButton.PRIMARY && bandPressed) {
			bandPressed = false;
			if (bandActive) {
				finishRubberBand(event);
			} else if (event.isStillSincePress() && !bandAdditive && !selectedSeats.isEmpty() && !isOnSeatOrTable(event.getTarget())) {
				// Klick ins Leere hebt die Auswahl auf
				selectedSeats.clear();
				renderHall();
			}
		} else if (event.getButton() == MouseButton.SECONDARY && editMode && event.isStillSincePress()) {
			showSelectionMenu(event);
		}
	}

	private Seat seatAt(Object target) {
		for (Node node = target instanceof Node n ? n : null; node != null; node = node.getParent()) {
			Seat seat = seatNodes.get(node);
			if (seat != null) {
				return seat;
			}
		}
		return null;
	}

	private boolean isDialogOpen() {
		return activePopup != null && activePopup.isShowing();
	}

	private static boolean isOnSeatOrTable(Object target) {
		for (Node node = target instanceof Node n ? n : null; node != null; node = node.getParent()) {
			if (node.getStyleClass().contains(SEAT_STYLE_CLASS) || node.getStyleClass().contains(TABLE_STYLE_CLASS)) {
				return true;
			}
		}
		return false;
	}

	private void updateRubberBand(MouseEvent event) {
		if (!bandActive) {
			if (Math.hypot(event.getSceneX() - bandStartSceneX, event.getSceneY() - bandStartSceneY) < RUBBER_BAND_THRESHOLD) {
				return;
			}
			bandActive = true;
			closePopup();
		}
		Point2D start = viewportPane.sceneToLocal(bandStartSceneX, bandStartSceneY);
		Point2D end = viewportPane.sceneToLocal(event.getSceneX(), event.getSceneY());
		rubberBand.setX(Math.min(start.getX(), end.getX()));
		rubberBand.setY(Math.min(start.getY(), end.getY()));
		rubberBand.setWidth(Math.abs(end.getX() - start.getX()));
		rubberBand.setHeight(Math.abs(end.getY() - start.getY()));
		rubberBand.setVisible(true);
	}

	/** Wählt alle Sitze im aufgezogenen Rahmen, die der Suchfilter zeigt; mit Strg zusätzlich zur bisherigen Auswahl. */
	private void finishRubberBand(MouseEvent event) {
		bandActive = false;
		rubberBand.setVisible(false);
		Bounds band = new BoundingBox(Math.min(bandStartSceneX, event.getSceneX()), Math.min(bandStartSceneY, event.getSceneY()),
				Math.abs(event.getSceneX() - bandStartSceneX), Math.abs(event.getSceneY() - bandStartSceneY));
		if (!bandAdditive) {
			selectedSeats.clear();
		}
		seatNodes.forEach((node, seat) -> {
			if (matchesQuery(seat) && node.localToScene(node.getBoundsInLocal()).intersects(band)) {
				selectedSeats.add(seat);
			}
		});
		renderHall();
	}

	private void toggleSelection(Seat seat) {
		if (!matchesQuery(seat)) {
			return; // Was der Suchfilter ausblendet, lässt sich nicht auswählen
		}
		if (!selectedSeats.remove(seat)) {
			selectedSeats.add(seat);
		}
		renderHall();
	}

	/**
	 * Rechtsklick ohne Ziehen (mit gedrückter Taste zu ziehen verschiebt den Plan): Das Menü gehört zu einem Sitz. Auf
	 * einem gewählten Sitz gilt es für die ganze Auswahl, auf einem anderen Sitz wird dieser zur einzigen Auswahl, wie im
	 * Explorer. Im Leeren, auf einem Tisch, auf einem vom Suchfilter ausgeblendeten Sitz und bei offenem Dialog (er
	 * würde sonst samt Eingaben ersetzt) gibt es kein Menü.
	 */
	private void showSelectionMenu(MouseEvent event) {
		if (selectionMenu != null) {
			selectionMenu.hide();
		}
		Seat seat = seatAt(event.getTarget());
		if (seat == null || isDialogOpen() || !matchesQuery(seat)) {
			return;
		}
		if (!selectedSeats.contains(seat)) {
			selectedSeats.clear();
			selectedSeats.add(seat);
			renderHall();
		}
		MenuItem sellItem = new MenuItem(SeatSelectionEditor.menuText(selectedSeats.size()));
		selectionMenu = new ContextMenu(sellItem);
		sellItem.setOnAction(action -> sellSelection(selectionMenu.getAnchorX(), selectionMenu.getAnchorY()));
		selectionMenu.show(viewportPane, event.getScreenX(), event.getScreenY());
	}

	private void sellSelection(double screenX, double screenY) {
		closePopup();
		SeatDetailPopup dialog = SeatSelectionEditor.edit(List.copyOf(selectedSeats), viewportPane.getScene().getWindow(), screenX, screenY, () -> {
			selectedSeats.clear();
			renderHall();
		});
		if (dialog != null) {
			activePopup = dialog.popup();
		}
	}

	public void renderHall() {
		seatNodes.clear();
		Hall hall = selectedPres == null ? null : hallRepository.findById(selectedPres.getHallId());
		viewportPane.render(selectedPres, hall, planDecorator);
	}

	/** Aussehen und Verhalten der Tische und Sitze in der Saalübersicht (Klicks, Auswahl, Suchfilter). */
	private final HallPlanPane.Decorator planDecorator = new HallPlanPane.Decorator() {
		@Override
		public void decorateTable(Table table, Node tableNode) {
			tableNode.getStyleClass().add(TABLE_STYLE_CLASS);
			tableNode.setCursor(Cursor.HAND);
			setupTableClickHandler(tableNode, table);
		}

		@Override
		public void decorateSeat(Seat seat, Circle seatNode) {
			styleSeatNode(seat, seatNode);
		}
	};

	/**
	 * Ein Klick auf einen Tisch öffnet immer das Tisch-Detail-Popup (in beiden
	 * Modi; im Anzeigemodus sind die Felder dort nur lesbar). Das Verschieben
	 * der Position passiert nicht mehr per Drag direkt am Tisch, sondern über
	 * den Knopf "Position festlegen" im Popup (siehe {@link #startPlacingTable}).
	 */
	private void setupTableClickHandler(Node tableShapeNode, Table table) {
		tableShapeNode.setOnMouseClicked(event -> {
			if (event.getButton() != MouseButton.PRIMARY || !event.isStillSincePress()) {
				return; // z.B. nach einem Schwenk/Zoom der Ansicht, sicherheitshalber ignorieren
			}
			event.consume();
			if (editMode && isDialogOpen()) {
				return; // Siehe Sitzklick: Der offene Dialog behält seine Eingaben
			}
			Window window = tableShapeNode.getScene().getWindow();
			openTableDetailPopup(table, window, event.getScreenX() + 12, event.getScreenY() + 12);
		});
	}

	/**
	 * Versetzt die Saalübersicht in einen Platzierungsmodus: Der Tisch folgt
	 * bis zum nächsten Klick dem Mauszeiger; dieser Klick bestätigt die neue
	 * Position und markiert den Tisch als manuell positioniert.
	 */
	private void startPlacingTable(Table table) {
		cancelPlacingTable();

		placingTable = table;
		// Muss bereits hier gesetzt werden: sonst überschreibt applyDefaultPositions()
		// die per Cursor gesetzte Position bei jedem renderHall() während des Verschiebens wieder.
		placingTable.setManualPos(true);
		hallPane.setCursor(Cursor.CROSSHAIR);
		hallPane.addEventFilter(MouseEvent.MOUSE_MOVED, placingMoveHandler);
		hallPane.addEventFilter(MouseEvent.MOUSE_CLICKED, placingClickHandler);
	}

	private void handlePlacingMouseMoved(MouseEvent event) {
		if (placingTable == null) {
			return;
		}
		Point2D localPoint = hallPane.sceneToLocal(event.getSceneX(), event.getSceneY());
		placingTable.setPosX(localPoint.getX() - placingTable.getWidth() / 2.0);
		placingTable.setPosY(localPoint.getY() - placingTable.getHeight() / 2.0);
		renderHall();
	}

	private void handlePlacingMouseClicked(MouseEvent event) {
		if (placingTable == null || event.getButton() != MouseButton.PRIMARY) {
			return;
		}
		event.consume();
		placingTable.setManualPos(true);
		finishPlacingTable();
	}

	/** Bestätigter Abschluss der Platzierung: räumt auf und zeichnet den Saal an der neuen Position neu. */
	private void finishPlacingTable() {
		cancelPlacingTable();
		renderHall();
	}

	/** Räumt einen laufenden Platzierungsvorgang ohne Neuzeichnen auf (z.B. bei Moduswechsel). */
	private void cancelPlacingTable() {
		if (placingTable != null) {
			placingTable = null;
			hallPane.setCursor(Cursor.DEFAULT);
			hallPane.removeEventFilter(MouseEvent.MOUSE_MOVED, placingMoveHandler);
			hallPane.removeEventFilter(MouseEvent.MOUSE_CLICKED, placingClickHandler);
		}
	}

	/** Farbzuordnung für den Saalplan - gehört hierher in die UI-Schicht, nicht ins Modell. */
	private static Color seatColorFor(SeatStatus status) {
		return switch (status) {
		case FREE -> UiColors.SEAT_FREE.getFxColor();
		case RESERVED -> UiColors.SEAT_RESEREVED.getFxColor();
		case SOLD -> UiColors.SEAT_SOLD.getFxColor();
		case BLOCKED -> UiColors.SEAT_BLOCKED.getFxColor();
		};
	}

	private void styleSeatNode(Seat seat, Circle seatCircle) {
		seatCircle.setFill(seatColorFor(seat.getStatus()));

		boolean matchesQuery = matchesQuery(seat);
		boolean searchActive = !currentQuery.isEmpty();
		seatCircle.setOpacity(matchesQuery ? 1.0 : 0.25);
		boolean selected = selectedSeats.contains(seat);
		seatCircle.setStroke(selected ? SELECTED_SEAT_STROKE : searchActive && matchesQuery ? Color.DODGERBLUE : Color.BLACK);
		seatCircle.setStrokeWidth(selected ? 4 : searchActive && matchesQuery ? 3 : 1);
		seatCircle.getStyleClass().add(SEAT_STYLE_CLASS);
		seatNodes.put(seatCircle, seat);

		Tooltip.install(seatCircle, new Tooltip("Sitz " + seat.getSeatNumber() + " (" + seat.getStatus().getDisplayName() + ")"));
		seatCircle.setOnMouseClicked(event -> {
			if (event.getButton() != MouseButton.PRIMARY || !event.isStillSincePress()) {
				return;
			}
			event.consume();
			if (editMode && isDialogOpen()) {
				return; // Ein zweiter Dialog würde den offenen samt Eingaben ersetzen: erst Anwenden oder Abbrechen
			}
			if (editMode && event.isShortcutDown()) {
				toggleSelection(seat);
				return;
			}
			Window window = seatCircle.getScene().getWindow();
			openSeatDetailPopup(seat, window, event.getScreenX() + 12, event.getScreenY() + 12);
		});
	}

	private boolean matchesQuery(Seat seat) {
		if (currentQuery.isEmpty()) {
			return true;
		}

		String lastName = seat.getLastName();
		String firstName = seat.getFirstName();
		String comment = seat.getComment();

		return (lastName != null && lastName.toLowerCase().contains(currentQuery)) || (firstName != null && firstName.toLowerCase().contains(currentQuery))
				|| (comment != null && comment.toLowerCase().contains(currentQuery));
	}

	/**
	 * Öffnet ein Popup mit den Kartendetails des angeklickten Sitzes direkt über
	 * der Saalübersicht. Änderungen gelten erst mit "Anwenden"; danach wird die
	 * Saalübersicht neu gezeichnet (Farbe, Tooltip etc.).
	 */
	private void openSeatDetailPopup(Seat seat, Window window, double screenX, double screenY) {
		closePopup();
		SeatDetailPopup dialog = new SeatDetailPopup(List.of(seat), editMode, this::renderHall);
		activePopup = dialog.popup();
		dialog.show(window, screenX, screenY);
	}

	/**
	 * Öffnet ein Popup mit den Tisch-Stammdaten (Nummer, Kategorie, Beschreibung,
	 * Maße) sowie einer Möglichkeit, eine manuell gesetzte Position wieder auf
	 * die automatische Anordnung zurückzusetzen.
	 */
	private void openTableDetailPopup(Table table, Window window, double screenX, double screenY) {
		closePopup();
		activePopup = buildTableDetailPopup(table);
		showDetailPopup(activePopup, window, screenX, screenY);
	}

	private void closePopup() {
		if (activePopup != null) {
			Popup popupToClose = activePopup;
			activePopup = null;
			popupToClose.setOnHidden(null);
			popupToClose.hide();
		}
	}

	/**
	 * Gemeinsame Bausteine für die Sitz- und Tisch-Detail-Popups, damit beide
	 * dasselbe Erscheinungsbild teilen und nicht unabhängig voneinander gepflegt
	 * werden müssen.
	 */
	static final String DETAIL_POPUP_STYLE = "-fx-background-color: white; -fx-border-color: #999999; -fx-border-width: 1; " + "-fx-padding: 12; -fx-background-radius: 4; -fx-border-radius: 4;";

	static final String APP_STYLESHEET = HallOverviewController.class.getResource("/de/eltviller_carneval_verein/karten/ui/style.css").toExternalForm();

	/**
	 * Zeigt das Popup an und hängt danach das App-Stylesheet an dessen (erst beim
	 * Anzeigen erzeugte) eigene Szene, damit Buttons, TextFields, ComboBoxen usw.
	 * im Popup genauso aussehen wie im Rest der App. Ein Popup bekommt sonst keine
	 * Stylesheets von der Haupt-Szene mit, da es eine eigenständige Szene ist.
	 */
	private void showDetailPopup(Popup popup, Window window, double screenX, double screenY) {
		popup.show(window, screenX, screenY);
		popup.getScene().getStylesheets().add(APP_STYLESHEET);
	}

	private Popup createDetailPopup() {
		Popup popup = new Popup();
		popup.setAutoHide(true);
		popup.setHideOnEscape(true);
		return popup;
	}

	private GridPane createDetailGrid() {
		GridPane grid = new GridPane();
		grid.setHgap(8);
		grid.setVgap(6);
		grid.setStyle(DETAIL_POPUP_STYLE);
		return grid;
	}

	private Label createHeaderLabel(String text) {
		Label header = new Label(text);
		header.setStyle("-fx-font-weight: bold;");
		return header;
	}

	/** Knopfleiste des Tisch-Dialogs: "Abbrechen" und "Anwenden" im Bearbeitungsmodus, sonst nur "Schließen". */
	private void addTableButtonRow(GridPane grid, Popup popup, int row, BooleanSupplier applyEdits) {
		HBox buttonBar = new HBox(8);
		buttonBar.setAlignment(Pos.CENTER_RIGHT);
		if (editMode) {
			Button cancelButton = new Button("Abbrechen");
			cancelButton.getStyleClass().add("button-default");
			cancelButton.setOnAction(event -> popup.hide());
			Button applyButton = new Button("Anwenden");
			applyButton.getStyleClass().add("button-primary");
			applyButton.setOnAction(event -> {
				if (applyEdits.getAsBoolean()) {
					popup.hide();
				}
			});
			buttonBar.getChildren().addAll(cancelButton, applyButton);
		} else {
			Button closeButton = new Button("Schließen");
			closeButton.getStyleClass().add("button-primary");
			closeButton.setOnAction(event -> popup.hide());
			buttonBar.getChildren().add(closeButton);
		}
		grid.add(buttonBar, 0, row, 2, 1);
	}

	/** Hängt das Grid in das Popup ein und sorgt dafür, dass die Saalübersicht beim Schließen neu gezeichnet wird. */
	private Popup finalizePopup(Popup popup, GridPane grid) {
		popup.getContent().add(grid);
		popup.setOnHidden(event -> renderHall());
		return popup;
	}

	/**
	 * Tisch-Dialog: Die Eingaben gelten erst mit "Anwenden" ("Abbrechen" und Esc verwerfen sie), wie beim Sitz-Dialog. Ist
	 * eine Eingabe abgelehnt worden (z. B. "abc" in der Breite), wird nichts übernommen und der Dialog bleibt offen.
	 */
	private Popup buildTableDetailPopup(Table table) {
		Popup popup = createDetailPopup();
		popup.setAutoHide(!editMode); // Mit Eingaben im Dialog soll ein Klick daneben sie nicht verwerfen
		GridPane grid = createDetailGrid();
		int row = 0;

		grid.add(createHeaderLabel("Tisch " + table.getTableNumber()), 0, row++, 2, 1);

		TextField categoryField = new TextField(table.getCategory() != null ? table.getCategory() : "");
		categoryField.setPromptText("Kategorie");
		categoryField.setDisable(!editMode);
		grid.addRow(row++, new Label("Kategorie:"), categoryField);

		TextField descField = new TextField(table.getDesc() != null ? table.getDesc() : "");
		descField.setPromptText("Beschreibung");
		descField.setDisable(!editMode);
		grid.addRow(row++, new Label("Beschreibung:"), descField);

		Spinner<Double> widthSpinner = new Spinner<>();
		widthSpinner.setValueFactory(SpinnerInput.doubleFactory(table.getWidth(), 5));
		widthSpinner.setEditable(true);
		SpinnerInput.install(widthSpinner, "Breite", () -> EntityLabels.table(table));
		widthSpinner.setPrefWidth(100);
		widthSpinner.setDisable(!editMode);
		grid.addRow(row++, new Label("Breite:"), widthSpinner);

		Spinner<Double> heightSpinner = new Spinner<>();
		heightSpinner.setValueFactory(SpinnerInput.doubleFactory(table.getHeight(), 5));
		heightSpinner.setEditable(true);
		SpinnerInput.install(heightSpinner, "Höhe", () -> EntityLabels.table(table));
		heightSpinner.setPrefWidth(100);
		heightSpinner.setDisable(!editMode);
		grid.addRow(row++, new Label("Höhe:"), heightSpinner);

		Spinner<Double> posXSpinner = new Spinner<>();
		posXSpinner.setValueFactory(SpinnerInput.doubleFactory(table.getPosX(), 5));
		posXSpinner.setEditable(true);
		SpinnerInput.install(posXSpinner, "Position X", () -> EntityLabels.table(table));
		posXSpinner.setPrefWidth(100);
		posXSpinner.setDisable(!editMode);
		grid.addRow(row++, new Label("PosX:"), posXSpinner);

		Spinner<Double> posYSpinner = new Spinner<>();
		posYSpinner.setValueFactory(SpinnerInput.doubleFactory(table.getPosY(), 5));
		posYSpinner.setEditable(true);
		SpinnerInput.install(posYSpinner, "Position Y", () -> EntityLabels.table(table));
		posYSpinner.setPrefWidth(100);
		posYSpinner.setDisable(!editMode);
		grid.addRow(row++, new Label("PosY:"), posYSpinner);

		// Übernimmt die Eingaben ins Modell (nur geänderte Felder). Der Klick auf einen Knopf nimmt einem Spinner den Fokus und
		// löst eine Ablehnung aus: Dann gilt nichts davon, und es wird nichts übernommen (false).
		BooleanSupplier applyEdits = () -> {
			if (SpinnerInput.blockSaveIfScreenInputRejected(widthSpinner)) {
				return false;
			}
			if (!editMode) {
				return true;
			}
			if (!categoryField.getText().equals(Objects.toString(table.getCategory(), ""))) {
				table.setCategory(categoryField.getText());
			}
			if (!descField.getText().equals(Objects.toString(table.getDesc(), ""))) {
				table.setDesc(descField.getText());
			}
			Double width = widthSpinner.getValue();
			if (width != null && Double.compare(width, table.getWidth()) != 0) {
				table.setWidth(width);
			}
			Double height = heightSpinner.getValue();
			if (height != null && Double.compare(height, table.getHeight()) != 0) {
				table.setHeight(height);
			}
			Double x = posXSpinner.getValue();
			if (x != null && Double.compare(x, table.getPosX()) != 0) {
				table.setPosX(x);
				table.setManualPos(true);
			}
			Double y = posYSpinner.getValue();
			if (y != null && Double.compare(y, table.getPosY()) != 0) {
				table.setPosY(y);
				table.setManualPos(true);
			}
			return true;
		};

		Label positionLabel = new Label(table.isManualPos() ? "Position: manuell gesetzt" : "Position: automatisch");
		grid.add(positionLabel, 0, row++, 2, 1);

		Button resetPositionButton = new Button("Position zurücksetzen");
		resetPositionButton.setDisable(!editMode || !table.isManualPos());
		resetPositionButton.getStyleClass().add("button-danger");
		resetPositionButton.setOnAction(event -> {
			if (!applyEdits.getAsBoolean()) {
				return;
			}
			table.setManualPos(false);
			popup.hide();
		});

		Button placeButton = new Button("Position festlegen");
		placeButton.setDisable(!editMode);
		placeButton.setOnAction(event -> {
			if (!applyEdits.getAsBoolean()) {
				return;
			}
			popup.hide();
			startPlacingTable(table);
		});

		HBox positionButtonRow = new HBox(8, resetPositionButton, placeButton);
		grid.add(positionButtonRow, 0, row++, 2, 1);

		addTableButtonRow(grid, popup, row++, applyEdits);

		return finalizePopup(popup, grid);
	}

	private void applyEditMode() {
		closePopup();
		cancelPlacingTable();
		selectedSeats.clear(); // Die Mehrfachauswahl gibt es nur im Bearbeitungsmodus
		renderHall();
	}

	/** Die Auswahl im Saalplan und der sichtbare Ausschnitt gehen in den gemeinsamen Verkaufszustand. */
	@Override
	public void setSalesState(SalesViewState state) {
		selectedSeats = state.selectedSeats();
		viewportPane.visibleHallRectProperty().addListener((obs, oldRect, newRect) -> state.visibleHallRectProperty().set(newRect));
		state.visibleHallRectProperty().set(viewportPane.visibleHallRectProperty().get());
	}

	@Override
	public void setEvent(Event event) {
		closePopup();
		cancelPlacingTable();
		selectedSeats.clear();
		this.selectedEvent = event;
		selectedPres = null;
		hallPane.getChildren().clear();
		seatNodes.clear();
		updateHint();
	}

	@Override
	public void setPresentation(Presentation presentation) {
		closePopup();
		cancelPlacingTable();
		selectedSeats.clear();
		this.selectedPres = presentation;
		if (selectedPres != null) {
			this.selectedEvent = presentation.getParent();
			renderHall();
			Hall hall = hallRepository.findById(selectedPres.getHallId());
			Rectangle2D hallRect = HallViewport.hallRect(hall);
			if (hallRect != null && !hall.getId().equals(fittedHallId)) {
				fittedHallId = hall.getId();
				viewportPane.fit(hallRect, HallViewport.HALL_FIT_MARGIN);
			}
		} else {
			// "Alle Vorstellungen": Der Saalplan zeigt immer genau eine Vorstellung, das Event bleibt gewählt
			hallPane.getChildren().clear();
			seatNodes.clear();
		}
		updateHint();
	}

	/** Ohne gewählte Vorstellung bleibt der Plan leer: Ein Hinweis sagt, was zu tun ist. */
	private void updateHint() {
		lblHint.setText(selectedEvent == null ? "Bitte wähle oben ein Event und eine Vorstellung." : "Die Saalansicht zeigt eine einzelne Vorstellung. Bitte wähle oben eine Vorstellung.");
		lblHint.setVisible(selectedPres == null);
	}

	@Override
	public void save() {
		// Aktuellen Stand speichern
		if (selectedEvent != null) {
			EventSaver.save(selectedEvent);
		}
	}

	@Override
	public void filter(String query) {
		this.currentQuery = (query == null) ? "" : query.toLowerCase().trim();
		selectedSeats.removeIf(seat -> !matchesQuery(seat)); // Ausgeblendete Sitze bleiben nicht ausgewählt
		renderHall();
	}

	@Override
	public void setEditMode(boolean enabled) {
		editMode = enabled;
		applyEditMode();
	}
}
