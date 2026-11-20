package de.eltviller_carneval_verein.karten.ui.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableView;
import javafx.stage.Stage;

/**
 * Der Spaltenfilter der Kartentabelle (KARV-193, Spec Abschnitt 8): Werteliste mit Häkchen, Suchfeld, „Anwenden“,
 * „Keine“, „Alle“ und „Alle Filter zurücksetzen“, Filtersymbol im Spaltenkopf. Die Tabelle wird hier ohne FXML und
 * ohne Controller gebaut.
 */
class ColumnFilterMenuTest {

	private final Event event = new Event();
	private final Presentation presentation = event.addPresentation("Freitag");
	private final TestBookings data = new TestBookings();
	private final List<Booking> bookings = new ArrayList<>();

	private final Booking m1 = booking("Müller", "Anna");
	private final Booking m2 = booking("müller ", "Bernd");
	private final Booking s1 = booking("Schmidt", "Clara");
	private final Booking w1 = booking("Weber", "Anna");

	private final TicketFilter filter = new TicketFilter();
	private Stage stage;
	private TreeTableView<TicketRow> table;
	private TicketTreeTable tree;
	private TreeTableColumn<TicketRow, String> colLast;
	private TreeTableColumn<TicketRow, String> colFirst;
	private int applied;

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
		onFx(() -> null);
	}

	@AfterEach
	void closeStage() throws Exception {
		if (stage != null) {
			onFx(() -> {
				colLast.getContextMenu().hide();
				colFirst.getContextMenu().hide();
				stage.hide();
				return null;
			});
		}
	}

	// ---- Aufbau ----

	private List<BookingDTO> rows() {
		return bookings.stream().map(b -> new BookingDTO(event, presentation, b)).toList();
	}

	private void rebuild() {
		List<BookingDTO> shown = rows().stream().filter(filter::passes).toList();
		tree.show(TicketGrouping.group(shown, List.of(), GroupOrder.VALUE, tree.sortComparator(), event.getPresentations()));
	}

	private static TreeTableColumn<TicketRow, String> column(String title, Function<Booking, String> value) {
		TreeTableColumn<TicketRow, String> column = new TreeTableColumn<>(title);
		column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(
				cell.getValue().getValue() instanceof TicketRow.BookingRow row ? value.apply(row.dto().getBooking()) : null));
		column.setPrefWidth(150);
		return column;
	}

	private void show() throws Exception {
		run(() -> {
			table = new TreeTableView<>();
			TreeTableColumn<TicketRow, String> colTree = new TreeTableColumn<>("");
			colTree.setVisible(false);
			colLast = column("Nachname", Booking::getLastName);
			colFirst = column("Vorname", Booking::getFirstName);
			table.getColumns().setAll(List.of(colTree, colLast, colFirst));
			tree = new TicketTreeTable(table, colTree);
			Runnable onApplied = () -> {
				applied++;
				rebuild();
			};
			ColumnFilterMenu.install(colLast, TicketColumn.LAST_NAME, filter,
					() -> filter.values(TicketColumn.LAST_NAME, rows(), row -> false, event.getPresentations()), onApplied);
			ColumnFilterMenu.install(colFirst, TicketColumn.FIRST_NAME, filter,
					() -> filter.values(TicketColumn.FIRST_NAME, rows(), row -> false, event.getPresentations()), onApplied);
			rebuild();
			stage = new Stage();
			stage.setScene(new Scene(table, 600, 400));
			stage.show();
			table.applyCss();
			table.layout();
		});
	}

	// ---- Bedienung ----

	private void open(TreeTableColumn<TicketRow, String> column) throws Exception {
		run(() -> column.getContextMenu().show(table, stage.getX() + 50, stage.getY() + 50));
	}

	private static Node content(TreeTableColumn<TicketRow, String> column) {
		return ((CustomMenuItem) column.getContextMenu().getItems().get(0)).getContent();
	}

	private static TextField search(TreeTableColumn<TicketRow, String> column) {
		return (TextField) content(column).lookup(".text-field");
	}

	@SuppressWarnings("unchecked")
	private static ListView<ColumnFilterMenu.Entry> list(TreeTableColumn<TicketRow, String> column) {
		return (ListView<ColumnFilterMenu.Entry>) content(column).lookup(".list-view");
	}

	private void click(TreeTableColumn<TicketRow, String> column, String text) throws Exception {
		run(() -> content(column).lookupAll(".button").stream().map(Button.class::cast).filter(b -> text.equals(b.getText())).findFirst()
				.orElseThrow(() -> new AssertionError("kein Knopf " + text)).fire());
	}

	/** Die sichtbaren Einträge der Werteliste als „Text (Anzahl)“, mit „-“ vorn, wenn ohne Häkchen. */
	private List<String> entries(TreeTableColumn<TicketRow, String> column) throws Exception {
		return onFx(() -> list(column).getItems().stream().map(e -> (e.checked().get() ? "" : "-") + e.label()).toList());
	}

	/** Setzt das Häkchen über die CheckBox der Zelle, wie ein Klick. */
	private void toggle(TreeTableColumn<TicketRow, String> column, String label) throws Exception {
		run(() -> {
			ListView<ColumnFilterMenu.Entry> list = list(column);
			list.applyCss();
			list.layout();
			for (Node node : list.lookupAll(".list-cell")) {
				if (node instanceof ListCell<?> cell && cell.getItem() instanceof ColumnFilterMenu.Entry entry && entry.label().equals(label)) {
					((CheckBox) cell.getGraphic()).fire();
					return;
				}
			}
			throw new AssertionError("kein Eintrag " + label);
		});
	}

	private List<Booking> shown() throws Exception {
		return onFx(() -> List.copyOf(tree.allBookings()));
	}

	private boolean showing(TreeTableColumn<TicketRow, String> column) throws Exception {
		return onFx(() -> column.getContextMenu().isShowing());
	}

	// ---- Tests ----

	@Test
	void anwendenFiltert() throws Exception {
		show();
		open(colLast);
		assertEquals(List.of("Müller (2)", "Schmidt (1)", "Weber (1)"), entries(colLast), "Texte nach Schlüssel zusammengefasst, mit Anzahl");

		toggle(colLast, "Müller (2)");
		assertEquals(List.of(m1, m2, s1, w1), shown(), "erst „Anwenden“ ändert die Tabelle");
		click(colLast, "Anwenden");

		assertEquals(Set.of(TicketColumn.LAST_NAME.keyOf(rows().get(0))), filter.deselected(TicketColumn.LAST_NAME));
		assertEquals(List.of(s1, w1), shown());
		assertEquals(1, applied);
		assertFalse(showing(colLast), "„Anwenden“ schließt den Filter");

		// Werteliste der anderen Spalte unter dem Filter des Nachnamens
		open(colFirst);
		assertEquals(List.of("Anna (1)", "Clara (1)"), entries(colFirst));
	}

	@Test
	void schliessenOhneAnwendenVerwirft() throws Exception {
		show();
		open(colLast);
		toggle(colLast, "Schmidt (1)");
		run(() -> colLast.getContextMenu().hide());

		assertTrue(filter.deselected(TicketColumn.LAST_NAME).isEmpty());
		assertEquals(0, applied);
		open(colLast);
		assertEquals(List.of("Müller (2)", "Schmidt (1)", "Weber (1)"), entries(colLast), "die geänderten Häkchen sind verworfen");
	}

	@Test
	void keineUndAlleWirkenNurAufSichtbare() throws Exception {
		show();
		open(colLast);
		run(() -> search(colLast).setText("e"));
		assertEquals(List.of("Müller (2)", "Weber (1)"), entries(colLast));
		click(colLast, "Keine");
		run(() -> search(colLast).setText(""));
		assertEquals(List.of("-Müller (2)", "Schmidt (1)", "-Weber (1)"), entries(colLast), "Schmidt war nicht sichtbar");

		run(() -> search(colLast).setText("web"));
		click(colLast, "Alle");
		run(() -> search(colLast).setText(""));
		assertEquals(List.of("-Müller (2)", "Schmidt (1)", "Weber (1)"), entries(colLast));
		assertEquals(0, applied, "„Keine“ und „Alle“ ändern die Tabelle nicht");

		click(colLast, "Anwenden");
		assertEquals(List.of(s1, w1), shown());
	}

	@Test
	void allesZuruecksetzenSofort() throws Exception {
		show();
		open(colLast);
		toggle(colLast, "Schmidt (1)");
		click(colLast, "Anwenden");
		open(colFirst);
		toggle(colFirst, "Bernd (1)");
		click(colFirst, "Anwenden");
		assertEquals(List.of(m1, w1), shown());
		applied = 0;

		open(colLast);
		click(colLast, "Alle Filter zurücksetzen");

		assertFalse(filter.isActive(TicketColumn.LAST_NAME));
		assertFalse(filter.isActive(TicketColumn.FIRST_NAME));
		assertEquals(1, applied);
		assertEquals(List.of(m1, m2, s1, w1), shown());
		assertFalse(showing(colLast));
		assertNull(onFx(() -> colFirst.getGraphic()), "auch das Symbol der anderen Spalte verschwindet");
		assertNull(onFx(() -> colLast.getGraphic()));

		open(colLast);
		click(colLast, "Alle Filter zurücksetzen");
		assertEquals(1, applied, "ohne aktiven Filter meldet das Zurücksetzen nichts");
	}

	@Test
	void symbolBeiAktivemFilter() throws Exception {
		show();
		assertNull(onFx(() -> colLast.getGraphic()));
		open(colLast);
		toggle(colLast, "Weber (1)");
		click(colLast, "Anwenden");

		assertNotNull(onFx(() -> colLast.getGraphic()));
		assertNull(onFx(() -> colFirst.getGraphic()));

		open(colLast);
		click(colLast, "Alle");
		click(colLast, "Anwenden");
		assertNull(onFx(() -> colLast.getGraphic()), "alle Häkchen gesetzt: kein Filter, kein Symbol");
		assertFalse(filter.isActive(TicketColumn.LAST_NAME));
	}

	@Test
	void suchfeldFiltertWerteliste() throws Exception {
		show();
		open(colLast);
		assertEquals("Suchen…", onFx(() -> search(colLast).getPromptText()));

		run(() -> search(colLast).setText("MÜ"));
		assertEquals(List.of("Müller (2)"), entries(colLast), "ohne Groß- und Kleinschreibung");

		run(() -> colLast.getContextMenu().hide());
		open(colLast);
		assertEquals("", onFx(() -> search(colLast).getText()), "das Suchfeld beginnt leer");
		assertEquals(3, entries(colLast).size());
	}

	@Test
	void anwendenOhneAenderungMeldetNichts() throws Exception {
		show();
		open(colLast);
		click(colLast, "Anwenden");
		assertEquals(0, applied);

		open(colLast);
		toggle(colLast, "Weber (1)");
		click(colLast, "Anwenden");
		assertEquals(1, applied);

		open(colLast);
		toggle(colLast, "Schmidt (1)");
		toggle(colLast, "Schmidt (1)");
		click(colLast, "Anwenden");
		assertEquals(1, applied, "dieselben Häkchen wie vorher: keine Änderung");
	}

	@Test
	void abgewaehlterWertAusserhalbDerListeBleibt() throws Exception {
		show();
		open(colLast);
		toggle(colLast, "Weber (1)");
		click(colLast, "Anwenden");
		Set<Object> weber = filter.deselected(TicketColumn.LAST_NAME);

		// Vorname unter dem Filter des Nachnamens: Anna zählt nur einmal (Anna Weber ist ausgefiltert); Anna abwählen
		open(colFirst);
		assertEquals(List.of("Anna (1)", "Bernd (1)", "Clara (1)"), entries(colFirst));
		toggle(colFirst, "Anna (1)");
		click(colFirst, "Anwenden");
		assertEquals(List.of(m2, s1), shown());

		// Weber kommt in der Werteliste des Nachnamens nicht mehr vor (nur Anna Weber trug ihn) und bleibt abgewählt
		open(colLast);
		assertEquals(List.of("müller (1)", "Schmidt (1)"), entries(colLast), "Text der ersten übrigen Buchung, ohne Rand");
		click(colLast, "Anwenden");
		assertEquals(weber, filter.deselected(TicketColumn.LAST_NAME));
		assertEquals(2, applied, "nichts geändert");
	}
}
