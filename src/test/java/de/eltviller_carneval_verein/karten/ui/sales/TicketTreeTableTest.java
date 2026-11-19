package de.eltviller_carneval_verein.karten.ui.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.ui.input.ParsingTextFieldTreeTableCell;
import de.eltviller_carneval_verein.karten.ui.input.TableInput;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.IndexedCell;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableCell;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTablePosition;
import javafx.scene.control.TreeTableRow;
import javafx.scene.control.TreeTableView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.stage.Stage;

/**
 * Die Baumtabelle der Kartentabelle (KARV-193): Auswahl als Menge von Buchungen, Bedienung mit Maus und Tasten,
 * Neuaufbau und Sortierung. Die Tabelle wird hier ohne FXML und ohne Controller gebaut.
 */
class TicketTreeTableTest {

	/** Spalten der Tabelle: 0 Pfeil und Einrückung, 1 Nachname, 2 Vorname. */
	private static final int TREE = 0;
	private static final int LAST = 1;
	private static final int FIRST = 2;

	private final Event event = new Event();
	private final Presentation presentation = event.addPresentation("Freitag");
	private final TestBookings data = new TestBookings();
	private final List<Booking> bookings = new ArrayList<>();

	private final Booking m1 = booking("Müller", "Anna");
	private final Booking m2 = booking("Müller", "Bernd");
	private final Booking s1 = booking("Schmidt", "Clara");
	private final Booking w1 = booking("Weber", "Dora");
	private final Booking w2 = booking("Weber", "Emil");

	private List<TicketColumn> grouping = List.of(TicketColumn.LAST_NAME);
	private final TicketFilter filter = new TicketFilter();
	private Stage stage;
	private TreeTableView<TicketRow> table;
	private TicketTreeTable tree;
	private int rebuilds;

	private Booking booking(String lastName, String firstName) {
		Booking booking = data.booking(presentation, 1, bookings.size() + 1);
		booking.setLastName(lastName);
		booking.setFirstName(firstName);
		bookings.add(booking);
		return booking;
	}

	@BeforeAll
	static void startFx() {
		try {
			Platform.startup(() -> {
			});
		} catch (IllegalStateException alreadyStarted) {
			// der Toolkit läuft schon (anderer Test im selben Lauf)
		}
		Platform.setImplicitExit(false);
	}

	private static <T> T onFx(Supplier<T> action) throws Exception {
		CompletableFuture<T> result = new CompletableFuture<>();
		Platform.runLater(() -> {
			try {
				result.complete(action.get());
			} catch (Throwable e) {
				result.completeExceptionally(e);
			}
		});
		return result.get(10, TimeUnit.SECONDS);
	}

	private static void run(Runnable action) throws Exception {
		onFx(() -> {
			action.run();
			return null;
		});
		flush();
	}

	/** Wartet, bis alles durch ist, was mit {@code Platform.runLater} nachgeschoben wurde. */
	private static void flush() throws Exception {
		onFx(() -> null);
		onFx(() -> null);
		onFx(() -> null);
	}

	@AfterEach
	void closeStage() throws Exception {
		if (stage != null) {
			onFx(() -> {
				stage.hide();
				return null;
			});
		}
	}

	// ---- Aufbau ----

	private List<TicketNode> nodes() {
		List<BookingDTO> rows = bookings.stream().map(b -> new BookingDTO(event, presentation, b)).filter(filter::passes).toList();
		return TicketGrouping.group(rows, grouping, GroupOrder.VALUE, tree.sortComparator(), event.getPresentations());
	}

