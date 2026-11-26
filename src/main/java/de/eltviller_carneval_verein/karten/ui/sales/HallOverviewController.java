package de.eltviller_carneval_verein.karten.ui.sales;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.BookingMove;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.SeatStatus;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.repository.ForeignBatch;
import de.eltviller_carneval_verein.karten.repository.RepositoryListener;
import de.eltviller_carneval_verein.karten.repository.StoreEventRepository;
import de.eltviller_carneval_verein.karten.repository.StoreHallRepository;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.ui.CoalescedAction;
import de.eltviller_carneval_verein.karten.ui.ContentController;
import de.eltviller_carneval_verein.karten.ui.EventSaver;
import de.eltviller_carneval_verein.karten.ui.LiveViews;
import de.eltviller_carneval_verein.karten.ui.LiveViews.Registration;
import de.eltviller_carneval_verein.karten.ui.StatusMessage;
import de.eltviller_carneval_verein.karten.ui.hall.PlanLabelOptions;
import de.eltviller_carneval_verein.karten.validation.Severity;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.geometry.BoundingBox;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.ZoomEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.Shape;
import javafx.stage.Popup;
import javafx.stage.Window;

/**
 * Die Saalübersicht der Verkaufsansicht: zeichnet die Bestuhlung der gewählten Vorstellung (nur lesend, angeordnet wird
 * im Hallen-Editor) und verkauft über die Sitze. Ein Klick auf einen Sitz öffnet das Buchungs-Popup; im
 * Bearbeitungsmodus wählen Strg-Klick und ein aufgezogener Rahmen mehrere Sitze für den Sammelverkauf. "Reihen
 * anzeigen" blendet die Reihen (Linie mit Buchstabe) aus, Tische und Sitze bleiben; das Kundenfenster folgt. Beim
 * Verschieben von Buchungen (KARV-140) wählt ein Klick die Zielplätze, siehe {@link #startMove}.
 */
public class HallOverviewController implements ContentController, SalesStateAware {

	private final StoreHallRepository hallRepository = StoreHallRepository.getInstance();
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

	// Bündelt die Anforderungen eines Vorgangs (renderHall) zu einem Planaufbau (drawHall)
	private final CoalescedAction redraw = new CoalescedAction(Platform::runLater, this::drawHall);
	// Zieht einen angeforderten Planaufbau vor das nächste Bild; hängt an der Szene, solange die Ansicht zu sehen ist
	private final Runnable drawBeforeFrame = redraw::flush;
	private Scene pulseScene;

	// Fremde Änderungen am gezeigten Event oder an der Halle der gezeigten Vorstellung: Der Plan wird neu gezeichnet
	private final StoreEventRepository eventRepository = StoreEventRepository.getInstance();
	private final RepositoryListener<Event> foreignEventChanges = new RepositoryListener<>() {
		// Ein neu zusammengesetztes Event (anderes Objekt) bindet die Shell mit setEvent neu
		private final RepositoryListener<Event> refreshing = LiveViews.onFxThread(
				units -> LiveViews.touches(units, selectedEvent, selectedPres), () -> {
					dropGoneBookingsFromSelection();
					checkMove();
					renderHall();
				});

		@Override
		public void changedByOthers(Event event, ForeignBatch<Event> batch) {
			refreshing.changedByOthers(event, batch);
		}

		@Override
		public void changedElsewhere(Event event, Set<UnitKey> units) {
			refreshing.changedElsewhere(event, units);
		}
	};
	private final RepositoryListener<Hall> foreignHallChanges = LiveViews.onFxThread(
			units -> LiveViews.touchesHall(units, selectedPres == null ? null : selectedPres.getHallId()), () -> {
				checkMove();
				renderIfShown();
			});

	@FXML
	private HallPlanPane viewportPane;

	@FXML
	private Label lblHint;

	// Unter dem Plan: welcher Sitz was bedeutet; nur mit barrierefreier Darstellung zu sehen (KARV-26)
	@FXML
	private StackPane legendSlot;

	// Leiste über dem Plan, solange die Zielwahl beim Verschieben läuft (KARV-140)
	@FXML
	private HBox moveBar;
	@FXML
	private Label lblMove;
	@FXML
	private Button btnMoveGo;

