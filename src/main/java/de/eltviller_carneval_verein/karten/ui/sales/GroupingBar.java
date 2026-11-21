package de.eltviller_carneval_verein.karten.ui.sales;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import de.eltviller_carneval_verein.karten.ui.LiveViews;
import de.eltviller_carneval_verein.karten.ui.input.TableGestures;

import javafx.beans.property.ObjectProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableBooleanValue;
import javafx.event.EventHandler;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.stage.Window;

/**
 * Die Bausteine der Leiste "Gruppieren" (KARV-193, Spec Abschnitt 14.2): vorn "Gruppiert nach:", dann je Spalte der
 * Gruppierung ein Baustein mit ihrer Überschrift und einem "×", in der Reihenfolge der Ebenen und getrennt durch "›".
 * Ohne Gruppierung steht dort nichts.
 *
 * <p>Ein Baustein lässt sich mit gedrückter linker Maustaste an eine andere Stelle ziehen. Eine Einfügemarke zeigt, wo er
 * landet: vor dem Baustein unter dem Zeiger, wenn der Zeiger in dessen linker Hälfte steht, sonst dahinter. Loslassen
 * außerhalb der Bausteine oder Esc lässt die Reihenfolge gleich, ebenso ein Druck ohne Ziehen. Gezogen wird mit den
 * gewöhnlichen Mausereignissen, nicht mit dem Drag-and-drop des Systems: Der gedrückte Baustein bekommt alle Ereignisse
 * bis zum Loslassen, und Tests kommen mit synthetischen Ereignissen aus. Geht das Loslassen verloren (modaler Dialog,
 * Fensterwechsel), endet das Ziehen ohne Wirkung beim nächsten Mausereignis ohne gedrückte linke Taste oder wenn das
 * Fenster den Fokus verliert; Esc wird nur verbraucht, wenn schon gezogen wurde. Mit der Tastatur: Tab fokussiert die Bausteine,
 * Strg+Links und Strg+Rechts verschieben den Baustein im Fokus um eine Stelle, Entf nimmt ihn heraus; der Fokus bleibt
 * auf dem verschobenen Baustein.
 *
 * <p>Jede Änderung setzt die Gruppierung wie das Menü ({@link GroupingMenu}). Die Property gilt für die ganze App
 * ({@code DisplayPreferences}), die Leiste nur so lange wie ihre Kartentabelle; sie hört deshalb nur schwach auf sie und
 * hängt sich selbst an ihre {@link HBox}.
 */
final class GroupingBar {

	/** Breite der Einfügemarke und ihr Abstand zum Baustein, vor oder hinter dem sie steht. */
	private static final double MARKER_WIDTH = 2;
	private static final double MARKER_GAP = 2;

	private final HBox chips;
	private final ObjectProperty<List<TicketColumn>> grouping;
	/** Die Einfügemarke: nicht vom Layout der Leiste gesetzt, damit die Bausteine beim Ziehen nicht springen. */
	private final Region marker = new Region();
	/** Das laufende Ziehen, sonst {@code null}. */
	private Drag drag;
	/** Liefert die Property "Fenster hat den Fokus"; in der App {@link Window#focusedProperty}, in Tests steuerbar. */
	private final Function<Window, ObservableBooleanValue> windowFocus;
	/**
	 * Esc während des Ziehens, wo immer der Fokus steht: beendet es und wird nur verbraucht, wenn schon gezogen wurde.
	 * Sonst erreicht Esc etwa eine bearbeitete Zelle, die ihre Eingabe verwirft.
	 */
	private final EventHandler<KeyEvent> escape = event -> {
		if (event.getCode() == KeyCode.ESCAPE && drag != null) {
			boolean moved = drag.moved;
			endDrag();
			if (moved) {
				event.consume();
			}
		}
	};
	/**
	 * Alle Mausereignisse der Szene während des Ziehens: Das Loslassen der linken Taste legt ab, wo immer es ankommt. Ein
	 * neuer Druck oder ein Ereignis ohne gedrückte linke Taste beendet ein Ziehen, dessen Loslassen verloren ging (etwa
	 * weil beim Druck ein modaler Dialog aufging oder das Fenster gewechselt wurde).
	 */
	private final EventHandler<MouseEvent> mouseWatch = event -> {
		if (drag == null) {
			return;
		}
		if (event.getEventType() == MouseEvent.MOUSE_RELEASED && event.getButton() == MouseButton.PRIMARY) {
			drop(event);
		} else if (event.getEventType() == MouseEvent.MOUSE_PRESSED || !event.isPrimaryButtonDown()) {
			endDrag();
		}
	};
	/** Verliert das Fenster den Fokus, endet das Ziehen ohne Wirkung. */
	private final ChangeListener<Boolean> focusWatch = (obs, before, focused) -> {
		if (!focused) {
			endDrag();
		}
	};

