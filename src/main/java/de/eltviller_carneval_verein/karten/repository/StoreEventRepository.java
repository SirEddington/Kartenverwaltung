package de.eltviller_carneval_verein.karten.repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.repository.UnitMapper.Assembly;
import de.eltviller_carneval_verein.karten.store.ChainStep;
import de.eltviller_carneval_verein.karten.store.StoreMessages;
import de.eltviller_carneval_verein.karten.store.SyncService;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.UnitSnapshot;
import de.eltviller_carneval_verein.karten.store.UnitState;
import de.eltviller_carneval_verein.karten.store.UnitType;
import de.eltviller_carneval_verein.karten.validation.EntityLabels;

/**
 * Das Repository der Events auf dem Versionsspeicher. Beim Speichern wird das Event in Kopf, Vorstellungen und Buchungen
 * zerlegt und nur geschrieben, was sich gegenüber dem geladenen Stand geändert hat (siehe {@link UnitBaseline}).
 */
public class StoreEventRepository implements EventRepository {

	private static final Logger LOG = Logger.getLogger(StoreEventRepository.class.getName());

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

	/** Die zurückgestellten fremden Stände; {@link #applyForeign} prüft sie bei jedem Aufruf erneut. */
	private final Map<UnitKey, Deferral> deferred = new LinkedHashMap<>();

	/**
	 * Fremde Stände, die still warten. Vor allem Buchungen, bis ihr Platz im Modell da ist: Sie passen nicht zur
	 * Bestuhlung der Vorstellung im Modell, oder der Hallen- bzw. Bestuhlungswechsel (oder das Löschen) ihrer Vorstellung
	 * ist zurückgestellt oder eine Gabelung. Meist kommt nur die Halle oder Vorstellung später an; vom Abgleich umgesetzt,
	 * überschriebe das nächste Speichern den fremden Stand. Außerdem die Einheiten eines Events, bei dem die Entscheidung
	 * scheiterte. Ohne Hinweis für den Nutzer, bei jedem Aufruf von {@link #applyForeign} erneut geprüft.
	 */
	private final Set<UnitKey> held = new LinkedHashSet<>();

	/**
	 * Vorstellungen, deren fremder Stand eine Gabelung ist und Halle oder Bestuhlung wechselt bzw. sie löscht: Ihre
	 * Buchungen warten. Sie werden bei jedem Aufruf mit geprüft, damit die Buchungen weiter warten, solange die Gabelung
	 * besteht.
	 */
	private final Set<UnitKey> forkedParents = new LinkedHashSet<>();

	/**
	 * Inhalte im Modell, die nicht der Nutzer, sondern das Einarbeiten erzeugt hat: Wechselt eine fremde Vorstellung Halle
	 * oder Bestuhlung, setzt der Abgleich ihre Buchungen auf die neuen Sitze, bevor deren Einheiten eintreffen. Solange das
	 * Modell genau diesen Inhalt hat, gilt die Einheit nicht als lokal verändert; der Wert {@code null} heißt, das Einarbeiten
	 * hat sie aus dem Modell genommen. Ein Eintrag gilt, bis sich der Vergleichsstand der Einheit ändert.
	 */
	private final Map<UnitKey, ObjectNode> derived = new HashMap<>();

	/** Ob für das Modellobjekt einer Einheit gerade eine Eingabe offen ist (siehe {@link #setEditingCheck}). */
	private Predicate<Object> inEditing = model -> false;

