package de.eltviller_carneval_verein.karten.repository;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.store.Change;
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

	/**
	 * Das Ergebnis des Zusammensetzens: die lesbaren Objekte, die Warnungen für den Nutzer und die Einheiten, die
	 * tatsächlich in die Objekte eingegangen sind ({@code shown}: Kopf, Vorstellungen, Buchungen bzw. Hallen).
	 */
	record Assembly<T>(List<T> items, List<String> warnings, Set<UnitKey> shown) {
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
		Set<String> presentationIds = new HashSet<>();
		for (int i = 0; i < presentations.size(); i++) {
			Presentation presentation = presentations.get(i);
			ObjectNode presentationNode = (ObjectNode) presentationNodes.get(i);
			JsonNode bookingNodes = presentationNode.remove(BOOKINGS);
			if (!presentationIds.add(presentation.getId())) {
				throw new IllegalStateException("Zwei Vorstellungen haben dieselbe ID (" + presentation.getId()
						+ ") im Event \"" + event.getName() + "\". Es wurde nichts gespeichert.");
			}
			parts.add(new UnitPayload(UnitKey.presentation(event.getId(), presentation.getId()), presentationNode));

			List<Booking> bookings = presentation.getBookings() != null ? presentation.getBookings() : List.of();
			Map<UnitKey, Booking> taken = new HashMap<>();
			for (int j = 0; j < bookings.size(); j++) {
				Booking booking = bookings.get(j);
				if (!booking.hasData()) {
					continue;
				}
				UnitKey key = bookingKey(event.getId(), presentation.getId(), booking);
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

	/**
	 * Die Änderungen, die aus dem Bestand {@code baseline} den Stand {@code units} machen. Das Ergebnis folgt zuerst
	 * der Reihenfolge von {@code units} (Eltern vor Kindern), danach kommen die Löschungen nach Schlüssel
	 * ({@code relativePath}).
	 * <ul>
	 * <li>Neue Einheit (nicht im Bestand oder ohne Version): erste Version, ohne Vorgänger.</li>
	 * <li>Geänderte oder wieder aufgetauchte (gelöschte) Einheit: neue Version auf der aktuellen Version.</li>
	 * <li>Einheit nur im Bestand: Löschung, aber nur wenn sie angezeigt wurde ({@code shown}), ihre Eltern-Einheit
	 * (Vorstellung: der Kopf, Buchung: ihre Vorstellung) in {@code units} steht und sie eine Version hat und nicht
	 * schon gelöscht ist. Sonst bleibt sie unberührt, denn gelöscht wird nur am Kopf. Ein Event-Kopf oder eine Halle
	 * hat keine Eltern-Einheit und wird nie hier gelöscht, nur mit {@link #deletion}.</li>
	 * </ul>
	 * Die Daten von {@code baseline} und {@code units} bleiben unverändert.
	 *
	 * @param baseline alle bekannten Einheiten des Events bzw. der Halle, sichtbare wie unsichtbare
	 * @param shown    die Einheiten des Modells, wie es zuletzt geladen oder gespeichert wurde (siehe
	 *                 {@link Assembly#shown()}). Eine Einheit, die nicht angezeigt werden konnte (unlesbar, Inhalt
	 *                 passt nicht zum Ordner), fehlt in {@code units}, ohne dass der Nutzer sie entfernt hat; sie
	 *                 wird deshalb nie gelöscht.
	 * @throws LockedUnitException wenn eine betroffene Einheit gesperrt ist oder noch auf eine Datei wartet; dann
	 *                             wird nichts gespeichert
	 */
	static List<Change> changes(Map<UnitKey, UnitSnapshot> baseline, Set<UnitKey> shown, List<UnitPayload> units) {
		Set<UnitKey> present = new HashSet<>();
		for (UnitPayload part : units) {
			present.add(part.unit());
		}

		List<Change> changes = new ArrayList<>();
		for (UnitPayload part : units) {
			UnitSnapshot known = baseline.get(part.unit());
			if (known == null || known.version() == null) {
				changes.add(new Change(part.unit(), List.of(), part.payload(), false));
			} else if (known.deleted() || !part.payload().equals(known.payload())) {
				changes.add(new Change(part.unit(), List.of(known.version()), part.payload(), false));
			}
		}

		List<UnitSnapshot> vanished = new ArrayList<>();
		for (UnitSnapshot known : baseline.values()) {
			UnitKey parent = parentOf(known.unit());
			if (known.version() != null && !known.deleted() && shown.contains(known.unit())
					&& !present.contains(known.unit()) && parent != null && present.contains(parent)) {
				vanished.add(known);
			}
		}
		vanished.sort(Comparator.comparing(s -> s.unit().relativePath()));
		for (UnitSnapshot known : vanished) {
			changes.add(deletion(known));
		}

		for (Change change : changes) {
			UnitSnapshot known = baseline.get(change.unit());
			if (known != null) {
				requireWritable(known);
			}
		}
		return changes;
	}

	/** Die Löschung einer Einheit auf ihrer aktuellen Version (für das Löschen eines ganzen Events oder einer Halle). */
	static Change deletion(UnitSnapshot baseline) {
		return new Change(baseline.unit(), List.of(baseline.version()), null, true);
	}

	/** Die Eltern-Einheit nach dem Schlüssel; {@code null} für Event-Kopf und Halle. */
	private static UnitKey parentOf(UnitKey unit) {
		return switch (unit.type()) {
		case PRESENTATION -> UnitKey.event(unit.eventId());
		case BOOKING -> UnitKey.presentation(unit.eventId(), unit.presentationId());
		case EVENT, HALL -> null;
		};
	}

	/**
	 * Eine gesperrte oder noch wartende Einheit lässt sich nicht ändern, auch nicht löschen.
	 *
	 * @throws LockedUnitException wenn die Einheit gesperrt ist oder noch auf eine Datei wartet
	 */
	static void requireWritable(UnitSnapshot known) {
		String path = known.unit().relativePath();
		switch (known.status()) {
		case LOCKED_DAMAGED -> throw new LockedUnitException(StoreMessages.damaged(path));
		case LOCKED_FORMAT -> throw new LockedUnitException(StoreMessages.newerFormat(path));
		case WAITING -> throw new LockedUnitException(
				path + " wartet noch auf eine Datei aus dem gemeinsamen Ordner. Es wurde nichts gespeichert.");
		case OK -> {
		}
		}
	}

	/** Der Schlüssel einer Buchung: auf dem Sitz, wenn sie einen hat und nicht "ohne Platz" ist, sonst über ihre Kennung. */
	private static UnitKey bookingKey(String eventId, String presentationId, Booking booking) {
		if (booking.getSeatId() != null && !booking.isUnassigned()) {
			return UnitKey.booking(eventId, presentationId, booking.getSeatId());
		}
		return UnitKey.unassignedBooking(eventId, presentationId, booking.getId());
	}

	/**
	 * Warum der Inhalt einer Buchung nicht zur Einheit {@code unit} passt, oder {@code null}, wenn er passt. Die Regel
	 * ist die Umkehrung von {@link #bookingKey}: Die Buchung gehört genau dorthin, wohin das Zerlegen sie schreibt.
	 */
	private static String bookingMismatch(UnitKey unit, Booking booking) {
		try {
			if (bookingKey(unit.eventId(), unit.presentationId(), booking).equals(unit)) {
				return null;
			}
		} catch (IllegalArgumentException e) {
			// Sitz oder Kennung im Inhalt ist als Ordnername unzulässig
		}
		return "Sitz (" + booking.getSeatId() + ") und Kennung (" + booking.getId() + ") im Inhalt passen nicht zum Ordner";
	}

	/**
	 * Setzt die Events aus den Ständen der Einheiten zusammen. Die Payloads der Stände bleiben unverändert (der Index
	 * teilt sie sich). Events nach Name sortiert, ihre Vorstellungen nach Datum, Uhrzeit, Name und ID, die
	 * Buchungen in der Reihenfolge der Plätze.
	 */
	static Assembly<Event> assembleEvents(Collection<UnitSnapshot> snapshots) {
		List<String> warnings = new ArrayList<>();
		Set<UnitKey> shown = new HashSet<>();
		int orphans = 0;

		// Gruppiert wird über die Schlüssel der Einheiten: die ID einer Vorstellung gilt nur innerhalb ihres Events
		Map<UnitKey, Event> events = new LinkedHashMap<>();
		for (UnitSnapshot snapshot : snapshots) {
			UnitKey unit = snapshot.unit();
			if (unit.type() == UnitType.EVENT) {
				Event event = read(snapshot, Event.class, warnings,
						"Das Event " + unit.eventId() + " konnte nicht gelesen werden und fehlt.",
						eventMismatch(unit));
				if (event != null) {
					events.put(unit, event);
					shown.add(unit);
				}
			}
		}

		Map<UnitKey, Presentation> presentations = new LinkedHashMap<>();
		Map<UnitKey, List<Presentation>> presentationsOfEvent = new HashMap<>();
		for (UnitSnapshot snapshot : snapshots) {
			UnitKey unit = snapshot.unit();
			if (unit.type() != UnitType.PRESENTATION) {
				continue;
			}
			UnitKey eventKey = UnitKey.event(unit.eventId());
			Event event = events.get(eventKey);
			if (event == null) {
				orphans++;
				continue;
			}
			Presentation presentation = read(snapshot, Presentation.class, warnings,
					"Die Vorstellung " + unit.presentationId() + " von \"" + event.getName()
							+ "\" konnte nicht gelesen werden und fehlt.",
					presentationMismatch(unit));
			if (presentation != null) {
				presentations.put(unit, presentation);
				shown.add(unit);
				presentationsOfEvent.computeIfAbsent(eventKey, k -> new ArrayList<>()).add(presentation);
			}
		}

		Map<UnitKey, List<Booking>> bookingsOfPresentation = new HashMap<>();
		for (UnitSnapshot snapshot : snapshots) {
			UnitKey unit = snapshot.unit();
			if (unit.type() != UnitType.BOOKING) {
				continue;
			}
			UnitKey presentationKey = UnitKey.presentation(unit.eventId(), unit.presentationId());
			Presentation presentation = presentations.get(presentationKey);
			if (presentation == null) {
				orphans++;
				continue;
			}
			Booking booking = read(snapshot, Booking.class, warnings,
					"Eine Buchung von \"" + presentation.getName() + "\" konnte nicht gelesen werden und fehlt.",
					b -> bookingMismatch(unit, b));
			if (booking != null) {
				bookingsOfPresentation.computeIfAbsent(presentationKey, k -> new ArrayList<>()).add(booking);
				shown.add(unit);
			}
		}

		for (Map.Entry<UnitKey, List<Booking>> entry : bookingsOfPresentation.entrySet()) {
			entry.getValue().sort(BOOKING_ORDER);
			presentations.get(entry.getKey()).setBookings(entry.getValue());
		}
		for (Map.Entry<UnitKey, List<Presentation>> entry : presentationsOfEvent.entrySet()) {
			entry.getValue().sort(PRESENTATION_ORDER);
			events.get(entry.getKey()).setPresentations(entry.getValue());
		}
		if (orphans > 0) {
			LOG.info(orphans + " Einheiten ohne sichtbare Eltern-Einheit wurden übergangen.");
		}

		List<Event> result = new ArrayList<>(events.values());
		result.sort(EVENT_ORDER);
		return new Assembly<>(result, warnings, shown);
	}

	/** Setzt die Hallen aus den Ständen der Einheiten zusammen, nach Name sortiert. */
	static Assembly<Hall> assembleHalls(Collection<UnitSnapshot> snapshots) {
		List<String> warnings = new ArrayList<>();
		List<Hall> halls = new ArrayList<>();
		Set<UnitKey> shown = new HashSet<>();
		for (UnitSnapshot snapshot : snapshots) {
			if (snapshot.unit().type() == UnitType.HALL) {
				String hallId = snapshot.unit().hallId();
				Hall hall = read(snapshot, Hall.class, warnings,
						"Die Halle " + hallId + " konnte nicht gelesen werden und fehlt.",
						hallMismatch(snapshot.unit()));
				if (hall != null) {
					halls.add(hall);
					shown.add(snapshot.unit());
				}
			}
		}
		halls.sort(HALL_ORDER);
		return new Assembly<>(halls, warnings, shown);
	}

	/**
	 * Liest die Einheit, wenn sie sichtbar ist. Ergibt {@code null} für eine gelöschte Einheit, für einen Ordner ohne
	 * eigene Dateien (keine Version, Status {@code OK}: die Einheit gibt es nicht) und, mit der Warnung
	 * {@code unreadable}, für eine Einheit ohne lesbare Version oder mit Inhalt, den Jackson nicht liest. Eine
	 * gesperrte Einheit mit lesbarer Version wird geladen und erzeugt die Warnung SYS-008 bzw. SYS-009. Ergibt
	 * {@code mismatchOf} einen Text, passt der Inhalt nicht zum Ordner der Einheit (die ID zeigt woandershin): Die
	 * Einheit wird wie eine unlesbare behandelt.
	 */
	private static <T> T read(UnitSnapshot snapshot, Class<T> type, List<String> warnings, String unreadable,
			Function<T, String> mismatchOf) {
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
		T value = parse(snapshot, type, mismatchOf);
		if (value == null) {
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

	/**
	 * Liest die Daten einer Version als {@code type}. Ergibt {@code null} (mit einem Eintrag im Log), wenn Jackson sie
	 * nicht liest oder {@code mismatchOf} einen Text ergibt: Der Inhalt passt dann nicht zum Ordner der Einheit (z. B. ein
	 * von Hand kopierter Ordner, gespeichert würde dorthin, wohin die ID des Inhalts zeigt). Die Regel für {@link #read}
	 * und {@link #readableUnits}.
	 */
	private static <T> T parse(UnitSnapshot snapshot, Class<T> type, Function<T, String> mismatchOf) {
		T value;
		try {
			// treeToValue verändert den Baum nicht; die Payloads gehören dem Index
			value = MAPPER.treeToValue(snapshot.payload(), type);
		} catch (JsonProcessingException | RuntimeException e) {
			LOG.warning("Die Einheit " + snapshot.unit().relativePath() + " lässt sich nicht lesen: "
					+ StoreMessages.describe(e).lines().findFirst().orElse(""));
			return null;
		}
		String mismatch = mismatchOf.apply(value);
		if (mismatch != null) {
			LOG.warning("Die Einheit " + snapshot.unit().relativePath() + " wird übergangen: " + mismatch + ".");
			return null;
		}
		return value;
	}

	/**
	 * Alle Einheiten, die das Zusammensetzen lesen würde (Version vorhanden, nicht gelöscht, Daten als Objekt lesbar, ID
	 * passt zum Ordner), ohne Rücksicht darauf, ob ihre Eltern-Einheit gelöscht oder unlesbar ist. Für das Wiederbeleben
	 * eines gelöschten Events oder einer gelöschten Vorstellung: Was darunter noch lesbar liegt, war dem Nutzer nicht
	 * angezeigt, gehört aber zum wieder sichtbaren Objekt.
	 */
	static Set<UnitKey> readableUnits(Collection<UnitSnapshot> snapshots) {
		Set<UnitKey> readable = new HashSet<>();
		for (UnitSnapshot snapshot : snapshots) {
			if (snapshot.version() == null || snapshot.deleted() || snapshot.payload() == null) {
				continue;
			}
			UnitKey unit = snapshot.unit();
			boolean ok = switch (unit.type()) {
			case EVENT -> parse(snapshot, Event.class, eventMismatch(unit)) != null;
			case PRESENTATION -> parse(snapshot, Presentation.class, presentationMismatch(unit)) != null;
			case BOOKING -> parse(snapshot, Booking.class, (Booking b) -> bookingMismatch(unit, b)) != null;
			case HALL -> parse(snapshot, Hall.class, hallMismatch(unit)) != null;
			};
			if (ok) {
				readable.add(unit);
			}
		}
		return readable;
	}

	private static Function<Event, String> eventMismatch(UnitKey unit) {
		return e -> unit.eventId().equals(e.getId()) ? null : "Die ID im Inhalt (" + e.getId() + ") passt nicht zum Ordner";
	}

	private static Function<Presentation, String> presentationMismatch(UnitKey unit) {
		return p -> unit.presentationId().equals(p.getId()) ? null
				: "Die ID im Inhalt (" + p.getId() + ") passt nicht zum Ordner";
	}

	private static Function<Hall, String> hallMismatch(UnitKey unit) {
		return h -> unit.hallId().equals(h.getId()) ? null : "Die ID im Inhalt (" + h.getId() + ") passt nicht zum Ordner";
	}
}
