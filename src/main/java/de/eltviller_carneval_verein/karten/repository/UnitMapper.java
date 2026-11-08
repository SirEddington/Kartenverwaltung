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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.store.Change;
import de.eltviller_carneval_verein.karten.store.ConflictInput;
import de.eltviller_carneval_verein.karten.store.ContentGate;
import de.eltviller_carneval_verein.karten.store.StoreMessages;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.UnitSnapshot;
import de.eltviller_carneval_verein.karten.store.UnitState.Status;
import de.eltviller_carneval_verein.karten.store.UnitType;
import de.eltviller_carneval_verein.karten.store.VersionMarks;
import de.eltviller_carneval_verein.karten.validation.EntityLabels;

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
	 * Das Ergebnis des Zusammensetzens: die lesbaren Objekte, die Warnungen für den Nutzer, die gesperrten Einheiten, die
	 * dabei geladen wurden, und die Einheiten, die tatsächlich in die Objekte eingegangen sind ({@code shown}: Kopf,
	 * Vorstellungen, Buchungen bzw. Hallen).
	 *
	 * @param warnings Texte zu unlesbaren Einheiten, ohne die gesperrten
	 * @param locked   die geladenen Einheiten, deren Kette gesperrt ist (beschädigt oder neueres Format), mit ihrem Zustand
	 */
	record Assembly<T>(List<T> items, List<String> warnings, Map<UnitKey, Status> locked, Set<UnitKey> shown) {

		/** Alle Warnungen als Text, auch die gesperrten Einheiten (SYS-008 bzw. SYS-009): für alles, was nicht lädt. */
		List<String> allWarnings() {
			List<String> all = new ArrayList<>(warnings);
			locked.forEach((unit, status) -> all.add(lockMessage(unit, status)));
			return all;
		}
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
	 * ({@code relativePath}); die Löschungen unter einer wiederbelebten Eltern-Einheit stehen davor (siehe unten).
	 * <ul>
	 * <li>Neue Einheit (nicht im Bestand oder ohne Version): erste Version, ohne Vorgänger.</li>
	 * <li>Einheit mit unlesbarem, nicht gelöschtem Stand im Bestand, die nicht angezeigt wird, obwohl ihre Eltern-Einheit
	 * angezeigt wird (etwa ein fremder Verkauf mit unlesbarem Inhalt auf einem Sitz, den der Nutzer jetzt verkauft): ebenfalls
	 * erste Version, ohne Vorgänger. Der Nutzer hat den Stand nie gesehen; seine Version tritt gegen ihn an (Gabelung), statt
	 * als Nachfolger auf der unlesbaren Version zu stehen und sie still zu überschreiben. Wird auch die Eltern-Einheit nicht
	 * angezeigt (unlesbar oder gelöscht, etwa für ein gehaltenes Objekt nach einem fremden unlesbaren Kopf), fehlte die
	 * Einheit nur mit ihr und bekommt wie jede geänderte eine neue Version auf der aktuellen. Beim Wiederherstellen gelten
	 * die unlesbaren Einheiten des Zielstands als angezeigt: Das Wiederherstellen ersetzt sie als Nachfolger. Einheiten, die auf ihren Platz im Modell warten, sind nicht im Bestand und zählen als neue Einheit.</li>
	 * <li>Geänderte oder wieder aufgetauchte (gelöschte) Einheit: neue Version auf der aktuellen Version. "Geändert" heißt:
	 * anders als der Stand, wie dieses Programm ihn aus der Datei macht ({@link #sameAsModel}); Zusatzfelder, fehlende
	 * und verworfene Felder machen eine Einheit nicht zur geänderten (KARV-133).</li>
	 * <li>Einheit nur im Bestand: Löschung, aber nur wenn sie angezeigt wurde ({@code shown}), ihre Eltern-Einheit
	 * (Vorstellung: der Kopf, Buchung: ihre Vorstellung) in {@code units} steht und sie eine Version hat und nicht
	 * schon gelöscht ist. Sonst bleibt sie unberührt, denn gelöscht wird nur am Kopf. Ein Event-Kopf oder eine Halle
	 * hat keine Eltern-Einheit und wird nie hier gelöscht, nur mit {@link #deletion}.</li>
	 * <li>Reihenfolge beim Wiederbeleben: Ist eine übergeordnete Einheit (Kopf, Vorstellung) der gelöschten Einheit im
	 * Bestand gelöscht und wird jetzt wiederbelebt, stehen diese Löschungen (die alten Kinder) <b>vor</b> allen anderen
	 * Änderungen, also vor der neuen Version des Kopfs. Scheitert das Speichern danach, sieht der Wiederholversuch den Kopf
	 * noch gelöscht und räumt erneut, ohne Schon-Gelöschtes zu wiederholen; lebte der Kopf zuerst, wären die alten Kinder
	 * nach einem Fehler sofort wieder sichtbar.</li>
	 * </ul>
	 * Gesperrte Inhalte fasst das Speichern nicht an (KARV-135); geprüft wird dafür am Stand des Versionsspeichers, nicht
	 * nur am Vergleichsstand (der kennt eine fremde, zurückgestellte Sperre nicht):
	 * <ul>
	 * <li>Liegt eine geänderte Einheit unter einer Vorstellung oder einem Event-Kopf, dessen Stand inhaltlich zu neu ist
	 * ({@link #requireWritableAncestors}), scheitert das Speichern mit SYS-009.</li>
	 * <li>Wird eine angezeigte Vorstellung gelöscht, unter der eine gesperrte Einheit ohne gelöschten Vorfahren liegt,
	 * scheitert es mit SYS-009 bzw. SYS-008: Die gesperrte, womöglich bezahlte Buchung verschwände sonst still.</li>
	 * <li>Ein eigener Inhalt, den die Inhaltsprüfung ({@link ContentRules#GATE}) für inhaltlich zu neu hält, wird nicht
	 * geschrieben (SYS-009); sonst hielte dieser Rechner die Einheit für lesbar und die anderen sperrten sie.</li>
	 * </ul>
	 * Die Daten von {@code baseline} und {@code units} bleiben unverändert.
	 *
	 * @param baseline    alle bekannten Einheiten des Events bzw. der Halle, sichtbare wie unsichtbare
	 * @param shown       die Einheiten des Modells, wie es zuletzt geladen oder gespeichert wurde (siehe
	 *                    {@link Assembly#shown()}). Eine Einheit, die nicht angezeigt werden konnte (unlesbar, Inhalt
	 *                    passt nicht zum Ordner), fehlt in {@code units}, ohne dass der Nutzer sie entfernt hat; sie
	 *                    wird deshalb nie gelöscht.
	 * @param storeStates die Stände der genannten Einheiten im Versionsspeicher (Index, ohne Datei-Ein-/Ausgabe); gefragt
	 *                    werden nur die Vorfahren der geänderten Einheiten
	 * @param eventStates die Stände aller Einheiten eines Events im Versionsspeicher (Index); nur gefragt, wenn eine
	 *                    Vorstellung gelöscht wird
	 * @throws LockedUnitException   wenn eine betroffene Einheit gesperrt ist oder noch auf eine Datei wartet, unter einem
	 *                               inhaltlich zu neuen Vorfahren liegt, eine gelöschte Vorstellung gesperrte Einheiten hat
	 *                               oder ein eigener Inhalt inhaltlich zu neu ist; dann wird nichts gespeichert
	 * @throws IllegalStateException wenn die Inhaltsprüfung selbst scheitert (ein Programmfehler); es wird nichts gespeichert
	 */
	static List<Change> changes(Map<UnitKey, UnitSnapshot> baseline, Set<UnitKey> shown, List<UnitPayload> units,
			Function<Collection<UnitKey>, Map<UnitKey, UnitSnapshot>> storeStates,
			Function<String, Map<UnitKey, UnitSnapshot>> eventStates) {
		return changes(baseline, shown, units, storeStates, eventStates, ContentRules.GATE);
	}

	/** Wie {@link #changes(Map, Set, List, Function, Function)}, mit der Inhaltsprüfung {@code gate} (für Tests). */
	static List<Change> changes(Map<UnitKey, UnitSnapshot> baseline, Set<UnitKey> shown, List<UnitPayload> units,
			Function<Collection<UnitKey>, Map<UnitKey, UnitSnapshot>> storeStates,
			Function<String, Map<UnitKey, UnitSnapshot>> eventStates, ContentGate gate) {
		Set<UnitKey> present = new HashSet<>();
		for (UnitPayload part : units) {
			present.add(part.unit());
		}

		List<Change> changes = new ArrayList<>();
		for (UnitPayload part : units) {
			UnitSnapshot known = baseline.get(part.unit());
			if (known == null || known.version() == null) {
				changes.add(new Change(part.unit(), List.of(), part.payload(), false));
			} else if (!known.deleted() && sameAsModel(part.payload(), known)) {
				continue;
			} else if (!known.deleted() && !shown.contains(part.unit()) && !isReadable(known)
					&& parentShown(part.unit(), shown)) {
				changes.add(new Change(part.unit(), List.of(), part.payload(), false));
			} else {
				changes.add(new Change(part.unit(), List.of(known.version()), part.payload(), false));
			}
		}

		List<UnitSnapshot> vanished = new ArrayList<>();
		for (UnitSnapshot known : baseline.values()) {
			if (known.isLive() && shown.contains(known.unit())
					&& !present.contains(known.unit())) {
				UnitKey parent = known.unit().parent();
				if (parent != null && present.contains(parent)) {
					vanished.add(known);
				}
			}
		}
		vanished.sort(Comparator.comparing(s -> s.unit().relativePath()));
		List<Change> cleanup = new ArrayList<>();
		for (UnitSnapshot known : vanished) {
			if (revivesParent(baseline, known.unit())) {
				cleanup.add(deletion(known));
			} else {
				changes.add(deletion(known));
			}
		}
		changes.addAll(0, cleanup);

		for (Change change : changes) {
			UnitSnapshot known = baseline.get(change.unit());
			if (known != null) {
				requireWritable(known);
			}
		}
		requireWritableInStore(changes, storeStates, eventStates);
		requireOwnContent(changes, gate);
		return withMoves(baseline, changes);
	}

	/**
	 * Die Prüfungen von {@link #changes} am Stand des Versionsspeichers: kein inhaltlich zu neuer Vorfahr einer geänderten
	 * Einheit, keine gesperrte Einheit unter einer gelöschten Vorstellung.
	 */
	private static void requireWritableInStore(List<Change> changes,
			Function<Collection<UnitKey>, Map<UnitKey, UnitSnapshot>> storeStates,
			Function<String, Map<UnitKey, UnitSnapshot>> eventStates) {
		Set<UnitKey> ancestors = new LinkedHashSet<>();
		for (Change change : changes) {
			ancestors.addAll(change.unit().ancestors());
		}
		if (!ancestors.isEmpty()) {
			Map<UnitKey, UnitSnapshot> states = storeStates.apply(List.copyOf(ancestors));
			for (Change change : changes) {
				requireWritableAncestors(change.unit(), states);
			}
		}
		Map<String, Map<UnitKey, UnitSnapshot>> events = new HashMap<>();
		for (Change change : changes) {
			UnitKey unit = change.unit();
			if (change.deleted() && unit.type() == UnitType.PRESENTATION) {
				requireNothingLockedBelow(unit, events.computeIfAbsent(unit.eventId(), eventStates));
			}
		}
	}

	/**
	 * Eine Buchung unter einer Vorstellung oder einem Event-Kopf und eine Vorstellung unter einem Event-Kopf, dessen Stand
	 * inhaltlich zu neu ist ({@link UnitSnapshot#contentLocked()}), lassen sich nicht speichern: Dieses Programm kennt die
	 * Bedeutung des Vorfahren nicht. Eine Formatsperre ({@code LOCKED_FORMAT} ohne das Merkmal, ein älterer Stand wird
	 * angezeigt) und eine beschädigte Kette am Vorfahren sperren die Kinder nicht.
	 *
	 * @param storeStates die Stände der Vorfahren im Versionsspeicher; ein Vorfahr ohne Stand sperrt nicht
	 * @throws LockedUnitException mit SYS-009 und der Bezeichnung des Vorfahren
	 */
	static void requireWritableAncestors(UnitKey unit, Map<UnitKey, UnitSnapshot> storeStates) {
		for (UnitKey ancestor : unit.ancestors()) {
			UnitSnapshot state = storeStates.get(ancestor);
			if (state != null && state.contentLocked()) {
				throw LockedUnitException.newerFormat(ancestor);
			}
		}
	}

	/**
	 * Die Sperre einer Einheit nach ihrem Stand, oder {@code null}, wenn sie nicht gesperrt ist: zuerst
	 * {@link #missingLock} (sie fehlt im Modell, auch bei {@code contentLocked} mit Zustand {@code OK}, etwa bei einem
	 * ausstehenden rohen Zurücksetzen auf einen inhaltlich zu neuen Stand), sonst der Zustand, wenn die Kette beschädigt
	 * ist oder ein neueres Format hat. Ein wartender Stand ist nicht gesperrt.
	 */
	static Status lockOf(UnitSnapshot state) {
		Status lock = missingLock(state);
		if (lock == null && isLocked(state.status())) {
			lock = state.status();
		}
		return lock;
	}

	/**
	 * Unter der Vorstellung, die gelöscht wird, darf keine gesperrte Einheit ({@link #lockedBelow}) liegen. Das ist die
	 * letzte Sicherung beim Speichern; vorher lehnen Oberfläche und Repository das Löschen schon ab
	 * ({@link StoreEventRepository#requireNothingLockedUnder}), mit demselben Text.
	 *
	 * @param eventStates die Stände aller Einheiten des Events im Versionsspeicher
	 * @throws LockedUnitException mit {@link StoreMessages#lockedBelow}: die Vorstellung mit dem Namen aus ihrem Stand im
	 *                             Versionsspeicher, ohne lesbaren Stand mit ihrer Bezeichnung
	 */
	private static void requireNothingLockedBelow(UnitKey presentation, Map<UnitKey, UnitSnapshot> eventStates) {
		Map<UnitKey, Status> locked = lockedBelow(presentation, eventStates);
		if (!locked.isEmpty()) {
			throw new LockedUnitException(
					StoreMessages.lockedBelow(nameInStore(presentation, eventStates), lockedBelowCause(locked.values())));
		}
	}

	/**
	 * Der Name der Einheit (Feld {@code name}) aus ihrem Stand im Versionsspeicher, für die Meldungen; ohne lesbaren Stand
	 * ihre Bezeichnung ({@link LockedUnitException#label}).
	 */
	private static String nameInStore(UnitKey unit, Map<UnitKey, UnitSnapshot> states) {
		UnitSnapshot state = states.get(unit);
		if (state == null || state.payload() == null) {
			return LockedUnitException.label(unit);
		}
		return EntityLabels.nameOrPlaceholder(state.payload().path("name").asText(null));
	}

	/** Die Ursache für {@link StoreMessages#lockedBelow}: neueres Format vor beschädigt. */
	static Status lockedBelowCause(Collection<Status> locks) {
		return locks.contains(Status.LOCKED_FORMAT) ? Status.LOCKED_FORMAT : Status.LOCKED_DAMAGED;
	}

	/**
	 * Die gesperrten Einheiten ({@link #lockOf}) unter {@code root} (einer Vorstellung oder einem Event-Kopf, ohne
	 * {@code root} selbst), deren Vorfahren im Versionsspeicher nicht gelöscht sind (wie bei {@link #requireNothingLockedBelow}),
	 * mit der Art ihrer Sperre, nach Pfad geordnet.
	 *
	 * @param eventStates die Stände aller Einheiten des Events im Versionsspeicher
	 */
	static Map<UnitKey, Status> lockedBelow(UnitKey root, Map<UnitKey, UnitSnapshot> eventStates) {
		List<UnitSnapshot> below = new ArrayList<>();
		for (UnitSnapshot state : eventStates.values()) {
			if (state.unit().ancestors().contains(root) && !hasDeletedAncestor(state.unit(), eventStates)) {
				below.add(state);
			}
		}
		below.sort(Comparator.comparing(state -> state.unit().relativePath()));
		Map<UnitKey, Status> locked = new LinkedHashMap<>();
		for (UnitSnapshot state : below) {
			Status lock = lockOf(state);
			if (lock != null) {
				locked.put(state.unit(), lock);
			}
		}
		return locked;
	}

	/**
	 * Die Sitze der Vorstellung, die der Abgleich der Buchungen nicht vergeben darf (KARV-135): Ihre Buchungs-Einheit ist
	 * gesperrt ({@link #lockedBelow}), und das Modell hat keine Buchung mit Daten auf diesem Schlüssel ({@link #bookingKey}).
	 * Eine gesperrte Buchung, deren letzter lesbarer Stand angezeigt wird (etwa beschädigt nach einem lesbaren Stand),
	 * behält so ihren Sitz; ein leerer Platzhalter auf dem Sitz zählt nicht als Buchung.
	 *
	 * @param presentation  die Einheit der Vorstellung
	 * @param modelBookings die Buchungen der Vorstellung im Modell; {@code null}, wenn sie dort fehlt
	 * @param eventStates   die Stände aller Einheiten des Events im Versionsspeicher
	 * @return die Sitz-IDs ({@link UnitKey#bookingDir})
	 */
	static Set<String> lockedSeatIds(UnitKey presentation, List<Booking> modelBookings,
			Map<UnitKey, UnitSnapshot> eventStates) {
		Set<UnitKey> shown = new HashSet<>();
		for (Booking booking : modelBookings != null ? modelBookings : List.<Booking>of()) {
			if (booking.hasData()) {
				try {
					shown.add(bookingKey(presentation.eventId(), presentation.presentationId(), booking));
				} catch (IllegalArgumentException e) {
					// Sitz oder Kennung ist als Ordnername unzulässig: Diese Buchung trägt keine Einheit des Versionsspeichers
				}
			}
		}
		Set<String> seats = new LinkedHashSet<>();
		for (UnitKey unit : lockedBelow(presentation, eventStates).keySet()) {
			if (unit.type() == UnitType.BOOKING && !unit.isUnassigned() && !shown.contains(unit)) {
				seats.add(unit.bookingDir());
			}
		}
		return seats;
	}

	/** Ob eine Einheit über {@code unit} im Versionsspeicher gelöscht ist. */
	private static boolean hasDeletedAncestor(UnitKey unit, Map<UnitKey, UnitSnapshot> states) {
		for (UnitKey ancestor : unit.ancestors()) {
			UnitSnapshot state = states.get(ancestor);
			if (state != null && state.deleted()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Kein eigener Inhalt darf inhaltlich zu neu sein: Sonst hielte dieser Rechner die Einheit für lesbar, die anderen
	 * sperrten sie. Löschungen haben keinen Inhalt.
	 *
	 * @throws LockedUnitException   mit SYS-009, wenn die Prüfung anschlägt
	 * @throws IllegalStateException wenn die Prüfung selbst scheitert: ein Programmfehler, kein neueres Format
	 */
	private static void requireOwnContent(List<Change> changes, ContentGate gate) {
		for (Change change : changes) {
			if (change.deleted()) {
				continue;
			}
			UnitKey unit = change.unit();
			boolean newer;
			try {
				newer = gate.isNewer(unit, change.payload());
			} catch (RuntimeException e) {
				LOG.log(Level.SEVERE, "Die Inhaltsprüfung von " + unit.relativePath() + " ist gescheitert", e);
				throw new IllegalStateException("Die Inhaltsprüfung von " + unit.relativePath()
						+ " ist gescheitert. Es wurde nichts gespeichert.", e);
			}
			if (newer) {
				throw LockedUnitException.newerFormat(unit);
			}
		}
	}

	/**
	 * Setzt an den Änderungen von Buchungs-Einheiten die Umzüge dieses Speichervorgangs (KARV-93): Fällt eine Buchungs-ID
	 * in einer Einheit weg (der lesbare, lebende Stand im Bestand trägt sie, die Änderung nicht mehr: Löschung oder andere
	 * Buchung) und taucht sie in einer anderen Einheit derselben Vorstellung auf (die Änderung trägt sie, der Stand im
	 * Bestand nicht), bekommt die Quelle {@code movedTo} und das Ziel {@code movedFrom}. Sind es je ID mehrere Quellen oder
	 * Ziele (das verhindert sonst die Prüfung der Vorstellung), werden sie nach Pfad der Reihe nach zugeordnet, mit einer
	 * Warnung im Log.
	 */
	/** Eine Buchung in einer Vorstellung, für die Zuordnung von Wegfall und Auftauchen in {@link #withMoves}. */
	private record MoveKey(String presentationId, String bookingId) {
	}

	private static List<Change> withMoves(Map<UnitKey, UnitSnapshot> baseline, List<Change> changes) {
		// je Vorstellung und Buchungs-ID die Einheiten, aus denen sie wegfällt bzw. in denen sie auftaucht
		Map<MoveKey, List<UnitKey>> left = new HashMap<>();
		Map<MoveKey, List<UnitKey>> arrived = new HashMap<>();
		for (Change change : changes) {
			UnitKey unit = change.unit();
			if (unit.type() != UnitType.BOOKING) {
				continue;
			}
			UnitSnapshot known = baseline.get(unit);
			String before = known != null && known.isLive() ? ConflictInput.bookingIdOf(known.payload()) : null;
			String after = change.deleted() ? null : ConflictInput.bookingIdOf(change.payload());
			if (before != null && !before.equals(after)) {
				left.computeIfAbsent(new MoveKey(unit.presentationId(), before), key -> new ArrayList<>()).add(unit);
			}
			if (after != null && !after.equals(before)) {
				arrived.computeIfAbsent(new MoveKey(unit.presentationId(), after), key -> new ArrayList<>()).add(unit);
			}
		}
		Map<UnitKey, VersionMarks> marks = new HashMap<>();
		Comparator<UnitKey> byPath = Comparator.comparing(UnitKey::relativePath);
		left.forEach((key, found) -> {
			List<UnitKey> targets = arrived.get(key);
			if (targets == null) {
				return;
			}
			// erst hier lesen (teuer, und meist ist kein Umzug dabei): nur ein lesbarer Stand im Bestand ist eine Quelle
			List<UnitKey> sources = found.stream().filter(unit -> isReadable(baseline.get(unit)))
					.collect(Collectors.toCollection(ArrayList::new));
			if (sources.isEmpty()) {
				return;
			}
			if (sources.size() > 1 || targets.size() > 1) {
				LOG.warning("Der Umzug der Buchung " + key.bookingId() + " ist mehrdeutig (" + sources.size()
						+ " Quellen, " + targets.size() + " Ziele); zugeordnet nach Pfad.");
			}
			sources.sort(byPath);
			targets.sort(byPath);
			for (int i = 0; i < Math.min(sources.size(), targets.size()); i++) {
				UnitKey source = sources.get(i);
				UnitKey target = targets.get(i);
				marks.merge(source, VersionMarks.NONE.withMovedTo(target), (old, add) -> old.withMovedTo(target));
				marks.merge(target, VersionMarks.NONE.withMovedFrom(source), (old, add) -> old.withMovedFrom(source));
			}
		});
		if (marks.isEmpty()) {
			return changes;
		}
		List<Change> marked = new ArrayList<>(changes.size());
		for (Change change : changes) {
			VersionMarks mark = marks.get(change.unit());
			marked.add(mark == null ? change : change.withMarks(mark));
		}
		return marked;
	}

	/** Die Löschung einer Einheit auf ihrer aktuellen Version (für das Löschen eines ganzen Events oder einer Halle). */
	static Change deletion(UnitSnapshot baseline) {
		return new Change(baseline.unit(), List.of(baseline.version()), null, true);
	}

	/**
	 * Ob eine übergeordnete Einheit (Eltern-Einheit, deren Eltern-Einheit …) im Bestand gelöscht ist und mit diesem Speichern
	 * wiederbelebt wird, weil die Einheit unter ihr noch zu räumen ist (siehe {@link #changes}).
	 */
	private static boolean revivesParent(Map<UnitKey, UnitSnapshot> baseline, UnitKey unit) {
		for (UnitKey ancestor : unit.ancestors()) {
			UnitSnapshot known = baseline.get(ancestor);
			if (known != null && known.deleted()) {
				return true;
			}
		}
		return false;
	}

	/** Ob die Eltern-Einheit angezeigt wird; ein Event-Kopf oder eine Halle hat keine. */
	private static boolean parentShown(UnitKey unit, Set<UnitKey> shown) {
		UnitKey parent = unit.parent();
		return parent != null && shown.contains(parent);
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

	/** Der Text (SYS-008 bzw. SYS-009) mit der Bezeichnung der Einheit, wenn sie gesperrt ist; sonst {@code null}. */
	static String lockMessage(UnitKey unit, Status status) {
		return LockedUnitException.lockMessage(unit, status);
	}

	/** Ob die Kette gesperrt ist (beschädigt oder neueres Format): ohne den Text und ohne die Bezeichnung zu ermitteln. */
	private static boolean isLocked(Status status) {
		return status == Status.LOCKED_DAMAGED || status == Status.LOCKED_FORMAT;
	}

	/**
	 * Der Zustand, mit dem eine gesperrte Einheit im Modell fehlt (KARV-135), oder {@code null}, wenn sie nicht so fehlt:
	 * gesperrt ohne Version (ihr Zustand), oder ihr aktueller Stand ist inhaltlich zu neu ({@link UnitSnapshot#contentLocked}).
	 * Im zweiten Fall immer {@code LOCKED_FORMAT}, bei einem ausstehenden Zurücksetzen hinter einer beschädigten Version
	 * {@code LOCKED_DAMAGED}, nie einfach der Zustand des Stands: Ziele eines Wiederherstellens kommen aus
	 * {@code UnitSnapshot.of(unit, version)} mit {@code OK}, und dazu gäbe es keine Sperrmeldung.
	 */
	static Status missingLock(UnitSnapshot snapshot) {
		if (snapshot.contentLocked()) {
			return snapshot.status() == Status.LOCKED_DAMAGED ? Status.LOCKED_DAMAGED : Status.LOCKED_FORMAT;
		}
		return snapshot.version() == null && isLocked(snapshot.status()) ? snapshot.status() : null;
	}

	/** Der Schlüssel einer Buchung: auf dem Sitz, wenn sie einen hat und nicht "ohne Platz" ist, sonst über ihre Kennung. */
	static UnitKey bookingKey(String eventId, String presentationId, Booking booking) {
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
		Map<UnitKey, Status> locked = new LinkedHashMap<>();
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
				Event event = read(snapshot, Event.class, warnings, locked, unreadableEvent(unit.eventId()),
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
			Presentation presentation = read(snapshot, Presentation.class, warnings, locked,
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
			Booking booking = read(snapshot, Booking.class, warnings, locked, unreadableBooking(presentation.getName()),
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
		return new Assembly<>(result, warnings, locked, shown);
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
		Map<UnitKey, Status> locked = new LinkedHashMap<>();
		List<Hall> halls = new ArrayList<>();
		Set<UnitKey> shown = new HashSet<>();
		for (UnitSnapshot snapshot : snapshots) {
			if (snapshot.unit().type() == UnitType.HALL) {
				String hallId = snapshot.unit().hallId();
				Hall hall = read(snapshot, Hall.class, warnings, locked, unreadableHall(hallId),
						idMismatch(hallId, Hall::getId));
				if (hall != null) {
					halls.add(hall);
					shown.add(snapshot.unit());
				}
			}
		}
		halls.sort(HALL_ORDER);
		return new Assembly<>(halls, warnings, locked, shown);
	}

	/**
	 * Liest die Einheit, wenn sie sichtbar ist. Ergibt {@code null} für eine gelöschte Einheit, für einen Ordner ohne
	 * eigene Dateien (keine Version, Status {@code OK}: die Einheit gibt es nicht) und, mit der Warnung
	 * {@code unreadable}, für eine Einheit ohne lesbare Version oder mit Inhalt, den Jackson nicht liest. Eine
	 * gesperrte Einheit mit lesbarer Version wird geladen und kommt mit ihrem Zustand in {@code locked} (SYS-008 bzw.
	 * SYS-009); eine ohne lesbare Version fehlt, kommt aber ebenso in {@code locked}, damit sie unter den gesperrten
	 * Einträgen steht (sonst wirkte etwa ihr Sitz frei, obwohl Speichern darauf abgelehnt wird). Ist der aktuelle Stand
	 * inhaltlich zu neu (KARV-135), fehlt die Einheit ebenso und kommt nach {@link #missingLock} in {@code locked}, ohne
	 * Warnung: Sie ist gesperrt, nicht unlesbar. Ergibt {@code mismatchOf} einen Text, passt der Inhalt nicht zum Ordner
	 * der Einheit (die ID zeigt woandershin): Die Einheit wird wie eine unlesbare behandelt.
	 */
	private static <T> T read(UnitSnapshot snapshot, Class<T> type, List<String> warnings, Map<UnitKey, Status> locked,
			String unreadable, Function<T, String> mismatchOf) {
		if (snapshot.contentLocked()) {
			locked.put(snapshot.unit(), missingLock(snapshot));
			return null;
		}
		if (snapshot.version() == null) {
			if (snapshot.status() != Status.OK) {
				warnings.add(unreadable);
			}
			if (isLocked(snapshot.status())) {
				locked.put(snapshot.unit(), snapshot.status());
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
		if (isLocked(snapshot.status())) {
			locked.put(snapshot.unit(), snapshot.status());
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
	 * Der Stand einer Einheit, wie dieses Programm ihn aus der Datei macht (Rundlauf): gelesen ({@link #readUnit}) und
	 * geformt wie in {@link #decompose}: der Event-Kopf ohne Vorstellungen, die Vorstellung ohne Buchungen, Buchung und Halle
	 * normalisiert. Felder, die das Modell beim Lesen verwirft (etwa aus einer anderen Programmversion), fehlen darin, Felder,
	 * die der Datei fehlen, stehen darin mit dem Wert des Modells, und unbekannte Felder und Rückfall-Enums sind erhalten
	 * (KARV-128, KARV-133). Nur so ist ein unverändert geladenes Objekt gleich seinem Stand. Gilt für jeden Snapshot, auch
	 * für fremde.
	 *
	 * @return {@code null}, wenn sich die Einheit nicht lesen lässt (siehe {@link #readUnit}); sie gilt dann als verändert
	 */
	static ObjectNode comparable(UnitSnapshot snapshot) {
		Object value = readUnit(snapshot, modelClass(snapshot.unit().type()));
		if (value == null) {
			return null;
		}
		ObjectNode node = normalize(value);
		switch (snapshot.unit().type()) {
		case EVENT -> node.remove(PRESENTATIONS);
		case PRESENTATION -> node.remove(BOOKINGS);
		case BOOKING, HALL -> {
		}
		}
		return node;
	}

	/**
	 * Ob die Einheit im Modell ({@code model}, wie {@link #decompose} sie bildet) dem Stand {@code snapshot} entspricht:
	 * erst roh verglichen, nur bei Ungleichheit mit dem Rundlauf ({@link #comparable}). So kostet der übliche Fall (gleiche
	 * Version) nichts zusätzlich. Ein unlesbarer Stand ist nie gleich, es sei denn, er ist roh gleich.
	 */
	static boolean sameAsModel(ObjectNode model, UnitSnapshot snapshot) {
		return snapshot.payload() != null && (snapshot.payload().equals(model) || model.equals(comparable(snapshot)));
	}
	/**
	 * Liest eine einzelne Einheit nach den Regeln des Zusammensetzens ({@link #read}, ID-Prüfung eingeschlossen), aber
	 * ohne Warnung. Der Payload bleibt unverändert.
	 *
	 * @param type die Modellklasse zur Art der Einheit (Event, Presentation, Booking oder Hall)
	 * @return ein neues Objekt, oder {@code null}, wenn die Einheit gelöscht ist, keine Version hat, sich nicht lesen
	 *         lässt oder ihr aktueller Stand inhaltlich zu neu ist ({@link UnitSnapshot#contentLocked}, KARV-135: Laden
	 *         und Einarbeiten nehmen sie damit heraus)
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
		if (snapshot.contentLocked() || !snapshot.isLive() || snapshot.payload() == null) {
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
