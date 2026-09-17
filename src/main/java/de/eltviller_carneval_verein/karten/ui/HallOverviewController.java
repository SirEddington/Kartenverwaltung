package de.eltviller_carneval_verein.karten.ui;

import java.util.List;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.HallObject;
import de.eltviller_carneval_verein.karten.model.PaymentStatus;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.Table;
import de.eltviller_carneval_verein.karten.repository.JsonTicketRepository;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.Shape;
import javafx.scene.text.Text;
import javafx.stage.Popup;
import javafx.stage.Window;
import javafx.util.StringConverter;

public class HallOverviewController implements ContentController {

	private static final double MIN_SCALE = 0.2;
	private static final double MAX_SCALE = 4.0;
	private static final double ZOOM_FACTOR_PER_NOTCH = 1.1;

	private final JsonTicketRepository repository = JsonTicketRepository.getInstance();
	private Event selectedEvent;
	private Presentation selectedPres;
	private boolean editMode = false;
	private String currentQuery = "";

	private double dragAnchorSceneX;
	private double dragAnchorSceneY;
	private double dragAnchorTranslateX;
	private double dragAnchorTranslateY;

	private Popup activePopup;

	private Table placingTable;
	private final EventHandler<MouseEvent> placingMoveHandler = this::handlePlacingMouseMoved;
	private final EventHandler<MouseEvent> placingClickHandler = this::handlePlacingMouseClicked;

	@FXML
	private StackPane viewportPane;

	@FXML
	private Pane hallPane;

	@FXML
	public void initialize() {
		Rectangle clip = new Rectangle();
		clip.widthProperty().bind(viewportPane.widthProperty());
		clip.heightProperty().bind(viewportPane.heightProperty());
		viewportPane.setClip(clip);

		viewportPane.setOnScroll(this::handleScroll);
		viewportPane.setOnMousePressed(this::handleDragStart);
		viewportPane.setOnMouseDragged(this::handleDrag);
	}

	/**
	 * Zoomt zur Mausposition (wie bei Google Maps), indem die Verschiebung so
	 * nachgerechnet wird, dass der Punkt unter dem Mauszeiger fix bleibt.
	 */
	private void handleScroll(ScrollEvent event) {
		event.consume();
		if (event.getDeltaY() == 0) {
			return;
		}

		double oldScale = hallPane.getScaleX();
		double zoomFactor = event.getDeltaY() > 0 ? ZOOM_FACTOR_PER_NOTCH : 1 / ZOOM_FACTOR_PER_NOTCH;
		double newScale = clamp(oldScale * zoomFactor, MIN_SCALE, MAX_SCALE);
		if (newScale == oldScale) {
			return;
		}

		Point2D parentPoint = viewportPane.sceneToLocal(event.getSceneX(), event.getSceneY());
		Point2D localPoint = hallPane.sceneToLocal(event.getSceneX(), event.getSceneY());

		Bounds layoutBounds = hallPane.getLayoutBounds();
		double pivotX = layoutBounds.getWidth() / 2.0;
		double pivotY = layoutBounds.getHeight() / 2.0;

		hallPane.setScaleX(newScale);
		hallPane.setScaleY(newScale);
		hallPane.setTranslateX(parentPoint.getX() - pivotX - newScale * (localPoint.getX() - pivotX));
		hallPane.setTranslateY(parentPoint.getY() - pivotY - newScale * (localPoint.getY() - pivotY));
	}

	private void handleDragStart(MouseEvent event) {
		dragAnchorSceneX = event.getSceneX();
		dragAnchorSceneY = event.getSceneY();
		dragAnchorTranslateX = hallPane.getTranslateX();
		dragAnchorTranslateY = hallPane.getTranslateY();
	}

	private void handleDrag(MouseEvent event) {
		hallPane.setTranslateX(dragAnchorTranslateX + (event.getSceneX() - dragAnchorSceneX));
		hallPane.setTranslateY(dragAnchorTranslateY + (event.getSceneY() - dragAnchorSceneY));
	}

