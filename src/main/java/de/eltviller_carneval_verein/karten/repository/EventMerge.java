package de.eltviller_carneval_verein.karten.repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.BookingSync;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.repository.UnitMapper.Assembly;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.UnitSnapshot;
import de.eltviller_carneval_verein.karten.store.UnitType;

/**
 * Arbeitet fremde Stände von Kopf, Vorstellungen und Buchungen in ein vorhandenes {@link Event} ein, so dass die Objekte
 * ihre Identität behalten (Spec KARV-85, "Einarbeiten"). Die Klasse ist zustandslos: Derselbe {@link Plan} führt das
 * Event im Cache und, über {@link ForeignBatch#replayOn}, eine Kopie für den Änderungsmelder nach. Was dabei übernommen
 * wurde, meldet sie einem {@link Sink}; Vergleichsstand und Listener kennt sie nicht.
 * <p>
 * Ob ein Stand eingearbeitet werden darf, hat vorher der Aufrufer entschieden ({@link ForeignDecision}). Ein gelöschter
 * oder unlesbarer Kopf und ein neues Event tauschen das Objekt im Cache aus; das ist Sache des Repositorys, hier gibt es
 * dafür nur {@link #assembleEvent}.
 */
final class EventMerge {

	/** Wohin das Einarbeiten meldet, was es übernommen hat. */
	interface Sink {

		/** Die Einheit hat diesen Stand; {@code shown}: sie ist ins Modell eingegangen (sonst wird sie nie gelöscht). */
		default void adopted(UnitSnapshot snapshot, boolean shown) {
		}

		/**
		 * Die Buchung hat im Modell keinen Platz (ihr Sitz fehlt in der Bestuhlung der Vorstellung, oder die Vorstellung
		 * fehlt im Modell): Modell und Vergleichsstand bleiben unberührt, sie wartet, bis Halle oder Vorstellung mit dem
		 * Sitz da sind.
		 */
		default void held(UnitSnapshot snapshot) {
		}

		/**
		 * Die Vorstellung hat das Modell verlassen (oder fehlte schon und ist nun gelöscht bzw. unlesbar); was unter ihr
		 * angezeigt war, ist es nicht mehr.
		 */
		default void left(UnitKey presentation) {
		}

		/** Eine Warnung für den Nutzer (unlesbare oder gesperrte Einheit). */
		default void warning(String text) {
		}
	}

	/** Meldet nichts: für das Nachspielen auf einer Kopie. */
	static final Sink NONE = new Sink() {
	};

	/** Präfix des Ordnernamens einer Buchung ohne Platz (siehe {@link UnitKey}). */
	private static final String UNASSIGNED_PREFIX = "_";

	/**
	 * Was in ein Event eingearbeitet wird. Ein Plan wirkt auf das Event im Cache und auf jede Kopie gleich: Auch die
	 * Kennungen leerer Platzhalter (geleert oder vom Abgleich neu angelegt) entstehen nur beim ersten Einarbeiten und
	 * gelten dann für jede Kopie, sonst wiche die Kopie für den Änderungsmelder ab.
	 */
	static final class Plan {

		private final List<UnitSnapshot> units;
		private final List<UnitSnapshot> index;
		private final Function<String, Hall> hallLookup;

		/** Die Vorstellungen, die gelöscht oder unlesbar sind: Ihre Buchungen warten auf keinen Platz. */
		private final Set<UnitKey> goneParents;

		/** Die Kennungen der Platzhalter je Vorstellung und Sitz ({@code <presentationId>/<seatId>}). */
		private final Map<String, String> placeholderIds;

		/** Ein Plan, in dem keine Vorstellung gelöscht oder unlesbar ist (siehe den Konstruktor mit {@code goneParents}). */
		Plan(List<UnitSnapshot> units, Collection<UnitSnapshot> index, Function<String, Hall> hallLookup) {
			this(units, index, hallLookup, Set.of());
		}

