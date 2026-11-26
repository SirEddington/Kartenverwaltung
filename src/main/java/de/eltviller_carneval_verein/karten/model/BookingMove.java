package de.eltviller_carneval_verein.karten.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Verschiebt belegende {@link Booking Buchungen} einer {@link Presentation Vorstellung} auf andere Sitze ihrer
 * {@link SeatingPlan Bestuhlung} (KARV-140). Eine Buchung behält dabei ihre Kennung.
 *
 * <p>
 * {@link #plan} ordnet Quellen und Ziele nach der Reihenfolge der Plätze einander zu: Reihenbuchstabe
 * ({@link Booking#ROW_LABEL_ORDER}), Tischnummer, Sitznummer, bei Gleichstand die Kennung des Sitzes; eine Quelle zählt
 * mit ihrem bisherigen Sitz. Die Züge der Quellen bilden Ketten von einem Sitz, den keine Quelle bekommt
 * (Kettenanfang), zu einem Ziel, das nicht Sitz einer Quelle ist (Kettenende). Trägt das Kettenende eine belegende
 * Buchung, zieht sie auf den Kettenanfang (Gegenzug); sonst wird die Buchung des Kettenendes entfernt, und der
 * Kettenanfang bekommt einen leeren Platzhalter. Weil beide Seiten nach derselben Ordnung gepaart werden, schließt sich
 * keine Kette zum Kreis. {@link #apply} führt einen bestätigten Plan aus, wenn er beim erneuten Berechnen derselbe ist.
 * <p>
 * Die Klasse hat nur statische Methoden.
 */
public final class BookingMove {

	/** Warum ein Plan nicht ausführbar ist; bei mehreren Gründen gilt der erste in dieser Reihenfolge. */
	public enum Refusal {
		/** Die Bestuhlung fehlt. */
		NO_PLAN,
		/** Eine Quelle ist nicht mehr so, wie sie gewählt wurde, oder ein Ziel ist kein Sitz der Bestuhlung mehr. */
		STALE,
		/** Die Zahl der Ziele ist nicht die der Quellen, etwas kommt doppelt vor, oder es gibt keine Quelle. */
		COUNT,
		/** Der Sitz einer Quelle oder ein Ziel ist gesperrt. */
		LOCKED,
		/** Jede Quelle sitzt schon auf ihrem Ziel. */
		NOTHING_TO_DO
	}

	/**
	 * Eine gewählte Quelle mit Kennung und Sitz zum Zeitpunkt der Wahl: Nach einer fremden Änderung kann dasselbe Objekt
	 * eine andere Buchung tragen.
	 */
	public record Source(Booking booking, String bookingId, String seatId) {

		/** Die Quelle mit der heutigen Kennung und dem heutigen Sitz der Buchung. */
		public static Source of(Booking booking) {
			return new Source(booking, booking.getId(), booking.getSeatId());
		}

		/** Ob das Objekt noch dieselbe Buchung am selben Sitz der Bestuhlung trägt und ihn belegt. */
		public boolean isCurrent(Presentation presentation, SeatingPlan plan) {
			List<Booking> bookings = presentation != null ? presentation.getBookings() : null;
			return bookings != null && bookings.stream().anyMatch(b -> b == booking) && !booking.isUnassigned()
					&& booking.isTaken() && Objects.equals(booking.getId(), bookingId)
					&& Objects.equals(booking.getSeatId(), seatId) && plan != null && plan.findSeat(seatId) != null;
		}
	}

	/**
	 * Ein Zug: die Buchung zieht von {@code from} nach {@code to}; {@code counterMove} bei einem Gegenzug.
	 * {@code bookingId} ist die Kennung der Buchung zum Zeitpunkt des Plans: Nach einer fremden Änderung kann dasselbe
	 * Objekt eine andere Buchung tragen.
	 */
	public record Step(Booking booking, String bookingId, Seat from, Seat to, boolean counterMove) {
	}

	/**
	 * Ergebnis von {@link #plan}.
	 *
	 * @param steps           erst die Züge der Quellen, dann die Gegenzüge, je nach bisherigem Sitz geordnet
	 * @param emptied         Kettenenden ohne Gegenzug: ihre Buchungen werden entfernt
	 * @param emptiedWithData Kennungen der Sitze aus emptied, deren Buchung Daten trägt (Reste)
	 * @param vacated         Kettenanfänge ohne Gegenzug: sie bekommen einen leeren Platzhalter
	 * @param refusal         null, wenn der Plan ausführbar ist
	 * @param lockedSeat      der gesperrte Sitz bei Refusal.LOCKED, sonst null
	 */
	public record Plan(List<Step> steps, List<Seat> emptied, Set<String> emptiedWithData, List<Seat> vacated,
			Refusal refusal, Seat lockedSeat) {

		public Plan {
			steps = List.copyOf(steps);
			emptied = List.copyOf(emptied);
			emptiedWithData = Set.copyOf(emptiedWithData);
			vacated = List.copyOf(vacated);
		}

		private static Plan refusedFor(Refusal refusal, Seat lockedSeat) {
			return new Plan(List.of(), List.of(), Set.of(), List.of(), refusal, lockedSeat);
		}

		public boolean refused() {
			return refusal != null;
		}

		/**
		 * Gleiche Ablehnung und gleiche Züge (dasselbe Objekt mit derselben Kennung zum Zeitpunkt des Plans,
		 * Sitzkennungen, Gegenzug) und emptiedWithData. Die Daten der Buchungen zählen nicht: Verschoben wird die
		 * Buchung, nicht ihr Inhalt.
		 */
		public boolean sameAs(Plan other) {
			if (other == null || refusal != other.refusal || steps.size() != other.steps.size()
					|| !emptiedWithData.equals(other.emptiedWithData)) {
				return false;
			}
			for (int i = 0; i < steps.size(); i++) {
				Step mine = steps.get(i);
				Step theirs = other.steps.get(i);
				if (mine.booking() != theirs.booking() || !Objects.equals(mine.bookingId(), theirs.bookingId())
						|| !mine.from().getId().equals(theirs.from().getId())
						|| !mine.to().getId().equals(theirs.to().getId()) || mine.counterMove() != theirs.counterMove()) {
					return false;
				}
			}
			return true;
		}
	}

	/** Reihenfolge der Plätze nach dem Sitz der Bestuhlung, bei Gleichstand nach seiner Kennung. */
	private static final Comparator<Seat> SEAT_ORDER = Comparator
			.comparing((Seat seat) -> seat.getParent().getRowLabel(), Booking.ROW_LABEL_ORDER)
			.thenComparingInt(seat -> seat.getParent().getTableNumber()).thenComparingInt(Seat::getSeatNumber)
			.thenComparing(Seat::getId);

	private BookingMove() {
	}

	/**
	 * Plant, die Quellen auf die Ziele zu verschieben, ohne etwas zu ändern. Die Ablehnungsgründe werden in der
	 * Reihenfolge von {@link Refusal} geprüft.
	 *
	 * @param plan          die Bestuhlung der Vorstellung oder {@code null}
	 * @param sources       die gewählten Buchungen
	 * @param targetSeatIds die Kennungen der Zielsitze; sie werden in {@code plan} aufgelöst
	 * @param lockedSeatIds die Sitze, deren Buchungs-Einheit gesperrt ist
	 */
	public static Plan plan(Presentation presentation, SeatingPlan plan, List<Source> sources,
			List<String> targetSeatIds, Set<String> lockedSeatIds) {
		Objects.requireNonNull(sources, "sources");
		Objects.requireNonNull(targetSeatIds, "targetSeatIds");
		Objects.requireNonNull(lockedSeatIds, "lockedSeatIds");
		if (plan == null) {
			return Plan.refusedFor(Refusal.NO_PLAN, null);
		}

		List<Seat> targets = new ArrayList<>();
		for (String targetSeatId : targetSeatIds) {
			Seat target = plan.findSeat(targetSeatId);
			if (target == null) {
				return Plan.refusedFor(Refusal.STALE, null);
			}
			targets.add(target);
		}
		for (Source source : sources) {
			if (!source.isCurrent(presentation, plan)) {
				return Plan.refusedFor(Refusal.STALE, null);
			}
		}

		Set<String> sourceSeatIds = new HashSet<>();
		Set<String> sourceBookingIds = new HashSet<>();
		for (Source source : sources) {
			if (!sourceSeatIds.add(source.seatId()) || !sourceBookingIds.add(source.bookingId())) {
				return Plan.refusedFor(Refusal.COUNT, null);
			}
		}
		if (sources.isEmpty() || targets.size() != sources.size()
				|| new HashSet<>(targetSeatIds).size() != targetSeatIds.size()) {
			return Plan.refusedFor(Refusal.COUNT, null);
		}

		Map<Seat, Source> moving = new HashMap<>();
		for (Source source : sources) {
			moving.put(plan.findSeat(source.seatId()), source);
		}
		Seat lockedSeat = firstLocked(moving.keySet(), targets, lockedSeatIds);
		if (lockedSeat != null) {
			return Plan.refusedFor(Refusal.LOCKED, lockedSeat);
		}

		// Zuordnung nach der Reihenfolge der Plätze; Paare, deren Quelle schon auf dem Ziel sitzt, fallen weg
		List<Seat> froms = new ArrayList<>(moving.keySet());
		froms.sort(SEAT_ORDER);
		targets.sort(SEAT_ORDER);
		Map<Seat, Seat> moves = new HashMap<>(); // bisheriger Sitz -> neuer Sitz
		Map<Seat, Seat> arrivals = new HashMap<>(); // neuer Sitz -> bisheriger Sitz
		List<Step> steps = new ArrayList<>();
		for (int i = 0; i < froms.size(); i++) {
			Seat from = froms.get(i);
			Seat to = targets.get(i);
			if (!from.equals(to)) {
				moves.put(from, to);
				arrivals.put(to, from);
				Source source = moving.get(from);
				steps.add(new Step(source.booking(), source.bookingId(), from, to, false));
			}
		}
		if (steps.isEmpty()) {
			return Plan.refusedFor(Refusal.NOTHING_TO_DO, null);
		}

		// Ketten: je Kettenende zurück über die Quellsitze bis zum Kettenanfang
		List<Seat> ends = new ArrayList<>();
		for (Seat to : moves.values()) {
			if (!moves.containsKey(to)) {
				ends.add(to);
			}
		}
		ends.sort(SEAT_ORDER);
		List<Step> counterMoves = new ArrayList<>();
		List<Seat> emptied = new ArrayList<>();
		Set<String> emptiedWithData = new LinkedHashSet<>();
		List<Seat> vacated = new ArrayList<>();
		for (Seat end : ends) {
			Seat start = arrivals.get(end);
			while (arrivals.containsKey(start)) {
				start = arrivals.get(start);
			}
			Booking occupant = occupantOf(presentation, end);
			if (occupant != null) {
				counterMoves.add(new Step(occupant, occupant.getId(), end, start, true));
			} else {
				emptied.add(end);
				if (hasDataOn(presentation, end)) {
					emptiedWithData.add(end.getId());
				}
				vacated.add(start);
			}
		}
		vacated.sort(SEAT_ORDER);
		steps.addAll(counterMoves);
		return new Plan(steps, emptied, emptiedWithData, vacated, null, null);
	}

	/**
	 * Berechnet den Plan aus denselben Eingaben wie {@link #plan} neu, vergleicht ihn mit {@code shown}
	 * ({@link Plan#sameAs}) und führt ihn nur bei Gleichheit und ohne Ablehnung aus, sonst ändert sich nichts.
	 * Ausführen heißt: An jedem Kettenende ohne Gegenzug werden die Buchungen entfernt, die auf dem Sitz liegen; jede
	 * Buchung eines Zugs bekommt mit {@link Booking#takeSnapshot} ihren neuen Sitz; jeder Kettenanfang ohne Gegenzug
	 * bekommt einen neuen leeren Platzhalter. Gespeichert wird nicht.
	 *
	 * @param shown der Plan, den die Kasse bestätigt hat
	 * @return ob ausgeführt wurde
	 */
	public static boolean apply(Presentation presentation, SeatingPlan plan, List<Source> sources,
			List<String> targetSeatIds, Set<String> lockedSeatIds, Plan shown) {
		Plan current = plan(presentation, plan, sources, targetSeatIds, lockedSeatIds);
		if (current.refused() || !current.sameAs(shown)) {
			return false;
		}
		for (Seat end : current.emptied()) {
			presentation.getBookings()
					.removeIf(b -> liesOn(b, end) && current.steps().stream().noneMatch(step -> step.booking() == b));
		}
		for (Step step : current.steps()) {
			step.booking().takeSnapshot(step.to().getParent(), step.to());
		}
		for (Seat start : current.vacated()) {
			presentation.addBooking().takeSnapshot(start.getParent(), start);
		}
		return true;
	}

	/**
	 * Nimmt bei jeder abgeholten Buchung der Züge "abgeholt" und das Abholdatum zurück, über {@link SaleDates#change}.
	 */
	public static void clearCollected(Plan executed) {
		for (Step step : executed.steps()) {
			if (step.booking().isCollected()) {
				SaleDates.change(step.booking(), b -> {
					b.setCollected(false);
					b.setCollectionDate(null);
				});
			}
		}
	}

	/** Wie viele Buchungen der Züge (Quellen und Gegenzüge) abgeholt sind. */
	public static int collectedCount(Plan plan) {
		return (int) plan.steps().stream().filter(step -> step.booking().isCollected()).count();
	}

	/** Der erste gesperrte Sitz unter den Sitzen der Quellen und den Zielen in der Reihenfolge der Plätze. */
	private static Seat firstLocked(Set<Seat> sourceSeats, List<Seat> targets, Set<String> lockedSeatIds) {
		Set<Seat> touched = new HashSet<>(sourceSeats);
		touched.addAll(targets);
		return touched.stream().filter(seat -> lockedSeatIds.contains(seat.getId())).min(SEAT_ORDER).orElse(null);
	}

	/** Ob die Buchung auf dem Sitz liegt: Sie zeigt auf ihn und ist nicht „ohne Platz“. */
	private static boolean liesOn(Booking booking, Seat seat) {
		return seat.getId().equals(booking.getSeatId()) && !booking.isUnassigned();
	}

	/** Die belegende Buchung der Vorstellung auf dem Sitz, sonst {@code null}. */
	private static Booking occupantOf(Presentation presentation, Seat seat) {
		return presentation.getBookings().stream().filter(b -> liesOn(b, seat) && b.isTaken()).findFirst()
				.orElse(null);
	}

	/** Ob eine Buchung der Vorstellung auf dem Sitz Daten trägt. */
	private static boolean hasDataOn(Presentation presentation, Seat seat) {
		return presentation.getBookings().stream().anyMatch(b -> liesOn(b, seat) && b.hasData());
	}
}
