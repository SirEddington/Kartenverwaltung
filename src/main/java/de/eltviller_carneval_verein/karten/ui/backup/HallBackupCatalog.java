package de.eltviller_carneval_verein.karten.ui.backup;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.repository.BackupStore;
import de.eltviller_carneval_verein.karten.repository.JsonHallRepository;
import de.eltviller_carneval_verein.karten.ui.HallSaver;

/** Wiederherstellen für Hallen. */
public final class HallBackupCatalog extends AbstractBackupCatalog<Hall> {

	public HallBackupCatalog(BackupStore store, Supplier<List<Hall>> currentHalls, Consumer<Hall> save, Consumer<Hall> replaceInCache) {
		super(store, Hall.class, "Halle", currentHalls, save, replaceInCache);
	}

	/** Der Katalog der App: echtes Repository, Speichern über den {@link HallSaver}. */
	public static HallBackupCatalog create() {
		JsonHallRepository halls = JsonHallRepository.getInstance();
		return new HallBackupCatalog(halls.backups(), halls::loadHalls, HallSaver::saveRestoredState, halls::replaceCachedHall);
	}

	@Override
	String idOf(Hall hall) {
		return hall.getId();
	}

	@Override
	String nameOf(Hall hall) {
		return hall.getName();
	}
}
