package de.eltviller_carneval_verein.karten.ui.sales;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.BookingMove;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;

/**
 * Zustand der Zielwahl beim Verschieben von Buchungen (KARV-140), ohne JavaFX. Hält die Quellen und die gewählten
 * Ziele; Meldungen zeigt und den Vorgang plant oder führt er nicht aus.
 */
final class MoveTargets {

	/** Ergebnis eines Klicks auf einen Platz. */
	enum Click {
		ADDED, REMOVED, FULL, LOCKED
	}

	private final Presentation presentation;
	private final List<BookingMove.Source> sources;
	// Die Buchungen der Quellen nach Identität: isSource läuft je gezeichnetem Sitz
	private final Set<Booking> sourceBookings = Collections.newSetFromMap(new IdentityHashMap<>());
	private final Set<String> targets = new LinkedHashSet<>(); // in der Reihenfolge der Wahl

	MoveTargets(Presentation presentation, List<BookingMove.Source> sources) {
		this.presentation = presentation;
		this.sources = List.copyOf(sources);
		this.sources.forEach(source -> sourceBookings.add(source.booking()));
	}

	Presentation presentation() {
		return presentation;
	}

	List<BookingMove.Source> sources() {
		return sources;
	}

	/** Die gewählten Sitzkennungen in der Reihenfolge der Wahl. */
	List<String> targets() {
		return List.copyOf(targets);
	}

	boolean isTarget(String seatId) {
		return targets.contains(seatId);
	}

	boolean isSource(Booking booking) {
		return sourceBookings.contains(booking);
	}

	boolean complete() {
		return targets.size() == sources.size();
	}

	/**
	 * Wählt den Platz als Ziel oder nimmt ihn heraus. Ein gewähltes Ziel lässt sich immer abwählen; ein gesperrter
	 * Platz oder einer über die Zahl der Quellen hinaus ändert nichts. Der Sitz einer Quelle darf Ziel sein.
	 */
	Click toggle(String seatId, Set<String> lockedSeatIds) {
		if (targets.remove(seatId)) {
			return Click.REMOVED;
		}
		if (lockedSeatIds.contains(seatId)) {
			return Click.LOCKED;
		}
		if (complete()) {
			return Click.FULL;
		}
		targets.add(seatId);
		return Click.ADDED;
	}

	/** Ob jede Quelle noch gilt. */
	boolean sourcesCurrent(SeatingPlan plan) {
		return sources.stream().allMatch(s -> s.isCurrent(presentation, plan));
	}

	/** Nimmt Ziele heraus, die gesperrt sind oder nicht mehr zur Bestuhlung gehören; ohne Bestuhlung alle. */
	void dropGoneTargets(SeatingPlan plan, Set<String> lockedSeatIds) {
		targets.removeIf(id -> plan == null || lockedSeatIds.contains(id) || plan.findSeat(id) == null);
	}

	String barText() {
		return MoveTexts.barText(sources, targets.size());
	}
}