		/**
		 * @param units       die einzuarbeitenden Stände (eines Events); eingearbeitet wird in der Reihenfolge Kopf,
		 *                    Vorstellungen, Buchungen
		 * @param index       die Stände des ganzen Events aus dem Index, aus denen eine neue Vorstellung samt ihren
		 *                    Buchungen entsteht; leer, wenn keine neu ist
		 * @param hallLookup  die Halle zur ID, {@code null}, wenn es sie nicht gibt
		 * @param goneParents die Vorstellungen, die gelöscht oder unlesbar sind: Eine Buchung darunter, deren Vorstellung
		 *                    nicht im Modell steht, geht nur in den Vergleichsstand. Sonst wartet sie auf ihre Vorstellung.
		 */
		Plan(List<UnitSnapshot> units, Collection<UnitSnapshot> index, Function<String, Hall> hallLookup,
				Set<UnitKey> goneParents) {
			this(sorted(units), List.copyOf(index), hallLookup, Set.copyOf(goneParents), new HashMap<>());
		}

		private Plan(List<UnitSnapshot> units, List<UnitSnapshot> index, Function<String, Hall> hallLookup,
				Set<UnitKey> goneParents, Map<String, String> placeholderIds) {
			this.units = units;
			this.index = index;
			this.hallLookup = hallLookup;
			this.goneParents = goneParents;
			this.placeholderIds = placeholderIds;
		}

		/** Die einzuarbeitenden Stände in der Reihenfolge des Einarbeitens. */
		List<UnitSnapshot> units() {
			return units;
		}

		/** Derselbe Plan, beschränkt auf die genannten Einheiten (mit denselben Kennungen der Platzhalter). */
		Plan restrictedTo(Collection<UnitKey> keep) {
			Set<UnitKey> kept = new HashSet<>(keep);
			List<UnitSnapshot> remaining = units.stream().filter(s -> kept.contains(s.unit())).toList();
			return new Plan(remaining, index, hallLookup, goneParents, placeholderIds);
		}

		/** Die neue Kennung des geleerten Platzhalters auf diesem Sitz, beim ersten Mal ausgewürfelt. */
		private String clearedId(String presentationId, String seatId) {
			return placeholderIds.computeIfAbsent(presentationId + "/" + seatId, k -> UUID.randomUUID().toString());
		}

		/**
		 * Gibt den Platzhaltern, die der Abgleich eben neu angelegt hat, dieselben Kennungen wie beim ersten Einarbeiten;
		 * beim ersten Einarbeiten merkt er sich ihre Kennungen.
		 */
		private void fixCreatedIds(Presentation presentation, Set<Booking> before) {
			for (Booking booking : presentation.getBookings() != null ? presentation.getBookings() : List.<Booking>of()) {
				if (before.contains(booking) || booking.hasData() || booking.getSeatId() == null) {
					continue;
				}
				String key = presentation.getId() + "/" + booking.getSeatId();
				String known = placeholderIds.get(key);
				if (known != null) {
					booking.setId(known);
				} else {
					placeholderIds.put(key, booking.getId());
				}
			}
		}

		private static List<UnitSnapshot> sorted(List<UnitSnapshot> units) {
			List<UnitSnapshot> sorted = new ArrayList<>(units);
			sorted.sort(MERGE_ORDER);
			return List.copyOf(sorted);
		}
	}

	/** Die Reihenfolge des Einarbeitens: Kopf, Vorstellungen, Buchungen (wie beim Zerlegen). */
	static final Comparator<UnitSnapshot> MERGE_ORDER = Comparator.comparingInt(s -> rank(s.unit().type()));

	private static int rank(UnitType type) {
		return switch (type) {
		case EVENT -> 0;
		case PRESENTATION -> 1;
		case BOOKING -> 2;
		case HALL -> throw new IllegalArgumentException("Eine Halle gehört zu keinem Event.");
		};
	}

	private EventMerge() {
	}

