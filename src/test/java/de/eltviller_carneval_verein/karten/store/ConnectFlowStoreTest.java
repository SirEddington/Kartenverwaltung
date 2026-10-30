package de.eltviller_carneval_verein.karten.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.eltviller_carneval_verein.karten.store.ConnectOutcome.Kind;

/**
 * Der Verbindungsablauf im Versionsspeicher (KARV-103, Abschnitt E): das Ergebnis von {@code connect} am Ende des ersten
 * Laufs, der erste Lauf ohne Pause zwischen den Takten, die Marke "verbunden" und {@code connectionComplete()}; der Modus
 * ASK mit {@code decideConnect} und die Schutzregel, auch gegen Speichervorgänge, die mit dem Beiseitelegen zusammentreffen.
 * <p>
 * Die Takte, die {@code connect} einplant, landen in {@link Node#queued} und laufen nur, wenn der Test sie ausführt; sonst
 * löst der Test die Takte mit {@code tick()} selbst aus. Damit ein Lauf mehrere Takte braucht, kostet jeder Lesezugriff
 * {@link #READ_COST} auf der Test-Uhr.
 */
class ConnectFlowStoreTest {

	/** Ein fremder Rechner, der nur als Dateien im gemeinsamen Ordner vorkommt. */
	private static final String OTHER = "zzzzzzzzzzzzzzzzzzzzzzzzzz";
	private static final UnitKey BOOKING = UnitKey.booking("e1", "p1", "A-1");
	/** Die eigene Einheit aus einem früheren Programmlauf ({@link #nodeWithEarlierUnit}). */
	private static final UnitKey LOCAL_EVENT = UnitKey.event("lokal");
	/** So viel Zeit kostet ein Lesezugriff auf der Test-Uhr, sobald {@link Node#slowReads()} gilt. */
	private static final Duration READ_COST = Duration.ofMillis(20);
	private static final ObjectMapper MAPPER = new ObjectMapper();

	@TempDir
	Path dir;

	private final TestClock clock = new TestClock();
	private Path sharedRoot;
	private String shared;

	@BeforeEach
	void setUp() throws IOException {
		sharedRoot = Files.createDirectories(dir.resolve("gemeinsam"));
		shared = sharedRoot.toString();
	}

	/** Eine Rückfrage des Modus ASK ({@link SyncListener#connectDecisionNeeded}). */
	private record Question(String dir, boolean setAsideAllowed) {
	}

	/**
	 * Ein simulierter Rechner mit eigenem Datenordner und eigenem Dienst. Der gemeinsame Ordner ist erreichbar, solange er
	 * als Ordner da ist und {@link #down} nicht gesetzt ist; mit {@link #down} scheitert außerdem jeder Zugriff darauf.
	 */
	private final class Node {
		final Path appData;
		final FaultyFileLayer layer = new FaultyFileLayer(new NioFileLayer());
		final AtomicBoolean down = new AtomicBoolean();
		/** Die Takte, die der Dienst eingeplant hat (ohne eigenen Worker). */
		final List<Runnable> queued = new ArrayList<>();
		/** Die Rückfragen des Modus ASK; ein Test mit eigenem Empfänger zeichnet sie nicht auf. */
		final List<Question> asked = Collections.synchronizedList(new ArrayList<>());
		final SyncService service;

		Node(String name) throws IOException {
			this(dir.resolve(name), name, null);
		}

		/** @param worker der Thread für den Takt; {@code null} heißt: eingeplante Takte landen in {@link #queued} */
		Node(Path appData, String name, Executor worker) throws IOException {
			this.appData = appData;
			MachineIdentity identity = MachineIdentity.loadOrCreate(appData, () -> name);
			for (String operation : List.of("createNew", "read", "listNames", "listNamesWithTemps")) {
				layer.failWhen(p -> down.get() && p.startsWith(sharedRoot), operation, new IOException("Netzlaufwerk getrennt"));
			}
			this.service = new SyncService(appData, identity, layer, Runnable::run, worker != null ? worker : queued::add,
					p -> !down.get() && Files.isDirectory(p), clock);
			service.setListener(new SyncListener() {
				@Override
				public void connectDecisionNeeded(String folder, boolean setAsideAllowed) {
					asked.add(new Question(folder, setAsideAllowed));
				}
			});
		}

		/** Ab jetzt stellt jeder Lesezugriff die Test-Uhr um {@link #READ_COST} vor. */
		void slowReads() {
			layer.onEach("read", () -> clock.advance(READ_COST));
		}

		Path localData() {
			return appData.resolve("data");
		}

		Optional<ConnectionMarker.State> marker() {
			return ConnectionMarker.read(appData);
		}

		/** Führt den zuletzt eingeplanten Takt aus (einen Aufruf von {@code tickSafely}). */
		void runScheduled() {
			assertFalse(queued.isEmpty(), "kein Takt eingeplant");
			queued.remove(queued.size() - 1).run();
		}
	}

	// --- Hilfen

	/** Fremde Hallen mit Version und Anspruch im gemeinsamen Ordner, ohne Listen (sechs Abschnitte bei 300). */
	private void seedSharedHalls(int count) throws IOException {
		VersionStore sharedStore = new VersionStore(sharedRoot.resolve("data"), new NioFileLayer());
		long millis = clock.instant().toEpochMilli() - 3_600_000L;
		for (int i = 0; i < count; i++) {
			UnitKey hall = UnitKey.hall("f" + i);
			Version v = new Version(StoreCodec.FORMAT, VersionId.create(millis, OTHER, i), List.of(), OTHER, "Kasse", null,
					clock.instant(), "f" + i, false, MAPPER.createObjectNode().put("name", "Halle " + i));
			byte[] bytes = StoreCodec.write(v);
			assertTrue(sharedStore.writeVersion(hall, v.id(), bytes));
			assertTrue(sharedStore.createClaim(hall, "root",
					StoreCodec.write(new Claim(v.id(), StoreCodec.sha256(bytes), OTHER, clock.instant()))));
		}
	}

	private static Version save(Node node, UnitKey unit, String name) throws IOException {
		List<Version> saved = node.service.append(UUID.randomUUID().toString(),
				List.of(new Change(unit, List.of(), MAPPER.createObjectNode().put("name", name), false)));
		assertEquals(1, saved.size());
		return saved.get(0);
	}

	private Claim sharedClaim(UnitKey unit) throws IOException {
		return StoreCodec.readClaim(
				Files.readAllBytes(sharedRoot.resolve("data").resolve(unit.relativePath()).resolve("claims/root.json")));
	}

	private static List<String> asideFolders(Node node) throws IOException {
		try (Stream<Path> entries = Files.list(node.appData)) {
			return entries.map(p -> p.getFileName().toString()).filter(n -> n.startsWith("data-beiseite-")).sorted().toList();
		}
	}

