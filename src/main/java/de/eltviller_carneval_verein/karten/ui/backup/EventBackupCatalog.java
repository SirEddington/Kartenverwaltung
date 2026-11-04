package de.eltviller_carneval_verein.karten.ui.backup;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.repository.StoreEventRepository;
import de.eltviller_carneval_verein.karten.repository.StoreHallRepository;

/**
 * Wiederherstellen für Events. Vorstellungen, deren Halle es nicht mehr gibt, verlieren die Zuordnung
 * ({@code hallId = null}); die normale Prüfung meldet dann "keine Halle ausgewählt" (PRS-003), und der Nutzer
 * weist eine neue zu.
 */
public final class EventBackupCatalog extends AbstractBackupCatalog<Event> {

	private final Predicate<String> hallExists;

	/**
	 * @param events das Event-Repository, aus dem wiederhergestellt wird
	 * @param halls  das Hallen-Repository, an dem sich prüft, ob es die Halle einer Vorstellung noch gibt
	 */
	public EventBackupCatalog(StoreEventRepository events, StoreHallRepository halls) {
		super("Event", () -> events.knownEvents().stream().map(AbstractBackupCatalog::entry).toList(), unchecked(events::savePoints), events::prepareRestoreEvent,
				events::commitRestore, Event::getName);
		this.hallExists = hallId -> halls.findById(hallId) != null;
	}

	/** Der Katalog der App: die echten Repositories. */
	public static EventBackupCatalog create() {
		return new EventBackupCatalog(StoreEventRepository.getInstance(), StoreHallRepository.getInstance());
	}

	@Override
	List<String> adjustForRestore(Event event) {
		List<String> notes = new ArrayList<>();
		if (event.getPresentations() == null) {
			return notes;
		}
		for (Presentation presentation : event.getPresentations()) {
			String hallId = presentation.getHallId();
			if (hallId != null && !hallId.isBlank() && !hallExists.test(hallId)) {
				presentation.setHallId(null);
				notes.add("Die Halle von \"" + presentation.getName() + "\" existiert nicht mehr und wurde entfernt, bitte neu zuordnen.");
			}
		}
		return notes;
	}

	@Override
	public String restoreWarning() {
		return "Spätere Änderungen und Verkäufe, auch die anderer Rechner, werden mit zurückgesetzt.";
	}
}