	private double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}

	public void renderHall() {

		hallPane.getChildren().clear();

		if (selectedPres == null) {
			return;
		}

		// 1. Automatische Positionierung für Tische & Stühle ohne manuelle Position anwenden
		applyDefaultPositions();

		// 2. Zeichnen der Hallen-Objekte
		for (HallObject hallObject : selectedPres.getHallObjects()) {
			drawHallObject(hallObject);
		}

		// 3. Zeichnen der Tische und Stühle
		for (Table table : selectedPres.getTables()) {
			drawTable(table);
		}
	}

	/**
	 * Berechnet die Standardpositionen für Tische und Stühle,
	 * sofern diese noch nicht manuell verschoben wurden.
	 * Die Abstände ergeben sich aus der tatsächlichen Tisch- und Stuhlgröße,
	 * damit sich Tische (und ihre Stühle) nie überschneiden und direkt
	 * aneinander gesetzt werden.
	 */
	private void applyDefaultPositions() {
		List<Table> tables = selectedPres.getTables();
		if (tables == null || tables.isEmpty()) {
			return;
		}

		int tablesPerColumn = 7;// selectedPres.getTableRows(); // Max. Tische pro Spalte
		double startX = 60; // Start-X im Pane
		double startY = 40; // Start-Y im Pane
		double defaultWidth = 70; // Standard-Tischbreite
		double defaultHeight = 300; // Standard-Tischhöhe
		double tableRowGap = 0; // Sichtbarer Abstand zwischen benachbarten Tischen
		double tableColGap = 20; // Sichtbarer Abstand zwischen benachbarten Tischen

		// Fallback für Tischgrößen, falls diese 0 sind (muss vor der Breitenberechnung stehen)
		for (Table table : tables) {
			if (table.getWidth() <= 0)
				table.setWidth(defaultWidth);
			if (table.getHeight() <= 0)
				table.setHeight(defaultHeight);
		}

		// Breiteste Tisch-Stuhl-Kombination je Spalte ermitteln, damit die nächste
		// Spalte erst dahinter beginnt
		int columnCount = (tables.size() - 1) / tablesPerColumn + 1;
		double[] columnFootprint = new double[columnCount];
		for (int i = 0; i < tables.size(); i++) {
			int col = i / tablesPerColumn;
			double footprint = tables.get(i).getWidth() + 2 * getSeatMargin(tables.get(i));
			columnFootprint[col] = Math.max(columnFootprint[col], footprint);
		}

		double[] columnX = new double[columnCount];
		columnX[0] = startX;
		for (int col = 1; col < columnCount; col++) {
			columnX[col] = columnX[col - 1] + columnFootprint[col - 1] + tableColGap;
		}

		double[] rowCursorY = new double[columnCount];
		for (int col = 0; col < columnCount; col++) {
			rowCursorY[col] = startY;
		}

		for (int i = 0; i < tables.size(); i++) {
			Table table = tables.get(i);
			int col = i / tablesPerColumn;

			// --- TISCH POSITIONIERUNG ---
			if (!table.isManualPos()) {
				table.setPosX(columnX[col] + getSeatMargin(table));
				table.setPosY(rowCursorY[col]);
			}
			rowCursorY[col] += table.getHeight() + tableRowGap;

			// --- STUHL POSITIONIERUNG ---
			if (table.getSeats() != null && !table.getSeats().isEmpty()) {
				applyDefaultSeatPositions(table);
			}
		}
	}

	/**
	 * Zusätzlicher Platzbedarf links/rechts eines Tischs durch die dort
	 * platzierten Stühle (siehe applyDefaultSeatPositions), damit Stühle
	 * benachbarter Tische sich nicht überschneiden.
	 */
	private double getSeatMargin(Table table) {
		List<Seat> seats = table.getSeats();
		if (seats == null || seats.isEmpty()) {
			return 0;
		}
		double maxSeatRadius = seats.stream().mapToDouble(Seat::getWidth).max().orElse(0) / 2.0;
		return 15 + maxSeatRadius; // 15px Abstand, siehe applyDefaultSeatPositions bzw. drawSeat
	}

	/**
	 * Platziert die Stühle paarweise links und rechts am Tisch.
	 */
	private void applyDefaultSeatPositions(Table table) {
		List<Seat> seats = table.getSeats();
		int totalSeats = seats.size();

		// Anzahl der Reihen (Paare) am Tisch
		int rows = (int) Math.ceil(totalSeats / 2.0);

		double marginY = 20; // Abstand der äußeren Stühle zur Ober-/Unterkante des Tischs
		double usableHeight = Math.max(table.getHeight() - (2 * marginY), 20);
		double stepY = rows > 1 ? usableHeight / (rows - 1) : 0;

		for (int j = 0; j < totalSeats; j++) {
			Seat seat = seats.get(j);

			if (!seat.isManualPos()) {
				boolean isLeft = (j % 2 == 0); // Index 0, 2, 4... -> Links | Index 1, 3, 5... -> Rechts
				int rowIndex = j / 2; // 0, 0, 1, 1, 2, 2...

				// X-Position: Links oder Rechts vom Tisch mit 15px Abstand
				double posX = isLeft ? (table.getPosX() - 15) : (table.getPosX() + table.getWidth() + 15);

				// Y-Position: Bei 1 Stuhl-Paar zentriert, sonst gleichmäßig verteilt
				double posY = (rows == 1) ? (table.getPosY() + (table.getHeight() / 2.0)) : (table.getPosY() + marginY + (rowIndex * stepY));

				seat.setPosX(posX);
				seat.setPosY(posY);
			}
		}
	}

	private void drawTable(Table table) {
		Group tableGroup = new Group();

		Node tableShapeNode = createTableNode(table);
		tableGroup.getChildren().add(tableShapeNode);

		if (table.getSeats() != null) {
			for (Seat seat : table.getSeats()) {
				tableGroup.getChildren().add(createSeatNode(seat));
			}
		}

		setupTableClickHandler(tableShapeNode, table);

		hallPane.getChildren().add(tableGroup);
	}

	private Node createTableNode(Table table) {
		Rectangle tableShape = new Rectangle(table.getPosX(), table.getPosY(), table.getWidth(), table.getHeight());
		tableShape.setArcWidth(10);
		tableShape.setArcHeight(10);

		tableShape.setFill(Color.LIGHTGRAY);
		tableShape.setStroke(Color.DARKGRAY);

		Text tableLabel = new Text("Tisch " + table.getTableNumber());
		tableLabel.setTextOrigin(VPos.CENTER);
		tableLabel.setX(table.getPosX() + table.getWidth() / 2.0 - tableLabel.getLayoutBounds().getWidth() / 2.0);
		tableLabel.setY(table.getPosY() + table.getHeight() / 2.0);

		Group tableShapeNode = new Group(tableShape, tableLabel);
		tableShapeNode.setCursor(Cursor.HAND);
		return tableShapeNode;
	}

	/**
	 * Ein Klick auf einen Tisch öffnet immer das Tisch-Detail-Popup (in beiden
	 * Modi; im Anzeigemodus sind die Felder dort nur lesbar). Das Verschieben
	 * der Position passiert nicht mehr per Drag direkt am Tisch, sondern über
	 * den Knopf "Position festlegen" im Popup (siehe {@link #startPlacingTable}).
	 */
	private void setupTableClickHandler(Node tableShapeNode, Table table) {
		tableShapeNode.setOnMouseClicked(event -> {
			if (!event.isStillSincePress()) {
				return; // z.B. nach einem Schwenk/Zoom der Ansicht, sicherheitshalber ignorieren
			}
			event.consume();
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
		if (placingTable == null) {
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

	private Node createSeatNode(Seat seat) {
		Circle seatCircle = new Circle(seat.getPosX(), seat.getPosY(), seat.getWidth() / 2.0);
		seatCircle.setFill(seat.getStatus().getSeatColor().getFxColor());

		boolean matchesQuery = matchesQuery(seat);
		boolean searchActive = !currentQuery.isEmpty();
		seatCircle.setOpacity(matchesQuery ? 1.0 : 0.25);
		seatCircle.setStroke(searchActive && matchesQuery ? Color.DODGERBLUE : Color.BLACK);
		seatCircle.setStrokeWidth(searchActive && matchesQuery ? 3 : 1);

		Tooltip.install(seatCircle, new Tooltip("Sitz " + seat.getSeatNumber() + " (" + seat.getStatus().getDisplayName() + ")"));
		seatCircle.setOnMouseClicked(event -> {
			event.consume();
			Window window = seatCircle.getScene().getWindow();
			openSeatDetailPopup(seat, window, event.getScreenX() + 12, event.getScreenY() + 12);
		});

		return seatCircle;
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
	 * der Saalübersicht. Änderungen werden sofort ins Modell übernommen; erst
	 * beim Schließen des Popups wird die Saalübersicht neu gezeichnet (Farbe,
	 * Tooltip etc.).
	 */
	private void openSeatDetailPopup(Seat seat, Window window, double screenX, double screenY) {
		closePopup();
		activePopup = buildSeatDetailPopup(seat);
		showDetailPopup(activePopup, window, screenX, screenY);
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
	private static final String DETAIL_POPUP_STYLE = "-fx-background-color: white; -fx-border-color: #999999; -fx-border-width: 1; " + "-fx-padding: 12; -fx-background-radius: 4; -fx-border-radius: 4;";

	private static final String APP_STYLESHEET = HallOverviewController.class.getResource("/de/eltviller_carneval_verein/karten/ui/style.css").toExternalForm();

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

	private void addCloseButtonRow(GridPane grid, Popup popup, int row) {
		Button closeButton = new Button("Anwenden");
		closeButton.getStyleClass().add("button-primary");
		closeButton.setOnAction(event -> popup.hide());
		HBox buttonBar = new HBox(closeButton);
		buttonBar.setAlignment(Pos.CENTER_RIGHT);
		grid.add(buttonBar, 0, row, 2, 1);
	}

	/** Hängt das Grid in das Popup ein und sorgt dafür, dass die Saalübersicht beim Schließen neu gezeichnet wird. */
	private Popup finalizePopup(Popup popup, GridPane grid) {
		popup.getContent().add(grid);
		popup.setOnHidden(event -> renderHall());
		return popup;
	}

	private Popup buildSeatDetailPopup(Seat seat) {
		Popup popup = createDetailPopup();
		GridPane grid = createDetailGrid();
		int row = 0;

		grid.add(createHeaderLabel("Tisch " + seat.getParent().getTableNumber() + " · Sitz " + seat.getSeatNumber()), 0, row++, 2, 1);

		TextField lastNameField = new TextField(seat.getLastName() != null ? seat.getLastName() : "");
		lastNameField.setPromptText("Nachname");
		lastNameField.setDisable(!editMode);
		grid.addRow(row++, new Label("Nachname:"), lastNameField);

		TextField firstNameField = new TextField(seat.getFirstName() != null ? seat.getFirstName() : "");
		firstNameField.setPromptText("Vorname");
		firstNameField.setDisable(!editMode);
		grid.addRow(row++, new Label("Vorname:"), firstNameField);

		ComboBox<PaymentStatus> paymentCombo = new ComboBox<>();
		paymentCombo.getItems().setAll(PaymentStatus.values());
		paymentCombo.setValue(seat.getPaymentStatus());
		paymentCombo.setConverter(new StringConverter<PaymentStatus>() {
			@Override
			public String toString(PaymentStatus status) {
				return status == null ? "" : status.getDisplayName();
			}

			@Override
			public PaymentStatus fromString(String string) {
				return null; // Bei fixer ComboBox-Auswahl nicht erforderlich
			}
		});
		paymentCombo.setDisable(!editMode);
		grid.addRow(row++, new Label("Zahlung:"), paymentCombo);

		Spinner<Double> priceSpinner = new Spinner<>();
		priceSpinner.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(0.0, 1000.0, seat.getPriceDouble(), 0.5));
		priceSpinner.setEditable(true);
		priceSpinner.setPrefWidth(100);
		priceSpinner.setDisable(!editMode);
		grid.addRow(row++, new Label("Preis (€):"), priceSpinner);

		CheckBox collectedCheck = new CheckBox("Abgeholt");
		collectedCheck.setSelected(seat.isCollected());
		collectedCheck.setDisable(!editMode);

		CheckBox wheelchairCheck = new CheckBox("Rollstuhlgeeignet");
		wheelchairCheck.setSelected(seat.isWheelchairAccessible());
		wheelchairCheck.setDisable(!editMode);

		HBox checkboxRow = new HBox(12, collectedCheck, wheelchairCheck);
		grid.add(checkboxRow, 0, row++, 2, 1);

		TextField commentField = new TextField(seat.getComment() != null ? seat.getComment() : "");
		commentField.setPromptText("Kommentar");
		commentField.setDisable(!editMode);
		grid.addRow(row++, new Label("Kommentar:"), commentField);

		addCloseButtonRow(grid, popup, row++);

		// Änderungen sofort ins Modell übernehmen; Anzeige aktualisiert sich beim Schließen (siehe unten)
		lastNameField.textProperty().addListener((obs, oldVal, newVal) -> seat.setLastName(newVal));
		firstNameField.textProperty().addListener((obs, oldVal, newVal) -> seat.setFirstName(newVal));
		paymentCombo.valueProperty().addListener((obs, oldVal, newVal) -> seat.setPaymentStatus(newVal));
		priceSpinner.valueProperty().addListener((obs, oldVal, newVal) -> {
			if (newVal != null) {
				seat.setPriceDouble(newVal);
			}
		});
		collectedCheck.selectedProperty().addListener((obs, oldVal, newVal) -> seat.setCollected(newVal));
		wheelchairCheck.selectedProperty().addListener((obs, oldVal, newVal) -> seat.setWheelchairAccessible(newVal));
		commentField.textProperty().addListener((obs, oldVal, newVal) -> seat.setComment(newVal));

		return finalizePopup(popup, grid);
	}

	private Popup buildTableDetailPopup(Table table) {
		Popup popup = createDetailPopup();
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
		widthSpinner.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(10.0, 1000.0, table.getWidth(), 5));
		widthSpinner.setEditable(true);
		widthSpinner.setPrefWidth(100);
		widthSpinner.setDisable(!editMode);
		grid.addRow(row++, new Label("Breite:"), widthSpinner);

		Spinner<Double> heightSpinner = new Spinner<>();
		heightSpinner.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(10.0, 1000.0, table.getHeight(), 5));
		heightSpinner.setEditable(true);
		heightSpinner.setPrefWidth(100);
		heightSpinner.setDisable(!editMode);
		grid.addRow(row++, new Label("Höhe:"), heightSpinner);

		Spinner<Double> posXSpinner = new Spinner<>();
		posXSpinner.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(-10000.0, 10000.0, table.getPosX(), 5));
		posXSpinner.setEditable(true);
		posXSpinner.setPrefWidth(100);
		posXSpinner.setDisable(!editMode);
		grid.addRow(row++, new Label("PosX:"), posXSpinner);

		Spinner<Double> posYSpinner = new Spinner<>();
		posYSpinner.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(-10000.0, 10000.0, table.getPosY(), 5));
		posYSpinner.setEditable(true);
		posYSpinner.setPrefWidth(100);
		posYSpinner.setDisable(!editMode);
		grid.addRow(row++, new Label("PosY:"), posYSpinner);

		Label positionLabel = new Label(table.isManualPos() ? "Position: manuell gesetzt" : "Position: automatisch");
		grid.add(positionLabel, 0, row++, 2, 1);

		Button resetPositionButton = new Button("Position zurücksetzen");
		resetPositionButton.setDisable(!editMode || !table.isManualPos());
		resetPositionButton.getStyleClass().add("button-danger");
		resetPositionButton.setOnAction(event -> {
			table.setManualPos(false);
			popup.hide();
		});

		Button placeButton = new Button("Position festlegen");
		placeButton.setDisable(!editMode);
		placeButton.setOnAction(event -> {
			popup.hide();
			startPlacingTable(table);
		});

		HBox positionButtonRow = new HBox(8, resetPositionButton, placeButton);
		grid.add(positionButtonRow, 0, row++, 2, 1);

		addCloseButtonRow(grid, popup, row++);

		// Änderungen sofort ins Modell übernehmen; Anzeige aktualisiert sich beim Schließen (siehe unten)
		categoryField.textProperty().addListener((obs, oldVal, newVal) -> table.setCategory(newVal));
		descField.textProperty().addListener((obs, oldVal, newVal) -> table.setDesc(newVal));
		widthSpinner.valueProperty().addListener((obs, oldVal, newVal) -> {
			if (newVal != null) {
				table.setWidth(newVal);
			}
		});
		heightSpinner.valueProperty().addListener((obs, oldVal, newVal) -> {
			if (newVal != null) {
				table.setHeight(newVal);
			}
		});
		posXSpinner.valueProperty().addListener((obs, oldVal, newVal) -> {
			if (newVal != null) {
				table.setPosX(newVal);
				table.setManualPos(true);
			}
		});
		posYSpinner.valueProperty().addListener((obs, oldVal, newVal) -> {
			if (newVal != null) {
				table.setPosY(newVal);
				table.setManualPos(true);
			}
		});

		return finalizePopup(popup, grid);
	}

	private void drawHallObject(HallObject hallObject) {
		Node hallObjectNode = createHallObjectNode(hallObject);
		hallPane.getChildren().add(hallObjectNode);
	}

	private Node createHallObjectNode(HallObject hallObject) {
		// Java 21 Switch Expression für Shapes
		Shape hallObjectShape = switch (hallObject.getShape()) {
		case CIRCLE -> {
			double radius = hallObject.getWidth() / 2.0;
			yield new Circle(hallObject.getPosX() + radius, hallObject.getPosY() + radius, radius);
		}
		case RECTANGLE -> {
			Rectangle rect = new Rectangle(hallObject.getPosX(), hallObject.getPosY(), hallObject.getWidth(), hallObject.getHeight());
			rect.setArcWidth(10);
			rect.setArcHeight(10);
			yield rect;
		}
		};

		hallObjectShape.setFill(Color.LIGHTGRAY);
		hallObjectShape.setStroke(Color.DARKGRAY);

		Text hallObjectLabel = new Text(hallObject.getName());
		hallObjectLabel.setX(hallObject.getPosX() + 10);
		hallObjectLabel.setY(hallObject.getPosY() + 20);

		return new Group(hallObjectShape, hallObjectLabel);
	}

	private void applyEditMode() {
		closePopup();
		cancelPlacingTable();
		renderHall();
	}

	@Override
	public void setEvent(Event event) {
		closePopup();
		cancelPlacingTable();
		this.selectedEvent = event;
		selectedPres = null;
		hallPane.getChildren().clear();
	}

	@Override
	public void setPresentation(Presentation presentation) {
		closePopup();
		cancelPlacingTable();
		this.selectedPres = presentation;
		if (selectedPres != null) {
			this.selectedEvent = presentation.getParent();
			renderHall();
		} else {
			selectedEvent = null;
		}
	}

	@Override
	public void save() {
		// Aktuellen Stand speichern
		repository.saveEvent(selectedEvent);
	}

	@Override
	public void filter(String query) {
		this.currentQuery = (query == null) ? "" : query.toLowerCase().trim();
		renderHall();
	}

	@Override
	public void setEditMode(boolean enabled) {
		editMode = enabled;
		applyEditMode();
	}
}
