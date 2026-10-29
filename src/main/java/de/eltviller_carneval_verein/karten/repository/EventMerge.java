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
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.repository.UnitMapper.Assembly;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.UnitSnapshot;
import de.eltviller_carneval_verein.karten.store.UnitState;
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
 * <p>
 * <b>Die eine Platzierungsregel</b> ({@link #placeable}, Spec KARV-86 U2): Eine Buchung kommt nur ins Modell, wenn ihre
 * Vorstellung dort steht, Halle und Bestuhlung dieser Vorstellung im Modell denen ihres aktuellen Index-Stands
 * entsprechen und die Buchung zur Bestuhlung passt ({@link #fitsPlan}, auch Schritt 2 des Abgleichs). Sonst wartet sie
 * ({@link Sink#held}), bis es passt; nur wenn nichts auf einen Platz wartet (die Buchung ist gelöscht oder unlesbar, oder
 * ihre Vorstellung ist es), geht sie in den Vergleichsstand ({@link #nothingToPlace}). Das Einarbeiten, die neue
 * Vorstellung, {@link #assembleEvent}, {@link #assembleLoaded} und die Vorprüfung im Repository ({@link #waits})
 * entscheiden alle mit dieser Regel.
 */
final class EventMerge {

	/** Wohin das Einarbeiten meldet, was es übernommen hat. */
	interface Sink {

		/** Die Einheit hat diesen Stand; {@code shown}: sie ist ins Modell eingegangen (sonst wird sie nie gelöscht). */
		default void adopted(UnitSnapshot snapshot, boolean shown) {
		}

		/**
		 * Die Buchung ist nicht platzierbar ({@link EventMerge#placeable}: ihre Vorstellung fehlt im Modell oder weicht von ihrem
		 * Index-Stand ab, oder die Buchung passt nicht zur Bestuhlung): Modell und Vergleichsstand bleiben unberührt, sie
		 * wartet, bis Halle oder Vorstellung mit dem Sitz da sind.
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

	/**
	 * Was in ein Event eingearbeitet wird. Ein Plan wirkt auf das Event im Cache und auf jede Kopie gleich: Auch die
	 * Kennungen leerer Platzhalter (geleert oder vom Abgleich neu angelegt) entstehen nur beim ersten Einarbeiten und
	 * gelten dann für jede Kopie, sonst wiche die Kopie für den Änderungsmelder ab.
	 */
	static final class Plan {

		private final List<UnitSnapshot> units;
		private final List<UnitSnapshot> index;
		private final Function<String, Hall> hallLookup;

		/**
		 * Die aktuellen Stände der Vorstellungen, unter denen Buchungen eingearbeitet werden, wie sie der Index hat (auch
		 * gelöschte und unlesbare): Daran misst {@link #placeable} das Modell.
		 */
		private final Map<UnitKey, UnitSnapshot> presentationStates;

		/** Die Kennungen der Platzhalter je Vorstellung und Sitz ({@code <presentationId>/<seatId>}). */
		private final Map<String, String> placeholderIds;

		/**
		 * Die Kennung der Buchung, die auf dem Sitz der gelöschten oder unlesbaren Sitz-Einheit stand (aus ihrem zuletzt
		 * bekannten Stand), solange keine andere Einheit des Vergleichsstands sie trägt: Hat der Abgleich die Buchung auf einen
		 * anderen Sitz gesetzt, finden Löschung und Stornierung sie darüber.
		 */
		private final Map<UnitKey, String> lastIds;

		/**
		 * Ein Plan ohne bekannte Kennungen der Buchungen hinter gelöschten Sitz-Einheiten (siehe den Konstruktor mit
		 * {@code lastIds}).
		 */
		Plan(List<UnitSnapshot> units, Collection<UnitSnapshot> index, Function<String, Hall> hallLookup,
				Map<UnitKey, UnitSnapshot> presentationStates) {
			this(units, index, hallLookup, presentationStates, Map.of());
		}

		/**
		 * @param units              die einzuarbeitenden Stände (eines Events); eingearbeitet wird in der Reihenfolge Kopf,
		 *                           Vorstellungen, Buchungen
		 * @param index              die Stände des ganzen Events aus dem Index, aus denen eine neue Vorstellung samt ihren
		 *                           Buchungen entsteht; leer, wenn keine neu ist
		 * @param hallLookup         die Halle zur ID, {@code null}, wenn es sie nicht gibt
		 * @param presentationStates die aktuellen Index-Stände der Vorstellungen der Buchungen unter {@code units}; fehlt
		 *                           einer, ist die Buchung nicht platzierbar, ist er gelöscht oder unlesbar, geht sie nur in
		 *                           den Vergleichsstand, wenn die Vorstellung nicht (mehr) im Modell steht
		 * @param lastIds            siehe {@link #lastIds}
		 */
		Plan(List<UnitSnapshot> units, Collection<UnitSnapshot> index, Function<String, Hall> hallLookup,
				Map<UnitKey, UnitSnapshot> presentationStates, Map<UnitKey, String> lastIds) {
			this(sorted(units), List.copyOf(index), hallLookup, Map.copyOf(presentationStates), Map.copyOf(lastIds),
					new HashMap<>());
		}

		private Plan(List<UnitSnapshot> units, List<UnitSnapshot> index, Function<String, Hall> hallLookup,
				Map<UnitKey, UnitSnapshot> presentationStates, Map<UnitKey, String> lastIds,
				Map<String, String> placeholderIds) {
			this.units = units;
			this.index = index;
			this.hallLookup = hallLookup;
			this.presentationStates = presentationStates;
			this.lastIds = lastIds;
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
			return new Plan(remaining, index, hallLookup, presentationStates, lastIds, placeholderIds);
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

		/**
		 * Kopf, Vorstellungen, Buchungen; unter den Buchungen zuerst die gelöschten und unlesbaren: Sie räumen ihren Platz,
		 * bevor eine lesbare ihn belegt (eine Buchung, die der Abgleich auf einen anderen Sitz gesetzt hat, gehört dem Sitz,
		 * dessen Einheit sie trägt).
		 */
		private static List<UnitSnapshot> sorted(List<UnitSnapshot> units) {
			List<UnitSnapshot> sorted = new ArrayList<>(units);
			sorted.sort(MERGE_ORDER);
			List<UnitSnapshot> cancelled = new ArrayList<>();
			List<UnitSnapshot> others = new ArrayList<>();
			for (UnitSnapshot snapshot : sorted) {
				(snapshot.unit().type() == UnitType.BOOKING && !UnitMapper.isReadable(snapshot) ? cancelled : others)
						.add(snapshot);
			}
			List<UnitSnapshot> result = new ArrayList<>();
			for (UnitSnapshot snapshot : others) {
				if (snapshot.unit().type() == UnitType.BOOKING && !cancelled.isEmpty()) {
					result.addAll(cancelled);
					cancelled.clear();
				}
				result.add(snapshot);
			}
			result.addAll(cancelled);
			return List.copyOf(result);
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
	 * <li>Buchung auf einem Sitz: Ist sie {@linkplain #placeable platzierbar}, bekommt die Buchung, die dort steht (bei
	 * freiem Sitz der leere Platzhalter), alle Felder, auch die Kennung. Steht dort keine, wird sie angehängt. Sonst bleibt
	 * alles unberührt und sie wartet ({@link Sink#held}). Gelöscht oder unlesbar: nur die Kundendaten leeren, der
	 * Platzhalter bekommt eine neue Kennung.</li>
	 * <li>Buchung ohne Platz: anhand der Kennung aktualisieren, sonst anhängen; gelöscht oder unlesbar: entfernen.</li>
	 * <li>Buchung unter einer Vorstellung, die nicht im Modell steht: Ist die Vorstellung noch nicht da, wartet eine lesbare
	 * Buchung ({@link Sink#held}) und wird mit ihr sichtbar. Gelöscht oder unlesbar, oder ist die Vorstellung gelöscht oder
	 * unlesbar (ihr Stand im {@link Plan}): nur in den Vergleichsstand ({@link #nothingToPlace}).</li>
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
			if (unit.type() == UnitType.BOOKING && assembled.contains(unit.parent())) {
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
			if (replanned) {
				// Stornierungen auf den alten Sitzen zuerst: Nach dem Abgleich fände sie ihre Buchung dort nicht mehr
				for (UnitSnapshot below : plan.units) {
					if (below.unit().type() == UnitType.BOOKING && inSubtree(below.unit(), unit)
							&& UnitMapper.readUnit(below, Booking.class) == null) {
						cancel(existing, below.unit(), plan);
					}
				}
			}
			existing.copyFrom(state);
			if (replanned) {
				resync(existing, plan);
			}
			sink.adopted(snapshot, true);
			warnIfLocked(snapshot, sink);
			return false;
		}

		// Buchungen, die nicht zur Bestuhlung passen, setzte der Abgleich um; sie warten wie jede solche Buchung
		List<UnitSnapshot> all = new ArrayList<>();
		for (UnitSnapshot below : plan.index) {
			if (below.unit().type() == UnitType.BOOKING && inSubtree(below.unit(), unit)) {
				all.add(below);
			}
		}
		List<UnitSnapshot> waiting = placeBelow(snapshot, all, plan.hallLookup, false);
		Set<UnitKey> waitingUnits = new HashSet<>();
		waiting.forEach(s -> waitingUnits.add(s.unit()));
		List<UnitSnapshot> fitting = all.stream().filter(s -> !waitingUnits.contains(s.unit())).toList();
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
		UnitSnapshot indexed = plan.presentationStates.get(unit.parent());
		if (nothingToPlace(event, snapshot, indexed)) {
			// gelöscht oder unlesbar, oder ihre Vorstellung ist es: Es gibt nichts, was auf einen Platz wartet
			sink.adopted(snapshot, false);
			return;
		}
		if (!placeable(event, snapshot, indexed, plan.hallLookup)) {
			// ihre Vorstellung ist noch nicht da oder weicht von ihrem Index-Stand ab, oder die Bestuhlung hat den Sitz noch
			// nicht (der Abgleich setzte sie um, und das nächste Speichern überschriebe den fremden Stand): sichtbar wird sie
			// mit ihnen, bis dahin steht sie nicht im Vergleichsstand
			sink.held(snapshot);
			return;
		}
		Presentation presentation = presentation(event, unit.presentationId());
		Booking state = UnitMapper.readUnit(snapshot, Booking.class);
		Booking current = bookingAt(presentation, unit);
		if (state == null) {
			cancel(presentation, unit, plan);
			if (!UnitMapper.isAbsent(snapshot)) {
				sink.warning(UnitMapper.unreadableBooking(presentation.getName()));
			}
			sink.adopted(snapshot, false);
			return;
		}
		if (current != null) {
			current.copyFrom(state);
		} else {
			if (presentation.getBookings() == null) {
				presentation.setBookings(new ArrayList<>());
			}
			state.setParent(presentation);
			presentation.getBookings().add(state);
		}
		sink.adopted(snapshot, true);
		warnIfLocked(snapshot, sink);
	}

	/**
	 * Nimmt die Buchung der gelöschten oder unlesbaren Einheit aus dem Modell: Eine Buchung ohne Platz wird entfernt, auf
	 * einem Sitz werden nur die Kundendaten geleert (die Momentaufnahme des Sitzes bleibt; ein späterer Verkauf trägt nicht
	 * die Kennung des alten Käufers). Steht auf dem Sitz nichts mehr, weil der Abgleich die Buchung nach einem Wechsel von
	 * Halle oder Bestuhlung versetzt hat, gilt dasselbe für die Buchung mit der Kennung aus dem letzten bekannten Stand
	 * ({@link Plan#lastIds}): Sonst schriebe das nächste Speichern die Stornierung als neuen Verkauf über.
	 */
	private static void cancel(Presentation presentation, UnitKey unit, Plan plan) {
		Booking current = bookingAt(presentation, unit);
		if (current != null && unit.isUnassigned()) {
			presentation.getBookings().remove(current);
			return;
		}
		if (current == null && !unit.isUnassigned()) {
			current = movedBooking(presentation, plan.lastIds.get(unit));
			if (current != null && (current.isUnassigned() || current.getSeatId() == null)) {
				presentation.getBookings().remove(current);
				return;
			}
		}
		if (current != null && current.hasData()) {
			current.clearCustomerData();
			current.setId(plan.clearedId(presentation.getId(), current.getSeatId()));
		}
	}

	/** Die Buchung mit Daten und dieser Kennung in der Vorstellung, sonst {@code null}. */
	private static Booking movedBooking(Presentation presentation, String bookingId) {
		if (bookingId == null || presentation.getBookings() == null) {
			return null;
		}
		for (Booking booking : presentation.getBookings()) {
			if (bookingId.equals(booking.getId()) && booking.hasData()) {
				return booking;
			}
		}
		return null;
	}

	/**
	 * Die eine Regel, ob eine fremde Buchung ins Modell darf (siehe die Klasse): Ihre Vorstellung steht im Modell, Halle
	 * und Bestuhlung der Vorstellung im Modell sind die ihres aktuellen Index-Stands, und die Buchung passt zur Bestuhlung
	 * ({@link #fitsPlan}) oder steht im Modell schon auf ihrem Platz. Eine gelöschte oder unlesbare Buchung braucht keinen
	 * Sitz, nur die Vorstellung im Stand des Index.
	 * <p>
	 * Das Modell hinkt dem Index hinterher, solange der Wechsel von Halle oder Bestuhlung der Vorstellung oder ihr Löschen
	 * zurückgestellt ist oder eine Gabelung bleibt, auch wenn die Vorstellung noch nicht gemeldet wurde: Ihre Buchungen
	 * gehören zum anderen Stand und warten mit ihr.
	 *
	 * @param presentationIndexState der aktuelle Stand der Vorstellung der Buchung im Index; fehlt er oder ist er gelöscht
	 *                               oder unlesbar, ist die Buchung nicht platzierbar
	 */
	static boolean placeable(Event event, UnitSnapshot booking, UnitSnapshot presentationIndexState,
			Function<String, Hall> hallLookup) {
		UnitKey unit = booking.unit();
		Presentation presentation = presentation(event, unit.presentationId());
		Presentation indexed = presentationIndexState == null ? null
				: UnitMapper.readUnit(presentationIndexState, Presentation.class);
		if (presentation == null || indexed == null || changesPlan(presentation, indexed)) {
			return false;
		}
		Booking state = UnitMapper.readUnit(booking, Booking.class);
		return state == null || bookingAt(presentation, unit) != null || fitsPlan(unit, state, presentation, hallLookup);
	}

	/**
	 * Ob für die Buchung nichts auf einen Platz wartet: Ihre Vorstellung steht nicht im Modell, und die Buchung ist gelöscht
	 * oder unlesbar oder die Vorstellung ist es ({@link #gone}). Sie geht dann nur in den Vergleichsstand. Eine lesbare
	 * Buchung unter einer Vorstellung, die nur noch nicht da ist, wartet dagegen auf sie.
	 */
	static boolean nothingToPlace(Event event, UnitSnapshot booking, UnitSnapshot presentationIndexState) {
		return presentation(event, booking.unit().presentationId()) == null
				&& (UnitMapper.readUnit(booking, Booking.class) == null || gone(presentationIndexState));
	}

	/** Ob die Buchung warten muss: Sie ist weder {@link #placeable} noch {@link #nothingToPlace}. */
	static boolean waits(Event event, UnitSnapshot booking, UnitSnapshot presentationIndexState,
			Function<String, Hall> hallLookup) {
		return !nothingToPlace(event, booking, presentationIndexState)
				&& !placeable(event, booking, presentationIndexState, hallLookup);
	}

	/**
	 * Ob die Vorstellung gelöscht oder unlesbar ist: gelöscht, ohne lesbare Version, oder gesperrt, ohne dass es eine
	 * Version gäbe. Fehlt sie im Index nur noch ({@code null}, oder ohne Datei), ist sie nicht weg, sondern wird kommen.
	 */
	static boolean gone(UnitSnapshot presentation) {
		if (presentation == null) {
			return false;
		}
		boolean lockedWithoutVersion = presentation.version() == null && (presentation.status() == UnitState.Status.LOCKED_DAMAGED
				|| presentation.status() == UnitState.Status.LOCKED_FORMAT);
		return lockedWithoutVersion || (presentation.version() != null && !UnitMapper.isReadable(presentation));
	}

	/**
	 * Ob die Buchung zur Bestuhlung der Vorstellung passt: auf einem Sitz, den die Bestuhlung kennt; ohne Platz, wenn der
	 * Abgleich ({@link BookingSync}) sie nicht umsetzte: Sie nennt keinen Sitz, den die Bestuhlung kennt (Schritt 1 setzte
	 * sie sonst dorthin zurück), und Reihe, Tisch und Sitznummer treffen keinen Sitz, den keine andere Buchung der
	 * Vorstellung trägt (Schritt 2). Eine unpassende stammt aus einer anderen Bestuhlung als der im Modell; meist ist die
	 * Halle oder die Vorstellung nur noch nicht da.
	 */
	private static boolean fitsPlan(UnitKey unit, Booking state, Presentation presentation,
			Function<String, Hall> hallLookup) {
		SeatingPlan seating = planOf(presentation, hallLookup);
		if (!unit.isUnassigned()) {
			return seating != null && seating.findSeat(unit.bookingDir()) != null;
		}
		if (seating == null) {
			return true;
		}
		if (state.getSeatId() != null && seating.findSeat(state.getSeatId()) != null) {
			return false;
		}
		return !hitsFreeSeat(state, presentation, seating);
	}

	/**
	 * Ob Reihe, Tisch und Sitznummer der Buchung einen Sitz treffen, den keine andere Buchung der Vorstellung über ihre
	 * Sitzkennung trägt (auch ein leerer Platzhalter trägt ihn): Dorthin setzt Schritt 2 des Abgleichs sie.
	 */
	private static boolean hitsFreeSeat(Booking state, Presentation presentation, SeatingPlan seating) {
		Set<String> taken = new HashSet<>();
		for (Booking other : presentation.getBookings() != null ? presentation.getBookings() : List.<Booking>of()) {
			if (other.getSeatId() != null && !Objects.equals(other.getId(), state.getId())) {
				taken.add(other.getSeatId());
			}
		}
		String key = BookingSync.key(state);
		for (Seat seat : seating.getSeats()) {
			if (key.equals(BookingSync.key(seat)) && !taken.contains(seat.getId())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Setzt die Events aus den Ständen zusammen ({@link UnitMapper#assembleEvents}) und gleicht ihre Vorstellungen mit der
	 * Bestuhlung ab, wie beim Vorbereiten eines geladenen Events für die Anzeige. Lesbare Buchungen, die nicht
	 * {@linkplain #placeable platzierbar} sind, bleiben draußen und kommen in {@code waiting}: Der Abgleich setzte sie
	 * sonst um, und das nächste Speichern überschriebe den fremden Stand. Sie sind dann nicht angezeigt.
	 */
	static Assembly<Event> assembleEvent(Collection<UnitSnapshot> snapshots, Function<String, Hall> hallLookup,
			List<UnitSnapshot> waiting) {
		Assembly<Event> assembly = UnitMapper.assembleEvents(withoutWaiting(snapshots, hallLookup, false, waiting));
		for (Event event : assembly.items()) {
			for (Presentation presentation : event.getPresentations()) {
				BookingSync.sync(presentation, planOf(presentation, hallLookup));
			}
		}
		return assembly;
	}

	/**
	 * Setzt die Events beim Laden zusammen, ohne Abgleich (der folgt beim Vorbereiten für die Anzeige). Wie bei
	 * {@link #assembleEvent} bleiben die Buchungen draußen, die nicht {@linkplain #placeable platzierbar} sind, und kommen in
	 * {@code waiting}; sonst erschiene eine wartende Buchung nach einem Neustart auf ihrer Sitzkennung. Kennt
	 * {@code hallLookup} die Bestuhlung einer Vorstellung nicht (die App setzt die Hallen erst nach dem Laden, oder die Halle
	 * fehlt), lässt sich nichts über die Sitze sagen: Die Buchungen bleiben dann in der Vorstellung, und der Abgleich
	 * markiert sie später wie bisher.
	 */
	static Assembly<Event> assembleLoaded(Collection<UnitSnapshot> snapshots, Function<String, Hall> hallLookup,
			List<UnitSnapshot> waiting) {
		return UnitMapper.assembleEvents(withoutWaiting(snapshots, hallLookup, true, waiting));
	}

	/**
	 * Die Stände ohne die Buchungen unter lesbaren Vorstellungen, die nicht platzierbar sind (sie kommen in {@code waiting}).
	 *
	 * @param lenient ob die Buchungen unter einer Vorstellung bleiben, deren Bestuhlung {@code hallLookup} nicht kennt
	 */
	private static List<UnitSnapshot> withoutWaiting(Collection<UnitSnapshot> snapshots, Function<String, Hall> hallLookup,
			boolean lenient, List<UnitSnapshot> waiting) {
		Map<UnitKey, UnitSnapshot> presentations = new HashMap<>();
		Map<UnitKey, List<UnitSnapshot>> bookings = new HashMap<>();
		for (UnitSnapshot snapshot : snapshots) {
			UnitKey unit = snapshot.unit();
			if (unit.type() == UnitType.PRESENTATION && UnitMapper.isReadable(snapshot)) {
				presentations.put(unit, snapshot);
			} else if (unit.type() == UnitType.BOOKING) {
				bookings.computeIfAbsent(unit.parent(), k -> new ArrayList<>()).add(snapshot);
			}
		}
		Set<UnitKey> waitingUnits = new HashSet<>();
		for (Map.Entry<UnitKey, List<UnitSnapshot>> entry : bookings.entrySet()) {
			UnitSnapshot presentation = presentations.get(entry.getKey());
			if (presentation != null) {
				for (UnitSnapshot booking : placeBelow(presentation, entry.getValue(), hallLookup, lenient)) {
					waitingUnits.add(booking.unit());
					waiting.add(booking);
				}
			}
		}
		return snapshots.stream().filter(snapshot -> !waitingUnits.contains(snapshot.unit())).toList();
	}

	/**
	 * Prüft die Buchungen unter einer Vorstellung, die aus dem Index zusammengesetzt wird, mit {@link #placeable}: Das Modell
	 * ist hier der Stand der Vorstellung, so dass es keinem Index hinterherhinkt. Die Buchungen kommen der Reihe nach in eine
	 * Probe der Vorstellung, erst die auf einem Sitz, dann die ohne Platz, damit "frei" ({@link #fitsPlan}) die Sitze meint,
	 * die der Abgleich nach den übrigen Buchungen noch frei hat.
	 *
	 * @param lenient ob alle Buchungen bleiben, wenn {@code hallLookup} die Bestuhlung der Vorstellung nicht kennt
	 * @return die Buchungen, die warten
	 */
	private static List<UnitSnapshot> placeBelow(UnitSnapshot presentation, List<UnitSnapshot> bookings,
			Function<String, Hall> hallLookup, boolean lenient) {
		Presentation probe = UnitMapper.readUnit(presentation, Presentation.class);
		probe.setBookings(new ArrayList<>());
		Event event = new Event(presentation.unit().eventId());
		event.setPresentations(new ArrayList<>(List.of(probe)));
		boolean planKnown = planOf(probe, hallLookup) != null;
		List<UnitSnapshot> ordered = new ArrayList<>(bookings);
		ordered.sort(Comparator.comparing((UnitSnapshot booking) -> booking.unit().isUnassigned()));
		List<UnitSnapshot> waiting = new ArrayList<>();
		for (UnitSnapshot booking : ordered) {
			if (lenient && !planKnown) {
				continue;
			}
			if (placeable(event, booking, presentation, hallLookup)) {
				Booking state = UnitMapper.readUnit(booking, Booking.class);
				if (state != null) {
					probe.getBookings().add(state);
				}
			} else {
				waiting.add(booking);
			}
		}
		return waiting;
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

	/** Die Bestuhlung der Vorstellung in ihrer Halle nach {@code hallLookup}; {@code null} ohne Halle oder Bestuhlung. */
	static SeatingPlan planOf(Presentation presentation, Function<String, Hall> hallLookup) {
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
		if (unit.isUnassigned()) {
			String id = unit.unassignedBookingId();
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

	// <-- Wege durch das Modell
}
