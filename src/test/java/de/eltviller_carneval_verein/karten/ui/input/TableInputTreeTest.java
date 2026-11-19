package de.eltviller_carneval_verein.karten.ui.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTablePosition;
import javafx.scene.control.TreeTableView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;

/**
 * Tab in einer {@link TreeTableView} mit Gruppen: {@link TableInput} überspringt Zeilen, die die Bedingung der Zeile
 * nicht als bearbeitbar zulässt (hier die Gruppenzeilen), und zugeklappte Gruppen, ohne sie aufzuklappen.
 */
class TableInputTreeTest {

	/** Eine Zeile: Buchung oder Gruppe. {@link TableInput} kennt nur die Bedingung, nicht diesen Typ. */
	private record Zeile(String name, boolean gruppe) {
	}

	private static final int FIRST = 0;
	private static final int LAST = 2;

	private Stage stage;
	private TreeTableView<Zeile> table;

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

	private static void flush() throws Exception {
		onFx(() -> null);
		onFx(() -> null);
	}

	private static TreeItem<Zeile> buchung(String name) {
		return new TreeItem<>(new Zeile(name, false));
	}

	@SafeVarargs
	private static TreeItem<Zeile> gruppe(String name, boolean expanded, TreeItem<Zeile>... buchungen) {
		TreeItem<Zeile> item = new TreeItem<>(new Zeile(name, true));
		item.getChildren().addAll(buchungen);
		item.setExpanded(expanded);
		return item;
	}

	/** Zeigt eine Baumtabelle mit drei bearbeitbaren Spalten und den Zeilen unter einer unsichtbaren Wurzel. */
	@SafeVarargs
	private void show(TreeItem<Zeile>... zeilen) throws Exception {
		onFx(() -> {
			table = new TreeTableView<>();
			table.setEditable(true);
			table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
			for (String title : List.of("Nachname", "Vorname", "Ort")) {
				TreeTableColumn<Zeile, String> column = new TreeTableColumn<>(title);
				column.setEditable(true);
				column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().getValue().name()));
				table.getColumns().add(column);
			}
			TreeItem<Zeile> root = new TreeItem<>();
			root.getChildren().addAll(zeilen);
			root.setExpanded(true);
			table.setRoot(root);
			table.setShowRoot(false);
			TableInput.install(table, zeile -> !zeile.gruppe());
			stage = new Stage();
			stage.setScene(new Scene(table, 600, 400));
			stage.show();
			table.applyCss();
			table.layout();
			table.requestFocus();
			return null;
		});
		flush();
	}

	@AfterEach
	void closeStage() throws Exception {
		if (stage == null) {
			return;
		}
		onFx(() -> {
			stage.hide();
			return null;
		});
	}

	private void focus(int row, int column) throws Exception {
		onFx(() -> {
			table.getSelectionModel().clearAndSelect(row);
			table.getFocusModel().focus(row, table.getColumns().get(column));
			table.requestFocus();
			return null;
		});
		flush();
	}

	private void press(KeyCode code, boolean shift) throws Exception {
		onFx(() -> {
			Event.fireEvent(table, new KeyEvent(this, table, KeyEvent.KEY_PRESSED, KeyEvent.CHAR_UNDEFINED, "", code, shift, false, false, false));
			return null;
		});
		flush();
	}

	private void assertFocus(int row, int column, String message) throws Exception {
		TreeTablePosition<Zeile, ?> focused = onFx(() -> table.getFocusModel().getFocusedCell());
		assertEquals(row, focused.getRow(), message);
		assertSame(table.getColumns().get(column), focused.getTableColumn(), message);
	}

	private List<Integer> selectedRows() throws Exception {
		return onFx(() -> List.copyOf(table.getSelectionModel().getSelectedIndices()));
	}

	@Test
	void tabUeberspringtGruppenzeile() throws Exception {
		// Zeilen: 0 Buchung, 1 Gruppe, 2 Buchung
		show(buchung("A"), gruppe("G", true, buchung("B")));
		focus(0, LAST);

		press(KeyCode.TAB, false);

		assertFocus(2, FIRST, "Tab überspringt die Gruppenzeile");
		assertEquals(List.of(2), selectedRows());
	}

	@Test
	void tabAmGruppenendeSpringtInErsteBuchungDerNaechsten() throws Exception {
		// Zeilen: 0 Gruppe, 1 Buchung, 2 Buchung, 3 Gruppe, 4 Buchung, 5 Buchung
		show(gruppe("G1", true, buchung("A"), buchung("B")), gruppe("G2", true, buchung("C"), buchung("D")));
		focus(2, LAST);

		press(KeyCode.TAB, false);

		assertFocus(4, FIRST, "Tab am Ende der Gruppe springt in die erste Buchung der nächsten");
		assertEquals(List.of(4), selectedRows());
	}

	@Test
	void umschaltTabUeberspringtGruppenzeile() throws Exception {
		show(gruppe("G1", true, buchung("A"), buchung("B")), gruppe("G2", true, buchung("C"), buchung("D")));
		focus(4, FIRST);

		press(KeyCode.TAB, true);

		assertFocus(2, LAST, "Umschalt+Tab überspringt die Gruppenzeile rückwärts");
		assertEquals(List.of(2), selectedRows());
	}

	@Test
	void tabUeberspringtZugeklappteGruppe() throws Exception {
		// Zeilen: 0 Gruppe, 1 Buchung, 2 Gruppe (zu), 3 Gruppe, 4 Buchung
		TreeItem<Zeile> zu = gruppe("G2", false, buchung("B"));
		show(gruppe("G1", true, buchung("A")), zu, gruppe("G3", true, buchung("C")));
		focus(1, LAST);

		press(KeyCode.TAB, false);

		assertFocus(4, FIRST, "Tab überspringt die zugeklappte Gruppe");
		assertFalse(onFx(zu::isExpanded), "die zugeklappte Gruppe bleibt zu");
	}

	@Test
	void ohneWeitereBuchungBleibtDerFokus() throws Exception {
		// Zeilen: 0 Gruppe, 1 Buchung, 2 Gruppe (zu)
		show(gruppe("G1", true, buchung("A")), gruppe("G2", false, buchung("B")));
		focus(1, LAST);

		press(KeyCode.TAB, false);

		assertFocus(1, LAST, "nach der letzten Buchung gibt es keine weitere bearbeitbare Zeile");

		focus(1, FIRST);

		press(KeyCode.TAB, true);

		assertFocus(1, FIRST, "vor der ersten Buchung liegt nur eine Gruppenzeile");
	}
}