	private static TreeTableColumn<TicketRow, String> column(String title, java.util.function.Function<TicketRow, String> value) {
		TreeTableColumn<TicketRow, String> column = new TreeTableColumn<>(title);
		column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue().getValue())));
		column.setPrefWidth(150);
		return column;
	}

	private static String bookingText(TicketRow row, java.util.function.Function<Booking, String> value) {
		return row instanceof TicketRow.BookingRow booking ? value.apply(booking.dto().getBooking()) : null;
	}

	/** Zeigt die Tabelle mit Gruppierung {@link #grouping}; die Bedienung von {@link TableInput} hängt hinter der Baumtabelle. */
	private void show() throws Exception {
		run(() -> {
			table = new TreeTableView<>();
			table.setEditable(true);
			TreeTableColumn<TicketRow, String> colTree = column("", row -> row instanceof TicketRow.GroupRow group
					? group.column().header() + ": " + group.text() + " (" + group.count() + ")" : null);
			colTree.setPrefWidth(200);
			colTree.setSortable(false);
			colTree.setEditable(false);
			TreeTableColumn<TicketRow, String> colLast = column("Nachname", row -> bookingText(row, Booking::getLastName));
			TreeTableColumn<TicketRow, String> colFirst = column("Vorname", row -> bookingText(row, Booking::getFirstName));
			for (TreeTableColumn<TicketRow, String> data : List.of(colLast, colFirst)) {
				data.setEditable(true);
				data.setCellFactory(ParsingTextFieldTreeTableCell.forTextColumn(row -> row instanceof TicketRow.BookingRow));
			}
			table.getColumns().setAll(List.of(colTree, colLast, colFirst));
			tree = new TicketTreeTable(table, colTree);
			tree.setRebuild(() -> {
				rebuilds++;
				tree.show(nodes());
			});
			TableInput.install(table, row -> row instanceof TicketRow.BookingRow);
			tree.show(nodes());
			stage = new Stage();
			stage.setScene(new Scene(table, 700, 500));
			stage.show();
			table.applyCss();
			table.layout();
			table.requestFocus();
		});
	}

	private void rebuild() throws Exception {
		run(() -> tree.show(nodes()));
	}

	// ---- Zugriff ----

	private TreeItem<TicketRow> item(int row) throws Exception {
		return onFx(() -> table.getTreeItem(row));
	}

	private List<Booking> selected() throws Exception {
		return onFx(() -> List.copyOf(tree.selectedBookings()));
	}

	private List<Integer> modelRows() throws Exception {
		return onFx(() -> List.copyOf(table.getSelectionModel().getSelectedIndices()));
	}

	private int focusedRow() throws Exception {
		return onFx(() -> table.getFocusModel().getFocusedIndex());
	}

	private TreeTableColumn<TicketRow, ?> focusedColumn() throws Exception {
		return onFx(() -> {
			TreeTablePosition<TicketRow, ?> focused = table.getFocusModel().getFocusedCell();
			return focused == null ? null : focused.getTableColumn();
		});
	}

	private boolean groupSelected(int row) throws Exception {
		TreeItem<TicketRow> item = item(row);
		return onFx(() -> tree.isGroupSelected(item));
	}

	private void setExpanded(int row, boolean expanded) throws Exception {
		TreeItem<TicketRow> item = item(row);
		run(() -> item.setExpanded(expanded));
	}

	private TreeTableRow<?> rowNode(int row) {
		table.layout();
		for (Node node : table.lookupAll(".tree-table-row-cell")) {
			if (node instanceof TreeTableRow<?> tableRow && tableRow.getIndex() == row && !tableRow.isEmpty()) {
				return tableRow;
			}
		}
		throw new AssertionError("keine Zeile " + row);
	}

	private IndexedCell<?> cell(int row, int column) {
		for (Node node : rowNode(row).lookupAll(".tree-table-cell")) {
			if (node instanceof TreeTableCell<?, ?> candidate && candidate.getTableColumn() == table.getColumns().get(column)) {
				return candidate;
			}
		}
		throw new AssertionError("keine Zelle in Zeile " + row + ", Spalte " + column);
	}

	// ---- Eingaben ----

	private void fireMouse(Node target, javafx.event.EventType<MouseEvent> type, double sceneX, double sceneY, int clicks, boolean shift,
			boolean ctrl) {
		PickResult pick = new PickResult(target, sceneX, sceneY);
		javafx.event.Event.fireEvent(target, new MouseEvent(this, target, type, sceneX, sceneY, sceneX, sceneY, MouseButton.PRIMARY, clicks,
				shift, ctrl, false, false, type != MouseEvent.MOUSE_RELEASED, false, false, false, false, true, pick));
	}

	private void clickAt(int row, int column, double[] at, int clicks, boolean shift, boolean ctrl) throws Exception {
		run(() -> {
			IndexedCell<?> target = cell(row, column);
			double[] point = at;
			if (point == null) {
				Bounds bounds = target.localToScene(target.getBoundsInLocal());
				point = new double[] { (bounds.getMinX() + bounds.getMaxX()) / 2, (bounds.getMinY() + bounds.getMaxY()) / 2 };
			}
			fireMouse(target, MouseEvent.MOUSE_PRESSED, point[0], point[1], clicks, shift, ctrl);
			fireMouse(target, MouseEvent.MOUSE_RELEASED, point[0], point[1], clicks, shift, ctrl);
			fireMouse(target, MouseEvent.MOUSE_CLICKED, point[0], point[1], clicks, shift, ctrl);
		});
	}

	private void click(int row) throws Exception {
		clickAt(row, LAST, null, 1, false, false);
	}

	private void click(int row, int column) throws Exception {
		clickAt(row, column, null, 1, false, false);
	}

	private void ctrlClick(int row) throws Exception {
		clickAt(row, LAST, null, 1, false, true);
	}

	private void shiftClick(int row) throws Exception {
		clickAt(row, LAST, null, 1, true, false);
	}

	/** Klick auf den Pfeil zum Auf- und Zuklappen der Zeile. */
	private void clickArrow(int row) throws Exception {
		double[] at = onFx(() -> {
			Node arrow = rowNode(row).getDisclosureNode();
			assertNotNull(arrow, "die Zeile hat einen Pfeil");
			assertTrue(arrow.isVisible(), "der Pfeil ist sichtbar");
			Bounds bounds = arrow.localToScene(arrow.getBoundsInLocal());
			return new double[] { (bounds.getMinX() + bounds.getMaxX()) / 2, (bounds.getMinY() + bounds.getMaxY()) / 2 };
		});
		clickAt(row, TREE, at, 1, false, false);
	}

	private void press(KeyCode code, boolean shift, boolean ctrl) throws Exception {
		run(() -> javafx.event.Event.fireEvent(table,
				new KeyEvent(this, table, KeyEvent.KEY_PRESSED, KeyEvent.CHAR_UNDEFINED, "", code, shift, ctrl, false, false)));
	}

	private void press(KeyCode code) throws Exception {
		press(code, false, false);
	}

	private void type(String text) throws Exception {
		run(() -> {
			javafx.event.Event.fireEvent(table, new KeyEvent(this, table, KeyEvent.KEY_TYPED, text, "", KeyCode.UNDEFINED, false, false, false, false));
			table.layout();
		});
	}

	private int editingRow() throws Exception {
		return onFx(() -> table.getEditingCell() == null ? -1 : table.getEditingCell().getRow());
	}

	private void stopEditing() throws Exception {
		run(() -> table.edit(-1, null));
	}

	// ---- Fälle ----
	// Zeilen mit Gruppierung nach Nachname, alles aufgeklappt:
	// 0 Müller (2), 1 m1, 2 m2, 3 Schmidt (1), 4 s1, 5 Weber (2), 6 w1, 7 w2

	@Test
	void klickAufGruppenkopfWaehltGruppe() throws Exception {
		show();
		click(4);

		click(0);

		assertEquals(List.of(m1, m2), selected());
		assertEquals(List.of(1, 2), modelRows(), "das Auswahlmodell enthält nur die Buchungszeilen");
		assertTrue(groupSelected(0));
		assertFalse(groupSelected(3));
		assertEquals(0, focusedRow(), "der Fokus steht auf dem Gruppenkopf");
	}

	@Test
	void zugeklappteGruppeZaehltInSelectedBookings() throws Exception {
		show();
		click(0);

		setExpanded(0, false);

		assertEquals(List.of(m1, m2), selected());
		assertEquals(List.of(), modelRows());
		assertTrue(groupSelected(0));
		// Zeilen jetzt: 0 Müller (zu), 1 Schmidt, 2 s1, ...
		ctrlClick(2);
		assertEquals(List.of(m1, m2, s1), selected());
		assertEquals(List.of(2), modelRows());
	}

	@Test
	void zuklappenMitHauptauswahlAusserhalbUeberschreibtJavaFx() throws Exception {
		show();
		click(0);
		ctrlClick(4); // die Hauptauswahl liegt jetzt außerhalb von Müller

		clickArrow(0);

		assertFalse(onFx(() -> table.getTreeItem(0).isExpanded()), "der Pfeil klappt zu");
		assertEquals(List.of(m1, m2, s1), selected(), "eine ganz gewählte Gruppe bleibt beim Zuklappen gewählt");
		assertTrue(groupSelected(0));
		assertEquals(List.of(2), modelRows());

		// teilweise gewählt: der gewählte Teil fällt beim Zuklappen heraus
		clickArrow(0);
		assertEquals(List.of(m1, m2, s1), selected(), "Aufklappen ändert die Auswahl nicht");
		assertEquals(List.of(1, 2, 4), modelRows());
		ctrlClick(1);
		assertEquals(List.of(m2, s1), selected());
		clickArrow(0);
		assertEquals(List.of(s1), selected());
		assertFalse(groupSelected(0));
	}

	@Test
	void neuaufbauKlapptTeilweiseGewaehlteGruppeAuf() throws Exception {
		show();
		click(5);
		setExpanded(5, false);
		assertEquals(List.of(w1, w2), selected());

		Booking w3 = booking("Weber", "Fritz"); // fremde Änderung: eine neue Buchung kommt in die Gruppe
		rebuild();

		assertEquals(List.of(w1, w2), selected(), "die gewählte Gruppe wächst nicht von selbst");
		assertTrue(onFx(() -> table.getTreeItem(5).isExpanded()), "die nur noch teilweise gewählte Gruppe ist aufgeklappt");
		assertFalse(groupSelected(5));
		assertEquals(List.of(6, 7), modelRows());
		assertSame(w3, onFx(() -> ((TicketRow.BookingRow) table.getTreeItem(8).getValue()).dto().getBooking()));
	}

	@Test
	void neuaufbauBehaeltZugeklappteGruppen() throws Exception {
		show();
		setExpanded(5, false);
		booking("Albers", "Gerd");

		rebuild();

		// 0 Albers, 1 a, 2 Müller, 3 m1, 4 m2, 5 Schmidt, 6 s1, 7 Weber (zu)
		assertFalse(onFx(() -> table.getTreeItem(7).isExpanded()));
		assertEquals(8, onFx(() -> table.getExpandedItemCount()));
	}

	@Test
	void restoreSelectionKlapptTeilweiseAuf() throws Exception {
		show();
		setExpanded(0, false);

		run(() -> tree.restoreSelection(List.of(m1, booking("Neu", "Hans"))));

		assertEquals(List.of(m1), selected(), "nur Buchungen, die in der Tabelle stehen");
		assertTrue(onFx(() -> table.getTreeItem(0).isExpanded()));
		assertEquals(List.of(1), modelRows());
	}

	@Test
	void deselectKlapptGanzGewaehlteGruppeAuf() throws Exception {
		show();
		click(0);
		setExpanded(0, false);

		run(() -> tree.deselect(List.of(m2)));

		assertEquals(List.of(m1), selected());
		assertTrue(onFx(() -> table.getTreeItem(0).isExpanded()));
		assertEquals(List.of(1), modelRows());
	}

	@Test
	void selectAndRevealKlapptAufUndWaehlt() throws Exception {
		show();
		click(4);
		setExpanded(5, false);

		run(() -> tree.selectAndReveal(w2));

		assertEquals(List.of(w2), selected());
		assertTrue(onFx(() -> table.getTreeItem(5).isExpanded()));
		assertEquals(List.of(7), modelRows());
		assertEquals(7, focusedRow());
	}

	@Test
	void sortierungUeberlebtNeuaufbau() throws Exception {
		show();
		TreeTableColumn<TicketRow, ?> colFirst = onFx(() -> table.getColumns().get(FIRST));
		rebuilds = 0;

		run(() -> {
			colFirst.setSortType(TreeTableColumn.SortType.DESCENDING);
			table.getSortOrder().setAll(List.of(colFirst));
		});

		assertTrue(rebuilds > 0, "die Sortierung stößt den Neuaufbau an");
		assertEquals(List.of(colFirst), onFx(() -> List.copyOf(table.getSortOrder())));
		assertSame(m2, bookingAt(1), "absteigend nach Vorname innerhalb der Gruppe");
		assertSame(m1, bookingAt(2));
		assertSame(w2, bookingAt(6));

		booking("Müller", "Carl"); // fremde Änderung
		rebuild();

		assertEquals(List.of(colFirst), onFx(() -> List.copyOf(table.getSortOrder())), "die Sortierung bleibt nach dem Neuaufbau");
		assertEquals(List.of("Carl", "Bernd", "Anna"), List.of(bookingAt(1).getFirstName(), bookingAt(2).getFirstName(), bookingAt(3).getFirstName()));
	}

	/**
	 * Fall aus KARV-196: Die Rückkehr aus der Saalansicht wählt dieselben Buchungen, auch wenn die Tabelle sortiert und
	 * nach einer Spalte gefiltert ist; eine ausgefilterte Buchung bleibt ungewählt.
	 */
	@Test
	void rueckkehrAusSaalansichtWaehltRichtigeInSortierterGefilterterTabelle() throws Exception {
		grouping = List.of();
		filter.setDeselected(TicketColumn.LAST_NAME, java.util.Set.of(TicketColumn.LAST_NAME.keyOf(new BookingDTO(event, presentation, s1))));
		show();
		TreeTableColumn<TicketRow, ?> colLast = onFx(() -> table.getColumns().get(LAST));
		run(() -> {
			colLast.setSortType(TreeTableColumn.SortType.DESCENDING);
			table.getSortOrder().setAll(List.of(colLast));
		});
		// Zeilen jetzt: 0 w1, 1 w2, 2 m1, 3 m2 (Schmidt ausgefiltert)
		assertSame(w1, bookingAt(0));

		run(() -> tree.restoreSelection(List.of(s1, m1, w2)));

		assertEquals(List.of(w2, m1), selected(), "in Reihenfolge der Tabelle, ohne die ausgefilterte Buchung");
		assertEquals(List.of(1, 2), modelRows());
	}

	@Test
	void sortComparatorOhneSortierungIstNull() throws Exception {
		show();
		assertNull(onFx(() -> tree.sortComparator()));
	}

	private Booking bookingAt(int row) throws Exception {
		return onFx(() -> ((TicketRow.BookingRow) table.getTreeItem(row).getValue()).dto().getBooking());
	}

	@Test
	void pfeilAbAufGruppenzeileWaehltGruppe() throws Exception {
		show();
		click(2);

		press(KeyCode.DOWN);

		assertEquals(3, focusedRow());
		assertEquals(List.of(s1), selected());
		assertEquals(List.of(4), modelRows());
		assertTrue(groupSelected(3));

		press(KeyCode.UP);
		assertEquals(2, focusedRow());
		assertEquals(List.of(m2), selected());

		press(KeyCode.DOWN, false, true); // Strg: nur der Fokus wandert
		assertEquals(3, focusedRow());
		assertEquals(List.of(m2), selected());

		press(KeyCode.DOWN, true, false); // Umschalt: Bereich vom Anker m2 bis s1
		assertEquals(List.of(m2, s1), selected());
	}

	@Test
	void linksRechtsWechselnSpalteInBuchungszeile() throws Exception {
		show();
		click(1, LAST);

		press(KeyCode.RIGHT);

		assertEquals(1, focusedRow());
		assertSame(onFx(() -> table.getColumns().get(FIRST)), focusedColumn());
		assertEquals(List.of(m1), selected());
		assertTrue(onFx(() -> table.getTreeItem(0).isExpanded()), "Rechts klappt in einer Buchungszeile nichts");

		press(KeyCode.LEFT);
		assertSame(onFx(() -> table.getColumns().get(LAST)), focusedColumn());
		press(KeyCode.LEFT);
		assertSame(onFx(() -> table.getColumns().get(LAST)), focusedColumn(), "die Spalte für Pfeil und Einrückung zählt nicht");
		assertEquals(List.of(m1), selected());

		// danach bearbeitet Enter die Zelle im Fokus
		press(KeyCode.RIGHT);
		press(KeyCode.ENTER);
		assertSame(onFx(() -> table.getColumns().get(FIRST)), onFx(() -> table.getEditingCell() == null ? null : table.getEditingCell().getTableColumn()));
		stopEditing();
	}

	@Test
	void linksRechtsKlappenGruppenkopf() throws Exception {
		grouping = List.of(TicketColumn.LAST_NAME, TicketColumn.FIRST_NAME);
		// 0 Müller, 1 Anna, 2 m1, 3 Bernd, 4 m2, 5 Schmidt, ...
		show();
		click(6);
		run(() -> table.getFocusModel().focus(3, table.getColumns().get(LAST)));

		press(KeyCode.LEFT);
		assertFalse(onFx(() -> table.getTreeItem(3).isExpanded()), "Links klappt die Gruppe zu");
		assertEquals(3, focusedRow());

		press(KeyCode.LEFT);
		assertEquals(0, focusedRow(), "steht sie schon zu, geht der Fokus auf die Gruppe darüber");

		run(() -> table.getFocusModel().focus(3, table.getColumns().get(LAST)));
		press(KeyCode.RIGHT);
		assertTrue(onFx(() -> table.getTreeItem(3).isExpanded()), "Rechts klappt auf");
		assertEquals(List.of(s1), selected(), "die Menge bleibt");
	}

	@Test
	void plusMinusSternAufGruppenkopf() throws Exception {
		grouping = List.of(TicketColumn.LAST_NAME, TicketColumn.FIRST_NAME);
		show();
		setExpanded(3, false); // Bernd
		setExpanded(1, false); // Anna
		click(0);
		setExpanded(0, false); // Müller

		press(KeyCode.ADD);
		assertTrue(onFx(() -> table.getTreeItem(0).isExpanded()));
		assertFalse(onFx(() -> table.getTreeItem(1).isExpanded()));

		press(KeyCode.SUBTRACT);
		assertFalse(onFx(() -> table.getTreeItem(0).isExpanded()));

		press(KeyCode.MULTIPLY);
		assertTrue(onFx(() -> table.getTreeItem(0).isExpanded()));
		assertTrue(onFx(() -> table.getTreeItem(1).isExpanded()));
		assertTrue(onFx(() -> table.getTreeItem(3).isExpanded()));
		assertEquals(List.of(m1, m2), selected(), "die Menge bleibt");

		click(2); // m1
		press(KeyCode.SUBTRACT);
		assertTrue(onFx(() -> table.getTreeItem(1).isExpanded()), "Minus in einer Buchungszeile wirkt nicht");
		assertEquals(List.of(m1), selected());
	}

	@Test
	void getipptesMinusBleibtFuerBearbeiten() throws Exception {
		show();
		click(1, FIRST);

		press(KeyCode.SUBTRACT);
		assertEquals(-1, editingRow());
		type("-");

		assertEquals(1, editingRow(), "das getippte Minus beginnt das Bearbeiten");
		assertEquals("-", onFx(() -> cell(1, FIRST).getGraphic() instanceof TextField field ? field.getText() : null));
		stopEditing();
	}

	@Test
	void enterAufGruppenkopfOhneWirkung() throws Exception {
		show();
		click(0);

		press(KeyCode.ENTER);
		assertEquals(-1, editingRow());
		press(KeyCode.F2);
		assertEquals(-1, editingRow());

		assertEquals(List.of(1, 2), modelRows(), "die Gruppenzeile kommt nicht ins Auswahlmodell");
		assertEquals(List.of(m1, m2), selected());
	}

	@Test
	void umschaltKlickNachKopfRechnetVomKopf() throws Exception {
		show();
		click(3);

		shiftClick(6);

		assertEquals(List.of(s1, w1), selected(), "Bereich vom Kopf Schmidt bis w1, nur Buchungszeilen");
		assertFalse(groupSelected(5));
		assertEquals(6, focusedRow());

		shiftClick(1);
		assertEquals(List.of(m1, m2), selected(), "der Anker bleibt auf dem Kopf");
	}

	@Test
	void bereichUeberZugeklappteGruppeTraegtNichtsBei() throws Exception {
		show();
		setExpanded(3, false);
		// 0 Müller, 1 m1, 2 m2, 3 Schmidt (zu), 4 Weber, 5 w1, 6 w2
		click(2);

		shiftClick(5);

		assertEquals(List.of(m2, w1), selected());
	}

	/** Drückt in Zeile {@code from} und zieht bis Zeile {@code to}, jeweils Spalte Nachname (Ziehen aus TableInput). */
	private void drag(int from, int to, boolean ctrl) throws Exception {
		run(() -> {
			IndexedCell<?> start = cell(from, LAST);
			Bounds a = start.localToScene(start.getBoundsInLocal());
			IndexedCell<?> end = cell(to, LAST);
			Bounds b = end.localToScene(end.getBoundsInLocal());
			double x = (a.getMinX() + a.getMaxX()) / 2;
			double y0 = (a.getMinY() + a.getMaxY()) / 2;
			double y1 = (b.getMinY() + b.getMaxY()) / 2;
			fireMouse(start, MouseEvent.MOUSE_PRESSED, x, y0, 1, false, ctrl);
			for (int i = 1; i <= 4; i++) {
				fireMouse(start, MouseEvent.MOUSE_DRAGGED, x, y0 + (y1 - y0) * i / 4, 1, false, ctrl);
			}
			fireMouse(start, MouseEvent.MOUSE_RELEASED, x, y1, 1, false, ctrl);
		});
	}

	@Test
	void ziehenWaehltBuchungszeilenDesBereichs() throws Exception {
		show();
		setExpanded(5, false);
		click(5); // Weber, zugeklappt

		drag(1, 2, true);
		assertEquals(List.of(m1, m2, w1, w2), selected(), "mit Strg kommt der Bereich zur Menge, die zugeklappte Gruppe bleibt");

		drag(2, 4, false);
		assertEquals(List.of(m2, s1), selected(), "ohne Strg nur die Buchungszeilen des Bereichs");
		assertEquals(List.of(2, 4), modelRows(), "die Gruppenzeile im Bereich steht nicht im Auswahlmodell");
	}

	@Test
	void strgKlickAufGanzGewaehlteGruppeNimmtAlleHeraus() throws Exception {
		show();
		click(0);
		ctrlClick(4);

		ctrlClick(0);
		assertEquals(List.of(s1), selected());
		ctrlClick(0);
		assertEquals(List.of(m1, m2, s1), selected());
	}

	@Test
	void leertasteAufGruppenkopfFuegtHinzu() throws Exception {
		show();
		click(4);
		run(() -> table.getFocusModel().focus(5, table.getColumns().get(LAST)));

		press(KeyCode.SPACE);

		assertEquals(List.of(s1, w1, w2), selected());
		assertEquals(-1, editingRow());
	}

	@Test
	void einzelneBuchungAlleinInGruppeBearbeitbarNachKlick() throws Exception {
		show();
		click(3);
		assertEquals(List.of(4), modelRows(), "genau ein Index, auch wenn die Buchung allein in ihrer Gruppe steht");

		click(4, LAST);

		assertEquals(4, editingRow(), "der nächste Klick auf die Buchung startet das Bearbeiten");
		stopEditing();
	}

	@Test
	void keineRueckkopplungBeimZuklappen() throws Exception {
		show();
		List<List<Booking>> reports = new ArrayList<>();
		run(() -> tree.selectedBookingsProperty().addListener(obs -> reports.add(List.copyOf(tree.selectedBookingsProperty().get()))));

		click(1);
		ctrlClick(6); // Hauptauswahl außerhalb von Müller
		clickArrow(0); // m1 fällt heraus
		clickArrow(0); // Aufklappen ändert nichts

		assertEquals(List.of(List.of(m1), List.of(m1, w1), List.of(w1)), reports, "genau eine Meldung je Bedienung, keine Zwischenstände");
		assertEquals(List.of(6), modelRows());
	}

	@Test
	void fokusFolgtBuchungNachNeuaufbau() throws Exception {
		show();
		click(4);

		m1.setLastName("Zimmer");
		rebuild();

		// 0 Müller (1), 1 m2, 2 Schmidt, 3 s1, 4 Weber, 5 w1, 6 w2, 7 Zimmer, 8 m1
		assertEquals(3, focusedRow(), "der Fokus folgt der Buchung, nicht dem Index");
		assertEquals(List.of(s1), selected());
		assertEquals(List.of(3), modelRows());

		click(4); // Kopf Weber
		m2.setLastName("Albers");
		rebuild();
		// 0 Albers, 1 m2, 2 Schmidt, 3 s1, 4 Weber, ...
		assertEquals(4, focusedRow(), "der Fokus folgt dem Pfad der Gruppe");

		w1.setLastName("Xaver");
		w2.setLastName("Xaver");
		rebuild();
		assertEquals(-1, focusedRow(), "gibt es die Gruppe nicht mehr, steht der Fokus auf keiner Zeile");
		assertEquals(List.of(w1, w2), selected(), "die Buchungen bleiben gewählt");
	}

	private Booking focusedBooking() throws Exception {
		return onFx(() -> table.getFocusModel().getFocusedItem() != null
				&& table.getFocusModel().getFocusedItem().getValue() instanceof TicketRow.BookingRow row ? row.dto().getBooking() : null);
	}

	@Test
	void fokusBleibtBeimKlappenDarueber() throws Exception {
		show();
		click(4); // s1

		clickArrow(0); // Müller zu
		assertEquals(2, focusedRow(), "der Fokus bleibt auf s1");
		assertSame(s1, focusedBooking());

		clickArrow(0); // Müller auf
		assertEquals(4, focusedRow());
		assertSame(s1, focusedBooking());
		assertEquals(List.of(s1), selected());
	}

	@Test
	void fokusBleibtBeimKlappenMitTasten() throws Exception {
		show();
		click(5); // Kopf Weber

		press(KeyCode.LEFT);
		assertEquals(5, focusedRow());
		press(KeyCode.RIGHT);
		assertEquals(5, focusedRow());
		press(KeyCode.SUBTRACT);
		assertEquals(5, focusedRow());
		press(KeyCode.MULTIPLY);
		assertEquals(5, focusedRow());

		click(4); // s1
		run(() -> table.getTreeItem(0).setExpanded(false)); // Müller zu, wie mit Minus auf dem Kopf
		assertEquals(2, focusedRow());
		assertSame(s1, focusedBooking());
	}

	@Test
	void fokusBleibtBeimAufklappenNachRegel3UndClearCollapsed() throws Exception {
		show();
		setExpanded(0, false);
		click(2); // s1

		run(() -> tree.restoreSelection(List.of(m1, s1))); // Regel 3 klappt Müller auf
		assertTrue(onFx(() -> table.getTreeItem(0).isExpanded()));
		assertEquals(4, focusedRow(), "der Fokus bleibt auf s1");

		setExpanded(0, false);
		assertEquals(2, focusedRow());
		run(() -> tree.clearCollapsed());
		assertEquals(4, focusedRow());
		assertSame(s1, focusedBooking());
	}

	@Test
	void zuklappenUeberDemFokusFokussiertDieGruppe() throws Exception {
		show();
		click(2); // m2

		clickArrow(0);

		assertEquals(0, focusedRow(), "ist die Zeile verdeckt, steht der Fokus auf ihrer Gruppe");
		assertEquals(List.of(), selected());
	}

	@Test
	void gleicheStrukturZeichntNurNeu() throws Exception {
		show();
		TreeItem<TicketRow> before = item(1);

		rebuild();

		assertSame(before, item(1), "dieselbe Struktur behält die Zeilen");
		m1.setFirstName("Anja");
		rebuild();
		assertSame(before, item(1), "ein geänderter Wert ohne andere Struktur zeichnet nur neu");
	}

	@Test
	void strgAWaehltAuchZugeklappte() throws Exception {
		show();
		setExpanded(5, false);
		click(1);

		press(KeyCode.A, false, true);

		assertEquals(List.of(m1, m2, s1, w1, w2), selected());
		assertTrue(groupSelected(5));
		assertEquals(List.of(1, 2, 4), modelRows());
		assertEquals(List.of(m1, m2, s1, w1, w2), onFx(() -> tree.allBookings()));
	}

	@Test
	void eingefrorenAendertNichts() throws Exception {
		show();
		click(1);
		boolean[] frozen = { true };
		run(() -> tree.setFrozen(() -> frozen[0]));

		click(5);
		press(KeyCode.DOWN);
		press(KeyCode.A, false, true);
		clickArrow(0);
		run(() -> table.getFocusModel().focus(3, table.getColumns().get(LAST)));
		press(KeyCode.LEFT);
		press(KeyCode.SUBTRACT);

		assertEquals(List.of(m1), selected());
		assertTrue(onFx(() -> table.getTreeItem(0).isExpanded()));
		assertTrue(onFx(() -> table.getTreeItem(3).isExpanded()));

		frozen[0] = false;
		click(5);
		assertEquals(List.of(w1, w2), selected());
	}

	@Test
	void tabAusBuchungWaehltNaechsteBuchung() throws Exception {
		show();
		click(5);
		setExpanded(5, false);
		ctrlClick(0);
		assertEquals(List.of(m1, m2, w1, w2), selected());
		run(() -> table.getFocusModel().focus(2, table.getColumns().get(FIRST)));

		press(KeyCode.TAB);

		assertEquals(List.of(s1), selected(), "Tab: Menge = die Buchung im neuen Fokus");
		assertEquals(4, focusedRow());
	}
}