	/** Kopiert den Ordner mit allem darunter; liefert das Ziel. */
	private static Path copyTree(Path from, Path to) throws IOException {
		try (Stream<Path> entries = Files.walk(from)) {
			for (Path entry : entries.toList()) {
				Path target = to.resolve(from.relativize(entry).toString());
				if (Files.isDirectory(entry)) {
					Files.createDirectories(target);
				} else {
					Files.copy(entry, target);
				}
			}
		}
		return to;
	}

	/** Takte, bis kein Vollabgleich mehr läuft; liefert, was {@code tick()} jeweils meldete. */
	private static List<Boolean> tickUntilRunEnds(Node node) {
		List<Boolean> results = new ArrayList<>();
		do {
			results.add(node.service.tick());
			assertTrue(results.size() < 100, "der Lauf endet nicht");
		} while (node.service.fullSyncProgress().get() != null);
		return results;
	}

	/** Ein Rechner im Einzelbetrieb, gestartet, mit 300 fremden Hallen im gemeinsamen Ordner und langsamen Lesezugriffen. */
	private Node slowNodeBesideHalls() throws IOException {
		seedSharedHalls(300);
		Node a = new Node("a");
		assertTrue(a.service.startup(""));
		a.slowReads();
		return a;
	}

	private static ConnectOutcome outcome(Kind kind) {
		return new ConnectOutcome(kind, false, null, null);
	}

	private static ConnectOutcome decisionNeeded(boolean setAsideAllowed) {
		return new ConnectOutcome(Kind.ENTSCHEIDUNG, setAsideAllowed, null, null);
	}

	/** Ein Neustart auf dem Datenordner des Rechners, gestartet im Einzelbetrieb; der alte Dienst bleibt unbenutzt liegen. */
	private Node restart(Node node, String name) throws IOException {
		Node restarted = new Node(node.appData, name, null);
		assertTrue(restarted.service.startup(""));
		return restarted;
	}

	/**
	 * Ein Rechner im Einzelbetrieb mit der eigenen Einheit {@link #LOCAL_EVENT} aus einem früheren Programmlauf: Dieser Lauf
	 * hat nach seinem Start nichts gespeichert, das Beiseitelegen ist erlaubt (Schutzregel).
	 */
	private Node nodeWithEarlierUnit(String name) throws IOException {
		Node earlier = new Node(name);
		assertTrue(earlier.service.startup(""));
		save(earlier, LOCAL_EVENT, "A");
		return restart(earlier, name);
	}

	/** Alle Dateien und Ordner unter dem Ordner, relativ und sortiert. */
	private static List<String> tree(Path folder) throws IOException {
		try (Stream<Path> entries = Files.walk(folder)) {
			return entries.map(p -> folder.relativize(p).toString()).sorted().toList();
		}
	}

	private Path sharedUnit(UnitKey unit) {
		return sharedRoot.resolve("data").resolve(unit.relativePath());
	}

	// --- Ergebnis am Ende des ersten Laufs

	@Test
	void connectCompletesAtTheEndOfTheFirstRunNotAfterTheFirstTick() throws IOException {
		Node a = slowNodeBesideHalls();

		CompletableFuture<ConnectOutcome> result = a.service.connect(shared, ConnectMode.MERGE);
		assertFalse(result.isDone(), "vor dem ersten Takt");
		a.service.tick();
		assertNotNull(a.service.fullSyncProgress().get(), "der Lauf braucht mehrere Takte");
		assertFalse(result.isDone(), "nach dem ersten Takt ist der Lauf nicht zu Ende");

		tickUntilRunEnds(a);

		assertEquals(outcome(Kind.AKTUELL), result.getNow(null));
		assertEquals(300, a.service.hallIds().size());
		assertEquals(SyncState.AKTUELL, a.service.syncState().get());
	}

	@Test
	void firstRunOfAConnectionRunsTicksWithoutPause() throws IOException {
		Node a = slowNodeBesideHalls();
		CompletableFuture<ConnectOutcome> result = a.service.connect(shared, ConnectMode.MERGE);
		assertEquals(1, a.queued.size(), "connect plant einen Takt ein");

		assertTrue(a.service.tick(), "erster Lauf, Ordner erreichbar: der nächste Takt folgt sofort");
		assertFalse(result.isDone());
		String folderCheck = "isDirectory " + sharedRoot;
		long before = a.layer.calls().stream().filter(folderCheck::equals).count();
		a.runScheduled();

		assertNull(a.service.fullSyncProgress().get(), "ein Aufruf von tickSafely erledigt den Lauf");
		assertEquals(outcome(Kind.AKTUELL), result.getNow(null));
		long ticks = a.layer.calls().stream().filter(folderCheck::equals).count() - before;
		assertTrue(ticks >= 2, "mehrere Takte ohne Pause in einem Aufruf: " + ticks);
		assertFalse(a.service.tick(), "nach dem ersten Lauf gilt wieder die Pause");
	}

	@Test
	void laterRunsKeepThePause() throws IOException {
		seedSharedHalls(300);
		Node a = new Node("a");
		a.service.startup("");
		CompletableFuture<ConnectOutcome> result = a.service.connect(shared, ConnectMode.MERGE);
		// ohne Kosten erledigt ein Takt den ersten Lauf
		a.runScheduled();
		assertEquals(outcome(Kind.AKTUELL), result.getNow(null));
		a.slowReads();
		clock.advance(SyncService.FULL_SYNC_INTERVAL);

		assertFalse(a.service.tick(), "der zweite Lauf hält die Pause ein");
		assertNotNull(a.service.fullSyncProgress().get(), "der zweite Lauf braucht mehrere Takte");
		assertFalse(tickUntilRunEnds(a).contains(true));
	}

	// --- Marke "verbunden"

	@Test
	void markerIsIncompleteDuringAndCompleteAfterTheFirstRun() throws IOException {
		Node a = slowNodeBesideHalls();
		assertEquals(Optional.empty(), a.marker());

		a.service.connect(shared, ConnectMode.MERGE);
		assertEquals(Optional.empty(), a.marker(), "geschrieben erst am Beginn des Laufs");
		assertFalse(a.service.connectionComplete().get(), "ab connect");
		a.service.tick();

		ConnectionMarker.State during = a.marker().orElseThrow();
		assertEquals(shared, during.dir());
		assertFalse(during.complete());
		assertEquals(Optional.of(shared), a.service.connectedDir());
		assertFalse(a.service.connectionComplete().get());

		tickUntilRunEnds(a);

		ConnectionMarker.State after = a.marker().orElseThrow();
		assertEquals(shared, after.dir());
		assertTrue(after.complete());
		assertFalse(after.at().isBefore(during.at()));
		assertTrue(a.service.connectionComplete().get());
	}

