package de.eltviller_carneval_verein.karten.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.eltviller_carneval_verein.karten.store.UnitState.Status;

/**
 * Löst verlorene eigene Strecken auf: Je Strecke entsteht eine Zusammenführung mit den Vorgängern {@code [Deren, Meine]},
 * deren Inhalt die {@link ConflictPolicy} der Einheitenart bestimmt (ohne Strategie: "Deren bleibt", Vermerk
 * {@link ConflictNote.Kind#UNMERGED}). Danach ist "Meine" laut {@link ChainResolver} zusammengeführt und fällt aus der
 * Ableitung der Verluste; eine eigene Merkliste braucht es nicht, denn ein Neustart nach einem Absturz vor dem Schreiben
 * findet die Strecke wieder.
 * <p>
 * Der {@link SyncService} reiht jede Einheit ein, für die er einen Verlust meldet, und arbeitet die Warteschlange im Takt
 * nach dem Hochladen und beim Start ab. Je Einheit wird der Stand aus den Dateien gelesen und für jede verlorene Strecke
 * Basis, Meine und Deren gebildet:
 * <ul>
 * <li>Im gemeinsamen Betrieb wartet eine Strecke, solange eine fremde Version der Kette seit der Basis nicht über die
 * Änderungsliste ihres Rechners eingelesen ist (der Speichervorgang des Gewinners könnte unvollständig da sein),
 * höchstens {@link #LIST_WAIT} seit dem ersten Versuch; danach wird mit dem aufgelöst, was da ist (Log WARNING). Im
 * Einzelbetrieb und für eigene Versionen gilt jede Version als eingelesen.</li>
 * <li>Fehlt die Basis lokal oder ist sie noch in keinem Glied der Kette aufgegangen (die Zusammenführung, die sie enthält,
 * ist noch nicht beansprucht), wartet die Strecke, bis sich die Einheit ändert: Dann meldet der Dienst den Verlust
 * erneut. Steht die Basis in einer anderen verlorenen Strecke, kommt die Strecke nach deren Auflösung dran.</li>
 * <li>Eine gesperrte oder wartende Kette wird übersprungen: {@code LOCKED_FORMAT} bis zum neueren Programm,
 * {@code LOCKED_DAMAGED} bis die Heilung oder {@link SyncService#resetToLastReadable} eine neue Version schreibt (dann
 * meldet der Dienst den Verlust erneut), {@code WAITING} bis die fehlende Version da ist.</li>
 * </ul>
 * Geschrieben wird über {@link SyncService#append} mit einer neuen {@code saveId} je Strecke: zuerst die weiteren
 * Änderungen der {@link Resolution}, dann die Zusammenführung. Eine weitere neue Einheit (ohne Vorgänger) entfällt, sobald
 * die Einheit irgendeine Version hat, eine weitere Löschung, wenn die Einheit schon gelöscht ist: Bricht das Programm
 * zwischen beiden ab, wiederholt der nächste Versuch die Auflösung, ohne eine zweite Erstversion anzulegen, die gegen die
 * erste gabelte (auch wenn die Strategie den Inhalt beim zweiten Mal etwas anders liefert).
 * <p>
 * Fehler: Wirft die Strategie oder liefert sie ohne Löschung keinen Inhalt, wird nichts geschrieben (Log SEVERE, Fußzeile
 * einmal je Einheit und Lauf), und die Einheit wird nicht erneut eingereiht. Scheitert das Lesen oder Schreiben, bleibt
 * die Einheit eingereiht und wird im nächsten Takt erneut versucht; Log WARNING einmal je Einheit und Störung, SYS-006 in
 * der Fußzeile einmal je Störung (sie endet, wenn jede gestörte Einheit wieder durchläuft).
 * <p>
 * Rückmeldung: Die geschriebenen Einheiten und geänderten Konflikte meldet ein Durchlauf gesammelt am Ende, nicht je
 * Einheit (siehe {@link #resolvePending}).
 * <p>
 * Threads: {@link #enqueue} von jedem Thread; {@link #resolvePending} serialisiert sich selbst und läuft im Takt bzw.
 * beim Start. Die Auflösung fasst nie Modell, Vergleichsstand oder Tracker an.
 */
final class ConflictResolver {

	private static final Logger LOGGER = Logger.getLogger(SyncService.class.getName());

