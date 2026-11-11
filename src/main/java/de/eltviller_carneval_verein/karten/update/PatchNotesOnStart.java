package de.eltviller_carneval_verein.karten.update;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import de.eltviller_carneval_verein.karten.update.PatchNotes.Release;

/**
 * Was beim Start an Patchnotes erscheint (KARV-125): einmal nach einem Update alle Versionen seit der zuletzt
 * gesehenen, nichts bei einer Neuinstallation, in der Entwicklung oder bei gleicher Version (auch einem Testpaket mit
 * anderer Revision). Ohne JavaFX; ein Fehler darf den Start nie stören.
 */
public final class PatchNotesOnStart {

	private static final Logger LOG = Logger.getLogger(PatchNotesOnStart.class.getName());

	/**
	 * @param show     die zu zeigenden Versionen, die neueste zuerst; leer = kein Fenster
	 * @param remember die Versionsnummer, die als gesehen gespeichert wird; leer = nichts speichern
	 */
	public record Decision(List<Release> show, Optional<String> remember) {
		public Decision {
			show = List.copyOf(show);
		}
	}

	private PatchNotesOnStart() {
	}

	/**
	 * @param current         die laufende Version, leer in der Entwicklung
	 * @param seen            {@code patchNotesSeen} aus den Einstellungen; {@code null} oder leer = noch keine
	 * @param settingsExisted ob {@code settings.json} beim Start schon da war
	 * @param notes           die Patchnotes, leer, wenn die Datei sich nicht lesen ließ
	 */
	public static Decision decide(Optional<AppVersion> current, String seen, boolean settingsExisted,
			Optional<PatchNotes> notes) {
		if (current.isEmpty()) {
			return new Decision(List.of(), Optional.empty());
		}
		AppVersion now = PatchNotes.base(current.get());
		Optional<String> remember = Optional.of(now.version());
		boolean seenBlank = seen == null || seen.isEmpty();
		Optional<AppVersion> seenVersion = seenBlank ? Optional.empty() : AppVersion.parse(seen, null);
		if (seenVersion.isPresent()) {
			int order = PatchNotes.base(seenVersion.get()).compareTo(now);
			if (order == 0) {
				return new Decision(List.of(), Optional.empty());
			}
			if (order > 0) {
				return new Decision(List.of(), remember);
			}
		} else if (seenBlank && !settingsExisted) {
			return new Decision(List.of(), remember);
		}
		List<Release> show = notes.map(n -> n.after(seenVersion, now)).orElse(List.of());
		return new Decision(show, remember);
	}

	/**
	 * Der Ablauf beim Start: lesen, entscheiden, zeigen, merken. Jeder Schritt ist einzeln abgesichert; scheitert das
	 * Zeigen, wird trotzdem gemerkt.
	 */
	public static void run(Optional<AppVersion> current, String seen, boolean settingsExisted, Supplier<PatchNotes> read,
			Consumer<List<Release>> show, Consumer<String> remember) {
		Optional<PatchNotes> notes;
		try {
			notes = Optional.of(read.get());
		} catch (RuntimeException e) {
			LOG.log(Level.WARNING, "Patchnotes ließen sich nicht lesen", e);
			notes = Optional.empty();
		}
		Decision decision = decide(current, seen, settingsExisted, notes);
		if (!decision.show().isEmpty()) {
			try {
				show.accept(decision.show());
			} catch (RuntimeException e) {
				LOG.log(Level.WARNING, "Patchnotes ließen sich nicht zeigen", e);
			}
		}
		decision.remember().ifPresent(version -> {
			try {
				remember.accept(version);
			} catch (RuntimeException e) {
				LOG.log(Level.WARNING, "Gesehene Patchnotes-Version ließ sich nicht speichern", e);
			}
		});
	}
}