	/**
	 * Arbeitet die Stände des Plans in das Event ein (Regeln der Spec, Abschnitt "Einarbeiten"):
	 * <ul>
	 * <li>Kopf: Felder kopieren, die Vorstellungen bleiben. Er muss lesbar sein.</li>
	 * <li>Vorstellung geändert: Felder kopieren, bei anderer Halle oder Bestuhlung danach abgleichen. Neu oder
	 * wiederbelebt: mit ihren sichtbaren Buchungen aus dem Index zusammensetzen, einsortieren wie beim Laden, abgleichen.
	 * Gelöscht oder unlesbar: aus der Liste.</li>
	 * <li>Buchung auf einem Sitz: Die Buchung, die dort steht (bei freiem Sitz der leere Platzhalter), bekommt alle
	 * Felder, auch die Kennung. Steht dort keine, wird sie angehängt, wenn die Bestuhlung den Sitz kennt; sonst bleibt
	 * alles unberührt und sie wartet ({@link Sink#held}). Gelöscht oder unlesbar: nur die Kundendaten leeren, der
	 * Platzhalter bekommt eine neue Kennung.</li>
	 * <li>Buchung ohne Platz: anhand der Kennung aktualisieren, sonst anhängen; gelöscht oder unlesbar: entfernen.</li>
	 * <li>Buchung unter einer Vorstellung, die nicht im Modell steht: Ist die Vorstellung noch nicht da, wartet eine lesbare
	 * Buchung ({@link Sink#held}) und wird mit ihr sichtbar. Gelöscht oder unlesbar, oder ist die Vorstellung gelöscht oder
	 * unlesbar ({@link Plan}): nur in den Vergleichsstand.</li>
	 * </ul>
	 * Scheitert das Einarbeiten einer Einheit, ist das Modell für diese Einheit wieder wie vorher (die Einheiten davor
	 * bleiben eingearbeitet und gemeldet), dann geht die Ausnahme weiter.
	 *
	 * @throws IllegalArgumentException wenn ein Stand zu einem anderen Event gehört oder der Kopf nicht lesbar ist
	 */
	static void apply(Event event, Plan plan, Sink sink) {
		Set<UnitKey> assembled = new HashSet<>();
		for (UnitSnapshot snapshot : plan.units) {
			UnitKey unit = snapshot.unit();
			if (!event.getId().equals(unit.eventId())) {
				throw new IllegalArgumentException(unit.relativePath() + " gehört nicht zum Event " + event.getId());
			}
			// eine Buchung unter einer gerade zusammengesetzten Vorstellung kam schon mit ihr aus dem Index
			if (unit.type() == UnitType.BOOKING && assembled.contains(presentationKey(unit))) {
				continue;
			}
			Undo undo = new Undo(event, unit);
			try {
				switch (unit.type()) {
				case EVENT -> mergeHead(event, snapshot, sink);
				case PRESENTATION -> {
					if (mergePresentation(event, snapshot, plan, sink)) {
						assembled.add(unit);
					}
				}
				case BOOKING -> mergeBooking(event, snapshot, plan, sink);
				case HALL -> throw new IllegalArgumentException("Eine Halle gehört zu keinem Event.");
				}
			} catch (RuntimeException e) {
				undo.restore();
				throw e;
			}
		}
	}

	// Jede Einarbeitung ändert zuerst das Modell und meldet erst danach (nichts Gemeldetes wird zurückgesetzt)

	private static void mergeHead(Event event, UnitSnapshot snapshot, Sink sink) {
		Event state = UnitMapper.readUnit(snapshot, Event.class);
		if (state == null) {
			throw new IllegalArgumentException(snapshot.unit().relativePath() + " lässt sich nicht einarbeiten.");
		}
		event.copyFrom(state);
		sink.adopted(snapshot, true);
		warnIfLocked(snapshot, sink);
	}

