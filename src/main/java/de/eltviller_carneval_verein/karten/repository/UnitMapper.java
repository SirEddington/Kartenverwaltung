package de.eltviller_carneval_verein.karten.repository;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.store.StoreMessages;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.UnitSnapshot;
import de.eltviller_carneval_verein.karten.store.UnitState.Status;
import de.eltviller_carneval_verein.karten.store.UnitType;

/**
 * Zerlegt Events und Hallen in die Einheiten des Versionsspeichers und setzt sie wieder zusammen.
 * <p>
 * Einheiten sind der Event-Kopf ({@link Event} ohne {@code presentations}), die Vorstellung ({@link Presentation} ohne
 * {@code bookings}), die Buchung und die ganze {@link Hall}. Leere Buchungen ({@link Booking#hasData()} falsch)
 * werden nie geschrieben; {@code BookingSync} legt sie beim Laden aus der Bestuhlung wieder an. Gelöscht wird nur am
 * Kopf: Kinder gelöschter Einheiten bleiben liegen und sind unsichtbar.
 */
final class UnitMapper {

	private static final Logger LOG = Logger.getLogger(UnitMapper.class.getName());

	/** Derselbe Mapper wie beim Speichern und im Versionsspeicher. */
	private static final ObjectMapper MAPPER = JsonMapperFactory.create();

	private static final String PRESENTATIONS = "presentations";
	private static final String BOOKINGS = "bookings";

