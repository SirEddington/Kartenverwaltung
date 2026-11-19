package de.eltviller_carneval_verein.karten.ui.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.controlsfx.control.table.TableFilter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;

/**
 * KARV-196: Die Kartentabelle stellt die Auswahl auf den Zeilen derselben Buchungen wieder her, auch wenn die
 * Spaltenkopf-Filter von ControlsFX die Zeilen sortieren oder filtern (aufgebaut wie im {@link TicketTableController}).
 */
class TicketTableSelectionTest {

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

	/** Tabelle wie in der Kartentabelle: FilteredList für die Suche, darüber die Spaltenkopf-Filter. */
	private static final class Fixture {
		final ObservableList<BookingDTO> master = FXCollections.observableArrayList();
		final FilteredList<BookingDTO> filteredData = new FilteredList<>(master, p -> true);
		final TableView<BookingDTO> table = new TableView<>();
		final TableColumn<BookingDTO, String> lastName = new TableColumn<>("Nachname");
		final TableFilter<BookingDTO> columnFilters;

		Fixture() {
			Event event = new Event();
			Presentation presentation = event.addPresentation("Samstag");
			for (String name : List.of("Arnold", "Bauer", "Christ", "Dietz")) {
				Booking booking = presentation.addBooking();
				booking.setLastName(name);
				master.add(new BookingDTO(event, presentation, booking));
			}
			lastName.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().getBooking().getLastName()));
			table.getColumns().add(lastName);
			table.setItems(filteredData);
			columnFilters = TableFilter.forTableView(table).apply();
			table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
		}

		Booking booking(String name) {
			return master.stream().map(BookingDTO::getBooking).filter(b -> b.getLastName().equals(name)).findFirst().orElseThrow();
		}

		void sortDescending() {
			lastName.setSortType(TableColumn.SortType.DESCENDING);
			table.getSortOrder().add(lastName);
			table.sort();
		}

		List<String> select(String... names) {
			TicketTableController.select(table, Arrays.stream(names).map(this::booking).toList());
			return table.getSelectionModel().getSelectedItems().stream().map(dto -> dto.getBooking().getLastName()).toList();
		}
	}

	@Test
	void selectionInSortedTableHitsTheSameBookings() throws Exception {
		List<String> selected = onFx(() -> {
			Fixture fixture = new Fixture();
			fixture.sortDescending();
			return fixture.select("Arnold", "Christ");
		});
		// in Tabellenreihenfolge (absteigend)
		assertEquals(List.of("Christ", "Arnold"), selected);
	}

	@Test
	void selectionWithColumnFilterHitsTheSameBookings() throws Exception {
		List<String> selected = onFx(() -> {
			Fixture fixture = new Fixture();
			fixture.columnFilters.unselectValue(fixture.lastName, "Arnold");
			fixture.columnFilters.executeFilter();
			return fixture.select("Christ");
		});
		assertEquals(List.of("Christ"), selected);
	}

	@Test
	void bookingHiddenBySearchStaysUnselected() throws Exception {
		List<String> selected = onFx(() -> {
			Fixture fixture = new Fixture();
			fixture.sortDescending();
			fixture.filteredData.setPredicate(dto -> !dto.getBooking().getLastName().equals("Bauer"));
			return fixture.select("Bauer", "Arnold");
		});
		assertEquals(List.of("Arnold"), selected);
	}
}