	/** @return ob die Vorstellung neu zusammengesetzt wurde (ihre Buchungen kamen mit ihr) */
	private static boolean mergePresentation(Event event, UnitSnapshot snapshot, Plan plan, Sink sink) {
		UnitKey unit = snapshot.unit();
		Presentation existing = presentation(event, unit.presentationId());
		Presentation state = UnitMapper.readUnit(snapshot, Presentation.class);
		if (state == null) {
			if (existing != null) {
				event.getPresentations().remove(existing);
			}
			if (!UnitMapper.isAbsent(snapshot)) {
				sink.warning(UnitMapper.unreadablePresentation(unit.presentationId(), event.getName()));
			}
			// auch ohne Vorstellung im Modell (der Nutzer hatte sie schon entfernt): Was unter ihr angezeigt war, ist weg
			sink.left(unit);
			sink.adopted(snapshot, false);
			return false;
		}
		if (existing != null) {
			boolean replanned = changesPlan(existing, state);
			existing.copyFrom(state);
			if (replanned) {
				resync(existing, plan);
			}
			sink.adopted(snapshot, true);
			warnIfLocked(snapshot, sink);
			return false;
		}

		// Buchungen, die nicht zur Bestuhlung passen, setzte der Abgleich um; sie warten wie jede solche Buchung
		List<UnitSnapshot> fitting = new ArrayList<>();
		List<UnitSnapshot> waiting = new ArrayList<>();
		for (UnitSnapshot below : plan.index) {
			if (below.unit().type() == UnitType.BOOKING && inSubtree(below.unit(), unit)) {
				Booking booking = UnitMapper.readUnit(below, Booking.class);
				if (booking != null && !fitsPlan(below.unit(), booking, state, plan.hallLookup)) {
					waiting.add(below);
				} else {
					fitting.add(below);
				}
			}
		}
		Presentation added = UnitMapper.assemblePresentation(snapshot, fitting);
		insertSorted(event, added);
		resync(added, plan);
		sink.adopted(snapshot, true);
		warnIfLocked(snapshot, sink);
		for (UnitSnapshot below : fitting) {
			boolean readable = UnitMapper.readUnit(below, Booking.class) != null;
			if (!readable && !UnitMapper.isAbsent(below)) {
				sink.warning(UnitMapper.unreadableBooking(added.getName()));
			}
			sink.adopted(below, readable);
			if (readable) {
				warnIfLocked(below, sink);
			}
		}
		waiting.forEach(sink::held);
		return true;
	}

	private static void mergeBooking(Event event, UnitSnapshot snapshot, Plan plan, Sink sink) {
		UnitKey unit = snapshot.unit();
		Presentation presentation = presentation(event, unit.presentationId());
		Booking state = UnitMapper.readUnit(snapshot, Booking.class);
		if (presentation == null) {
			if (state == null || plan.goneParents.contains(presentationKey(unit))) {
				// gelöscht oder unlesbar, oder ihre Vorstellung ist es: Es gibt nichts, was auf einen Platz wartet
				sink.adopted(snapshot, false);
			} else {
				// ihre Vorstellung ist noch nicht da: sichtbar wird sie mit ihr, bis dahin steht sie nicht im Vergleichsstand
				sink.held(snapshot);
			}
			return;
		}
		Booking current = bookingAt(presentation, unit);
		if (state == null) {
			if (current != null && isUnassigned(unit)) {
				presentation.getBookings().remove(current);
			} else if (current != null && current.hasData()) {
				// die Momentaufnahme des Sitzes bleibt; ein späterer Verkauf trägt nicht die Kennung des alten Käufers
				current.clearCustomerData();
				current.setId(plan.clearedId(presentation.getId(), unit.bookingDir()));
			}
			if (!UnitMapper.isAbsent(snapshot)) {
				sink.warning(UnitMapper.unreadableBooking(presentation.getName()));
			}
			sink.adopted(snapshot, false);
			return;
		}
		if (current != null) {
			current.copyFrom(state);
		} else if (fitsPlan(unit, state, presentation, plan.hallLookup)) {
			if (presentation.getBookings() == null) {
				presentation.setBookings(new ArrayList<>());
			}
			state.setParent(presentation);
			presentation.getBookings().add(state);
		} else {
			// der Abgleich setzte sie um ("ohne Platz" bzw. zurück auf ihren alten Sitz), und das nächste Speichern
			// überschriebe den fremden Stand
			sink.held(snapshot);
			return;
		}
		sink.adopted(snapshot, true);
		warnIfLocked(snapshot, sink);
	}

