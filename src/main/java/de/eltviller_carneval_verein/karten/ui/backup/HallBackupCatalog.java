package de.eltviller_carneval_verein.karten.ui.backup;

import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.repository.StoreHallRepository;

/** Wiederherstellen für Hallen. */
public final class HallBackupCatalog extends AbstractBackupCatalog<Hall> {

	public HallBackupCatalog(StoreHallRepository halls) {
		super("Halle", () -> halls.knownHalls().stream().map(AbstractBackupCatalog::entry).toList(), unchecked(halls::savePoints), halls::prepareRestoreHall,
				halls::commitRestore, Hall::getName);
	}

	/** Der Katalog der App: das echte Repository. */
	public static HallBackupCatalog create() {
		return new HallBackupCatalog(StoreHallRepository.getInstance());
	}

	@Override
	public String restoreWarning() {
		return "Spätere Änderungen, auch die anderer Rechner, werden mit zurückgesetzt.";
	}
}
