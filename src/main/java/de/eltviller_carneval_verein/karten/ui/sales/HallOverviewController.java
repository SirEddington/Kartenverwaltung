package de.eltviller_carneval_verein.karten.ui.sales;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.SeatStatus;
import de.eltviller_carneval_verein.karten.repository.JsonHallRepository;
import de.eltviller_carneval_verein.karten.ui.ContentController;
import de.eltviller_carneval_verein.karten.ui.EventSaver;
import de.eltviller_carneval_verein.karten.ui.UiColors;
import javafx.fxml.FXML;
import javafx.geometry.BoundingBox;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.Shape;
import javafx.stage.Popup;
import javafx.stage.Window;

/**
 * Die Saalübersicht der Verkaufsansicht: zeichnet die Bestuhlung der gewählten Vorstellung (nur lesend, angeordnet wird
 * im Hallen-Editor) und verkauft über die Sitze. Ein Klick auf einen Sitz öffnet das Buchungs-Popup; im
 * Bearbeitungsmodus wählen Strg-Klick und ein aufgezogener Rahmen mehrere Sitze für den Sammelverkauf. "Reihen
 * anzeigen" blendet die Reihen (Linie mit Buchstabe) aus, Tische und Sitze bleiben; das Kundenfenster folgt.
 */
public class HallOverviewController implements ContentController, SalesStateAware {

	private final JsonHallRepository hallRepository = JsonHallRepository.getInstance();
	private Event selectedEvent;
	private Presentation selectedPres;
	private boolean editMode = false;
	private String currentQuery = "";

	private Popup activePopup;

	// Mehrfachauswahl (nur im Bearbeitungsmodus): Strg-Klick auf Sitze oder Aufziehen eines Rahmens mit der linken Taste
	private static final double RUBBER_BAND_THRESHOLD = 4;
	// Mit gesetztem Verkaufszustand ist es dessen Auswahl (so sieht das Kundenfenster sie)
	private Set<Booking> selectedBookings = new LinkedHashSet<>();
	private final Map<Node, Booking> seatNodes = new HashMap<>();
	private final Rectangle rubberBand = new Rectangle();
	private ContextMenu selectionMenu;
	private double bandStartSceneX;
	private double bandStartSceneY;
	private boolean bandPressed = false;
	private boolean bandActive = false;
	private boolean bandAdditive = false;

	// Einpassen auf die Halle erfolgt nur, wenn sich die Halle ändert (Zoom bleibt beim Vorstellungswechsel)
	private String fittedHallId = null;

	@FXML
	private HallPlanPane viewportPane;

	@FXML
	private Label lblHint;

	// "Reihen anzeigen": blendet nur die Reihen aus, Tische und Sitze bleiben (mit Verkaufszustand an dessen Wert gebunden)
	@FXML
	private CheckBox chkShowRows;

	@FXML
	public void initialize() {
		chkShowRows.selectedProperty().addListener((obs, wasShown, shown) -> renderHall());
		viewportPane.setOnMousePressed(this::handleDragStart);
		viewportPane.setOnMouseDragged(this::handleDrag);
		viewportPane.setOnMouseReleased(this::handleMouseReleased);
		rubberBand.setManaged(false);
		rubberBand.setMouseTransparent(true);
		rubberBand.setVisible(false);
		rubberBand.setFill(HallPlanPane.SELECTED_SEAT_STROKE.deriveColor(0, 1, 1, 0.15));
		rubberBand.setStroke(HallPlanPane.SELECTED_SEAT_STROKE);
		viewportPane.getChildren().add(rubberBand);
		updateHint();
	}

