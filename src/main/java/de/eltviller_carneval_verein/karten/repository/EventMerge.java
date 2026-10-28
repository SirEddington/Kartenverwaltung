package de.eltviller_carneval_verein.karten.repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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
		void adopted(UnitSnapshot snapshot, boolean shown);

		/** Die Vorstellung hat das Modell verlassen; was unter ihr angezeigt war, ist es nicht mehr. */
		void left(UnitKey presentation);

		/** Eine Warnung für den Nutzer (unlesbare oder gesperrte Einheit). */
		void warning(String text);
	}

	/** Meldet nichts: für das Nachspielen auf einer Kopie. */
	static final Sink NONE = new Sink() {
		@Override
		public void adopted(UnitSnapshot snapshot, boolean shown) {
		}

		@Override
		public void left(UnitKey presentation) {
		}

		@Override
		public void warning(String text) {
		}
	};

	/** Präfix des Ordnernamens einer Buchung ohne Platz (siehe {@link UnitKey}). */
	private static final String UNASSIGNED_PREFIX = "_";

	/**
	 * Was in ein Event eingearbeitet wird. Ein Plan wirkt auf das Event im Cache und auf jede Kopie gleich: Auch die neue
	 * Kennung eines geleerten Platzhalters entsteht nur einmal und gilt dann für beide.
	 */
	static final class Plan {

		private final List<UnitSnapshot> units;
		private final List<UnitSnapshot> index;
		private final Function<String, Hall> hallLookup;
		private final Map<UnitKey, String> placeholderIds;

		/**
		 * @param units      die einzuarbeitenden Stände (eines Events); eingearbeitet wird in der Reihenfolge Kopf,
		 *                   Vorstellungen, Buchungen
		 * @param index      die Stände des ganzen Events aus dem Index, aus denen eine neue Vorstellung samt ihren
		 *                   Buchungen entsteht; leer, wenn keine neu ist
		 * @param hallLookup die Halle zur ID, {@code null}, wenn es sie nicht gibt
		 */
		Plan(List<UnitSnapshot> units, Collection<UnitSnapshot> index, Function<String, Hall> hallLookup) {
			this(sorted(units), List.copyOf(index), hallLookup, new HashMap<>());
		}

		private Plan(List<UnitSnapshot> units, List<UnitSnapshot> index, Function<String, Hall> hallLookup,
				Map<UnitKey, String> placeholderIds) {
			this.units = units;
			this.index = index;
			this.hallLookup = hallLookup;
			this.placeholderIds = placeholderIds;
		}

		/** Die einzuarbeitenden Stände in der Reihenfolge des Einarbeitens. */
		List<UnitSnapshot> units() {
			return units;
		}

		/** Derselbe Plan, beschränkt auf die genannten Einheiten (mit denselben neuen Kennungen). */
		Plan restrictedTo(Collection<UnitKey> keep) {
			Set<UnitKey> kept = new HashSet<>(keep);
			List<UnitSnapshot> remaining = units.stream().filter(s -> kept.contains(s.unit())).toList();
			return new Plan(remaining, index, hallLookup, placeholderIds);
		}

		private String placeholderId(UnitKey unit) {
			return placeholderIds.computeIfAbsent(unit, k -> UUID.randomUUID().toString());
		}

		private static List<UnitSnapshot> sorted(List<UnitSnapshot> units) {
			List<UnitSnapshot> sorted = new ArrayList<>(units);
			sorted.sort(Comparator.comparingInt(s -> rank(s.unit().type())));
			return List.copyOf(sorted);
		}

		private static int rank(UnitType type) {
			return switch (type) {
			case EVENT -> 0;
			case PRESENTATION -> 1;
			case BOOKING -> 2;
			case HALL -> throw new IllegalArgumentException("Eine Halle gehört zu keinem Event.");
			};
		}
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
	 * Felder, auch die Kennung; gibt es den Sitz in der Bestuhlung nicht, wird sie angehängt und abgeglichen (wird "ohne
	 * Platz"). Gelöscht oder unlesbar: nur die Kundendaten leeren, der Platzhalter bekommt eine neue Kennung.</li>
	 * <li>Buchung ohne Platz: anhand der Kennung aktualisieren, sonst anhängen; gelöscht oder unlesbar: entfernen.</li>
	 * <li>Buchung unter einer Vorstellung, die nicht im Modell steht: nur übernehmen; sichtbar wird sie mit der
	 * Vorstellung.</li>
	 * </ul>
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
			switch (unit.type()) {
			case EVENT -> mergeHead(event, snapshot, sink);
			case PRESENTATION -> {
				if (mergePresentation(event, snapshot, plan, sink)) {
					assembled.add(unit);
				}
			}
			// eine Buchung unter einer gerade zusammengesetzten Vorstellung kam schon mit ihr aus dem Index
			case BOOKING -> {
				if (!assembled.contains(presentationKey(unit))) {
					mergeBooking(event, snapshot, plan, sink);
				}
			}
			case HALL -> throw new IllegalArgumentException("Eine Halle gehört zu keinem Event.");
			}
		}
	}

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
			if (!UnitMapper.isAbsent(snapshot)) {
				sink.warning(UnitMapper.unreadablePresentation(unit.presentationId(), event.getName()));
			}
			if (existing != null) {
				event.getPresentations().remove(existing);
				sink.left(unit);
			}
			sink.adopted(snapshot, false);
			return false;
		}
		if (existing != null) {
			boolean replanned = changesPlan(existing, state);
			existing.copyFrom(state);
			if (replanned) {
				resync(existing, plan.hallLookup);
			}
			sink.adopted(snapshot, true);
			warnIfLocked(snapshot, sink);
			return false;
		}

		Presentation added = UnitMapper.assemblePresentation(snapshot, plan.index);
		insertSorted(event, added);
		resync(added, plan.hallLookup);
		sink.adopted(snapshot, true);
		warnIfLocked(snapshot, sink);
		for (UnitSnapshot below : plan.index) {
			if (below.unit().type() == UnitType.BOOKING && inSubtree(below.unit(), unit)) {
				boolean readable = UnitMapper.readUnit(below, Booking.class) != null;
				if (!readable && !UnitMapper.isAbsent(below)) {
					sink.warning(UnitMapper.unreadableBooking(added.getName()));
				}
				sink.adopted(below, readable);
				if (readable) {
					warnIfLocked(below, sink);
				}
			}
		}
		return true;
	}

	private static void mergeBooking(Event event, UnitSnapshot snapshot, Plan plan, Sink sink) {
		UnitKey unit = snapshot.unit();
		Presentation presentation = presentation(event, unit.presentationId());
		if (presentation == null) {
			sink.adopted(snapshot, false);
			return;
		}
		Booking current = bookingAt(presentation, unit);
		Booking state = UnitMapper.readUnit(snapshot, Booking.class);
		if (state == null) {
			if (!UnitMapper.isAbsent(snapshot)) {
				sink.warning(UnitMapper.unreadableBooking(presentation.getName()));
			}
			if (current != null && isUnassigned(unit)) {
				presentation.getBookings().remove(current);
			} else if (current != null && current.hasData()) {
				// die Momentaufnahme des Sitzes bleibt; ein späterer Verkauf trägt nicht die Kennung des alten Käufers
				current.clearCustomerData();
				current.setId(plan.placeholderId(unit));
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
			if (!isUnassigned(unit) && !hasSeat(presentation, unit.bookingDir(), plan.hallLookup)) {
				// den Sitz gibt es in der Bestuhlung nicht: der Abgleich wie beim Laden macht sie "ohne Platz"
				resync(presentation, plan.hallLookup);
			}
		}
		sink.adopted(snapshot, true);
		warnIfLocked(snapshot, sink);
	}

	/**
	 * Setzt die Events aus den Ständen zusammen ({@link UnitMapper#assembleEvents}) und gleicht ihre Vorstellungen mit der
	 * Bestuhlung ab, wie beim Vorbereiten eines geladenen Events für die Anzeige.
	 */
	static Assembly<Event> assembleEvent(Collection<UnitSnapshot> snapshots, Function<String, Hall> hallLookup) {
		Assembly<Event> assembly = UnitMapper.assembleEvents(snapshots);
		for (Event event : assembly.items()) {
			for (Presentation presentation : event.getPresentations()) {
				resync(presentation, hallLookup);
			}
		}
		return assembly;
	}

	/**
	 * Macht {@code target} zu dem Event, das {@link #assembleEvent} aus den Ständen zusammensetzt (Felder und
	 * Vorstellungen); für das Nachspielen, wenn ein Event aus dem Index neu zusammengesetzt wurde.
	 */
	static void replaceContent(Event target, Collection<UnitSnapshot> snapshots, Function<String, Hall> hallLookup) {
		for (Event fresh : assembleEvent(snapshots, hallLookup).items()) {
			if (fresh.getId().equals(target.getId())) {
				target.copyFrom(fresh);
				target.setPresentations(fresh.getPresentations());
			}
		}
	}

	/** Gleicht die Buchungen mit der Bestuhlung der Vorstellung ab ({@link BookingSync#sync}). */
	private static void resync(Presentation presentation, Function<String, Hall> hallLookup) {
		BookingSync.sync(presentation, planOf(presentation, hallLookup));
	}

	private static SeatingPlan planOf(Presentation presentation, Function<String, Hall> hallLookup) {
		Hall hall = presentation.getHallId() != null ? hallLookup.apply(presentation.getHallId()) : null;
		return hall != null ? hall.findSeatingPlan(presentation.getSeatingPlanId()) : null;
	}

	private static boolean hasSeat(Presentation presentation, String seatId, Function<String, Hall> hallLookup) {
		SeatingPlan plan = planOf(presentation, hallLookup);
		return plan != null && plan.findSeat(seatId) != null;
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
		List<Presentation> presentations = event.getPresentations();
		int index = 0;
		while (index < presentations.size()
				&& UnitMapper.PRESENTATION_ORDER.compare(presentations.get(index), added) <= 0) {
			index++;
		}
		presentations.add(index, added);
		added.setParent(event);
	}

	private static void warnIfLocked(UnitSnapshot snapshot, Sink sink) {
		String locked = UnitMapper.lockMessage(snapshot.unit(), snapshot.status());
		if (locked != null) {
			sink.warning(locked);
		}
	}

	// Wege durch das Modell, auch für die Entscheidung "blockiert" -->

	/** Die Vorstellung mit dieser ID im Event, sonst {@code null}. */
	static Presentation presentation(Event event, String presentationId) {
		if (event.getPresentations() == null) {
			return null;
		}
		for (Presentation presentation : event.getPresentations()) {
			if (presentation.getId().equals(presentationId)) {
				return presentation;
			}
		}
		return null;
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
	 * unlesbar), oder eine Vorstellung wechselt Halle oder Bestuhlung.
	 */
	static boolean touchesSubtree(Event event, UnitSnapshot snapshot) {
		UnitKey unit = snapshot.unit();
		return switch (unit.type()) {
		case EVENT -> UnitMapper.readUnit(snapshot, Event.class) == null;
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

	private static UnitKey presentationKey(UnitKey booking) {
		return UnitKey.presentation(booking.eventId(), booking.presentationId());
	}

	// <-- Wege durch das Modell
}
