package de.eltviller_carneval_verein.karten.repository;

import de.eltviller_carneval_verein.karten.store.SyncService;
import de.eltviller_carneval_verein.karten.store.UnitType;

/**
 * Hängt die Fachregeln für Konflikte in den Versionsspeicher ein: je Einheitenart die Strategie, mit der verlorene
 * Strecken zusammengeführt werden. Vor {@link SyncService#startup} aufrufen, damit schon die Auflösung beim Start sie
 * benutzt.
 */
public final class ConflictPolicies {

	private ConflictPolicies() {
	}

	/** Setzt {@link EventHeadPolicy}, {@link PresentationPolicy}, {@link BookingPolicy} und {@link HallPolicy}. */
	public static void install(SyncService sync) {
		sync.setConflictPolicy(UnitType.EVENT, new EventHeadPolicy());
		sync.setConflictPolicy(UnitType.PRESENTATION, new PresentationPolicy());
		sync.setConflictPolicy(UnitType.BOOKING, new BookingPolicy());
		sync.setConflictPolicy(UnitType.HALL, new HallPolicy());
	}
}