	// Verschoben wird der Plan von der HallPlanPane mit der rechten Maustaste (ein Klick ohne Ziehen öffnet das
	// Auswahlmenü); die linke Taste klickt Sitze und zieht im Bearbeitungsmodus einen Auswahlrahmen auf
	private void handleDragStart(MouseEvent event) {
		if (event.getButton() != MouseButton.PRIMARY) {
			return;
		}
		// Bei offenem Dialog bleibt die Auswahl unberührt: Ein Rahmen würde den Dialog samt Eingaben schließen
		bandPressed = editMode && !isDialogOpen();
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
			} else if (event.isStillSincePress() && !bandAdditive && !selectedBookings.isEmpty() && bookingAt(event.getTarget()) == null) {
				// Klick ins Leere (auch auf einen Tisch) hebt die Auswahl auf
				selectedBookings.clear();
				renderHall();
			}
		} else if (event.getButton() == MouseButton.SECONDARY && event.isStillSincePress()) {
			showSelectionMenu(event);
		}
	}

	private Booking bookingAt(Object target) {
		for (Node node = target instanceof Node n ? n : null; node != null; node = node.getParent()) {
			Booking booking = seatNodes.get(node);
			if (booking != null) {
				return booking;
			}
		}
		return null;
	}

	private boolean isDialogOpen() {
		return activePopup != null && activePopup.isShowing();
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
			selectedBookings.clear();
		}
		seatNodes.forEach((node, booking) -> {
			if (matchesQuery(booking) && node.localToScene(node.getBoundsInLocal()).intersects(band)) {
				selectedBookings.add(booking);
			}
		});
		renderHall();
	}

	private void toggleSelection(Booking booking) {
		if (!matchesQuery(booking)) {
			return; // Was der Suchfilter ausblendet, lässt sich nicht auswählen
		}
		if (!selectedBookings.remove(booking)) {
			selectedBookings.add(booking);
		}
		renderHall();
	}

	/**
	 * Rechtsklick ohne Ziehen (mit gedrückter Taste zu ziehen verschiebt den Plan): Das Menü gehört zu einem Sitz. Auf
	 * einem gewählten Sitz gilt es für die ganze Auswahl, auf einem anderen Sitz wird dieser zur einzigen Auswahl, wie im
	 * Explorer. Im Leeren, auf einem Tisch, auf einem vom Suchfilter ausgeblendeten Sitz und bei offenem Dialog (er
	 * würde sonst samt Eingaben ersetzt) gibt es kein Menü. Im Anzeigemodus ist der Eintrag sichtbar, aber nicht
	 * ausführbar; die Auswahl ändert der Rechtsklick dort nicht (der Text nennt die Auswahl nur, wenn der Sitz dazugehört).
	 */
	private void showSelectionMenu(MouseEvent event) {
		if (selectionMenu != null) {
			selectionMenu.hide();
		}
		Booking booking = bookingAt(event.getTarget());
		if (booking == null || isDialogOpen() || !matchesQuery(booking)) {
			return;
		}
		if (editMode && !selectedBookings.contains(booking)) {
			selectedBookings.clear();
			selectedBookings.add(booking);
			renderHall();
		}
		MenuItem sellItem = new MenuItem(SeatSelectionEditor.menuText(selectedBookings.contains(booking) ? selectedBookings.size() : 1));
		sellItem.setDisable(!editMode);
		selectionMenu = new ContextMenu(sellItem);
		sellItem.setOnAction(action -> sellSelection(selectionMenu.getAnchorX(), selectionMenu.getAnchorY()));
		selectionMenu.show(viewportPane, event.getScreenX(), event.getScreenY());
	}

	private void sellSelection(double screenX, double screenY) {
		closePopup();
		SeatDetailPopup dialog = SeatSelectionEditor.edit(List.copyOf(selectedBookings), viewportPane.getScene().getWindow(), screenX, screenY, () -> {
			selectedBookings.clear();
			renderHall();
		}, occupied -> {
			selectedBookings.removeAll(occupied);
			renderHall();
		});
		if (dialog != null) {
			activePopup = dialog.popup();
		}
	}

	public void renderHall() {
		seatNodes.clear();
		Hall hall = hallOf(selectedPres);
		viewportPane.render(hall, SalesPlan.of(selectedPres, hall), selectedPres, chkShowRows.isSelected(), planDecorator);
	}

	private Hall hallOf(Presentation presentation) {
		return presentation == null ? null : hallRepository.findById(presentation.getHallId());
	}

	/** Aussehen und Verhalten der Sitze in der Saalübersicht (Klicks, Auswahl, Suchfilter). */
	private final HallPlanPane.Decorator planDecorator = new HallPlanPane.Decorator() {
		@Override
		public void decorateSeat(Booking booking, Seat seat, Node seatNode) {
			styleSeatNode(booking, seatNode);
		}
	};

	/** Farbzuordnung für den Saalplan - gehört hierher in die UI-Schicht, nicht ins Modell. */
	private static Color seatColorFor(SeatStatus status) {
		return switch (status) {
		case FREE -> UiColors.SEAT_FREE.getFxColor();
		case RESERVED -> UiColors.SEAT_RESEREVED.getFxColor();
		case SOLD -> UiColors.SEAT_SOLD.getFxColor();
		case BLOCKED -> UiColors.SEAT_BLOCKED.getFxColor();
		};
	}

	private void styleSeatNode(Booking booking, Node seatNode) {
		boolean matchesQuery = matchesQuery(booking);
		boolean searchActive = !currentQuery.isEmpty();
		boolean selected = selectedBookings.contains(booking);
		if (seatNode instanceof Shape shape) {
			shape.setFill(seatColorFor(booking.getStatus()));
			shape.setStroke(selected ? HallPlanPane.SELECTED_SEAT_STROKE : searchActive && matchesQuery ? Color.DODGERBLUE : Color.BLACK);
			shape.setStrokeWidth(selected ? 4 : searchActive && matchesQuery ? 3 : 1);
		}
		seatNode.setOpacity(matchesQuery ? 1.0 : 0.25);
		seatNodes.put(seatNode, booking);

		Tooltip.install(seatNode, new Tooltip(booking.getPlaceLabel() + " (" + booking.getStatus().getDisplayName() + ")"));
		seatNode.setOnMouseClicked(event -> {
			if (event.getButton() != MouseButton.PRIMARY || !event.isStillSincePress()) {
				return;
			}
			event.consume();
			if (editMode && isDialogOpen()) {
				return; // Ein zweiter Dialog würde den offenen samt Eingaben ersetzen: erst Anwenden oder Abbrechen
			}
			if (editMode && event.isShortcutDown()) {
				toggleSelection(booking);
				return;
			}
			Window window = seatNode.getScene().getWindow();
			openSeatDetailPopup(booking, window, event.getScreenX() + 12, event.getScreenY() + 12);
		});
	}

	private boolean matchesQuery(Booking booking) {
		if (currentQuery.isEmpty()) {
			return true;
		}

		String lastName = booking.getLastName();
		String firstName = booking.getFirstName();
		String comment = booking.getComment();

		return (lastName != null && lastName.toLowerCase().contains(currentQuery)) || (firstName != null && firstName.toLowerCase().contains(currentQuery))
				|| (comment != null && comment.toLowerCase().contains(currentQuery));
	}

	/**
	 * Öffnet ein Popup mit den Kartendetails des angeklickten Sitzes direkt über der Saalübersicht. Änderungen gelten erst
	 * mit "Anwenden"; danach wird die Saalübersicht neu gezeichnet (Farbe, Tooltip etc.).
	 */
	private void openSeatDetailPopup(Booking booking, Window window, double screenX, double screenY) {
		closePopup();
		SeatDetailPopup dialog = new SeatDetailPopup(List.of(booking), editMode, this::renderHall);
		activePopup = dialog.popup();
		dialog.show(window, screenX, screenY);
	}

	private void closePopup() {
		if (activePopup != null) {
			Popup popupToClose = activePopup;
			activePopup = null;
			popupToClose.setOnHidden(null);
			popupToClose.hide();
		}
	}

	/** Rahmen des Buchungs-Popups ({@link SeatDetailPopup}). */
	static final String DETAIL_POPUP_STYLE = "-fx-background-color: white; -fx-border-color: #999999; -fx-border-width: 1; " + "-fx-padding: 12; -fx-background-radius: 4; -fx-border-radius: 4;";

	/**
	 * Das App-Stylesheet für Popups: Ein Popup ist eine eigene Szene und bekommt die Stylesheets der Haupt-Szene nicht
	 * mit; ohne sähen Buttons, Felder usw. dort anders aus.
	 */
	static final String APP_STYLESHEET = HallOverviewController.class.getResource("/de/eltviller_carneval_verein/karten/ui/style.css").toExternalForm();

	private void applyEditMode() {
		closePopup();
		selectedBookings.clear(); // Die Mehrfachauswahl gibt es nur im Bearbeitungsmodus
		renderHall();
	}

	/**
	 * Die Auswahl im Saalplan, der sichtbare Ausschnitt und "Reihen anzeigen" gehen in den gemeinsamen Verkaufszustand;
	 * der Schalter übernimmt dabei dessen Stand (z. B. nach dem Wechsel von der Tabelle zurück zur Saalansicht).
	 */
	@Override
	public void setSalesState(SalesViewState state) {
		selectedBookings = state.selectedBookings();
		chkShowRows.selectedProperty().bindBidirectional(state.showRowsProperty());
		viewportPane.visibleHallRectProperty().addListener((obs, oldRect, newRect) -> state.visibleHallRectProperty().set(newRect));
		state.visibleHallRectProperty().set(viewportPane.visibleHallRectProperty().get());
	}

	@Override
	public void setEvent(Event event) {
		closePopup();
		selectedBookings.clear();
		this.selectedEvent = event;
		selectedPres = null;
		renderHall();
		updateHint();
	}

	@Override
	public void setPresentation(Presentation presentation) {
		closePopup();
		selectedBookings.clear();
		this.selectedPres = presentation;
		if (selectedPres != null) {
			this.selectedEvent = presentation.getParent();
		}
		// Ohne Vorstellung ("Alle Vorstellungen") bleibt der Plan leer: Er zeigt immer genau eine Vorstellung
		renderHall();
		Hall hall = hallOf(selectedPres);
		Rectangle2D hallRect = HallViewport.hallRect(hall);
		if (hallRect != null && !hall.getId().equals(fittedHallId)) {
			fittedHallId = hall.getId();
			viewportPane.fit(hallRect, HallViewport.HALL_FIT_MARGIN);
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
		selectedBookings.removeIf(booking -> !matchesQuery(booking)); // Ausgeblendete Sitze bleiben nicht ausgewählt
		renderHall();
	}

	@Override
	public void setEditMode(boolean enabled) {
		editMode = enabled;
		applyEditMode();
	}
}