	// Menü "Anzeige" der Shell: Mit gesetztem Verkaufszustand sind es dessen Einstellungen (so folgt das Kundenfenster)
	private PlanLabelOptions labelOptions = new PlanLabelOptions();
	private BooleanProperty seatPatterns = new SimpleBooleanProperty(true);
	// Der gemeinsame Verkaufszustand, sobald die Shell ihn setzt; bekommt die Treffer der Suche (KARV-192)
	private SalesViewState salesState;

	@FXML
	public void initialize() {
		// Das Auswahlmenü schließt bei jeder Bedienung des Plans. JavaFX schließt ein Menü nur bei einem unverarbeiteten
		// Ereignis daneben; die Sitze und der Plan verarbeiten Klick und Mausrad selbst, deshalb bliebe es sonst stehen.
		viewportPane.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> hideSelectionMenu());
		viewportPane.addEventFilter(ScrollEvent.ANY, event -> hideSelectionMenu());
		viewportPane.addEventFilter(ZoomEvent.ANY, event -> hideSelectionMenu());
		viewportPane.addEventFilter(KeyEvent.KEY_PRESSED, event -> hideSelectionMenu());
		viewportPane.setOnMousePressed(this::handleDragStart);
		viewportPane.setOnMouseDragged(this::handleDrag);
		viewportPane.setOnMouseReleased(this::handleMouseReleased);
		rubberBand.setManaged(false);
		rubberBand.setMouseTransparent(true);
		rubberBand.setVisible(false);
		rubberBand.setFill(HallPlanPane.SELECTION_COLOR.deriveColor(0, 1, 1, 0.15));
		rubberBand.setStroke(HallPlanPane.SELECTION_COLOR);
		viewportPane.getChildren().add(rubberBand);
		legendSlot.getChildren().setAll(SeatLook.legend());
		// Sichtbar erst, wenn ein Plan gezeichnet ist (drawHall)
		legendSlot.setVisible(false);
		legendSlot.setManaged(false);
		updateHint();

