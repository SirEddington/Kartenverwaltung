package de.eltviller_carneval_verein.karten.ui.shared;

import java.nio.file.Path;

import de.eltviller_carneval_verein.karten.store.ConnectionControl;
import de.eltviller_carneval_verein.karten.validation.MessageTemplate;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * Die Ladeansicht des Verbindens ("Verbinde mit dem gemeinsamen Ordner … (n %)") und die übrige Oberfläche, die
 * {@link ConnectFlow} braucht. Der Fortschritt folgt {@code fullSyncProgress()} (unbestimmt bei {@code null}). Fällt der
 * Abgleich im ersten Lauf aus, steht unter dem Balken die Ursache und der Knopf "Trotzdem öffnen"; nach einem Beiseitelegen
 * zusätzlich, wo die bisherigen Daten liegen.
 * <p>
 * Was nach dem Ende geschieht (neu laden, Hauptmenü, zurück in die Einstellungen) und wohin ein Fehler der Prüfungen
 * geht, entscheidet der {@link Host}. Alle Aufrufe auf dem FX-Thread.
 */
public final class ConnectProgressView implements ConnectFlow.Screen {

	/** Breite des Balkens und der Texte darunter. */
	private static final double WIDTH = 560;

	/** Was um die Ladeansicht herum geschieht. */
	public interface Host {

		/** Zeigt den Inhalt im Hauptfenster, ohne nach ungespeicherten Änderungen zu fragen (das tat der Ablauf schon). */
		void show(Parent content);

		/** Das Hauptfenster, über dem die Frage erscheint. */
		Window owner();

		/** Eine Prüfung vor dem Verbinden scheiterte: den Text am Einstellungsabschnitt zeigen. */
		void checkFailed(String text);

		/** Der erste Lauf ist zu Ende oder "Trotzdem öffnen": neu laden (beim Start: erstes Laden), dann Hauptmenü. */
		void finished();

		/** Abgebrochen: zurück in die Einstellungen. */
		void cancelled();
	}

	private final ConnectionControl sync;
	private final Host host;
	private final ChangeListener<Double> progressListener = (observable, oldValue, newValue) -> showProgressValue(newValue);
	private ConnectFlow flow;
	private boolean shown;

	// Die Knoten der gerade gezeigten Ansicht; jede Ladeansicht wird neu aufgebaut
	private Label title;
	private ProgressBar bar;
	private Label hint;
	private Label setAside;
	private Button openAnyway;

	public ConnectProgressView(ConnectionControl sync, Host host) {
		this.sync = sync;
		this.host = host;
	}

	/** Der Ablauf, dem die Knöpfe antworten (er braucht seinerseits diese Ansicht, daher nicht im Konstruktor). */
	public void setFlow(ConnectFlow flow) {
		this.flow = flow;
	}

	/** Ob die Ladeansicht gerade zu sehen ist; solange öffnet sich der Konflikt-Dialog nicht von selbst. */
	public boolean isShown() {
		return shown;
	}

	@Override
	public void showProgress() {
		if (shown) {
			return;
		}
		shown = true;
		Parent content = build();
		sync.fullSyncProgress().addListener(progressListener);
		showProgressValue(sync.fullSyncProgress().get());
		host.show(content);
	}

	@Override
	public void checkFailed(String text) {
		host.checkFailed(text);
	}

	@Override
	public void askDecision(String dir, boolean setAsideAllowed) {
		ConnectDecisionDialog.show(host.owner(), setAsideAllowed, false, flow::decide, flow::cancelDecision);
	}

	@Override
	public void showHint(String message, Path setAsidePath) {
		if (!shown) {
			return;
		}
		hint.setText(SharedFolderTexts.outageHint(message));
		setShown(hint, true);
		if (setAsidePath != null) {
			setAside.setText(MessageTemplate.format(SharedFolderTexts.SET_ASIDE_NOTE, setAsidePath));
			setShown(setAside, true);
		}
		setShown(openAnyway, true);
	}

	@Override
	public void hideHint() {
		if (shown) {
			setShown(hint, false);
		}
	}

	@Override
	public void finished() {
		leave();
		host.finished();
	}

	@Override
	public void cancelled() {
		leave();
		host.cancelled();
	}

	/** Die Ladeansicht ist zu Ende: nicht mehr auf den Fortschritt hören, eine offene Frage schließen. */
	private void leave() {
		shown = false;
		sync.fullSyncProgress().removeListener(progressListener);
		ConnectDecisionDialog.closeOpen();
	}

	private Parent build() {
		title = new Label();
		title.getStyleClass().add("settings-label");
		bar = new ProgressBar();
		bar.setPrefWidth(WIDTH);
		Label note = wrapped(SharedFolderTexts.PROGRESS_NOTE);
		note.getStyleClass().add("settings-hint");

		hint = wrapped(null);
		setAside = wrapped(null);
		openAnyway = new Button(SharedFolderTexts.OPEN_ANYWAY);
		openAnyway.getStyleClass().add("button-secondary");
		openAnyway.setOnAction(e -> flow.openAnyway());
		setShown(hint, false);
		setShown(setAside, false);
		setShown(openAnyway, false);

		VBox box = new VBox(16, title, bar, note, hint, setAside, openAnyway);
		box.setAlignment(Pos.CENTER);
		box.setMaxWidth(WIDTH);
		VBox outer = new VBox(box);
		outer.setAlignment(Pos.CENTER);
		return outer;
	}

	private void showProgressValue(Double progress) {
		if (!shown) {
			return;
		}
		title.setText(SharedFolderTexts.progressText(progress));
		bar.setProgress(progress == null ? ProgressBar.INDETERMINATE_PROGRESS : progress);
	}

	private static Label wrapped(String text) {
		Label label = new Label(text);
		label.setWrapText(true);
		label.setMaxWidth(WIDTH);
		label.setMinHeight(Region.USE_PREF_SIZE);
		return label;
	}

	private static void setShown(Node node, boolean shown) {
		node.setVisible(shown);
		node.setManaged(shown);
	}
}
