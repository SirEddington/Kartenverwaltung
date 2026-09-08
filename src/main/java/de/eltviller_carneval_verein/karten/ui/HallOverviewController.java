package de.eltviller_carneval_verein.karten.ui;

import java.util.List;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.HallObject;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.Table;
import de.eltviller_carneval_verein.karten.repository.JsonTicketRepository;
import javafx.fxml.FXML;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.geometry.VPos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.Shape;
import javafx.scene.text.Text;

public class HallOverviewController implements ContentController {

	private static final double MIN_SCALE = 0.2;
	private static final double MAX_SCALE = 4.0;
	private static final double ZOOM_FACTOR_PER_NOTCH = 1.1;

	private final JsonTicketRepository repository = new JsonTicketRepository();
	private Event selectedEvent;
	private Presentation selectedPres;
	private boolean editMode = false;

	private double dragAnchorSceneX;
	private double dragAnchorSceneY;
	private double dragAnchorTranslateX;
	private double dragAnchorTranslateY;

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
		double tableGap = 20; // Sichtbarer Abstand zwischen benachbarten Tischen

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
			columnX[col] = columnX[col - 1] + columnFootprint[col - 1] + tableGap;
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
			rowCursorY[col] += table.getHeight() + tableGap;

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
		Node tableNode = createTableNode(table);
		hallPane.getChildren().add(tableNode);

		if (table.getSeats() != null) {
			for (Seat seat : table.getSeats()) {
				drawSeat(seat);
			}
		}
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

		return new Group(tableShape, tableLabel);
	}

	private void drawSeat(Seat seat) {
		Circle seatCircle = new Circle(seat.getPosX(), seat.getPosY(), seat.getWidth() / 2.0);
		seatCircle.setFill(seat.getStatus().getSeatColor().getFxColor());
		seatCircle.setStroke(Color.BLACK);

		Tooltip.install(seatCircle, new Tooltip("Sitz " + seat.getSeatNumber() + " (" + seat.getStatus() + ")"));
		seatCircle.setOnMouseClicked(e -> handleSeatClick(seat));

		hallPane.getChildren().add(seatCircle);
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

	private void handleSeatClick(Seat seat) {
		System.out.println("Clicked seat: " + seat.getSeatNumber());
	}

	private void applyEditMode() {
		// ToDo
	}

	@Override
	public void setEvent(Event event) {
		this.selectedEvent = event;
		selectedPres = null;
	}

	@Override
	public void setPresentation(Presentation presentation) {
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
		// TODO Auto-generated method stub

	}

	@Override
	public void setEditMode(boolean enabled) {
		editMode = enabled;
		applyEditMode();
	}
}