		// Fremde Änderungen nur, solange der Plan zu sehen ist (danach hielten die Repositories diese Ansicht am Leben)
		// Ebenso eine geänderte Sperrliste: Ein gesperrter Sitz wird grau oder wieder normal und verlässt die Auswahl, sonst
		// schlösse "Verkaufen" ihn ein und scheiterte an ihm (KARV-111)
		// Wer die Ansicht verlässt, beendet die Zielwahl beim Verschieben
		LiveViews.whileShown(viewportPane, Registration.onDetach(() -> endMove(false)),
				Registration.of(eventRepository::addListener, eventRepository::removeListener, foreignEventChanges),
				Registration.of(hallRepository::addListener, hallRepository::removeListener, foreignHallChanges),
				LockedSeats.whileShown(() -> {
					LockedSeats.dropFrom(selectedBookings);
					checkMove();
					renderIfShown();
				}), new Registration(this::drawBeforeFrames, this::stopDrawingBeforeFrames));
	}

	/**
	 * Ein angeforderter Planaufbau läuft spätestens vor dem nächsten Bild der Szene. Ohne das kann ein schon eingereihter
	 * Puls dem eingeplanten Aufbau zuvorkommen: Die Ansicht erschiene nach dem Wechsel kurz leer, und die Halle würde
	 * eingepasst, bevor die Legende ihren Platz belegt (KARV-108).
	 */
	private void drawBeforeFrames() {
		// LiveViews.whileShown meldet erst an, wenn die Ansicht in einem gezeigten Fenster steht: Die Szene ist da
		pulseScene = viewportPane.getScene();
		pulseScene.addPreLayoutPulseListener(drawBeforeFrame);
	}

	/** Die Szene ist beim Abmelden schon gewechselt oder weg; abgemeldet wird bei der gemerkten. */
	private void stopDrawingBeforeFrames() {
		pulseScene.removePreLayoutPulseListener(drawBeforeFrame);
		pulseScene = null;
	}

	/** Zeichnet die Halle der gezeigten Vorstellung neu (eine ersetzte Halle hat dieselbe ID, {@link #hallOf} sucht nach ihr). */
	private void renderIfShown() {
		if (selectedPres != null) {
			renderHall();
		}
	}

	/** Buchungen, die ein anderer Rechner aus der Vorstellung genommen hat, bleiben nicht ausgewählt. */
	private void dropGoneBookingsFromSelection() {
		if (selectedPres != null && selectedPres.getBookings() != null) {
			selectedBookings.removeIf(booking -> !selectedPres.getBookings().contains(booking));
		}
	}

	// Verschoben wird der Plan von der HallPlanPane mit der rechten Maustaste (ein Klick ohne Ziehen öffnet das
	// Auswahlmenü); die linke Taste klickt Sitze und zieht im Bearbeitungsmodus einen Auswahlrahmen auf
	private void handleDragStart(MouseEvent event) {
		if (event.getButton() != MouseButton.PRIMARY) {
			return;
		}
		// Bei offenem Dialog bleibt die Auswahl unberührt: Ein Rahmen würde den Dialog samt Eingaben schließen. In der
		// Zielwahl gibt es keinen Rahmen, und ein Klick ins Leere ändert die Auswahl nicht
		bandPressed = editMode && !isDialogOpen() && moveTargets == null;
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
		} else if (event.getButton() == MouseButton.SECONDARY && event.isStillSincePress() && moveTargets == null) {
			showSelectionMenu(event); // in der Zielwahl kein Menü
		}
	}

	private void hideSelectionMenu() {
		if (selectionMenu != null) {
			selectionMenu.hide();
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
			// Ein gesperrter Sitz lässt sich nicht verkaufen und kommt deshalb nicht in die Auswahl (KARV-111)
			if (matchesQuery(booking) && !LockedSeats.isLocked(booking) && node.localToScene(node.getBoundsInLocal()).intersects(band)) {
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
	 * Explorer. Im Leeren, auf einem Tisch, auf einem vom Suchfilter ausgeblendeten oder gesperrten Sitz (KARV-111) und bei
	 * offenem Dialog (er würde sonst samt Eingaben ersetzt) gibt es kein Menü. Im Anzeigemodus ist der Eintrag sichtbar, aber nicht
	 * ausführbar; dort gibt es keine Auswahl ({@link #restoreSelection}), der Text nennt deshalb einen Sitz.
	 */
	private void showSelectionMenu(MouseEvent event) {
		if (selectionMenu != null) {
			selectionMenu.hide();
		}
		Booking booking = bookingAt(event.getTarget());
		if (booking == null || LockedSeats.isLocked(booking) || isDialogOpen() || !matchesQuery(booking)) {
			return;
		}
		if (editMode && !selectedBookings.contains(booking)) {
			selectedBookings.clear();
			selectedBookings.add(booking);
			renderHall();
		}
		MenuItem sellItem = new MenuItem(SeatSelectionEditor.menuText(selectedBookings.contains(booking) ? selectedBookings.size() : 1));
		sellItem.setDisable(!editMode);
		List<Booking> menuSelection = selectedBookings.contains(booking) ? List.copyOf(selectedBookings) : List.of(booking);
		// Verschieben gilt für dieselbe Auswahl; gesperrt, wenn keine gewählte Buchung ihren Sitz belegt (KARV-140)
		MenuItem moveItem = new MenuItem();
		MoveAction.prepare(moveItem, menuSelection, editMode);
		// Stornieren gilt für dieselbe Auswahl wie der Verkauf (KARV-121); gesperrt, wenn keine gewählte Buchung Daten trägt
		MenuItem cancelItem = new MenuItem();
		CancelAction.prepare(cancelItem, menuSelection, editMode);
		selectionMenu = new ContextMenu(sellItem, moveItem, cancelItem);
		sellItem.setOnAction(action -> sellSelection(selectionMenu.getAnchorX(), selectionMenu.getAnchorY()));
		moveItem.setOnAction(action -> startMove(menuSelection));
		cancelItem.setOnAction(action -> cancelSelection());
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

	private void cancelSelection() {
		closePopup();
		if (CancelAction.run(List.copyOf(selectedBookings), viewportPane.getScene().getWindow())) {
			selectedBookings.clear();
			renderHall();
		}
	}

	// ---- Zielwahl beim Verschieben von Buchungen (KARV-140) ----
	// Solange moveTargets gesetzt ist, wählt ein Linksklick auf einen Sitz ein Ziel (styleSeatNode); Rahmen, Kontextmenü
	// und Karte gibt es nicht, und die Auswahl bleibt, wie sie beim Start war (Suche und Klick ins Leere ändern sie nicht).
	// Die Quellen hält moveTargets selbst. Ohne Zielwahl ist die Bedienung unverändert.

	private MoveTargets moveTargets;
	// Esc bricht die Zielwahl ab: Der Plan bekommt nie den Tastaturfokus, deshalb an der Szene (gemerkt fürs Abmelden)
	private final EventHandler<KeyEvent> moveEscape = event -> {
		if (event.getCode() == KeyCode.ESCAPE && moveTargets != null && !isDialogOpen()) {
			event.consume();
			endMove(false);
		}
	};
	private Scene moveScene;

	/**
	 * Beginnt die Zielwahl für die belegenden Buchungen der Auswahl. Liegt der Sitz einer Quelle auf einer gesperrten
	 * Einheit, nennt die Fußzeile ihn, und es beginnt keine Zielwahl.
	 */
	private void startMove(List<Booking> selection) {
		SeatingPlan plan = currentPlan();
		List<BookingMove.Source> sources = MoveTexts.movable(selection).stream().map(BookingMove.Source::of).toList();
		if (sources.isEmpty() || plan == null) {
			return; // der Eintrag ist dann gesperrt oder der Sitz gar nicht gezeichnet
		}
		Set<String> locked = LockedSeats.lockedSeatIds(selectedPres);
		for (BookingMove.Source source : sources) {
			Seat seat = plan.findSeat(source.seatId());
			if (seat != null && locked.contains(seat.getId())) {
				StatusMessage.getInstance().show(Severity.ERROR, MoveTexts.locked(seat));
				return;
			}
		}
		closePopup();
		moveTargets = new MoveTargets(selectedPres, sources);
		moveScene = viewportPane.getScene();
		moveScene.addEventFilter(KeyEvent.KEY_PRESSED, moveEscape);
		moveBar.setVisible(true);
		moveBar.setManaged(true);
		updateMoveBar();
		renderHall();
	}

	/** Linksklick auf einen Sitz in der Zielwahl: wählt ihn als Ziel oder wieder ab; mit dem letzten Ziel geht es weiter. */
	private void clickMoveTarget(String seatId) {
		SeatingPlan plan = currentPlan();
		Seat seat = plan == null ? null : plan.findSeat(seatId);
		if (seat == null) {
			return; // nach einer fremden Änderung kein Sitz der Bestuhlung mehr; der Plan wird gleich neu gezeichnet
		}
		switch (moveTargets.toggle(seatId, LockedSeats.lockedSeatIds(selectedPres))) {
		case LOCKED -> StatusMessage.getInstance().show(Severity.ERROR, MoveTexts.locked(seat));
		case FULL -> StatusMessage.getInstance().show(Severity.INFO, MoveTexts.tooMany(moveTargets.sources().size()));
		case ADDED, REMOVED -> {
			renderHall();
			updateMoveBar();
			if (moveTargets.complete()) {
				tryMove();
			}
		}
		}
	}

	@FXML
	private void handleMoveGo() {
		if (moveTargets != null && moveTargets.complete()) {
			tryMove();
		}
	}

	@FXML
	private void handleMoveCancel() {
		endMove(false);
	}

	/**
	 * Plant den Vorgang und fragt nach. "Nichts zu tun" und "Platz gesperrt" lassen die Zielwahl mit ihren Zielen stehen,
	 * damit die Kasse eines abwählen kann; "Abbrechen" im Dialog ebenso. Jede veraltete Auswahl beendet sie.
	 */
	private void tryMove() {
		MoveTargets move = moveTargets;
		Presentation presentation = move.presentation();
		SeatingPlan plan = currentPlan();
		List<String> targets = move.targets();
		BookingMove.Plan shown = BookingMove.plan(presentation, plan, move.sources(), targets, LockedSeats.lockedSeatIds(presentation));
		if (shown.refused()) {
			String text = MoveTexts.refusal(shown, presentation);
			switch (shown.refusal()) {
			case NOTHING_TO_DO -> StatusMessage.getInstance().show(Severity.INFO, text);
			case LOCKED -> StatusMessage.getInstance().show(Severity.ERROR, text);
			case STALE, COUNT, NO_PLAN -> {
				StatusMessage.getInstance().show(Severity.ERROR, text);
				endMove(true);
			}
			}
			return;
		}
		// Meldungen nach dem Ausführen und bei veralteter Auswahl zeigt MoveAction selbst. Ausgeführt wird nur, solange
		// genau diese Zielwahl läuft und die Ansicht dieselbe Vorstellung zeigt: Ein Wechsel während der Rückfrage hängt das
		// alte Modell ab, es würde nie gespeichert
		MoveAction.Outcome outcome = MoveAction.run(presentation, plan, move.sources(), targets, shown,
				() -> moveTargets == move && selectedPres == move.presentation(), viewportPane.getScene().getWindow());
		if (moveTargets != move) {
			// Während der Rückfrage beendet (Wechsel der Ansicht, fremde Änderung): nur den neuen Stand zeigen; die
			// Zielwahl, die jetzt vielleicht läuft, gehört nicht zu diesem Vorgang
			renderHall();
			return;
		}
		switch (outcome) {
		case DONE -> {
			// Die Quellen an ihren neuen Plätzen sind die Auswahl; was die Suche ausblendet, wie sonst nicht
			List<Booking> moved = move.sources().stream().map(BookingMove.Source::booking).filter(this::matchesQuery).toList();
			stopMove();
			selectedBookings.clear();
			selectedBookings.addAll(moved);
			renderHall();
		}
		case CANCELLED -> {
			// zurück in die Zielwahl, die Ziele bleiben
		}
		case STALE -> endMove(true);
		}
	}

	/**
	 * Nach einer fremden Änderung: Ist eine Quelle veraltet, endet die Zielwahl mit der Meldung; sonst fallen Ziele heraus,
	 * die gesperrt sind oder nicht mehr zur Bestuhlung gehören, und die Zielwahl läuft weiter.
	 */
	private void checkMove() {
		if (moveTargets == null) {
			return;
		}
		SeatingPlan plan = currentPlan();
		if (!moveTargets.sourcesCurrent(plan)) {
			StatusMessage.getInstance().show(Severity.ERROR, MoveTexts.STALE);
			endMove(true);
			return;
		}
		moveTargets.dropGoneTargets(plan, LockedSeats.lockedSeatIds(selectedPres));
		updateMoveBar();
	}

	/**
	 * Beendet eine laufende Zielwahl. Bei {@code stale} ist die Auswahl danach leer (die Meldung zeigt der Aufrufer), sonst
	 * die vom Start ohne das, was die Suche inzwischen ausblendet.
	 */
	private void endMove(boolean stale) {
		if (moveTargets == null) {
			return;
		}
		stopMove();
		if (stale) {
			selectedBookings.clear();
		} else {
			selectedBookings.removeIf(booking -> !matchesQuery(booking));
		}
		renderHall();
	}

	/** Leiste weg, Esc ab, keine Zielwahl mehr; die Auswahl setzt der Aufrufer. */
	private void stopMove() {
		moveTargets = null;
		moveBar.setVisible(false);
		moveBar.setManaged(false);
		moveScene.removeEventFilter(KeyEvent.KEY_PRESSED, moveEscape);
		moveScene = null;
	}

	private void updateMoveBar() {
		lblMove.setText(moveTargets.barText());
		btnMoveGo.setDisable(!moveTargets.complete());
	}

	/** Die Bestuhlung der gezeigten Vorstellung, wie sie gerade im Modell steht (nicht wie zuletzt gezeichnet). */
	private SeatingPlan currentPlan() {
		return SalesPlan.of(selectedPres, hallOf(selectedPres));
	}

	// ---- Ende der Zielwahl ----

	/**
	 * Fordert an, den Plan neu zu zeichnen. Gezeichnet wird einmal, nachdem der laufende Vorgang fertig ist, gleich wie oft
	 * er das anfordert: Der Wechsel zur Saalansicht setzt Vorstellung, Modus, Suchfilter und Auswahl nacheinander und baute
	 * den Plan sonst viermal auf (KARV-108). Bis dahin zeigen Plan und {@link #seatNodes} noch den alten Stand; spätestens
	 * vor dem nächsten Bild ist der neue da ({@link #drawBeforeFrames}).
	 */
	public void renderHall() {
		redraw.request();
	}

	private void drawHall() {
		seatNodes.clear();
		Hall hall = hallOf(selectedPres);
		SeatingPlan plan = SalesPlan.of(selectedPres, hall);
		viewportPane.render(hall, plan, selectedPres, labelOptions.snapshot(), planDecorator);
		reportSearchHits();
		// Die Legende erklärt gezeichnete Sitze: nur mit barrierefreier Darstellung und nur, wenn ein Plan zu sehen ist
		boolean legend = seatPatterns.get() && selectedPres != null && plan != null;
		legendSlot.setVisible(legend);
		legendSlot.setManaged(legend);
	}

	/**
	 * Meldet die nicht abgeblendeten Sitze als Treffer (KARV-192), sortiert wie die Kartentabelle. Zeichnet diese Ansicht
	 * noch, nachdem die Shell zur Tabelle gewechselt hat (Menü "Anzeige"), ignoriert der Zustand die Meldung.
	 */
	private void reportSearchHits() {
		if (salesState != null) {
			salesState.showSearchHits(this, seatNodes.values().stream().filter(this::matchesQuery).sorted(TableLabel.BOOKING_ORDER).toList());
		}
	}

	private Hall hallOf(Presentation presentation) {
		return presentation == null ? null : hallRepository.findById(presentation.getHallId());
	}

	/** Aussehen und Verhalten der Sitze in der Saalübersicht (Klicks, Auswahl, Suchfilter). */
	private final HallPlanPane.Decorator planDecorator = new HallPlanPane.Decorator() {
		@Override
		public void decorateSeat(Booking booking, Seat seat, Node seatNode) {
			styleSeatNode(booking, seat, seatNode);
		}

		@Override
		public Node seatMark(Booking booking, Seat seat) {
			if (moveTargets != null && moveTargets.isTarget(seat.getId())) {
				return SeatLook.targetMark(seat.getPosX(), seat.getPosY(), seat.radius());
			}
			return SeatLook.hasCross(statusOf(booking), seatPatterns.get())
					? SeatLook.cross(seat.getPosX(), seat.getPosY(), seat.radius())
					: null;
		}
	};

	/** Ein gesperrter Sitz (unlesbare Buchung, KARV-111) zeigt sich gesperrt, gleich was die Buchung sagt. */
	private static SeatStatus statusOf(Booking booking) {
		return LockedSeats.isLocked(booking) ? SeatStatus.BLOCKED : booking.getStatus();
	}

	private void styleSeatNode(Booking booking, Seat seat, Node seatNode) {
		// In der Zielwahl tragen die Quellen das Halo, unabhängig von der Suche; Ziele werden nicht abgeblendet
		boolean target = moveTargets != null && moveTargets.isTarget(seat.getId());
		boolean undimmed = matchesQuery(booking) || target;
		boolean selected = moveTargets != null ? moveTargets.isSource(booking) : selectedBookings.contains(booking);
		// Ein gesperrter Sitz ist grau und öffnet die gesperrten Einträge statt der Buchungskarte
		boolean locked = LockedSeats.isLocked(booking);
		SeatStatus status = locked ? SeatStatus.BLOCKED : booking.getStatus();
		if (seatNode instanceof Shape shape) {
			// Wie der Status aussieht (mit oder ohne barrierefreie Darstellung), legt SeatLook fest (KARV-26)
			shape.setFill(SeatLook.fill(status, seatPatterns.get()));
			// Suchtreffer bekommen keine eigene Hervorhebung: Die übrigen Sitze sind abgeblendet (unten), die Auswahl
			// trägt ein goldenes Halo hinter dem Sitz (HallPlanPane)
			shape.setStroke(Color.BLACK);
			shape.setStrokeWidth(1);
		}
		if (selected) {
			seatNode.getProperties().put(HallPlanPane.SELECTED_KEY, Boolean.TRUE);
		}
		seatNode.setOpacity(undimmed ? 1.0 : 0.25);
		seatNodes.put(seatNode, booking);

		// Kein Tooltip mit Platz und Status: Er läge unter dem Zeiger, wo der Hinweis zur Auswahl stehen kann (KARV-197)
		seatNode.setOnMouseClicked(event -> {
			if (event.getButton() != MouseButton.PRIMARY || !event.isStillSincePress()) {
				return;
			}
			event.consume();
			if (moveTargets != null) {
				clickMoveTarget(seat.getId()); // Zielwahl: keine Karte, die Auswahl bleibt
				return;
			}
			if (editMode && isDialogOpen()) {
				return; // Ein zweiter Dialog würde den offenen samt Eingaben ersetzen: erst Anwenden oder Abbrechen
			}
			if (locked) {
				LockedSeats.open(); // auch mit Strg: Der Sitz kommt nicht in die Auswahl
				return;
			}
			if (editMode && event.isShortcutDown()) {
				toggleSelection(booking);
				return;
			}
			Window window = seatNode.getScene().getWindow();
			if (editMode && matchesQuery(booking)) {
				// Ein Einzelklick wählt den Sitz aus (wie im Explorer ersetzt er die bisherige Auswahl) und öffnet seine Karte
				selectedBookings.clear();
				selectedBookings.add(booking);
				renderHall();
			}
			openSeatDetailPopup(booking, window, event.getScreenX() + 12, event.getScreenY() + 12);
		});
	}

	private boolean matchesQuery(Booking booking) {
		return BookingSearch.matches(booking, currentQuery);
	}

	/**
	 * Öffnet ein Popup mit den Kartendetails des angeklickten Sitzes direkt über der Saalübersicht. Änderungen gelten erst
	 * mit "Anwenden" (bzw. "Speichern", siehe {@link BookingAutoSave}); danach wird die Saalübersicht neu gezeichnet (Farbe, Auswahl etc.).
	 */
	private void openSeatDetailPopup(Booking booking, Window window, double screenX, double screenY) {
		closePopup();
		SeatDetailPopup dialog = new SeatDetailPopup(List.of(booking), editMode, BookingAutoSave.settingOn(), this::renderHall);
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
		endMove(false);
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
		salesState = state;
		selectedBookings = state.selectedBookings();
		labelOptions = state.planLabels();
		labelOptions.follow(viewportPane, this::renderHall);
		seatPatterns = state.seatPatternsProperty();
		// Neu gezeichnet werden Sitze und Legende (renderHall)
		LiveViews.follow(viewportPane, seatPatterns, this::renderHall);
		viewportPane.visibleHallRectProperty().addListener((obs, oldRect, newRect) -> state.visibleHallRectProperty().set(newRect));
		state.visibleHallRectProperty().set(viewportPane.visibleHallRectProperty().get());
	}

	/**
	 * Markiert die Sitze der Vorstellung, die in der Auswahl der verlassenen Ansicht waren (nach dem Wechsel von der
	 * Tabelle). Im Anzeigemodus bleibt die Auswahl leer: Die Mehrfachauswahl gibt es nur im Bearbeitungsmodus, und dort
	 * ließe sie sich nicht wieder aufheben (KARV-194). Die Shell setzt den Modus vorher ({@code setEditMode}).
	 */
	@Override
	public void restoreSelection(java.util.Collection<Booking> bookings) {
		endMove(false);
		selectedBookings.clear();
		if (editMode && selectedPres != null) {
			bookings.stream().filter(booking -> booking.getParent() == selectedPres && !booking.isUnassigned()).forEach(selectedBookings::add);
			// Wurde ein Sitz gesperrt, während die Tabelle zu sehen war, kommt er nicht in die Auswahl (KARV-111)
			LockedSeats.dropFrom(selectedBookings);
		}
		renderHall();
	}

	@Override
	public void setEvent(Event event) {
		endMove(false);
		closePopup();
		selectedBookings.clear();
		this.selectedEvent = event;
		selectedPres = null;
		renderHall();
		updateHint();
	}

	@Override
	public void setPresentation(Presentation presentation) {
		endMove(false);
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
		if (moveTargets == null) {
			// Ausgeblendete Sitze bleiben nicht ausgewählt; in der Zielwahl bleibt die Auswahl bis zu ihrem Ende
			selectedBookings.removeIf(booking -> !matchesQuery(booking));
		}
		renderHall();
	}

	@Override
	public void setEditMode(boolean enabled) {
		editMode = enabled;
		applyEditMode();
	}
}
