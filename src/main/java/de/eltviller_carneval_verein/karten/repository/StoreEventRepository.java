package de.eltviller_carneval_verein.karten.repository;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.repository.UnitMapper.Assembly;
import de.eltviller_carneval_verein.karten.store.SyncService;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.UnitSnapshot;
import de.eltviller_carneval_verein.karten.store.UnitState;
import de.eltviller_carneval_verein.karten.store.UnitType;

/**
 * Das Repository der Events auf dem Versionsspeicher. Beim Speichern wird das Event in Kopf, Vorstellungen und Buchungen
 * zerlegt und nur geschrieben, was sich gegenüber dem geladenen Stand geändert hat (siehe {@link UnitBaseline}).
 */
public class StoreEventRepository implements EventRepository {

	private static StoreEventRepository instance;

	private final SyncService sync;
	private final UnitBaseline baseline;

	// Im-Speicher-Cache der Events. null bedeutet "noch nicht aus dem Versionsspeicher geladen".
	// Alle Controller bekommen über loadEvents() dieselben Event-Objektreferenzen,
	// damit ungespeicherte Änderungen eines Controllers auch für andere sichtbar sind.
	private List<Event> cachedEvents;

	// Warnungen aus dem letzten Ladevorgang (unlesbare oder gesperrte Einheiten). Das Repository kennt keine UI,
	// daher werden sie hier nur gesammelt - anzeigen muss die aufrufende Schicht (siehe getAndClearLoadWarnings()).
	private final List<String> loadWarnings = new ArrayList<>();

	private final RepositoryListeners<Event> listeners = new RepositoryListeners<>();

	/**
	 * Liefert die einzige Instanz des Repositorys. Alle Controller sollen sich
	 * darüber die Instanz holen, statt selbst eine eigene zu erzeugen, damit
	 * nicht an mehreren Stellen unabhängig voneinander geladen wird.
	 */
	public static synchronized StoreEventRepository getInstance() {
		if (instance == null) {
			instance = new StoreEventRepository(SyncService.getInstance());
		}
		return instance;
	}

	/**
	 * Paketsichtbar statt privat, damit Tests direkt einen Versionsspeicher in einem temporären Verzeichnis
	 * injizieren können, ohne den Produktions-Singleton anzufassen.
	 */
	StoreEventRepository(SyncService sync) {
		this.sync = sync;
		this.baseline = new UnitBaseline(sync);
	}

	@Override
	public List<Event> loadEvents() {
		ensureLoaded();
		// Kopie der Liste zurückgeben, damit niemand von außen den Cache selbst
		// verändern kann (add/remove) - die enthaltenen Event-Objekte sind aber
		// bewusst dieselben Referenzen wie im Cache.
		return new ArrayList<>(cachedEvents);
	}

	/**
	 * Verwirft den Cache und lädt alle Events zwangsweise neu aus dem Versionsspeicher.
	 * Ungespeicherte Änderungen an den bisherigen Event-Objekten gehen dabei verloren.
	 */
	public List<Event> reloadEvents() {
		cachedEvents = readEventsFromStore();
		return new ArrayList<>(cachedEvents);
	}

	/**
	 * Warnungen aus dem letzten Ladevorgang (unlesbare oder gesperrte Einheiten).
	 * Leert die Liste beim Abholen, damit dieselbe Warnung nicht mehrfach angezeigt wird.
	 * Anzeigen (z.B. per Alert) ist Sache der aufrufenden UI-Schicht.
	 */
	public synchronized List<String> getAndClearLoadWarnings() {
		List<String> warnings = new ArrayList<>(loadWarnings);
		loadWarnings.clear();
		return warnings;
	}

	private void ensureLoaded() {
		if (cachedEvents == null) {
			cachedEvents = readEventsFromStore();
		}
	}

	private List<Event> readEventsFromStore() {
		if (!sync.isStarted()) {
			throw new IllegalStateException("Der Versionsspeicher wurde noch nicht gestartet.");
		}
		List<UnitSnapshot> snapshots = sync.snapshots().values().stream()
				.filter(snapshot -> snapshot.unit().type() != UnitType.HALL).toList();
		Assembly<Event> assembly = UnitMapper.assembleEvents(snapshots);
		baseline.reset(snapshots, assembly.shown());
		addLoadWarnings(assembly.warnings());
		return new ArrayList<>(assembly.items());
	}

	private synchronized void addLoadWarnings(List<String> warnings) {
		loadWarnings.addAll(warnings);
	}

	@Override
	public void saveEvents(List<Event> events) {
		for (Event event : events) {
			saveEvent(event);
		}
	}

	@Override
	public void saveEvent(Event event) {
		if (event == null) {
			throw new IllegalArgumentException("Das Event-Objekt darf nicht null sein.");
		}

		if (event.getName() == null || event.getName().isBlank()) {
			throw new IllegalArgumentException("Event-Name darf nicht leer oder null sein.");
		}

		// Ohne geladenen Vergleichsstand würde ein vorhandenes Event wie ein neues geschrieben
		ensureLoaded();
		baseline.save(event.getId(), event.getName(), UnitMapper.decompose(event));

		// Cache aktualisieren, falls das Event dort noch nicht enthalten ist
		// (z.B. ein neu erstelltes Event)
		if (!cachedEvents.contains(event)) {
			cachedEvents.add(event);
		}

		listeners.notifySaved(event);
	}

