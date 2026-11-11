package de.eltviller_carneval_verein.karten.ui.shared;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import de.eltviller_carneval_verein.karten.update.PatchNotes.Release;
import de.eltviller_carneval_verein.karten.update.PatchNotes.Section;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * Das Fenster „Was ist neu“ (KARV-125): nicht modal über dem Hauptfenster, höchstens eins gleichzeitig. Je Version eine
 * Überschrift, darunter die Abschnitte fett und die Einträge als Aufzählung; längere Inhalte scrollen, und das Fenster
 * wird nie höher als 70 % des Bildschirms, damit „Schließen“ immer zu sehen ist.
 */
public final class PatchNotesWindow {

	private static final Logger LOG = Logger.getLogger(PatchNotesWindow.class.getName());
	private static final String STYLESHEET = "/de/eltviller_carneval_verein/karten/ui/style.css";
	private static final String ICON = "/de/eltviller_carneval_verein/karten/ui/images/harlekin_logo.png";

	/** Das eine Fenster der App; {@code null}, solange keins gebaut ist. */
	private static PatchNotesWindow open;

	private final Stage stage = new Stage();
	private final VBox content = new VBox(6);

	private PatchNotesWindow(Window owner) {
		if (owner != null) {
			stage.initOwner(owner);
		}
		try {
			stage.getIcons().add(new Image(PatchNotesWindow.class.getResourceAsStream(ICON)));
		} catch (RuntimeException e) {
			LOG.log(Level.FINE, "Symbol des Patchnotes-Fensters nicht ladbar", e);
		}
		content.setId("patch-notes-content");
		content.setPadding(new Insets(4, 8, 4, 4));
		ScrollPane scroll = new ScrollPane(content);
		// ohne fitToWidth brechen die Labels nicht um und das Fenster würde so breit wie die längste Zeile
		scroll.setFitToWidth(true);
		scroll.setPrefViewportHeight(480);
		VBox.setVgrow(scroll, Priority.ALWAYS);

		Button close = new Button(UpdateTexts.PATCH_NOTES_CLOSE);
		close.setId("patch-notes-close");
		close.getStyleClass().add("button-default");
		close.setOnAction(e -> stage.close());
		// Escape schließt wie in SyncInfoWindow
		close.setCancelButton(true);
		HBox buttons = new HBox(close);
		buttons.setAlignment(Pos.CENTER_RIGHT);

		VBox root = new VBox(12, scroll, buttons);
		root.setPadding(new Insets(16));
		root.setPrefWidth(560);
		Scene scene = new Scene(root);
		scene.getStylesheets().add(PatchNotesWindow.class.getResource(STYLESHEET).toExternalForm());
		stage.setScene(scene);
		stage.setMinWidth(480);
	}

	/**
	 * Zeigt die Versionen; ist das Fenster schon offen, ersetzt es den Inhalt und kommt nach vorn. Eine leere Liste
	 * öffnet nichts.
	 *
	 * @param owner das Hauptfenster; {@code null} ohne Elternfenster (dann zählt der Hauptbildschirm)
	 */
	@SuppressWarnings("exports")
	public static void show(Window owner, List<Release> releases) {
		if (releases.isEmpty()) {
			return;
		}
		if (open == null) {
			open = new PatchNotesWindow(owner);
		}
		open.fill(releases);
		open.stage.setMaxHeight(screenHeight(owner) * 0.7);
		open.stage.show();
		open.stage.sizeToScene();
		open.stage.toFront();
	}

	/** Das gebaute Fenster oder {@code null}; für Tests. */
	static Stage openStage() {
		return open == null ? null : open.stage;
	}

	/** Schließt und vergisst das Fenster; für Tests. */
	static void closeForTest() {
		if (open != null) {
			open.stage.close();
			open = null;
		}
	}

	private void fill(List<Release> releases) {
		stage.setTitle(releases.size() == 1 ? UpdateTexts.patchNotesTitle(releases.get(0).version().version())
				: UpdateTexts.PATCH_NOTES_TITLE);
		content.getChildren().clear();
		for (Release release : releases) {
			Label version = new Label(UpdateTexts.patchNotesVersion(release.version().version()));
			version.setStyle("-fx-font-weight: bold; -fx-font-size: 1.25em;");
			if (!content.getChildren().isEmpty()) {
				VBox.setMargin(version, new Insets(12, 0, 0, 0));
			}
			content.getChildren().add(version);
			for (Section section : release.sections()) {
				Label title = new Label(section.kind().title());
				title.setStyle("-fx-font-weight: bold;");
				VBox.setMargin(title, new Insets(6, 0, 0, 0));
				content.getChildren().add(title);
				for (String entry : section.entries()) {
					Label item = new Label("•  " + entry);
					item.setWrapText(true);
					item.setMaxWidth(Double.MAX_VALUE);
					content.getChildren().add(item);
				}
			}
		}
	}

	/** Die Höhe der sichtbaren Fläche des Bildschirms von {@code owner}; ohne Besitzer oder außerhalb jedes Bildschirms der Hauptbildschirm. */
	static double screenHeight(Window owner) {
		List<Screen> screens = owner == null ? List.of()
				: Screen.getScreensForRectangle(owner.getX(), owner.getY(), Math.max(1, owner.getWidth()),
						Math.max(1, owner.getHeight()));
		Rectangle2D bounds = (screens.isEmpty() ? Screen.getPrimary() : screens.get(0)).getVisualBounds();
		return bounds.getHeight();
	}
}
