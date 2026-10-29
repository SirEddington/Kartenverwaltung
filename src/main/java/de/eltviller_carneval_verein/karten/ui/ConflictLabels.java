package de.eltviller_carneval_verein.karten.ui;

import java.util.function.Function;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.repository.StoreEventRepository;
import de.eltviller_carneval_verein.karten.repository.StoreHallRepository;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.UnitType;
import de.eltviller_carneval_verein.karten.validation.EntityLabels;

/**
 * Die Anzeigenamen der Einheiten für den Sammel-Dialog ({@link ConflictList}), ohne JavaFX: ein Event heißt wie das Event,
 * eine Vorstellung "Event › Vorstellung", eine Buchung "Event › Vorstellung › Platz" bzw. bei einer Buchung ohne Platz
 * (Einheit {@code _<ID>}) "… › Kunde", eine Halle wie die Halle. Texte vorläufig (Überarbeitung in KARV-87).
 */
public final class ConflictLabels {

	private static final String SEPARATOR = " › ";
	private static final String UNKNOWN_PLACE = "(Platz unbekannt)";
	private static final String UNKNOWN_CUSTOMER = "(Kunde unbekannt)";

	private ConflictLabels() {
	}

	/** Die Namen nach den Repositories der App. */
	public static Function<UnitKey, String> of(StoreEventRepository events, StoreHallRepository halls) {
		return of(events::findEvent, events::modelObject, halls::findById);
	}

	/**
	 * @param findEvent   das Event zur Kennung oder {@code null}
	 * @param modelObject das Modellobjekt der Einheit oder {@code null}
	 * @param findHall    die Halle zur Kennung oder {@code null}
	 * @return der Name der Einheit; {@code null}, wenn es sie im Modell nicht (mehr) gibt (die Liste zeigt dann den Pfad)
	 */
	static Function<UnitKey, String> of(Function<String, Event> findEvent, Function<UnitKey, Object> modelObject,
			Function<String, Hall> findHall) {
		return unit -> {
			if (unit.type() == UnitType.HALL) {
				Hall hall = findHall.apply(unit.hallId());
				return hall == null ? null : EntityLabels.nameOrPlaceholder(hall.getName());
			}
			Event event = findEvent.apply(unit.eventId());
			if (event == null) {
				return null;
			}
			String eventName = EntityLabels.nameOrPlaceholder(event.getName());
			if (unit.type() == UnitType.EVENT) {
				return eventName;
			}
			Presentation presentation = event.findPresentation(unit.presentationId());
			if (presentation == null) {
				return null;
			}
			String path = eventName + SEPARATOR + EntityLabels.nameOrPlaceholder(presentation.getName());
			if (unit.type() == UnitType.PRESENTATION) {
				return path;
			}
			return path + SEPARATOR + bookingPart(modelObject.apply(unit), unit.isUnassigned());
		};
	}

	/** Der Platz der Buchung; bei einer Buchung ohne Platz der Kunde. */
	private static String bookingPart(Object model, boolean withoutSeat) {
		if (!(model instanceof Booking booking)) {
			return withoutSeat ? UNKNOWN_CUSTOMER : UNKNOWN_PLACE;
		}
		if (!withoutSeat) {
			return EntityLabels.place(booking);
		}
		String name = ((booking.getFirstName() == null ? "" : booking.getFirstName().trim()) + " "
				+ (booking.getLastName() == null ? "" : booking.getLastName().trim())).trim();
		return name.isEmpty() ? UNKNOWN_CUSTOMER : name;
	}
}
