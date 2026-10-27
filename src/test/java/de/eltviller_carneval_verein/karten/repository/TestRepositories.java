package de.eltviller_carneval_verein.karten.repository;

import java.io.File;

import de.eltviller_carneval_verein.karten.store.SyncService;

/**
 * Testzugang zu den Repositories mit eigenem Speicherordner für Tests außerhalb dieses Pakets (die Konstruktoren sind
 * paketsichtbar). Jeder Aufruf liefert eine neue Instanz ohne Cache, liest also von der Festplatte.
 */
public final class TestRepositories {

	private TestRepositories() {
	}

	public static JsonEventRepository events(File storageDir) {
		return new JsonEventRepository(storageDir);
	}

	public static JsonHallRepository halls(File storageDir) {
		return new JsonHallRepository(storageDir);
	}

	/** Ein Event-Repository auf dem Versionsspeicher; der Dienst muss gestartet sein. */
	public static StoreEventRepository storeEvents(SyncService sync) {
		return new StoreEventRepository(sync);
	}

	/** Ein Hallen-Repository auf dem Versionsspeicher; der Dienst muss gestartet sein. */
	public static StoreHallRepository storeHalls(SyncService sync) {
		return new StoreHallRepository(sync);
	}
}
