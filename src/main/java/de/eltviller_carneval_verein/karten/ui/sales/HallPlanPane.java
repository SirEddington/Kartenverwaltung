package de.eltviller_carneval_verein.karten.ui.sales;

import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.HallObject;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.Table;
import de.eltviller_carneval_verein.karten.ui.UiColors;
import de.eltviller_carneval_verein.karten.ui.hall.HallObjectText;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.geometry.Rectangle2D;
import javafx.geometry.VPos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.Shape;
import javafx.scene.text.Text;

/**
 * Zeichenfläche für den Saalplan einer Vorstellung, gemeinsam für die Saalübersicht im Hauptfenster (interaktiv:
 * Zoom mit dem Mausrad, Verschieben mit der rechten Maustaste) und das Kundenfenster (nur Anzeige).
 *
 * <p>
 * Gezeichnet wird in Hallenkoordinaten (cm) auf {@link #contentPane()}; Zoom und Verschiebung wirken als Transformation
 * dieser Fläche. Wie ein Sitz oder Tisch aussieht und reagiert, bestimmt der {@link Decorator}.
 */
public class HallPlanPane extends StackPane {

	/** Rand für ausgewählte Sitze (Saalübersicht und Kundenfenster). */
	public static final Color SELECTED_SEAT_STROKE = UiColors.ECV_BLAU.getFxColor();

	private static final double MIN_SCALE = 0.05;
	private static final double MAX_SCALE = 4.0;
	private static final double ZOOM_FACTOR_PER_NOTCH = 1.1;

	/** Bestimmt Aussehen und Verhalten der gezeichneten Tische und Sitze (Füllung, Rand, Klick, Tooltip ...). */
	public interface Decorator {
		default void decorateTable(Table table, Node tableNode) {
		}

		default void decorateSeat(Seat seat, Circle seatNode) {
		}
	}

	private final Pane contentPane = new Pane();
	private final ReadOnlyObjectWrapper<Rectangle2D> visibleHallRect = new ReadOnlyObjectWrapper<>();

	private boolean interactive = true;
	private Rectangle2D fitTarget;
	private double fitMargin = 1.0;
	private boolean fitPending = false;
	private boolean viewUpdating = false;

	private double dragAnchorSceneX;
	private double dragAnchorSceneY;
	private double dragAnchorTranslateX;
	private double dragAnchorTranslateY;

	public HallPlanPane() {
		contentPane.setPrefSize(1200, 800);
		getChildren().add(contentPane);

		Rectangle clip = new Rectangle();
		clip.widthProperty().bind(widthProperty());
		clip.heightProperty().bind(heightProperty());
		setClip(clip);

		addEventHandler(ScrollEvent.SCROLL, this::handleScroll);
		addEventHandler(MouseEvent.MOUSE_PRESSED, this::handleDragStart);
		addEventHandler(MouseEvent.MOUSE_DRAGGED, this::handleDrag);

		contentPane.widthProperty().addListener((obs, oldVal, newVal) -> sizeChanged());
		contentPane.heightProperty().addListener((obs, oldVal, newVal) -> sizeChanged());
		contentPane.scaleXProperty().addListener((obs, oldVal, newVal) -> updateVisibleRect());
		contentPane.translateXProperty().addListener((obs, oldVal, newVal) -> updateVisibleRect());
		contentPane.translateYProperty().addListener((obs, oldVal, newVal) -> updateVisibleRect());
	}

	/** Die Zeichenfläche in Hallenkoordinaten (cm); ihre Kinder sind der gezeichnete Saalplan. */
	public Pane contentPane() {
		return contentPane;
	}

	/** Zoom (Mausrad) und Verschieben (rechte Maustaste) ein- oder ausschalten; Standard ist an. */
	public void setInteractive(boolean interactive) {
		this.interactive = interactive;
	}

	/**
	 * Der sichtbare Ausschnitt in Hallenkoordinaten (cm), {@code null} solange die Fläche keine Größe hat. Wird bei
	 * jedem Zoom, jeder Verschiebung und jeder Größenänderung neu gesetzt.
	 */
	public ReadOnlyObjectProperty<Rectangle2D> visibleHallRectProperty() {
		return visibleHallRect.getReadOnlyProperty();
	}

