package de.eltviller_carneval_verein.karten.ui.hall;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.model.Table;
import de.eltviller_carneval_verein.karten.model.TableRow;
import javafx.geometry.Point2D;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;

/**
 * Die Zeichnung einer Bestuhlung im Hallenplan-Editor: je Reihe eine Gruppe aus Reihenlinie, Tischen und Sitzen, dazu
 * Tische ohne gültige Reihe. Reihen, Tische und Sitze lassen sich anklicken und im Bearbeitungsmodus ziehen.
 *
 * <p>
 * Das Ziehen einer Reihe verschiebt ihren Startpunkt; das eines Tischs oder Sitzes setzt seine Position und
 * {@code manualPos}. Während der Geste werden die Knoten nur verschoben (der gedrückte Knoten muss bestehen bleiben),
 * Anordnen und Neuzeichnen übernimmt der Aufrufer beim Loslassen ({@link Callbacks#moved()}). Ein Klick ohne Ziehen
 * markiert nur, im Anzeigemodus auch.
 */
final class SeatingPlanDrawing {

	/** Was der Aufrufer (der {@link SeatingPlanController}) bei Mausaktionen tut. */
	interface Callbacks {
		/** Gedrückt: markieren, ohne neu zu zeichnen. {@code table} und {@code seat} können {@code null} sein. */
		void pressed(TableRow row, Table table, Seat seat);

		/** Während des Ziehens: Positionsfelder nachführen. */
		void moving();

		/** Nach dem Ziehen: anordnen und neu zeichnen. */
		void moved();

		/** Losgelassen ohne Ziehen: neu zeichnen, damit die Markierung erscheint. */
		void clicked();
	}

	// Sichtbare Länge einer Reihe ohne (oder mit sehr kurzen) Tischen
	private static final double MIN_ROW_LENGTH = 100;

	private final Callbacks callbacks;
	private final Group layer = new Group();
	private final Map<Table, Node> nodeByTable = new HashMap<>();
	private final Map<Seat, Node> nodeBySeat = new HashMap<>();

	private SeatingPlan plan;
	private boolean editMode;

	// Laufende Geste: Ankerpunkt, die mitbewegten Knoten und was das Ziehen am Modell ändert
	private boolean gesture = false;
	private boolean dragged = false;
	private Point2D dragAnchor;
	private List<Node> movingNodes = List.of();
	private Consumer<Point2D> mover;

	SeatingPlanDrawing(Callbacks callbacks) {
		this.callbacks = callbacks;
	}

	/** Die Gruppe, die der Hallenplan über die Hallenobjekte legt; bleibt dieselbe, wird nur neu befüllt. */
	Group layer() {
		return layer;
	}

	/**
	 * Zeichnet die Bestuhlung neu mit den markierten Reihen, dem markierten Tisch und Sitz. Während einer Geste geschieht
	 * nichts: Der gedrückte Knoten muss bis zum Loslassen bestehen bleiben.
	 */
	void redraw(SeatingPlan plan, boolean editMode, Collection<TableRow> selectedRows, Table selectedTable, Seat selectedSeat) {
		this.plan = plan;
		this.editMode = editMode;
		if (gesture) {
			return;
		}
		layer.getChildren().clear();
		nodeByTable.clear();
		nodeBySeat.clear();
		if (plan == null) {
			return;
		}
		Set<TableRow> rows = new HashSet<>(selectedRows);
		for (TableRow row : plan.getRows()) {
			double length = Math.max(MIN_ROW_LENGTH, SeatingPlanLayout.rowLength(plan, row));
			Node rowNode = PlanNodeFactory.rowNode(row, length, rows.contains(row));
			attachRowHandlers(rowNode, row);
			Group rowGroup = new Group(rowNode);
			addTables(rowGroup, plan.getTablesOf(row), selectedTable, selectedSeat);
			layer.getChildren().add(rowGroup);
		}
		// Tische ohne gültige Reihe (TBL-005) stehen da, wo sie zuletzt lagen
		List<Table> withoutRow = plan.getTables().stream().filter(t -> plan.findRow(t.getRowId()) == null).toList();
		if (!withoutRow.isEmpty()) {
			Group rest = new Group();
			addTables(rest, withoutRow, selectedTable, selectedSeat);
			layer.getChildren().add(rest);
		}
	}

	/** Erst alle Tische, dann alle Sitze: Sitze liegen oben und bleiben anklickbar. */
	private void addTables(Group parent, List<Table> tables, Table selectedTable, Seat selectedSeat) {
		for (Table table : tables) {
			// Bei markiertem Sitz ist der Sitz hervorgehoben, nicht der Tisch
			Node tableNode = PlanNodeFactory.tableNode(table, table.equals(selectedTable) && selectedSeat == null);
			attachTableHandlers(tableNode, table);
			nodeByTable.put(table, tableNode);
			parent.getChildren().add(tableNode);
		}
		for (Table table : tables) {
			for (Seat seat : table.getSeats()) {
				Node seatNode = PlanNodeFactory.seatNode(seat, seat.equals(selectedSeat));
				attachSeatHandlers(seatNode, seat);
				nodeBySeat.put(seat, seatNode);
				parent.getChildren().add(seatNode);
			}
		}
	}

