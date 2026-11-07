package de.eltviller_carneval_verein.karten.ui.shared;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import de.eltviller_carneval_verein.karten.update.Installation;
import de.eltviller_carneval_verein.karten.update.Plattform;
import de.eltviller_carneval_verein.karten.update.UpdateState;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Region;

/**
 * Der ruhige Hinweis in der Fußzeile auf ein Update (KARV-131): „Update 1.1.0 (Rev. 2) verfügbar“, solange eine neuere
 * Version bekannt ist (auch während einer Prüfung und nach einer gescheiterten), sonst unter Windows im Paket bei einer
 * systematisch scheiternden Prüfung „Update-Prüfung nicht möglich“; sonst unsichtbar und ohne Platz. Ein Klick startet
 * das Update, wenn die App sich selbst aktualisieren kann, sonst öffnet er die Einstellungen. Wie
 * {@link LockedUnitsIndicator} ohne Listener: Die App ruft {@link #refresh()} für den Hinweis der aktuellen Ansicht.
 * Nicht threadsicher: alle Aufrufe im JavaFX-Thread.
 */
public final class UpdateIndicator extends Hyperlink {

	private static final Logger LOG = Logger.getLogger(UpdateIndicator.class.getName());

	private final Supplier<UpdateState> state;
	private final Supplier<Optional<Installation>> inst;
	private final Tooltip tooltip = new Tooltip();
	private Optional<UpdateState.Available> clickUpdate = Optional.empty();

	/**
	 * @param state        der Zustand der Prüfung (die FX-Property der App)
	 * @param inst         die Installation, leer in der Entwicklung
	 * @param update       startet das Update auf die bekannte Version
	 * @param openSettings öffnet die Einstellungen
	 */
	public UpdateIndicator(Supplier<UpdateState> state, Supplier<Optional<Installation>> inst,
			Consumer<UpdateState.Available> update, Runnable openSettings) {
		this.state = state;
		this.inst = inst;
		getStyleClass().add("update-indicator");
		setMinWidth(Region.USE_PREF_SIZE);
		setTooltip(tooltip);
		setOnAction(event -> {
			setVisited(false);
			clickUpdate.ifPresentOrElse(update, openSettings);
		});
		refresh();
	}

	/** Liest den Zustand neu und zeigt oder versteckt den Hinweis. */
	public void refresh() {
		String text = null;
		String tip = null;
		clickUpdate = Optional.empty();
		try {
			UpdateState s = state.get();
			Optional<Installation> installation = inst.get();
			Optional<UpdateState.Available> known = s.knownUpdate();
			if (known.isPresent()) {
				text = UpdateTexts.indicatorText(known.get().neu().version());
				boolean inPlace = installation.map(i -> i.canUpdateInPlace(known.get().neu())).orElse(false);
				tip = inPlace ? UpdateTexts.INDICATOR_TOOLTIP_UPDATE : UpdateTexts.INDICATOR_TOOLTIP_DETAILS;
				clickUpdate = inPlace ? known : Optional.empty();
			} else if (s.checkImpossible()
					&& installation.map(i -> i.plattform() == Plattform.WINDOWS_PAKET).orElse(false)) {
				text = UpdateTexts.CHECK_IMPOSSIBLE;
				tip = UpdateTexts.CHECK_IMPOSSIBLE_TOOLTIP;
			}
		} catch (RuntimeException e) {
			LOG.log(Level.WARNING, "Der Update-Hinweis ließ sich nicht auffrischen", e);
		}
		boolean shown = text != null;
		if (shown) {
			setText(text);
			tooltip.setText(tip);
		}
		setVisible(shown);
		setManaged(shown);
	}
}
