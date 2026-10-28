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
	private static final ObjectMapper MAPPER = JsonMapperFactory.createCompact();

	private static final String PRESENTATIONS = "presentations";
	private static final String BOOKINGS = "bookings";

	/** Vorstellungen: Datum, Uhrzeit (je {@code null} zuletzt), Name ohne Groß-/Kleinschreibung ({@code null} zuletzt), ID. */
	static final Comparator<Presentation> PRESENTATION_ORDER = Comparator
			.comparing(Presentation::getDate, Comparator.nullsLast(Comparator.<LocalDate>naturalOrder()))
			.thenComparing(Presentation::getTime, Comparator.nullsLast(Comparator.<LocalTime>naturalOrder()))
			.thenComparing(Presentation::getName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
			.thenComparing(Presentation::getId, Comparator.nullsLast(Comparator.<String>naturalOrder()));

	/** Buchungen: {@link Booking#PLACE_ORDER}; bei gleichem Platz (Buchungen ohne Platz) entscheidet die ID. */
	private static final Comparator<Booking> BOOKING_ORDER = Booking.PLACE_ORDER
			.thenComparing(Booking::getId, Comparator.nullsLast(Comparator.<String>naturalOrder()));

	/** Events: Name ohne Groß-/Kleinschreibung ({@code null} zuletzt), ID. */
	static final Comparator<Event> EVENT_ORDER = Comparator
			.comparing(Event::getName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
			.thenComparing(Event::getId, Comparator.nullsLast(Comparator.<String>naturalOrder()));

	/** Hallen: Name ohne Groß-/Kleinschreibung ({@code null} zuletzt), ID. */
	static final Comparator<Hall> HALL_ORDER = Comparator
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

	/**
	 * Die Inhalte der Einheiten des Events je Schlüssel, wie {@link #decompose(Event)} sie bildet (Kopf, Vorstellungen,
	 * Buchungen mit Daten): der Inhalt der Einheit im Modell, für den Vergleich "lokal verändert".
	 *
	 * @throws IllegalStateException wie {@link #decompose(Event)}
	 */
	static Map<UnitKey, ObjectNode> payloads(Event event) {
		Map<UnitKey, ObjectNode> payloads = new LinkedHashMap<>();
		for (UnitPayload part : decompose(event)) {
			payloads.put(part.unit(), part.payload());
		}
		return payloads;
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
	 * <li>Buchung mit unlesbarem, nicht gelöschtem Stand im Bestand, die nicht angezeigt wird, obwohl ihre Vorstellung
	 * angezeigt wird (ein fremder Verkauf mit unlesbarem Inhalt auf einem Sitz, den der Nutzer jetzt verkauft): ebenfalls
	 * erste Version, ohne Vorgänger. Der Nutzer hat den Stand nie gesehen; seine Version tritt gegen ihn an (Gabelung), statt
	 * als Nachfolger auf der unlesbaren Version zu stehen und sie still zu überschreiben. Nur eine Buchung kann im Modell
	 * stehen, während ihr Stand verborgen ist (der Sitz ist ein leerer Platzhalter); eine verborgene Vorstellung steht nicht
	 * darin, ihre Änderung (etwa beim Wiederherstellen) ersetzt den unlesbaren Stand als Nachfolger. Wird auch die
	 * Vorstellung nicht angezeigt (unlesbar oder gelöscht, etwa beim Wiederherstellen oder für ein gehaltenes Objekt nach
	 * einem fremden unlesbaren Kopf), fehlte die Buchung nur mit ihr und bekommt wie jede geänderte eine neue Version auf der
	 * aktuellen. Einheiten, die auf ihren Platz im Modell warten, sind nicht im Bestand und zählen als neue Einheit.</li>
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
			} else if (!known.deleted() && part.payload().equals(known.payload())) {
				continue;
			} else if (part.unit().type() == UnitType.BOOKING && !known.deleted() && !shown.contains(part.unit())
					&& !isReadable(known) && parentShown(part.unit(), shown)) {
				changes.add(new Change(part.unit(), List.of(), part.payload(), false));
			} else {
				changes.add(new Change(part.unit(), List.of(known.version()), part.payload(), false));
			}
		}

		List<UnitSnapshot> vanished = new ArrayList<>();
		for (UnitSnapshot known : baseline.values()) {
			if (known.version() != null && !known.deleted() && shown.contains(known.unit())
					&& !present.contains(known.unit())) {
				UnitKey parent = parentOf(known.unit());
				if (parent != null && present.contains(parent)) {
					vanished.add(known);
				}
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

	/** Ob die Eltern-Einheit angezeigt wird; ein Event-Kopf oder eine Halle hat keine. */
	private static boolean parentShown(UnitKey unit, Set<UnitKey> shown) {
		UnitKey parent = parentOf(unit);
		return parent != null && shown.contains(parent);
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
		requireWritable(known.unit(), known.status());
	}

	/** Wie {@link #requireWritable(UnitSnapshot)}, für eine Einheit, von der nur der Zustand der Kette zählt. */
	static void requireWritable(UnitKey unit, Status status) {
		String locked = lockMessage(unit, status);
		if (locked != null) {
			throw new LockedUnitException(locked);
		}
		if (status == Status.WAITING) {
			throw new LockedUnitException(unit.relativePath()
					+ " wartet noch auf eine Datei aus dem gemeinsamen Ordner. Es wurde nichts gespeichert.");
		}
	}

	/** Der Text (SYS-008 bzw. SYS-009), wenn die Einheit gesperrt ist; sonst {@code null}. */
	static String lockMessage(UnitKey unit, Status status) {
		return switch (status) {
		case LOCKED_DAMAGED -> StoreMessages.damaged(unit.relativePath());
		case LOCKED_FORMAT -> StoreMessages.newerFormat(unit.relativePath());
		case WAITING, OK -> null;
		};
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
		// Kinder einer gelöschten Einheit bleiben liegen (Löschen nur am Kopf) und sind gewollt unsichtbar; gemeldet
		// werden nur Einheiten, deren Eltern-Einheit fehlt oder unlesbar ist
		Set<UnitKey> hidden = new HashSet<>();
		for (UnitSnapshot snapshot : snapshots) {
			if (snapshot.version() != null && snapshot.deleted()) {
				hidden.add(snapshot.unit());
			}
		}

		// Gruppiert wird über die Schlüssel der Einheiten: die ID einer Vorstellung gilt nur innerhalb ihres Events
		Map<UnitKey, Event> events = new LinkedHashMap<>();
		for (UnitSnapshot snapshot : snapshots) {
			UnitKey unit = snapshot.unit();
			if (unit.type() == UnitType.EVENT) {
				Event event = read(snapshot, Event.class, warnings, unreadableEvent(unit.eventId()),
						idMismatch(unit.eventId(), Event::getId));
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
				if (hidden.contains(eventKey)) {
					hidden.add(unit);
				} else {
					orphans++;
				}
				continue;
			}
			Presentation presentation = read(snapshot, Presentation.class, warnings,
					unreadablePresentation(unit.presentationId(), event.getName()),
					idMismatch(unit.presentationId(), Presentation::getId));
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
				if (!hidden.contains(presentationKey)) {
					orphans++;
				}
				continue;
			}
			Booking booking = read(snapshot, Booking.class, warnings, unreadableBooking(presentation.getName()),
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

	/**
	 * Setzt eine Vorstellung mit ihren sichtbaren Buchungen zusammen, nach den Regeln von {@link #assembleEvents}: Aus
	 * {@code bookings} zählen nur die Buchungen dieser Vorstellung, die sich lesen lassen, in der Reihenfolge der Plätze.
	 * Die Payloads bleiben unverändert.
	 *
	 * @return die Vorstellung ohne Elternverweis, oder {@code null}, wenn sie gelöscht oder unlesbar ist
	 */
	static Presentation assemblePresentation(UnitSnapshot presentation, Collection<UnitSnapshot> bookings) {
		Presentation result = readUnit(presentation, Presentation.class);
		if (result == null) {
			return null;
		}
		UnitKey key = presentation.unit();
		List<Booking> visible = new ArrayList<>();
		for (UnitSnapshot snapshot : bookings) {
			UnitKey unit = snapshot.unit();
			if (unit.type() == UnitType.BOOKING && unit.eventId().equals(key.eventId())
					&& unit.presentationId().equals(key.presentationId())) {
				Booking booking = readUnit(snapshot, Booking.class);
				if (booking != null) {
					visible.add(booking);
				}
			}
		}
		visible.sort(BOOKING_ORDER);
		result.setBookings(visible);
		return result;
	}

	/** Die Warnung für ein Event, das sich nicht lesen lässt. */
	static String unreadableEvent(String eventId) {
		return "Das Event " + eventId + " konnte nicht gelesen werden und fehlt.";
	}

	/** Die Warnung für eine Vorstellung, die sich nicht lesen lässt. */
	static String unreadablePresentation(String presentationId, String eventName) {
		return "Die Vorstellung " + presentationId + " von \"" + eventName + "\" konnte nicht gelesen werden und fehlt.";
	}

	/** Die Warnung für eine Buchung, die sich nicht lesen lässt. */
	static String unreadableBooking(String presentationName) {
		return "Eine Buchung von \"" + presentationName + "\" konnte nicht gelesen werden und fehlt.";
	}

	/** Die Warnung für eine Halle, die sich nicht lesen lässt. */
	static String unreadableHall(String hallId) {
		return "Die Halle " + hallId + " konnte nicht gelesen werden und fehlt.";
	}

	/** Setzt die Hallen aus den Ständen der Einheiten zusammen, nach Name sortiert. */
	static Assembly<Hall> assembleHalls(Collection<UnitSnapshot> snapshots) {
		List<String> warnings = new ArrayList<>();
		List<Hall> halls = new ArrayList<>();
		Set<UnitKey> shown = new HashSet<>();
		for (UnitSnapshot snapshot : snapshots) {
			if (snapshot.unit().type() == UnitType.HALL) {
				String hallId = snapshot.unit().hallId();
				Hall hall = read(snapshot, Hall.class, warnings, unreadableHall(hallId),
						idMismatch(hallId, Hall::getId));
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
		String locked = lockMessage(snapshot.unit(), snapshot.status());
		if (locked != null) {
			warnings.add(locked);
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
			if (isReadable(snapshot)) {
				readable.add(snapshot.unit());
			}
		}
		return readable;
	}

	/** Ob das Zusammensetzen die Einheit lesen würde ({@link #readUnit} ergibt ein Objekt). */
	static boolean isReadable(UnitSnapshot snapshot) {
		return readUnit(snapshot, modelClass(snapshot.unit().type())) != null;
	}

	/** Fügt {@code item} vor dem ersten Eintrag ein, der nach der Ordnung hinter ihm käme (wie beim Laden); die übrige Reihenfolge bleibt. */
	static <T> void insertSorted(List<T> list, T item, Comparator<? super T> order) {
		int index = 0;
		while (index < list.size() && order.compare(list.get(index), item) <= 0) {
			index++;
		}
		list.add(index, item);
	}

	/**
	 * Liest eine einzelne Einheit nach den Regeln des Zusammensetzens ({@link #read}, ID-Prüfung eingeschlossen), aber
	 * ohne Warnung. Der Payload bleibt unverändert.
	 *
	 * @param type die Modellklasse zur Art der Einheit (Event, Presentation, Booking oder Hall)
	 * @return ein neues Objekt, oder {@code null}, wenn die Einheit gelöscht ist, keine Version hat oder sich nicht lesen
	 *         lässt
	 * @throws IllegalArgumentException wenn {@code type} nicht zur Art der Einheit passt
	 */
	static <T> T readUnit(UnitSnapshot snapshot, Class<T> type) {
		UnitKey unit = snapshot.unit();
		Function<?, String> mismatchOf = switch (unit.type()) {
		case EVENT -> idMismatch(unit.eventId(), Event::getId);
		case PRESENTATION -> idMismatch(unit.presentationId(), Presentation::getId);
		case BOOKING -> (Booking b) -> bookingMismatch(unit, b);
		case HALL -> idMismatch(unit.hallId(), Hall::getId);
		};
		if (type != modelClass(unit.type())) {
			throw new IllegalArgumentException(type.getSimpleName() + " passt nicht zu " + unit.relativePath());
		}
		if (snapshot.version() == null || snapshot.deleted() || snapshot.payload() == null) {
			return null;
		}
		@SuppressWarnings("unchecked")
		Function<T, String> typed = (Function<T, String>) mismatchOf;
		return parse(snapshot, type, typed);
	}

	private static Class<?> modelClass(UnitType type) {
		return switch (type) {
		case EVENT -> Event.class;
		case PRESENTATION -> Presentation.class;
		case BOOKING -> Booking.class;
		case HALL -> Hall.class;
		};
	}

	/**
	 * Ob die Einheit gewollt fehlt: gelöscht, oder ein Ordner ohne eigene Version mit Status {@code OK} (die Einheit gibt es
	 * nicht). Dann gibt es, anders als bei einer unlesbaren Einheit, keine Warnung (siehe {@link #read}).
	 */
	static boolean isAbsent(UnitSnapshot snapshot) {
		return snapshot.version() == null ? snapshot.status() == Status.OK : snapshot.deleted();
	}

	/** Prüft, dass die ID im Inhalt die des Ordners ist; sonst der Grund (siehe {@link #parse}). */
	private static <T> Function<T, String> idMismatch(String expectedId, Function<T, String> idOf) {
		return value -> {
			String actual = idOf.apply(value);
			return expectedId.equals(actual) ? null : "Die ID im Inhalt (" + actual + ") passt nicht zum Ordner";
		};
	}
}
