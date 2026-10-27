package de.eltviller_carneval_verein.karten.repository;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.repository.UnitMapper.Assembly;
import de.eltviller_carneval_verein.karten.store.SyncService;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.UnitSnapshot;
import de.eltviller_carneval_verein.karten.store.UnitState;
import de.eltviller_carneval_verein.karten.store.UnitType;

/**
 * Das Repository der Hallen auf dem Versionsspeicher. Eine Halle ist eine einzige Einheit; geschrieben wird nur, wenn
 * sie sich gegenüber dem geladenen Stand geändert hat (siehe {@link UnitBaseline}).
 */
public class StoreHallRepository implements HallRepository {

	private static StoreHallRepository instance;

	private final SyncService sync;
	private final UnitBaseline baseline;

	// Im-Speicher-Cache der Hallen. null bedeutet "noch nicht aus dem Versionsspeicher geladen".
	// Mehrere Vorstellungen (auch über verschiedene Events hinweg) teilen sich über
	// dieselbe hallId dieselbe Hall-Objektreferenz aus diesem Cache.
	private List<Hall> cachedHalls;

	// Warnungen aus dem letzten Ladevorgang (unlesbare oder gesperrte Hallen). Das Repository kennt keine UI,
	// daher werden sie hier nur gesammelt - anzeigen muss die aufrufende Schicht (siehe getAndClearLoadWarnings()).
	private final List<String> loadWarnings = new ArrayList<>();

	private final RepositoryListeners<Hall> listeners = new RepositoryListeners<>();

	/**
	 * Liefert die einzige Instanz des Repositorys. Alle Controller sollen sich
	 * darüber die Instanz holen, statt selbst eine eigene zu erzeugen, damit
	 * nicht an mehreren Stellen unabhängig voneinander geladen wird.
	 */
	public static synchronized StoreHallRepository getInstance() {
		if (instance == null) {
			instance = new StoreHallRepository(SyncService.getInstance());
		}
		return instance;
	}

	/**
	 * Paketsichtbar statt privat, damit Tests direkt einen Versionsspeicher in einem temporären Verzeichnis
	 * injizieren können, ohne den Produktions-Singleton anzufassen.
	 */
	StoreHallRepository(SyncService sync) {
		this.sync = sync;
		this.baseline = new UnitBaseline(sync);
	}

	@Override
	public List<Hall> loadHalls() {
		ensureLoaded();
		// Kopie der Liste zurückgeben, damit niemand von außen den Cache selbst
		// verändern kann (add/remove) - die enthaltenen Hall-Objekte sind aber
		// bewusst dieselben Referenzen wie im Cache.
		return new ArrayList<>(cachedHalls);
	}

	/**
	 * Verwirft den Cache und lädt alle Hallen zwangsweise neu aus dem Versionsspeicher.
	 * Ungespeicherte Änderungen an den bisherigen Hall-Objekten gehen dabei verloren.
	 */
	public List<Hall> reloadHalls() {
		cachedHalls = readHallsFromStore();
		return new ArrayList<>(cachedHalls);
	}

	@Override
	public Hall findById(String hallId) {
		if (hallId == null) {
			return null;
		}
		for (Hall hall : loadHalls()) {
			if (hallId.equals(hall.getId())) {
				return hall;
			}
		}
		return null;
	}

	/**
	 * Warnungen aus dem letzten Ladevorgang (unlesbare oder gesperrte Hallen).
	 * Leert die Liste beim Abholen, damit dieselbe Warnung nicht mehrfach angezeigt wird.
	 * Anzeigen (z.B. per Alert) ist Sache der aufrufenden UI-Schicht.
	 */
	public synchronized List<String> getAndClearLoadWarnings() {
		List<String> warnings = new ArrayList<>(loadWarnings);
		loadWarnings.clear();
		return warnings;
	}

	private void ensureLoaded() {
		if (cachedHalls == null) {
			cachedHalls = readHallsFromStore();
		}
	}

	private List<Hall> readHallsFromStore() {
		if (!sync.isStarted()) {
			throw new IllegalStateException("Der Versionsspeicher wurde noch nicht gestartet.");
		}
		List<UnitSnapshot> snapshots = sync.snapshots().values().stream()
				.filter(snapshot -> snapshot.unit().type() == UnitType.HALL).toList();
		Assembly<Hall> assembly = UnitMapper.assembleHalls(snapshots);
		baseline.reset(snapshots, assembly.shown());
		addLoadWarnings(assembly.warnings());
		return new ArrayList<>(assembly.items());
	}

	private synchronized void addLoadWarnings(List<String> warnings) {
		loadWarnings.addAll(warnings);
	}

	@Override
	public void saveHalls(List<Hall> halls) {
		for (Hall hall : halls) {
			saveHall(hall);
		}
	}

	@Override
	public void saveHall(Hall hall) {
		if (hall == null) {
			throw new IllegalArgumentException("Das Hall-Objekt darf nicht null sein.");
		}

		// Ohne geladenen Vergleichsstand würde eine vorhandene Halle wie eine neue geschrieben
		ensureLoaded();
		baseline.save(hall.getId(), hall.getName(), List.of(UnitMapper.decompose(hall)));

		// Cache aktualisieren, falls die Halle dort noch nicht enthalten ist
		// (z.B. eine neu erstellte Halle)
		if (!cachedHalls.contains(hall)) {
			cachedHalls.add(hall);
		}

		listeners.notifySaved(hall);
	}

