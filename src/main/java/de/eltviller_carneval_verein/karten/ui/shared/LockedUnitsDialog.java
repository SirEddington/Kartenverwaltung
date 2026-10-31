package de.eltviller_carneval_verein.karten.ui.shared;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.ui.ConfirmDialog;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * Der Dialog "Gesperrte Einträge" (Spec "Gesperrte Einheiten"): Spalten Eintrag, Ursache und Aktion. Bei einem beschädigten
 * Eintrag, den der Versionsspeicher zurücksetzen kann, steht in der Aktion der Knopf "Auf letzten lesbaren Stand
 * zurücksetzen" (mit Rückfrage); bei einem Eintrag, der von einer neueren Programmversion stammt, steht nur die Ursache, die
 * auf das Update hinweist. Die Zeilen liefert {@link LockedUnits#rows}; der Dialog frischt sich über {@link #refreshIfOpen()}
 * auf, wenn sich die gesperrten Einträge ändern (etwa nach dem Zurücksetzen), und zeigt dann "Keine gesperrten Einträge."
 * statt der Liste. Nicht modal, höchstens ein Fenster je App: Ein erneutes {@link #show} holt es nach vorn. Alle Aufrufe im
 * JavaFX-Thread.
 */
public final class LockedUnitsDialog {

	private static final Logger LOG = Logger.getLogger(LockedUnitsDialog.class.getName());
	private static final String STYLESHEET = "/de/eltviller_carneval_verein/karten/ui/style.css";
	private static final String ICON = "/de/eltviller_carneval_verein/karten/ui/images/harlekin_logo.png";

	/** Das eine Fenster der App; {@code null}, solange keins offen ist. */
	private static LockedUnitsDialog open;

	private final Supplier<List<LockedUnits.Row>> rows;
	private final Consumer<UnitKey> reset;
	private final Stage stage = new Stage();
	private final GridPane list = new GridPane();
	private final ScrollPane listScroll = new ScrollPane(list);
	private final Label empty = new Label(SharedFolderTexts.LOCKED_EMPTY);

	private LockedUnitsDialog(Window owner, Supplier<List<LockedUnits.Row>> rows, Consumer<UnitKey> reset) {
		this.rows = rows;
		this.reset = reset;
		stage.setTitle(SharedFolderTexts.LOCKED_TITLE);
		if (owner != null) {
			stage.initOwner(owner);
		}
		try {
			stage.getIcons().add(new Image(LockedUnitsDialog.class.getResourceAsStream(ICON)));
		} catch (RuntimeException e) {
			LOG.log(Level.FINE, "Symbol des Dialogs nicht ladbar", e);
		}

		Label intro = new Label(SharedFolderTexts.LOCKED_INTRO);
		intro.setWrapText(true);
		ColumnConstraints entryColumn = new ColumnConstraints();
		entryColumn.setPercentWidth(38);
		ColumnConstraints causeColumn = new ColumnConstraints();
		causeColumn.setPercentWidth(34);
		ColumnConstraints actionColumn = new ColumnConstraints();
		actionColumn.setPercentWidth(28);
		list.getColumnConstraints().addAll(entryColumn, causeColumn, actionColumn);
		list.setHgap(16);
		list.setVgap(8);
		list.setPadding(new Insets(6));
		listScroll.setFitToWidth(true);
		listScroll.setPrefViewportHeight(180);
		VBox.setVgrow(listScroll, Priority.ALWAYS);

		Button close = new Button(SharedFolderTexts.CLOSE);
		close.setCancelButton(true);
		close.setOnAction(event -> stage.close());
		HBox buttons = new HBox(10, close);
		buttons.setAlignment(Pos.CENTER_RIGHT);

		VBox root = new VBox(10, intro, listScroll, empty, buttons);
		root.setPadding(new Insets(14));
		Scene scene = new Scene(root, 720, 360);
		scene.getStylesheets().add(LockedUnitsDialog.class.getResource(STYLESHEET).toExternalForm());
		stage.setScene(scene);
		stage.setMinWidth(560);
		stage.setMinHeight(260);
		stage.setOnHidden(event -> {
			if (open == this) {
				open = null;
			}
		});
	}

	/**
	 * Zeigt den Dialog; ist er schon offen, wird er nach vorn geholt und aufgefrischt.
	 *
	 * @param owner das Hauptfenster; {@code null} ohne Elternfenster
	 * @param rows  liefert die Zeilen, bei jedem Auffrischen neu ({@code LockedUnits.rows})
	 * @param reset setzt die Einheit zurück ({@code LockedUnits.reset}); die Rückfrage stellt der Dialog vorher
	 */
	@SuppressWarnings("exports")
	public static void show(Window owner, Supplier<List<LockedUnits.Row>> rows, Consumer<UnitKey> reset) {
		if (open == null) {
			open = new LockedUnitsDialog(owner, rows, reset);
		}
		open.refresh();
		open.stage.show();
		open.stage.toFront();
	}

	/** Frischt den Dialog auf, wenn er offen ist. */
	public static void refreshIfOpen() {
		if (open != null) {
			open.refresh();
		}
	}

	private void refresh() {
		List<LockedUnits.Row> current;
		try {
			current = rows.get();
		} catch (RuntimeException e) {
			LOG.log(Level.WARNING, "Die gesperrten Einträge ließen sich nicht auffrischen", e);
			return;
		}
		list.getChildren().clear();
		boolean any = !current.isEmpty();
		listScroll.setVisible(any);
		listScroll.setManaged(any);
		empty.setVisible(!any);
		empty.setManaged(!any);
		if (!any) {
			return;
		}
		list.add(header(SharedFolderTexts.LOCKED_COLUMN_ENTRY), 0, 0);
		list.add(header(SharedFolderTexts.LOCKED_COLUMN_CAUSE), 1, 0);
		list.add(header(SharedFolderTexts.LOCKED_COLUMN_ACTION), 2, 0);
		int row = 1;
		for (LockedUnits.Row entry : current) {
			list.add(cell(entry.entry()), 0, row);
			list.add(cell(entry.cause()), 1, row);
			if (entry.resettable()) {
				list.add(resetButton(entry), 2, row);
			}
			row++;
		}
	}

	private Button resetButton(LockedUnits.Row entry) {
		Button button = new Button(SharedFolderTexts.LOCKED_RESET);
		button.getStyleClass().add("button-default");
		button.setWrapText(true);
		button.setOnAction(event -> {
			if (!ConfirmDialog.confirm(SharedFolderTexts.LOCKED_TITLE, SharedFolderTexts.lockedConfirm(entry.entry()),
					SharedFolderTexts.LOCKED_CONFIRM_YES, "button-danger", stage)) {
				return;
			}
			// bis die Liste sich auffrischt, nicht noch einmal
			button.setDisable(true);
			try {
				reset.accept(entry.unit());
			} catch (RuntimeException e) {
				LOG.log(Level.SEVERE, "Das Zurücksetzen ließ sich nicht anstoßen", e);
				button.setDisable(false);
			}
		});
		return button;
	}

	private static Label header(String text) {
		Label label = new Label(text);
		label.setStyle("-fx-font-weight: bold;");
		return label;
	}

	private static Label cell(String text) {
		Label label = new Label(text);
		label.setWrapText(true);
		label.setMaxWidth(Double.MAX_VALUE);
		return label;
	}
}