	/**
	 * Ein Ziehen von seinem Druck bis zum Loslassen; {@code target} ist die Einfügestelle unter dem letzten Ziehen oder -1
	 * ohne Ziel. {@code scene} und {@code focus} tragen die Wächter, die nur so lange angemeldet sind.
	 */
	private static final class Drag {
		final TicketColumn column;
		final double startX;
		final double startY;
		Scene scene;
		ObservableBooleanValue focus;
		boolean moved;
		int target = -1;

		Drag(TicketColumn column, double startX, double startY) {
			this.column = column;
			this.startX = startX;
			this.startY = startY;
		}
	}

	GroupingBar(HBox chips, ObjectProperty<List<TicketColumn>> grouping) {
		this(chips, grouping, Window::focusedProperty);
	}

	/** Wie oben; {@code windowFocus} liefert je Fenster, ob es den Fokus hat (für Tests ohne echten Fensterwechsel). */
	GroupingBar(HBox chips, ObjectProperty<List<TicketColumn>> grouping, Function<Window, ObservableBooleanValue> windowFocus) {
		this.chips = chips;
		this.grouping = grouping;
		this.windowFocus = windowFocus;
		chips.getStyleClass().add("grouping-chips");
		marker.getStyleClass().add("grouping-drop-marker");
		marker.setManaged(false);
		marker.setVisible(false);
		marker.setMouseTransparent(true);
		show();
		LiveViews.follow(chips, grouping, this::show);
	}

	/** Die Spalten der Bausteine in ihrer Reihenfolge, wie die Leiste sie zeigt. */
	List<TicketColumn> shown() {
		return chipNodes().stream().map(chip -> (TicketColumn) chip.getUserData()).toList();
	}

	/** Der Baustein einer Spalte, falls sie zur Gruppierung gehört. */
	Optional<Region> chip(TicketColumn column) {
		return chipNodes().stream().filter(chip -> chip.getUserData() == column).findFirst();
	}

	/** Die Einfügestelle des laufenden Ziehens (0 bis Anzahl der Bausteine), -1 ohne Ziel oder ohne Ziehen. */
	int dropIndex() {
		return drag == null ? -1 : drag.target;
	}

	/** Die Einfügemarke (für Tests). */
	Region marker() {
		return marker;
	}

	/**
	 * Verschiebt den Baustein an Stelle {@code from} an Stelle {@code to} der neuen Reihenfolge und setzt die Gruppierung;
	 * der Fokus geht auf ihn. Eine Stelle außerhalb oder dieselbe Stelle ändert nichts.
	 */
	void move(int from, int to) {
		List<TicketColumn> next = new ArrayList<>(current());
		if (from == to || from < 0 || to < 0 || from >= next.size() || to >= next.size()) {
			return;
		}
		TicketColumn column = next.remove(from);
		next.add(to, column);
		grouping.set(List.copyOf(next));
		chip(column).ifPresent(Node::requestFocus);
	}

	/**
	 * Nimmt die Spalte aus der Gruppierung, wie Abhaken im Menü. Stand der Fokus in der Leiste, geht er auf den Baustein,
	 * der nun an derselben Stelle steht, sonst auf den davor.
	 */
	private void remove(TicketColumn column) {
		List<TicketColumn> next = new ArrayList<>(current());
		int index = next.indexOf(column);
		if (index < 0) {
			return;
		}
		boolean focusHere = chips.getScene() != null && chipNodes().contains(chips.getScene().getFocusOwner());
		next.remove(index);
		grouping.set(List.copyOf(next));
		if (focusHere && !next.isEmpty()) {
			chip(next.get(Math.min(index, next.size() - 1))).ifPresent(Node::requestFocus);
		}
	}

	private List<TicketColumn> current() {
		return grouping.get() == null ? List.of() : grouping.get();
	}

	/** Baut die Leiste nach der Gruppierung neu auf; ein laufendes Ziehen endet ohne Wirkung. */
	private void show() {
		endDrag();
		List<TicketColumn> current = current();
		List<Node> nodes = new ArrayList<>();
		if (!current.isEmpty()) {
			nodes.add(new Label("Gruppiert nach:"));
			for (int i = 0; i < current.size(); i++) {
				if (i > 0) {
					Label separator = new Label("›");
					separator.getStyleClass().add("grouping-separator");
					nodes.add(separator);
				}
				nodes.add(newChip(current.get(i)));
			}
		}
		nodes.add(marker);
		chips.getChildren().setAll(nodes);
	}

	private HBox newChip(TicketColumn column) {
		Button remove = new Button("×");
		remove.getStyleClass().add("grouping-chip-remove");
		remove.setFocusTraversable(false); // Tab geht von Baustein zu Baustein; Entf nimmt heraus
		remove.setOnAction(event -> remove(column));
		HBox chip = new HBox(new Label(column.header()), remove);
		chip.getStyleClass().add("grouping-chip");
		chip.setAlignment(Pos.CENTER_LEFT);
		chip.setUserData(column);
		chip.setFocusTraversable(true);
		chip.addEventHandler(KeyEvent.KEY_PRESSED, event -> keyPressed(column, event));
		chip.addEventHandler(MouseEvent.MOUSE_PRESSED, event -> pressed(column, chip, event));
		chip.addEventHandler(MouseEvent.MOUSE_DRAGGED, this::dragged);
		return chip;
	}