	@Test
	void connectionCompleteFollowsMarker() throws IOException {
		assertTrue(new Node("leer").service.connectionComplete().get(), "ohne Marke");
		Node a = slowNodeBesideHalls();
		a.service.connect(shared, ConnectMode.MERGE);
		a.service.tick();

		// Neustart mitten im ersten Lauf, auf einer Kopie des Datenordners (der erste Dienst läuft weiter): Die Marke ist
		// unvollständig
		Path copy = copyTree(a.appData, dir.resolve("kopie"));
		Node restarted = new Node(copy, "a", null);
		assertFalse(restarted.service.connectionComplete().get());
		assertEquals(Optional.of(shared), restarted.service.connectedDir(), "auch ohne Start");
		// der Start ohne Ordner lässt die Marke stehen (KARV-87 trennt dafür ausdrücklich)
		assertTrue(restarted.service.startup(""));
		assertFalse(restarted.service.connectionComplete().get());
		assertFalse(restarted.marker().orElseThrow().complete());

		tickUntilRunEnds(a);
		assertTrue(a.service.connectionComplete().get());
		assertTrue(new Node(a.appData, "a", null).service.connectionComplete().get(), "vollständige Marke");

		a.service.disconnect();
		Node disconnected = new Node(a.appData, "a", null);
		assertTrue(disconnected.service.connectionComplete().get());
		assertEquals(Optional.empty(), disconnected.service.connectedDir());
	}

	// --- vorzeitige Ausgänge

	@Test
	void outcomeIsOfflineWhenTheFolderVanishesDuringTheFirstRun() throws IOException {
		seedSharedHalls(300);
		Node a = nodeWithEarlierUnit("a");
		a.slowReads();
		CompletableFuture<ConnectOutcome> result = a.service.connect(shared, ConnectMode.SET_ASIDE);
		assertTrue(a.service.tick());
		List<String> aside = asideFolders(a);
		assertEquals(1, aside.size());
		assertFalse(a.marker().orElseThrow().complete(), "die Marke steht nach dem Beiseitelegen");

		// der gemeinsame Ordner verschwindet mitten im ersten Lauf
		Path moved = Files.move(sharedRoot, dir.resolve("weg"));
		assertFalse(a.service.tick(), "nicht erreichbar: der nächste Takt wartet die Pause ab");

		assertEquals(new ConnectOutcome(Kind.OFFLINE, false, a.appData.resolve(aside.get(0)), StoreMessages.unreachable(shared)),
				result.getNow(null));
		assertEquals(SyncState.OFFLINE, a.service.syncState().get());
		assertFalse(a.service.connectionComplete().get());

		// zurück: Der Lauf setzt fort, sein Ende meldet connectionComplete; ein zweites Ergebnis gibt es nicht
		Files.move(moved, sharedRoot);
		tickUntilRunEnds(a);
		assertTrue(a.service.connectionComplete().get());
		assertTrue(a.marker().orElseThrow().complete());
		assertEquals(Kind.OFFLINE, result.getNow(null).kind());
		assertEquals(List.of(aside.get(0)), asideFolders(a), "nur einmal beiseitegelegt");
	}

	@Test
	void outcomeIsFehlerOnAFailedSlice() throws IOException {
		Node a = slowNodeBesideHalls();
		CompletableFuture<ConnectOutcome> result = a.service.connect(shared, ConnectMode.MERGE);
		assertTrue(a.service.tick());
		IllegalStateException broken = new IllegalStateException("Lesefehler in der Freigabe");
		a.layer.failNextUnchecked(p -> p.startsWith(sharedRoot), "read", broken);

		assertFalse(a.service.tick(), "nach einer Störung wartet der nächste Takt die Pause ab");

		assertEquals(new ConnectOutcome(Kind.FEHLER, false, null,
				StoreMessages.syncFailed(shared, StoreMessages.describe(broken))), result.getNow(null));
		assertEquals(SyncState.FEHLER, a.service.syncState().get());
		assertNotNull(a.service.fullSyncProgress().get(), "der Lauf ruht nur");
		// der Lauf setzt im nächsten Takt fort
		tickUntilRunEnds(a);
		assertTrue(a.service.connectionComplete().get());
		assertEquals(Kind.FEHLER, result.getNow(null).kind());
		assertEquals(300, a.service.hallIds().size());
	}

	@Test
	void disconnectCompletesAPendingOutcomeAsAbgebrochen() throws IOException {
		Node a = slowNodeBesideHalls();
		CompletableFuture<ConnectOutcome> result = a.service.connect(shared, ConnectMode.MERGE);
		assertTrue(a.service.tick());
		assertTrue(a.marker().isPresent());

		a.service.disconnect();

		assertEquals(outcome(Kind.ABGEBROCHEN), result.getNow(null));
		assertEquals(Optional.empty(), a.marker(), "die Marke ist gelöscht");
		assertEquals(Optional.empty(), a.service.connectedDir());
		assertTrue(a.service.connectionComplete().get());
		assertFalse(a.service.tick());
		assertEquals(SyncState.AUS, a.service.syncState().get());

		// noch vor dem ersten Takt getrennt, und ein leerer Pfad trennt ebenso: Sein Ergebnis kommt sofort
		CompletableFuture<ConnectOutcome> early = a.service.connect(shared, ConnectMode.MERGE);
		CompletableFuture<ConnectOutcome> empty = a.service.connect("  ", ConnectMode.MERGE);
		assertEquals(outcome(Kind.ABGEBROCHEN), empty.getNow(null));
		assertEquals(outcome(Kind.ABGEBROCHEN), early.getNow(null));
		assertNull(a.service.sharedDir());
		assertTrue(a.service.connectionComplete().get());
	}

	@Test
	void newConnectCompletesThePreviousOutcomeAsAbgebrochen() throws IOException {
		Node a = slowNodeBesideHalls();
		CompletableFuture<ConnectOutcome> first = a.service.connect(shared, ConnectMode.MERGE);
		assertTrue(a.service.tick());

		CompletableFuture<ConnectOutcome> second = a.service.connect(shared, ConnectMode.MERGE);

		assertEquals(outcome(Kind.ABGEBROCHEN), first.getNow(null));
		assertFalse(second.isDone());
		assertFalse(a.service.connectionComplete().get());
		a.runScheduled();
		assertEquals(outcome(Kind.AKTUELL), second.getNow(null));
		assertEquals(outcome(Kind.ABGEBROCHEN), first.getNow(null));
		assertTrue(a.service.connectionComplete().get());
	}

	@Test
	void outcomeArrivesEvenIfTheTickThatEndsTheFirstRunThrowsAfterwards() throws IOException {
		seedSharedHalls(3);
		Node a = new Node("a");
		a.service.startup("");
		// gleich nach dem Ende des Laufs wirft der nächste Lesezugriff unerwartet: in der Ableitung der Verluste am Ende des
		// Takts, die keine Absicherung des Durchlaufs umgibt
		AtomicBoolean armed = new AtomicBoolean();
		AtomicBoolean thrown = new AtomicBoolean();
		a.service.fullSyncProgress().addListener((property, before, after) -> {
			if (before != null && after == null) {
				armed.set(true);
			}
		});
		a.layer.onEach("read", () -> {
			if (armed.compareAndSet(true, false)) {
				thrown.set(true);
				throw new IllegalStateException("unerwarteter Fehler nach dem Ende des Laufs");
			}
		});
		CompletableFuture<ConnectOutcome> result = a.service.connect(shared, ConnectMode.MERGE);

		a.runScheduled();

		assertTrue(thrown.get(), "der Fehler kam nach dem Ende des Laufs");
		assertNull(a.service.fullSyncProgress().get());
		assertTrue(a.service.connectionComplete().get());
		assertEquals(outcome(Kind.AKTUELL), result.getNow(null), "das Ende des ersten Laufs liefert sein Ergebnis trotzdem");
	}

