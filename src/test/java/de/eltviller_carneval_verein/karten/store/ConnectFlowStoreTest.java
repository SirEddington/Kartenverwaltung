package de.eltviller_carneval_verein.karten.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
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
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.eltviller_carneval_verein.karten.store.ConnectOutcome.Kind;

/**
 * Der Verbindungsablauf im Versionsspeicher (KARV-103, Abschnitt E): das Ergebnis von {@code connect} am Ende des ersten
 * Laufs, der erste Lauf ohne Pause zwischen den Takten, die Marke "verbunden" und {@code connectionComplete()}.
 * <p>
 * Die Takte, die {@code connect} einplant, landen in {@link Node#queued} und laufen nur, wenn der Test sie ausführt; sonst
 * löst der Test die Takte mit {@code tick()} selbst aus. Damit ein Lauf mehrere Takte braucht, kostet jeder Lesezugriff
 * {@link #READ_COST} auf der Test-Uhr.
 */
class ConnectFlowStoreTest {

	/** Ein fremder Rechner, der nur als Dateien im gemeinsamen Ordner vorkommt. */
	private static final String OTHER = "zzzzzzzzzzzzzzzzzzzzzzzzzz";
	private static final UnitKey BOOKING = UnitKey.booking("e1", "p1", "A-1");
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

		// Neustart mitten im ersten Lauf: Die Marke ist unvollständig
		Node restarted = new Node(a.appData, "a", null);
		assertFalse(restarted.service.connectionComplete().get());
		assertEquals(Optional.of(shared), restarted.service.connectedDir(), "auch ohne Start");
		// der Start ohne Ordner lässt die Marke stehen (KARV-87 trennt dafür ausdrücklich)
		Node single = new Node(a.appData, "a", null);
		assertTrue(single.service.startup(""));
		assertFalse(single.service.connectionComplete().get());
		assertFalse(a.marker().orElseThrow().complete());

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
		Node a = new Node("a");
		a.service.startup("");
		save(a, UnitKey.event("lokal"), "A");
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
}
