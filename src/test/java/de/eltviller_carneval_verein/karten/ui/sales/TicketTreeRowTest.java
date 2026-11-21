package de.eltviller_carneval_verein.karten.ui.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TreeTableCell;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableRow;
import javafx.scene.control.TreeTableView;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.BorderStroke;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.stage.Stage;

/**
 * Die Zeilen der Kartentabelle (KARV-193, Spec 14.3): Kopf einer Gruppe aus Überschrift, Wert und Zahl, auf einer Höhe
 * mit dem Pfeil, gekürzt erst in der Überschrift; eigener Hintergrund der Gruppenzeile ohne Spaltenlinien. Die Tabelle
 * wird ohne FXML und ohne Controller gebaut, aber mit dem Stylesheet der App, damit seine Regeln gelten.
 */
class TicketTreeRowTest {

	private static final String STYLESHEET = TicketTreeRowTest.class.getResource("/de/eltviller_carneval_verein/karten/ui/style.css")
			.toExternalForm();

	/** Spalten der Tabelle: 0 Pfeil und Einrückung, 1 Nachname, 2 Vorname. */
	private static final int TREE = 0;
	private static final int LAST = 1;

	private final Event event = new Event();
	private final Presentation presentation = event.addPresentation("Freitag");
	private final TestBookings data = new TestBookings();
	private final List<Booking> bookings = new ArrayList<>();

	// Zeilen mit Gruppierung nach Nachname, alles aufgeklappt:
	// 0 Hulbert (4), 1–4 Buchungen, 5 Schmidt (1), 6 s1, 7 Weber (2), 8 w1, 9 w2
	private final Booking h1 = booking("Hulbert", "Anna");
	private final Booking h2 = booking("Hulbert", "Bernd");
	private final Booking h3 = booking("Hulbert", "Carl");
	private final Booking h4 = booking("Hulbert", "Dirk");
	private final Booking s1 = booking("Schmidt", "Clara");
	private final Booking w1 = booking("Weber", "Dora");
	private final Booking w2 = booking("Weber", "Emil");

	private List<TicketColumn> grouping = List.of(TicketColumn.LAST_NAME);
	private Stage stage;
	private TreeTableView<TicketRow> table;
	private TicketTreeTable tree;

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