	/** So lange wartet eine Strecke höchstens darauf, dass die Änderungslisten der Kette seit der Basis gelesen sind. */
	static final Duration LIST_WAIT = Duration.ofMinutes(5);
	/** So viele Zusammenführungen schreibt ein Durchlauf höchstens je Einheit; die Einheit kommt dann im nächsten wieder. */
	private static final int MAX_MERGES_PER_UNIT = 20;

	/** Was die Auflösung vom Dienst braucht. */
	interface Host {

		/** Dateien und Stand der Einheit aus dem lokalen Bestand, eingetragen in den Index. */
		SyncService.Resolved resolve(UnitKey unit) throws IOException;

		/** Ob ein gemeinsamer Ordner verbunden ist. */
		boolean shared();

		/** Ob die fremde Version über die Änderungsliste ihres Rechners eingelesen wurde. */
		boolean isListed(UnitKey unit, VersionId version);

		/** Die ID dieses Rechners. */
		String machineId();

		/**
		 * Die anderen Buchungs-Einheiten derselben Vorstellung aus dem Start-Index (Inhalte nicht kopiert, ohne
		 * Speichervorgänge; die ergänzt die Auflösung).
		 */
		Map<UnitKey, ConflictInput.Sibling> siblings(UnitKey booking);

		/**
		 * Die Einheiten unter {@code units}, die laut Start-Index eigene Versionen außerhalb ihrer gültigen Kette haben:
		 * verlorene, ausstehende oder in eine Zusammenführung aufgegangene. Nur in ihrem Ordner kann das Ziel eines
		 * eigenen Umzugs liegen, der dort nicht gilt.
		 */
		Set<UnitKey> withOwnVersionsBesideChain(Collection<UnitKey> units);

		/** Schreibt die Änderungen wie {@link SyncService#append}. */
		List<Version> append(String saveId, List<Change> changes) throws IOException;

		/** Der Text von SYS-006 für einen Fehler beim Lesen oder Schreiben, wie der Dienst ihn sonst meldet. */
		String failureText(Exception failure);
	}

	/** Was mit einer verlorenen Strecke in diesem Durchlauf geschieht. */
	private enum Step {
		/** Zusammenführen. */
		MERGE,
		/** Auf die Änderungslisten warten: Die Einheit bleibt eingereiht. */
		WAIT,
		/** Nach einer anderen Strecke derselben Einheit. */
		LATER,
		/** Nicht jetzt; erst wenn sich die Einheit ändert, kommt sie wieder. */
		SKIP
	}

	/** Eine vorbereitete Strecke; Basis, Meine, Deren und Kette nur bei {@link Step#MERGE}. */
	private record Stretch(Step step, Version base, Version mine, Version theirs, List<Version> chainSinceBase) {

		static Stretch of(Step step) {
			return new Stretch(step, null, null, null, List.of());
		}
	}

	private final Host host;
	private final SyncNotifier notifier;
	private final Clock clock;
	private final Map<UnitType, ConflictPolicy> policies = new ConcurrentHashMap<>();
	/** Die eingereihten Einheiten in der Reihenfolge des Einreihens; zugleich die Sperre dafür. */
	private final Set<UnitKey> queue = new LinkedHashSet<>();
	/** Serialisiert {@link #resolvePending}; alles Folgende gehört dem, der sie hält. */
	private final ReentrantLock running = new ReentrantLock();
	/** Seit wann eine Einheit auf Änderungslisten wartet. */
	private final Map<UnitKey, Instant> waitingSince = new HashMap<>();
	/** Die Einheiten, deren Strategie in diesem Programmlauf schon in der Fußzeile gemeldet wurde. */
	private final Set<UnitKey> reportedFailures = new HashSet<>();
	/**
	 * Die Einheiten, deren Lesen oder Schreiben in der laufenden Störung scheiterte; eine Einheit fällt heraus, sobald sie
	 * wieder durchläuft. Ist die Menge leer, ist keine Störung im Gang.
	 */
	private final Set<UnitKey> disrupted = new HashSet<>();

	ConflictResolver(Host host, SyncNotifier notifier, Clock clock) {
		this.host = host;
		this.notifier = notifier;
		this.clock = clock;
	}

