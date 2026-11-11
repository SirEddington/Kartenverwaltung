package de.eltviller_carneval_verein.karten.ui.shared;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import de.eltviller_carneval_verein.karten.update.FolderOffer;
import de.eltviller_carneval_verein.karten.update.FolderState;
import de.eltviller_carneval_verein.karten.update.Installation;
import de.eltviller_carneval_verein.karten.update.UpdateState;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Region;

/**
 * Der ruhige Hinweis in der Fußzeile auf ein Update (KARV-131/132): welcher es ist, entscheidet {@link UpdateHint}
 * (Update im gemeinsamen Ordner, Update von GitHub, Neuinstallation, neuere Kasse, „Update-Prüfung nicht möglich“);
 * sonst unsichtbar und ohne Platz. Ein Klick startet das Update aus dem Ordner bzw. von GitHub, sonst öffnet er die
 * Einstellungen. Wie {@link LockedUnitsIndicator} ohne Listener: Die App ruft {@link #refresh()} für den Hinweis der
 * aktuellen Ansicht. Nicht threadsicher: alle Aufrufe im JavaFX-Thread.
 */
public final class UpdateIndicator extends Hyperlink {

	private static final Logger LOG = Logger.getLogger(UpdateIndicator.class.getName());

	private final Supplier<UpdateState> state;
	private final Supplier<FolderState> folder;
	private final Supplier<Optional<Installation>> inst;
	private final Tooltip tooltip = new Tooltip();
	private Optional<UpdateHint.Hint> hint = Optional.empty();

	/**
	 * @param state            der Zustand der Prüfung (die FX-Property der App)
	 * @param folder           der Stand des gemeinsamen Ordners (die FX-Property der App)
	 * @param inst             die Installation, leer in der Entwicklung
	 * @param update           startet das Update von GitHub auf die bekannte Version
	 * @param updateFromFolder startet das Update aus dem gemeinsamen Ordner
	 * @param openSettings     öffnet die Einstellungen
	 */
	public UpdateIndicator(Supplier<UpdateState> state, Supplier<FolderState> folder, Supplier<Optional<Installation>> inst,
			Consumer<UpdateState.Available> update, Consumer<FolderOffer> updateFromFolder, Runnable openSettings) {
		this.state = state;
		this.folder = folder;
		this.inst = inst;
		getStyleClass().add("update-indicator");
		setMinWidth(Region.USE_PREF_SIZE);
		setTooltip(tooltip);
		setOnAction(event -> {
			setVisited(false);
			Optional<UpdateHint.Hint> h = hint;
			if (h.isEmpty()) {
				return;
			}
			switch (h.get().action()) {
			case UPDATE_FOLDER -> h.get().folder().ifPresent(updateFromFolder);
			case UPDATE_GITHUB -> h.get().github().ifPresent(update);
			case OPEN_SETTINGS -> openSettings.run();
			}
		});
		refresh();
	}

	/** Liest den Zustand neu und zeigt oder versteckt den Hinweis. */
	public void refresh() {
		Optional<UpdateHint.Hint> next = Optional.empty();
		try {
			next = UpdateHint.choose(state.get(), folder.get(), inst.get());
		} catch (RuntimeException e) {
			LOG.log(Level.WARNING, "Der Update-Hinweis ließ sich nicht auffrischen", e);
		}
		hint = next;
		boolean shown = next.isPresent();
		if (shown) {
			setText(next.get().text());
			tooltip.setText(next.get().tooltip());
		}
		setVisible(shown);
		setManaged(shown);
	}
}