	@Test
	void startupWithoutFolderAbortsAPendingOutcome() throws IOException {
		Node a = new Node("a");
		a.service.startup("");
		CompletableFuture<ConnectOutcome> result = a.service.connect(shared, ConnectMode.MERGE);

		assertTrue(a.service.startup(""));

		assertEquals(outcome(Kind.ABGEBROCHEN), result.getNow(null));
		assertNull(a.service.sharedDir());
	}

	// --- Ruling 17: die Marke "verbunden" lässt sich nicht schreiben

	/** Ab jetzt scheitert (solange {@code broken} gilt) das Anlegen der temporären Datei der Marke "verbunden". */
	private static void breakMarker(Node node, AtomicBoolean broken) {
		node.layer.failWhen(p -> broken.get() && p.getFileName().toString().startsWith(FileLayer.TEMP_PREFIX + "connection-"),
				"createNew", new AccessDeniedException("connection.json gesperrt"));
	}

	private String markerWriteFailed(Node node) {
		return "Die Marke 'verbunden' ließ sich nicht schreiben: " + node.appData.resolve("connection.json");
	}

	@Test
	void markerWriteFailingAtTheBeginningFailsTheTickWithALocalText() throws IOException {
		seedSharedHalls(3);
		Node a = new Node("a");
		a.service.startup("");
		AtomicBoolean broken = new AtomicBoolean(true);
		breakMarker(a, broken);
		List<String> errors = new ArrayList<>();
		a.service.setListener(new SyncListener() {
			@Override
			public void error(String message) {
				errors.add(message);
			}
		});
		CompletableFuture<ConnectOutcome> result = a.service.connect(shared, ConnectMode.MERGE);

		assertFalse(a.service.tick());

		assertEquals(new ConnectOutcome(Kind.FEHLER, false, null, markerWriteFailed(a)), result.getNow(null));
		assertEquals(List.of(markerWriteFailed(a)), errors, "der Text nennt die lokale Datei, nicht den gemeinsamen Ordner");
		assertEquals(SyncState.FEHLER, a.service.syncState().get());
		assertNull(a.service.fullSyncProgress().get(), "kein Lauf ohne Marke");
		assertEquals(Optional.empty(), a.marker());
		assertFalse(a.service.connectionComplete().get());

		// der nächste Takt versucht es erneut
		broken.set(false);
		tickUntilRunEnds(a);
		assertTrue(a.marker().orElseThrow().complete());
		assertTrue(a.service.connectionComplete().get());
		assertEquals(Kind.FEHLER, result.getNow(null).kind());
	}

	@Test
	void markerWriteFailingAtTheEndStillEndsTheFirstRun() throws Exception {
		Node a = slowNodeBesideHalls();
		AtomicBoolean broken = new AtomicBoolean();
		breakMarker(a, broken);
		List<LogRecord> logs = new ArrayList<>();
		Handler capture = new Handler() {
			@Override
			public void publish(LogRecord record) {
				synchronized (logs) {
					logs.add(record);
				}
			}

			@Override
			public void flush() {
			}

			@Override
			public void close() {
			}
		};
		Logger logger = Logger.getLogger(SyncService.class.getName());
		logger.addHandler(capture);
		try {
			CompletableFuture<ConnectOutcome> result = a.service.connect(shared, ConnectMode.MERGE);
			assertTrue(a.service.tick());
			assertFalse(a.marker().orElseThrow().complete());
			broken.set(true);

			tickUntilRunEnds(a);

			assertEquals(outcome(Kind.AKTUELL), result.getNow(null), "das Ende kommt trotzdem");
			assertTrue(a.service.connectionComplete().get());
			assertEquals(SyncState.AKTUELL, a.service.syncState().get());
			assertFalse(a.marker().orElseThrow().complete(), "die Marke bleibt unvollständig: der nächste Start nimmt mit");
			// der Modus ist erledigt: kein weiterer Lauf
			assertFalse(a.service.tick());
			assertNull(a.service.fullSyncProgress().get());
			synchronized (logs) {
				assertTrue(logs.stream().anyMatch(r -> r.getLevel() == Level.WARNING && markerWriteFailed(a).equals(r.getMessage())),
						"WARNING mit dem Pfad der Marke");
			}
			assertFalse(new Node(a.appData, "a", null).service.connectionComplete().get(), "nach einem Neustart");
		} finally {
			logger.removeHandler(capture);
		}
	}

	// --- Ruling 10: die Entscheidung am Beginn des ersten Laufs gilt je Verbindung einmal

	@Test
	void setAsideDecisionIsTakenOnceEvenIfTheFirstBeginFails() throws IOException {
		seedSharedHalls(3);
		Node a = new Node("a");
		a.service.startup("");
		// die lokale Seite ist leer: nichts beiseitezulegen; danach scheitert das Bestimmen der Abschnitte einmal
		a.layer.failNextUnchecked(p -> p.startsWith(sharedRoot), "listNames", new IllegalStateException("Freigabe hakt"));
		CompletableFuture<ConnectOutcome> result = a.service.connect(shared, ConnectMode.SET_ASIDE);
		assertFalse(a.service.tick());
		assertNull(a.service.fullSyncProgress().get(), "kein Lauf begonnen");
		assertEquals(Kind.FEHLER, result.getNow(null).kind());

		// ein Verkauf, bevor der Beginn wiederholt wird: Jetzt hätten beide Seiten Einheiten
		Version sale = save(a, BOOKING, "Anna");
		a.service.tick();

		assertNull(a.service.fullSyncProgress().get(), "der Lauf ist zu Ende");
		assertEquals(List.of(), asideFolders(a), "nichts beiseitegelegt");
		assertTrue(Files.exists(a.localData().resolve(BOOKING.relativePath()).resolve(sale.id() + ".json")));
		assertEquals(sale.id(), a.service.unitState(BOOKING).head().id());
		assertEquals(sale.id(), sharedClaim(BOOKING).version(), "hochgeladen");
		assertEquals(3, a.service.hallIds().size());
		assertTrue(a.service.connectionComplete().get());
	}

	// --- Review Focus 3: die Arbeitssperre wird zwischen den Takten eines eiligen ersten Laufs frei

	/** Hält den Worker beim ersten Lesen an, nachdem der Lauf einen Abschnitt erledigt hat: im zweiten Takt, unter der Sperre. */
	private static final class Gate {
		final CountDownLatch paused = new CountDownLatch(1);
		final CountDownLatch go = new CountDownLatch(1);
	}