	/** Setzt die Strategie einer Einheitenart; {@code null} entfernt sie ("Deren bleibt"). */
	void setPolicy(UnitType type, ConflictPolicy policy) {
		Objects.requireNonNull(type, "type");
		if (policy == null) {
			policies.remove(type);
		} else {
			policies.put(type, policy);
		}
	}

	/** Reiht eine Einheit ein; schon eingereihte behalten ihren Platz. */
	void enqueue(UnitKey unit) {
		synchronized (queue) {
			queue.add(unit);
		}
	}

	/** Reiht Einheiten ein. */
	void enqueue(Collection<UnitKey> units) {
		synchronized (queue) {
			queue.addAll(units);
		}
	}

	/**
	 * Löst höchstens {@code budget} eingereihte Einheiten auf, die zuerst eingereihten zuerst; der Rest bleibt in der
	 * Schlange. Eine Einheit, die auf Änderungslisten wartet, kommt wieder ans Ende.
	 * <p>
	 * Gemeldet wird einmal je Durchlauf, am Ende (auch bei einem unerwarteten Fehler): die geschriebenen Einheiten in einem
	 * {@link SyncNotifier#unitsChanged} und, wenn eine geschriebene Version einen Vermerk trägt, danach ein
	 * {@link SyncNotifier#conflictsChanged}. Jeder Rückruf lädt auf dem FX-Thread die Ansichten neu, je Einheit gemeldet
	 * kostete das bei vielen Zusammenführungen einen ganzen Takt.
	 */
	void resolvePending(int budget) {
		running.lock();
		Written written = new Written();
		try {
			List<UnitKey> units = new ArrayList<>();
			synchronized (queue) {
				Iterator<UnitKey> it = queue.iterator();
				while (it.hasNext() && units.size() < budget) {
					units.add(it.next());
					it.remove();
				}
			}
			List<UnitKey> again = new ArrayList<>();
			for (UnitKey unit : units) {
				if (resolveUnit(unit, written)) {
					again.add(unit);
				}
			}
			enqueue(again);
		} finally {
			try {
				notifier.unitsChanged(written.units);
				if (written.noted) {
					notifier.conflictsChanged();
				}
			} finally {
				running.unlock();
			}
		}
	}

	/** Was die Auflösung eines Durchlaufs geschrieben hat, für die Rückmeldung. */
	private static final class Written {
		/** Die Einheiten der geschriebenen (oder bei einem Fehler: versuchten) Änderungen. */
		final Set<UnitKey> units = new LinkedHashSet<>();
		/** Ob eine geschriebene Version einen Vermerk trägt. */
		boolean noted;
	}

	/**
	 * Löst die verlorenen Strecken einer Einheit auf und trägt die geschriebenen Einheiten und einen etwaigen Vermerk in
	 * {@code written} ein; gemeldet wird erst am Ende des Durchlaufs. Scheitert Lesen oder Schreiben, bleibt die Einheit
	 * eingereiht (siehe {@link #disrupted}); ein unerwarteter Fehler steht im Log (WARNING), und die Einheit kommt erst wieder,
	 * wenn der Dienst den Verlust erneut meldet.
	 *
	 * @return ob die Einheit wieder eingereiht wird
	 */
	private boolean resolveUnit(UnitKey unit, Written written) {
		boolean again = false;
		try {
			again = mergeStretches(unit, written);
			disrupted.remove(unit);
		} catch (IOException | UncheckedIOException e) {
			again = true;
			reportDisruption(unit, e);
		} catch (RuntimeException e) {
			LOGGER.log(Level.WARNING, "Der Konflikt an " + unit.relativePath() + " ließ sich nicht zusammenführen", e);
		} finally {
			if (!again) {
				// die Einheit verlässt die Schlange: Ein späteres Warten beginnt von vorn
				waitingSince.remove(unit);
			}
		}
		return again;
	}

	/**
	 * Ein Fehler beim Lesen oder Schreiben: Log WARNING einmal je Einheit und Störung, SYS-006 in der Fußzeile einmal je
	 * Störung. Die Störung beginnt mit der ersten gestörten Einheit und endet, wenn jede wieder durchläuft.
	 */
	private void reportDisruption(UnitKey unit, Exception failure) {
		boolean beginning = disrupted.isEmpty();
		if (!disrupted.add(unit)) {
			LOGGER.log(Level.FINE, "Der Konflikt an " + unit.relativePath() + " ließ sich weiter nicht zusammenführen", failure);
			return;
		}
		LOGGER.log(Level.WARNING, "Der Konflikt an " + unit.relativePath()
				+ " ließ sich nicht zusammenführen; der nächste Takt versucht es erneut", failure);
		if (beginning) {
			notifier.error(host.failureText(failure));
		}
	}

