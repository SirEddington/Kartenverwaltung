package de.eltviller_carneval_verein.karten.ui.shared;

import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

/**
 * Das Info-Fenster zum Abgleich mit dem gemeinsamen Ordner (Spec "Fußzeile: SyncIndicator"): Ordner, letzter Abgleich,
 * Ursache der Störung, Stand des ersten Abgleichs und die Liste der nicht abgeglichenen Einträge. Der Inhalt kommt fertig
 * als {@link SyncInfo}. Knöpfe "Jetzt abgleichen" und "Schließen". Nicht modal, höchstens ein Fenster je App: Ein erneutes
 * {@link #show} holt es nach vorn. Aufgefrischt wird es von {@link #refreshIfOpen()} (bei jeder Rückmeldung des Abgleichs),
 * beim Fokus und im 1-s-Takt, solange es offen ist (Fortschritt, Ende eines Laufs). Alle Aufrufe im JavaFX-Thread.
 */
public final class SyncInfoWindow {

	private static final Logger LOG = Logger.getLogger(SyncInfoWindow.class.getName());
	private static final String STYLESHEET = "/de/eltviller_carneval_verein/karten/ui/style.css";
	private static final String ICON = "/de/eltviller_carneval_verein/karten/ui/images/harlekin_logo.png";

	/** Das eine Fenster der App; {@code null}, solange keins offen ist. */
	private static SyncInfoWindow open;

	private final Supplier<SyncInfo> info;
	private final Stage stage = new Stage();
	private final Label folder = new Label();
	private final Label lastSync = new Label();
	private final Label cause = new Label();
	private final Label firstRun = new Label();
	private final GridPane list = new GridPane();
	private final ScrollPane listScroll = new ScrollPane(list);
	private final Label listHint = new Label();
	private final Timeline ticker = new Timeline(new KeyFrame(Duration.seconds(1), event -> refresh()));

	private SyncInfoWindow(Window owner, Supplier<SyncInfo> info, Runnable syncNow) {
		this.info = info;
		stage.setTitle(SharedFolderTexts.INFO_TITLE);
		if (owner != null) {
			stage.initOwner(owner);
		}
		try {
			stage.getIcons().add(new Image(SyncInfoWindow.class.getResourceAsStream(ICON)));
		} catch (RuntimeException e) {
			LOG.log(Level.FINE, "Symbol des Info-Fensters nicht ladbar", e);
		}

		for (Label label : new Label[] { folder, lastSync, cause, firstRun, listHint }) {
			label.setWrapText(true);
		}
		Label listTitle = new Label(SharedFolderTexts.INFO_LIST_TITLE);
		listTitle.setStyle("-fx-font-weight: bold;");
		ColumnConstraints entryColumn = new ColumnConstraints();
		entryColumn.setPercentWidth(50);
		ColumnConstraints causeColumn = new ColumnConstraints();
		causeColumn.setPercentWidth(50);
		list.getColumnConstraints().addAll(entryColumn, causeColumn);
		list.setHgap(16);
		list.setVgap(6);
		list.setPadding(new Insets(6));
		listScroll.setFitToWidth(true);
		listScroll.setPrefViewportHeight(160);
		VBox.setVgrow(listScroll, Priority.ALWAYS);

		Button sync = new Button(SharedFolderTexts.SYNC_NOW);
		sync.setOnAction(event -> {
			try {
				syncNow.run();
			} catch (RuntimeException e) {
				LOG.log(Level.WARNING, "Der Abgleich ließ sich nicht anfordern", e);
			}
		});
		Button close = new Button(SharedFolderTexts.CLOSE);
		close.setCancelButton(true);
		close.setOnAction(event -> stage.close());
		HBox buttons = new HBox(10, sync, close);
		buttons.setAlignment(Pos.CENTER_RIGHT);

		VBox root = new VBox(8, folder, lastSync, cause, firstRun, listTitle, listScroll, listHint, buttons);
		root.setPadding(new Insets(14));
		Scene scene = new Scene(root, 640, 420);
		scene.getStylesheets().add(SyncInfoWindow.class.getResource(STYLESHEET).toExternalForm());
		stage.setScene(scene);
		stage.setMinWidth(480);
		stage.setMinHeight(340);
		stage.focusedProperty().addListener((observable, was, now) -> {
			if (now) {
				refresh();
			}
		});
		ticker.setCycleCount(Animation.INDEFINITE);
		stage.setOnHidden(event -> {
			ticker.stop();
			if (open == this) {
				open = null;
			}
		});
	}

	/**
	 * Zeigt das Fenster; ist es schon offen, wird es nach vorn geholt und aufgefrischt.
	 *
	 * @param owner   das Hauptfenster; {@code null} ohne Elternfenster
	 * @param info    liefert den aktuellen Inhalt, bei jedem Auffrischen neu
	 * @param syncNow fordert einen Vollabgleich an ({@code ConnectionControl.syncNow})
	 */
	@SuppressWarnings("exports")
	public static void show(Window owner, Supplier<SyncInfo> info, Runnable syncNow) {
		if (open == null) {
			open = new SyncInfoWindow(owner, info, syncNow);
		}
		open.refresh();
		open.stage.show();
		open.stage.toFront();
		open.ticker.play();
	}

	/** Frischt das Fenster auf, wenn es offen ist. */
	public static void refreshIfOpen() {
		if (open != null) {
			open.refresh();
		}
	}

	private void refresh() {
		SyncInfo content;
		try {
			content = info.get();
		} catch (RuntimeException e) {
			LOG.log(Level.WARNING, "Das Info-Fenster zum Abgleich ließ sich nicht auffrischen", e);
			return;
		}
		folder.setText(content.folderLine());
		lastSync.setText(content.lastSyncLine());
		cause.setText(content.causeLine());
		boolean hasCause = !content.causeLine().isEmpty();
		cause.setVisible(hasCause);
		cause.setManaged(hasCause);
		firstRun.setText(content.firstRunLine());
		fillList(content);
		listHint.setText(content.listHint());
	}

	private void fillList(SyncInfo content) {
		list.getChildren().clear();
		boolean any = !content.rows().isEmpty();
		listScroll.setVisible(any);
		listScroll.setManaged(any);
		if (!any) {
			return;
		}
		list.add(header(SharedFolderTexts.INFO_COLUMN_ENTRY), 0, 0);
		list.add(header(SharedFolderTexts.INFO_COLUMN_CAUSE), 1, 0);
		int row = 1;
		for (SyncInfo.Row entry : content.rows()) {
			list.add(cell(entry.entry()), 0, row);
			list.add(cell(entry.cause()), 1, row);
			row++;
		}
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
