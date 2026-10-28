package de.eltviller_carneval_verein.karten.repository;

import java.util.Set;

import de.eltviller_carneval_verein.karten.store.SyncService;
import de.eltviller_carneval_verein.karten.store.UnitKey;

/**
 * Testzugang zu den Repositories auf einem eigenen Versionsspeicher für Tests außerhalb dieses Pakets (die
 * Konstruktoren sind paketsichtbar). Jeder Aufruf liefert eine neue Instanz ohne Cache, liest also aus dem Speicher.
 */
public final class TestRepositories {

	private TestRepositories() {
	}

	/** Ein Event-Repository auf dem Versionsspeicher; der Dienst muss gestartet sein. */
	public static StoreEventRepository events(SyncService sync) {
		return new StoreEventRepository(sync);
	}

	/** Ein Hallen-Repository auf dem Versionsspeicher; der Dienst muss gestartet sein. */
	public static StoreHallRepository halls(SyncService sync) {
		return new StoreHallRepository(sync);
	}

	/** Ein Schub fremder Stände, dessen Nachspielen auf einer Kopie scheitert (für den Notweg des Änderungsmelders). */
	public static <T> ForeignBatch<T> failingBatch(Set<UnitKey> units) {
		return new ForeignBatch<>(units, copy -> {
			throw new IllegalStateException("Nachspielen gescheitert (Test)");
		});
	}
}
