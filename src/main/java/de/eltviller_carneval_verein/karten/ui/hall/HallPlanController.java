package de.eltviller_carneval_verein.karten.ui.hall;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.HallObject;
import de.eltviller_carneval_verein.karten.model.Shape;
import de.eltviller_carneval_verein.karten.model.TextAlignH;
import de.eltviller_carneval_verein.karten.model.TextAlignV;
import javafx.fxml.FXML;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.util.StringConverter;

/**
 * Hallenplan-Editor: zeigt die Hallenobjekte einer Halle auf einer zoom- und
 * verschiebbaren Fläche. Im Bearbeiten-Modus lassen sich Objekte anlegen,
 * per Ziehen verschieben und im Seitenpanel (Name, Beschreibung, Maße, Form,
 * Farbe) anpassen. Koordinaten und Maße sind dieselben Einheiten wie in der
 * Saalübersicht des Kartenverkaufs.
 */
public class HallPlanController {

	private static final double MIN_SCALE = 0.05;
	private static final double MAX_SCALE = 4.0;
	private static final double ZOOM_FACTOR_PER_NOTCH = 1.1;
	private static final double FALLBACK_OBJECT_WIDTH = 100;
	private static final double FALLBACK_OBJECT_HEIGHT = 60;
	private static final Color DEFAULT_FILL = Color.LIGHTGRAY;
	private static final Color SELECTION_STROKE = Color.DODGERBLUE;

	private Hall hall;
	private HallObject selectedObject;
	private boolean editMode = false;
	// Verhindert, dass das Befüllen des Seitenpanels die Listener auslöst und zurückschreibt
	private boolean updatingPanel = false;

	private double dragAnchorSceneX;
	private double dragAnchorSceneY;
	private double dragAnchorTranslateX;
	private double dragAnchorTranslateY;
	private double objectAnchorX;
	private double objectAnchorY;
	// Einpassen wartet, bis die Fläche eine Größe hat (beim Öffnen des Screens noch 0)
	private boolean fitPending = false;
	// Knoten je Hallenobjekt, damit Auswahl und Ziehen den laufenden Knoten nicht durch Neuzeichnen ersetzen müssen
	private final Map<HallObject, Group> nodeByObject = new HashMap<>();
	private boolean objectDragged = false;

	@FXML
	private StackPane viewportPane;
	@FXML
	private Pane hallPane;
	@FXML
	private VBox propertiesPanel;
	@FXML
	private Button btnAddObject;
	@FXML
	private Button btnDeleteObject;
	@FXML
	private TextField nameField;
	@FXML
	private TextField descField;
	@FXML
	private Spinner<Double> posXSpinner;
	@FXML
	private Spinner<Double> posYSpinner;
	@FXML
	private Spinner<Double> widthSpinner;
	@FXML
	private Spinner<Double> heightSpinner;
	@FXML
	private ComboBox<Shape> shapeCombo;
	@FXML
	private ColorPicker colorPicker;
	@FXML
	private CheckBox textAutoCheck;
	@FXML
	private Spinner<Double> textSizeSpinner;
	@FXML
	private ComboBox<TextAlignH> textAlignHCombo;
	@FXML
	private ComboBox<TextAlignV> textAlignVCombo;
	@FXML
	private Spinner<Double> textRotationSpinner;

	@FXML
	public void initialize() {
		Rectangle clip = new Rectangle();
		clip.widthProperty().bind(viewportPane.widthProperty());
		clip.heightProperty().bind(viewportPane.heightProperty());
		viewportPane.setClip(clip);

		viewportPane.setOnScroll(this::handleScroll);
		viewportPane.setOnMousePressed(this::handleDragStart);
		viewportPane.setOnMouseDragged(this::handleDrag);
		viewportPane.widthProperty().addListener((obs, oldVal, newVal) -> fitIfPending());
		viewportPane.heightProperty().addListener((obs, oldVal, newVal) -> fitIfPending());

		setupPanel();
		updatePanel();

	}

