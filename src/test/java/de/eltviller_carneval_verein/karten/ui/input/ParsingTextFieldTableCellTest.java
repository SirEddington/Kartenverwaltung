package de.eltviller_carneval_verein.karten.ui.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.scene.Scene;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TablePosition;
import javafx.scene.control.TableView;
import javafx.util.converter.DefaultStringConverter;

/** Die Zeilenbedingung der Textzelle ({@link ParsingTextFieldTableCell#forTableColumn} mit {@code editableRow}). */
class ParsingTextFieldTableCellTest {

	/** Eine Zeile der Testtabelle: ein Wert und ob er sich bearbeiten lässt. */
	private record Row(String value, boolean editable) {
	}

	@BeforeAll
	static void startFx() {
		try {
			Platform.startup(() -> {
			});
		} catch (IllegalStateException alreadyStarted) {
			// der Toolkit läuft schon (anderer Test im selben Lauf)
		}
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

	/** Eine bearbeitbare Tabelle mit einer gesperrten und einer freien Zeile, ausgelegt wie auf dem Bildschirm. */
	private static TableView<Row> table() {
		TableView<Row> table = new TableView<>();
		table.setEditable(true);
		TableColumn<Row, String> column = new TableColumn<>("Wert");
		column.setEditable(true);
		column.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().value()));
		column.setCellFactory(ParsingTextFieldTableCell.forTableColumn(new DefaultStringConverter(), InputIssue.NOT_A_NUMBER, "Wert",
				row -> null, Row::editable));
		table.getColumns().add(column);
		table.getItems().addAll(new Row("gesperrt", false), new Row("frei", true));
		new Scene(table, 300, 200);
		table.applyCss();
		table.layout();
		return table;
	}

	@Test
	void editOfALockedRowLeavesNoEditingCellBehind() throws Exception {
		TablePosition<Row, ?> editing = onFx(() -> {
			TableView<Row> table = table();
			table.edit(0, table.getColumns().get(0));
			return table.getEditingCell();
		});

		assertNull(editing, "eine gesperrte Zeile darf die Tabelle nicht im Bearbeiten festhalten");
	}

	@Test
	void editOfAFreeRowStillStarts() throws Exception {
		Integer editingRow = onFx(() -> {
			TableView<Row> table = table();
			table.edit(1, table.getColumns().get(0));
			return table.getEditingCell() == null ? null : table.getEditingCell().getRow();
		});

		assertEquals(1, editingRow);
	}
}
