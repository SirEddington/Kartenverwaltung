package de.eltviller_carneval_verein.karten.ui.shared;

import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import de.eltviller_carneval_verein.karten.MainApp;
import de.eltviller_carneval_verein.karten.store.ConnectMode;
import javafx.event.Event;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * Die Frage beim Verbinden, wenn auf beiden Seiten schon Daten liegen: "Mitnehmen", "Beiseitelegen" oder "Abbrechen", je
 * mit Erklärung. Ohne {@code setAsideAllowed} (Schutzregel) fehlt "Beiseitelegen"; an seiner Stelle steht, warum.
 * <p>
 * Es ist höchstens eine Frage offen: Eine neue (etwa nur noch mit "Mitnehmen") ersetzt die alte. Während eines Ablaufs mit
 * Ladeansicht ist die Frage modal und lässt sich nur über die Knöpfe schließen; im Betrieb (Modus ASK) lässt sie sich ohne
 * Antwort schließen und später wieder öffnen.
 */
public final class ConnectDecisionDialog {

	private static final Logger LOG = Logger.getLogger(ConnectDecisionDialog.class.getName());
	/** Breite des Fensters; die Erklärungen brechen darin um. */
	private static final double WIDTH = 640;
	/** Breite der Knöpfe, damit die Erklärungen bündig beginnen. */
	private static final double BUTTON_WIDTH = 140;

	/** Die offene Frage; {@code null}, solange keine offen ist. */
	private static ConnectDecisionDialog open;

	private final Stage stage = new Stage();
	private boolean answered;

	private ConnectDecisionDialog(Window owner, boolean setAsideAllowed, boolean closable, Consumer<ConnectMode> decide,
			Runnable cancel) {
		stage.setTitle(SharedFolderTexts.DECISION_TITLE);
		if (owner != null) {
			stage.initOwner(owner);
		}
		if (!closable) {
			stage.initModality(Modality.WINDOW_MODAL);
			// Die Ladeansicht wartet auf die Antwort: Ohne sie ginge es nicht weiter
			stage.setOnCloseRequest(Event::consume);
		}
		try {
			stage.getIcons().add(new Image(MainApp.class.getResourceAsStream(
					"/de/eltviller_carneval_verein/karten/ui/images/harlekin_logo.png")));
		} catch (RuntimeException e) {
			LOG.log(Level.FINE, "Symbol der Frage beim Verbinden nicht ladbar", e);
		}

		Label text = wrapped(SharedFolderTexts.DECISION_TEXT);
		text.getStyleClass().add("settings-label");

		Button merge = new Button(SharedFolderTexts.MERGE);
		merge.getStyleClass().add("button-primary");
		merge.setOnAction(e -> answer(() -> decide.accept(ConnectMode.MERGE)));
		VBox options = new VBox(14, option(merge, SharedFolderTexts.MERGE_EXPLANATION));

		if (setAsideAllowed) {
			Button setAside = new Button(SharedFolderTexts.SET_ASIDE);
			setAside.getStyleClass().add("button-secondary");
			setAside.setOnAction(e -> answer(() -> decide.accept(ConnectMode.SET_ASIDE)));
			options.getChildren().add(option(setAside, SharedFolderTexts.SET_ASIDE_EXPLANATION));
		} else {
			options.getChildren().add(wrapped(SharedFolderTexts.SET_ASIDE_LOCKED));
		}

		Button cancelButton = new Button(SharedFolderTexts.CANCEL);
		cancelButton.getStyleClass().add("button-default");
		cancelButton.setOnAction(e -> answer(cancel));
		HBox buttons = new HBox(cancelButton);
		buttons.setAlignment(Pos.CENTER_RIGHT);

		VBox root = new VBox(18, text, options, buttons);
		root.setPadding(new Insets(20));
		Scene scene = new Scene(root, WIDTH, -1);
		scene.getStylesheets().add(MainApp.class.getResource("/de/eltviller_carneval_verein/karten/ui/style.css").toExternalForm());
		stage.setScene(scene);
		stage.setResizable(false);
		stage.setOnHidden(event -> {
			if (open == this) {
				open = null;
			}
		});
	}

	/**
	 * Zeigt die Frage; eine schon offene wird ersetzt.
	 *
	 * @param owner           das Hauptfenster
	 * @param setAsideAllowed ob "Beiseitelegen" zur Wahl steht
	 * @param closable        ob sich die Frage ohne Antwort schließen lässt (im Betrieb); sonst ist sie modal
	 * @param decide          die Antwort "Mitnehmen" bzw. "Beiseitelegen"
	 * @param cancel          "Abbrechen"
	 */
	@SuppressWarnings("exports")
	public static void show(Window owner, boolean setAsideAllowed, boolean closable, Consumer<ConnectMode> decide,
			Runnable cancel) {
		closeOpen();
		open = new ConnectDecisionDialog(owner, setAsideAllowed, closable, decide, cancel);
		open.stage.show();
	}

	/** Schließt eine offene Frage ohne Antwort (der Ablauf ist zu Ende); ohne offene Frage nichts. */
	public static void closeOpen() {
		if (open != null) {
			ConnectDecisionDialog closing = open;
			open = null;
			closing.answered = true;
			closing.stage.close();
		}
	}

	/** Eine Antwort gilt nur einmal; das Fenster schließt vor der Antwort, damit eine neue Frage es nicht verdeckt. */
	private void answer(Runnable action) {
		if (answered) {
			return;
		}
		answered = true;
		if (open == this) {
			open = null;
		}
		stage.close();
		action.run();
	}

	private static Node option(Button button, String explanation) {
		button.setMinWidth(BUTTON_WIDTH);
		button.setPrefWidth(BUTTON_WIDTH);
		Label label = wrapped(explanation);
		HBox.setHgrow(label, Priority.ALWAYS);
		HBox row = new HBox(14, button, label);
		row.setAlignment(Pos.TOP_LEFT);
		return row;
	}

	private static Label wrapped(String text) {
		Label label = new Label(text);
		label.setWrapText(true);
		label.setMaxWidth(Double.MAX_VALUE);
		// Umbrochene Texte werden nicht auf eine Zeile gekürzt
		label.setMinHeight(Region.USE_PREF_SIZE);
		return label;
	}
}