	/**
	 * Alle Events, die dieses Repository kennt, auch die gelöschten (zum Wiederherstellen), nach Name sortiert. Ein
	 * gelöschtes Event trägt den Namen aus seiner letzten nicht gelöschten Version, sonst seine ID.
	 */
	public List<KnownEntity> knownEvents() {
		ensureLoaded();
		return RestorePlanner.knownEntities(sync, baseline.heads(UnitType.EVENT));
	}

	/** Die Speicherstände des Events (Kopf, Vorstellungen, Buchungen), neueste zuerst. */
	public List<SavePoint> savePoints(String eventId) throws IOException {
		return RestorePlanner.savePoints(statesOf(eventId));
	}

	/**
	 * Stellt das Event auf den Speicherstand zurück. Ablauf: Die Einheiten des Events werden frisch aus dem Versionsspeicher
	 * gelesen und sind ab jetzt Vergleichsstand und angezeigter Stand (Änderungen von anderer Seite seit dem Laden gelten
	 * damit als Stand, den es zurückzusetzen gilt, nicht als unbekannt); ein sichtbares Event ersetzt das im Cache. Dann
	 * wird der Zielstand zusammengesetzt, mit {@code adjust} angepasst (liefert Hinweise für den Nutzer) und wie jedes
	 * Event gespeichert: Die Unterschiede werden neue Versionen mit gemeinsamer neuer {@code saveId} auf dem aktuellen
	 * Ende der Ketten. Das Ergebnis ersetzt das Event im Cache.
	 * <p>
	 * Scheitert das Speichern, bleibt im Cache der frisch gelesene Stand, passend zum Vergleichsstand.
	 *
	 * @throws LockedUnitException wenn eine Einheit des Events gesperrt ist oder noch auf eine Datei wartet, wenn das Event
	 *                             zu diesem Speicherstand gelöscht war oder der Zielstand unlesbare Teile enthält; es
	 *                             wurde nichts gespeichert
	 * @throws RuntimeException    wenn das Schreiben scheitert
	 */
	public Restored<Event> restoreEvent(String eventId, SavePoint point, Function<Event, List<String>> adjust)
			throws IOException {
		ensureLoaded();
		List<UnitState> states = statesOf(eventId);
		RestorePlanner.requireRestorable(states);
		refreshFromStore(eventId, states);
		Event target = targetOf(states, point);
		List<String> notes = adjust.apply(target);
		saveEvent(target);
		replaceCachedEvent(target);
		return new Restored<>(target, notes);
	}

	/**
	 * Das Event, wie es zum Speicherstand war, als neues Objekt; auch für ein gelöschtes Event. Gespeichert wird nichts
	 * und der Cache bleibt unberührt (nur für Tests; das Wiederherstellen ist {@link #restoreEvent}).
	 */
	Event eventAt(String eventId, SavePoint point) throws IOException {
		List<UnitState> states = statesOf(eventId);
		RestorePlanner.requireRestorable(states);
		return targetOf(states, point);
	}

	private Event targetOf(List<UnitState> states, SavePoint point) {
		return RestorePlanner.single(UnitMapper.assembleEvents(RestorePlanner.targetsAt(states, point.at()).values()),
				"Zu diesem Speicherstand war das Event gelöscht.");
	}

	/** Übernimmt den frisch gelesenen Stand des Events als Vergleichsstand und, wenn es sichtbar ist, in den Cache. */
	private void refreshFromStore(String eventId, List<UnitState> states) {
		List<UnitSnapshot> snapshots = states.stream().map(UnitSnapshot::of).toList();
		Assembly<Event> current = UnitMapper.assembleEvents(snapshots);
		baseline.resetOwner(eventId, snapshots, current.shown());
		if (!current.items().isEmpty()) {
			Event fresh = current.items().get(0);
			int index = cachedEvents.indexOf(fresh);
			if (index >= 0) {
				cachedEvents.set(index, fresh);
			} else {
				cachedEvents.add(fresh);
			}
		}
	}

	private List<UnitState> statesOf(String eventId) throws IOException {
		List<UnitState> states = new ArrayList<>();
		for (UnitKey unit : sync.eventUnits(eventId)) {
			states.add(sync.unitState(unit));
		}
		return states;
	}

	/** Meldet einen Listener an, der nach jedem Speichern und Löschen eines Events benachrichtigt wird. */
	public void addListener(RepositoryListener<Event> listener) {
		listeners.add(listener);
	}

	/**
	 * Ersetzt das Event mit derselben ID im Cache durch ein anderes Objekt, z. B. nach dem Wiederherstellen eines
	 * früheren Zustands. Der Versionsspeicher bleibt unberührt, und es wird kein Listener benachrichtigt. Ist das Event
	 * nicht im Cache (noch nie gespeichert), passiert nichts.
	 */
	public void replaceCachedEvent(Event replacement) {
		if (cachedEvents == null) {
			return;
		}
		int index = cachedEvents.indexOf(replacement);
		if (index >= 0) {
			cachedEvents.set(index, replacement);
		}
	}

	@Override
	public void deleteEvents(List<Event> events) {
		for (Event event : events) {
			deleteEvent(event);
		}
	}

	/**
	 * Löscht das Event: Nur sein Kopf bekommt eine Löschung, Vorstellungen und Buchungen bleiben liegen und sind
	 * unsichtbar. Ein nie gespeichertes Event verlässt nur den Cache.
	 */
	@Override
	public void deleteEvent(Event event) {
		if (event == null) {
			return;
		}

		ensureLoaded();
		baseline.delete(event.getId(), UnitKey.event(event.getId()), event.getName());

		cachedEvents.remove(event);

		listeners.notifyDeleted(event);
	}

}
