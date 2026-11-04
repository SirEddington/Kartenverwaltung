package de.eltviller_carneval_verein.karten.ui;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.function.Function;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.repository.StoreEventRepository;
import de.eltviller_carneval_verein.karten.repository.StoreHallRepository;
import de.eltviller_carneval_verein.karten.store.SyncService;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.UnitType;
import de.eltviller_carneval_verein.karten.store.Version;
import de.eltviller_carneval_verein.karten.validation.EntityLabels;

/**
 * Die Anzeigenamen der Einheiten für den Sammel-Dialog ({@link ConflictList}), ohne JavaFX: ein Event heißt wie das Event,
 * eine Vorstellung "Event › Vorstellung", eine Buchung "Event › Vorstellung › Platz" bzw. bei einer Buchung ohne Platz
 * (Einheit {@code _<ID>}) "… › Kunde", eine Halle wie die Halle. Fehlt die Buchung, heißt sie nach ihrem Sitz in der
 * Bestuhlung (KARV-111). Dieselben Bezeichnungen setzt die Oberfläche des gemeinsamen Ordners in ihre Meldungen ein.
 */
public final class ConflictLabels {

	private static final String SEPARATOR = " › ";
	private static final String UNKNOWN_PLACE = "(Platz unbekannt)";
	private static final String UNKNOWN_CUSTOMER = "(Kunde unbekannt)";

	private ConflictLabels() {
	}

	/**
	 * Die Namen nach den Repositories der App; ein gelöschtes Event bzw. eine gelöschte Vorstellung heißt wie in ihrer
	 * letzten Version vor dem Löschen ({@link #formerName}).
	 */
	public static Function<UnitKey, String> of(StoreEventRepository events, StoreHallRepository halls, SyncService sync) {
		return of(events::findEvent, events::modelObject, halls::findById, formerName(unit -> {
			try {
				return sync.history(unit);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}));
	}

	/** Wie {@link #of(Function, Function, Function, Function)}, ohne Namen gelöschter Einträge. */
	static Function<UnitKey, String> of(Function<String, Event> findEvent, Function<UnitKey, Object> modelObject,
			Function<String, Hall> findHall) {
		return of(findEvent, modelObject, findHall, unit -> null);
	}

	/**
	 * @param findEvent   das Event zur Kennung oder {@code null}
	 * @param modelObject das Modellobjekt der Einheit oder {@code null}
	 * @param findHall    die Halle zur Kennung oder {@code null}
	 * @param formerName  der Name eines Events bzw. einer Vorstellung, die es im Modell nicht mehr gibt, oder {@code null}
	 * @return der Name der Einheit; {@code null}, wenn sie sich nicht benennen lässt (die Liste zeigt dann den Pfad)
	 */
	static Function<UnitKey, String> of(Function<String, Event> findEvent, Function<UnitKey, Object> modelObject,
			Function<String, Hall> findHall, Function<UnitKey, String> formerName) {
		return unit -> {
			if (unit.type() == UnitType.HALL) {
				Hall hall = findHall.apply(unit.hallId());
				return hall == null ? null : EntityLabels.nameOrPlaceholder(hall.getName());
			}
			Event event = findEvent.apply(unit.eventId());
			String eventName = event != null ? EntityLabels.nameOrPlaceholder(event.getName())
					: formerName.apply(UnitKey.event(unit.eventId()));
			if (eventName == null || unit.type() == UnitType.EVENT) {
				return eventName;
			}
			Presentation presentation = event == null ? null : event.findPresentation(unit.presentationId());
			String presentationName = presentation != null ? EntityLabels.nameOrPlaceholder(presentation.getName())
					: formerName.apply(UnitKey.presentation(unit.eventId(), unit.presentationId()));
			if (presentationName == null) {
				return null;
			}
			String path = eventName + SEPARATOR + presentationName;
			if (unit.type() == UnitType.PRESENTATION) {
				return path;
			}
			Object model = modelObject.apply(unit);
			if (!(model instanceof Booking) && !unit.isUnassigned()) {
				String place = placeInPlan(presentation, findHall, unit.bookingDir());
				if (place != null) {
					return path + SEPARATOR + place;
				}
			}
			return path + SEPARATOR + bookingPart(model, unit.isUnassigned());
		};
	}

	/**
	 * Der Platz einer Buchung, die im Modell fehlt (KARV-111): ihr Sitz in der Bestuhlung der Vorstellung, wie bei einer
	 * vorhandenen Buchung. {@code null}, wenn Vorstellung, Halle, Bestuhlung oder Sitz fehlen oder die Vorstellung
	 * eingefroren ist (ihre Bestuhlung gibt es nicht mehr).
	 */
	private static String placeInPlan(Presentation presentation, Function<String, Hall> findHall, String seatId) {
		if (presentation == null || presentation.isPlanMissing() || presentation.getHallId() == null) {
			return null;
		}
		Hall hall = findHall.apply(presentation.getHallId());
		SeatingPlan plan = hall == null ? null : hall.findSeatingPlan(presentation.getSeatingPlanId());
		Seat seat = plan == null ? null : plan.findSeat(seatId);
		return seat == null ? null : EntityLabels.placeOf(seat);
	}

	/**
	 * Der Name aus der neuesten nicht gelöschten Version der Einheit, für Einträge, die es im Modell nicht mehr gibt.
	 *
	 * @param history die Versionen der Einheit nach ID ({@link SyncService#history}); darf eine
	 *                {@link UncheckedIOException} werfen
	 * @return der Name; {@code null}, wenn keine Version einen hat oder sich die Versionen nicht lesen lassen
	 */
	static Function<UnitKey, String> formerName(Function<UnitKey, List<Version>> history) {
		return unit -> {
			List<Version> versions;
			try {
				versions = history.apply(unit);
			} catch (UncheckedIOException e) {
				return null;
			}
			for (int i = versions.size() - 1; i >= 0; i--) {
				Version version = versions.get(i);
				if (!version.deleted() && version.payload() != null) {
					String name = version.payload().path("name").asText("");
					if (!name.isBlank()) {
						return name;
					}
				}
			}
			return null;
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
		String name = EntityLabels.customer(booking);
		return name.isEmpty() ? UNKNOWN_CUSTOMER : name;
	}
}