	private List<Region> chipNodes() {
		List<Region> result = new ArrayList<>();
		for (Node node : chips.getChildren()) {
			if (node.getUserData() instanceof TicketColumn && node instanceof Region region) {
				result.add(region);
			}
		}
		return result;
	}

	private void keyPressed(TicketColumn column, KeyEvent event) {
		int index = current().indexOf(column);
		KeyCode code = event.getCode();
		if (event.isControlDown() && (code == KeyCode.LEFT || code == KeyCode.KP_LEFT)) {
			move(index, index - 1);
			event.consume(); // auch ganz vorn: sonst wanderte der Fokus
		} else if (event.isControlDown() && (code == KeyCode.RIGHT || code == KeyCode.KP_RIGHT)) {
			move(index, index + 1);
			event.consume();
		} else if (code == KeyCode.DELETE) {
			remove(column);
			event.consume();
		}
	}

	private void pressed(TicketColumn column, Node chip, MouseEvent event) {
		if (event.getButton() != MouseButton.PRIMARY || onButton(event.getTarget(), chip)) {
			return;
		}
		endDrag();
		chip.requestFocus();
		drag = new Drag(column, event.getSceneX(), event.getSceneY());
		drag.scene = chips.getScene();
		if (drag.scene != null) {
			drag.scene.addEventFilter(KeyEvent.KEY_PRESSED, escape);
			drag.scene.addEventFilter(MouseEvent.ANY, mouseWatch);
			if (drag.scene.getWindow() != null) {
				drag.focus = windowFocus.apply(drag.scene.getWindow());
				drag.focus.addListener(focusWatch);
			}
		}
		event.consume();
	}

	private void dragged(MouseEvent event) {
		if (drag == null || !event.isPrimaryButtonDown()) {
			return;
		}
		if (!drag.moved && Math.hypot(event.getSceneX() - drag.startX, event.getSceneY() - drag.startY) < TableGestures.DRAG_THRESHOLD) {
			return;
		}
		drag.moved = true;
		drag.target = dropIndexAt(event.getSceneX(), event.getSceneY());
		showMarker(drag.target);
		event.consume();
	}

	/** Loslassen der linken Taste: Nach einem Ziehen landet der Baustein an der Einfügestelle unter dem Loslassen. */
	private void drop(MouseEvent event) {
		Drag done = drag;
		endDrag();
		int target = done.moved ? dropIndexAt(event.getSceneX(), event.getSceneY()) : -1;
		if (target >= 0) {
			int from = current().indexOf(done.column);
			move(from, target > from ? target - 1 : target);
		}
	}

	/** Beendet ein laufendes Ziehen ohne Wirkung und meldet seine Wächter ab. */
	private void endDrag() {
		if (drag != null) {
			if (drag.scene != null) {
				drag.scene.removeEventFilter(KeyEvent.KEY_PRESSED, escape);
				drag.scene.removeEventFilter(MouseEvent.ANY, mouseWatch);
			}
			if (drag.focus != null) {
				drag.focus.removeListener(focusWatch);
			}
		}
		drag = null;
		marker.setVisible(false);
	}

	/** Die Einfügestelle unter dem Punkt (Szenenkoordinaten): vor dem Baustein in seiner linken Hälfte, sonst dahinter. */
	private int dropIndexAt(double sceneX, double sceneY) {
		List<Region> list = chipNodes();
		for (int i = 0; i < list.size(); i++) {
			Region chip = list.get(i);
			Point2D local = chip.sceneToLocal(sceneX, sceneY);
			Bounds bounds = chip.getLayoutBounds();
			if (local != null && bounds.contains(local)) {
				return local.getX() < bounds.getMinX() + bounds.getWidth() / 2 ? i : i + 1;
			}
		}
		return -1;
	}

	private void showMarker(int index) {
		List<Region> list = chipNodes();
		if (index < 0 || list.isEmpty()) {
			marker.setVisible(false);
			return;
		}
		Bounds bounds = list.get(Math.min(index, list.size() - 1)).getBoundsInParent();
		double x = index < list.size() ? bounds.getMinX() - MARKER_GAP - MARKER_WIDTH : bounds.getMaxX() + MARKER_GAP;
		marker.resizeRelocate(x, bounds.getMinY(), MARKER_WIDTH, bounds.getHeight());
		marker.setVisible(true);
	}

	/** Ob das Ziel ein Knopf im Baustein ist (das "×"): Dort beginnt kein Ziehen. */
	private static boolean onButton(Object target, Node chip) {
		return TableGestures.ancestor(target, node -> node instanceof ButtonBase || node == chip) instanceof ButtonBase;
	}
}
