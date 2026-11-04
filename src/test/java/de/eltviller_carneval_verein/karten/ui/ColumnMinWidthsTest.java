package de.eltviller_carneval_verein.karten.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Orientation;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.skin.TableColumnHeader;
import javafx.scene.text.Text;

/** Mindestbreiten der Tabellenspalten (KARV-126): Die Überschrift bleibt lesbar, Inhaltsspalten behalten ihre Untergrenze. */
class ColumnMinWidthsTest {

	private static final String STYLESHEET = ColumnMinWidthsTest.class
			.getResource("/de/eltviller_carneval_verein/karten/ui/style.css").toExternalForm();

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

	private static TableColumn<String, String> column(String title) {
		TableColumn<String, String> column = new TableColumn<>(title);
		column.setPrefWidth(100);
		column.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue()));
		return column;
	}

	/** Die Tabelle wie in der App: Stylesheet und Spaltenregel wie in TicketTableView.fxml, breit genug für alle Mindestbreiten. */
	@SafeVarargs
	private static TableView<String> table(Map<TableColumn<?, ?>, Double> contentFloors, TableColumn<String, ?>... columns) {
		return table(600, contentFloors, columns);
	}

	/** Eine Tabelle der angegebenen Breite; die Breite lässt sich später über {@code setPrefWidth} ändern. */
	@SafeVarargs
	private static TableView<String> table(double width, Map<TableColumn<?, ?>, Double> contentFloors,
			TableColumn<String, ?>... columns) {
		TableView<String> table = new TableView<>();
		table.getColumns().addAll(columns);
		table.getItems().add("Meier");
		table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
		ColumnMinWidths.install(table, contentFloors);
		table.setPrefSize(width, 200);
		Scene scene = new Scene(new Group(table));
		scene.getStylesheets().add(STYLESHEET);
		layout(table);
		return table;
	}

	/** Zwei Durchläufe: Der erste legt die Spaltenköpfe an und misst sie, der zweite verteilt die Breiten danach. */
	private static void layout(TableView<?> table) {
		for (int i = 0; i < 2; i++) {
			table.getScene().getRoot().applyCss();
			table.getScene().getRoot().layout();
		}
	}

	private static boolean horizontalScrollBarShown(TableView<?> table) {
		for (Node node : table.lookupAll(".scroll-bar")) {
			if (node instanceof ScrollBar bar && bar.getOrientation() == Orientation.HORIZONTAL) {
				return bar.isVisible();
			}
		}
		return false;
	}

	/** Wie weit die Spalten reichen, gemessen vom linken Rand der Tabelle. */
	private static double columnsRightEdge(TableView<?> table) {
		return table.getColumns().stream().mapToDouble(TableColumn::getWidth).sum();
	}

	/** Der Text, den der Spaltenkopf wirklich zeigt (bei zu wenig Platz mit „…“ gekürzt). */
	private static String shownHeader(TableView<?> table, TableColumn<?, ?> column) {
		for (Node node : table.lookupAll(".column-header")) {
			if (node instanceof TableColumnHeader header && header.getTableColumn() == column) {
				return ((Text) header.lookup(".label .text")).getText();
			}
		}
		throw new AssertionError("kein Spaltenkopf für " + column.getText());
	}

	@Test
	void headerStaysReadableWhenTheColumnIsDraggedNarrow() throws Exception {
		String shown = onFx(() -> {
			TableColumn<String, String> status = column("Zahlungsstatus");
			TableView<String> table = table(Map.of(), status, column("Kommentar"));
			table.resizeColumn(status, -1000);
			layout(table);
			return shownHeader(table, status);
		});
		assertEquals("Zahlungsstatus", shown);
	}

	@Test
	void headerStaysReadableWhenTheColumnIsSorted() throws Exception {
		String shown = onFx(() -> {
			TableColumn<String, String> status = column("Zahlungsstatus");
			TableView<String> table = table(Map.of(), status, column("Kommentar"));
			table.getSortOrder().add(status);
			table.resizeColumn(status, -1000);
			layout(table);
			return shownHeader(table, status);
		});
		assertEquals("Zahlungsstatus", shown);
	}

	@Test
	void contentColumnKeepsItsFloorAboveTheHeader() throws Exception {
		double width = onFx(() -> {
			TableColumn<String, String> seat = column("Sitz");
			TableView<String> table = table(Map.of(seat, 80.0), seat, column("Kommentar"));
			table.resizeColumn(seat, -1000);
			layout(table);
			return seat.getWidth();
		});
		assertTrue(width >= 80, "Breite " + width);
	}

	/** Passen die Mindestbreiten nicht hinein, ragten die letzten Spalten sonst ohne Rollbalken aus der Tabelle. */
	@Test
	void narrowTableScrollsInsteadOfCuttingOffColumns() throws Exception {
		boolean scrollBar = onFx(() -> horizontalScrollBarShown(
				table(200, Map.of(), column("Zahlungsstatus"), column("Reserviert am"), column("Kommentar"))));
		assertTrue(scrollBar);
	}

	@Test
	void wideTableFillsItsWidthWithoutScrollBar() throws Exception {
		double[] result = onFx(() -> {
			TableView<String> table = table(600, Map.of(), column("Zahlungsstatus"), column("Reserviert am"), column("Kommentar"));
			return new double[] { horizontalScrollBarShown(table) ? 1 : 0, columnsRightEdge(table), table.getWidth() };
		});
		assertEquals(0, result[0], "Rollbalken");
		assertTrue(result[1] > result[2] - 25 && result[1] <= result[2], "Spalten " + result[1] + " in Tabelle " + result[2]);
	}

	@Test
	void tableFillsItsWidthAgainWhenItGrows() throws Exception {
		double[] result = onFx(() -> {
			TableView<String> table = table(200, Map.of(), column("Zahlungsstatus"), column("Reserviert am"), column("Kommentar"));
			table.setPrefWidth(900);
			layout(table);
			return new double[] { horizontalScrollBarShown(table) ? 1 : 0, columnsRightEdge(table), table.getWidth() };
		});
		assertEquals(0, result[0], "Rollbalken");
		assertTrue(result[1] > result[2] - 25 && result[1] <= result[2], "Spalten " + result[1] + " in Tabelle " + result[2]);
	}
}
