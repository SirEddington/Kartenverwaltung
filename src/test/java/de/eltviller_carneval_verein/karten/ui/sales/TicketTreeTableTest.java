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
import javafx.scene.layout.StackPane;
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
	private boolean nested = true;
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
		return TicketGrouping.group(rows, grouping, GroupOrder.VALUE, nested, tree.sortComparator(), event.getPresentations());
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

	/**
	 * Zeigt die Tabelle mit Gruppierung {@link #grouping} und den Zeilen der Kartentabelle ({@link TicketTreeRow}); die
	 * Bedienung von {@link TableInput} hängt hinter der Baumtabelle. Die Spalte für Pfeil und Einrückung ist schmal und
	 * zeigt nichts, wie im FXML.
	 */
	private void show() throws Exception {
		run(() -> {
			table = new TreeTableView<>();
			table.setEditable(true);
			TreeTableColumn<TicketRow, String> colTree = new TreeTableColumn<>("");
			colTree.setPrefWidth(30);
			colTree.setSortable(false);
			colTree.setEditable(false);
			colTree.setReorderable(false);
			TreeTableColumn<TicketRow, String> colLast = column("Nachname", row -> bookingText(row, Booking::getLastName));
			TreeTableColumn<TicketRow, String> colFirst = column("Vorname", row -> bookingText(row, Booking::getFirstName));
			for (TreeTableColumn<TicketRow, String> data : List.of(colLast, colFirst)) {
				data.setEditable(true);
				data.setCellFactory(ParsingTextFieldTreeTableCell.forTextColumn(row -> row instanceof TicketRow.BookingRow));
			}
			table.getColumns().setAll(List.of(colTree, colLast, colFirst));
			tree = new TicketTreeTable(table, colTree);
			table.setRowFactory(view -> new TicketTreeRow(tree));
			tree.setRebuild(() -> {
				rebuilds++;
				showNodes();
			});
			TableInput.install(table, row -> row instanceof TicketRow.BookingRow);
			showNodes();
			// wie im FXML: Die Tabelle hat einen Elternknoten, an dem die Doppelklick-Sperre hängt
			StackPane area = new StackPane(table);
			tree.installMultiClickGuard(area);
			stage = new Stage();
			stage.setScene(new Scene(area, 700, 500));
			stage.show();
			table.applyCss();
			table.layout();
			table.requestFocus();
		});
	}

	/** Neuaufbau wie im Controller: erst die Gruppierung (eine Kopie, wie aus den Einstellungen), dann der Baum. */
	private void showNodes() {
		tree.setGrouping(List.copyOf(grouping), nested);
		tree.show(nodes());
	}

	private void rebuild() throws Exception {
		run(this::showNodes);
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
	void leertasteOhneFokuszeileBleibtUnverbraucht() throws Exception {
		show();
		List<KeyCode> reached = new ArrayList<>();
		run(() -> {
			table.getFocusModel().focus(-1);
			table.addEventHandler(KeyEvent.KEY_PRESSED, event -> reached.add(event.getCode()));
		});

		press(KeyCode.SPACE);

		assertEquals(List.of(KeyCode.SPACE), reached, "ohne Zeile im Fokus tut die Baumtabelle nichts und verbraucht nichts");
		assertEquals(List.of(), selected());
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

	/** Ein einzelnes Mausereignis auf die Zelle, die gerade in Zeile {@code row} steht, an der Stelle {@code at}. */
	private void mouse(javafx.event.EventType<MouseEvent> type, int row, int column, double[] at, int clicks) throws Exception {
		run(() -> fireMouse(cell(row, column), type, at[0], at[1], clicks, false, false));
	}

	/**
	 * Verdrahtet die Tabelle wie der Controller mit {@link TicketEdits}: Ein Commit im Nachnamen ändert die Buchung, der
	 * Neuaufbau wartet bis nach dem Ende der Bearbeitung. Ohne Mausbedienung: wie bei einer Doppelklickzeit über 500 ms,
	 * der Neuaufbau kann zwischen die Klicks fallen.
	 */
	private void wireEditsLikeController() throws Exception {
		wireEdits(() -> false);
	}

	/** Die gestellte Uhr der Mausbedienung und ihre geplanten Prüfungen (Fälligkeit, Aufgabe). */
	private long now;
	private final List<Long> dueTimes = new ArrayList<>();
	private final List<Runnable> dueTasks = new ArrayList<>();

	/**
	 * Wie {@link #wireEditsLikeController}, dazu die Mausbedienung wie im Controller: Filter an der Szene, ein Druck in
	 * einer Zeile beginnt sie, ein Loslassen irgendwo beendet sie nach der Nachlaufzeit ({@link #advance}).
	 */
	private void wireEditsWithMouseGesture() throws Exception {
		MouseGesture[] gesture = new MouseGesture[1];
		TicketEdits edits = wireEdits(() -> gesture[0].active());
		run(() -> {
			gesture[0] = new MouseGesture(() -> now, (delay, task) -> {
				dueTimes.add(now + delay);
				dueTasks.add(task);
			}, edits::idle);
			Scene scene = table.getScene();
			scene.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
				if (event.getTarget() instanceof Node node && inRow(node)) {
					gesture[0].pressed();
				}
			});
			scene.addEventFilter(MouseEvent.MOUSE_RELEASED, event -> gesture[0].released());
		});
	}

	private static boolean inRow(Node node) {
		for (Node at = node; at != null; at = at.getParent()) {
			if (at instanceof TreeTableRow<?>) {
				return true;
			}
		}
		return false;
	}

	/** Stellt die Uhr der Mausbedienung vor und führt aus, was bis dahin fällig ist. */
	private void advance(long millis) throws Exception {
		run(() -> {
			now += millis;
			for (int i = 0; i < dueTasks.size(); i++) {
				if (dueTimes.get(i) <= now) {
					dueTimes.remove(i);
					Runnable task = dueTasks.remove(i);
					i--;
					task.run();
				}
			}
		});
	}

	private TicketEdits wireEdits(java.util.function.BooleanSupplier gesture) throws Exception {
		return onFx(() -> {
			TicketEdits edits = new TicketEdits(() -> table.getEditingCell() != null, gesture, this::showNodes, () -> {
			});
			tree.setRebuild(edits::requestRebuild);
			table.editingCellProperty().addListener((obs, before, editing) -> {
				if (editing == null) {
					edits.editingEnded();
				}
			});
			@SuppressWarnings("unchecked")
			TreeTableColumn<TicketRow, String> colLast = (TreeTableColumn<TicketRow, String>) table.getColumns().get(LAST);
			colLast.setOnEditCommit(commit -> {
				Booking booking = ((TicketRow.BookingRow) commit.getRowValue().getValue()).dto().getBooking();
				booking.setLastName(commit.getNewValue());
				edits.changed(booking, true);
			});
			return edits;
		});
	}

	/** Startet die Bearbeitung des Nachnamens in Zeile {@code row} und tippt {@code text}, ohne zu übernehmen. */
	private void editLastName(int row, String text) throws Exception {
		click(row);
		click(row, LAST);
		assertEquals(row, editingRow());
		run(() -> ((TextField) cell(row, LAST).getGraphic()).setText(text));
	}

	private double[] centerOf(int row, int column) throws Exception {
		return onFx(() -> {
			Bounds bounds = cell(row, column).localToScene(cell(row, column).getBoundsInLocal());
			return new double[] { (bounds.getMinX() + bounds.getMaxX()) / 2, (bounds.getMinY() + bounds.getMaxY()) / 2 };
		});
	}

	/** Die Buchungen, deren Zellen ab jetzt ins Bearbeiten gehen, in Reihenfolge. */
	private List<Booking> recordEditStarts() throws Exception {
		List<Booking> started = new ArrayList<>();
		run(() -> table.editingCellProperty().addListener((obs, before, editing) -> {
			if (editing != null && editing.getTreeItem().getValue() instanceof TicketRow.BookingRow row) {
				started.add(row.dto().getBooking());
			}
		}));
		return started;
	}

	private Booking bookingInRow(int row) throws Exception {
		return onFx(() -> table.getTreeItem(row).getValue() instanceof TicketRow.BookingRow booking ? booking.dto().getBooking() : null);
	}

	/**
	 * Abnahme 23 (Spec Abschnitt 14.5): Der erste Druck eines Doppelklicks übernimmt den neuen Nachnamen, der Neuaufbau ist
	 * angefordert, wartet aber auf das Ende der Mausbedienung. Unter dem zweiten Druck liegt so dieselbe Buchung; bearbeitet
	 * wird nur m1, und erst nach Nachlaufzeit und Ende der Bearbeitung zieht sie an ihre neue Stelle.
	 */
	@Test
	void doppelklickNachNeuaufbauBearbeitetDieselbeBuchung() throws Exception {
		show();
		wireEditsWithMouseGesture();
		editLastName(1, "Zimmer");
		advance(1000); // die Klicks zum Bearbeiten sind lange vorbei
		double[] at = centerOf(1, FIRST);
		List<Booking> started = recordEditStarts();

		mouse(MouseEvent.MOUSE_PRESSED, 1, FIRST, at, 1);
		assertEquals("Zimmer", m1.getLastName(), "der erste Druck übernimmt die Eingabe");
		assertSame(m1, bookingInRow(1), "der Neuaufbau wartet auf das Ende der Mausbedienung");
		mouse(MouseEvent.MOUSE_RELEASED, 1, FIRST, at, 1);
		mouse(MouseEvent.MOUSE_CLICKED, 1, FIRST, at, 1);
		advance(200);
		assertSame(m1, bookingInRow(1), "auch in der Nachlaufzeit");
		mouse(MouseEvent.MOUSE_PRESSED, 1, FIRST, at, 2);
		mouse(MouseEvent.MOUSE_RELEASED, 1, FIRST, at, 2);
		mouse(MouseEvent.MOUSE_CLICKED, 1, FIRST, at, 2);

		assertEquals(1, editingRow(), "der Doppelklick bearbeitet die Buchung, in der er begann");
		assertSame(m1, bookingInRow(1));
		assertFalse(started.isEmpty());
		assertTrue(started.stream().allMatch(booking -> booking == m1), "nur m1 geht ins Bearbeiten: " + started);
		assertEquals(List.of(m1), selected());

		advance(MouseGesture.AFTERGLOW_MILLIS);
		assertSame(m1, bookingInRow(1), "nach der Mausbedienung wartet der Neuaufbau auf das Ende der Bearbeitung");

		stopEditing();
		// 0 Müller (1), 1 m2, 2 Schmidt, 3 s1, 4 Weber, 5 w1, 6 w2, 7 Zimmer, 8 m1
		assertSame(m1, bookingInRow(8), "erst danach zieht m1 an ihre neue Stelle");
		assertEquals("Bernd", m2.getFirstName());
		assertEquals(List.of(m1), selected());
	}

	/**
	 * Sperre für längere Doppelklickzeiten (Spec Abschnitt 14.5): Der Neuaufbau fällt zwischen die Klicks und schiebt eine
	 * andere Buchung bzw. einen Gruppenkopf unter den Zeiger. Druck und Loslassen mit Klickzahl 2 werden verworfen: kein
	 * Bearbeiten, kein Klappen, die Auswahl bleibt.
	 */
	@Test
	void zweiterKlickAufAndereZeileWirdVerworfen() throws Exception {
		show();
		List<Booking> started = recordEditStarts();

		// Buchung: erster Klick auf m1, dann zieht m1 nach „Zimmer“ um, und m2 rückt in Zeile 1
		double[] at = centerOf(1, FIRST);
		mouse(MouseEvent.MOUSE_PRESSED, 1, FIRST, at, 1);
		mouse(MouseEvent.MOUSE_RELEASED, 1, FIRST, at, 1);
		mouse(MouseEvent.MOUSE_CLICKED, 1, FIRST, at, 1);
		assertEquals(List.of(m1), selected());
		m1.setLastName("Zimmer");
		rebuild();
		// 0 Müller (1), 1 m2, 2 Schmidt (1), 3 s1, 4 Weber, 5 w1, 6 w2, 7 Zimmer, 8 m1
		assertSame(m2, bookingInRow(1));
		mouse(MouseEvent.MOUSE_PRESSED, 1, FIRST, at, 2);
		mouse(MouseEvent.MOUSE_RELEASED, 1, FIRST, at, 2);
		mouse(MouseEvent.MOUSE_CLICKED, 1, FIRST, at, 2);

		assertEquals(List.of(), started, "keine Buchung geht ins Bearbeiten");
		assertEquals(-1, editingRow());
		assertEquals(List.of(m1), selected(), "die Auswahl bleibt");

		// Gruppenkopf: erster Klick auf w1, dann zieht m2 nach „Zimmer“ um, und der Kopf „Zimmer“ rückt in Zeile 5
		double[] at5 = centerOf(5, FIRST);
		mouse(MouseEvent.MOUSE_PRESSED, 5, FIRST, at5, 1);
		mouse(MouseEvent.MOUSE_RELEASED, 5, FIRST, at5, 1);
		mouse(MouseEvent.MOUSE_CLICKED, 5, FIRST, at5, 1);
		assertEquals(List.of(w1), selected());
		m2.setLastName("Zimmer");
		rebuild();
		// 0 Schmidt (1), 1 s1, 2 Weber (2), 3 w1, 4 w2, 5 Zimmer (2), 6 m1, 7 m2
		TreeItem<TicketRow> header = item(5);
		assertTrue(header.getValue() instanceof TicketRow.GroupRow, "unter dem Zeiger steht ein Gruppenkopf");
		mouse(MouseEvent.MOUSE_PRESSED, 5, FIRST, at5, 2);
		mouse(MouseEvent.MOUSE_RELEASED, 5, FIRST, at5, 2);
		mouse(MouseEvent.MOUSE_CLICKED, 5, FIRST, at5, 2);

		assertTrue(onFx(header::isExpanded), "der Gruppenkopf klappt nicht zu");
		assertEquals(List.of(w1), selected(), "die Auswahl bleibt");
		assertFalse(groupSelected(5));
		assertEquals(List.of(), started);
	}

	/** Ohne Neuaufbau zwischen den Klicks klappt ein Doppelklick auf einen Gruppenkopf wie bisher. */
	@Test
	void doppelklickAufGruppenkopfKlappt() throws Exception {
		show();
		double[] at = centerOf(3, FIRST);
		for (int clicks = 1; clicks <= 2; clicks++) {
			mouse(MouseEvent.MOUSE_PRESSED, 3, FIRST, at, clicks);
			mouse(MouseEvent.MOUSE_RELEASED, 3, FIRST, at, clicks);
			mouse(MouseEvent.MOUSE_CLICKED, 3, FIRST, at, clicks);
		}

		assertFalse(onFx(() -> table.getTreeItem(3).isExpanded()), "derselbe Kopf unter beiden Klicks: er klappt");
	}

	/**
	 * Doppelklick direkt nach einer Bearbeitung bei einer Doppelklickzeit über 500 ms (Abschlussreview, Important 2; Spec
	 * Abschnitt 14.5): Der erste Druck beendet das Bearbeiten, der Neuaufbau läuft vor dem Loslassen und schiebt m2 unter
	 * den Zeiger. Ohne Sperre begänne JavaFX das Bearbeiten von m2 beim zweiten Druck (Klickzahl 2, Zeile nicht gewählt).
	 * Die Sperre am Elternknoten verwirft Druck und Loslassen mit Klickzahl 2: m2 geht nicht ins Bearbeiten und wird nicht
	 * gewählt.
	 */
	@Test
	void doppelklickNachNeuaufbauBearbeitetKeineAndereBuchung() throws Exception {
		show();
		wireEditsLikeController();
		editLastName(1, "Zimmer");
		double[] at = centerOf(1, FIRST);
		List<Booking> started = recordEditStarts();

		// Doppelklick auf den Vornamen derselben Zeile; zwischen den Ereignissen läuft, was nachgeschoben ist
		mouse(MouseEvent.MOUSE_PRESSED, 1, FIRST, at, 1);
		assertEquals("Zimmer", m1.getLastName(), "der erste Druck übernimmt die Eingabe");
		// 0 Müller (1), 1 m2, 2 Schmidt, 3 s1, 4 Weber, 5 w1, 6 w2, 7 Zimmer, 8 m1
		assertSame(m2, bookingInRow(1), "der Neuaufbau lief vor dem Loslassen: unter dem Zeiger steht m2");
		mouse(MouseEvent.MOUSE_RELEASED, 1, FIRST, at, 1);
		mouse(MouseEvent.MOUSE_CLICKED, 1, FIRST, at, 1);
		mouse(MouseEvent.MOUSE_PRESSED, 1, FIRST, at, 2);
		mouse(MouseEvent.MOUSE_RELEASED, 1, FIRST, at, 2);
		mouse(MouseEvent.MOUSE_CLICKED, 1, FIRST, at, 2);

		assertEquals(List.of(), started, "m2 geht nicht ins Bearbeiten, auch nicht für die Dauer des Drucks");
		assertEquals(-1, editingRow());
		assertEquals("Bernd", m2.getFirstName());
		assertEquals(List.of(m1), selected(), "der verworfene zweite Druck ändert die Auswahl nicht");
	}

	@Test
	void doppelklickAufEineBuchungBearbeitetSie() throws Exception {
		show();
		double[] at = centerOf(4, FIRST);

		for (int clicks = 1; clicks <= 2; clicks++) {
			mouse(MouseEvent.MOUSE_PRESSED, 4, FIRST, at, clicks);
			mouse(MouseEvent.MOUSE_RELEASED, 4, FIRST, at, clicks);
			mouse(MouseEvent.MOUSE_CLICKED, 4, FIRST, at, clicks);
		}

		assertEquals(4, editingRow());
		assertEquals(List.of(s1), selected());
		stopEditing();
	}

	/**
	 * Wie {@link #doppelklickNachNeuaufbauBearbeitetKeineAndereBuchung}, aber das erste Loslassen kommt vor dem Neuaufbau
	 * (schneller Klick): Es beginnt das Bearbeiten des Vornamens von m1 (Klick auf die einzige gewählte Zeile), der
	 * Neuaufbau wartet darauf. Der zweite Druck trifft die Zelle am Rand neben dem Feld: Die Tabelle holt sich den Fokus,
	 * das Bearbeiten endet ohne Änderung, und der Neuaufbau läuft vor dem Loslassen und schiebt m2 unter den Zeiger. JavaFX
	 * wertet den Klick auf die beim Druck gewählte Zeile erst beim Loslassen aus (Klickzahl 2) und begänne damit das
	 * Bearbeiten von m2, das dann bestehen bliebe.
	 */
	@Test
	void doppelklickMitLoslassenVorDemNeuaufbauBearbeitetKeineAndereBuchung() throws Exception {
		show();
		wireEditsLikeController();
		editLastName(1, "Zimmer");
		double[] at = onFx(() -> {
			Bounds bounds = cell(1, FIRST).localToScene(cell(1, FIRST).getBoundsInLocal());
			return new double[] { bounds.getMinX() + 1, (bounds.getMinY() + bounds.getMaxY()) / 2 };
		});

		run(() -> {
			Node target = cell(1, FIRST);
			fireMouse(target, MouseEvent.MOUSE_PRESSED, at[0], at[1], 1, false, false);
			fireMouse(target, MouseEvent.MOUSE_RELEASED, at[0], at[1], 1, false, false);
			fireMouse(target, MouseEvent.MOUSE_CLICKED, at[0], at[1], 1, false, false);
		});
		assertEquals("Zimmer", m1.getLastName());
		assertEquals(1, editingRow(), "der Vorname von m1 wird bearbeitet");
		assertSame(m1, bookingInRow(1), "der Neuaufbau wartet auf das Ende der Bearbeitung");
		assertFalse(onFx(() -> {
			Node field = cell(1, FIRST).getGraphic();
			return field.localToScene(field.getBoundsInLocal()).contains(at[0], at[1]);
		}), "die Stelle liegt neben dem Feld: Der Druck trifft die Zelle");
		List<Booking> started = recordEditStarts();
		mouse(MouseEvent.MOUSE_PRESSED, 1, FIRST, at, 2);
		assertSame(m2, bookingInRow(1), "der Neuaufbau lief vor dem Loslassen: unter dem Zeiger steht m2");
		mouse(MouseEvent.MOUSE_RELEASED, 1, FIRST, at, 2);
		mouse(MouseEvent.MOUSE_CLICKED, 1, FIRST, at, 2);

		assertEquals(List.of(), started, "m2 geht nicht ins Bearbeiten");
		assertEquals(-1, editingRow());
		assertEquals("Bernd", m2.getFirstName());
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

	// ---- Alle auf- und zuklappen (Spec 14.1) ----
	// Zeilen mit Gruppierung nach Nachname und Vorname, alles aufgeklappt:
	// 0 Müller, 1 Anna, 2 m1, 3 Bernd, 4 m2, 5 Schmidt, 6 Clara, 7 s1, 8 Weber, 9 Dora, 10 w1, 11 Emil, 12 w2

	private boolean expanded(int row) throws Exception {
		return onFx(() -> table.getTreeItem(row).isExpanded());
	}

	private int rowCount() throws Exception {
		return onFx(() -> table.getExpandedItemCount());
	}

	/** Eine Taste mit Strg und dem getippten Text {@code text}, wie sie eine Tastatur mit anderen Codes liefert. */
	private void pressCtrl(KeyCode code, String text) throws Exception {
		run(() -> javafx.event.Event.fireEvent(table,
				new KeyEvent(this, table, KeyEvent.KEY_PRESSED, KeyEvent.CHAR_UNDEFINED, text, code, false, true, false, false)));
	}

	/** Die Tasten, die die Tabelle unverbraucht erreichen. */
	private List<KeyCode> recordUnconsumedKeys() throws Exception {
		List<KeyCode> reached = new ArrayList<>();
		run(() -> table.addEventHandler(KeyEvent.KEY_PRESSED, event -> reached.add(event.getCode())));
		return reached;
	}

	@Test
	void alleZuklappenKlapptJedeEbene() throws Exception {
		grouping = List.of(TicketColumn.LAST_NAME, TicketColumn.FIRST_NAME);
		show();

		run(() -> tree.collapseAll());

		assertEquals(3, rowCount(), "nur die obersten Gruppen sind zu sehen");
		assertFalse(expanded(0));
		assertFalse(expanded(1));
		assertFalse(expanded(2));
		setExpanded(0, true); // Müller auf
		// 0 Müller, 1 Anna, 2 Bernd, 3 Schmidt, 4 Weber
		assertEquals(5, rowCount());
		assertFalse(expanded(1), "die Untergruppen bleiben zu");
		assertFalse(expanded(2));

		booking("Albers", "Gerd"); // fremde Änderung: andere Struktur, dieselbe Gruppierung
		rebuild();
		// 0 Albers, 1 Gerd, 2 a, 3 Müller, 4 Anna, 5 Bernd, 6 Schmidt, 7 Weber
		assertEquals(8, rowCount(), "die zugeklappten Pfade bleiben gemerkt; die neue Gruppe ist auf");
		assertFalse(expanded(4));
		assertFalse(expanded(6));
	}

	@Test
	void alleZuklappenMeldetEinmal() throws Exception {
		show();
		click(1); // m1
		ctrlClick(5); // Weber ganz
		assertEquals(List.of(m1, w1, w2), selected());
		List<List<Booking>> reports = new ArrayList<>();
		run(() -> tree.selectedBookingsProperty().addListener(obs -> reports.add(List.copyOf(tree.selectedBookingsProperty().get()))));

		run(() -> tree.collapseAll());

		// 0 Müller, 1 Schmidt, 2 Weber
		assertEquals(List.of(w1, w2), selected(), "Müller war nur teilweise gewählt, Weber ganz");
		assertEquals(List.of(List.of(w1, w2)), reports, "eine Meldung für den ganzen Schritt");
		assertEquals(List.of(), modelRows());
		assertTrue(groupSelected(2));
		assertFalse(groupSelected(0));
	}

	@Test
	void alleZuklappenFokusAufOberste() throws Exception {
		grouping = List.of(TicketColumn.LAST_NAME, TicketColumn.FIRST_NAME);
		show();
		click(12); // w2

		run(() -> tree.collapseAll());

		// 0 Müller, 1 Schmidt, 2 Weber
		assertEquals(2, focusedRow());
		setExpanded(2, true); // Weber auf: 3 Dora, 4 Emil
		assertEquals(2, focusedRow(), "der Fokus steht auf der obersten Gruppe, nicht auf der Untergruppe von w2");

		setExpanded(4, true); // Emil: 5 w2
		setExpanded(3, true); // Dora: 4 w1, 5 Emil, 6 w2
		run(() -> table.getFocusModel().focus(0, table.getColumns().get(LAST))); // nur der Fokus wandert
		shiftClick(4);
		assertEquals(List.of(w1), selected(), "der Anker steht auf Weber, nicht mehr auf w2");
	}

	@Test
	void alleAufklappenLaesstAuswahl() throws Exception {
		grouping = List.of(TicketColumn.LAST_NAME, TicketColumn.FIRST_NAME);
		show();
		click(0); // Müller
		setExpanded(8, false); // Weber
		setExpanded(3, false); // Bernd
		// 0 Müller, 1 Anna, 2 m1, 3 Bernd, 4 Schmidt, 5 Clara, 6 s1, 7 Weber
		ctrlClick(6); // s1
		assertEquals(List.of(m1, m2, s1), selected());
		List<List<Booking>> reports = new ArrayList<>();
		run(() -> tree.selectedBookingsProperty().addListener(obs -> reports.add(List.copyOf(tree.selectedBookingsProperty().get()))));

		run(() -> tree.expandAll());

		assertEquals(13, rowCount(), "jede Gruppe auf jeder Ebene ist auf");
		assertEquals(List.of(m1, m2, s1), selected());
		assertEquals(List.of(2, 4, 7), modelRows());
		assertEquals(7, focusedRow());
		assertSame(s1, focusedBooking());
		assertEquals(List.of(), reports, "die Auswahl ändert sich nicht");

		booking("Albers", "Gerd");
		rebuild();
		assertEquals(16, rowCount(), "die zugeklappten Pfade sind vergessen");
	}

	@Test
	void alleKlappenBeiGesperrterTabelleNicht() throws Exception {
		show();
		click(1);
		setExpanded(5, false); // Weber
		run(() -> tree.setFrozen(() -> true));

		run(() -> tree.collapseAll());
		assertTrue(expanded(0));
		assertEquals(List.of(m1), selected());

		run(() -> tree.expandAll());
		assertFalse(expanded(5));

		press(KeyCode.SUBTRACT, false, true);
		press(KeyCode.ADD, false, true);
		assertTrue(expanded(0));
		assertFalse(expanded(5));
		assertEquals(List.of(m1), selected());
	}

	@Test
	void strgPlusMinusNurMitGruppierung() throws Exception {
		show();
		assertTrue(onFx(() -> tree.groupedProperty().get()));
		List<KeyCode> reached = recordUnconsumedKeys();

		press(KeyCode.SUBTRACT, false, true);
		assertEquals(3, rowCount(), "Strg+Minus des Ziffernblocks klappt alle zu");
		press(KeyCode.ADD, false, true);
		assertEquals(8, rowCount(), "Strg+Plus des Ziffernblocks klappt alle auf");
		press(KeyCode.MINUS, false, true);
		assertEquals(3, rowCount(), "Strg+Minus des Hauptfelds");
		press(KeyCode.PLUS, false, true);
		assertEquals(8, rowCount(), "Strg+Plus des Hauptfelds");
		pressCtrl(KeyCode.UNDEFINED, "-");
		assertEquals(3, rowCount(), "andere Codes: der getippte Text entscheidet");
		pressCtrl(KeyCode.UNDEFINED, "+");
		assertEquals(8, rowCount());
		assertEquals(List.of(), reached, "mit Gruppierung werden die Tasten verbraucht");

		grouping = List.of();
		rebuild();
		assertFalse(onFx(() -> tree.groupedProperty().get()));
		press(KeyCode.SUBTRACT, false, true);
		press(KeyCode.PLUS, false, true);
		assertEquals(List.of(KeyCode.SUBTRACT, KeyCode.PLUS), reached, "ohne Gruppierung tun sie nichts und werden nicht verbraucht");
		assertEquals(5, rowCount());
	}

	@Test
	void strgPlusMinusNichtImBearbeiten() throws Exception {
		show();
		click(1);
		click(1, LAST);
		assertEquals(1, editingRow());

		press(KeyCode.SUBTRACT, false, true);
		press(KeyCode.MINUS, false, true);

		assertTrue(expanded(0), "während eine Zelle bearbeitet wird, klappt nichts");
		assertEquals(8, rowCount());
		assertEquals(1, editingRow());
		stopEditing();
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

	// ---- Spalte für Pfeil und Einrückung (den Gruppenkopf prüft TicketTreeRowTest) ----

	/** Ob die Zeile den Kopf einer Gruppe zeigt. */
	private boolean showsHeader(int row) throws Exception {
		return onFx(() -> rowNode(row).getChildrenUnmodifiable().stream()
				.anyMatch(node -> node.isVisible() && node.getStyleClass().contains(TicketTreeRow.HEADER_STYLE_CLASS)));
	}

	/** Die Spalte für Pfeil und Einrückung; nur auf dem FX-Thread. */
	private TreeTableColumn<TicketRow, ?> treeColumn() {
		return table.getColumns().get(TREE);
	}

	@Test
	void ohneGruppierungKeineEinrueckungNachAufheben() throws Exception {
		grouping = List.of(TicketColumn.LAST_NAME, TicketColumn.FIRST_NAME);
		show();
		assertTrue(onFx(() -> treeColumn().isVisible()), "mit Gruppierung ist die Spalte für den Pfeil zu sehen");

		grouping = List.of();
		rebuild();

		assertFalse(onFx(() -> treeColumn().isVisible()), "ohne Gruppierung ist sie ausgeblendet");
		assertSame(m1, bookingAt(0));
		double[] result = onFx(() -> {
			TreeTableRow<?> row = rowNode(0);
			IndexedCell<?> cell = cell(0, LAST);
			Node text = cell.lookup(".text");
			return new double[] { text.getBoundsInParent().getMinX(), cell.snappedLeftInset(), row.getDisclosureNode() == null
					|| !row.getDisclosureNode().isVisible() ? 0 : 1, cell.getBoundsInParent().getMinX() };
		});
		assertEquals(result[1], result[0], 0.5, "die erste Datenspalte ist nicht eingerückt");
		assertEquals(0, result[2], "kein Pfeil");
		assertEquals(0, result[3], 0.5, "die erste Datenspalte steht ganz links");
		assertFalse(showsHeader(0), "kein Gruppenkopf");
	}

	@Test
	void zugeklapptBleibtNachFremderAenderung() throws Exception {
		show();
		setExpanded(5, false); // Weber

		s1.setFirstName("Clarissa"); // fremde Änderungen
		booking("Albers", "Gerd");
		rebuild();

		// 0 Albers, 1 a, 2 Müller, 3 m1, 4 m2, 5 Schmidt, 6 s1, 7 Weber (zu)
		assertFalse(onFx(() -> table.getTreeItem(7).isExpanded()), "dieselbe Gruppierung behält die zugeklappten Gruppen");

		grouping = List.of(TicketColumn.FIRST_NAME);
		rebuild();
		grouping = List.of(TicketColumn.LAST_NAME);
		rebuild();

		assertTrue(onFx(() -> table.getTreeItem(7).isExpanded()), "eine andere Gruppierung vergisst die zugeklappten Gruppen");
		assertEquals(10, onFx(() -> table.getExpandedItemCount()));
	}

	@Test
	void umschaltenBeiEinerSpalteVergisstNichts() throws Exception {
		show();
		setExpanded(5, false); // Weber

		nested = false; // eine Spalte: es gibt nichts zu verschachteln
		rebuild();

		assertFalse(onFx(() -> table.getTreeItem(5).isExpanded()), "die zugeklappte Gruppe bleibt zu");
	}

	@Test
	void umschaltenBeiZweiSpaltenKlapptAllesAuf() throws Exception {
		grouping = List.of(TicketColumn.LAST_NAME, TicketColumn.FIRST_NAME);
		show();
		setExpanded(0, false); // Müller
		assertFalse(onFx(() -> table.getTreeItem(0).isExpanded()));

		nested = false;
		rebuild();

		assertTrue(onFx(() -> table.getTreeItem(0).isExpanded()), "andere Gruppen: alles ist wieder aufgeklappt");
		double flat = onFx(() -> treeColumn().getMinWidth());
		nested = true;
		rebuild();
		assertTrue(onFx(() -> treeColumn().getMinWidth()) > flat, "ohne Verschachtelung genügt eine Ebene für Pfeil und Einrückung");
	}

	@Test
	void markierungDerGruppeUeberlebtNeuzeichnen() throws Exception {
		show();
		click(0);

		run(() -> table.refresh()); // Zeilen werden neu befüllt, wie beim Scrollen

		assertTrue(onFx(() -> rowNode(0).getPseudoClassStates().contains(TicketTreeTable.GROUP_SELECTED)));
		assertFalse(onFx(() -> rowNode(3).getPseudoClassStates().contains(TicketTreeTable.GROUP_SELECTED)));
	}

	@Test
	void buchungOhnePlatzKursiv() throws Exception {
		m1.setUnassigned(true);
		show();

		assertTrue(onFx(() -> rowNode(1).getStyle().contains("italic")), "eine Buchung ohne Platz ist kursiv");
		assertFalse(onFx(() -> rowNode(2).getStyle().contains("italic")));
		assertFalse(onFx(() -> rowNode(0).getStyle().contains("italic")), "ein Gruppenkopf nicht");
	}
}