	/**
	 * Ob eine fremde Buchung beim Einarbeiten einen Platz im Modell findet ({@link #apply} sie also nicht zurückhält):
	 * gelöscht oder unlesbar, im Modell schon vorhanden (auf ihrem Sitz bzw. mit ihrer Kennung), passend zur Bestuhlung
	 * ({@link #fitsPlan}), oder ihre Vorstellung steht nicht im Modell, kommt aber im selben Schritt. Eine lesbare Buchung
	 * ohne Vorstellung im Modell hat dagegen keinen Platz. {@code presentationState} ist der Stand der Vorstellung,
	 * wenn er im selben Schritt eingearbeitet wird (sonst {@code null}): Wechselt er die Bestuhlung, zählt die neue.
	 */
	static boolean hasPlace(Event event, UnitSnapshot booking, UnitSnapshot presentationState,
			Function<String, Hall> hallLookup) {
		UnitKey unit = booking.unit();
		Presentation presentation = presentation(event, unit.presentationId());
		Booking state = UnitMapper.readUnit(booking, Booking.class);
		if (state == null) {
			return true;
		}
		Presentation presentationNow = presentationState == null ? null
				: UnitMapper.readUnit(presentationState, Presentation.class);
		if (presentationState != null && presentationNow == null) {
			// die Vorstellung verlässt das Modell: die Buchung geht nur in den Vergleichsstand
			return true;
		}
		if (presentation == null) {
			// kommt die Vorstellung im selben Schritt, kommt die Buchung mit ihr; sonst wartet sie auf die Vorstellung
			return presentationState != null;
		}
		if (presentationNow != null && changesPlan(presentation, presentationNow)) {
			return fitsPlan(unit, state, presentationNow, hallLookup);
		}
		return bookingAt(presentation, unit) != null || fitsPlan(unit, state, presentation, hallLookup);
	}

	/**
	 * Ob die Buchung zur Bestuhlung der Vorstellung passt: auf einem Sitz, den die Bestuhlung kennt; ohne Platz, wenn sie
	 * keinen Sitz nennt, den die Bestuhlung kennt (Schritt 1 des Abgleichs setzte sie sonst dorthin zurück). Eine
	 * unpassende stammt aus einer anderen Bestuhlung als der im Modell; meist ist die Halle oder die Vorstellung nur noch
	 * nicht da.
	 * <p>
	 * Nicht abgedeckt ist Schritt 2 des Abgleichs ({@link BookingSync}): Eine Buchung ohne Platz, deren Reihe, Tisch und
	 * Sitznummer einen im Modell freien Sitz treffen, setzt er dorthin. Das kommt nur vor, wenn der Sitz bei dem Rechner,
	 * der sie geschrieben hat, belegt war und im Modell (noch) frei ist; ob er frei ist, hängt an den übrigen Buchungen im
	 * Modell, die hier nicht geprüft werden.
	 */
	private static boolean fitsPlan(UnitKey unit, Booking state, Presentation presentation,
			Function<String, Hall> hallLookup) {
		SeatingPlan seating = planOf(presentation, hallLookup);
		if (isUnassigned(unit)) {
			return state.getSeatId() == null || seating == null || seating.findSeat(state.getSeatId()) == null;
		}
		return seating != null && seating.findSeat(unit.bookingDir()) != null;
	}