	/**
	 * Führt die verlorenen Strecken einer Einheit nacheinander zusammen; nach jeder Zusammenführung wird der Stand neu
	 * gelesen (eine Strecke kann dadurch wachsen oder eine neue entstehen, wenn die Zusammenführung sofort verliert).
	 *
	 * @return ob die Einheit wieder eingereiht wird: Eine Strecke wartet auf Änderungslisten, oder die Höchstzahl der
	 *         Zusammenführungen ist erreicht
	 */
	private boolean mergeStretches(UnitKey unit, Written written) throws IOException {
		Set<VersionId> tried = new HashSet<>();
		boolean waiting = false;
		for (int merges = 0; merges < MAX_MERGES_PER_UNIT; merges++) {
			SyncService.Resolved resolved = host.resolve(unit);
			UnitState state = resolved.state();
			if (state.status() != Status.OK) {
				// gesperrt oder wartend: Ändert sich die Einheit, meldet der Dienst den Verlust erneut
				return false;
			}
			Stretch merge = null;
			for (List<VersionId> lost : state.lost()) {
				VersionId tip = lost.get(lost.size() - 1);
				if (tried.contains(tip)) {
					continue;
				}
				Stretch stretch = prepare(unit, resolved.files(), state, lost);
				if (stretch.step() != Step.LATER) {
					tried.add(tip);
				}
				if (stretch.step() == Step.WAIT) {
					waiting = true;
				} else if (stretch.step() == Step.MERGE) {
					merge = stretch;
					break;
				}
			}
			if (merge == null) {
				return waiting;
			}
			Resolution resolution = decide(unit, merge);
			if (resolution == null) {
				return false;
			}
			List<Change> changes = changes(unit, merge, resolution);
			changes.forEach(change -> written.units.add(change.unit()));
			host.append(UUID.randomUUID().toString(), changes);
			written.noted |= changes.stream().anyMatch(change -> change.note() != null);
		}
		return true;
	}

	/** Bildet Basis, Meine und Deren einer verlorenen Strecke und entscheidet, ob sie jetzt zusammengeführt wird. */
	private Stretch prepare(UnitKey unit, UnitFiles files, UnitState state, List<VersionId> lost) {
		Version oldest = versionOf(files, lost.get(0));
		Version mine = versionOf(files, lost.get(lost.size() - 1));
		if (oldest == null || mine == null) {
			return Stretch.of(Step.SKIP);
		}
		VersionId baseId = oldest.firstParent();
		if (baseId != null && state.lost().stream().anyMatch(other -> other != lost && other.contains(baseId))) {
			// eigene Verzweigung: Die Strecke baut auf einer anderen verlorenen auf und kommt nach deren Auflösung dran
			return Stretch.of(Step.LATER);
		}
		Version base = baseId == null ? null : versionOf(files, baseId);
		if (baseId != null && base == null) {
			LOGGER.fine("Die Basis " + baseId + " von " + unit.relativePath() + " fehlt lokal; die Auflösung wartet");
			return Stretch.of(Step.SKIP);
		}
		List<Version> since = chainSinceBase(files, state.chain(), baseId);
		if (since.isEmpty()) {
			// die Basis ist noch in keinem Glied der Kette aufgegangen (ihre Zusammenführung ist noch nicht beansprucht)
			return Stretch.of(Step.SKIP);
		}
		if (mustWait(unit, since)) {
			return Stretch.of(Step.WAIT);
		}
		return new Stretch(Step.MERGE, base, mine, state.head(), since);
	}

	private static Version versionOf(UnitFiles files, VersionId id) {
		VersionFile file = files.versions().get(id);
		return file == null ? null : file.version();
	}

	/**
	 * Die Glieder der Kette ab dem ersten, das die Basis über seine Vorgänger enthält (ohne Basis die ganze Kette). Leer,
	 * wenn kein Glied die Basis enthält.
	 */
	private static List<Version> chainSinceBase(UnitFiles files, List<Version> chain, VersionId baseId) {
		if (baseId == null) {
			return chain;
		}
		Set<VersionId> descendants = descendantsOf(files, baseId);
		for (int i = 0; i < chain.size(); i++) {
			if (descendants.contains(chain.get(i).id())) {
				return chain.subList(i, chain.size());
			}
		}
		return List.of();
	}