	/**
	 * Passt {@code rect} (Hallenkoordinaten) mit dem Faktor {@code margin} in die Fläche ein, sobald diese eine Größe
	 * hat (sofort, wenn schon vorhanden). Im interaktiven Modus bleibt der Zoom in den Grenzen der Bedienung.
	 */
	public void fit(Rectangle2D rect, double margin) {
		fitTarget = rect;
		fitMargin = margin;
		fitPending = true;
		applyFitIfPossible();
	}

	/** Grenzen der gezeichneten Inhalte in Hallenkoordinaten, {@code null} wenn nichts gezeichnet ist. */
	public Rectangle2D contentBounds() {
		double minX = Double.POSITIVE_INFINITY;
		double minY = Double.POSITIVE_INFINITY;
		double maxX = Double.NEGATIVE_INFINITY;
		double maxY = Double.NEGATIVE_INFINITY;
		for (Node child : contentPane.getChildren()) {
			Bounds bounds = child.getBoundsInParent();
			minX = Math.min(minX, bounds.getMinX());
			minY = Math.min(minY, bounds.getMinY());
			maxX = Math.max(maxX, bounds.getMaxX());
			maxY = Math.max(maxY, bounds.getMaxY());
		}
		if (minX > maxX || minY > maxY) {
			return null;
		}
		return new Rectangle2D(minX, minY, maxX - minX, maxY - minY);
	}

	/**
	 * Zeichnet den Saalplan der Vorstellung neu: Hallenumriss und Hallenobjekte (falls eine Halle zugeordnet ist),
	 * dann die Tische mit ihren Stühlen. Koordinaten sind cm ab der oberen linken Hallenecke (0/0), wie im
	 * Hallen-Editor. Tische und Stühle ohne manuelle Position ordnet {@link HallLayout} an.
	 *
	 * @param presentation die Vorstellung, {@code null} leert die Fläche
	 * @param hall         die Halle der Vorstellung, {@code null} wenn keine zugeordnet ist
	 */
	public void render(Presentation presentation, Hall hall, Decorator decorator) {
		contentPane.getChildren().clear();
		if (presentation == null) {
			return;
		}
		HallLayout.apply(presentation, hall);

		if (hall != null) {
			Rectangle2D hallRect = HallViewport.hallRect(hall);
			if (hallRect != null) {
				Rectangle outline = new Rectangle(0, 0, hallRect.getWidth(), hallRect.getHeight());
				outline.setFill(Color.WHITE);
				outline.setStroke(Color.GRAY);
				outline.getStrokeDashArray().addAll(8.0, 6.0);
				outline.setMouseTransparent(true);
				contentPane.getChildren().add(outline);
			}
			for (HallObject hallObject : hall.getHallObjects()) {
				contentPane.getChildren().add(createHallObjectNode(hallObject));
			}
		}

		for (Table table : presentation.getTables()) {
			drawTable(table, decorator);
		}
	}

