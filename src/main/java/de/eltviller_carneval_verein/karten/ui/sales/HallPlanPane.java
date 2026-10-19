package de.eltviller_carneval_verein.karten.ui.sales;

import java.util.HashMap;
import java.util.Map;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.HallObject;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.model.Table;
import de.eltviller_carneval_verein.karten.model.TableRow;
import de.eltviller_carneval_verein.karten.ui.UiColors;
import de.eltviller_carneval_verein.karten.ui.hall.HallObjectText;
import de.eltviller_carneval_verein.karten.ui.hall.PlanNodeFactory;
import de.eltviller_carneval_verein.karten.ui.hall.SeatingPlanLayout;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.Shape;

/**
 * Zeichenfläche für den Saalplan einer Vorstellung, gemeinsam für die Saalübersicht im Hauptfenster (interaktiv:
 * Zoom mit dem Mausrad, Verschieben mit der rechten Maustaste) und das Kundenfenster (nur Anzeige).
 *
 * <p>
 * Gezeichnet wird in Hallenkoordinaten (cm) auf {@link #contentPane()}; Zoom und Verschiebung wirken als Transformation
 * dieser Fläche. Reihen und Tische zeichnet {@link PlanNodeFactory} wie im Hallen-Editor; wie ein Sitz aussieht und
 * reagiert, bestimmt der {@link Decorator}.
 */
public class HallPlanPane extends StackPane {

	/** Rand für ausgewählte Sitze (Saalübersicht und Kundenfenster). */
	public static final Color SELECTED_SEAT_STROKE = UiColors.ECV_BLAU.getFxColor();

	private static final double MIN_SCALE = 0.05;
	private static final double MAX_SCALE = 4.0;
	private static final double ZOOM_FACTOR_PER_NOTCH = 1.1;

	/** Bestimmt Aussehen und Verhalten der gezeichneten Sitze (Füllung, Rand, Klick, Tooltip ...). */
	public interface Decorator {
		/**
		 * @param booking die Buchung der Vorstellung auf diesem Sitz
		 * @param seat    der Sitz der Bestuhlung
		 * @param node    der gezeichnete Sitz (ein {@link Shape})
		 */
		default void decorateSeat(Booking booking, Seat seat, Node node) {
		}
	}

	private final Pane contentPane = new Pane();
	// Hinweis anstelle der Bestuhlung (eingefroren oder keine gewählt); liegt über dem Plan und zoomt nicht mit
	private final Label planHint = new Label();
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
		planHint.getStyleClass().add("subheader-label");
		planHint.setMouseTransparent(true);
		planHint.setVisible(false);
		StackPane.setAlignment(planHint, Pos.TOP_CENTER);
		StackPane.setMargin(planHint, new Insets(12));
		getChildren().addAll(contentPane, planHint);

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
	 * dann die Reihen, Tische und Sitze der Bestuhlung so, wie sie gespeichert sind (angeordnet hat sie der
	 * Hallen-Editor; hier wird nichts berechnet). Koordinaten sind cm ab der oberen linken Hallenecke (0/0), wie im
	 * Hallen-Editor. Ohne Bestuhlung (eingefroren oder keine gewählt) erscheinen nur die Hallenobjekte und ein Hinweis
	 * ({@link SalesPlan#hint}).
	 *
	 * @param hall         die Halle der Vorstellung, {@code null} wenn keine zugeordnet ist
	 * @param plan         die Bestuhlung ({@link SalesPlan#of}), {@code null} ohne Bestuhlung
	 * @param presentation die Vorstellung, {@code null} leert die Fläche
	 * @param showRows     {@code false} lässt die Reihen (Linie mit Buchstabe) weg; Tische und Sitze bleiben
	 */
	public void render(Hall hall, SeatingPlan plan, Presentation presentation, boolean showRows, Decorator decorator) {
		contentPane.getChildren().clear();
		String hint = SalesPlan.hint(presentation, plan);
		planHint.setText(hint);
		planHint.setVisible(hint != null);
		if (presentation == null) {
			return;
		}

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

		if (plan != null) {
			drawPlan(plan, presentation, showRows, decorator);
		}
	}

	/**
	 * Reihen (falls gewünscht), dann Tische, dann Sitze (oben, damit sie anklickbar bleiben). Reihen und Tische nehmen
	 * keine Klicks.
	 */
	private void drawPlan(SeatingPlan plan, Presentation presentation, boolean showRows, Decorator decorator) {
		if (showRows) {
			for (TableRow row : plan.getRows()) {
				if (plan.getTablesOf(row).isEmpty()) {
					continue; // Eine leere Reihe hat keine Länge: nur ein Punkt ohne Bedeutung für den Verkauf
				}
				Node rowNode = PlanNodeFactory.rowNode(row, SeatingPlanLayout.rowLength(plan, row), false);
				rowNode.setMouseTransparent(true);
				contentPane.getChildren().add(rowNode);
			}
		}
		for (Table table : plan.getTables()) {
			Node tableNode = PlanNodeFactory.tableNode(table, false);
			tableNode.setMouseTransparent(true);
			contentPane.getChildren().add(tableNode);
		}
		Map<String, Booking> bookingBySeat = bookingsBySeat(presentation);
		for (Seat seat : plan.getSeats()) {
			Node seatNode = PlanNodeFactory.seatNode(seat);
			Booking booking = bookingBySeat.get(seat.getId());
			if (booking != null) {
				decorator.decorateSeat(booking, seat, seatNode);
			} else {
				// Nach dem Abgleich hat jeder Sitz eine Buchung; sonst bleibt der Sitz grau und ohne Verhalten
				seatNode.setOpacity(0.4);
				seatNode.setMouseTransparent(true);
			}
			contentPane.getChildren().add(seatNode);
		}
	}

	/** Die Buchungen mit Platz nach Sitz-Id; bei mehreren auf einem Sitz gilt die erste (wie im Abgleich). */
	private static Map<String, Booking> bookingsBySeat(Presentation presentation) {
		Map<String, Booking> bySeat = new HashMap<>();
		if (presentation.getBookings() != null) {
			for (Booking booking : presentation.getBookings()) {
				if (!booking.isUnassigned() && booking.getSeatId() != null) {
					bySeat.putIfAbsent(booking.getSeatId(), booking);
				}
			}
		}
		return bySeat;
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