	private static TreeTableColumn<TicketRow, String> column(String title, Function<Booking, String> value) {
		TreeTableColumn<TicketRow, String> column = new TreeTableColumn<>(title);
		column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(
				cell.getValue().getValue() instanceof TicketRow.BookingRow booking ? value.apply(booking.dto().getBooking()) : null));
		column.setPrefWidth(150);
		return column;
	}

	/** Zeigt die Tabelle mit Gruppierung {@link #grouping}, Stylesheet und Style-Klasse der Kartentabelle. */
	private void show() throws Exception {
		run(() -> {
			table = new TreeTableView<>();
			table.getStyleClass().add("ticket-table");
			TreeTableColumn<TicketRow, String> colTree = new TreeTableColumn<>("");
			colTree.setPrefWidth(30);
			colTree.setSortable(false);
			colTree.setReorderable(false);
			table.getColumns().setAll(List.of(colTree, column("Nachname", Booking::getLastName), column("Vorname", Booking::getFirstName)));
			tree = new TicketTreeTable(table, colTree);
			table.setRowFactory(view -> new TicketTreeRow(tree));
			tree.setRebuild(this::showNodes);
			showNodes();
			stage = new Stage();
			Scene scene = new Scene(table, 700, 400);
			scene.getStylesheets().add(STYLESHEET);
			stage.setScene(scene);
			stage.show();
			table.applyCss();
			table.layout();
		});
	}

	private void showNodes() {
		List<BookingDTO> rows = bookings.stream().map(b -> new BookingDTO(event, presentation, b)).toList();
		tree.setGrouping(List.copyOf(grouping), true);
		tree.show(TicketGrouping.group(rows, grouping, GroupOrder.VALUE, true, tree.sortComparator(), event.getPresentations()));
	}

	private void rebuild() throws Exception {
		run(this::showNodes);
	}

	// ---- Zugriff (nur auf dem FX-Thread) ----

	private TreeTableRow<?> rowNode(int row) {
		table.layout();
		for (Node node : table.lookupAll(".tree-table-row-cell")) {
			if (node instanceof TreeTableRow<?> tableRow && tableRow.getIndex() == row && !tableRow.isEmpty()) {
				return tableRow;
			}
		}
		throw new AssertionError("keine Zeile " + row);
	}

	private TreeTableCell<?, ?> cell(int row, int column) {
		for (Node node : rowNode(row).lookupAll(".tree-table-cell")) {
			if (node instanceof TreeTableCell<?, ?> candidate && candidate.getTableColumn() == table.getColumns().get(column)) {
				return candidate;
			}
		}
		throw new AssertionError("keine Zelle in Zeile " + row + ", Spalte " + column);
	}

	/** Der Kopf einer Gruppenzeile; {@code null}, wenn die Zeile keinen zeigt. */
	private static Region header(TreeTableRow<?> row) {
		for (Node node : row.getChildrenUnmodifiable()) {
			if (node instanceof Region region && region.getStyleClass().contains(TicketTreeRow.HEADER_STYLE_CLASS)) {
				return region.isVisible() ? region : null;
			}
		}
		return null;
	}

	private static Label part(TreeTableRow<?> row, String styleClass) {
		Region header = header(row);
		assertNotNull(header, "die Zeile zeigt einen Kopf");
		return (Label) header.lookup("." + styleClass);
	}

	/** Der angezeigte, gegebenenfalls gekürzte Text eines Teils; leer, wenn der Teil entfällt. */
	private static String shown(Label part) {
		return part.isVisible() ? ((Text) part.lookup(".text")).getText() : "";
	}

	/** Texte der drei Teile eines Kopfs, {@code null} ohne Kopf. */
	private List<String> headerTexts(int row) throws Exception {
		return onFx(() -> {
			TreeTableRow<?> tableRow = rowNode(row);
			return header(tableRow) == null ? null
					: List.of(part(tableRow, TicketTreeRow.COLUMN_STYLE_CLASS).getText(), part(tableRow, TicketTreeRow.VALUE_STYLE_CLASS).getText(),
							part(tableRow, TicketTreeRow.COUNT_STYLE_CLASS).getText());
		});
	}

	/** Die senkrechte Mitte des Knotens selbst (ohne Kinder, die über ihn hinausragen) in der Szene. */
	private static double centerY(Node node) {
		Bounds bounds = node.localToScene(node.getLayoutBounds());
		return (bounds.getMinY() + bounds.getMaxY()) / 2;
	}

	/** Die oberste Füllung des Hintergrunds, die man sieht. */
	private static Paint background(Region region) {
		assertNotNull(region.getBackground());
		List<BackgroundFill> fills = region.getBackground().getFills();
		return fills.get(fills.size() - 1).getFill();
	}

	/** Ob die Zelle eine sichtbare Linie am rechten Rand hat (Spaltenlinie oder blauer Rand einer gewählten Zelle). */
	private static boolean rightLine(Region cell) {
		if (cell.getBorder() == null) {
			return false;
		}
		for (BorderStroke stroke : cell.getBorder().getStrokes()) {
			if (stroke.getRightStroke() instanceof Color color && color.getOpacity() > 0 && stroke.getWidths().getRight() > 0) {
				return true;
			}
		}
		return false;
	}

	// ---- Fälle ----

	@Test
	void kopfHatDreiTeile() throws Exception {
		show();

		assertEquals(List.of("Nachname", "Hulbert", "4 Karten"), headerTexts(0));
		assertEquals(List.of("Nachname", "Weber", "2 Karten"), headerTexts(7));
		assertNull(headerTexts(1), "eine Buchungszeile zeigt keinen Kopf");

		Object[] style = onFx(() -> {
			TreeTableRow<?> row = rowNode(0);
			Label column = part(row, TicketTreeRow.COLUMN_STYLE_CLASS);
			Label value = part(row, TicketTreeRow.VALUE_STYLE_CLASS);
			Label count = part(row, TicketTreeRow.COUNT_STYLE_CLASS);
			return new Object[] { column.getFont().getSize(), value.getFont().getSize(), FontWeight.findByName(value.getFont().getStyle()),
					column.getTextFill(), value.getTextFill(), count.getTextFill(), FontWeight.findByName(column.getFont().getStyle()) };
		});
		assertTrue((double) style[0] < (double) style[1], "die Überschrift ist kleiner als der Wert: " + style[0] + " / " + style[1]);
		assertEquals(FontWeight.BOLD, style[2], "der Wert ist fett");
		assertNotEquals(FontWeight.BOLD, style[6], "die Überschrift ist nicht fett");
		assertNotEquals(style[4], style[3], "die Überschrift ist grau, nicht wie der Wert");
		assertEquals(style[3], style[5], "Zahl und Überschrift in derselben grauen Schrift");
	}

	@Test
	void eineKarteImSingular() throws Exception {
		assertEquals("1 Karte", TicketTreeRow.countText(1));
		assertEquals("2 Karten", TicketTreeRow.countText(2));
		assertEquals("17 Karten", TicketTreeRow.countText(17));

		show();
		assertEquals(List.of("Nachname", "Schmidt", "1 Karte"), headerTexts(5));
	}

	@Test
	void kopfFolgtNeuaufbau() throws Exception {
		show();
		assertEquals("4 Karten", headerTexts(0).get(2));

		booking("Hulbert", "Erik");
		rebuild();

		assertEquals("5 Karten", headerTexts(0).get(2));
	}

	@Test
	void textUndPfeilAufEinerHoehe() throws Exception {
		grouping = List.of(TicketColumn.LAST_NAME, TicketColumn.FIRST_NAME);
		show();
		// 0 Hulbert, 1 Anna, 2 h1, ...
		for (int index : new int[] { 0, 1 }) {
			double[] centers = onFx(() -> {
				TreeTableRow<?> row = rowNode(index);
				Node arrow = row.getDisclosureNode();
				Node glyph = arrow.lookup(".arrow");
				return new double[] { centerY(row), centerY(glyph == null ? arrow : glyph), centerY(part(row, TicketTreeRow.COLUMN_STYLE_CLASS)),
						centerY(part(row, TicketTreeRow.VALUE_STYLE_CLASS)), centerY(part(row, TicketTreeRow.COUNT_STYLE_CLASS)) };
			});
			String where = "Zeile " + index + ": Zeile, Pfeil, Überschrift, Wert, Zahl " + Arrays.toString(centers);
			assertEquals(centers[0], centers[1], 1, "der Pfeil steht in der Mitte der Zeile; " + where);
			for (int part = 2; part < centers.length; part++) {
				assertEquals(centers[1], centers[part], 1, "der Text steht auf der Höhe des Pfeils; " + where);
			}
		}
	}

	@Test
	void kopfUeberSpaltenHinweg() throws Exception {
		show();

		double[] bounds = onFx(() -> {
			TreeTableRow<?> row = rowNode(0);
			Region header = header(row);
			Label value = part(row, TicketTreeRow.VALUE_STYLE_CLASS);
			Label count = part(row, TicketTreeRow.COUNT_STYLE_CLASS);
			return new double[] { header.getBoundsInParent().getMinX(), row.getDisclosureNode().getBoundsInParent().getMaxX(),
					table.getColumns().get(TREE).getWidth(), header.getBoundsInParent().getMinX() + count.getBoundsInParent().getMaxX(),
					value.getWidth(), value.prefWidth(-1), count.getWidth(), count.prefWidth(-1), header.isMouseTransparent() ? 1 : 0 };
		});
		assertTrue(bounds[0] >= bounds[1] - 0.5, "der Kopf beginnt hinter dem Pfeil");
		assertTrue(bounds[0] < bounds[2], "der Kopf beginnt noch in der Spalte des Pfeils");
		assertTrue(bounds[3] > bounds[2], "der Kopf reicht über die Spalte des Pfeils hinaus");
		// auf ganze Pixel aufgerundet
		assertEquals(bounds[5], bounds[4], 1, "der Wert ist nicht auf eine Spalte beschnitten");
		assertEquals(bounds[7], bounds[6], 1, "die Zahl ist nicht auf eine Spalte beschnitten");
		assertEquals(1, bounds[8], "der Kopf nimmt keine Maus an, Klicks treffen die Zellen");
	}

	@Test
	void tiefereEbeneRuecktKopfEinUndPfeilBleibtSichtbar() throws Exception {
		grouping = List.of(TicketColumn.LAST_NAME, TicketColumn.FIRST_NAME);
		show();
		// 0 Hulbert, 1 Anna, 2 h1, ...
		assertEquals(List.of("Vorname", "Anna", "1 Karte"), headerTexts(1));

		double[] result = onFx(() -> {
			Node top = rowNode(0).getDisclosureNode();
			Node deep = rowNode(1).getDisclosureNode();
			return new double[] { header(rowNode(0)).getBoundsInParent().getMinX(), header(rowNode(1)).getBoundsInParent().getMinX(),
					deep.isVisible() ? 1 : 0, deep.getOpacity(), top.getOpacity() };
		});
		assertTrue(result[1] > result[0], "der Kopf der zweiten Ebene steht weiter rechts");
		assertEquals(1, result[2]);
		assertEquals(1.0, result[3], 0.01, "die Spalte ist breit genug für den Pfeil der tiefsten Ebene");
		assertEquals(1.0, result[4], 0.01);
		double twoLevels = onFx(() -> table.getColumns().get(TREE).getMinWidth());

		grouping = List.of(TicketColumn.LAST_NAME);
		rebuild();
		double oneLevel = onFx(() -> table.getColumns().get(TREE).getMinWidth());
		assertTrue(twoLevels > oneLevel, "die Mindestbreite richtet sich nach der Zahl der Ebenen: " + twoLevels + " / " + oneLevel);
	}

	/** Ein Kopf in einer Zeile gegebener Breite: angezeigte Texte, Breite und Lage der Zahl. */
	private record Layout(String column, String value, String count, double countWidth, double countPref, double gap, double countRight,
			double rowRight) {
	}

	/** Legt die Zeile mit {@code width} aus; die Tabelle gibt ihr beim nächsten Takt wieder ihre Breite. */
	private static Layout layoutAt(TreeTableRow<?> row, double width) {
		row.resize(width, row.getHeight());
		row.requestLayout();
		row.layout();
		Label value = part(row, TicketTreeRow.VALUE_STYLE_CLASS);
		Label count = part(row, TicketTreeRow.COUNT_STYLE_CLASS);
		Bounds valueBounds = value.getBoundsInParent();
		Bounds countBounds = count.getBoundsInParent();
		return new Layout(shown(part(row, TicketTreeRow.COLUMN_STYLE_CLASS)), shown(value), shown(count), count.getWidth(), count.prefWidth(-1),
				countBounds.getMinX() - valueBounds.getMaxX(), header(row).getBoundsInParent().getMinX() + countBounds.getMaxX(), width);
	}

	@Test
	void kopfKuerztErstSpalteDannWert() throws Exception {
		show();

		List<Layout> layouts = onFx(() -> {
			TreeTableRow<?> row = rowNode(0);
			double left = header(row).getBoundsInParent().getMinX();
			double column = part(row, TicketTreeRow.COLUMN_STYLE_CLASS).prefWidth(-1);
			double value = part(row, TicketTreeRow.VALUE_STYLE_CLASS).prefWidth(-1);
			double count = part(row, TicketTreeRow.COUNT_STYLE_CLASS).prefWidth(-1);
			double gap = TicketTreeRow.PART_GAP;
			return List.of(layoutAt(row, left + column + gap + value + gap + count + 20), // alles passt
					layoutAt(row, left + column / 2 + gap + value + gap + count), // die Überschrift wird kürzer
					layoutAt(row, left + value / 2 + gap + count), // die Überschrift ist weg, der Wert wird kürzer
					layoutAt(row, left + gap + count)); // nur noch die Zahl
		});

		Layout full = layouts.get(0);
		assertEquals(List.of("Nachname", "Hulbert", "4 Karten"), List.of(full.column(), full.value(), full.count()));

		Layout shortColumn = layouts.get(1);
		assertTrue(shortColumn.column().endsWith("…") && shortColumn.column().length() < "Nachname".length(),
				"die Überschrift ist gekürzt: " + shortColumn);
		assertEquals("Hulbert", shortColumn.value(), "der Wert bleibt ganz, solange es die Überschrift gibt");

		Layout shortValue = layouts.get(2);
		assertEquals("", shortValue.column(), "die Überschrift ist ganz weg");
		assertTrue(shortValue.value().endsWith("…") && shortValue.value().length() < "Hulbert".length(),
				"dann wird der Wert gekürzt: " + shortValue);

		assertEquals("", layouts.get(3).column());
		for (Layout layout : layouts) {
			assertEquals("4 Karten", layout.count(), "die Zahl bleibt ganz: " + layout);
			// Breiten auf ganze Pixel aufgerundet
			assertEquals(layout.countPref(), layout.countWidth(), 1, "die Zahl hat ihre ganze Breite: " + layout);
			assertTrue(layout.countRight() <= layout.rowRight() + 1, "die Zahl steht ganz in der Zeile: " + layout);
			assertEquals(TicketTreeRow.PART_GAP, layout.gap(), 0.5, "die Zahl steht direkt hinter dem Wert: " + layout);
		}
	}

	@Test
	void gruppenzeileMitEigenemHintergrundOhneSpaltenlinien() throws Exception {
		show();

		Object[] result = onFx(() -> new Object[] { background(rowNode(0)), background(rowNode(1)), background(rowNode(2)),
				rightLine(cell(0, LAST)), rightLine(cell(1, LAST)), rowNode(0).getPseudoClassStates().contains(TicketTreeRow.GROUP),
				rowNode(1).getPseudoClassStates().contains(TicketTreeRow.GROUP) });
		assertNotEquals(result[1], result[0], "die Gruppenzeile hebt sich von Buchungszeilen ab");
		assertNotEquals(result[2], result[0], "auch von ihrer Zebrafärbung");
		assertFalse((boolean) result[3], "die Zellen der Gruppenzeile zeigen keine Spaltenlinien");
		assertTrue((boolean) result[4], "eine Buchungszeile zeigt sie (der Test erkennt die Linie)");
		assertTrue((boolean) result[5]);
		assertFalse((boolean) result[6], "eine Buchungszeile hat die Pseudoklasse nicht");
	}

	@Test
	void gewaehlteGruppeInFarbenEinerGewaehltenZeile() throws Exception {
		show();
		run(() -> tree.restoreSelection(List.of(h1, h2, h3, h4, s1)));

		Object[] result = onFx(() -> new Object[] { background(rowNode(0)), background(rowNode(1)), rightLine(cell(0, LAST)),
				rightLine(cell(0, TREE)), rightLine(cell(1, LAST)), part(rowNode(0), TicketTreeRow.VALUE_STYLE_CLASS).getTextFill(),
				part(rowNode(0), TicketTreeRow.COUNT_STYLE_CLASS).getTextFill() });
		assertEquals(Color.web("#FFE000"), result[0], "die gewählte Gruppe ist gelb wie eine gewählte Zeile");
		assertEquals(result[1], result[0], "wie die gewählte Buchungszeile darunter");
		assertFalse((boolean) result[2], "kein blauer rechter Rand in der Gruppenzeile");
		assertFalse((boolean) result[3]);
		assertTrue((boolean) result[4], "die gewählte Buchungszeile behält ihn");
		assertEquals(Color.web("#2C2F88"), result[5], "der Text in Blau wie in einer gewählten Zeile");
		assertEquals(Color.web("#2C2F88"), result[6]);
	}
}