	/** Die lesbaren Versionen, die die Version über beliebige Vorgänger enthalten, ohne sie selbst. */
	private static Set<VersionId> descendantsOf(UnitFiles files, VersionId ancestor) {
		Set<VersionId> reached = new HashSet<>(Set.of(ancestor));
		boolean grown = true;
		while (grown) {
			grown = false;
			for (VersionFile file : files.versions().values()) {
				Version version = file.version();
				if (version != null && !reached.contains(version.id())
						&& version.parents().stream().anyMatch(reached::contains)) {
					reached.add(version.id());
					grown = true;
				}
			}
		}
		reached.remove(ancestor);
		return reached;
	}

	/**
	 * Ob die Strecke noch wartet: im gemeinsamen Betrieb, solange eine fremde Version der Kette seit der Basis nicht über
	 * ihre Änderungsliste eingelesen ist, höchstens {@link #LIST_WAIT} seit dem ersten Versuch.
	 */
	private boolean mustWait(UnitKey unit, List<Version> since) {
		if (!host.shared()) {
			return false;
		}
		String me = host.machineId();
		boolean unlisted = since.stream().anyMatch(v -> !me.equals(v.machine()) && !host.isListed(unit, v.id()));
		if (!unlisted) {
			return false;
		}
		Instant now = clock.instant();
		Instant first = waitingSince.computeIfAbsent(unit, key -> now);
		if (now.isBefore(first.plus(LIST_WAIT))) {
			return true;
		}
		LOGGER.warning("Die Änderungsliste zu einer Version von " + unit.relativePath()
				+ " ist auch nach 5 Minuten nicht gelesen; der Konflikt wird mit dem lokalen Stand aufgelöst.");
		return false;
	}

	/**
	 * Fragt die Strategie (ohne Strategie: "Deren bleibt"). Wirft sie, liefert sie nichts oder ohne Löschung keinen Inhalt
	 * (das würde als leerer Inhalt geschrieben), steht es im Log (SEVERE) und einmal je Einheit und Lauf in der Fußzeile.
	 *
	 * @return die Entscheidung; {@code null}, wenn die Strategie scheiterte
	 * @throws IOException wenn sich der Ordner einer Geschwister-Einheit nicht lesen lässt (die Einheit bleibt eingereiht)
	 */
	private Resolution decide(UnitKey unit, Stretch stretch) throws IOException {
		ConflictPolicy policy = policies.get(unit.type());
		if (policy == null) {
			return unmerged(stretch.mine(), stretch.theirs());
		}
		Map<UnitKey, ConflictInput.Sibling> siblings = unit.type() == UnitType.BOOKING
				? siblings(unit, stretch.base(), stretch.mine())
				: Map.of();
		try {
			ConflictInput input = new ConflictInput(unit, copy(stretch.base()), copy(stretch.mine()),
					copy(stretch.theirs()), stretch.chainSinceBase().stream().map(ConflictResolver::copy).toList(),
					host.machineId(), siblings);
			Resolution resolution = Objects.requireNonNull(policy.resolve(input), "Die Strategie lieferte kein Ergebnis.");
			if (!resolution.deleted() && resolution.payload() == null) {
				throw new IllegalStateException("Die Strategie lieferte keinen Inhalt.");
			}
			return resolution;
		} catch (RuntimeException e) {
			String message = "Ein Konflikt an " + unit.relativePath() + " ließ sich nicht auflösen: "
					+ StoreMessages.describe(e);
			LOGGER.log(Level.SEVERE, message, e);
			if (reportedFailures.add(unit)) {
				notifier.error(message);
			}
			return null;
		}
	}

	/** Ohne Strategie: Deren bleibt, der Vermerk sagt diesem Rechner, dass sein Inhalt weicht. */
	private Resolution unmerged(Version mine, Version theirs) {
		ConflictNote note = new ConflictNote(ConflictNote.Kind.UNMERGED, host.machineId(),
				new ConflictNote.Party(theirs.machine(), theirs.machineName(), theirs.operator(), theirs.id()), List.of(),
				null, null, mine.id());
		return new Resolution(theirs.payload(), theirs.deleted(), List.of(), note);
	}