	private void drawTable(Table table, Decorator decorator) {
		Group tableGroup = new Group();

		Node tableNode = createTableNode(table);
		tableGroup.getChildren().add(tableNode);

		if (table.getSeats() != null) {
			for (Seat seat : table.getSeats()) {
				Circle seatCircle = new Circle(seat.getPosX(), seat.getPosY(), seat.getWidth() / 2.0);
				decorator.decorateSeat(seat, seatCircle);
				tableGroup.getChildren().add(seatCircle);
			}
		}
		decorator.decorateTable(table, tableNode);

		contentPane.getChildren().add(tableGroup);
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

	private Node createHallObjectNode(HallObject hallObject) {
		// Java 21 Switch Expression für Shapes
		Shape hallObjectShape = switch (hallObject.getShape() != null ? hallObject.getShape() : de.eltviller_carneval_verein.karten.model.Shape.RECTANGLE) {
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

		hallObjectShape.setFill(parseHallObjectColor(hallObject.getColor()));
		hallObjectShape.setStroke(Color.DARKGRAY);

		return new Group(hallObjectShape, HallObjectText.create(hallObject));
	}

	private static Color parseHallObjectColor(String color) {
		if (color == null || color.isBlank()) {
			return Color.LIGHTGRAY;
		}
		try {
			return Color.web(color);
		} catch (IllegalArgumentException e) {
			return Color.LIGHTGRAY;
		}
	}

	private void sizeChanged() {
		if (fitPending) {
			applyFitIfPossible();
		}
		updateVisibleRect();
	}

	private void applyFitIfPossible() {
		double width = contentPane.getWidth();
		double height = contentPane.getHeight();
		if (fitTarget == null || width <= 0 || height <= 0) {
			return;
		}
		HallViewport.View view = HallViewport.fit(width, height, fitTarget, fitMargin);
		if (interactive) {
			double clamped = clamp(view.scale(), MIN_SCALE, MAX_SCALE);
			if (clamped != view.scale()) {
				view = HallViewport.centered(width, height, fitTarget, clamped);
			}
		}
		fitPending = false;
		applyView(view);
	}

	/**
	 * Setzt Zoom und Verschiebung zusammen und meldet den sichtbaren Ausschnitt einmal danach: Einzeln gesetzt, würden
	 * Zwischenwerte (neuer Zoom mit alter Verschiebung) als Ausschnitt gemeldet und vom Kundenfenster eingepasst.
	 */
	private void applyView(HallViewport.View view) {
		viewUpdating = true;
		try {
			contentPane.setScaleX(view.scale());
			contentPane.setScaleY(view.scale());
			contentPane.setTranslateX(view.translateX());
			contentPane.setTranslateY(view.translateY());
		} finally {
			viewUpdating = false;
		}
		updateVisibleRect();
	}

	private void updateVisibleRect() {
		if (viewUpdating) {
			return;
		}
		double width = contentPane.getWidth();
		double height = contentPane.getHeight();
		if (width <= 0 || height <= 0) {
			visibleHallRect.set(null);
			return;
		}
		HallViewport.View view = new HallViewport.View(contentPane.getScaleX(), contentPane.getTranslateX(), contentPane.getTranslateY());
		visibleHallRect.set(HallViewport.visibleRect(width, height, view));
	}

	/**
	 * Zoomt zur Mausposition (wie bei Google Maps), indem die Verschiebung so
	 * nachgerechnet wird, dass der Punkt unter dem Mauszeiger fix bleibt.
	 */
	private void handleScroll(ScrollEvent event) {
		if (!interactive) {
			return;
		}
		event.consume();
		if (event.getDeltaY() == 0) {
			return;
		}

		double oldScale = contentPane.getScaleX();
		double zoomFactor = event.getDeltaY() > 0 ? ZOOM_FACTOR_PER_NOTCH : 1 / ZOOM_FACTOR_PER_NOTCH;
		double newScale = clamp(oldScale * zoomFactor, MIN_SCALE, MAX_SCALE);
		if (newScale == oldScale) {
			return;
		}

		Point2D parentPoint = sceneToLocal(event.getSceneX(), event.getSceneY());
		Point2D localPoint = contentPane.sceneToLocal(event.getSceneX(), event.getSceneY());

		Bounds layoutBounds = contentPane.getLayoutBounds();
		double pivotX = layoutBounds.getWidth() / 2.0;
		double pivotY = layoutBounds.getHeight() / 2.0;

		applyView(new HallViewport.View(newScale, parentPoint.getX() - pivotX - newScale * (localPoint.getX() - pivotX),
				parentPoint.getY() - pivotY - newScale * (localPoint.getY() - pivotY)));
	}

	// Der Plan wird mit der rechten Maustaste verschoben; die linke Taste gehört den Klicks auf Tische und Sitze
	private void handleDragStart(MouseEvent event) {
		if (!interactive || event.getButton() != MouseButton.SECONDARY) {
			return;
		}
		dragAnchorSceneX = event.getSceneX();
		dragAnchorSceneY = event.getSceneY();
		dragAnchorTranslateX = contentPane.getTranslateX();
		dragAnchorTranslateY = contentPane.getTranslateY();
	}

	private void handleDrag(MouseEvent event) {
		if (!interactive || event.getButton() != MouseButton.SECONDARY) {
			return;
		}
		applyView(new HallViewport.View(contentPane.getScaleX(), dragAnchorTranslateX + (event.getSceneX() - dragAnchorSceneX),
				dragAnchorTranslateY + (event.getSceneY() - dragAnchorSceneY)));
	}

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}
}
