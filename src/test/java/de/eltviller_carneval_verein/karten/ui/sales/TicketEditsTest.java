package de.eltviller_carneval_verein.karten.ui.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.PaymentStatus;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.ui.input.ParsingTextFieldTreeTableCell;
import de.eltviller_carneval_verein.karten.ui.input.TableInput;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeTableCell;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableRow;
import javafx.scene.control.TreeTableView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;

/**
 * Eigene Änderungen in der Kartentabelle (KARV-193, Spec Abschnitt 11): Der Neuaufbau wartet, bis keine Zelle mehr
 * bearbeitet wird, und eine selbst geänderte Buchung bleibt trotz Suche sichtbar, bis die Suche sich ändert.
 */
class TicketEditsTest {

	private final Event event = new Event();
	private final Presentation presentation = event.addPresentation("Freitag");
	private final TestBookings data = new TestBookings();
	private final List<Booking> bookings = new ArrayList<>();

	private final Booking m1 = booking("Müller", "Anna");
	private final Booking m2 = booking("Müller", "Bernd");
	private final Booking s1 = booking("Schmidt", "Clara");

	private Stage stage;
	private TreeTableView<TicketRow> table;
	private TicketTreeTable tree;
	private TicketEdits edits;
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

	// ---- ohne Tabelle ----

	@Test
	void fremdeAenderungWaehrendBearbeitungKommtDanach() throws Exception {
		boolean[] editing = { true };
		edits = new TicketEdits(() -> editing[0], () -> rebuilds++);

		run(() -> edits.requestRebuild());
		assertEquals(0, rebuilds, "kein Neuaufbau, solange eine Zelle bearbeitet wird");

		run(() -> {
			editing[0] = false;
			edits.editingEnded();
		});
		assertEquals(1, rebuilds, "nach dem Ende der Bearbeitung kommt der Neuaufbau nach");

		run(() -> edits.editingEnded());
		assertEquals(1, rebuilds, "ohne wartende Änderung kein weiterer");
	}

	@Test
	void selbstGeaenderteBleibtTrotzSucheSichtbar() throws Exception {
		edits = new TicketEdits(() -> false, () -> rebuilds++);
		List<BookingDTO> rows = rows();

		run(() -> {
			m1.setLastName("Zander");
			edits.changed(m1, true);
		});

		assertEquals(1, rebuilds);
		assertTrue(edits.pinned().contains(m1));
		// Suche "Müller": m1 heißt jetzt Zander und bleibt trotzdem stehen, s1 fällt heraus
		List<Booking> shown = edits.visible(rows, dto -> BookingSearch.matches(dto.getBooking(), "Müller")).stream().map(BookingDTO::getBooking).toList();
		assertEquals(List.of(m1, m2), shown);
	}

	@Test
	void commitOhneAenderungPinntNicht() throws Exception {
		edits = new TicketEdits(() -> false, () -> rebuilds++);

		run(() -> edits.changed(m1, false));

		assertTrue(edits.pinned().isEmpty(), "ohne geänderten Wert gilt die Buchung nicht als selbst geändert");
		assertEquals(1, rebuilds, "neu gezeichnet wird trotzdem");
		assertEquals(List.of(), edits.visible(rows(), dto -> false));
	}

	@Test
	void endPinningBeiSuchaenderung() throws Exception {
		edits = new TicketEdits(() -> false, () -> rebuilds++);
		run(() -> edits.changed(m1, true));

		edits.endPinning();

		assertTrue(edits.pinned().isEmpty());
		assertEquals(List.of(), edits.visible(rows(), dto -> false), "die Ausnahme endet mit der neuen Suche");
	}

	@Test
	void pinBautNichtNeuAuf() throws Exception {
		edits = new TicketEdits(() -> false, () -> rebuilds++);

		run(() -> edits.pin(m1));

		assertTrue(edits.pinned().contains(m1));
		assertEquals(0, rebuilds, "den Neuaufbau ruft, wer pinnt, selbst (Platz zuweisen); kein zweiter danach");
	}