	/**
	 * Die zu schreibenden Änderungen: zuerst die weiteren, die noch nicht da sind (eine Wiederholung nach einem Absturz legt
	 * eine weitere Einheit nicht doppelt an), dann die Zusammenführung {@code [Deren, Meine]}. Jeder Vermerk bekommt den
	 * Speichervorgang von "Meine" ({@link ConflictNote#lostSave()}): Die Zusammenführungen bekommen je Strecke einen neuen,
	 * die Konflikte eines verlorenen Speichervorgangs gehören im Dialog aber zusammen.
	 */
	private List<Change> changes(UnitKey unit, Stretch stretch, Resolution resolution) throws IOException {
		String lostSave = stretch.mine().saveId();
		List<Change> changes = new ArrayList<>();
		for (Change extra : resolution.extra()) {
			if (!alreadyThere(extra)) {
				changes.add(extra.note() == null ? extra
						: new Change(extra.unit(), extra.parents(), extra.payload(), extra.deleted(),
								extra.note().withLostSave(lostSave), extra.seen()));
			}
		}
		ConflictNote note = resolution.note() == null ? null : resolution.note().withLostSave(lostSave);
		changes.add(new Change(unit, List.of(stretch.theirs().id(), stretch.mine().id()), resolution.payload(),
				resolution.deleted(), note, seenOf(unit, resolution, stretch.mine(), stretch.theirs())));
		return changes;
	}

	/**
	 * Ob die weitere Änderung schon geschrieben ist: eine neue Einheit (ohne Vorgänger), sobald die Einheit irgendeine
	 * Version hat (ein Inhaltsvergleich hinge an der JSON-Darstellung, und eine zweite Erstversion gabelte ohnehin); eine
	 * Löschung, wenn die Einheit schon gelöscht ist oder keinen Stand hat; jede andere Änderung, wenn der aktuelle Stand
	 * denselben Inhalt hat.
	 */
	private boolean alreadyThere(Change extra) throws IOException {
		Version current = host.resolve(extra.unit()).state().current();
		if (extra.deleted()) {
			return current == null || current.deleted();
		}
		if (extra.parents().isEmpty()) {
			return current != null;
		}
		return current != null && !current.deleted() && Objects.equals(current.payload(), extra.payload());
	}

	/**
	 * {@code seen} der Zusammenführung, wenn sie einen Event-Kopf oder eine Vorstellung löscht: die der gewinnenden Löschung
	 * (zuerst Deren, sonst Meine), damit der Vergleich mit den Verkäufen der anderen Rechner auf dem Stand des Löschenden
	 * bleibt. Sonst {@code null} (die Positionen dieses Rechners).
	 */
	private static Map<String, Long> seenOf(UnitKey unit, Resolution resolution, Version mine, Version theirs) {
		boolean parent = unit.type() == UnitType.EVENT || unit.type() == UnitType.PRESENTATION;
		if (!resolution.deleted() || !parent) {
			return null;
		}
		if (theirs.deleted()) {
			return theirs.seen();
		}
		return mine.deleted() ? mine.seen() : null;
	}

	// --- Kopien für die Strategie: Die Inhalte aus Dateien und Index gehören dem Index

	private static Version copy(Version version) {
		if (version == null) {
			return null;
		}
		return new Version(version.format(), version.id(), version.parents(), version.machine(), version.machineName(),
				version.operator(), version.at(), version.saveId(), version.deleted(),
				version.payload() == null ? null : version.payload().deepCopy(), copy(version.conflict()), version.seen());
	}

	private static ConflictNote copy(ConflictNote note) {
		if (note == null) {
			return null;
		}
		List<ConflictNote.FieldConflict> fields = note.fields().stream()
				.map(f -> new ConflictNote.FieldConflict(f.group(), f.mine().deepCopy(), f.theirs().deepCopy())).toList();
		return new ConflictNote(note.kind(), note.affected(), note.other(), fields, note.moved(),
				note.place() == null ? null : note.place().deepCopy(), note.dropped(), note.lostSave());
	}

