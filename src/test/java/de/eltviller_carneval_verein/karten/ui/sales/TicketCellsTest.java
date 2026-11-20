package de.eltviller_carneval_verein.karten.ui.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import de.eltviller_carneval_verein.karten.model.PaymentStatus;
import de.eltviller_carneval_verein.karten.model.Presentation;
import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TreeTableCell;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableRow;
import javafx.scene.control.TreeTableView;
import javafx.stage.Stage;
import javafx.util.StringConverter;

/**
 * Zellen der Kartentabelle (KARV-193, Spec Abschnitte 4 und 9): In Gruppenzeilen zeigen Checkbox und Auswahlliste
 * nichts, und die Checkbox „Abgeholt“ trifft die Buchung ihrer Zeile, auch gruppiert und sortiert.
 */
class TicketCellsTest {

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
	private TreeTableColumn<TicketRow, String> colFirst;
	private TreeTableColumn<TicketRow, Boolean> colCollected;
	private TreeTableColumn<TicketRow, PaymentStatus> colStatus;

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

	private List<TicketNode> nodes() {
		List<BookingDTO> rows = bookings.stream().map(b -> new BookingDTO(event, presentation, b)).toList();
		return TicketGrouping.group(rows, List.of(TicketColumn.LAST_NAME), GroupOrder.VALUE, true, tree.sortComparator(), event.getPresentations());
	}

	/** Zeilen mit Gruppierung nach Nachname: 0 Müller (2), 1 m1, 2 m2, 3 Schmidt (1), 4 s1. */
	private void show() throws Exception {
		run(() -> {
			table = new TreeTableView<>();
			table.setEditable(true);
			TreeTableColumn<TicketRow, String> colTree = new TreeTableColumn<>("");
			colFirst = new TreeTableColumn<>("Vorname");
			colFirst.setCellValueFactory(TicketCells.value(dto -> new SimpleStringProperty(dto.getBooking().getFirstName())));
			colCollected = new TreeTableColumn<>("Abgeholt");
			colCollected.setCellValueFactory(TicketCells.value(dto -> new SimpleBooleanProperty(dto.getBooking().isCollected())));
			colCollected.setCellFactory(TicketCells.checkBox(dto -> {
				SimpleBooleanProperty state = new SimpleBooleanProperty(dto.getBooking().isCollected());
				state.addListener((property, was, now) -> dto.getBooking().setCollected(now));
				return state;
			}));
			colCollected.setEditable(true);
			colStatus = new TreeTableColumn<>("Zahlungsstatus");
			colStatus.setCellValueFactory(TicketCells.value(dto -> dto.getBooking().getPaymentStatusProperty()));
			colStatus.setCellFactory(TicketCells.comboBox(new StringConverter<PaymentStatus>() {
				@Override
				public String toString(PaymentStatus status) {
					return status == null ? "" : status.getDisplayName();
				}

				@Override
				public PaymentStatus fromString(String text) {
					return null;
				}
			}, PaymentStatus.values()));
			colStatus.setEditable(true);
			table.getColumns().setAll(List.of(colTree, colFirst, colCollected, colStatus));
			tree = new TicketTreeTable(table, colTree);
			tree.setRebuild(() -> tree.show(nodes()));
			tree.show(nodes());
			stage = new Stage();
			stage.setScene(new Scene(table, 700, 400));
			stage.show();
			table.applyCss();
			table.layout();
		});
	}

	private TreeTableCell<?, ?> cell(int row, TreeTableColumn<TicketRow, ?> column) {
		table.layout();
		for (Node node : table.lookupAll(".tree-table-row-cell")) {
			if (node instanceof TreeTableRow<?> tableRow && tableRow.getIndex() == row && !tableRow.isEmpty()) {
				for (Node cell : tableRow.lookupAll(".tree-table-cell")) {
					if (cell instanceof TreeTableCell<?, ?> candidate && candidate.getTableColumn() == column) {
						return candidate;
					}
				}
			}
		}
		throw new AssertionError("keine Zelle in Zeile " + row);
	}

	@Test
	void gruppenzeileZeigtKeineCheckbox() throws Exception {
		show();

		assertNull(onFx(() -> cell(0, colCollected).getGraphic()), "der Gruppenkopf zeigt keine Checkbox");
		assertTrue(onFx(() -> cell(1, colCollected).getGraphic() instanceof CheckBox), "die Buchung zeigt ihre Checkbox");
		assertNull(onFx(() -> cell(3, colCollected).getGraphic()));
	}

	@Test
	void gruppenzeileZeigtKeineAuswahlliste() throws Exception {
		show();

		run(() -> table.edit(0, colStatus));
		assertFalse(onFx(() -> cell(0, colStatus).isEditing()), "ein Gruppenkopf lässt sich nicht bearbeiten");
		assertNull(onFx(() -> cell(0, colStatus).getGraphic()));
		assertEquals("", onFx(() -> cell(0, colStatus).getText() == null ? "" : cell(0, colStatus).getText()));

		run(() -> table.edit(1, colStatus));
		assertTrue(onFx(() -> cell(1, colStatus).getGraphic() instanceof ComboBox), "die Buchung zeigt die Auswahlliste");
	}

	@Test
	void abgeholtTrifftRichtigeBuchungInGruppierterSortierterTabelle() throws Exception {
		show();
		run(() -> {
			colFirst.setSortType(TreeTableColumn.SortType.DESCENDING);
			table.getSortOrder().setAll(List.of(colFirst));
		});
		// jetzt: 0 Müller (2), 1 m2, 2 m1, 3 Schmidt (1), 4 s1
		assertSame(m2, onFx(() -> ((TicketRow.BookingRow) table.getTreeItem(1).getValue()).dto().getBooking()));

		run(() -> ((CheckBox) cell(2, colCollected).getGraphic()).fire());

		assertTrue(m1.isCollected(), "die Buchung der Zeile ist abgeholt");
		assertFalse(m2.isCollected());
		assertFalse(s1.isCollected());
	}
}
