package de.eltviller_carneval_verein.karten.ui.shared;

import java.util.Optional;

import de.eltviller_carneval_verein.karten.update.FolderOffer;
import de.eltviller_carneval_verein.karten.update.FolderState;
import de.eltviller_carneval_verein.karten.update.Installation;
import de.eltviller_carneval_verein.karten.update.Plattform;
import de.eltviller_carneval_verein.karten.update.UpdateState;

/**
 * Welcher Update-Hinweis in der Fußzeile steht (KARV-131/132), ohne JavaFX. Rangfolge: Update im gemeinsamen Ordner →
 * Update von GitHub → Neuinstallation nötig → neuere Kasse → „Update-Prüfung nicht möglich“.
 */
public final class UpdateHint {

	/** Was ein Klick tut. */
	public enum Action {
		UPDATE_FOLDER, UPDATE_GITHUB, OPEN_SETTINGS
	}

	/** Ein Hinweis mit Text, Tooltip und Ziel des Klicks. */
	public record Hint(String text, String tooltip, Action action, Optional<UpdateState.Available> github,
			Optional<FolderOffer> folder) {
	}

	private UpdateHint() {
	}

	public static Optional<Hint> choose(UpdateState state, FolderState folder, Optional<Installation> inst) {
		if (folder.offer().isPresent()) {
			FolderOffer o = folder.offer().get();
			return Optional.of(new Hint(UpdateTexts.folderIndicatorText(o.version()), UpdateTexts.FOLDER_TOOLTIP,
					Action.UPDATE_FOLDER, Optional.empty(), Optional.of(o)));
		}
		Optional<UpdateState.Available> known = state.knownUpdate();
		if (known.isPresent()) {
			boolean inPlace = inst.map(i -> i.canUpdateInPlace(known.get().neu())).orElse(false);
			return Optional.of(new Hint(UpdateTexts.indicatorText(known.get().neu().version()),
					inPlace ? UpdateTexts.INDICATOR_TOOLTIP_UPDATE : UpdateTexts.INDICATOR_TOOLTIP_DETAILS,
					inPlace ? Action.UPDATE_GITHUB : Action.OPEN_SETTINGS, inPlace ? known : Optional.empty(),
					Optional.empty()));
		}
		if (folder.reinstallNeeded().isPresent()) {
			return Optional.of(new Hint(UpdateTexts.indicatorText(folder.reinstallNeeded().get()),
					UpdateTexts.INDICATOR_TOOLTIP_DETAILS, Action.OPEN_SETTINGS, Optional.empty(), Optional.empty()));
		}
		if (folder.newerStation().isPresent()) {
			return Optional.of(new Hint(
					UpdateTexts.newerStationText(folder.newerStation().get().name(), folder.newerStation().get().version()),
					UpdateTexts.NEWER_STATION_TOOLTIP, Action.OPEN_SETTINGS, Optional.empty(), Optional.empty()));
		}
		if (state.checkImpossible() && inst.map(i -> i.plattform() == Plattform.WINDOWS_PAKET).orElse(false)) {
			return Optional.of(new Hint(UpdateTexts.CHECK_IMPOSSIBLE, UpdateTexts.CHECK_IMPOSSIBLE_TOOLTIP,
					Action.OPEN_SETTINGS, Optional.empty(), Optional.empty()));
		}
		return Optional.empty();
	}
}