	/**
	 * Alle Hallen, die dieses Repository kennt, auch die gelöschten (zum Wiederherstellen), nach Name sortiert. Eine
	 * gelöschte Halle trägt den Namen aus ihrer letzten nicht gelöschten Version, sonst ihre ID.
	 */
	public List<KnownEntity> knownHalls() {
		ensureLoaded();
		return RestorePlanner.knownEntities(sync, baseline.heads(UnitType.HALL));
	}

	/** Die Speicherstände der Halle, neueste zuerst, aus der Kette im Start-Index. */
	public List<SavePoint> savePoints(String hallId) throws IOException {
		return RestorePlanner.savePoints(sync.chains(List.of(UnitKey.hall(hallId))).values());
	}

	/**
	 * Stellt die Halle auf den Speicherstand zurück, im selben Ablauf wie
	 * {@link StoreEventRepository#restoreEvent}: Zielstand zusammensetzen, mit
	 * {@code adjust} anpassen, frisch lesen (Vergleichsstand und Cache), speichern, im Cache ersetzen. Scheitert das Speichern, bleibt im Cache der frisch gelesene
	 * Stand. Wird das Wiederherstellen abgelehnt, bleiben Cache und Vergleichsstand unberührt.
	 *
	 * @throws LockedUnitException wenn die Halle gesperrt ist oder noch auf eine Datei wartet, wenn sie zu diesem
	 *                             Speicherstand gelöscht war oder der Zielstand unlesbar ist; es wurde nichts gespeichert
	 * @throws RuntimeException    wenn das Schreiben scheitert
	 */
	public Restored<Hall> restoreHall(String hallId, SavePoint point, Function<Hall, List<String>> adjust)
			throws IOException {
		ensureLoaded();
		List<UnitState> states = List.of(sync.unitState(UnitKey.hall(hallId)));
		RestorePlanner.requireRestorable(states);
		Hall target = targetOf(states, point);
		List<String> notes = adjust.apply(target);
		refreshFromStore(hallId, states);
		saveHall(target);
		replaceCachedHall(target);
		return new Restored<>(target, notes);
	}

	/**
	 * Die Halle, wie sie zum Speicherstand war, als neues Objekt; auch für eine gelöschte Halle. Gespeichert wird nichts
	 * und der Cache bleibt unberührt (nur für Tests; das Wiederherstellen ist {@link #restoreHall}).
	 */
	Hall hallAt(String hallId, SavePoint point) throws IOException {
		List<UnitState> states = List.of(sync.unitState(UnitKey.hall(hallId)));
		RestorePlanner.requireRestorable(states);
		return targetOf(states, point);
	}

	private Hall targetOf(List<UnitState> states, SavePoint point) {
		return RestorePlanner.single(UnitMapper.assembleHalls(RestorePlanner.targetsAt(states, point.at()).values()),
				"Zu diesem Speicherstand war die Halle gelöscht.");
	}

	/** Übernimmt den frisch gelesenen Stand der Halle als Vergleichsstand und, wenn sie sichtbar ist, in den Cache. */
	private void refreshFromStore(String hallId, List<UnitState> states) {
		List<UnitSnapshot> snapshots = states.stream().map(UnitSnapshot::of).toList();
		Assembly<Hall> current = UnitMapper.assembleHalls(snapshots);
		baseline.resetOwner(hallId, snapshots, current.shown());
		if (!current.items().isEmpty()) {
			Hall fresh = current.items().get(0);
			int index = cachedHalls.indexOf(fresh);
			if (index >= 0) {
				cachedHalls.set(index, fresh);
			} else {
				cachedHalls.add(fresh);
			}
		}
	}

	/** Meldet einen Listener an, der nach jedem Speichern und Löschen einer Halle benachrichtigt wird. */
	public void addListener(RepositoryListener<Hall> listener) {
		listeners.add(listener);
	}

	/**
	 * Ersetzt die Halle mit derselben ID im Cache durch ein anderes Objekt, z. B. nach dem Wiederherstellen eines
	 * früheren Zustands. Der Versionsspeicher bleibt unberührt, und es wird kein Listener benachrichtigt. Ist die Halle
	 * nicht im Cache (noch nie gespeichert), passiert nichts.
	 */
	public void replaceCachedHall(Hall replacement) {
		if (cachedHalls == null) {
			return;
		}
		int index = cachedHalls.indexOf(replacement);
		if (index >= 0) {
			cachedHalls.set(index, replacement);
		}
	}

	@Override
	public void deleteHalls(List<Hall> halls) {
		for (Hall hall : halls) {
			deleteHall(hall);
		}
	}

	/** Löscht die Halle (eine Löschung auf ihrer einzigen Einheit). Eine nie gespeicherte Halle verlässt nur den Cache. */
	@Override
	public void deleteHall(Hall hall) {
		if (hall == null) {
			return;
		}

		ensureLoaded();
		baseline.delete(hall.getId(), UnitKey.hall(hall.getId()), hall.getName());

		cachedHalls.remove(hall);

		listeners.notifyDeleted(hall);
	}

}