	/** Vorstellungen: Datum, Uhrzeit (je {@code null} zuletzt), Name ohne Groß-/Kleinschreibung ({@code null} zuletzt), ID. */
	private static final Comparator<Presentation> PRESENTATION_ORDER = Comparator
			.comparing(Presentation::getDate, Comparator.nullsLast(Comparator.<LocalDate>naturalOrder()))
			.thenComparing(Presentation::getTime, Comparator.nullsLast(Comparator.<LocalTime>naturalOrder()))
			.thenComparing(Presentation::getName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
			.thenComparing(Presentation::getId, Comparator.nullsLast(Comparator.<String>naturalOrder()));

	/** Buchungen: {@link Booking#PLACE_ORDER}; bei gleichem Platz (Buchungen ohne Platz) entscheidet die ID. */
	private static final Comparator<Booking> BOOKING_ORDER = Booking.PLACE_ORDER
			.thenComparing(Booking::getId, Comparator.nullsLast(Comparator.<String>naturalOrder()));

	private static final Comparator<Event> EVENT_ORDER = Comparator
			.comparing(Event::getName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
			.thenComparing(Event::getId, Comparator.nullsLast(Comparator.<String>naturalOrder()));

	private static final Comparator<Hall> HALL_ORDER = Comparator
			.comparing(Hall::getName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
			.thenComparing(Hall::getId, Comparator.nullsLast(Comparator.<String>naturalOrder()));

	/** Die Daten einer Einheit. */
	record UnitPayload(UnitKey unit, ObjectNode payload) {
	}

	/** Das Ergebnis des Zusammensetzens: die lesbaren Objekte und die Warnungen für den Nutzer. */
	record Assembly<T>(List<T> items, List<String> warnings) {
	}

	private UnitMapper() {
	}

	/**
	 * Die JSON-Darstellung eines Objekts genau so, wie sie beim Lesen eines JSON-Texts mit {@code readTree} entsteht
	 * (Zahlenarten, Daten als Text). Nur so ist ein geladener, unveränderter Stand gleich dem gespeicherten.
	 */
	static ObjectNode normalize(Object value) {
		try {
			return (ObjectNode) MAPPER.readTree(MAPPER.writeValueAsBytes(value));
		} catch (IOException e) {
			throw new IllegalStateException("Das Objekt lässt sich nicht als JSON darstellen: " + e.getMessage(), e);
		}
	}

	/**
	 * Zerlegt das Event in Kopf, Vorstellungen und Buchungen mit Daten, in der Reihenfolge: Kopf, dann je Vorstellung
	 * sie selbst und ihre Buchungen. Das Event bleibt unverändert.
	 *
	 * @throws IllegalStateException wenn zwei Buchungen denselben Schlüssel haben
	 */
	static List<UnitPayload> decompose(Event event) {
		// Ein Durchlauf: Kopf, Vorstellungen und Buchungen sind Teilbäume desselben Baums
		ObjectNode head = normalize(event);
		JsonNode presentationNodes = head.remove(PRESENTATIONS);
		List<Presentation> presentations = event.getPresentations() != null ? event.getPresentations() : List.of();

		List<UnitPayload> parts = new ArrayList<>();
		parts.add(new UnitPayload(UnitKey.event(event.getId()), head));
		for (int i = 0; i < presentations.size(); i++) {
			Presentation presentation = presentations.get(i);
			ObjectNode presentationNode = (ObjectNode) presentationNodes.get(i);
			JsonNode bookingNodes = presentationNode.remove(BOOKINGS);
			parts.add(new UnitPayload(UnitKey.presentation(event.getId(), presentation.getId()), presentationNode));

			List<Booking> bookings = presentation.getBookings() != null ? presentation.getBookings() : List.of();
			Map<UnitKey, Booking> taken = new HashMap<>();
			for (int j = 0; j < bookings.size(); j++) {
				Booking booking = bookings.get(j);
				if (!booking.hasData()) {
					continue;
				}
				UnitKey key = bookingKey(event, presentation, booking);
				if (taken.put(key, booking) != null) {
					throw new IllegalStateException("Zwei Buchungen belegen denselben Platz (Vorstellung \""
							+ presentation.getName() + "\", " + booking.getTableLabel() + ", Sitz "
							+ booking.getSeatNumber() + "). Es wurde nichts gespeichert.");
				}
				parts.add(new UnitPayload(key, (ObjectNode) bookingNodes.get(j)));
			}
		}
		return parts;
	}

	/** Zerlegt die Halle: Sie ist eine einzige Einheit. */
	static UnitPayload decompose(Hall hall) {
		return new UnitPayload(UnitKey.hall(hall.getId()), normalize(hall));
	}

	private static UnitKey bookingKey(Event event, Presentation presentation, Booking booking) {
		if (booking.getSeatId() != null && !booking.isUnassigned()) {
			return UnitKey.booking(event.getId(), presentation.getId(), booking.getSeatId());
		}
		return UnitKey.unassignedBooking(event.getId(), presentation.getId(), booking.getId());
	}

	/**
	 * Setzt die Events aus den Ständen der Einheiten zusammen. Die Payloads der Stände bleiben unverändert (der Index
	 * teilt sie sich). Events nach Name sortiert, ihre Vorstellungen nach Datum, Uhrzeit, Name und ID, die
	 * Buchungen in der Reihenfolge der Plätze.
	 */
	static Assembly<Event> assembleEvents(Collection<UnitSnapshot> snapshots) {
		List<String> warnings = new ArrayList<>();
		int orphans = 0;

		Map<String, Event> events = new LinkedHashMap<>();
		for (UnitSnapshot snapshot : snapshots) {
			if (snapshot.unit().type() == UnitType.EVENT) {
				Event event = read(snapshot, Event.class, warnings,
						"Das Event " + snapshot.unit().eventId() + " konnte nicht gelesen werden und fehlt.");
				if (event != null) {
					events.put(snapshot.unit().eventId(), event);
				}
			}
		}

		Map<UnitKey, Presentation> presentations = new LinkedHashMap<>();
		Map<Event, List<Presentation>> presentationsOfEvent = new HashMap<>();
		for (UnitSnapshot snapshot : snapshots) {
			UnitKey unit = snapshot.unit();
			if (unit.type() != UnitType.PRESENTATION) {
				continue;
			}
			Event event = events.get(unit.eventId());
			if (event == null) {
				orphans++;
				continue;
			}
			Presentation presentation = read(snapshot, Presentation.class, warnings, "Die Vorstellung "
					+ unit.presentationId() + " von \"" + event.getName() + "\" konnte nicht gelesen werden und fehlt.");
			if (presentation != null) {
				presentations.put(unit, presentation);
				presentationsOfEvent.computeIfAbsent(event, e -> new ArrayList<>()).add(presentation);
			}
		}

		Map<Presentation, List<Booking>> bookingsOfPresentation = new HashMap<>();
		for (UnitSnapshot snapshot : snapshots) {
			UnitKey unit = snapshot.unit();
			if (unit.type() != UnitType.BOOKING) {
				continue;
			}
			Presentation presentation = presentations.get(UnitKey.presentation(unit.eventId(), unit.presentationId()));
			if (presentation == null) {
				orphans++;
				continue;
			}
			Booking booking = read(snapshot, Booking.class, warnings,
					"Eine Buchung von \"" + presentation.getName() + "\" konnte nicht gelesen werden und fehlt.");
			if (booking != null) {
				bookingsOfPresentation.computeIfAbsent(presentation, p -> new ArrayList<>()).add(booking);
			}
		}

		for (Map.Entry<Presentation, List<Booking>> entry : bookingsOfPresentation.entrySet()) {
			entry.getValue().sort(BOOKING_ORDER);
			entry.getKey().setBookings(entry.getValue());
		}
		for (Map.Entry<Event, List<Presentation>> entry : presentationsOfEvent.entrySet()) {
			entry.getValue().sort(PRESENTATION_ORDER);
			entry.getKey().setPresentations(entry.getValue());
		}
		if (orphans > 0) {
			LOG.info(orphans + " Einheiten ohne sichtbare Eltern-Einheit wurden übergangen.");
		}

		List<Event> result = new ArrayList<>(events.values());
		result.sort(EVENT_ORDER);
		return new Assembly<>(result, warnings);
	}

	/** Setzt die Hallen aus den Ständen der Einheiten zusammen, nach Name sortiert. */
	static Assembly<Hall> assembleHalls(Collection<UnitSnapshot> snapshots) {
		List<String> warnings = new ArrayList<>();
		List<Hall> halls = new ArrayList<>();
		for (UnitSnapshot snapshot : snapshots) {
			if (snapshot.unit().type() == UnitType.HALL) {
				Hall hall = read(snapshot, Hall.class, warnings,
						"Die Halle " + snapshot.unit().hallId() + " konnte nicht gelesen werden und fehlt.");
				if (hall != null) {
					halls.add(hall);
				}
			}
		}
		halls.sort(HALL_ORDER);
		return new Assembly<>(halls, warnings);
	}

	/**
	 * Liest die Einheit, wenn sie sichtbar ist. Ergibt {@code null} für eine gelöschte Einheit, für einen Ordner ohne
	 * eigene Dateien (keine Version, Status {@code OK}: die Einheit gibt es nicht) und, mit der Warnung
	 * {@code unreadable}, für eine Einheit ohne lesbare Version oder mit Inhalt, den Jackson nicht liest. Eine
	 * gesperrte Einheit mit lesbarer Version wird geladen und erzeugt die Warnung SYS-008 bzw. SYS-009.
	 */
	private static <T> T read(UnitSnapshot snapshot, Class<T> type, List<String> warnings, String unreadable) {
		if (snapshot.version() == null) {
			if (snapshot.status() != Status.OK) {
				warnings.add(unreadable);
			}
			return null;
		}
		if (snapshot.deleted()) {
			return null;
		}
		if (snapshot.payload() == null) {
			warnings.add(unreadable);
			return null;
		}
		T value;
		try {
			// treeToValue verändert den Baum nicht; die Payloads gehören dem Index
			value = MAPPER.treeToValue(snapshot.payload(), type);
		} catch (JsonProcessingException | RuntimeException e) {
			LOG.warning("Die Einheit " + snapshot.unit().relativePath() + " lässt sich nicht lesen: "
					+ StoreMessages.describe(e).lines().findFirst().orElse(""));
			warnings.add(unreadable);
			return null;
		}
		if (snapshot.status() == Status.LOCKED_DAMAGED) {
			warnings.add(StoreMessages.damaged(snapshot.unit().relativePath()));
		} else if (snapshot.status() == Status.LOCKED_FORMAT) {
			warnings.add(StoreMessages.newerFormat(snapshot.unit().relativePath()));
		}
		return value;
	}
}