	/** Die Halle zur ID für den Abgleich der Buchungen fremder Stände (siehe {@link #setHallLookup}). */
	private Function<String, Hall> hallLookup = hallId -> null;

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
		derived.clear();
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
		// Ein fremder Stand hat das Objekt im Cache ersetzt: Das alte zu speichern überschriebe ihn
		Event cached = cachedEvent(event.getId());
		if (cached != null && cached != event) {
			throw new LockedUnitException("„" + EntityLabels.nameOrPlaceholder(event.getName())
					+ "“ wurde inzwischen auf einem anderen Rechner geändert. Bitte die Ansicht neu öffnen.");
		}
		write(event);
	}

	/** Schreibt das Event, ohne zu prüfen, ob es das Objekt im Cache ist (das Wiederherstellen setzt den Cache selbst). */
	private void write(Event event) {
		baseline.save(event.getId(), event.getName(), UnitMapper.decompose(event));
		forgetDerived(event.getId());

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

	/**
	 * Die Speicherstände des Events (Kopf, Vorstellungen, Buchungen), neueste zuerst. Sie entstehen aus den Ketten im
	 * Start-Index, ohne eine Versionsdatei zu lesen.
	 */
	public List<SavePoint> savePoints(String eventId) throws IOException {
		return RestorePlanner.savePoints(sync.chains(sync.eventUnits(eventId)).values());
	}

	/**
	 * Stellt das Event auf den Speicherstand zurück. Ablauf: Aus den frisch gelesenen Einheiten wird der Zielstand zusammengesetzt und mit
	 * {@code adjust} angepasst (liefert Hinweise für den Nutzer); wird er abgelehnt, ist nichts geschehen. Dann werden die
	 * frisch gelesenen Einheiten Vergleichsstand und angezeigter Stand (Änderungen von anderer Seite seit dem Laden gelten
	 * damit als Stand, den es zurückzusetzen gilt, nicht als unbekannt) und ein sichtbares Event ersetzt das im Cache. Zuletzt
	 * wird der Zielstand wie jedes Event gespeichert: Die Unterschiede werden neue Versionen mit gemeinsamer neuer {@code saveId} auf dem aktuellen
	 * Ende der Ketten. Das Ergebnis ersetzt das Event im Cache.
	 * <p>
	 * Scheitert das Speichern, bleibt im Cache der frisch gelesene Stand, passend zum Vergleichsstand. Wird das
	 * Wiederherstellen abgelehnt, bleiben Cache und Vergleichsstand unberührt.
	 *
	 * @throws LockedUnitException wenn eine Einheit des Events gesperrt ist oder noch auf eine Datei wartet, wenn das Event
	 *                             zu diesem Speicherstand gelöscht war oder der Zielstand unlesbare Teile enthält; es
	 *                             wurde nichts gespeichert
	 * @throws RuntimeException    wenn das Schreiben scheitert
	 */
	public Restored<Event> restoreEvent(String eventId, SavePoint point, Function<Event, List<String>> adjust)
			throws IOException {
		return commitRestore(prepareRestoreEvent(eventId, point), adjust);
	}

	/**
	 * Der erste Schritt des Wiederherstellens, geeignet für einen Hintergrund-Thread: liest die Einheiten des Events frisch,
	 * prüft, dass keine gesperrt ist und keine auf eine Datei wartet, und setzt den Zielstand zusammen. Berührt weder Cache
	 * noch Vergleichsstand noch Listener und schreibt nichts. Den Änderungszähler des Vergleichsstands liest es zuerst, damit
	 * {@link #commitRestore} jede Änderung seither bemerkt.
	 *
	 * @throws LockedUnitException wie {@link #restoreEvent}; es wurde nichts verändert
	 */
	public RestorePlan<Event> prepareRestoreEvent(String eventId, SavePoint point) throws IOException {
		long changes = baseline.changes(eventId);
		List<UnitState> states = statesOf(eventId);
		RestorePlanner.requireRestorable(states);
		return new RestorePlan<>(eventId, states, targetOf(states, point), changes);
	}

	/**
	 * Der zweite Schritt, auf dem Oberflächen-Thread: Hat sich der Vergleichsstand des Events seit der Vorbereitung
	 * geändert (etwa durch Speichern), wird abgelehnt. Sonst wird der Zielstand mit {@code adjust} angepasst, die
	 * vorbereiteten Einheiten werden Vergleichsstand und angezeigter Stand und der Zielstand wird wie jedes Event
	 * gespeichert; Einzelheiten und Fehlerverhalten wie bei {@link #restoreEvent}.
	 *
	 * @throws LockedUnitException wenn sich das Event seit der Vorbereitung geändert hat; es wurde nichts geschrieben
	 */
	public Restored<Event> commitRestore(RestorePlan<Event> plan, Function<Event, List<String>> adjust) {
		if (baseline.changes(plan.ownerId()) != plan.changes()) {
			throw new LockedUnitException("Das Event wurde inzwischen geändert. Bitte erneut wiederherstellen.");
		}
		ensureLoaded();
		Event target = plan.target();
		List<String> notes = adjust.apply(target);
		refreshFromStore(plan.ownerId(), plan.states());
		write(target);
		replaceCachedEvent(target);
		return new Restored<>(target, notes);
	}

	/** Der Änderungszähler des Vergleichsstands des Events (nur für Tests). */
	long baselineChanges(String eventId) {
		return baseline.changes(eventId);
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
		forgetDerived(eventId);
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

	/** Meldet einen Listener ab (etwa wenn eine Ansicht verlassen wird). */
	public void removeListener(RepositoryListener<Event> listener) {
		listeners.remove(listener);
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
		forgetDerived(event.getId());

		cachedEvents.remove(event);

		listeners.notifyDeleted(event);
	}

	// Fremde Stände -->

	/**
	 * Wer entscheidet, ob für ein Modellobjekt (Event, Presentation oder Booking) gerade eine Eingabe offen ist, die noch
	 * nicht im Modell steht. Ein fremder Stand dafür wird zurückgestellt. Vorgabe: nie.
	 */
	public void setEditingCheck(Predicate<Object> inEditing) {
		this.inEditing = inEditing;
	}

	/**
	 * Woher die Bestuhlung kommt, mit der die Buchungen fremd geänderter oder neuer Vorstellungen abgeglichen werden (in der
	 * App die Hallen des Hallen-Repositorys). Vorgabe: keine Halle, wie eine nicht gefundene.
	 */
	public void setHallLookup(Function<String, Hall> hallLookup) {
		this.hallLookup = hallLookup;
	}

	/**
	 * Arbeitet die Stände ein, die andere Rechner für diese Einheiten geschrieben haben (Spec KARV-85, "Entscheidung je
	 * Einheit", "Einarbeiten" und "Fehlerfälle"). Hallen werden übergangen; die zurückgestellten Einheiten früherer Aufrufe
	 * werden jedes Mal mit geprüft. Je Einheit gilt {@link ForeignDecision}: Ein Nachfolger des Vergleichsstands in der
	 * gültigen Kette wird eingearbeitet, außer der Nutzer hat die Einheit ungespeichert geändert oder bearbeitet sie gerade
	 * (bei Löschung von Kopf oder Vorstellung und beim Wechsel von Halle oder Bestuhlung zählt alles darunter); dann wird
	 * er zurückgestellt, der Vergleichsstand bleibt. Eine Buchung, die im Modell keinen Platz hat (Sitz fehlt in der
	 * Bestuhlung, Hallenwechsel ihrer Vorstellung zurückgestellt), wartet still ohne Hinweis und wird ebenfalls jedes Mal
	 * erneut geprüft. Je Event werden Objekt im Cache und Vergleichsstand in einem Schritt
	 * nachgeführt, danach erfahren es die Listener. Ist noch nichts geladen, passiert nichts: Das erste Laden liest
	 * ohnehin den aktuellen Stand.
	 * <p>
	 * Nur auf dem Oberflächen-Thread aufrufen: Modell und Vergleichsstand sind nicht synchronisiert.
	 *
	 * @return alle danach zurückgestellten Stände und die Warnungen für den Nutzer
	 */
	public ForeignResult applyForeign(Collection<UnitKey> units) {
		if (cachedEvents == null) {
			return new ForeignResult(List.of(), List.of());
		}
		Set<UnitKey> wanted = new LinkedHashSet<>();
		for (UnitKey unit : units) {
			if (unit.type() != UnitType.HALL) {
				wanted.add(unit);
			}
		}
		wanted.addAll(deferred.keySet());
		// vor den wartenden Buchungen: Ob sie weiter warten, hängt an der Gabelung ihrer Vorstellung
		wanted.addAll(forkedParents);
		wanted.addAll(held);
		List<String> warnings = new ArrayList<>();
		if (!wanted.isEmpty()) {
			Map<UnitKey, UnitSnapshot> current = sync.snapshots(wanted);
			// was der Index nicht mehr kennt, lässt sich nicht einarbeiten
			deferred.keySet().retainAll(current.keySet());
			held.retainAll(current.keySet());
			forkedParents.retainAll(current.keySet());
			Map<String, List<UnitSnapshot>> byEvent = new LinkedHashMap<>();
			for (UnitKey unit : wanted) {
				UnitSnapshot snapshot = current.get(unit);
				if (snapshot != null) {
					byEvent.computeIfAbsent(unit.eventId(), k -> new ArrayList<>()).add(snapshot);
				}
			}
			try {
				Map<UnitKey, List<ChainStep>> chains = sync.chains(current.keySet());
				for (List<UnitSnapshot> ofEvent : byEvent.values()) {
					// ein Fehler bei einem Event hält die übrigen nicht auf; seine Einheiten kommen beim nächsten Mal wieder
					try {
						applyForeign(ofEvent, chains, warnings);
					} catch (RuntimeException e) {
						failed(ofEvent.get(0).unit().eventId(), e, warnings);
						for (UnitSnapshot snapshot : ofEvent) {
							if (!deferred.containsKey(snapshot.unit())) {
								hold(snapshot.unit(), "die Entscheidung ist gescheitert");
							}
						}
					}
				}
			} catch (IOException e) {
				LOG.log(Level.WARNING, "Die Ketten fremd geänderter Einheiten ließen sich nicht lesen", e);
				warnings.add("Änderungen von einem anderen Rechner ließen sich nicht einarbeiten: " + StoreMessages.describe(e));
			}
		}
		return new ForeignResult(new ArrayList<>(deferred.values()), warnings);
	}

	/** Versucht die zurückgestellten Stände erneut, etwa nach dem Speichern oder wenn eine Bearbeitung endet. */
	public ForeignResult retryDeferred() {
		return applyForeign(List.of());
	}

	/**
	 * Die Einheiten aller Events im Cache, die der Nutzer ungespeichert geändert hat: Inhalt im Modell anders als im
	 * Vergleichsstand, oder nur auf einer Seite vorhanden (im Vergleichsstand zählt nur eine angezeigte). Nicht dazu zählt,
	 * was das Einarbeiten selbst im Modell umgesetzt hat (siehe {@link #derived}), solange es unverändert ist. Lässt sich ein
	 * Event nicht zerlegen (zwei Buchungen auf einem Platz), zählen sein Kopf und alles Angezeigte.
	 */
	public Set<UnitKey> locallyChangedUnits() {
		Set<UnitKey> changed = new HashSet<>();
		if (cachedEvents != null) {
			for (Event event : cachedEvents) {
				LocalChanges changes = localChanges(event);
				changed.addAll(changes.units());
				if (changes.all()) {
					changed.add(UnitKey.event(event.getId()));
					changed.addAll(baseline.shownUnits(event.getId()));
				}
			}
		}
		return changed;
	}

	/** Der Stand der Einheit im Vergleichsstand (nur für Tests). */
	UnitSnapshot knownUnit(UnitKey unit) {
		return baseline.known(unit);
	}

	/**
	 * Die lokal veränderten Einheiten eines Events; {@code all}, wenn es sich nicht zerlegen lässt (dann gilt jede Einheit
	 * als verändert). {@code model} sind die Inhalte der Einheiten im Modell ({@code null} bei {@code all}).
	 */
	private record LocalChanges(Set<UnitKey> units, boolean all, Map<UnitKey, ObjectNode> model) {

		boolean none() {
			return !all && units.isEmpty();
		}
	}

	private LocalChanges localChanges(Event event) {
		Map<UnitKey, ObjectNode> model;
		try {
			model = UnitMapper.payloads(event);
		} catch (IllegalStateException e) {
			return new LocalChanges(Set.of(), true, null);
		}
		Set<UnitKey> changed = new HashSet<>();
		for (Map.Entry<UnitKey, ObjectNode> entry : model.entrySet()) {
			UnitKey unit = entry.getKey();
			UnitSnapshot known = baseline.known(unit);
			boolean differs = known == null || known.deleted() || !baseline.isShown(unit)
					|| !entry.getValue().equals(known.payload());
			if (differs && !(derived.containsKey(unit) && entry.getValue().equals(derived.get(unit)))) {
				changed.add(unit);
			}
		}
		for (UnitKey unit : baseline.shownUnits(event.getId())) {
			if (!model.containsKey(unit) && !(derived.containsKey(unit) && derived.get(unit) == null)) {
				changed.add(unit);
			}
		}
		return new LocalChanges(changed, false, model);
	}

	/**
	 * Ob das Modell schon den fremden Stand der Einheit hat: derselbe Inhalt, oder die Einheit fehlt im Modell und ist auch
	 * fremd gelöscht bzw. ohne Version. Etwa nach einem Hallenwechsel, dessen Buchungen nach der Vorstellung eintreffen: Der
	 * Abgleich hat sie schon so umgesetzt wie der andere Rechner. Dann gibt es nichts zurückzustellen; der Stand geht still
	 * in den Vergleichsstand (das Einarbeiten ändert am Modell nichts).
	 */
	private static boolean converged(LocalChanges changes, UnitSnapshot current) {
		if (changes.model() == null) {
			return false;
		}
		ObjectNode mine = changes.model().get(current.unit());
		if (mine == null) {
			return UnitMapper.isAbsent(current);
		}
		return current.version() != null && !current.deleted() && mine.equals(current.payload());
	}

	/** Übernimmt den Stand in den Vergleichsstand; was das Einarbeiten an der Einheit umgesetzt hatte, gilt damit nicht mehr. */
	private void adopt(String eventId, UnitSnapshot snapshot, boolean shown) {
		baseline.adopt(eventId, snapshot, shown);
		derived.remove(snapshot.unit());
	}

	private void forgetDerived(String eventId) {
		derived.keySet().removeIf(unit -> eventId.equals(unit.eventId()));
	}

	/**
	 * Merkt sich, was das Einarbeiten neben den übernommenen Einheiten im Modell geändert hat (der Abgleich nach einem
	 * Hallen- bzw. Bestuhlungswechsel): Diese Inhalte stammen nicht vom Nutzer (siehe {@link #derived}). Unter einer
	 * Vorstellung, deren Wechsel eingearbeitet wird, ist nichts lokal verändert oder in Bearbeitung, sonst wäre er
	 * zurückgestellt.
	 */
	private void noteDerived(Event event, Map<UnitKey, ObjectNode> before, Set<UnitKey> adopted) {
		Map<UnitKey, ObjectNode> after;
		try {
			after = UnitMapper.payloads(event);
		} catch (IllegalStateException e) {
			return;
		}
		for (Map.Entry<UnitKey, ObjectNode> entry : after.entrySet()) {
			UnitKey unit = entry.getKey();
			if (!adopted.contains(unit) && !entry.getValue().equals(before.get(unit))) {
				derived.put(unit, entry.getValue());
			}
		}
		for (UnitKey unit : before.keySet()) {
			if (!after.containsKey(unit) && !adopted.contains(unit) && baseline.isShown(unit)) {
				derived.put(unit, null);
			}
		}
	}

	/** Entscheidet und arbeitet ein für die gemeldeten Einheiten eines Events. */
	private void applyForeign(List<UnitSnapshot> snapshots, Map<UnitKey, List<ChainStep>> chains, List<String> warnings) {
		String eventId = snapshots.get(0).unit().eventId();
		Event event = cachedEvent(eventId);
		List<UnitSnapshot> successors = new ArrayList<>();
		// Vorstellungen, deren Buchungen warten: Hallen- bzw. Bestuhlungswechsel oder Löschen zurückgestellt oder Gabelung
		Set<UnitKey> heldPresentations = new HashSet<>();
		for (UnitSnapshot current : snapshots) {
			UnitKey unit = current.unit();
			UnitSnapshot known = baseline.known(unit);
			ForeignDecision decision = ForeignDecision.decideUnblocked(known, current, chainOf(chains, unit));
			if (decision == ForeignDecision.KNOWN && notYetPlaced(event, known)) {
				// bekannt, aber nie ins Modell gekommen (etwa eine Buchung, die auf ihren Platz wartet): noch einzuarbeiten
				decision = ForeignDecision.APPLY;
			}
			forkedParents.remove(unit);
			if (decision != ForeignDecision.APPLY) {
				deferred.remove(unit);
				held.remove(unit);
			}
			if (decision == ForeignDecision.KNOWN) {
				baseline.adoptStatus(eventId, current, warnings);
			} else if (decision == ForeignDecision.FORK) {
				LOG.info("Fremder Stand von " + unit.relativePath()
						+ " nicht eingearbeitet: Die eigene Version ist unterlegen (Gabelung).");
				if (event != null && unit.type() == UnitType.PRESENTATION && EventMerge.touchesSubtree(event, current)) {
					heldPresentations.add(unit);
					forkedParents.add(unit);
				}
			} else if (decision == ForeignDecision.APPLY) {
				successors.add(current);
			}
		}
		if (successors.isEmpty()) {
			return;
		}

		// "lokal verändert" kostet das Zerlegen des Events: erst prüfen, wenn ein Nachfolger da ist
		LocalChanges changes = event == null ? new LocalChanges(Set.of(), false, null) : localChanges(event);
		// Kopf, Vorstellungen, Buchungen: Was mit einer Vorstellung geschieht, entscheidet über ihre Buchungen
		successors.sort(EventMerge.MERGE_ORDER);
		Map<UnitKey, UnitSnapshot> appliedPresentations = new HashMap<>();
		List<UnitSnapshot> apply = new ArrayList<>();
		for (UnitSnapshot current : successors) {
			UnitKey unit = current.unit();
			if (isBlocked(event, current, changes)) {
				held.remove(unit);
				defer(event, current, chainOf(chains, unit));
				if (unit.type() == UnitType.PRESENTATION && EventMerge.touchesSubtree(event, current)) {
					heldPresentations.add(unit);
				}
			} else if (unit.type() == UnitType.BOOKING
					&& heldPresentations.contains(EventMerge.presentationKey(unit))) {
				// gehört zur neuen Bestuhlung bzw. zur verschwindenden Vorstellung: erst mit ihr einarbeiten
				deferred.remove(unit);
				hold(unit, "ihre Vorstellung ist zurückgestellt");
			} else if (unit.type() == UnitType.BOOKING && event != null && !EventMerge.hasPlace(event, current,
					appliedPresentations.get(EventMerge.presentationKey(unit)), hallLookup)) {
				deferred.remove(unit);
				hold(unit, "ihr Sitz fehlt in der Bestuhlung der Vorstellung");
			} else {
				deferred.remove(unit);
				held.remove(unit);
				apply.add(current);
				if (unit.type() == UnitType.PRESENTATION) {
					appliedPresentations.put(unit, current);
				}
			}
		}
		if (apply.isEmpty()) {
			return;
		}

		UnitSnapshot head = apply.stream().filter(s -> s.unit().type() == UnitType.EVENT).findFirst().orElse(null);
		boolean headReadable = head != null && UnitMapper.readUnit(head, Event.class) != null;
		if (event == null && headReadable) {
			addEvent(eventId, apply, warnings);
		} else if (event == null) {
			// ohne sichtbares Event übernimmt nur der Vergleichsstand; angezeigt wird nichts
			for (UnitSnapshot snapshot : apply) {
				adopt(eventId, snapshot, false);
			}
			warnIfUnreadable(head, warnings);
		} else if (head != null && !headReadable) {
			removeEvent(event, head, apply, warnings);
		} else {
			mergeInto(event, apply, changes, chains, warnings);
		}
	}

	/**
	 * Ob die Einheit im Vergleichsstand einen lesbaren Stand hat, der nie angezeigt wurde, obwohl ihre Eltern-Einheit im
	 * Modell steht: etwa eine Buchung, die vor ihrer Vorstellung ankam und dann nicht zur Bestuhlung passte. Sie gilt
	 * nicht als bekannt, sonst fiele sie still heraus und ihr Sitz sähe frei aus.
	 */
	private boolean notYetPlaced(Event event, UnitSnapshot known) {
		if (event == null || known == null || baseline.isShown(known.unit()) || !UnitMapper.isReadable(known)) {
			return false;
		}
		UnitKey unit = known.unit();
		return switch (unit.type()) {
		case BOOKING -> EventMerge.presentation(event, unit.presentationId()) != null;
		case PRESENTATION -> true;
		case EVENT, HALL -> false;
		};
	}

	/** Lässt die Buchung still warten (siehe {@link #held}); geloggt wird beim ersten Mal. */
	private void hold(UnitKey unit, String reason) {
		if (held.add(unit)) {
			LOG.info("Fremder Stand von " + unit.relativePath() + " wartet: " + reason + ".");
		}
	}

	private static List<ChainStep> chainOf(Map<UnitKey, List<ChainStep>> chains, UnitKey unit) {
		return chains.getOrDefault(unit, List.of());
	}

	/**
	 * Ob der Nutzer die Einheit ungespeichert geändert hat oder gerade bearbeitet; verlässt Kopf oder Vorstellung das
	 * Modell oder wechselt eine Vorstellung Halle oder Bestuhlung, zählt jede Einheit darunter. Hat das Modell den fremden
	 * Stand schon ({@link #converged}), ist nichts blockiert.
	 */
	private boolean isBlocked(Event event, UnitSnapshot current, LocalChanges changes) {
		if (event == null || converged(changes, current)) {
			return false;
		}
		if (changes.all()) {
			return true;
		}
		UnitKey unit = current.unit();
		Object model = EventMerge.modelObject(event, unit);
		if (changes.units().contains(unit) || (model != null && inEditing.test(model))) {
			return true;
		}
		if (!EventMerge.touchesSubtree(event, current)) {
			return false;
		}
		for (UnitKey changed : changes.units()) {
			if (EventMerge.inSubtree(changed, unit)) {
				return true;
			}
		}
		for (Object below : EventMerge.subtree(event, unit)) {
			if (inEditing.test(below)) {
				return true;
			}
		}
		return false;
	}

	/** Stellt den Stand zurück; der Hinweis nennt den Rechner aus dem letzten Glied der Kette. */
	private void defer(Event event, UnitSnapshot current, List<ChainStep> chain) {
		String machineName = chain.isEmpty() ? null : chain.get(chain.size() - 1).machineName();
		deferred.put(current.unit(), new Deferral(current.unit(), current.version(), machineName,
				EventMerge.nearestModelObject(event, current.unit())));
	}

	private Event cachedEvent(String eventId) {
		for (Event event : cachedEvents) {
			if (event.getId().equals(eventId)) {
				return event;
			}
		}
		return null;
	}

	/**
	 * Ein neues oder wiederbelebtes Event: aus dem Index zusammensetzen, Vergleichsstand wie beim Laden, in den Cache.
	 * Buchungen, die nicht zur Bestuhlung passen, warten (siehe {@link EventMerge#assembleEvent}).
	 */
	private void addEvent(String eventId, List<UnitSnapshot> apply, List<String> warnings) {
		try {
			Map<UnitKey, UnitSnapshot> all = sync.eventSnapshots(eventId);
			List<UnitSnapshot> waiting = new ArrayList<>();
			Assembly<Event> assembly = EventMerge.assembleEvent(all.values(), hallLookup, waiting);
			baseline.resetOwner(eventId, all.values(), assembly.shown());
			forgetDerived(eventId);
			warnings.addAll(assembly.warnings());
			waiting.forEach(snapshot -> hold(snapshot.unit(), "sie passt nicht zur Bestuhlung ihrer Vorstellung"));
			for (Event added : assembly.items()) {
				// einsortieren wie beim Laden
				int index = 0;
				while (index < cachedEvents.size()
						&& UnitMapper.EVENT_ORDER.compare(cachedEvents.get(index), added) <= 0) {
					index++;
				}
				cachedEvents.add(index, added);
				listeners.notifyAddedByOthers(added);
			}
		} catch (RuntimeException e) {
			// es hat sich nichts geändert; die Einheiten warten und kommen beim nächsten Aufruf wieder
			failed(eventId, e, warnings);
			for (UnitSnapshot snapshot : apply) {
				hold(snapshot.unit(), "das Zusammensetzen des Events ist gescheitert");
			}
		}
	}

	/** Kopf gelöscht oder unlesbar: Das Event verlässt samt Kindern den Cache, wie beim Laden unter einem solchen Kopf. */
	private void removeEvent(Event event, UnitSnapshot head, List<UnitSnapshot> apply, List<String> warnings) {
		String eventId = event.getId();
		cachedEvents.remove(event);
		hideBelow(eventId, UnitKey.event(eventId));
		for (UnitSnapshot snapshot : apply) {
			adopt(eventId, snapshot, false);
		}
		forgetDerived(eventId);
		warnIfUnreadable(head, warnings);
		listeners.notifyRemovedByOthers(event);
	}

	/** Was unter Kopf bzw. Vorstellung angezeigt war, ist es nicht mehr: Es hat mit ihnen das Modell verlassen. */
	private void hideBelow(String eventId, UnitKey root) {
		for (UnitKey unit : baseline.shownUnits(eventId)) {
			UnitSnapshot known = baseline.known(unit);
			if (known != null && !unit.equals(root) && EventMerge.inSubtree(unit, root)) {
				adopt(eventId, known, false);
			}
		}
	}

	private static void warnIfUnreadable(UnitSnapshot head, List<String> warnings) {
		if (head != null && !UnitMapper.isAbsent(head) && UnitMapper.readUnit(head, Event.class) == null) {
			warnings.add(UnitMapper.unreadableEvent(head.unit().eventId()));
		}
	}

	/**
	 * Arbeitet die Stände in das Event im Cache ein; jede Einheit geht gleich danach in den Vergleichsstand. Scheitert eine
	 * Einheit, ist sie im Modell wieder wie vorher ({@link EventMerge#apply}); ein sauberes Event wird dann aus dem Index
	 * neu zusammengesetzt, sonst bleibt, was schon eingearbeitet ist, und der Rest wird zurückgestellt. Auf
	 * {@code changingByOthers} folgt immer {@code changedByOthers}.
	 */
	private void mergeInto(Event event, List<UnitSnapshot> apply, LocalChanges changes,
			Map<UnitKey, List<ChainStep>> chains, List<String> warnings) {
		String eventId = event.getId();
		boolean newPresentation = apply.stream().anyMatch(s -> s.unit().type() == UnitType.PRESENTATION
				&& EventMerge.presentation(event, s.unit().presentationId()) == null);
		Collection<UnitSnapshot> index = newPresentation ? sync.eventSnapshots(eventId).values() : List.of();
		EventMerge.Plan plan = new EventMerge.Plan(apply, index, hallLookup);
		Set<UnitKey> adopted = new LinkedHashSet<>();
		Set<UnitKey> waiting = new HashSet<>();
		EventMerge.Sink sink = new EventMerge.Sink() {
			@Override
			public void adopted(UnitSnapshot snapshot, boolean shown) {
				adopt(eventId, snapshot, shown);
				adopted.add(snapshot.unit());
			}

			@Override
			public void held(UnitSnapshot snapshot) {
				waiting.add(snapshot.unit());
				hold(snapshot.unit(), "ihr Sitz fehlt in der Bestuhlung der Vorstellung");
			}

			@Override
			public void left(UnitKey presentation) {
				hideBelow(eventId, presentation);
			}

			@Override
			public void warning(String text) {
				warnings.add(text);
			}
		};
		listeners.notifyChangingByOthers(event);
		try {
			EventMerge.apply(event, plan, sink);
		} catch (RuntimeException e) {
			failed(event.getName(), e, warnings);
			if (mayRebuild(event, changes) && rebuild(event, warnings)) {
				return;
			}
			for (UnitSnapshot snapshot : apply) {
				if (!adopted.contains(snapshot.unit()) && !waiting.contains(snapshot.unit())) {
					defer(event, snapshot, chainOf(chains, snapshot.unit()));
				}
			}
			plan = plan.restrictedTo(adopted);
		}
		// nur eine Vorstellung kann über den Abgleich Buchungen mitändern
		if (changes.model() != null && apply.stream().anyMatch(s -> s.unit().type() == UnitType.PRESENTATION)) {
			noteDerived(event, changes.model(), adopted);
		}
		EventMerge.Plan applied = plan;
		listeners.notifyChangedByOthers(event,
				new ForeignBatch<>(adopted, copy -> EventMerge.apply(copy, applied, EventMerge.NONE)));
	}

	/**
	 * Ob das Event nach gescheitertem Einarbeiten aus dem Index neu zusammengesetzt werden darf: nur wenn es sauber ist,
	 * also nichts lokal verändert, nichts davon zurückgestellt oder wartend und kein Objekt darin in Bearbeitung (eine
	 * offene Eingabe schriebe sonst in ein abgehängtes Objekt).
	 */
	private boolean mayRebuild(Event event, LocalChanges changes) {
		String eventId = event.getId();
		if (!changes.none() || deferred.keySet().stream().anyMatch(unit -> eventId.equals(unit.eventId()))
				|| held.stream().anyMatch(unit -> eventId.equals(unit.eventId()))) {
			return false;
		}
		try {
			return EventMerge.subtree(event, UnitKey.event(eventId)).stream().noneMatch(inEditing);
		} catch (RuntimeException e) {
			// ohne verlässliche Auskunft gilt das Event als in Bearbeitung; changedByOthers folgt trotzdem
			LOG.log(Level.SEVERE, "Ob \"" + event.getName() + "\" gerade bearbeitet wird, ließ sich nicht prüfen", e);
			return false;
		}
	}

	/**
	 * Nach gescheitertem Einarbeiten: setzt das Event aus dem Index neu zusammen, Vergleichsstand wie beim Laden, und
	 * ersetzt es im Cache. Die Kopie für den Änderungsmelder bekommt genau diesen Stand.
	 *
	 * @return ob das gelang; sonst ist nichts verändert
	 */
	private boolean rebuild(Event event, List<String> warnings) {
		String eventId = event.getId();
		try {
			Collection<UnitSnapshot> all = sync.eventSnapshots(eventId).values();
			List<UnitSnapshot> waiting = new ArrayList<>();
			Assembly<Event> assembly = EventMerge.assembleEvent(all, hallLookup, waiting);
			if (assembly.items().isEmpty()) {
				return false;
			}
			Event fresh = assembly.items().get(0);
			ObjectMapper mapper = JsonMapperFactory.create();
			byte[] state = mapper.writeValueAsBytes(fresh);
			baseline.resetOwner(eventId, all, assembly.shown());
			forgetDerived(eventId);
			warnings.addAll(assembly.warnings());
			replaceCachedEvent(fresh);
			// das frische Event zeigt jeden Stand des Index, außer den Buchungen, die nicht zur Bestuhlung passen
			deferred.keySet().removeIf(unit -> eventId.equals(unit.eventId()));
			held.removeIf(unit -> eventId.equals(unit.eventId()));
			forkedParents.removeIf(unit -> eventId.equals(unit.eventId()));
			waiting.forEach(snapshot -> hold(snapshot.unit(), "sie passt nicht zur Bestuhlung ihrer Vorstellung"));
			Set<UnitKey> units = new HashSet<>();
			all.forEach(snapshot -> units.add(snapshot.unit()));
			listeners.notifyChangedByOthers(fresh, new ForeignBatch<>(units, copy -> {
				try {
					Event rebuilt = mapper.readValue(state, Event.class);
					copy.copyFrom(rebuilt);
					copy.setPresentations(rebuilt.getPresentations());
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			}));
			return true;
		} catch (JsonProcessingException | RuntimeException e) {
			LOG.log(Level.SEVERE, "Das Event " + eventId + " ließ sich nicht aus dem Index neu zusammensetzen", e);
			return false;
		}
	}

	private static void failed(String name, RuntimeException e, List<String> warnings) {
		LOG.log(Level.SEVERE, "Fremde Änderungen an \"" + name + "\" ließen sich nicht einarbeiten", e);
		warnings.add("Die Änderungen eines anderen Rechners an \"" + name + "\" ließen sich nicht einarbeiten: "
				+ StoreMessages.describe(e));
	}

	// <-- Fremde Stände
}