	@Test
	void standErkenntGeaenderteWerte() {
		TicketEdits.Values before = TicketEdits.Values.of(m1);
		assertEquals(before, TicketEdits.Values.of(m1), "unverändert: gleicher Stand");

		List<Consumer<Booking>> changes = List.of(b -> b.setLastName("Zander"), b -> b.setFirstName("Anja"),
				b -> b.setEMail("a@example.org"), b -> b.setComment("Fenster"), b -> b.setPaymentStatus(PaymentStatus.CARD),
				b -> b.setPaidAmount(1200), b -> b.setPrice(900), b -> b.setCollected(true),
				b -> b.setReservationDate(LocalDate.of(2026, 1, 2)), b -> b.setPaymentDate(LocalDate.of(2026, 1, 3)),
				b -> b.setCollectionDate(LocalDate.of(2026, 1, 4)));
		for (Consumer<Booking> change : changes) {
			Booking copy = m1.copy();
			TicketEdits.Values original = TicketEdits.Values.of(copy);
			change.accept(copy);
			assertNotEquals(original, TicketEdits.Values.of(copy), "jede Änderung eines Felds des Dialogs zählt");
		}
	}

	private List<BookingDTO> rows() {
		return bookings.stream().map(b -> new BookingDTO(event, presentation, b)).toList();
	}

	// ---- mit Tabelle ----
	// Zeilen mit Gruppierung nach Nachname: 0 Müller (2), 1 m1, 2 m2, 3 Schmidt (1), 4 s1

	private List<TicketNode> nodes() {
		return TicketGrouping.group(rows(), List.of(TicketColumn.LAST_NAME), GroupOrder.VALUE, tree.sortComparator(), event.getPresentations());
	}

