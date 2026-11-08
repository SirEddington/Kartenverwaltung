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
import de.eltviller_carneval_verein.karten.repository.StoreEventRepository;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.UnitType;
import de.eltviller_carneval_verein.karten.ui.StatusMessage;
import de.eltviller_carneval_verein.karten.validation.Severity;

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
	 * Standardpreis. Die Buchungen werden nicht kopiert, die Kopie bekommt je Sitz der Bestuhlung eine leere Buchung. Kopiert
	 * wird der Stand des Modells; was gesperrt ist, meldet der Aufrufer mit {@link #reportLockedLeftOut(Presentation)}.
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
		copy.getUnknown().replaceWith(source.getUnknown());
		Hall hall = copy.getHallId() == null ? null : hallLookup.apply(copy.getHallId());
		// die Kopie ist eine neue Vorstellung ohne Einheiten im Versionsspeicher, also ohne gesperrte Sitze
		BookingSync.sync(copy, EventEditController.modelPlan(copy, hall), Set.of());
		return copy;
	}

	/**
	 * Kopiert das Event samt Vorstellungen. Es bekommt einen eigenen Namen ("&lt;Name&gt; (Kopie)"), sonst würde es nie
	 * gespeichert (EVT-002). Das Ergebnis ist noch in keiner Liste. Gesperrte Vorstellungen fehlen im Modell (oder stehen
	 * dort im älteren Stand) und damit in der Kopie; das meldet der Aufrufer mit {@link #reportLockedLeftOut(Event)}.
	 *
	 * @param existingNames die Namen der vorhandenen Events, damit der Name der Kopie frei ist
	 */
	public static Event copyEvent(Event source, Collection<String> existingNames, Function<String, Hall> hallLookup) {
		Event copy = new Event();
		copy.setName(Event.createCopyName(source.getName(), existingNames));
		copy.setDescription(source.getDescription());
		copy.getUnknown().replaceWith(source.getUnknown());
		List<Presentation> presentations = source.getPresentations() == null ? List.of() : List.copyOf(source.getPresentations());
		for (Presentation presentation : presentations) {
			copyPresentation(presentation, copy, hallLookup);
		}
		return copy;
	}

	/**
	 * Wie viele gesperrte Einträge die Kopie des Events auslässt (KARV-135): die gesperrten Vorstellungen darunter
	 * ({@link StoreEventRepository#lockedUnder}); sie fehlen im Modell oder stehen dort nur im älteren, lesbaren Stand.
	 * Gesperrte Buchungen zählen nicht, Buchungen werden nie kopiert.
	 */
	static int lockedLeftOut(Event source, StoreEventRepository repository) {
		int count = 0;
		for (UnitKey unit : repository.lockedUnder(UnitKey.event(source.getId())).keySet()) {
			if (unit.type() == UnitType.PRESENTATION) {
				count++;
			}
		}
		return count;
	}

	/** Wie {@link #lockedLeftOut(Event, StoreEventRepository)} für die Kopie einer Vorstellung: 1, wenn sie gesperrt ist. */
	static int lockedLeftOut(Presentation source, StoreEventRepository repository) {
		Event event = source.getParent();
		if (event == null) {
			return 0;
		}
		return repository.lockedUnder(UnitKey.event(event.getId()))
				.containsKey(UnitKey.presentation(event.getId(), source.getId())) ? 1 : 0;
	}

	/** Der Hinweis nach dem Kopieren; ohne ausgelassene Einträge {@code null}. */
	static String lockedNote(int leftOut) {
		return leftOut > 0 ? "Gesperrte Einträge werden nicht mitkopiert (" + leftOut + ")." : null;
	}

	/** Meldet in der Fußzeile, wie viele gesperrte Einträge die Kopie des Events ausgelassen hat (nichts, wenn keine). */
	public static void reportLockedLeftOut(Event source) {
		report(lockedNote(lockedLeftOut(source, StoreEventRepository.getInstance())));
	}

	/** Wie {@link #reportLockedLeftOut(Event)} für die Kopie einer Vorstellung. */
	public static void reportLockedLeftOut(Presentation source) {
		report(lockedNote(lockedLeftOut(source, StoreEventRepository.getInstance())));
	}

	private static void report(String note) {
		if (note != null) {
			StatusMessage.getInstance().show(Severity.WARNING, note);
		}
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
