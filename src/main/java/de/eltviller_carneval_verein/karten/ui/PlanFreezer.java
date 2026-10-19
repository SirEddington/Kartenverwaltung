package de.eltviller_carneval_verein.karten.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

import de.eltviller_carneval_verein.karten.model.BookingSync;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.repository.JsonEventRepository;
import de.eltviller_carneval_verein.karten.repository.JsonHallRepository;
import de.eltviller_carneval_verein.karten.tracking.ChangeTracker;

/**
 * Hält vor dem Löschen einer Bestuhlung die Buchungen der Vorstellungen fest, die sie verwenden. Nach dem Löschen
 * ändert der Abgleich an diesen Vorstellungen nichts mehr ({@link BookingSync#sync} friert sie ein), deshalb werden die
 * betroffenen Events vorher noch gegen die vorhandene Bestuhlung abgeglichen ({@link BookingSyncService#prepare}) und
 * ohne Prüfung gespeichert ({@link EventSaver#saveStructureChange(Event)}): Momentaufnahme und "Ohne Platz" stehen
 * dann in der Datei. Ein Event mit ungespeicherten Änderungen wird nur abgeglichen, nicht gespeichert, das Speichern
 * bleibt Sache seines Bearbeiters.
 *
 * <p>
 * Aufzurufen erst, nachdem der Nutzer das Löschen bestätigt hat, und bevor die Bestuhlung entfernt wird.
 */
public final class PlanFreezer {

	private static final Logger LOG = Logger.getLogger(PlanFreezer.class.getName());

	private PlanFreezer() {
	}

	/**
	 * Vor dem Löschen der Bestuhlung {@code plan} der Halle {@code hall} (Hallen-Editor): betrifft alle Events der App,
	 * deren Vorstellungen die Bestuhlung verwenden. Die Halle wird so genommen, wie sie im Editor steht (mit noch
	 * ungespeicherten Änderungen an der Bestuhlung), andere Hallen aus dem Repository.
	 *
	 * @return die gespeicherten Events
	 */
	public static List<Event> beforePlanDeletion(Hall hall, SeatingPlan plan) {
		JsonHallRepository halls = JsonHallRepository.getInstance();
		return freeze(JsonEventRepository.getInstance().loadEvents(),
				presentation -> plan.getId().equals(presentation.getSeatingPlanId()),
				hallId -> hall.getId().equals(hallId) ? hall : halls.findById(hallId), ChangeTracker.events(),
				EventSaver::saveStructureChange);
	}

	/**
	 * Gleicht jedes Event mit einer Vorstellung, für die {@code usesPlan} gilt, ab und speichert es, wenn es vorher
	 * keine ungespeicherten Änderungen hatte. Scheitert das Speichern eines Events (etwa ohne Namen), wird das
	 * protokolliert und mit den übrigen weitergemacht.
	 *
	 * @param usesPlan   ob die Vorstellung die zu löschende Bestuhlung verwendet
	 * @param hallLookup liefert die Halle zur Id, wie für {@link BookingSyncService#prepare(Event, Function, ChangeTracker)}
	 * @param save       speichert ein Event ohne Prüfung
	 * @return die gespeicherten Events
	 */
	static List<Event> freeze(List<Event> events, Predicate<Presentation> usesPlan, Function<String, Hall> hallLookup,
			ChangeTracker<Event> tracker, Consumer<Event> save) {
		List<Event> saved = new ArrayList<>();
		for (Event event : events) {
			if (event.getPresentations() == null || event.getPresentations().stream().noneMatch(usesPlan)) {
				continue;
			}
			tracker.check(event);
			boolean dirty = tracker.isDirty(event);
			BookingSyncService.prepare(event, hallLookup, tracker);
			if (dirty) {
				continue;
			}
			try {
				save.accept(event);
				saved.add(event);
			} catch (RuntimeException e) {
				LOG.log(Level.WARNING, "Event konnte vor dem Löschen der Bestuhlung nicht gespeichert werden: " + event.getName(), e);
			}
		}
		return saved;
	}
}