	/**
	 * Setzt die Events aus den Ständen zusammen ({@link UnitMapper#assembleEvents}) und gleicht ihre Vorstellungen mit der
	 * Bestuhlung ab, wie beim Vorbereiten eines geladenen Events für die Anzeige. Lesbare Buchungen, die nicht zur
	 * Bestuhlung ihrer Vorstellung passen ({@link #fitsPlan}), bleiben draußen und kommen in {@code waiting}: Der Abgleich
	 * setzte sie sonst um, und das nächste Speichern überschriebe den fremden Stand. Sie sind dann nicht angezeigt.
	 */
	static Assembly<Event> assembleEvent(Collection<UnitSnapshot> snapshots, Function<String, Hall> hallLookup,
			List<UnitSnapshot> waiting) {
		Map<UnitKey, Presentation> presentations = new HashMap<>();
		for (UnitSnapshot snapshot : snapshots) {
			if (snapshot.unit().type() == UnitType.PRESENTATION) {
				Presentation presentation = UnitMapper.readUnit(snapshot, Presentation.class);
				if (presentation != null) {
					presentations.put(snapshot.unit(), presentation);
				}
			}
		}
		List<UnitSnapshot> fitting = new ArrayList<>();
		for (UnitSnapshot snapshot : snapshots) {
			UnitKey unit = snapshot.unit();
			Presentation presentation = unit.type() == UnitType.BOOKING ? presentations.get(presentationKey(unit)) : null;
			Booking booking = presentation != null ? UnitMapper.readUnit(snapshot, Booking.class) : null;
			if (booking != null && !fitsPlan(unit, booking, presentation, hallLookup)) {
				waiting.add(snapshot);
			} else {
				fitting.add(snapshot);
			}
		}
		Assembly<Event> assembly = UnitMapper.assembleEvents(fitting);
		for (Event event : assembly.items()) {
			for (Presentation presentation : event.getPresentations()) {
				BookingSync.sync(presentation, planOf(presentation, hallLookup));
			}
		}
		return assembly;
	}

	/** Gleicht die Buchungen mit der Bestuhlung der Vorstellung ab ({@link BookingSync#sync}), mit festen Platzhalter-Kennungen. */
	private static void resync(Presentation presentation, Plan plan) {
		Set<Booking> before = Collections.newSetFromMap(new IdentityHashMap<>());
		if (presentation.getBookings() != null) {
			before.addAll(presentation.getBookings());
		}
		BookingSync.sync(presentation, planOf(presentation, plan.hallLookup));
		plan.fixCreatedIds(presentation, before);
	}

	private static SeatingPlan planOf(Presentation presentation, Function<String, Hall> hallLookup) {
		Hall hall = presentation.getHallId() != null ? hallLookup.apply(presentation.getHallId()) : null;
		return hall != null ? hall.findSeatingPlan(presentation.getSeatingPlanId()) : null;
	}

	private static boolean changesPlan(Presentation existing, Presentation state) {
		return !Objects.equals(existing.getHallId(), state.getHallId())
				|| !Objects.equals(existing.getSeatingPlanId(), state.getSeatingPlanId());
	}

	/** Fügt die Vorstellung vor der ersten ein, die beim Laden nach ihr käme; die übrige Reihenfolge bleibt. */
	private static void insertSorted(Event event, Presentation added) {
		if (event.getPresentations() == null) {
			event.setPresentations(new ArrayList<>());
		}
		UnitMapper.insertSorted(event.getPresentations(), added, UnitMapper.PRESENTATION_ORDER);
		added.setParent(event);
	}

	private static void warnIfLocked(UnitSnapshot snapshot, Sink sink) {
		String locked = UnitMapper.lockMessage(snapshot.unit(), snapshot.status());
		if (locked != null) {
			sink.warning(locked);
		}
	}