	private static TreeTableColumn<TicketRow, String> column(String title, Function<Booking, String> value) {
		TreeTableColumn<TicketRow, String> column = new TreeTableColumn<>(title);
		column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(
				cell.getValue().getValue() instanceof TicketRow.BookingRow row ? value.apply(row.dto().getBooking()) : null));
		column.setPrefWidth(150);
		return column;
	}

	/** Wie die Commit-Handler des Controllers: Wert setzen, dann {@link TicketEdits#changed} mit Wertvergleich. */
	private void onCommit(TreeTableColumn<TicketRow, String> column, Function<Booking, String> value, BiConsumer<Booking, String> setter) {
		column.setEditable(true);
		column.setCellFactory(ParsingTextFieldTreeTableCell.forTextColumn(row -> row instanceof TicketRow.BookingRow));
		column.setOnEditCommit(commit -> {
			Booking booking = ((TicketRow.BookingRow) commit.getRowValue().getValue()).dto().getBooking();
			String before = value.apply(booking);
			setter.accept(booking, commit.getNewValue());
			edits.changed(booking, !Objects.equals(before, value.apply(booking)));
		});
	}

	private TreeTableColumn<TicketRow, String> colLast;
	private TreeTableColumn<TicketRow, String> colFirst;

	private void show() throws Exception {
		run(() -> {
			table = new TreeTableView<>();
			table.setEditable(true);
			TreeTableColumn<TicketRow, String> colTree = new TreeTableColumn<>("");
			colTree.setVisible(false);
			colLast = column("Nachname", Booking::getLastName);
			colFirst = column("Vorname", Booking::getFirstName);
			onCommit(colLast, Booking::getLastName, Booking::setLastName);
			onCommit(colFirst, Booking::getFirstName, Booking::setFirstName);
			table.getColumns().setAll(List.of(colTree, colLast, colFirst));
			tree = new TicketTreeTable(table, colTree);
			edits = new TicketEdits(() -> table.getEditingCell() != null, () -> {
				rebuilds++;
				tree.show(nodes());
			});
			tree.setRebuild(edits::requestRebuild);
			TableInput.install(table, row -> row instanceof TicketRow.BookingRow);
			table.editingCellProperty().addListener((property, before, now) -> {
				if (now == null) {
					edits.editingEnded();
				}
			});
			tree.show(nodes());
			stage = new Stage();
			stage.setScene(new Scene(table, 600, 400));
			stage.show();
			table.applyCss();
			table.layout();
			table.requestFocus();
		});
		rebuilds = 0;
	}

	private TextField editor(int row, TreeTableColumn<TicketRow, String> column) {
		table.layout();
		for (Node node : table.lookupAll(".tree-table-row-cell")) {
			if (node instanceof TreeTableRow<?> tableRow && tableRow.getIndex() == row && !tableRow.isEmpty()) {
				for (Node cell : tableRow.lookupAll(".tree-table-cell")) {
					if (cell instanceof TreeTableCell<?, ?> candidate && candidate.getTableColumn() == column && candidate.getGraphic() instanceof TextField field) {
						return field;
					}
				}
			}
		}
		throw new AssertionError("kein Eingabefeld in Zeile " + row);
	}

	/** Tippt den Text in die bearbeitete Zelle und drückt Tab im Feld (Übernahme und weiter zur nächsten Zelle). */
	private void typeAndTab(int row, TreeTableColumn<TicketRow, String> column, String text) throws Exception {
		run(() -> {
			TextField field = editor(row, column);
			field.setText(text);
			javafx.event.Event.fireEvent(field, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.TAB, false, false, false, false));
		});
	}

	private int editingRow() throws Exception {
		return onFx(() -> table.getEditingCell() == null ? -1 : table.getEditingCell().getRow());
	}

	private Booking bookingAt(int row) throws Exception {
		return onFx(() -> table.getTreeItem(row).getValue() instanceof TicketRow.BookingRow booking ? booking.dto().getBooking() : null);
	}

	@Test
	void neuaufbauWartetBisTabFolgeEndet() throws Exception {
		show();
		run(() -> table.edit(1, colLast));

		typeAndTab(1, colLast, "Zander");

		assertEquals(1, editingRow(), "Tab bearbeitet den Vornamen derselben Buchung");
		assertEquals(0, rebuilds, "kein Neuaufbau unter der Tab-Folge");
		assertSame(m1, bookingAt(1), "die Zeile bleibt stehen, obwohl Müller nicht mehr passt");

		run(() -> edits.requestRebuild()); // fremde Änderung mitten in der Tab-Folge
		typeAndTab(1, colFirst, "Anja");

		assertEquals("Anja", m1.getFirstName(), "die Eingabe landet in der Buchung, in der sie begann");
		assertEquals(2, editingRow(), "weiter in die nächste Buchung");
		assertSame(m2, bookingAt(2));
		assertEquals(0, rebuilds);

		run(() -> table.edit(-1, null));

		assertEquals(1, rebuilds, "ein Neuaufbau nach dem Ende der Tab-Folge");
		// jetzt: 0 Müller (1), 1 m2, 2 Schmidt (1), 3 s1, 4 Zander (1), 5 m1
		assertSame(m1, bookingAt(5));
		assertEquals("Müller", m2.getLastName(), "die nächste Buchung blieb unverändert");
	}

	@Test
	void neuerNachnameZiehtErstNachBearbeitungUm() throws Exception {
		show();
		run(() -> table.edit(1, colLast));

		Booking during = onFx(() -> {
			TextField field = editor(1, colLast);
			field.setText("Zander");
			javafx.event.Event.fireEvent(field, new ActionEvent(field, field));
			return table.getTreeItem(1).getValue() instanceof TicketRow.BookingRow row ? row.dto().getBooking() : null;
		});
		assertSame(m1, during, "im Commit selbst zieht die Buchung noch nicht um");
		flush();

		assertEquals(-1, editingRow());
		assertEquals(1, rebuilds);
		assertSame(m1, bookingAt(5), "nach dem Ende der Bearbeitung steht sie in ihrer neuen Gruppe");
		assertTrue(edits.pinned().contains(m1));
	}
}
