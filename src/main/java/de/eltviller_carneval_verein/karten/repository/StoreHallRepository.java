package de.eltviller_carneval_verein.karten.repository;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
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

import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.repository.UnitMapper.Assembly;
import de.eltviller_carneval_verein.karten.store.ChainStep;
import de.eltviller_carneval_verein.karten.store.StoreMessages;
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

	private static final Logger LOG = Logger.getLogger(StoreHallRepository.class.getName());

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

	/** Die zurückgestellten fremden Stände; {@link #applyForeign} prüft sie bei jedem Aufruf erneut. */
	private final Map<UnitKey, Deferral> deferred = new LinkedHashMap<>();

	/** Einheiten, bei denen das Einarbeiten scheiterte; sie kommen bei jedem Aufruf von {@link #applyForeign} wieder dran. */
	private final Set<UnitKey> failedUnits = new LinkedHashSet<>();

	/**
	 * Die IDs der Hallen, die ein fremder Stand (gelöscht oder unlesbar) aus dem Cache genommen hat. Hält eine Ansicht eine
	 * solche Halle noch, lehnt {@link #saveHall} sie ab: Sie schriebe ihren alten Stand über den fremden. Kommt die Halle
	 * wieder in den Cache, gilt das nicht mehr.
	 */
	private final Set<String> removedByOthers = new HashSet<>();

	/** Ob eine Halle gerade in Bearbeitung ist oder davon abhängt (siehe {@link #setBlockedCheck}). */
	private Predicate<Hall> blockedCheck = hall -> false;

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
		removedByOthers.clear();
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
		// Ein fremder Stand hat das Objekt im Cache ersetzt oder entfernt: Das alte zu speichern überschriebe ihn
		Hall cached = cachedHall(hall.getId());
		if ((cached != null && cached != hall) || (cached == null && removedByOthers.contains(hall.getId()))) {
			throw LockedUnitException.replacedMeanwhile(hall.getName());
		}
		write(hall);
	}

	/** Schreibt die Halle, ohne zu prüfen, ob sie das Objekt im Cache ist (das Wiederherstellen setzt den Cache selbst). */
	private void write(Hall hall) {
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
		return commitRestore(prepareRestoreHall(hallId, point), adjust);
	}

	/**
	 * Der erste Schritt des Wiederherstellens, geeignet für einen Hintergrund-Thread: liest die Halle frisch, prüft, dass sie
	 * nicht gesperrt ist und nicht auf eine Datei wartet, und setzt den Zielstand zusammen. Berührt weder Cache noch
	 * Vergleichsstand noch Listener und schreibt nichts. Den Änderungszähler des Vergleichsstands liest es zuerst, damit
	 * {@link #commitRestore} jede Änderung seither bemerkt.
	 *
	 * @throws LockedUnitException wie {@link #restoreHall}; es wurde nichts verändert
	 */
	public RestorePlan<Hall> prepareRestoreHall(String hallId, SavePoint point) throws IOException {
		long changes = baseline.changes(hallId);
		List<UnitState> states = List.of(sync.unitState(UnitKey.hall(hallId)));
		RestorePlanner.requireRestorable(states);
		return new RestorePlan<>(hallId, states, targetOf(states, point), changes);
	}

	/**
	 * Der zweite Schritt, auf dem Oberflächen-Thread: Hat sich der Vergleichsstand der Halle seit der Vorbereitung geändert
	 * (etwa durch Speichern), wird abgelehnt. Sonst wird der Zielstand mit {@code adjust} angepasst, die vorbereitete Halle
	 * wird Vergleichsstand und angezeigter Stand und der Zielstand wird wie jede Halle gespeichert; Einzelheiten und
	 * Fehlerverhalten wie bei {@link #restoreHall}.
	 *
	 * @throws LockedUnitException wenn sich die Halle seit der Vorbereitung geändert hat; es wurde nichts geschrieben
	 */
	public Restored<Hall> commitRestore(RestorePlan<Hall> plan, Function<Hall, List<String>> adjust) {
		if (baseline.changes(plan.ownerId()) != plan.changes()) {
			throw new LockedUnitException("Die Halle wurde inzwischen geändert. Bitte erneut wiederherstellen.");
		}
		ensureLoaded();
		Hall target = plan.target();
		List<String> notes = adjust.apply(target);
		refreshFromStore(plan.ownerId(), plan.states());
		write(target);
		replaceCachedHall(target);
		return new Restored<>(target, notes);
	}

	/** Der Änderungszähler des Vergleichsstands der Halle (nur für Tests). */
	long baselineChanges(String hallId) {
		return baseline.changes(hallId);
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
		removedByOthers.remove(hallId);
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

	/** Meldet einen Listener ab (etwa wenn eine Ansicht verlassen wird). */
	public void removeListener(RepositoryListener<Hall> listener) {
		listeners.remove(listener);
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

	// Fremde Stände -->

	/**
	 * Wer entscheidet, ob eine Halle gerade nicht ersetzt werden darf, obwohl sie nicht ungespeichert verändert ist (in der
	 * App: der Hallen-Editor ist in Bearbeitung, oder Buchungen in Vorstellungen dieser Halle sind ungespeichert). Ein
	 * fremder Stand dafür wird zurückgestellt. Gilt zusätzlich zum eigenen "lokal verändert" der Halle. Vorgabe: nie.
	 */
	public void setBlockedCheck(Predicate<Hall> blocked) {
		this.blockedCheck = blocked;
	}

	/**
	 * Arbeitet die Stände ein, die andere Rechner für diese Einheiten geschrieben haben (Spec KARV-85, "Entscheidung je
	 * Einheit", "Einarbeiten" und "Fehlerfälle"). Nur Hallen zählen; die zurückgestellten Einheiten früherer Aufrufe werden
	 * jedes Mal mit geprüft. Je Einheit gilt {@link ForeignDecision}: Ein Nachfolger des Vergleichsstands in der gültigen
	 * Kette wird eingearbeitet, außer die Halle ist ungespeichert verändert oder gesperrt durch {@link #setBlockedCheck};
	 * dann wird er zurückgestellt, der Vergleichsstand bleibt. Eine eingearbeitete Halle ersetzt das Objekt im Cache (sie
	 * ist nie lokal verändert, sonst wäre sie zurückgestellt); neue und gelöschte Hallen kommen in den Cache bzw. verlassen
	 * ihn. Ist noch nichts geladen, passiert nichts: Das erste Laden liest ohnehin den aktuellen Stand.
	 * <p>
	 * Nur auf dem Oberflächen-Thread aufrufen: Modell und Vergleichsstand sind nicht synchronisiert.
	 *
	 * @return alle danach zurückgestellten Stände und die Warnungen für den Nutzer
	 */
	public ForeignResult applyForeign(Collection<UnitKey> units) {
		if (cachedHalls == null) {
			return new ForeignResult(List.of(), List.of());
		}
		Set<UnitKey> wanted = new LinkedHashSet<>();
		for (UnitKey unit : units) {
			if (unit.type() == UnitType.HALL) {
				wanted.add(unit);
			}
		}
		wanted.addAll(deferred.keySet());
		wanted.addAll(failedUnits);
		List<String> warnings = new ArrayList<>();
		if (!wanted.isEmpty()) {
			Map<UnitKey, UnitSnapshot> current = sync.snapshots(wanted);
			// was der Index nicht mehr kennt, lässt sich nicht einarbeiten
			deferred.keySet().retainAll(current.keySet());
			failedUnits.retainAll(current.keySet());
			try {
				Map<UnitKey, List<ChainStep>> chains = sync.chains(current.keySet());
				for (UnitKey unit : wanted) {
					UnitSnapshot snapshot = current.get(unit);
					if (snapshot == null) {
						continue;
					}
					// ein Fehler bei einer Halle hält die übrigen nicht auf; sie kommt beim nächsten Mal wieder
					try {
						applyForeign(snapshot, chains.getOrDefault(unit, List.of()), warnings);
					} catch (RuntimeException e) {
						failed(unit, e, warnings);
						failedUnits.add(unit);
					}
				}
			} catch (IOException e) {
				LOG.log(Level.WARNING, "Die Ketten fremd geänderter Hallen ließen sich nicht lesen", e);
				warnings.add("Änderungen von einem anderen Rechner ließen sich nicht einarbeiten: " + StoreMessages.describe(e));
			}
		}
		return new ForeignResult(new ArrayList<>(deferred.values()), warnings);
	}

	/** Versucht die zurückgestellten Stände erneut, etwa nach dem Speichern oder wenn eine Bearbeitung endet. */
	public ForeignResult retryDeferred() {
		return applyForeign(List.of());
	}

	/** Der Stand der Einheit im Vergleichsstand (nur für Tests). */
	UnitSnapshot knownUnit(UnitKey unit) {
		return baseline.known(unit);
	}

	/** Entscheidet und arbeitet ein für eine Halle. */
	private void applyForeign(UnitSnapshot current, List<ChainStep> chain, List<String> warnings) {
		UnitKey unit = current.unit();
		String hallId = unit.hallId();
		UnitSnapshot known = baseline.known(unit);
		switch (ForeignDecision.decideUnblocked(known, current, chain)) {
		case KNOWN -> {
			settle(unit);
			baseline.adoptStatus(hallId, current, warnings);
		}
		case FORK -> {
			settle(unit);
			LOG.info("Fremder Stand von " + unit.relativePath()
					+ " nicht eingearbeitet: Die eigene Version ist unterlegen (Gabelung).");
		}
		case APPLY -> applyNewer(current, known, chain, warnings);
		default -> settle(unit);
		}
	}

	/** Die Einheit ist erledigt: Sie ist weder zurückgestellt noch war ihr Einarbeiten gescheitert. */
	private void settle(UnitKey unit) {
		deferred.remove(unit);
		failedUnits.remove(unit);
	}

	/** Arbeitet den Nachfolger des Vergleichsstands ein, außer die Halle ist verändert oder gesperrt: Dann wird er zurückgestellt. */
	private void applyNewer(UnitSnapshot current, UnitSnapshot known, List<ChainStep> chain, List<String> warnings) {
		UnitKey unit = current.unit();
		String hallId = unit.hallId();
		Hall old = cachedHall(hallId);
		if (old != null && isBlocked(old, known)) {
			deferred.put(unit, Deferral.of(unit, current.version(), chain, old));
			return;
		}
		settle(unit);

		Hall fresh = UnitMapper.readUnit(current, Hall.class);
		if (fresh == null) {
			// gelöscht oder unlesbar: wie beim Laden unsichtbar
			baseline.adopt(hallId, current, false);
			if (!UnitMapper.isAbsent(current)) {
				warnings.add(UnitMapper.unreadableHall(hallId));
			}
			if (old != null) {
				cachedHalls.remove(old);
				removedByOthers.add(hallId);
				listeners.notifyRemovedByOthers(old);
			}
			return;
		}
		// eine gesperrte Halle mit lesbarer Version wird wie beim Laden geladen, mit Warnung
		String locked = UnitMapper.lockMessage(unit, current.status());
		if (locked != null) {
			warnings.add(locked);
		}
		if (old != null) {
			listeners.notifyChangingByOthers(old);
			baseline.adopt(hallId, current, true);
			cachedHalls.remove(old);
			UnitMapper.insertSorted(cachedHalls, fresh, UnitMapper.HALL_ORDER);
			listeners.notifyChangedByOthers(fresh, new ForeignBatch<>(Set.of(unit), copy -> {
				throw new UnsupportedOperationException(
						"Eine eingearbeitete Halle ist nie lokal verändert: Es gibt nichts nachzuspielen.");
			}));
		} else {
			baseline.adopt(hallId, current, true);
			removedByOthers.remove(hallId);
			UnitMapper.insertSorted(cachedHalls, fresh, UnitMapper.HALL_ORDER);
			listeners.notifyAddedByOthers(fresh);
		}
	}

	/**
	 * Ob die Halle nicht ersetzt werden darf: Sie ist ungespeichert verändert (ihr Inhalt weicht vom Vergleichsstand ab, oder
	 * es gibt ihn nur auf einer Seite) oder {@link #setBlockedCheck} sagt es.
	 */
	private boolean isBlocked(Hall hall, UnitSnapshot known) {
		return locallyChanged(hall, known) || blockedCheck.test(hall);
	}

	private boolean locallyChanged(Hall hall, UnitSnapshot known) {
		if (known == null || known.deleted() || !baseline.isShown(UnitKey.hall(hall.getId()))) {
			return true;
		}
		try {
			return !UnitMapper.decompose(hall).payload().equals(known.payload());
		} catch (IllegalStateException e) {
			// ohne verlässlichen Vergleich gilt die Halle als verändert
			return true;
		}
	}

	private Hall cachedHall(String hallId) {
		for (Hall hall : cachedHalls) {
			if (hallId.equals(hall.getId())) {
				return hall;
			}
		}
		return null;
	}

	private void failed(UnitKey unit, RuntimeException e, List<String> warnings) {
		Hall hall = cachedHall(unit.hallId());
		String name = hall != null && hall.getName() != null ? hall.getName() : unit.hallId();
		LOG.log(Level.SEVERE, "Fremde Änderungen an der Halle \"" + name + "\" ließen sich nicht einarbeiten", e);
		warnings.add("Die Änderungen eines anderen Rechners an der Halle \"" + name + "\" ließen sich nicht einarbeiten: "
				+ StoreMessages.describe(e));
	}

	// <-- Fremde Stände

}