	/**
	 * Was das Einarbeiten einer Einheit am Modell ändern kann: Kopf, Liste der Vorstellungen und die betroffene Vorstellung
	 * samt Buchungsliste und den Feldern der Buchungen, die es ändern kann (bei einer Vorstellung alle, die der Abgleich
	 * anfasst, bei einer Buchung die auf ihrem Platz). Scheitert es mittendrin, stellt {@link #restore} das wieder her:
	 * Ein halb übernommener fremder Stand gälte sonst als Änderung des Nutzers, und das nächste Speichern schriebe ihn.
	 */
	private static final class Undo {

		private final Event event;
		private final Event head;
		private final List<Presentation> presentations;
		private final Presentation presentation;
		private final Presentation fields;
		private final boolean planMissing;
		private final List<Booking> bookingList;
		private final List<Booking> bookings;
		private final Map<Booking, Booking> states = new IdentityHashMap<>();

		Undo(Event event, UnitKey unit) {
			this.event = event;
			head = new Event(event.getId());
			head.copyFrom(event);
			presentations = event.getPresentations() == null ? null : new ArrayList<>(event.getPresentations());
			presentation = unit.type() == UnitType.EVENT ? null : presentation(event, unit.presentationId());
			if (presentation == null) {
				fields = null;
				planMissing = false;
				bookingList = null;
				bookings = null;
				return;
			}
			fields = new Presentation(presentation.getId());
			fields.copyFrom(presentation);
			planMissing = presentation.isPlanMissing();
			bookingList = presentation.getBookings();
			bookings = bookingList == null ? null : new ArrayList<>(bookingList);
			if (bookings != null && unit.type() == UnitType.PRESENTATION) {
				bookings.forEach(booking -> states.put(booking, booking.copy()));
			} else if (bookings != null) {
				Booking target = bookingAt(presentation, unit);
				if (target != null) {
					states.put(target, target.copy());
				}
			}
		}

		void restore() {
			event.copyFrom(head);
			if (presentations != null && event.getPresentations() != null) {
				event.getPresentations().clear();
				event.getPresentations().addAll(presentations);
			} else {
				event.setPresentations(presentations == null ? null : new ArrayList<>(presentations));
			}
			if (presentation == null) {
				return;
			}
			presentation.copyFrom(fields);
			presentation.setPlanMissing(planMissing);
			if (bookingList != null) {
				bookingList.clear();
				bookingList.addAll(bookings);
			}
			presentation.setBookings(bookingList);
			states.forEach(Booking::copyFrom);
		}
	}

	// Wege durch das Modell, auch für die Entscheidung "blockiert" -->

	/** Die Vorstellung mit dieser ID im Event, sonst {@code null}. */
	static Presentation presentation(Event event, String presentationId) {
		return event.findPresentation(presentationId);
	}

	/**
	 * Die Buchung, die im Modell zur Einheit gehört (Umkehrung des Schlüssels beim Zerlegen): auf dem Sitz die Buchung
	 * mit dieser Sitzkennung, die nicht "ohne Platz" ist (bei mehreren die mit Daten, sonst die erste, also auch ein leerer
	 * Platzhalter); ohne Platz die mit dieser Kennung. Sonst {@code null}.
	 */
	static Booking bookingAt(Presentation presentation, UnitKey unit) {
		if (presentation.getBookings() == null) {
			return null;
		}
		String dir = unit.bookingDir();
		if (isUnassigned(unit)) {
			String id = dir.substring(UNASSIGNED_PREFIX.length());
			for (Booking booking : presentation.getBookings()) {
				if ((booking.getSeatId() == null || booking.isUnassigned()) && id.equals(booking.getId())) {
					return booking;
				}
			}
			return null;
		}
		Booking found = null;
		for (Booking booking : presentation.getBookings()) {
			if (dir.equals(booking.getSeatId()) && !booking.isUnassigned()) {
				if (booking.hasData()) {
					return booking;
				}
				if (found == null) {
					found = booking;
				}
			}
		}
		return found;
	}

