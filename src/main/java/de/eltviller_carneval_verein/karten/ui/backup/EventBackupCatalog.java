package de.eltviller_carneval_verein.karten.ui.backup;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.repository.BackupStore;
import de.eltviller_carneval_verein.karten.repository.JsonEventRepository;
import de.eltviller_carneval_verein.karten.repository.JsonHallRepository;
import de.eltviller_carneval_verein.karten.ui.EventSaver;

/**
 * Wiederherstellen für Events. Vorstellungen, deren Halle es nicht mehr gibt, verlieren die Zuordnung
 * ({@code hallId = null}); die normale Prüfung meldet dann "keine Halle ausgewählt" (PRS-003), und der Nutzer
 * weist eine neue zu.
 */
public final class EventBackupCatalog extends AbstractBackupCatalog<Event> {

	private final Predicate<String> hallExists;

	/**
	 * @param hallExists sagt, ob es die Halle mit dieser ID noch gibt
	 */
	public EventBackupCatalog(BackupStore store, Supplier<List<Event>> currentEvents, Predicate<String> hallExists, Consumer<Event> save, Consumer<Event> replaceInCache) {
		super(store, Event.class, "Event", currentEvents, save, replaceInCache);
		this.hallExists = hallExists;
	}

	/** Der Katalog der App: echte Repositories, Speichern über den {@link EventSaver}. */
	public static EventBackupCatalog create() {
		JsonEventRepository events = JsonEventRepository.getInstance();
		JsonHallRepository halls = JsonHallRepository.getInstance();
		return new EventBackupCatalog(events.backups(), events::loadEvents, hallId -> halls.findById(hallId) != null, EventSaver::saveRestoredState, events::replaceCachedEvent);
	}

	@Override
	String idOf(Event event) {
		return event.getId();
	}

	@Override
	String nameOf(Event event) {
		return event.getName();
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
}