	/** Wartet, bis der Thread in der Warteschlange der Arbeitssperre steht (er hat sie dann nicht bekommen). */
	private static void awaitQueuedForTheWorkLock(SyncService service, Thread thread) throws Exception {
		Field field = SyncService.class.getDeclaredField("workLock");
		field.setAccessible(true);
		ReentrantLock workLock = (ReentrantLock) field.get(service);
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (thread.isAlive() && !workLock.hasQueuedThread(thread) && System.nanoTime() < deadline) {
			Thread.sleep(5);
		}
		assertTrue(workLock.hasQueuedThread(thread), "der Aufruf wartet nicht auf die Arbeitssperre");
	}

	@Test
	void closeIndexAndDisconnectGetTheLockDuringAHurriedFirstRun() throws Exception {
		seedSharedHalls(300);
		ExecutorService worker = Executors.newSingleThreadExecutor();
		try {
			Node a = new Node(dir.resolve("a"), "a", worker);
			assertTrue(a.service.startup(""));
			AtomicReference<Gate> gate = new AtomicReference<>();
			a.layer.onEach("read", () -> {
				clock.advance(READ_COST);
				Gate armed = gate.get();
				Double progress = armed == null ? null : a.service.fullSyncProgress().get();
				if (progress != null && progress > 0 && gate.compareAndSet(armed, null)) {
					armed.paused.countDown();
					try {
						armed.go.await(10, TimeUnit.SECONDS);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				}
			});
			AtomicReference<Throwable> failure = new AtomicReference<>();

			// erster eiliger Lauf: disconnect kommt zwischen zwei Takten dran und kehrt zurück
			Gate first = new Gate();
			gate.set(first);
			CompletableFuture<ConnectOutcome> aborted = a.service.connect(shared, ConnectMode.MERGE);
			assertTrue(first.paused.await(10, TimeUnit.SECONDS), "der Worker steht nicht im zweiten Takt");
			Thread disconnect = new Thread(() -> {
				try {
					a.service.disconnect();
				} catch (RuntimeException e) {
					failure.set(e);
				}
			});
			disconnect.start();
			awaitQueuedForTheWorkLock(a.service, disconnect);
			first.go.countDown();
			disconnect.join(10_000);
			assertFalse(disconnect.isAlive(), "disconnect kehrt zurück");
			assertEquals(Kind.ABGEBROCHEN, aborted.get(10, TimeUnit.SECONDS).kind());
			// die Schleife der Takte endet: Der Worker nimmt wieder Aufgaben an
			worker.submit(() -> {
			}).get(10, TimeUnit.SECONDS);
			assertNull(a.service.sharedDir());

			// zweiter eiliger Lauf: closeIndex bekommt die Sperre innerhalb CLOSE_WAIT und legt die Marke "sauber beendet" an
			Gate second = new Gate();
			gate.set(second);
			CompletableFuture<ConnectOutcome> pending = a.service.connect(shared, ConnectMode.MERGE);
			assertTrue(second.paused.await(10, TimeUnit.SECONDS), "der Worker steht nicht im zweiten Takt");
			Thread close = new Thread(a.service::closeIndex);
			close.start();
			awaitQueuedForTheWorkLock(a.service, close);
			second.go.countDown();
			close.join(10_000);
			assertFalse(close.isAlive());
			assertTrue(Files.isRegularFile(a.appData.resolve("index/clean")), "closeIndex bekam die Sperre");
			// geschlossen: Der Takt tut nichts mehr, die Schleife endet
			worker.submit(() -> {
			}).get(10, TimeUnit.SECONDS);
			assertNotNull(a.service.fullSyncProgress().get(), "der Lauf steht still");
			assertFalse(pending.isDone());

			a.service.disconnect();
			assertEquals(Kind.ABGEBROCHEN, pending.get(10, TimeUnit.SECONDS).kind());
			assertNull(failure.get());
		} finally {
			worker.shutdownNow();
			assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS));
		}
	}

	// --- Modus ASK, decideConnect und Schutzregel

	@Test
	void askWithOneEmptySideMergesWithoutAsking() throws IOException {
		// der gemeinsame Ordner ist leer: Der lokale Verkauf wird mitgenommen
		Node a = new Node("a");
		assertTrue(a.service.startup(""));
		Version sale = save(a, BOOKING, "Anna");

		CompletableFuture<ConnectOutcome> result = a.service.connect(shared, ConnectMode.ASK);
		a.runScheduled();

		assertEquals(outcome(Kind.AKTUELL), result.getNow(null));
		assertEquals(List.of(), a.asked);
		assertEquals(sale.id(), sharedClaim(BOOKING).version());
		assertEquals(List.of(), asideFolders(a));
		assertTrue(a.marker().orElseThrow().complete());
		assertEquals(SyncState.AKTUELL, a.service.syncState().get());

		// die lokale Seite ist leer: Der gemeinsame Bestand kommt herein
		Node b = new Node("b");
		assertTrue(b.service.startup(""));
		CompletableFuture<ConnectOutcome> second = b.service.connect(shared, ConnectMode.ASK);
		b.runScheduled();

		assertEquals(outcome(Kind.AKTUELL), second.getNow(null));
		assertEquals(List.of(), b.asked);
		assertEquals(sale.id(), b.service.unitState(BOOKING).head().id());
		assertTrue(b.marker().orElseThrow().complete());
	}

	@Test
	void askWithBothSidesPausesAndAsks() throws IOException {
		seedSharedHalls(3);
		Node a = nodeWithEarlierUnit("a");
		List<String> sharedBefore = tree(sharedRoot);

		CompletableFuture<ConnectOutcome> result = a.service.connect(shared, ConnectMode.ASK);
		assertFalse(a.service.tick(), "die Pause folgt nicht ohne Abstand");

		assertEquals(SyncState.ENTSCHEIDUNG, a.service.syncState().get());
		assertEquals(List.of(new Question(shared, true)), a.asked);
		assertEquals(decisionNeeded(true), result.getNow(null));
		assertEquals(Optional.empty(), a.marker(), "die Marke kommt erst nach der Entscheidung");
		assertFalse(a.service.connectionComplete().get());
		assertNull(a.service.fullSyncProgress().get(), "kein Lauf");

		// weitere Takte, auch nach Ablauf der Fristen, und ein Verkauf in der Pause: nichts geht hinaus, nichts kommt herein
		clock.advance(SyncService.FULL_SYNC_INTERVAL);
		assertFalse(a.service.tick());
		Version sale = save(a, BOOKING, "Anna");
		clock.advance(SyncService.TICK);
		assertFalse(a.service.tick());

		assertEquals(sharedBefore, tree(sharedRoot), "keine neue Datei im gemeinsamen Ordner");
		assertEquals(List.of(), a.service.hallIds(), "nichts abgeholt");
		assertTrue(a.service.unitState(BOOKING).provisional(), "der Verkauf bleibt ausstehend");
		assertEquals(List.of(sale.id()), pending(a, BOOKING));
		assertEquals(SyncState.ENTSCHEIDUNG, a.service.syncState().get());
		assertEquals(1, a.asked.size(), "einmal je Eintritt in die Pause");
		assertNull(a.service.fullSyncProgress().get());
	}

	@Test
	void decideMergeContinuesAndCompletesAtTheEndOfTheRun() throws IOException {
		seedSharedHalls(300);
		Node a = nodeWithEarlierUnit("a");
		a.service.connect(shared, ConnectMode.ASK);
		a.runScheduled();
		assertEquals(SyncState.ENTSCHEIDUNG, a.service.syncState().get());
		a.slowReads();

		CompletableFuture<ConnectOutcome> decided = a.service.decideConnect(ConnectMode.MERGE);
		assertFalse(decided.isDone());
		assertEquals(1, a.queued.size(), "decideConnect plant einen Takt ein");
		a.service.tick();
		assertNotNull(a.service.fullSyncProgress().get(), "der Lauf braucht mehrere Takte");
		assertFalse(decided.isDone(), "nach dem ersten Takt ist der Lauf nicht zu Ende");
		assertFalse(a.marker().orElseThrow().complete(), "die Marke steht nach der Entscheidung");

		tickUntilRunEnds(a);

		assertEquals(outcome(Kind.AKTUELL), decided.getNow(null));
		assertEquals(300, a.service.hallIds().size());
		assertTrue(Files.isDirectory(sharedUnit(LOCAL_EVENT)), "mitgenommen");
		assertEquals(List.of(), asideFolders(a));
		assertTrue(a.marker().orElseThrow().complete());
		assertTrue(a.service.connectionComplete().get());
		assertEquals(SyncState.AKTUELL, a.service.syncState().get());
		assertEquals(List.of(new Question(shared, true)), a.asked);
	}

	@Test
	void decideSetAsideMovesTheLocalStoreAndCompletes() throws IOException {
		seedSharedHalls(3);
		Node a = nodeWithEarlierUnit("a");
		a.service.connect(shared, ConnectMode.ASK);
		a.runScheduled();

		CompletableFuture<ConnectOutcome> decided = a.service.decideConnect(ConnectMode.SET_ASIDE);
		a.runScheduled();

		List<String> aside = asideFolders(a);
		assertEquals(1, aside.size());
		Path asideRoot = a.appData.resolve(aside.get(0));
		assertEquals(new ConnectOutcome(Kind.AKTUELL, false, asideRoot, null), decided.getNow(null));
		assertTrue(Files.isDirectory(asideRoot.resolve(LOCAL_EVENT.relativePath())));
		assertEquals(List.of(), a.service.eventIds());
		assertEquals(3, a.service.hallIds().size());
		assertFalse(Files.exists(sharedUnit(LOCAL_EVENT)), "der beiseitegelegte Bestand wird nicht mitgenommen");
		assertTrue(a.marker().orElseThrow().complete());
		assertEquals(SyncState.AKTUELL, a.service.syncState().get());
	}

	@Test
	void setAsideIsNotAllowedAfterOwnSavesSinceStartup() throws IOException {
		seedSharedHalls(3);
		Node a = new Node("a");
		assertTrue(a.service.startup(""));
		save(a, LOCAL_EVENT, "A");

		CompletableFuture<ConnectOutcome> result = a.service.connect(shared, ConnectMode.ASK);
		a.runScheduled();
		assertEquals(List.of(new Question(shared, false)), a.asked);
		assertEquals(decisionNeeded(false), result.getNow(null));

		CompletableFuture<ConnectOutcome> refused = a.service.decideConnect(ConnectMode.SET_ASIDE);

		assertEquals(decisionNeeded(false), refused.getNow(null), "sofort, ohne Takt");
		assertEquals(List.of(new Question(shared, false), new Question(shared, false)), a.asked, "die Oberfläche fragt erneut");
		assertEquals(SyncState.ENTSCHEIDUNG, a.service.syncState().get());
		assertEquals(List.of(), a.queued, "kein Takt eingeplant");
		assertFalse(a.service.tick(), "die Pause bleibt");
		assertEquals(List.of(), asideFolders(a));

		// Mitnehmen bleibt möglich
		CompletableFuture<ConnectOutcome> merged = a.service.decideConnect(ConnectMode.MERGE);
		a.runScheduled();
		assertEquals(outcome(Kind.AKTUELL), merged.getNow(null));
		assertTrue(Files.isDirectory(sharedUnit(LOCAL_EVENT)));
		assertEquals(List.of(), asideFolders(a));
	}

	@Test
	void setAsideIsRecheckedWhenSavedBetweenQuestionAndAnswer() throws IOException {
		seedSharedHalls(3);
		Node a = nodeWithEarlierUnit("a");
		a.service.connect(shared, ConnectMode.ASK);
		a.runScheduled();
		assertEquals(List.of(new Question(shared, true)), a.asked);

		// zwischen Popup und Klick wird gespeichert
		Version sale = save(a, BOOKING, "Anna");
		CompletableFuture<ConnectOutcome> refused = a.service.decideConnect(ConnectMode.SET_ASIDE);

		assertEquals(decisionNeeded(false), refused.getNow(null));
		assertEquals(List.of(new Question(shared, true), new Question(shared, false)), a.asked);
		assertEquals(SyncState.ENTSCHEIDUNG, a.service.syncState().get());
		assertEquals(List.of(), a.queued);
		assertEquals(List.of(), asideFolders(a));
		assertTrue(Files.exists(a.localData().resolve(BOOKING.relativePath()).resolve(sale.id() + ".json")));
	}

	/** Eine Version des fremden Rechners auf {@code parent}, lokal geschrieben und lokal beansprucht (wie abgeholt). */
	private Version foreignOnTop(Node node, UnitKey unit, Version parent, int n) throws IOException {
		Version theirs = new Version(StoreCodec.FORMAT, VersionId.create(clock.instant().toEpochMilli() - 1000L, OTHER, n),
				List.of(parent.id()), OTHER, "Kasse", null, clock.instant(), "fremd-" + n, false,
				MAPPER.createObjectNode().put("name", "Bert"));
		byte[] bytes = StoreCodec.write(theirs);
		VersionStore store = new VersionStore(node.localData(), new NioFileLayer());
		assertTrue(store.writeVersion(unit, theirs.id(), bytes));
		new LocalArbiter(store, clock).claim(unit, theirs, bytes);
		return theirs;
	}

	/**
	 * Die verlorene Strecke der Einheit ist zusammengeführt: kein Verlust mehr, der Stand (im gemeinsamen Betrieb die
	 * ausstehende Zusammenführung) hat zwei Vorgänger.
	 */
	private static void assertMerged(Node node, UnitKey unit) throws IOException {
		UnitState state = node.service.unitState(unit);
		assertEquals(List.of(), state.lost(), unit.relativePath());
		Version tip = state.pending().isEmpty() ? state.head() : state.pending().get(state.pending().size() - 1);
		assertEquals(2, tip.parents().size(), unit.relativePath());
	}

	/** Die ausstehenden eigenen Versionen der Einheit. */
	private static List<VersionId> pending(Node node, UnitKey unit) throws IOException {
		return node.service.unitState(unit).pending().stream().map(Version::id).toList();
	}

	@Test
	void writesOfStartupAndOfTheResolverDoNotCount() throws IOException {
		UnitKey first = UnitKey.hall("h1");
		UnitKey second = UnitKey.hall("h2");
		// ein früherer Programmlauf hinterlässt zwei verlorene Strecken, noch nicht aufgelöst
		Node earlier = new Node("a");
		assertTrue(earlier.service.startup(""));
		int n = 0;
		for (UnitKey hall : List.of(first, second)) {
			Version base = save(earlier, hall, "Anna");
			foreignOnTop(earlier, hall, base, n++);
			Version mine = earlier.service.append(UUID.randomUUID().toString(),
					List.of(new Change(hall, List.of(base.id()), MAPPER.createObjectNode().put("name", "Carla"), false))).get(0);
			assertEquals(List.of(List.of(mine.id())), earlier.service.unitState(hall).lost());
		}
		seedSharedHalls(3);
		Node a = new Node(earlier.appData, "a", null);
		a.service.setConflictPolicy(UnitType.HALL,
				input -> new Resolution(input.mine().payload().deepCopy(), false, List.of(), null));
		// die Zusammenführung der zweiten Strecke scheitert beim Start einmal: Sie kommt im Takt dran
		AtomicBoolean blocked = new AtomicBoolean(true);
		a.layer.failWhen(p -> blocked.get() && p.startsWith(a.localData().resolve(second.relativePath())), "createNew",
				new IOException("gesperrt"));

		assertTrue(a.service.startup(""));
		blocked.set(false);
		assertMerged(a, first);
		assertEquals(1, a.service.unitState(second).lost().size(), "noch nicht aufgelöst");

		CompletableFuture<ConnectOutcome> result = a.service.connect(shared, ConnectMode.ASK);
		a.runScheduled();
		assertEquals(decisionNeeded(true), result.getNow(null), "die Auflösung beim Start zählt nicht");
		// die lokale Auflösung in der Pause, nach Ablauf der Wartezeit auf die Änderungslisten
		clock.advance(ConflictResolver.LIST_WAIT);
		assertFalse(a.service.tick());
		assertMerged(a, second);

		CompletableFuture<ConnectOutcome> decided = a.service.decideConnect(ConnectMode.SET_ASIDE);
		a.runScheduled();

		List<String> aside = asideFolders(a);
		assertEquals(1, aside.size(), "die Auflösung im Takt zählt nicht");
		Path asideRoot = a.appData.resolve(aside.get(0));
		assertEquals(new ConnectOutcome(Kind.AKTUELL, false, asideRoot, null), decided.getNow(null));
		assertTrue(Files.isDirectory(asideRoot.resolve(second.relativePath())));
		assertEquals(List.of("f0", "f1", "f2"), a.service.hallIds());
		assertFalse(Files.exists(sharedUnit(first)));
		assertFalse(Files.exists(sharedUnit(second)), "die Zusammenführung aus der Pause geht mit dem Bestand beiseite");
	}

	@Test
	void askWhileUnreachableIsOfflineAndKeepsSavesPending() throws IOException {
		seedSharedHalls(3);
		Node a = nodeWithEarlierUnit("a");
		a.down.set(true);

		CompletableFuture<ConnectOutcome> result = a.service.connect(shared, ConnectMode.ASK);
		a.runScheduled();

		assertEquals(new ConnectOutcome(Kind.OFFLINE, false, null, StoreMessages.unreachable(shared)), result.getNow(null));
		assertEquals(SyncState.OFFLINE, a.service.syncState().get());
		assertEquals(List.of(), a.asked);
		Version sale = save(a, BOOKING, "Anna");
		assertTrue(a.service.unitState(BOOKING).provisional(), "vorläufig, nicht lokal beansprucht");
		assertFalse(Files.exists(a.localData().resolve(BOOKING.relativePath()).resolve("claims/root.json")));
		assertFalse(a.service.tick());
		assertEquals(SyncState.OFFLINE, a.service.syncState().get());
		assertThrows(IllegalStateException.class, () -> a.service.decideConnect(ConnectMode.MERGE));

		// zurück: Jetzt prüft der Beginn die Dateien und fragt
		a.down.set(false);
		assertFalse(a.service.tick());

		assertEquals(SyncState.ENTSCHEIDUNG, a.service.syncState().get());
		assertEquals(List.of(new Question(shared, false)), a.asked, "inzwischen gespeichert");
		assertFalse(Files.exists(sharedUnit(BOOKING)), "nicht hochgeladen");
		assertTrue(a.service.unitState(BOOKING).provisional());
		assertEquals(List.of(sale.id()), pending(a, BOOKING));
		assertEquals(Kind.OFFLINE, result.getNow(null).kind(), "ein zweites Ergebnis gibt es nicht");
	}

	@Test
	void questionComesAgainAfterARestartUntilDecided() throws IOException {
		seedSharedHalls(3);
		Node a = nodeWithEarlierUnit("a");
		a.service.connect(shared, ConnectMode.ASK);
		a.runScheduled();
		assertEquals(List.of(new Question(shared, true)), a.asked);
		assertEquals(Optional.empty(), a.marker());

		// Neustart ohne Entscheidung: Ohne Marke ruft der Start (KARV-87) startup(null), dann connect(X, ASK)
		Node restarted = restart(a, "a");
		assertEquals(Optional.empty(), restarted.service.connectedDir());
		CompletableFuture<ConnectOutcome> again = restarted.service.connect(shared, ConnectMode.ASK);
		restarted.runScheduled();

		assertEquals(decisionNeeded(true), again.getNow(null));
		assertEquals(List.of(new Question(shared, true)), restarted.asked, "die Frage kommt erneut");
		assertEquals(SyncState.ENTSCHEIDUNG, restarted.service.syncState().get());

		// entschieden: Die Marke steht, der nächste Start verbindet ohne Frage
		CompletableFuture<ConnectOutcome> decided = restarted.service.decideConnect(ConnectMode.MERGE);
		restarted.runScheduled();
		assertEquals(outcome(Kind.AKTUELL), decided.getNow(null));
		ConnectionMarker.State marker = restarted.marker().orElseThrow();
		assertEquals(shared, marker.dir());
		assertTrue(marker.complete());
		assertTrue(new Node(a.appData, "a", null).service.connectionComplete().get());
	}

	@Test
	void decideConnectOutsideEntscheidungIsRejected() throws IOException {
		Node a = new Node("a");
		assertTrue(a.service.startup(""));
		assertThrows(IllegalStateException.class, () -> a.service.decideConnect(ConnectMode.MERGE), "Einzelbetrieb");
		assertThrows(IllegalArgumentException.class, () -> a.service.decideConnect(ConnectMode.ASK));
		assertThrows(NullPointerException.class, () -> a.service.decideConnect(null));

		// verbunden, aber vor dem ersten Takt, danach am Ende des Laufs
		CompletableFuture<ConnectOutcome> result = a.service.connect(shared, ConnectMode.ASK);
		assertThrows(IllegalStateException.class, () -> a.service.decideConnect(ConnectMode.MERGE), "vor dem Takt");
		a.runScheduled();
		assertEquals(outcome(Kind.AKTUELL), result.getNow(null));
		assertThrows(IllegalStateException.class, () -> a.service.decideConnect(ConnectMode.SET_ASIDE), "nach dem Lauf");

		// in der Pause genau einmal
		seedSharedHalls(3);
		Node b = nodeWithEarlierUnit("b");
		b.service.connect(shared, ConnectMode.ASK);
		b.runScheduled();
		assertThrows(IllegalArgumentException.class, () -> b.service.decideConnect(ConnectMode.ASK));
		b.service.decideConnect(ConnectMode.MERGE);
		assertThrows(IllegalStateException.class, () -> b.service.decideConnect(ConnectMode.MERGE), "schon entschieden");
		b.runScheduled();
		assertEquals(SyncState.AKTUELL, b.service.syncState().get());
	}

	// --- Beiseitelegen und laufende Speichervorgänge schließen sich aus

	/** Wartet, bis der Thread an einer Bedingung wartet oder zu Ende ist. */
	private static void awaitWaitingOrDone(Thread thread) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (System.nanoTime() < deadline) {
			Thread.State state = thread.getState();
			if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING || state == Thread.State.TERMINATED) {
				return;
			}
			Thread.onSpinWait();
		}
		fail("der Speichervorgang wartet nicht und endet nicht: " + thread.getState());
	}

	@Test
	void saveDuringSetAsideLandsInTheNewStore() throws Exception {
		seedSharedHalls(3);
		Node a = nodeWithEarlierUnit("a");
		a.service.connect(shared, ConnectMode.ASK);
		a.runScheduled();
		AtomicReference<Thread> saver = new AtomicReference<>();
		AtomicReference<Version> sale = new AtomicReference<>();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		// genau zwischen der Prüfung der Schutzregel und dem Umbenennen beginnt ein Speichervorgang
		a.layer.onEach("rename", () -> {
			if (saver.get() != null) {
				return;
			}
			Thread thread = new Thread(() -> {
				try {
					sale.set(save(a, BOOKING, "Anna"));
				} catch (Throwable e) {
					failure.set(e);
				}
			});
			saver.set(thread);
			thread.start();
			awaitWaitingOrDone(thread);
		});

		CompletableFuture<ConnectOutcome> decided = a.service.decideConnect(ConnectMode.SET_ASIDE);
		a.runScheduled();
		assertNotNull(saver.get(), "es wurde beiseitegelegt");
		saver.get().join(10_000);
		assertFalse(saver.get().isAlive());
		assertNull(failure.get());
		a.service.tick();

		List<String> aside = asideFolders(a);
		assertEquals(1, aside.size());
		Path asideRoot = a.appData.resolve(aside.get(0));
		assertEquals(asideRoot, decided.getNow(null).setAsidePath());
		assertTrue(Files.isDirectory(asideRoot.resolve(LOCAL_EVENT.relativePath())));
		assertFalse(Files.exists(asideRoot.resolve(BOOKING.relativePath())), "der Verkauf ist nicht beiseitegelegt");
		assertTrue(Files.exists(a.localData().resolve(BOOKING.relativePath()).resolve(sale.get().id() + ".json")));
		assertEquals(sale.get().id(), sharedClaim(BOOKING).version(), "hochgeladen");
		assertFalse(a.service.unitState(BOOKING).provisional());
	}

	@Test
	void saveInFlightWhenSetAsideStartsPreventsIt() throws Exception {
		seedSharedHalls(3);
		Node a = nodeWithEarlierUnit("a");
		a.service.connect(shared, ConnectMode.ASK);
		a.runScheduled();
		assertEquals(List.of(new Question(shared, true)), a.asked);
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		a.layer.blockNext("createNew", entered, release);
		AtomicReference<Version> sale = new AtomicReference<>();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread saver = new Thread(() -> {
			try {
				sale.set(save(a, BOOKING, "Anna"));
			} catch (Throwable e) {
				failure.set(e);
			}
		});
		saver.start();
		try {
			assertTrue(entered.await(10, TimeUnit.SECONDS), "der Speichervorgang schreibt");

			CompletableFuture<ConnectOutcome> refused = a.service.decideConnect(ConnectMode.SET_ASIDE);

			assertEquals(decisionNeeded(false), refused.getNow(null));
			assertEquals(List.of(new Question(shared, true), new Question(shared, false)), a.asked);
			assertEquals(List.of(), asideFolders(a), "nichts beiseitegelegt");
		} finally {
			release.countDown();
			saver.join(10_000);
		}
		assertNull(failure.get());
		CompletableFuture<ConnectOutcome> merged = a.service.decideConnect(ConnectMode.MERGE);
		a.runScheduled();
		assertEquals(Kind.AKTUELL, merged.getNow(null).kind());
		assertEquals(sale.get().id(), sharedClaim(BOOKING).version());
		assertEquals(List.of(), asideFolders(a));
	}

	@Test
	void setAsideRetryAfterAFailedRenameRespectsTheProtectionRule() throws IOException {
		seedSharedHalls(3);
		Node a = nodeWithEarlierUnit("a");
		// unter Windows etwa eine offene Datei: Das Umbenennen von data/ scheitert einmal
		a.layer.failNext("rename", new AccessDeniedException("data gesperrt"));
		CompletableFuture<ConnectOutcome> result = a.service.connect(shared, ConnectMode.SET_ASIDE);
		a.runScheduled();
		assertEquals(Kind.FEHLER, result.getNow(null).kind());
		assertEquals(List.of(), asideFolders(a));

		// gespeichert vor dem nächsten Versuch: Das Beiseitelegen ist jetzt verboten
		Version sale = save(a, BOOKING, "Anna");
		assertFalse(a.service.tick());

		assertEquals(List.of(), asideFolders(a), "nichts beiseitegelegt");
		assertTrue(Files.exists(a.localData().resolve(BOOKING.relativePath()).resolve(sale.id() + ".json")));
		assertEquals(SyncState.ENTSCHEIDUNG, a.service.syncState().get());
		assertEquals(List.of(new Question(shared, false)), a.asked, "die Oberfläche fragt erneut");
		assertEquals(Optional.empty(), a.marker());
		assertFalse(Files.exists(sharedUnit(BOOKING)), "die Pause lädt nichts hoch");
		assertEquals(Kind.FEHLER, result.getNow(null).kind(), "ein zweites Ergebnis gibt es nicht");

		// Mitnehmen bleibt möglich
		CompletableFuture<ConnectOutcome> merged = a.service.decideConnect(ConnectMode.MERGE);
		a.runScheduled();
		assertEquals(Kind.AKTUELL, merged.getNow(null).kind());
		assertEquals(sale.id(), sharedClaim(BOOKING).version());
		assertTrue(Files.isDirectory(sharedUnit(LOCAL_EVENT)));
		assertEquals(List.of(), asideFolders(a));
	}
}
