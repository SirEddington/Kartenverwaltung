package de.eltviller_carneval_verein.karten.ui.event;

import java.util.Objects;
import java.util.function.Consumer;

import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.repository.LockedUnitException;
import de.eltviller_carneval_verein.karten.repository.StoreEventRepository;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.ui.StatusMessage;
import de.eltviller_carneval_verein.karten.validation.EntityLabels;
import de.eltviller_carneval_verein.karten.validation.Severity;

/**
 * Lehnt Aktionen an einer Vorstellung ab, unter der gesperrte Einträge liegen (KARV-135): den Wechsel von Halle oder
 * Bestuhlung und das Löschen. Geprüft wird am Stand des Versionsspeichers
 * ({@link StoreEventRepository#requireNothingLockedUnder}), nicht an „Gesperrte Einträge“, und bevor sich das Modell
 * ändert: Sonst stünde im Modell eine Änderung, die sich nicht speichern lässt. Die Meldung geht wie die übrigen
 * Ablehnungen gesperrter Einheiten in die Fußzeile ({@link StatusMessage}, ohne Fußzeile als Dialog).
 */
final class LockedEntryGuard {

	private LockedEntryGuard() {
	}

	/** Wie {@link #rejects(Presentation, StoreEventRepository, Consumer)} mit dem Repository und der Fußzeile der App. */
	static boolean rejects(Presentation presentation) {
		return rejects(presentation, StoreEventRepository.getInstance(), LockedEntryGuard::showError);
	}

	/**
	 * Ob unter der Vorstellung gesperrte Einträge liegen; dann ist die Meldung (SYS-009 bzw. SYS-008) schon gezeigt. Eine
	 * Vorstellung ohne Event war nie gespeichert und hat nichts Gesperrtes.
	 *
	 * @param show zeigt die Meldung
	 */
	static boolean rejects(Presentation presentation, StoreEventRepository repository, Consumer<String> show) {
		if (presentation.getParent() == null) {
			return false;
		}
		try {
			repository.requireNothingLockedUnder(UnitKey.presentation(presentation.getParent().getId(), presentation.getId()),
					EntityLabels.nameOrPlaceholder(presentation.getName()));
			return false;
		} catch (LockedUnitException e) {
			show.accept(e.getMessage());
			return true;
		}
	}

	/** Wie {@link #rejectsHallChange(Presentation, String, StoreEventRepository, Consumer)} mit dem Repository und der Fußzeile der App. */
	static boolean rejectsHallChange(Presentation presentation, String newHallId) {
		return rejectsHallChange(presentation, newHallId, StoreEventRepository.getInstance(), LockedEntryGuard::showError);
	}

	/**
	 * Ob der Wechsel der Vorstellung auf die Halle {@code newHallId} abgelehnt wird ({@link #rejects}); dieselbe Halle ist
	 * kein Wechsel. Geprüft wird unabhängig davon, ob das Modell Buchungen mit Daten hat: Eine gesperrte Buchung fehlt dort
	 * (auf ihrem Sitz liegt nur ein leerer Platzhalter), der Wechsel liefe sonst ohne Rückfrage durch.
	 */
	static boolean rejectsHallChange(Presentation presentation, String newHallId, StoreEventRepository repository,
			Consumer<String> show) {
		return !Objects.equals(newHallId, presentation.getHallId()) && rejects(presentation, repository, show);
	}

	/** Wie {@link #deletePresentation(Presentation, StoreEventRepository, Consumer)} mit dem Repository und der Fußzeile der App. */
	static boolean deletePresentation(Presentation presentation) {
		return deletePresentation(presentation, StoreEventRepository.getInstance(), LockedEntryGuard::showError);
	}

	/**
	 * Entfernt die Vorstellung aus ihrem Event (nur im Modell, gespeichert wird beim Aufrufer), außer es liegen gesperrte
	 * Einträge darunter ({@link #rejects}).
	 *
	 * @return ob sie entfernt wurde
	 */
	static boolean deletePresentation(Presentation presentation, StoreEventRepository repository, Consumer<String> show) {
		if (rejects(presentation, repository, show)) {
			return false;
		}
		presentation.getParent().deletePresentation(presentation);
		return true;
	}

	/** Zeigt die Ablehnung in der Fußzeile (ohne Fußzeile als Dialog). */
	static void showError(String message) {
		StatusMessage.getInstance().show(Severity.ERROR, message);
	}
}
