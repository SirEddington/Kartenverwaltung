package de.eltviller_carneval_verein.karten.model;

import java.util.Set;

import de.eltviller_carneval_verein.karten.model.BookingSync.Report;

/**
 * Abgleich der Buchungen ({@link BookingSync}) ohne gesperrte Sitze, für Tests, in denen nichts gesperrt ist. Wer Sperren
 * prüft, ruft {@link BookingSync} mit der Menge der gesperrten Sitze direkt auf.
 */
public final class Syncs {

	private Syncs() {
	}

	/** {@link BookingSync#sync} ohne gesperrte Sitze. */
	public static Report sync(Presentation p, SeatingPlan plan) {
		return BookingSync.sync(p, plan, Set.of());
	}

	/** {@link BookingSync#switchPlan} ohne gesperrte Sitze. */
	public static Report switchPlan(Presentation p, SeatingPlan plan) {
		return BookingSync.switchPlan(p, plan, Set.of());
	}

	/** {@link BookingSync#preview} ohne gesperrte Sitze. */
	public static Report preview(Presentation p, SeatingPlan plan) {
		return BookingSync.preview(p, plan, Set.of());
	}
}