	/**
	 * Die anderen Buchungs-Einheiten derselben Vorstellung als Kopien. Trägt eine davon die Buchung der Basis (gleiche
	 * Buchungs-ID, nicht gelöscht), bekommt sie die Speichervorgänge aller lesbaren Versionen in ihrem Ordner: Daran erkennt
	 * die Strategie ein Verschieben, auch wenn die Kopie seitdem geändert wurde oder noch aussteht. Dasselbe bekommt die
	 * Einheit {@code _<ID>} der Buchung von "Meine", auch gelöscht: Daran erkennt die Strategie, dass "Meine" von dort
	 * hierher zugewiesen wurde (Ruling R31a). Ist "Meine" eine
	 * Löschung, bekommt eine Einheit, die die Buchung nicht trägt, das verlorene Ziel eines Umzugs aus dem Speichervorgang
	 * von "Meine" ({@link ConflictInput.Sibling#lostMove()}). Dateien werden nur für die Träger (meist keiner oder einer)
	 * und für Einheiten mit eigenen Versionen außerhalb ihrer Kette gelesen (laut Index; meist keine).
	 */
	private Map<UnitKey, ConflictInput.Sibling> siblings(UnitKey unit, Version base, Version mine) throws IOException {
		String bookingId = base == null || base.deleted() ? null : bookingIdOf(base.payload());
		String mineId = mine.deleted() ? null : bookingIdOf(mine.payload());
		UnitKey ownUnassigned = mineId == null ? null
				: UnitKey.unassignedBooking(unit.eventId(), unit.presentationId(), mineId);
		Map<UnitKey, ConflictInput.Sibling> fromIndex = host.siblings(unit);
		Set<UnitKey> withOwnBeside = bookingId != null && mine.deleted() && mine.saveId() != null
				? host.withOwnVersionsBesideChain(fromIndex.keySet())
				: Set.of();
		Map<UnitKey, ConflictInput.Sibling> copies = new LinkedHashMap<>();
		for (Map.Entry<UnitKey, ConflictInput.Sibling> entry : fromIndex.entrySet()) {
			ConflictInput.Sibling sibling = entry.getValue();
			UnitSnapshot s = sibling.snapshot();
			UnitSnapshot copy = new UnitSnapshot(s.unit(), s.version(), s.status(), s.deleted(),
					s.payload() == null ? null : s.payload().deepCopy());
			boolean carriesBooking = bookingId != null && s.version() != null && !s.deleted()
					&& bookingId.equals(bookingIdOf(s.payload()));
			Set<String> saveIds = carriesBooking || entry.getKey().equals(ownUnassigned) ? saveIdsOf(entry.getKey())
					: Set.of();
			Version lostMove = !carriesBooking && withOwnBeside.contains(entry.getKey())
					? lostMoveIn(entry.getKey(), bookingId, mine.saveId())
					: null;
			copies.put(entry.getKey(), new ConflictInput.Sibling(copy, sibling.head(), saveIds, lostMove));
		}
		return copies;
	}

	/**
	 * Die jüngste lesbare Version im Ordner der Einheit aus dem Speichervorgang {@code saveId}, die die Buchung trägt (nicht
	 * gelöscht, gleiche Buchungs-ID), als Kopie; {@code null}, wenn es keine gibt.
	 */
	private Version lostMoveIn(UnitKey unit, String bookingId, String saveId) throws IOException {
		Version found = null;
		for (VersionFile file : host.resolve(unit).files().versions().values()) {
			Version version = file.version();
			if (version != null && !version.deleted() && saveId.equals(version.saveId())
					&& bookingId.equals(bookingIdOf(version.payload()))
					&& (found == null || version.id().compareTo(found.id()) > 0)) {
				found = version;
			}
		}
		return copy(found);
	}

	/** Die Buchungs-ID im Inhalt einer Buchung ({@link ConflictInput#BOOKING_ID}); {@code null}, wenn sie fehlt. */
	private static String bookingIdOf(ObjectNode payload) {
		JsonNode id = payload == null ? null : payload.get(ConflictInput.BOOKING_ID);
		return id != null && id.isTextual() ? id.asText() : null;
	}

	/** Die Speichervorgänge aller lesbaren Versionen im Ordner der Einheit. */
	private Set<String> saveIdsOf(UnitKey unit) throws IOException {
		Set<String> saveIds = new HashSet<>();
		for (VersionFile file : host.resolve(unit).files().versions().values()) {
			Version version = file.version();
			if (version != null && version.saveId() != null) {
				saveIds.add(version.saveId());
			}
		}
		return saveIds;
	}
}