	/** Das Objekt im Modell, das zur Einheit gehört (Event, Presentation oder Booking), sonst {@code null}. */
	static Object modelObject(Event event, UnitKey unit) {
		return switch (unit.type()) {
		case EVENT -> event;
		case PRESENTATION -> presentation(event, unit.presentationId());
		case BOOKING -> {
			Presentation presentation = presentation(event, unit.presentationId());
			yield presentation == null ? null : bookingAt(presentation, unit);
		}
		case HALL -> null;
		};
	}

	/** Wie {@link #modelObject}, ohne Treffer das nächste Objekt darüber (für den Hinweis); ohne Event {@code null}. */
	static Object nearestModelObject(Event event, UnitKey unit) {
		if (event == null) {
			return null;
		}
		Object model = modelObject(event, unit);
		if (model == null && unit.type() == UnitType.BOOKING) {
			model = presentation(event, unit.presentationId());
		}
		return model != null ? model : event;
	}

	/**
	 * Ob der fremde Stand den ganzen Teilbaum darunter betrifft: Kopf oder Vorstellung verlassen das Modell (gelöscht oder
	 * unlesbar), das Event wird archiviert (es verschwindet aus den Verkaufsansichten, ein offener Verkauf ginge mit ihm),
	 * oder eine Vorstellung wechselt Halle oder Bestuhlung.
	 */
	static boolean touchesSubtree(Event event, UnitSnapshot snapshot) {
		UnitKey unit = snapshot.unit();
		return switch (unit.type()) {
		case EVENT -> {
			Event state = UnitMapper.readUnit(snapshot, Event.class);
			yield state == null || (state.isArchived() && !event.isArchived());
		}
		case PRESENTATION -> {
			Presentation existing = presentation(event, unit.presentationId());
			Presentation state = UnitMapper.readUnit(snapshot, Presentation.class);
			yield existing != null && (state == null || changesPlan(existing, state));
		}
		case BOOKING, HALL -> false;
		};
	}

	/** Ob die Einheit {@code root} ist oder darunter liegt (unter einem Kopf: alles des Events). */
	static boolean inSubtree(UnitKey unit, UnitKey root) {
		return switch (root.type()) {
		case EVENT -> unit.type() != UnitType.HALL && root.eventId().equals(unit.eventId());
		case PRESENTATION -> unit.type() != UnitType.HALL && unit.type() != UnitType.EVENT
				&& root.eventId().equals(unit.eventId()) && root.presentationId().equals(unit.presentationId());
		case BOOKING, HALL -> root.equals(unit);
		};
	}

	/** Die Objekte im Modell zur Einheit und darunter (Kopf: Event, Vorstellungen, Buchungen; Vorstellung: sie und ihre Buchungen). */
	static List<Object> subtree(Event event, UnitKey root) {
		List<Object> objects = new ArrayList<>();
		switch (root.type()) {
		case EVENT -> {
			objects.add(event);
			for (Presentation presentation : event.getPresentations() != null ? event.getPresentations()
					: List.<Presentation>of()) {
				addWithBookings(objects, presentation);
			}
		}
		case PRESENTATION -> {
			Presentation presentation = presentation(event, root.presentationId());
			if (presentation != null) {
				addWithBookings(objects, presentation);
			}
		}
		case BOOKING, HALL -> {
			Object model = modelObject(event, root);
			if (model != null) {
				objects.add(model);
			}
		}
		}
		return objects;
	}

	private static void addWithBookings(List<Object> objects, Presentation presentation) {
		objects.add(presentation);
		if (presentation.getBookings() != null) {
			objects.addAll(presentation.getBookings());
		}
	}

	private static boolean isUnassigned(UnitKey unit) {
		return unit.bookingDir().startsWith(UNASSIGNED_PREFIX);
	}

	/** Die Vorstellung, zu der die Buchung gehört. */
	static UnitKey presentationKey(UnitKey booking) {
		return UnitKey.presentation(booking.eventId(), booking.presentationId());
	}

	// <-- Wege durch das Modell
}
