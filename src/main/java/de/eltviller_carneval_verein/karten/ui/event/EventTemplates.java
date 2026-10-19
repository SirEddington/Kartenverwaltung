package de.eltviller_carneval_verein.karten.ui.event;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import de.eltviller_carneval_verein.karten.model.BookingSync;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.Presentation;

/**
 * Neue Events und Vorstellungen aus "Hinzufügen" und "Als Vorlage verwenden", gemeinsam für die Event-Übersicht und das
 * Event-Detail. Alles hier entsteht nur im Speicher: Wer es aufruft, meldet das Neue der Änderungserkennung (ein neues
 * Event über {@code ChangeTracker#trackNew}); gespeichert wird erst mit "Speichern".
 */
public final class EventTemplates {

	private static final String NEW_EVENT_NAME = "Neues Event";

	private EventTemplates() {
	}

	/**
	 * Kopiert die Vorstellung als Vorlage in {@code target}: Name, Beschreibung, Datum, Zeit, Halle, Bestuhlung und
	 * Standardpreis. Die Buchungen werden nicht kopiert, die Kopie bekommt je Sitz der Bestuhlung eine leere Buchung.
	 *
	 * @param hallLookup liefert die Halle zur Id oder {@code null}, wenn es sie nicht (mehr) gibt
	 */
	public static Presentation copyPresentation(Presentation source, Event target, Function<String, Hall> hallLookup) {
		Presentation copy = target.addPresentation(source.getName());
		copy.setDescription(source.getDescription());
		copy.setDate(source.getDate());
		copy.setTime(source.getTime());
		copy.setHallId(source.getHallId());
		copy.setSeatingPlanId(source.getSeatingPlanId());
		copy.setPrice(source.getPrice());
		Hall hall = copy.getHallId() == null ? null : hallLookup.apply(copy.getHallId());
		BookingSync.sync(copy, EventEditController.modelPlan(copy, hall));
		return copy;
	}

	/**
	 * Kopiert das Event samt Vorstellungen. Es bekommt einen eigenen Namen ("&lt;Name&gt; (Kopie)"), sonst würde es nie
	 * gespeichert (EVT-002). Das Ergebnis ist noch in keiner Liste.
	 *
	 * @param existingNames die Namen der vorhandenen Events, damit der Name der Kopie frei ist
	 */
	public static Event copyEvent(Event source, Collection<String> existingNames, Function<String, Hall> hallLookup) {
		Event copy = new Event();
		copy.setName(Event.createCopyName(source.getName(), existingNames));
		copy.setDescription(source.getDescription());
		List<Presentation> presentations = source.getPresentations() == null ? List.of() : List.copyOf(source.getPresentations());
		for (Presentation presentation : presentations) {
			copyPresentation(presentation, copy, hallLookup);
		}
		return copy;
	}

	/** Ein leeres Event mit einem freien Namen ("Neues Event", "Neues Event 2" ...). */
	public static Event newEvent(Collection<String> existingNames) {
		Set<String> taken = existingNames.stream().filter(Objects::nonNull).map(name -> name.trim().toLowerCase(Locale.GERMAN)).collect(Collectors.toSet());
		String candidate = NEW_EVENT_NAME;
		for (int i = 2; taken.contains(candidate.toLowerCase(Locale.GERMAN)); i++) {
			candidate = NEW_EVENT_NAME + " " + i;
		}
		Event event = new Event();
		event.setName(candidate);
		return event;
	}
}