	private void setupPanel() {
		widthSpinner.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(1.0, 10000.0, FALLBACK_OBJECT_WIDTH, 5));
		heightSpinner.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(1.0, 10000.0, FALLBACK_OBJECT_HEIGHT, 5));
		textSizeSpinner.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(1.0, 1000.0, 20.0, 1));
		SpinnerValueFactory.DoubleSpinnerValueFactory rotationFactory = new SpinnerValueFactory.DoubleSpinnerValueFactory(0.0, 360.0, 0.0, 15);
		// Über 360 Grad geht es bei 0 weiter
		rotationFactory.setWrapAround(true);
		textRotationSpinner.setValueFactory(rotationFactory);
		for (Spinner<Double> spinner : java.util.List.of(widthSpinner, heightSpinner, posXSpinner, posYSpinner, textSizeSpinner, textRotationSpinner)) {
			spinner.setEditable(true);
			// Getippte Werte beim Verlassen des Feldes übernehmen
			spinner.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
				if (!isFocused) {
					spinner.increment(0);
				}
			});
		}
		posXSpinner.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(-100000.0, 100000.0, 0.0, 5));
		posYSpinner.setValueFactory(new SpinnerValueFactory.DoubleSpinnerValueFactory(-100000.0, 100000.0, 0.0, 5));
		shapeCombo.getItems().setAll(Shape.values());

		nameField.setOnAction(e -> commitName());
		nameField.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
			if (!isFocused) {
				commitName();
			}
		});
		descField.textProperty().addListener((obs, oldVal, newVal) -> {
			if (!updatingPanel && selectedObject != null) {
				selectedObject.setDesc(newVal);
			}
		});
		posXSpinner.valueProperty().addListener((obs, oldVal, newVal) -> {
			if (!updatingPanel && selectedObject != null && newVal != null) {
				selectedObject.setPosX(newVal);
				renderHall();
			}
		});
		posYSpinner.valueProperty().addListener((obs, oldVal, newVal) -> {
			if (!updatingPanel && selectedObject != null && newVal != null) {
				selectedObject.setPosY(newVal);
				renderHall();
			}
		});
		widthSpinner.valueProperty().addListener((obs, oldVal, newVal) -> {
			if (!updatingPanel && selectedObject != null && newVal != null) {
				selectedObject.setWidth(newVal);
				renderHall();
			}
		});
		heightSpinner.valueProperty().addListener((obs, oldVal, newVal) -> {
			if (!updatingPanel && selectedObject != null && newVal != null) {
				selectedObject.setHeight(newVal);
				renderHall();
			}
		});
		shapeCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
			if (!updatingPanel && selectedObject != null && newVal != null) {
				selectedObject.setShape(newVal);
				renderHall();
			}
		});
		colorPicker.valueProperty().addListener((obs, oldVal, newVal) -> {
			if (!updatingPanel && selectedObject != null && newVal != null) {
				selectedObject.setColor(toWebString(newVal));
				renderHall();
			}
		});
		setupTextPanel();
	}

	/** Die Textfelder: Mit "Automatisch" sind sie gesperrt und zeigen die berechneten Werte. */
	private void setupTextPanel() {
		textAlignHCombo.getItems().setAll(TextAlignH.values());
		textAlignHCombo.setConverter(displayNames(TextAlignH::getDisplayName));
		textAlignVCombo.getItems().setAll(TextAlignV.values());
		textAlignVCombo.setConverter(displayNames(TextAlignV::getDisplayName));

		for (javafx.scene.Node field : java.util.List.of(textSizeSpinner, textAlignHCombo, textAlignVCombo, textRotationSpinner)) {
			field.disableProperty().bind(textAutoCheck.selectedProperty());
		}

		textAutoCheck.selectedProperty().addListener((obs, oldVal, auto) -> {
			if (updatingPanel || selectedObject == null) {
				return;
			}
			if (!auto) {
				// Von Hand weiter: Die Felder starten mit dem, was Auto gerade berechnet hat
				HallObjectText.Layout current = HallObjectText.resolve(selectedObject);
				selectedObject.setTextSize(roundForDisplay(current.fontSize()));
				selectedObject.setTextRotation(roundForDisplay(current.rotation()));
				selectedObject.setTextAlignH(current.alignH());
				selectedObject.setTextAlignV(current.alignV());
			}
			selectedObject.setTextAuto(auto);
			renderHall();
		});
		textSizeSpinner.valueProperty().addListener((obs, oldVal, newVal) -> {
			if (isEditingText() && newVal != null) {
				selectedObject.setTextSize(newVal);
				renderHall();
			}
		});
		textRotationSpinner.valueProperty().addListener((obs, oldVal, newVal) -> {
			if (isEditingText() && newVal != null) {
				selectedObject.setTextRotation(newVal);
				renderHall();
			}
		});
		textAlignHCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
			if (isEditingText() && newVal != null) {
				selectedObject.setTextAlignH(newVal);
				renderHall();
			}
		});
		textAlignVCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
			if (isEditingText() && newVal != null) {
				selectedObject.setTextAlignV(newVal);
				renderHall();
			}
		});
	}

	/** Änderungen an den Textfeldern gelten nur für ein gewähltes Objekt ohne Auto und nicht beim Befüllen des Panels. */
	private boolean isEditingText() {
		return !updatingPanel && selectedObject != null && !selectedObject.isTextAuto();
	}

	private static <T> StringConverter<T> displayNames(Function<T, String> displayName) {
		return new StringConverter<>() {
			@Override
			public String toString(T value) {
				return value == null ? "" : displayName.apply(value);
			}

			@Override
			public T fromString(String string) {
				return null; // Bei fixer ComboBox-Auswahl nicht erforderlich
			}
		};
	}

	/** Auf eine Nachkommastelle, damit die Spinner keine langen Dezimalzahlen zeigen. */
	private static double roundForDisplay(double value) {
		return Math.round(value * 10) / 10.0;
	}

	/** Zeigt die Textwerte des Objekts im Panel; bei Auto die berechneten (gesperrt), sonst die gespeicherten. */
	private void fillTextFields(HallObject obj) {
		boolean wasUpdating = updatingPanel;
		updatingPanel = true;
		try {
			textAutoCheck.setSelected(obj == null || obj.isTextAuto());
			if (obj == null) {
				return;
			}
			if (obj.isTextAuto()) {
				HallObjectText.Layout layout = HallObjectText.resolve(obj);
				textSizeSpinner.getValueFactory().setValue(roundForDisplay(layout.fontSize()));
				textRotationSpinner.getValueFactory().setValue(roundForDisplay(layout.rotation()));
				textAlignHCombo.setValue(layout.alignH());
				textAlignVCombo.setValue(layout.alignV());
			} else {
				textSizeSpinner.getValueFactory().setValue(obj.getTextSize());
				textRotationSpinner.getValueFactory().setValue(obj.getTextRotation());
				textAlignHCombo.setValue(obj.getTextAlignH());
				textAlignVCombo.setValue(obj.getTextAlignV());
			}
		} finally {
			updatingPanel = wasUpdating;
		}
	}

	private void commitName() {
		if (updatingPanel || selectedObject == null) {
			return;
		}
		try {
			selectedObject.setName(nameField.getText());
			renderHall();
		} catch (IllegalArgumentException e) {
			// Leerer Name: alten Namen wiederherstellen
			nameField.setText(selectedObject.getName());
		}
	}

	// --- Zoom und Verschieben der Fläche (wie in der Saalübersicht) -->
	private void handleScroll(ScrollEvent event) {
		event.consume();
		if (event.getDeltaY() == 0) {
			return;
		}

		double oldScale = hallPane.getScaleX();
		double zoomFactor = event.getDeltaY() > 0 ? ZOOM_FACTOR_PER_NOTCH : 1 / ZOOM_FACTOR_PER_NOTCH;
		double newScale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, oldScale * zoomFactor));
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

	// Der Plan wird immer mit der rechten Maustaste verschoben (links: Auswählen/Objekte ziehen)
	private void handleDragStart(MouseEvent event) {
		if (event.getButton() != MouseButton.SECONDARY) {
			return;
		}
		dragAnchorSceneX = event.getSceneX();
		dragAnchorSceneY = event.getSceneY();
		dragAnchorTranslateX = hallPane.getTranslateX();
		dragAnchorTranslateY = hallPane.getTranslateY();
	}

	private void handleDrag(MouseEvent event) {
		if (event.getButton() != MouseButton.SECONDARY) {
			return;
		}
		hallPane.setTranslateX(dragAnchorTranslateX + (event.getSceneX() - dragAnchorSceneX));
		hallPane.setTranslateY(dragAnchorTranslateY + (event.getSceneY() - dragAnchorSceneY));
	}
	// <-- Zoom und Verschieben

	@FXML
	private void handleFit() {
		fitToView();
	}

	private void fitIfPending() {
		if (fitPending && viewportPane.getWidth() > 0 && viewportPane.getHeight() > 0) {
			fitPending = false;
			fitToView();
		}
	}

	/**
	 * Passt Zoom und Verschiebung so an, dass der Hallenumriss (bzw. ohne Maße alle
	 * Hallenobjekte) mittig und vollständig in der Fläche sichtbar ist (1 cm = 1 px bei 100 %).
	 */
	private void fitToView() {
		double minX = 0, minY = 0, maxX = 0, maxY = 0;
		if (hall != null && hall.getHallWidth() > 0 && hall.getHallHeight() > 0) {
			maxX = hall.getHallWidth();
			maxY = hall.getHallHeight();
		} else if (hall != null && !hall.getHallObjects().isEmpty()) {
			minX = Double.MAX_VALUE;
			minY = Double.MAX_VALUE;
			maxX = -Double.MAX_VALUE;
			maxY = -Double.MAX_VALUE;
			for (HallObject object : hall.getHallObjects()) {
				minX = Math.min(minX, object.getPosX());
				minY = Math.min(minY, object.getPosY());
				maxX = Math.max(maxX, object.getPosX() + object.getWidth());
				maxY = Math.max(maxY, object.getPosY() + object.getHeight());
			}
		}

		double contentWidth = maxX - minX;
		double contentHeight = maxY - minY;
		double viewWidth = viewportPane.getWidth();
		double viewHeight = viewportPane.getHeight();
		if (contentWidth <= 0 || contentHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) {
			hallPane.setScaleX(1);
			hallPane.setScaleY(1);
			hallPane.setTranslateX(0);
			hallPane.setTranslateY(0);
			return;
		}

		double scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, 0.95 * Math.min(viewWidth / contentWidth, viewHeight / contentHeight)));

		// Skalierung erfolgt um die Mitte der Fläche (siehe handleScroll). Beim ersten Einpassen läuft der Aufruf noch
		// mitten im Layout, die hallPane hat dann noch keine Größe - ihr getLayoutBounds() taugt hier nicht.
		// Das StackPane streckt die hallPane später auf seine Innenfläche, daher wird deren Mitte als Drehpunkt verwendet.
		Insets insets = viewportPane.getInsets();
		double pivotX = (viewWidth - insets.getLeft() - insets.getRight()) / 2.0;
		double pivotY = (viewHeight - insets.getTop() - insets.getBottom()) / 2.0;
		double centerX = (minX + maxX) / 2.0;
		double centerY = (minY + maxY) / 2.0;

		hallPane.setScaleX(scale);
		hallPane.setScaleY(scale);
		hallPane.setTranslateX(viewWidth / 2.0 - insets.getLeft() - pivotX - scale * (centerX - pivotX));
		hallPane.setTranslateY(viewHeight / 2.0 - insets.getTop() - pivotY - scale * (centerY - pivotY));
	}

	/** Zeichnet den Plan neu, z.B. nach geänderten Hallenmaßen. */
	public void refresh() {
		renderHall();
	}

	private void renderHall() {
		hallPane.getChildren().clear();
		nodeByObject.clear();
		if (hall == null) {
			return;
		}

		// Hallenumriss, nur wenn Maße hinterlegt sind
		if (hall.getHallWidth() > 0 && hall.getHallHeight() > 0) {
			Rectangle outline = new Rectangle(0, 0, hall.getHallWidth(), hall.getHallHeight());
			outline.setFill(Color.WHITE);
			outline.setStroke(Color.GRAY);
			outline.getStrokeDashArray().addAll(8.0, 6.0);
			outline.setMouseTransparent(true);
			hallPane.getChildren().add(outline);
		}

		for (HallObject hallObject : hall.getHallObjects()) {
			Group node = createHallObjectNode(hallObject);
			nodeByObject.put(hallObject, node);
			hallPane.getChildren().add(node);
		}

		// Ändern sich Maße oder Name, ändern sich auch die berechneten Textwerte im (gesperrten) Panel
		if (selectedObject != null && selectedObject.isTextAuto()) {
			fillTextFields(selectedObject);
		}
	}

	private Group createHallObjectNode(HallObject hallObject) {
		javafx.scene.shape.Shape shape;
		if (hallObject.getShape() == Shape.CIRCLE) {
			double radius = hallObject.getWidth() / 2.0;
			shape = new Circle(hallObject.getPosX() + radius, hallObject.getPosY() + radius, radius);
		} else {
			Rectangle rect = new Rectangle(hallObject.getPosX(), hallObject.getPosY(), hallObject.getWidth(), hallObject.getHeight());
			rect.setArcWidth(10);
			rect.setArcHeight(10);
			shape = rect;
		}

		shape.setFill(parseColor(hallObject.getColor()));
		boolean selected = hallObject.equals(selectedObject);
		shape.setStroke(selected ? SELECTION_STROKE : Color.DARKGRAY);
		shape.setStrokeWidth(selected ? 3 : 1);

		Group node = new Group(shape, HallObjectText.create(hallObject));
		node.setCursor(editMode ? Cursor.MOVE : Cursor.HAND);
		node.setOnMousePressed(event -> startObjectDrag(event, hallObject));
		node.setOnMouseDragged(event -> dragObject(event, hallObject));
		node.setOnMouseReleased(event -> endObjectDrag(event));
		return node;
	}

	private void startObjectDrag(MouseEvent event, HallObject hallObject) {
		// Rechte/mittlere Taste nicht behandeln, damit sie zum Verschieben des Plans durchgereicht werden
		if (event.getButton() != MouseButton.PRIMARY) {
			return;
		}
		event.consume();
		objectDragged = false;
		if (!hallObject.equals(selectedObject)) {
			// Nur markieren, nicht neu zeichnen: der gedrückte Knoten muss für die folgende Drag-Geste bestehen bleiben
			selectedObject = hallObject;
			updatePanel();
			applySelectionStyle();
		}
		dragAnchorSceneX = event.getSceneX();
		dragAnchorSceneY = event.getSceneY();
		objectAnchorX = hallObject.getPosX();
		objectAnchorY = hallObject.getPosY();
	}

	private void dragObject(MouseEvent event, HallObject hallObject) {
		if (event.getButton() != MouseButton.PRIMARY) {
			return;
		}
		event.consume();
		if (!editMode) {
			return;
		}
		double scale = hallPane.getScaleX();
		double deltaX = (event.getSceneX() - dragAnchorSceneX) / scale;
		double deltaY = (event.getSceneY() - dragAnchorSceneY) / scale;
		hallObject.setPosX(objectAnchorX + deltaX);
		hallObject.setPosY(objectAnchorY + deltaY);
		objectDragged = true;

		// Knoten nur verschieben (kein Neuzeichnen während der Geste)
		Group node = nodeByObject.get(hallObject);
		if (node != null) {
			node.setTranslateX(deltaX);
			node.setTranslateY(deltaY);
		}
		updatePositionSpinners(hallObject);
	}

	private void endObjectDrag(MouseEvent event) {
		if (event.getButton() != MouseButton.PRIMARY) {
			return;
		}
		event.consume();
		if (objectDragged) {
			objectDragged = false;
			// Einmal sauber neu zeichnen, damit Geometrie wieder aus dem Modell stammt (Translate zurück auf 0)
			renderHall();
		}
	}

	private void updatePositionSpinners(HallObject obj) {
		updatingPanel = true;
		try {
			posXSpinner.getValueFactory().setValue(obj.getPosX());
			posYSpinner.getValueFactory().setValue(obj.getPosY());
		} finally {
			updatingPanel = false;
		}
	}

	/** Färbt den Rahmen der Knoten je nach Auswahl um, ohne die Knoten neu zu erzeugen. */
	private void applySelectionStyle() {
		nodeByObject.forEach((object, node) -> {
			boolean selected = object.equals(selectedObject);
			javafx.scene.shape.Shape shape = (javafx.scene.shape.Shape) node.getChildren().get(0);
			shape.setStroke(selected ? SELECTION_STROKE : Color.DARKGRAY);
			shape.setStrokeWidth(selected ? 3 : 1);
		});
	}

	private void selectObject(HallObject hallObject) {
		selectedObject = hallObject;
		updatePanel();
		renderHall();
	}

	/** Befüllt das Seitenpanel mit dem gewählten Objekt und sperrt es im Anzeigemodus. */
	private void updatePanel() {
		updatingPanel = true;
		try {
			HallObject obj = selectedObject;
			nameField.setText(obj != null ? obj.getName() : "");
			posXSpinner.getValueFactory().setValue(obj != null ? obj.getPosX() : 0.0);
			posYSpinner.getValueFactory().setValue(obj != null ? obj.getPosY() : 0.0);
			descField.setText(obj != null && obj.getDesc() != null ? obj.getDesc() : "");
			widthSpinner.getValueFactory().setValue(obj != null ? obj.getWidth() : FALLBACK_OBJECT_WIDTH);
			heightSpinner.getValueFactory().setValue(obj != null ? obj.getHeight() : FALLBACK_OBJECT_HEIGHT);
			shapeCombo.setValue(obj != null ? obj.getShape() : null);
			colorPicker.setValue(obj != null ? parseColor(obj.getColor()) : DEFAULT_FILL);
			fillTextFields(obj);
		} finally {
			updatingPanel = false;
		}

		boolean editable = editMode && selectedObject != null;
		propertiesPanel.setDisable(!editable);
		btnAddObject.setDisable(!editMode || hall == null);
		btnDeleteObject.setDisable(!editable);
	}

	@FXML
	private void handleAddObject() {
		if (hall == null) {
			return;
		}
		HallObject newObject = hall.addHallObject();
		// Ohne hinterlegte Standardmaße (0) wäre das Objekt unsichtbar
		if (newObject.getWidth() <= 0) {
			newObject.setWidth(FALLBACK_OBJECT_WIDTH);
		}
		if (newObject.getHeight() <= 0) {
			newObject.setHeight(FALLBACK_OBJECT_HEIGHT);
		}
		newObject.setShape(Shape.RECTANGLE);
		// Startposition: Hallenmitte (sofern Saalmaße gesetzt sind), sonst obere linke Ecke
		if (hall.getHallWidth() > 0 && hall.getHallHeight() > 0) {
			newObject.setPosX(Math.max(0, (hall.getHallWidth() - newObject.getWidth()) / 2.0));
			newObject.setPosY(Math.max(0, (hall.getHallHeight() - newObject.getHeight()) / 2.0));
		}
		selectObject(newObject);
	}

	@FXML
	private void handleDeleteObject() {
		if (hall == null || selectedObject == null) {
			return;
		}
		hall.getHallObjects().remove(selectedObject);
		selectObject(null);
	}

	private static Color parseColor(String color) {
		if (color == null || color.isBlank()) {
			return DEFAULT_FILL;
		}
		try {
			return Color.web(color);
		} catch (IllegalArgumentException e) {
			return DEFAULT_FILL;
		}
	}

	private static String toWebString(Color color) {
		return String.format("#%02X%02X%02X", Math.round(color.getRed() * 255), Math.round(color.getGreen() * 255), Math.round(color.getBlue() * 255));
	}

	public void setHall(Hall hall) {
		this.hall = hall;
		this.selectedObject = null;
		updatePanel();
		renderHall();
		fitPending = true;
		fitIfPending();
	}

	/** Markiert ein Hallenobjekt im Plan (z.B. wenn der Screen aus der Übersicht für ein Objekt geöffnet wurde). */
	public void setSelectedObject(HallObject hallObject) {
		selectObject(hallObject);
	}

	public void setEditMode(boolean enabled) {
		this.editMode = enabled;
		updatePanel();
		renderHall();
	}
}