	// --- Maus -->

	private void attachRowHandlers(Node node, TableRow row) {
		attachHandlers(node, () -> {
			callbacks.pressed(row, null, null);
			double anchorX = row.getPosX();
			double anchorY = row.getPosY();
			// Mit der Reihe wandern ihre automatisch angeordneten Tische und Sitze, manuell gesetzte bleiben stehen
			List<Node> nodes = new ArrayList<>(List.of(node));
			for (Table table : plan.getTablesOf(row)) {
				if (!table.isManualPos()) {
					nodes.add(nodeByTable.get(table));
					nodes.addAll(autoSeatNodes(table));
				}
			}
			startGesture(nodes, delta -> {
				row.setPosX(anchorX + delta.getX());
				row.setPosY(anchorY + delta.getY());
			});
		});
	}

	private void attachTableHandlers(Node node, Table table) {
		attachHandlers(node, () -> {
			callbacks.pressed(plan.findRow(table.getRowId()), table, null);
			double anchorX = table.getPosX();
			double anchorY = table.getPosY();
			List<Node> nodes = new ArrayList<>(List.of(node));
			nodes.addAll(autoSeatNodes(table));
			startGesture(nodes, delta -> {
				table.setManualPos(true);
				table.setPosX(anchorX + delta.getX());
				table.setPosY(anchorY + delta.getY());
			});
		});
	}

	private void attachSeatHandlers(Node node, Seat seat) {
		attachHandlers(node, () -> {
			Table table = seat.getParent();
			callbacks.pressed(table == null ? null : plan.findRow(table.getRowId()), table, seat);
			double anchorX = seat.getPosX();
			double anchorY = seat.getPosY();
			startGesture(List.of(node), delta -> {
				seat.setManualPos(true);
				seat.setPosX(anchorX + delta.getX());
				seat.setPosY(anchorY + delta.getY());
			});
		});
	}

	/** Die Knoten der Sitze ohne {@code manualPos}: Sie folgen ihrem Tisch. */
	private List<Node> autoSeatNodes(Table table) {
		return table.getSeats().stream().filter(seat -> !seat.isManualPos()).map(nodeBySeat::get).filter(Objects::nonNull).toList();
	}

	private void attachHandlers(Node node, Runnable onPress) {
		node.setCursor(editMode ? Cursor.MOVE : Cursor.HAND);
		node.setOnMousePressed(event -> {
			// Rechte/mittlere Taste nicht behandeln, damit sie zum Verschieben des Plans durchgereicht werden
			if (event.getButton() != MouseButton.PRIMARY) {
				return;
			}
			event.consume();
			dragAnchor = layer.sceneToLocal(event.getSceneX(), event.getSceneY());
			dragged = false;
			// Vor dem Markieren: Ein Neuzeichnen beim Markieren würde den gedrückten Knoten ersetzen
			gesture = true;
			onPress.run();
		});
		node.setOnMouseDragged(this::drag);
		node.setOnMouseReleased(this::release);
	}

	/** Legt fest, welche Knoten die Geste mitbewegt und was sie am Modell ändert. */
	private void startGesture(List<Node> nodes, Consumer<Point2D> mover) {
		this.movingNodes = nodes.stream().filter(Objects::nonNull).toList();
		this.mover = mover;
	}

	private void drag(MouseEvent event) {
		if (event.getButton() != MouseButton.PRIMARY) {
			return;
		}
		event.consume();
		// Ein Klick mit leichtem Zittern ist kein Ziehen: Er soll keine Position auf "manuell" setzen
		if (!editMode || !gesture || dragAnchor == null || (!dragged && event.isStillSincePress())) {
			return;
		}
		Point2D current = layer.sceneToLocal(event.getSceneX(), event.getSceneY());
		Point2D delta = current.subtract(dragAnchor);
		mover.accept(delta);
		dragged = true;
		for (Node node : movingNodes) {
			node.setTranslateX(delta.getX());
			node.setTranslateY(delta.getY());
		}
		callbacks.moving();
	}

	private void release(MouseEvent event) {
		if (event.getButton() != MouseButton.PRIMARY) {
			return;
		}
		event.consume();
		boolean wasDragged = dragged;
		gesture = false;
		dragged = false;
		dragAnchor = null;
		movingNodes = List.of();
		mover = null;
		if (wasDragged) {
			callbacks.moved();
		} else {
			callbacks.clicked();
		}
	}
	// <-- Maus
}
