package de.eltviller_carneval_verein.karten.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.eltviller_carneval_verein.karten.store.ConnectOutcome.Kind;
import de.eltviller_carneval_verein.karten.store.UnitState.Status;

class SyncServiceTest {

	private static final Instant START = Instant.parse("2026-10-26T10:00:00Z");
	private static final UnitKey BOOKING = UnitKey.booking("e1", "p1", "A-1");
	/** Ein fremder Rechner, der nur als Dateien im gemeinsamen Ordner vorkommt. */
	private static final String OTHER = "zzzzzzzzzzzzzzzzzzzzzzzzzz";
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** Eine Uhr, die der Test vorstellt; alle Rechner eines Tests teilen sie. */
	private static final class TestClock extends Clock {
		private Instant now = START;

		void advance(Duration duration) {
			now = now.plus(duration);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}

	/** Zeichnet alle Rückmeldungen eines Dienstes auf. */
	private static final class Recorder implements SyncListener {
		final List<Set<UnitKey>> changed = new ArrayList<>();
		final List<PendingSighting> pending = new ArrayList<>();
		final List<LostVersion> lost = new ArrayList<>();
		final List<String> unreachable = new ArrayList<>();
		final List<String> errors = new ArrayList<>();
		final List<String> duplicates = new ArrayList<>();
		int reconnected;

		@Override
		public void unitsChanged(Set<UnitKey> units) {
			changed.add(units);
		}

		@Override
		public void pending(UnitKey unit, VersionId version, String machineName) {
			pending.add(new PendingSighting(unit, version, machineName));
		}

		@Override
		public void lost(UnitKey unit, List<VersionId> mine, VersionId winner) {
			lost.add(new LostVersion(unit, mine, winner));
		}

		@Override
		public void unreachable(String path) {
			unreachable.add(path);
		}

		@Override
		public void reconnected() {
			reconnected++;
		}

		@Override
		public void error(String message) {
			errors.add(message);
		}

		@Override
		public void duplicateMachine(String message) {
			duplicates.add(message);
		}

		Set<UnitKey> changedUnits() {
			Set<UnitKey> all = new HashSet<>();
			changed.forEach(all::addAll);
			return all;
		}
	}

	/** Eine Aktion mit Dateizugriff. */
	private interface IoAction {
		void run() throws IOException;
	}

	/**
	 * Eine Dateischicht, die einmal vor dem ersten passenden {@code createNew} eine Aktion ausführt: So schreibt ein
	 * anderer Rechner mitten in einen Vollabgleich hinein.
	 */
	private static final class HookLayer implements FileLayer {
		private final FileLayer delegate = new NioFileLayer();
		private Predicate<Path> trigger;
		private IoAction action;

		void once(Predicate<Path> trigger, IoAction action) {
			this.trigger = trigger;
			this.action = action;
		}

		@Override
		public boolean createNew(Path file, byte[] content, Path anchor, String tempTag) throws IOException {
			if (trigger != null && trigger.test(file)) {
				IoAction pending = action;
				trigger = null;
				action = null;
				pending.run();
			}
			return delegate.createNew(file, content, anchor, tempTag);
		}

		@Override
		public List<String> listNamesWithTemps(Path dir) throws IOException {
			return delegate.listNamesWithTemps(dir);
		}

		@Override
		public void deleteOldTemps(Path dir, Collection<String> names, Duration olderThan) throws IOException {
			delegate.deleteOldTemps(dir, names, olderThan);
		}

		@Override
		public Optional<byte[]> read(Path file) throws IOException {
			return delegate.read(file);
		}

		@Override
		public boolean exists(Path file) {
			return delegate.exists(file);
		}

		@Override
		public List<String> listNames(Path dir) throws IOException {
			return delegate.listNames(dir);
		}

		@Override
		public Map<String, List<String>> listTree(Path root) throws IOException {
			return delegate.listTree(root);
		}

		@Override
		public boolean isDirectory(Path path) {
			return delegate.isDirectory(path);
		}

		@Override
		public void replaceDamaged(Path file, byte[] good, String corruptName, String tempTag) throws IOException {
			delegate.replaceDamaged(file, good, corruptName, tempTag);
		}

		@Override
		public void rename(Path from, Path to) throws IOException {
			delegate.rename(from, to);
		}

		@Override
		public void deleteOwnTemps(Path dir, Duration olderThan) throws IOException {
			delegate.deleteOwnTemps(dir, olderThan);
		}
	}

	/**
	 * Ein simulierter Rechner: eigener lokaler Datenordner, eigene Kennung, eigener Dienst. Mit {@link #down} ist der
	 * gemeinsame Ordner für ihn weg: Die Prüfung meldet "nicht erreichbar", und jeder Zugriff darauf scheitert.
	 */
	private final class Node {
		final Path appData;
		final MachineIdentity identity;
		final FaultyFileLayer layer;
		final AtomicBoolean down = new AtomicBoolean();
		final Recorder events = new Recorder();
		final List<SyncState> states = new ArrayList<>();
		final SyncService service;

		Node(Path appData, String name, FileLayer files, Executor ui, Executor worker) throws IOException {
			this(appData, name, files, ui, worker, null);
		}

		/** Mit eigener Prüfung der Erreichbarkeit statt des Schalters {@link #down}, etwa {@code Files::isDirectory}. */
		Node(Path appData, String name, FileLayer files, Executor ui, Executor worker, Predicate<Path> reachable)
				throws IOException {
			this.appData = appData;
			this.identity = MachineIdentity.loadOrCreate(appData, () -> name);
			this.layer = new FaultyFileLayer(files);
			for (String operation : List.of("createNew", "read", "listNames", "listNamesWithTemps")) {
				layer.failWhen(p -> down.get() && p.startsWith(sharedRoot), operation, new IOException("Netzlaufwerk getrennt"));
			}
			this.service = new SyncService(appData, identity, layer, ui, worker,
					reachable != null ? reachable : p -> !down.get(), clock);
			service.setListener(events);
			service.syncState().addListener((property, before, after) -> states.add(after));
		}

		Path localData() {
			return appData.resolve("data");
		}
	}

	@TempDir
	Path dir;

	private TestClock clock;
	private Path sharedRoot;
	private String shared;

	@BeforeEach
	void setUp() throws IOException {
		clock = new TestClock();
		sharedRoot = Files.createDirectories(dir.resolve("gemeinsam"));
		shared = sharedRoot.toString();
	}

	// --- Hilfen

	private Node node(String name) throws IOException {
		return new Node(dir.resolve(name), name, new NioFileLayer(), Runnable::run, Runnable::run);
	}

	/**
	 * Ein Neustart auf dem Datenordner des Rechners, gestartet im Einzelbetrieb: Dieser Programmlauf hat nach seinem Start
	 * nichts gespeichert, das Beiseitelegen ist erlaubt (Schutzregel). Der alte Dienst bleibt unbenutzt liegen.
	 */
	private Node restartSingle(Node node) throws IOException {
		Node restarted = new Node(node.appData, node.appData.getFileName().toString(), new NioFileLayer(), Runnable::run,
				Runnable::run);
		assertTrue(restarted.service.startup(""));
		return restarted;
	}

	private static ObjectNode payload(String name) {
		return MAPPER.createObjectNode().put("name", name);
	}

	private static Change create(UnitKey unit, String name) {
		return new Change(unit, List.of(), payload(name), false);
	}

	private static Change update(UnitKey unit, Version parent, String name) {
		return new Change(unit, List.of(parent.id()), payload(name), false);
	}

	private static Version save(Node node, Change change) throws IOException {
		List<Version> saved = node.service.append(UUID.randomUUID().toString(), List.of(change));
		assertEquals(1, saved.size());
		return saved.get(0);
	}

	private Path sharedFile(UnitKey unit, String name) {
		return sharedRoot.resolve("data").resolve(unit.relativePath()).resolve(name);
	}

	private static Path localFile(Node node, UnitKey unit, String name) {
		return node.localData().resolve(unit.relativePath()).resolve(name);
	}

	private Claim sharedClaim(UnitKey unit, String name) throws IOException {
		return StoreCodec.readClaim(Files.readAllBytes(sharedFile(unit, "claims/" + name + ".json")));
	}

	private static List<String> names(Path folder) throws IOException {
		try (Stream<Path> entries = Files.list(folder)) {
			return entries.map(p -> p.getFileName().toString()).sorted().toList();
		}
	}

	/** Alle Dateien unter dem Ordner, relativ und sortiert. */
	private static List<String> tree(Path folder) throws IOException {
		try (Stream<Path> entries = Files.walk(folder)) {
			return entries.filter(Files::isRegularFile).map(p -> folder.relativize(p).toString()).sorted().toList();
		}
	}

	private static Version version(String machine, long millis, int counter, String saveId, VersionId parent) {
		return new Version(StoreCodec.FORMAT, VersionId.create(millis, machine, counter),
				parent == null ? List.of() : List.of(parent), machine, "Kasse", null, START, saveId, false,
				payload("v" + counter));
	}

	/** Legt eine Versionsdatei im lokalen Bestand eines (noch nicht gestarteten) Rechners ab. */
	private static void writeLocal(Path appData, UnitKey unit, Version version) throws IOException {
		VersionStore local = new VersionStore(appData.resolve("data"), new NioFileLayer());
		assertTrue(local.writeVersion(unit, version.id(), StoreCodec.write(version)));
	}

	/** Version, Liste und Anspruch eines fremden Rechners im gemeinsamen Ordner, wie sein Uploader sie schreibt. */
	private static void publish(VersionStore sharedStore, long seq, UnitKey unit, Version version) throws IOException {
		byte[] bytes = listOnly(sharedStore, seq, unit, version);
		Claim claim = new Claim(version.id(), StoreCodec.sha256(bytes), version.machine(), START);
		assertTrue(sharedStore.createClaim(unit, version.claimName(), StoreCodec.write(claim)));
	}

	/** Version und Liste ohne den Anspruch: der fremde Rechner ist mitten im Hochladen. */
	private static byte[] listOnly(VersionStore sharedStore, long seq, UnitKey unit, Version version) throws IOException {
		byte[] bytes = StoreCodec.write(version);
		assertTrue(sharedStore.writeVersion(unit, version.id(), bytes));
		Feed feed = new Feed(StoreCodec.FORMAT, version.machine(), seq, START,
				List.of(new FeedEntry(unit.relativePath(), version.id(), version.claimName())));
		assertTrue(sharedStore.createRelative("feeds/" + version.machine() + "/" + seq + ".json", StoreCodec.write(feed)));
		return bytes;
	}

	/**
	 * Wie ein Sync-Client nach einer späten Niederlage: Die Anspruchsdatei im gemeinsamen Ordner zeigt plötzlich auf
	 * die Version eines anderen Rechners (roh überschrieben; das tut nur der Test-Doppel des Sync-Clients).
	 */
	private Version overrideSharedClaim(UnitKey unit, String claimName) throws IOException {
		Version foreign = version(OTHER, START.toEpochMilli() - 1000, 0, "fremd", null);
		byte[] bytes = StoreCodec.write(foreign);
		Files.write(sharedFile(unit, foreign.id() + ".json"), bytes);
		Claim claim = new Claim(foreign.id(), StoreCodec.sha256(bytes), OTHER, START);
		Files.write(sharedFile(unit, "claims/" + claimName + ".json"), StoreCodec.write(claim));
		return foreign;
	}

	private List<VersionId> feedVersions(String machineId, long seq) throws IOException {
		Feed feed = StoreCodec.readFeed(Files.readAllBytes(sharedRoot.resolve("data/feeds/" + machineId + "/" + seq + ".json")));
		return feed.entries().stream().map(FeedEntry::version).toList();
	}

	// --- Einzelbetrieb und Hochladen

	@Test
	void instanceIfCreatedIsEmptyBeforeGetInstance() {
		// getInstance() wird hier nie aufgerufen (es würde Sperre und Kennung im echten AppData anlegen)
		assertTrue(SyncService.instanceIfCreated().isEmpty());
	}

	@Test
	void singleModeAppendIsImmediatelyValid() throws IOException {
		Node a = node("a");
		assertTrue(a.service.startup(""));

		Version v = save(a, create(BOOKING, "Anna"));

		UnitState state = a.service.unitState(BOOKING);
		assertEquals(v.id(), state.head().id());
		assertFalse(state.provisional());
		assertEquals(SyncState.AUS, a.service.syncState().get());
		assertNull(a.service.sharedDir());
		assertEquals(a.identity.id(), v.machine());
		assertEquals(payload("Anna"), v.payload());
	}

	@Test
	void appendedChangeCarriesItsConflictNoteIntoTheVersionFile() throws IOException {
		Node a = node("a");
		assertTrue(a.service.startup(""));
		ConflictNote note = new ConflictNote(ConflictNote.Kind.DELETE_WINS, a.identity.id(), null, null, null, null, null);

		Version v = save(a, new Change(BOOKING, List.of(), payload("Anna"), false, note));

		assertEquals(note, v.conflict());
		assertEquals(Map.of(), v.seen());
		assertEquals(note, a.service.unitState(BOOKING).head().conflict());
		assertEquals(note, StoreCodec.readVersion(Files.readAllBytes(localFile(a, BOOKING, v.id() + ".json"))).conflict());
	}

	@Test
	void sharedModeUploadsAndValidates() throws IOException {
		Node a = node("a");
		assertTrue(a.service.startup(shared));
		Version v = save(a, create(BOOKING, "Anna"));
		assertTrue(a.service.unitState(BOOKING).provisional());
		assertEquals(SyncState.AUSSTEHEND, a.service.syncState().get());
		clock.advance(Duration.ofSeconds(2));

		a.service.tick();

		UnitState state = a.service.unitState(BOOKING);
		assertEquals(v.id(), state.head().id());
		assertFalse(state.provisional());
		assertEquals(v.id(), sharedClaim(BOOKING, "root").version());
		assertEquals(SyncState.AKTUELL, a.service.syncState().get());
		assertEquals(clock.instant(), a.service.lastSync().get());
		assertEquals(shared, a.service.sharedDir());
	}

	@Test
	void sharedWritesCarryTheSharedFolderAsAnchorAndTheMachineIdAsTag() throws IOException {
		Node a = node("a");
		assertTrue(a.service.startup(shared));
		save(a, create(BOOKING, "Anna"));
		clock.advance(Duration.ofSeconds(2));
		a.service.tick();

		List<FaultyFileLayer.Creation> inShared = a.layer.creations().stream().filter(c -> c.file().startsWith(sharedRoot)).toList();
		List<FaultyFileLayer.Creation> local = a.layer.creations().stream().filter(c -> c.file().startsWith(a.appData)).toList();

		assertFalse(inShared.isEmpty());
		assertFalse(local.isEmpty());
		for (FaultyFileLayer.Creation creation : inShared) {
			assertEquals(sharedRoot.toAbsolutePath().normalize(), creation.anchor().toAbsolutePath().normalize(), creation.file().toString());
			assertEquals(a.service.machineId(), creation.tempTag(), creation.file().toString());
		}
		for (FaultyFileLayer.Creation creation : local) {
			assertNull(creation.anchor(), creation.file().toString());
			assertNull(creation.tempTag(), creation.file().toString());
		}
		assertEquals(inShared.size() + local.size(), a.layer.creations().size());
	}

	@Test
	void twoMachinesFirstUploadWins() throws IOException {
		Node a = node("a");
		Node b = node("b");
		a.service.startup(shared);
		b.service.startup(shared);
		Version va = save(a, create(BOOKING, "Anna"));
		Version vb = save(b, create(BOOKING, "Bert"));

		a.service.tick();
		b.service.tick();
		a.service.tick();
		b.service.tick();

		assertEquals(List.of(new LostVersion(BOOKING, List.of(vb.id()), va.id())), b.events.lost);
		assertTrue(a.events.lost.isEmpty());
		assertEquals(va.id(), b.service.unitState(BOOKING).head().id());
	}

	@Test
	void foreignChangeArrivesWithinOneTick() throws IOException {
		Node a = node("a");
		a.service.startup(shared);
		save(a, create(UnitKey.hall("h1"), "Halle"));
		a.service.tick();
		// B kennt A schon von seinem Start: A hat eine Änderungsliste im gemeinsamen Ordner
		Node b = node("b");
		b.service.startup(shared);
		b.events.changed.clear();
		Version va = save(a, create(BOOKING, "Anna"));
		a.service.tick();

		b.service.tick();

		assertEquals(List.of(Set.of(BOOKING)), b.events.changed);
		assertEquals(va.id(), b.service.unitState(BOOKING).head().id());
		assertFalse(b.service.unitState(BOOKING).provisional());
	}

	@Test
	void listedVersionWithoutClaimIsReportedAsPending() throws IOException {
		VersionStore sharedStore = new VersionStore(sharedRoot.resolve("data"), new NioFileLayer());
		Version p1 = version(OTHER, 1_000_000L, 0, "fremd-1", null);
		publish(sharedStore, 1, BOOKING, p1);
		Node a = node("a");
		a.service.startup(shared);
		Version p2 = version(OTHER, 2_000_000L, 1, "fremd-2", p1.id());
		listOnly(sharedStore, 2, BOOKING, p2);

		a.service.tick();
		a.service.tick();

		assertEquals(List.of(new PendingSighting(BOOKING, p2.id(), "Kasse")), a.events.pending);
		assertEquals(p1.id(), a.service.unitState(BOOKING).head().id(), "noch nicht gültig");
	}

	@Test
	void newMachineIsFoundWithinThirtySeconds() throws IOException {
		Node a = node("a");
		Node b = node("b");
		a.service.startup(shared);
		b.service.startup(shared);
		Version va = save(a, create(BOOKING, "Anna"));
		a.service.tick();

		b.service.tick();
		assertFalse(b.events.changedUnits().contains(BOOKING), "A ist B noch unbekannt");
		clock.advance(Duration.ofSeconds(30));
		b.service.tick();

		assertTrue(b.events.changedUnits().contains(BOOKING));
		assertEquals(va.id(), b.service.unitState(BOOKING).head().id());
	}

	@Test
	void offlineAppendBecomesPendingAndUploadsOnReturn() throws IOException {
		Node a = node("a");
		assertTrue(a.service.startup(shared));
		a.down.set(true);
		Version v = save(a, create(BOOKING, "Anna"));

		a.service.tick();
		a.service.tick();

		assertEquals(SyncState.OFFLINE, a.service.syncState().get());
		assertEquals(List.of(shared), a.events.unreachable);
		assertTrue(a.service.unitState(BOOKING).provisional());
		assertEquals(0, a.events.reconnected);

		a.down.set(false);
		a.states.clear();
		a.service.tick();

		assertEquals(List.of(SyncState.AUSSTEHEND, SyncState.AKTUELL), a.states);
		assertEquals(1, a.events.reconnected);
		assertEquals(List.of(shared), a.events.unreachable);
		assertFalse(a.service.unitState(BOOKING).provisional());
		assertEquals(v.id(), sharedClaim(BOOKING, "root").version());
		// die schon eingereihte Version wurde nicht ein zweites Mal hochgeladen
		assertEquals(List.of("1.json"), names(sharedRoot.resolve("data/feeds/" + a.identity.id())));
	}

	@Test
	void unreachableAtStartupIsOfflineWithoutCallback() throws IOException {
		Node a = node("a");
		a.down.set(true);

		assertFalse(a.service.startup(shared));

		assertEquals(SyncState.OFFLINE, a.service.syncState().get());
		assertTrue(a.events.unreachable.isEmpty(), "das Popup zeigt der Aufrufer");
		Version v = save(a, create(BOOKING, "Anna"));
		a.service.tick();
		assertTrue(a.events.unreachable.isEmpty());

		a.down.set(false);
		a.service.tick();

		assertEquals(1, a.events.reconnected);
		assertEquals(SyncState.AKTUELL, a.service.syncState().get());
		assertEquals(v.id(), sharedClaim(BOOKING, "root").version());
	}

	@Test
	void startupUploadsPendingBeforeReturning() throws IOException {
		Path appData = dir.resolve("a");
		MachineIdentity identity = MachineIdentity.loadOrCreate(appData, () -> "a");
		Version v = version(identity.id(), 1_000_000L, 0, "frueher", null);
		writeLocal(appData, BOOKING, v);
		List<Runnable> background = new ArrayList<>();
		Node a = new Node(appData, "a", new NioFileLayer(), Runnable::run, background::add);

		assertTrue(a.service.startup(shared));

		// der Hintergrund-Thread lief noch nicht: hochgeladen hat startup selbst
		assertEquals(v.id(), sharedClaim(BOOKING, "root").version());
		assertFalse(background.isEmpty(), "der Vollabgleich läuft im Hintergrund");
	}

	@Test
	void pendingVersionsAreGroupedBySaveIdAndOrderedAtStartup() throws IOException {
		Path appData = dir.resolve("a");
		String me = MachineIdentity.loadOrCreate(appData, () -> "a").id();
		UnitKey u1 = UnitKey.event("e1");
		UnitKey u2 = UnitKey.event("e2");
		UnitKey u3 = UnitKey.event("e3");
		Version x1 = version(me, 1_000_000L, 0, "s-x", null);
		Version x2 = version(me, 3_000_000L, 1, "s-x", x1.id());
		Version y1 = version(me, 2_000_000L, 2, "s-y", null);
		// die Uhr sprang zurück: z2 hat die kleinere ID, baut aber auf z1 auf
		Version z1 = version(me, 5_000_000L, 3, "s-z", null);
		Version z2 = version(me, 1_500_000L, 4, "s-z", z1.id());
		writeLocal(appData, u1, x1);
		writeLocal(appData, u1, x2);
		writeLocal(appData, u2, y1);
		writeLocal(appData, u3, z1);
		writeLocal(appData, u3, z2);
		Node a = new Node(appData, "a", new NioFileLayer(), Runnable::run, task -> {
		});

		assertTrue(a.service.startup(shared));

		// je saveId ein Stapel, Stapel nach kleinster ID, im Stapel der Vorgänger zuerst
		assertEquals(List.of(x1.id(), x2.id()), feedVersions(me, 1));
		assertEquals(List.of(z1.id(), z2.id()), feedVersions(me, 2));
		assertEquals(List.of(y1.id()), feedVersions(me, 3));
		assertEquals(z2.id(), sharedClaim(u3, z1.id().value()).version());
	}

	@Test
	void appendFailureLeavesWrittenVersionsPending() throws IOException {
		UnitKey u1 = UnitKey.event("e1");
		UnitKey u2 = UnitKey.event("e2");
		UnitKey u3 = UnitKey.event("e3");
		Node a = node("a");
		a.service.startup(shared);
		Path u2Local = a.localData().resolve(u2.relativePath());
		a.layer.failWhen(p -> p.startsWith(u2Local), "createNew", new IOException("Platte voll"));

		assertThrows(IOException.class,
				() -> a.service.append("s1", List.of(create(u1, "eins"), create(u2, "zwei"), create(u3, "drei"))));

		assertEquals(1, names(a.localData().resolve(u1.relativePath())).size());
		assertFalse(Files.exists(a.localData().resolve(u3.relativePath())));
		assertTrue(a.service.unitState(u1).provisional());

		a.service.tick();

		assertTrue(Files.exists(sharedFile(u1, "claims/root.json")));
		assertFalse(a.service.unitState(u1).provisional());
	}

	@Test
	void runtimeFailureInAppendStillClaimsWrittenVersionsInSingleMode() throws IOException {
		UnitKey u1 = UnitKey.event("e1");
		UnitKey u2 = UnitKey.event("e2");
		Path appData = dir.resolve("a");
		FaultyFileLayer layer = new FaultyFileLayer(new NioFileLayer());
		SyncService service = TestSyncServices.singleMode(appData, layer);
		assertTrue(service.startup(""));
		Path u2Local = appData.resolve("data").resolve(u2.relativePath());
		UncheckedIOException failure = new UncheckedIOException(new IOException("Platte voll"));
		layer.failNextUnchecked(p -> p.startsWith(u2Local), "createNew", failure);

		UncheckedIOException thrown = assertThrows(UncheckedIOException.class,
				() -> service.append("s1", List.of(create(u1, "eins"), create(u2, "zwei"))));

		assertSame(failure, thrown);
		assertTrue(Files.exists(appData.resolve("data").resolve(u1.relativePath()).resolve("claims/root.json")),
				"die geschriebene Version ist beansprucht");
		assertFalse(Files.exists(appData.resolve("data").resolve(u2.relativePath())));
		UnitState state = service.unitState(u1);
		assertEquals("eins", state.head().payload().get("name").asText());
		assertFalse(state.provisional());
	}

	@Test
	void runtimeFailureInAppendStillQueuesWrittenVersionsInSharedMode() throws IOException {
		UnitKey u1 = UnitKey.event("e1");
		UnitKey u2 = UnitKey.event("e2");
		Node a = node("a");
		a.service.startup(shared);
		Path u2Local = a.localData().resolve(u2.relativePath());
		RuntimeException failure = new IllegalStateException("unerwartet");
		a.layer.failNextUnchecked(p -> p.startsWith(u2Local), "createNew", failure);

		RuntimeException thrown = assertThrows(RuntimeException.class,
				() -> a.service.append("s1", List.of(create(u1, "eins"), create(u2, "zwei"))));

		assertSame(failure, thrown);
		UnitState pending = a.service.unitState(u1);
		assertEquals("eins", pending.current().payload().get("name").asText());
		assertTrue(pending.provisional(), "die Version ist eingereiht, aber noch nicht oben");

		a.service.tick();

		assertTrue(Files.exists(sharedFile(u1, "claims/root.json")));
		assertFalse(a.service.unitState(u1).provisional());
		assertFalse(Files.exists(sharedFile(u2, "claims/root.json")));
	}

	// --- Verluste

	@Test
	void lossDetectedBeforeACrashIsReportedAfterRestart() throws IOException {
		Node a = node("a");
		a.service.startup(shared);
		// B startet vor A's Hochladen: So hat B A's Liste noch nicht gelesen, und die Auflösung wartet darauf (KARV-86)
		Path appDataB = dir.resolve("b");
		Node crashed = new Node(appDataB, "b", new NioFileLayer(), task -> {
		}, Runnable::run);
		crashed.service.startup(shared);
		Version va = save(a, create(BOOKING, "Anna"));
		a.service.tick();
		// B erkennt den Verlust, stürzt aber ab, bevor die Meldung die Oberfläche erreicht
		Version vb = save(crashed, create(BOOKING, "Bert"));
		crashed.service.tick();
		assertTrue(Files.exists(localFile(crashed, BOOKING, "claims/root.json")), "der Verlust ist lokal angekommen");

		Node restarted = new Node(appDataB, "b", new NioFileLayer(), Runnable::run, Runnable::run);
		restarted.service.startup(shared);
		restarted.service.tick();

		assertEquals(List.of(new LostVersion(BOOKING, List.of(vb.id()), va.id())), restarted.events.lost);
	}

	@Test
	void lateDefeatIsDetected() throws IOException {
		Node a = node("a");
		a.service.startup(shared);
		Version va = save(a, create(BOOKING, "Anna"));
		a.service.tick();
		assertTrue(a.events.lost.isEmpty());

		Version foreign = overrideSharedClaim(BOOKING, "root");
		clock.advance(Duration.ofMinutes(1));
		a.service.tick();
		a.service.tick();

		assertEquals(List.of(new LostVersion(BOOKING, List.of(va.id()), foreign.id())), a.events.lost);
	}

	@Test
	void lateDefeatReplacesTheLocalClaimAndKeepsItBeside() throws IOException {
		Node a = node("a");
		a.service.startup(shared);
		save(a, create(BOOKING, "Anna"));
		a.service.tick();
		Path claims = localFile(a, BOOKING, "claims");
		byte[] ownClaim = Files.readAllBytes(claims.resolve("root.json"));

		Version foreign = overrideSharedClaim(BOOKING, "root");
		a.service.tick();

		assertEquals(foreign.id(), StoreCodec.readClaim(Files.readAllBytes(claims.resolve("root.json"))).version());
		List<String> beside = names(claims).stream().filter(n -> n.startsWith("root.json.verdraengt-")).toList();
		assertEquals(List.of("root.json.verdraengt-2026-10-26_10-00-00"), beside);
		assertArrayEquals(ownClaim, Files.readAllBytes(claims.resolve(beside.get(0))));
		assertEquals(foreign.id(), a.service.unitState(BOOKING).head().id());
		assertTrue(a.events.changedUnits().contains(BOOKING));
	}

	@Test
	void lateDefeatAfterARestartIsDetected() throws IOException {
		Node a = node("a");
		a.service.startup(shared);
		Version va = save(a, create(BOOKING, "Anna"));
		a.service.tick();
		clock.advance(Duration.ofMinutes(5));

		Node restarted = new Node(a.appData, "a", new NioFileLayer(), Runnable::run, Runnable::run);
		restarted.service.startup(shared);
		Version foreign = overrideSharedClaim(BOOKING, "root");
		restarted.service.tick();

		assertEquals(List.of(new LostVersion(BOOKING, List.of(va.id()), foreign.id())), restarted.events.lost);
		assertEquals(foreign.id(), restarted.service.unitState(BOOKING).head().id());
	}

	/** Fremde Hallen mit je einer Version und ihrem Anspruch, gleich im lokalen und im gemeinsamen Bestand (nichts zu kopieren). */
	private void seedForeignHalls(Path appData, int count) throws IOException {
		VersionStore localStore = new VersionStore(appData.resolve("data"), new NioFileLayer());
		VersionStore sharedStore = new VersionStore(sharedRoot.resolve("data"), new NioFileLayer());
		for (int i = 0; i < count; i++) {
			UnitKey hall = UnitKey.hall("f" + i);
			Version v = version(OTHER, START.toEpochMilli() - 3_600_000L, i, "f" + i, null);
			byte[] bytes = StoreCodec.write(v);
			byte[] claim = StoreCodec.write(new Claim(v.id(), StoreCodec.sha256(bytes), OTHER, START));
			for (VersionStore store : List.of(localStore, sharedStore)) {
				assertTrue(store.writeVersion(hall, v.id(), bytes));
				assertTrue(store.createClaim(hall, "root", claim));
			}
		}
	}

	/** Baut den Start-Index des Rechners und schreibt ihn, damit der nächste Start keine Einheit lesen muss. */
	private static void writeStartIndex(Path appData, String machineId) throws IOException {
		UnitIndex index = new UnitIndex(appData.resolve("index"), new NioFileLayer(), new Ownership(machineId, List.of()));
		index.refresh(new VersionStore(appData.resolve("data"), new NioFileLayer()), p -> {
		});
		index.flush();
	}

	/**
	 * Die Lesezugriffe dieses Rechners auf Versionsdateien des lokalen Bestands, die nicht zu den genannten Einheiten
	 * gehören: Wer eine Einheit auflöst ({@code readUnit}), liest ihre Versionsdateien. Anspruchsdateien zählen nicht, denn der
	 * Vollabgleich vergleicht eigene Ansprüche auf beiden Seiten selbst.
	 */
	private static List<String> localReadsBesides(Node node, UnitKey... allowed) {
		String data = "read " + node.localData();
		return node.layer.calls().stream().filter(c -> c.startsWith(data)).map(c -> c.replace('\\', '/'))
				.filter(c -> !c.contains("/claims/"))
				.filter(c -> java.util.Arrays.stream(allowed).noneMatch(unit -> c.contains(unit.relativePath()))).toList();
	}

	@Test
	void fullSyncAfterRestartDoesNotReadTheWholeStore() throws IOException {
		Path appData = dir.resolve("a");
		String me = MachineIdentity.loadOrCreate(appData, () -> "a").id();
		seedForeignHalls(appData, 200);
		Version own = version(me, START.toEpochMilli(), 0, "s1", null);
		writeLocal(appData, BOOKING, own);
		writeStartIndex(appData, me);

		Node a = new Node(appData, "a", new NioFileLayer(), Runnable::run, Runnable::run);
		a.service.startup(shared);
		a.service.tick();

		assertEquals(own.id(), sharedClaim(BOOKING, "root").version(), "die ausstehende Version ist hochgeladen");
		assertEquals(List.of(), localReadsBesides(a, BOOKING));
		assertTrue(a.events.errors.isEmpty());
	}

	@Test
	void lossesAfterTheFirstRunAreDerivedFromIndexedLostUnits() throws IOException {
		Path appData = dir.resolve("a");
		String me = MachineIdentity.loadOrCreate(appData, () -> "a").id();
		seedForeignHalls(appData, 200);
		// der Platz hinter der Wurzel gehört dem Fremden (auf beiden Seiten); die eigene, nie beanspruchte Version ist verloren
		Version winner = version(OTHER, START.toEpochMilli() - 1000, 0, "fremd", null);
		byte[] winnerBytes = StoreCodec.write(winner);
		byte[] winnerClaim = StoreCodec.write(new Claim(winner.id(), StoreCodec.sha256(winnerBytes), OTHER, START));
		for (VersionStore store : List.of(new VersionStore(dir.resolve("a/data"), new NioFileLayer()),
				new VersionStore(sharedRoot.resolve("data"), new NioFileLayer()))) {
			assertTrue(store.writeVersion(BOOKING, winner.id(), winnerBytes));
			assertTrue(store.createClaim(BOOKING, "root", winnerClaim));
		}
		Version lost = version(me, START.toEpochMilli(), 0, "s1", null);
		writeLocal(appData, BOOKING, lost);
		writeStartIndex(appData, me);

		Node a = new Node(appData, "a", new NioFileLayer(), Runnable::run, Runnable::run);
		a.service.startup(shared);
		a.service.tick();

		assertEquals(List.of(new LostVersion(BOOKING, List.of(lost.id()), winner.id())), a.events.lost);
		assertEquals(List.of(), localReadsBesides(a, BOOKING));
	}

	/** Ein vollständiges Event (Kopf, Vorstellung, Buchung) und zwei Hallen, an der App vorbei in einen Bestand zu kopieren. */
	private static final List<UnitKey> COPIED = List.of(UnitKey.event("e2"), UnitKey.presentation("e2", "p1"),
			UnitKey.booking("e2", "p1", "B-1"), UnitKey.hall("k1"), UnitKey.hall("k2"));

	/**
	 * Legt je Einheit eine Version des Rechners {@code machine} mit ihrem Anspruch {@code root} in jedem der genannten
	 * Datenordner ab, in allen gleich: wie ein Kopieren an der App vorbei (etwa eine zurückgespielte Sicherung).
	 */
	private static void copyPastTheApp(String machine, List<UnitKey> units, Path... dataRoots) throws IOException {
		for (int i = 0; i < units.size(); i++) {
			Version v = version(machine, START.toEpochMilli() - 3_600_000L, i, "k" + i, null);
			byte[] bytes = StoreCodec.write(v);
			byte[] claim = StoreCodec.write(new Claim(v.id(), StoreCodec.sha256(bytes), machine, START));
			for (Path dataRoot : dataRoots) {
				VersionStore store = new VersionStore(dataRoot, new NioFileLayer());
				assertTrue(store.writeVersion(units.get(i), v.id(), bytes));
				assertTrue(store.createClaim(units.get(i), "root", claim));
			}
		}
	}

	/** Prüft, dass der Dienst die Einheiten mit dem Stand aus den lokalen Dateien kennt. */
	private static void assertIndexed(Node node, List<UnitKey> units) throws IOException {
		Map<UnitKey, UnitSnapshot> expected = snapshotsFromFiles(node.appData, node.identity.id());
		Map<UnitKey, UnitSnapshot> actual = node.service.snapshots();
		for (UnitKey unit : units) {
			assertNotNull(actual.get(unit), unit + " fehlt im Index");
			assertEquals(expected.get(unit), actual.get(unit));
		}
	}

	@Test
	void fullSyncIndexesLocalUnitsTheIndexDoesNotKnow() throws IOException {
		Node a = node("a");
		a.service.startup(shared);
		save(a, create(BOOKING, "Anna"));
		a.service.tick();
		a.service.closeIndex();
		assertTrue(Files.isRegularFile(cleanMarker(a)));
		// an der App vorbei in den lokalen und den gemeinsamen Bestand kopiert, gleich: Der Vollabgleich hat nichts zu kopieren
		copyPastTheApp(OTHER, COPIED, a.localData(), sharedRoot.resolve("data"));

		Node b = new Node(a.appData, "a", new NioFileLayer(), Runnable::run, Runnable::run);
		assertTrue(b.service.startup(shared));
		b.service.tick();

		assertFalse(walkedTree(b), "der Start vertraut dem Index: " + b.layer.calls());
		assertIndexed(b, COPIED);
		assertTrue(b.events.changedUnits().containsAll(COPIED), b.events.changed.toString());
		assertTrue(b.events.errors.isEmpty(), b.events.errors.toString());
		b.service.closeIndex();
		assertTrue(Files.isRegularFile(cleanMarker(a)));

		Node c = new Node(a.appData, "a", new NioFileLayer(), Runnable::run, Runnable::run);
		assertTrue(c.service.startup(""));
		assertFalse(walkedTree(c), c.layer.calls().toString());
		assertIndexed(c, COPIED);
	}

	@Test
	void abortedBlockStillIndexesTheUnknownUnitsItReached() throws IOException {
		Node a = node("a");
		a.service.startup(shared);
		save(a, create(BOOKING, "Anna"));
		a.service.tick();
		a.service.closeIndex();
		copyPastTheApp(OTHER, COPIED, a.localData(), sharedRoot.resolve("data"));
		UnitKey k1 = UnitKey.hall("k1");
		UnitKey k2 = UnitKey.hall("k2");

		Node b = new Node(a.appData, "a", new NioFileLayer(), Runnable::run, Runnable::run);
		// das Auflisten von k2 im gemeinsamen Ordner scheitert: Der Block der Hallen bricht dort ab
		AtomicBoolean broken = new AtomicBoolean(true);
		Path k2Shared = sharedFile(k2, "x").getParent();
		b.layer.failWhen(p -> broken.get() && p.equals(k2Shared), "listNamesWithTemps", new IOException("Störung"));
		assertTrue(b.service.startup(shared));

		assertNotNull(b.service.fullSyncProgress().get(), "der Lauf steht vor dem gescheiterten Block");
		assertIndexed(b, List.of(k1, k2));
		assertTrue(b.events.changedUnits().containsAll(List.of(k1, k2)), b.events.changed.toString());
		assertFalse(b.service.snapshots().containsKey(UnitKey.event("e2")), "nach dem Abbruch kam kein Block mehr dran");

		broken.set(false);
		b.service.tick();

		assertNull(b.service.fullSyncProgress().get());
		assertIndexed(b, COPIED);
		assertTrue(b.events.changedUnits().containsAll(COPIED), b.events.changed.toString());
	}

	@Test
	void fullSyncIndexesUnknownUnitsThatAreOnlyLocal() throws IOException {
		Node a = node("a");
		a.service.startup(shared);
		save(a, create(BOOKING, "Anna"));
		a.service.tick();
		a.service.closeIndex();
		assertTrue(Files.isRegularFile(cleanMarker(a)));
		// eigene Einheiten nur im lokalen Bestand (etwa eine zurückgespielte Sicherung): Der Vollabgleich lädt sie hoch (M4)
		copyPastTheApp(a.identity.id(), COPIED, a.localData());

		Node b = new Node(a.appData, "a", new NioFileLayer(), Runnable::run, Runnable::run);
		assertTrue(b.service.startup(shared));
		b.service.tick();

		assertFalse(walkedTree(b), "der Start vertraut dem Index: " + b.layer.calls());
		assertIndexed(b, COPIED);
		assertTrue(b.events.changedUnits().containsAll(COPIED), b.events.changed.toString());
		assertTrue(b.events.errors.isEmpty(), b.events.errors.toString());
		for (UnitKey unit : COPIED) {
			// wie bisher: eigene Ansprüche, die im gemeinsamen Ordner fehlen, kommen hinzu
			assertEquals(b.service.snapshots().get(unit).version(), sharedClaim(unit, "root").version());
		}
		b.service.closeIndex();

		Node c = new Node(a.appData, "a", new NioFileLayer(), Runnable::run, Runnable::run);
		assertTrue(c.service.startup(""));
		assertFalse(walkedTree(c), c.layer.calls().toString());
		assertIndexed(c, COPIED);
	}

	@Test
	void lateDefeatAfterARestartIsStillDetected() throws IOException {
		Path appData = dir.resolve("a");
		seedForeignHalls(appData, 200);
		Node a = new Node(appData, "a", new NioFileLayer(), Runnable::run, Runnable::run);
		a.service.startup(shared);
		Version va = save(a, create(BOOKING, "Anna"));
		a.service.tick();
		a.service.flushIndex();
		clock.advance(Duration.ofMinutes(5));

		Node restarted = new Node(appData, "a", new NioFileLayer(), Runnable::run, Runnable::run);
		restarted.service.startup(shared);
		Version foreign = overrideSharedClaim(BOOKING, "root");
		restarted.service.tick();

		assertEquals(List.of(new LostVersion(BOOKING, List.of(va.id()), foreign.id())), restarted.events.lost);
		assertEquals(foreign.id(), restarted.service.unitState(BOOKING).head().id());
		assertEquals(List.of(), localReadsBesides(restarted, BOOKING));
	}

	@Test
	void lateDefeatOfAnOwnLinkBelowAForeignHeadIsDetectedAfterARestart() throws IOException {
		Node a = node("a");
		a.service.startup(shared);
		Version va = save(a, create(BOOKING, "Anna"));
		a.service.tick();
		// ein anderer Rechner baut auf der eigenen Version auf: Der Kopf der Einheit ist danach fremd
		Node b = node("b");
		b.service.startup(shared);
		b.service.tick();
		Version vb = save(b, update(BOOKING, va, "Bert"));
		b.service.tick();
		// A entdeckt neue Rechner alle 30 Sekunden
		clock.advance(SyncService.DISCOVERY_INTERVAL);
		a.service.tick();
		assertEquals(vb.id(), a.service.unitState(BOOKING).head().id());
		a.service.flushIndex();
		clock.advance(Duration.ofMinutes(5));

		Node restarted = new Node(a.appData, "a", new NioFileLayer(), Runnable::run, Runnable::run);
		restarted.service.startup(shared);
		restarted.service.tick();
		assertTrue(restarted.events.lost.isEmpty());

		// die Niederlage kommt nach dem ersten Vollabgleich, vor dem nächsten (10 Minuten): nur die Beobachtung findet sie
		clock.advance(Duration.ofMinutes(1));
		Version foreign = overrideSharedClaim(BOOKING, "root");
		restarted.service.tick();
		restarted.service.tick();

		assertEquals(List.of(new LostVersion(BOOKING, List.of(va.id()), foreign.id())), restarted.events.lost);
	}

	/** Der Zustand nach einem Absturz im Wartebetrieb: Die Version liegt lokal, der Index kennt die Einheit nicht. */
	private Node nodeWithUnindexedPendingVersion() throws IOException {
		AtomicBoolean failReads = new AtomicBoolean();
		Path appData = dir.resolve("a");
		Node a = new Node(appData, "a", new NioFileLayer(), Runnable::run, Runnable::run);
		a.layer.failWhen(p -> failReads.get() && p.startsWith(a.localData()), "read", new IOException("Datei gesperrt"));
		a.down.set(true);
		a.service.startup(shared);
		failReads.set(true);
		try {
			// der Index scheitert beim Auflösen der neuen Einheit; die Version ist trotzdem geschrieben und eingereiht
			save(a, create(BOOKING, "Anna"));
		} finally {
			failReads.set(false);
		}
		assertNull(a.service.snapshots().get(BOOKING), "der Index kennt die Einheit nicht");
		return a;
	}

	@Test
	void unitWhoseIndexUpdateFailedIsStillFoundByTheNextFullSync() throws IOException {
		Node a = nodeWithUnindexedPendingVersion();
		// der Auftrag im Speicher geht verloren (Verbindung neu aufgebaut); nur der Index könnte die Version noch nennen
		a.down.set(false);
		a.service.connect(shared, ConnectMode.MERGE);
		a.service.tick();

		assertTrue(Files.exists(sharedFile(BOOKING, "claims/root.json")), "die ausstehende Version ist hochgeladen");
		assertEquals(a.service.unitState(BOOKING).head().id(), sharedClaim(BOOKING, "root").version());
	}

	@Test
	void cleanMarkerIsWrittenOnlyOnceEveryUnitIsIndexed() throws IOException {
		Node a = nodeWithUnindexedPendingVersion();
		Path marker = a.appData.resolve("index").resolve("clean");

		a.service.closeIndex();
		assertFalse(Files.exists(marker), "der Index kennt nicht alle Einheiten");

		// eine spätere gelungene Auflösung der Einheit (hier der Abgleich mit dem Hochladen) stellt den Index richtig
		a.down.set(false);
		a.service.connect(shared, ConnectMode.MERGE);
		a.service.tick();
		a.service.closeIndex();
		assertTrue(Files.exists(marker));
	}

	/** Ob der aktuelle Thread gerade in einer Methode dieses Namens steckt. */
	private static boolean calledFrom(String method) {
		return StackWalker.getInstance().walk(frames -> frames.anyMatch(frame -> frame.getMethodName().equals(method)));
	}

	@Test
	void unitLeavesTheCandidatesOnceTheSearchForPendingReadsItAgain() throws IOException {
		Node a = node("a");
		a.service.startup(shared);
		a.down.set(true);
		Version v = save(a, create(BOOKING, "Anna"));
		a.down.set(false);
		// nach dem Hochladen scheitert das Auflösen beim Melden einmal: Der Index nennt die Version noch als ausstehend, die
		// Einheit bleibt Kandidat
		Path unit = a.localData().resolve(BOOKING.relativePath());
		a.layer.failNextUnchecked(p -> p.startsWith(unit) && calledFrom("reportLosses"), "read",
				new IllegalStateException("Datei gesperrt"));
		a.service.tick();
		assertEquals(v.id(), sharedClaim(BOOKING, "root").version(), "hochgeladen");
		a.service.closeIndex();
		assertFalse(Files.exists(cleanMarker(a)), "der Index kennt nicht alle Einheiten");

		// der nächste Vollabgleich sucht unter den Kandidaten nach Ausstehendem und liest die Einheit jetzt fehlerfrei; sonst
		// ändert sich an ihr nichts
		clock.advance(SyncService.FULL_SYNC_INTERVAL);
		a.service.tick();
		a.service.closeIndex();

		assertTrue(Files.isRegularFile(cleanMarker(a)), "die Einheit ist kein Kandidat mehr");
		assertEquals(snapshotsFromFiles(a.appData, a.identity.id()).get(BOOKING), a.service.snapshots().get(BOOKING),
				"der Index kennt ihren Stand");
	}

	/**
	 * Das nächste Einreihen der Einheit zur Auflösung wirft einmal ungeprüft, also erst nachdem sie aufgelöst und im Index
	 * eingetragen ist: Die Schlange der Auflösung wird dafür ersetzt.
	 */
	private static void failNextEnqueue(SyncService service, UnitKey unit) throws ReflectiveOperationException {
		Field field = ConflictResolver.class.getDeclaredField("queue");
		field.setAccessible(true);
		AtomicBoolean armed = new AtomicBoolean();
		@SuppressWarnings("serial")
		Set<UnitKey> queue = new LinkedHashSet<>() {
			@Override
			public boolean add(UnitKey key) {
				if (key.equals(unit) && armed.getAndSet(false)) {
					throw new IllegalStateException("Einreihen gescheitert");
				}
				return super.add(key);
			}
		};
		@SuppressWarnings("unchecked")
		Set<UnitKey> before = (Set<UnitKey>) field.get(service.resolver());
		queue.addAll(before);
		armed.set(true);
		field.set(service.resolver(), queue);
	}

	@Test
	void lossWhoseReportFailedAfterResolvingIsReportedLater() throws Exception {
		Node a = node("a");
		a.service.startup(shared);
		Version va = save(a, create(BOOKING, "Anna"));
		a.service.tick();
		Version foreign = overrideSharedClaim(BOOKING, "root");
		failNextEnqueue(a.service, BOOKING);

		a.service.tick();
		assertEquals(List.of(), a.events.lost, "das Melden scheiterte");
		// die Oberfläche liest die Einheit dazwischen: Das löst sie auf, meldet aber nichts
		assertEquals(foreign.id(), a.service.unitState(BOOKING).head().id(), "die Niederlage ist lokal");

		// die nächste Runde meldet den Verlust doch noch, und nur einmal
		a.service.tick();
		a.service.tick();

		assertEquals(List.of(new LostVersion(BOOKING, List.of(va.id()), foreign.id())), a.events.lost);
	}

	@Test
	void disconnectClaimsPendingVersionsWithoutReadingTheWholeStore() throws IOException {
		Path appData = dir.resolve("a");
		String me = MachineIdentity.loadOrCreate(appData, () -> "a").id();
		seedForeignHalls(appData, 200);
		Version own = version(me, START.toEpochMilli(), 0, "s1", null);
		writeLocal(appData, BOOKING, own);
		writeStartIndex(appData, me);
		Node a = new Node(appData, "a", new NioFileLayer(), Runnable::run, Runnable::run);
		a.down.set(true);
		a.service.startup(shared);
		assertFalse(Files.exists(localFile(a, BOOKING, "claims/root.json")), "nicht erreichbar: noch nicht beansprucht");

		a.service.disconnect();

		assertEquals(own.id(), StoreCodec.readClaim(Files.readAllBytes(localFile(a, BOOKING, "claims/root.json"))).version());
		assertEquals(List.of(), localReadsBesides(a, BOOKING));
	}

	@Test
	void lateDefeatOfClaimsUploadedByMergeIsDetected() throws IOException {
		Node a = node("a");
		a.service.startup("");
		Version va = save(a, create(BOOKING, "Anna"));
		// der lokale Anspruch ist älter als 15 Minuten; beobachtet wird er, weil das Verbinden ihn jetzt hochlädt
		clock.advance(Duration.ofMinutes(20));
		a.service.connect(shared, ConnectMode.MERGE);
		assertEquals(va.id(), sharedClaim(BOOKING, "root").version());

		Version foreign = overrideSharedClaim(BOOKING, "root");
		a.service.tick();

		assertEquals(List.of(new LostVersion(BOOKING, List.of(va.id()), foreign.id())), a.events.lost);
	}

	/** Lesezugriffe dieses Rechners auf Anspruchsdateien {@code root.json} im gemeinsamen Ordner bisher. */
	private long sharedRootClaimReads(Node node) {
		String prefix = "read " + sharedRoot;
		return node.layer.calls().stream().filter(c -> c.startsWith(prefix) && c.endsWith("root.json")).count();
	}

	@Test
	void lateDefeatChecksStayWithinTheBudgetAndStillCoverTheBacklog() throws IOException {
		Path appData = dir.resolve("a");
		String me = MachineIdentity.loadOrCreate(appData, () -> "a").id();
		VersionStore localStore = new VersionStore(appData.resolve("data"), new NioFileLayer());
		int count = 200;
		for (int i = 0; i < count; i++) {
			UnitKey hall = UnitKey.hall("h" + i);
			// die ID trägt die Zeit des Schreibens: nur Einheiten mit jungem eigenem Kopf liest der Neustart auf Ansprüche
			Version v = version(me, START.toEpochMilli(), i, "s" + i, null);
			byte[] bytes = StoreCodec.write(v);
			localStore.writeVersion(hall, v.id(), bytes);
			localStore.createClaim(hall, "root", StoreCodec.write(new Claim(v.id(), StoreCodec.sha256(bytes), me, START)));
		}
		Node a = new Node(appData, "a", new NioFileLayer(), Runnable::run, Runnable::run);

		// der erste Vollabgleich nimmt alle 200 jungen eigenen Ansprüche in die Beobachtung, geprüft werden 150 je Takt
		a.service.startup(shared);
		long reads = sharedRootClaimReads(a);
		assertEquals(SyncService.LATE_DEFEAT_BUDGET, reads);
		a.service.tick();
		assertEquals(SyncService.LATE_DEFEAT_BUDGET, sharedRootClaimReads(a) - reads);

		// der 10-Minuten-Abgleich läuft vorher und vergleicht die eigenen Ansprüche auf beiden Seiten selbst: alle 200, denn
		// der erste Abgleich hat sie hochgeladen (M4); das Budget der späten Niederlagen bleibt davon unberührt
		clock.advance(Duration.ofMinutes(10));
		reads = sharedRootClaimReads(a);
		a.service.tick();
		assertEquals(count + SyncService.LATE_DEFEAT_BUDGET, sharedRootClaimReads(a) - reads);

		// nach Ablauf der Fenster wird jeder noch einmal geprüft, auch hinter dem Rückstand
		clock.advance(Duration.ofMinutes(6));
		UnitKey late = UnitKey.hall("h7");
		Files.createDirectories(sharedFile(late, "claims"));
		Version foreign = overrideSharedClaim(late, "root");
		reads = sharedRootClaimReads(a);
		a.service.tick();
		assertEquals(SyncService.LATE_DEFEAT_BUDGET, sharedRootClaimReads(a) - reads);
		reads = sharedRootClaimReads(a);
		a.service.tick();
		assertEquals(count - SyncService.LATE_DEFEAT_BUDGET, sharedRootClaimReads(a) - reads);

		VersionId mine = VersionId.create(START.toEpochMilli(), me, 7);
		assertEquals(List.of(new LostVersion(late, List.of(mine), foreign.id())), a.events.lost);
		reads = sharedRootClaimReads(a);
		a.service.tick();
		assertEquals(0, sharedRootClaimReads(a) - reads, "alle Fenster sind abgelaufen");
	}

	@Test
	void mergeWatchesExactlyTheUploadedClaims() throws IOException {
		UnitKey uploaded = UnitKey.event("neu");
		UnitKey known = UnitKey.event("bekannt");
		Node a = node("a");
		a.service.startup("");
		Version vu = save(a, create(uploaded, "neu"));
		Version vk = save(a, create(known, "bekannt"));
		// den Anspruch auf "bekannt" kennt der gemeinsame Ordner schon: er wird nicht hochgeladen
		Files.createDirectories(sharedFile(known, "claims"));
		Files.copy(localFile(a, known, vk.id() + ".json"), sharedFile(known, vk.id() + ".json"));
		Files.copy(localFile(a, known, "claims/root.json"), sharedFile(known, "claims/root.json"));
		// die lokalen Ansprüche sind älter als 15 Minuten
		clock.advance(Duration.ofMinutes(20));

		a.service.connect(shared, ConnectMode.MERGE);
		Version foreign = overrideSharedClaim(uploaded, "root");
		overrideSharedClaim(known, "root");
		a.service.tick();

		assertEquals(List.of(new LostVersion(uploaded, List.of(vu.id()), foreign.id())), a.events.lost);
	}

	@Test
	void sharedCopyIsHealedFromAGoodLocalCopy() throws IOException {
		Node a = node("a");
		a.service.startup(shared);
		Version v1 = save(a, create(BOOKING, "Anna"));
		a.service.tick();
		Path sharedV1 = sharedFile(BOOKING, v1.id() + ".json");
		byte[] good = Files.readAllBytes(sharedV1);
		Files.writeString(sharedV1, "kaputt");

		// der nächste Anspruch hinter v1 sieht die beschädigte gemeinsame Kopie
		save(a, update(BOOKING, v1, "Anna B"));
		a.service.tick();

		assertArrayEquals(good, Files.readAllBytes(sharedV1));
		assertTrue(names(sharedV1.getParent()).stream().anyMatch(n -> n.startsWith(v1.id() + ".json.corrupt-")));
		assertTrue(a.events.errors.isEmpty());
	}

	@Test
	void lockedUnitIsRetriedAfterTheNextFullSync() throws IOException {
		Node a = node("a");
		a.service.startup(shared);
		Version v1 = save(a, create(BOOKING, "Anna"));
		a.service.tick();
		Path localV1 = localFile(a, BOOKING, v1.id() + ".json");
		Path sharedV1 = sharedFile(BOOKING, v1.id() + ".json");
		byte[] good = Files.readAllBytes(localV1);
		Files.writeString(localV1, "kaputt");
		Files.writeString(sharedV1, "auch kaputt");
		a.service.unitState(BOOKING);
		a.service.tick();
		assertEquals(List.of(StoreMessages.damaged(BOOKING.relativePath())), a.events.errors);

		// ein Rechner mit guter Kopie heilt den gemeinsamen Ordner
		Files.write(sharedV1, good);
		a.service.unitState(BOOKING);
		a.service.tick();
		assertEquals(Status.LOCKED_DAMAGED, a.service.unitState(BOOKING).status(), "noch kein neuer Versuch");

		a.service.syncNow();
		a.service.unitState(BOOKING);
		a.service.tick();

		assertEquals(Status.OK, a.service.unitState(BOOKING).status());
		assertArrayEquals(good, Files.readAllBytes(localV1));
	}

	@Test
	void damagedCopyFromAFeedIsReportedWithoutAsking() throws IOException {
		UnitKey second = UnitKey.event("zweite");
		Node a = node("a");
		a.service.startup(shared);
		save(a, create(UnitKey.hall("h1"), "Halle"));
		a.service.tick();
		Node b = node("b");
		b.service.startup(shared);
		Version v = save(a, create(second, "neu"));
		a.service.tick();
		Files.writeString(sharedFile(second, v.id() + ".json"), "kaputt");

		b.service.tick();

		assertEquals(List.of(StoreMessages.damaged(second.relativePath())), b.events.errors);
		assertEquals(Status.LOCKED_DAMAGED, b.service.unitState(second).status());
	}

	@Test
	void lossFoundBeforeAFailureInTheSameJobIsReportedAtOnce() throws IOException {
		UnitKey u1 = UnitKey.event("e1");
		UnitKey u2 = UnitKey.event("e2");
		Node a = node("a");
		Node b = node("b");
		a.service.startup(shared);
		b.service.startup(shared);
		Version vb = save(b, create(u1, "B"));
		b.service.tick();
		Path u2Claims = sharedFile(u2, "claims");
		a.layer.failWhen(p -> p.startsWith(u2Claims), "createNew", new IOException("Störung"));

		List<Version> saved = a.service.append("s1", List.of(create(u1, "A"), create(u2, "A2")));
		a.service.tick();

		assertEquals(List.of(new LostVersion(u1, List.of(saved.get(0).id()), vb.id())), a.events.lost);
		assertEquals(SyncState.FEHLER, a.service.syncState().get());
	}

	@Test
	void failureOnOneContestedSlotKeepsTheOthers() throws IOException {
		UnitKey u1 = UnitKey.event("e1");
		UnitKey u2 = UnitKey.event("e2");
		Node a = node("a");
		a.service.startup(shared);
		Version v1 = save(a, create(u1, "eins"));
		Version v2 = save(a, create(u2, "zwei"));
		a.service.tick();
		clock.advance(Duration.ofMinutes(16));
		a.service.tick();
		// lange nach der Beobachtung: beide Plätze gehören im gemeinsamen Ordner einem anderen, seine Liste kommt an
		Version f1 = overrideSharedClaim(u1, "root");
		Version f2 = overrideSharedClaim(u2, "root");
		Feed feed = new Feed(StoreCodec.FORMAT, OTHER, 1, START, List.of(new FeedEntry(u1.relativePath(), f1.id(), "root"),
				new FeedEntry(u2.relativePath(), f2.id(), "root")));
		Files.createDirectories(sharedRoot.resolve("data/feeds/" + OTHER));
		Files.write(sharedRoot.resolve("data/feeds/" + OTHER + "/1.json"), StoreCodec.write(feed));
		// der zweite Lesezugriff auf den Anspruch von e1 (der Vergleich nach dem Abholen) scheitert
		AtomicInteger u1Reads = new AtomicInteger();
		a.layer.failWhen(p -> p.startsWith(sharedRoot) && p.toString().replace('\\', '/').endsWith("events/e1/claims/root.json")
				&& u1Reads.incrementAndGet() == 2, "read", new IOException("Störung"));
		clock.advance(Duration.ofSeconds(30));

		a.service.tick();

		// e2 wird trotz der Störung bei e1 verglichen; e1 kommt neu in die Beobachtung, die ihn im selben Takt nachliest
		assertEquals(2, a.events.lost.size());
		assertEquals(Set.of(new LostVersion(u2, List.of(v2.id()), f2.id()), new LostVersion(u1, List.of(v1.id()), f1.id())),
				Set.copyOf(a.events.lost));
		assertEquals(3, u1Reads.get());
		assertEquals(SyncState.FEHLER, a.service.syncState().get());
	}

	@Test
	void ownClaimsAreRecheckedOnlyForFifteenMinutes() throws IOException {
		Node a = node("a");
		a.service.startup(shared);
		save(a, create(BOOKING, "Anna"));
		a.service.tick();
		clock.advance(Duration.ofMinutes(16));
		a.service.tick();

		overrideSharedClaim(BOOKING, "root");
		a.service.tick();

		assertTrue(a.events.lost.isEmpty());
	}

	// --- Fehler

	@Test
	void duplicateMachineIdRegenerates() throws IOException {
		Node a = node("a");
		a.service.startup(shared);
		Version v1 = save(a, create(BOOKING, "Anna"));
		a.service.tick();
		String oldId = a.identity.id();
		// eine kopierte Installation schreibt mit derselben Kennung die nächste Liste
		Feed copy = new Feed(StoreCodec.FORMAT, oldId, 2, START, List.of());
		Files.write(sharedRoot.resolve("data/feeds/" + oldId + "/2.json"), StoreCodec.write(copy));

		Version v2 = save(a, update(BOOKING, v1, "Anna B"));
		a.service.tick();

		assertEquals(List.of(StoreMessages.duplicateMachine()), a.events.duplicates);
		String newId = MachineIdentity.loadOrCreate(a.appData, () -> "a").id();
		assertNotEquals(oldId, newId);
		assertEquals(v2.id(), sharedClaim(BOOKING, v1.id().value()).version());
		assertEquals(List.of(v2.id()), feedVersions(newId, 1));
		Version v3 = save(a, update(BOOKING, v2, "Anna C"));
		assertEquals(newId, v3.machine());
		a.service.tick();
		assertEquals(v3.id(), a.service.unitState(BOOKING).head().id());
		assertEquals(1, a.events.duplicates.size());
	}

	// --- Frühere Kennung nach SYS-011

	/** Eine zweite Buchung derselben Vorstellung: Ihre Speicherung löst SYS-011 aus, ohne {@link #BOOKING} zu berühren. */
	private static final UnitKey SECOND = UnitKey.booking("e1", "p1", "A-2");

	/**
	 * Löst SYS-011 aus: Eine kopierte Installation hat mit der Kennung des Rechners schon die Liste 2 geschrieben; das
	 * Hochladen der nächsten Speicherung (auf {@link #SECOND}) stößt darauf, und der Rechner bekommt eine neue Kennung. Der
	 * Rechner hat vorher genau eine Liste hochgeladen; die Uhr steht dabei.
	 *
	 * @return die Speicherung, die noch mit der alten Kennung entstand
	 */
	private Version triggerSys011(Node node) throws IOException {
		String oldId = node.service.machineId();
		Feed copy = new Feed(StoreCodec.FORMAT, oldId, 2, START, List.of());
		Files.write(sharedRoot.resolve("data/feeds/" + oldId + "/2.json"), StoreCodec.write(copy));
		Version saved = save(node, create(SECOND, "Berta"));
		node.service.tick();
		assertEquals(List.of(StoreMessages.duplicateMachine()), node.events.duplicates);
		assertNotEquals(oldId, node.service.machineId());
		assertEquals(oldId, saved.machine());
		return saved;
	}

	/** Lässt das Schreiben von Listen im gemeinsamen Ordner scheitern, außer unter der Kennung {@code allowed}. */
	private void breakListsExceptFor(Node node, String allowed, AtomicBoolean broken) {
		Path feeds = sharedRoot.resolve("data/feeds");
		node.layer.failWhen(p -> broken.get() && p.startsWith(feeds) && !p.startsWith(feeds.resolve(allowed)), "createNew",
				new IOException("Liste nicht schreibbar"));
	}

	@Test
	void pendingVersionOfThePreviousIdIsUploadedAfterSys011() throws IOException {
		Node a = node("a");
		a.service.startup(shared);
		save(a, create(BOOKING, "Anna"));
		a.service.tick();
		// nach der Neuvergabe scheitert die Liste der neuen Kennung: Die Speicherung der alten bleibt ausstehend
		AtomicBoolean broken = new AtomicBoolean(true);
		breakListsExceptFor(a, a.service.machineId(), broken);
		Version v2 = triggerSys011(a);
		String newId = a.service.machineId();
		assertTrue(a.service.unitState(SECOND).provisional());
		assertTrue(a.service.ownership().isOwn(v2));

		// neu verbinden verwirft die Aufträge: Was aussteht, findet der Vollabgleich im Bestand
		broken.set(false);
		a.service.connect(shared, ConnectMode.MERGE);
		a.service.tick();

		assertEquals(v2.id(), sharedClaim(SECOND, "root").version());
		assertEquals(List.of(v2.id()), feedVersions(newId, 1));
		assertFalse(a.service.unitState(SECOND).provisional());
	}

	@Test
	void lateDefeatOfAVersionFromBeforeSys011IsReported() throws IOException {
		Node a = node("a");
		a.service.startup(shared);
		Version v1 = save(a, create(BOOKING, "Anna"));
		a.service.tick();
		triggerSys011(a);

		Version foreign = overrideSharedClaim(BOOKING, "root");
		a.service.tick();

		assertEquals(List.of(new LostVersion(BOOKING, List.of(v1.id()), foreign.id())), a.events.lost);
		assertEquals(foreign.id(), a.service.unitState(BOOKING).head().id());
	}

	@Test
	void ownershipSurvivesARestartAfterSys011() throws IOException {
		Node a = node("a");
		a.service.startup(shared);
		Version v1 = save(a, create(BOOKING, "Anna"));
		a.service.tick();
		String oldId = a.service.machineId();
		breakListsExceptFor(a, oldId, new AtomicBoolean(true));
		Version v2 = triggerSys011(a);
		Ownership own = a.service.ownership();

		// Neustart mit der neuen Kennung; die Speicherung der alten wartet noch aufs Hochladen
		Node b = new Node(a.appData, "a", new NioFileLayer(), Runnable::run, Runnable::run);
		assertEquals(own, b.service.ownership());
		assertEquals(List.of(new Ownership.Previous(oldId, START)), own.previous());
		b.service.startup(shared);

		assertEquals(v2.id(), sharedClaim(SECOND, "root").version());
		assertEquals(List.of(v2.id()), feedVersions(b.service.machineId(), 1));

		// auch nach dem Neustart wird der eigene Anspruch von vor der Neuvergabe auf eine späte Niederlage geprüft
		Version foreign = overrideSharedClaim(BOOKING, "root");
		b.service.tick();

		assertEquals(List.of(new LostVersion(BOOKING, List.of(v1.id()), foreign.id())), b.events.lost);
	}

	@Test
	void versionOfThePreviousIdAfterUntilIsForeign() throws IOException {
		Node a = node("a");
		a.service.startup(shared);
		Version v1 = save(a, create(BOOKING, "Anna"));
		a.service.tick();
		String oldId = a.service.machineId();
		triggerSys011(a);
		// die kopierte Installation trägt die alte Kennung weiter und speichert nach der Neuvergabe auf v1, noch ohne Anspruch
		clock.advance(Duration.ofMinutes(1));
		Version copy = version(oldId, clock.millis(), 0, "kopie", v1.id());
		listOnly(new VersionStore(sharedRoot.resolve("data"), new NioFileLayer()), 3, BOOKING, copy);
		assertFalse(a.service.ownership().isOwn(copy));

		a.service.syncNow();
		a.service.tick();

		assertTrue(Files.exists(localFile(a, BOOKING, copy.id() + ".json")), "die Version der Kopie ist angekommen");
		UnitState state = a.service.unitState(BOOKING);
		assertEquals(List.of(), state.pending());
		assertEquals(v1.id(), state.current().id());
		assertFalse(Files.exists(sharedFile(BOOKING, "claims/" + v1.id() + ".json")), "nicht für die Kopie beansprucht");
		// die Listen der alten Kennung liest der Rechner wie die eines fremden: Die Speicherung der Kopie ist unterwegs
		assertTrue(a.events.pending.stream().anyMatch(sighting -> sighting.version().equals(copy.id())), a.events.pending.toString());
	}

	/** Der Start-Index des Dienstes (privates Feld). */
	private static Object indexOf(SyncService service) throws ReflectiveOperationException {
		Field field = SyncService.class.getDeclaredField("index");
		field.setAccessible(true);
		return field.get(service);
	}

	@Test
	void duplicateMachineDoesNotRebuildTheIndex() throws Exception {
		Node a = node("a");
		a.service.startup(shared);
		save(a, create(BOOKING, "Anna"));
		a.service.tick();
		Object index = indexOf(a.service);
		int callsBefore = a.layer.calls().size();

		triggerSys011(a);

		List<String> calls = a.layer.calls().subList(callsBefore, a.layer.calls().size());
		assertTrue(calls.stream().noneMatch(c -> c.startsWith("listTree ")), calls.toString());
		assertSame(index, indexOf(a.service), "derselbe Index, kein neuer");
		assertTrue(a.service.isStarted());
		Map<UnitKey, UnitSnapshot> snapshots = a.service.snapshots();
		assertTrue(snapshots.containsKey(BOOKING) && snapshots.containsKey(SECOND), snapshots.keySet().toString());
		// das nächste Schreiben trägt die neue Kennung in die Datei
		a.service.flushIndex();
		var root = MAPPER.readTree(Files.readAllBytes(a.appData.resolve("index/events/e1.json")));
		assertEquals(a.service.machineId(), root.get("machine").asText());
		Node restarted = new Node(a.appData, "a", new NioFileLayer(), Runnable::run, Runnable::run);
		restarted.service.startup("");
		// der Abgleich beim Start nimmt zusätzlich die Ordner von Event und Vorstellung auf
		assertTrue(restarted.service.snapshots().entrySet().containsAll(snapshots.entrySet()),
				restarted.service.snapshots().toString());
	}

	/**
	 * Der Auftrag scheitert beim zweiten Anspruch (er entsteht, die Antwort geht verloren): Der erste ist gewonnen, und seine
	 * Einheit wird schon in diesem Takt als geändert gemeldet, nicht erst, wenn der Auftrag fertig ist.
	 */
	@Test
	void claimsWonBeforeAnAbortAreReportedAsChanged() throws IOException {
		Node a = node("a");
		a.service.startup(shared);
		a.service.tick();
		Path secondClaim = Path.of(SECOND.relativePath(), "claims", "root.json");
		AtomicBoolean armed = new AtomicBoolean(true);
		a.layer.failAfterWhen(p -> p.startsWith(sharedRoot) && p.endsWith(secondClaim) && armed.getAndSet(false), "createNew",
				new IOException("Antwort verloren"));
		List<Version> saved = a.service.append("stapel", List.of(create(BOOKING, "Anna"), create(SECOND, "Berta")));
		a.events.changed.clear();

		a.service.tick();

		assertFalse(armed.get(), "der zweite Anspruch wurde versucht");
		assertEquals(saved.get(0).id(), sharedClaim(BOOKING, "root").version());
		assertTrue(a.events.changedUnits().contains(BOOKING), a.events.changed.toString());
		assertFalse(a.service.unitState(BOOKING).provisional());
		// der nächste Takt setzt beim zweiten Anspruch fort
		a.service.tick();
		assertTrue(a.events.changedUnits().contains(SECOND));
		assertFalse(a.service.unitState(SECOND).provisional());
	}

	/**
	 * Ein Speichervorgang läuft über SYS-011 hinweg: Er beginnt vor der Neuvergabe und hält beim Schreiben seiner ersten
	 * Version an; die Neuvergabe läuft durch, die Uhr geht weiter, erst dann entsteht seine zweite Version. Beide gelten als
	 * eigen und werden hochgeladen, auch nach einem Neustart, bevor der Auftrag lief.
	 */
	@Test
	void saveInFlightAcrossSys011StaysOwn() throws Exception {
		Node a = node("a");
		a.service.startup(shared);
		save(a, create(BOOKING, "Anna"));
		a.service.tick();
		String oldId = a.service.machineId();
		Feed copy = new Feed(StoreCodec.FORMAT, oldId, 2, START, List.of());
		Files.write(sharedRoot.resolve("data/feeds/" + oldId + "/2.json"), StoreCodec.write(copy));
		// ihr Hochladen stößt auf die Liste der Kopie
		save(a, create(SECOND, "Berta"));
		UnitKey third = UnitKey.booking("e1", "p1", "A-3");
		UnitKey fourth = UnitKey.booking("e1", "p1", "A-4");
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		a.layer.blockNext("createNew", entered, release);
		List<Version> saved;
		ExecutorService saver = Executors.newSingleThreadExecutor();
		try {
			Future<List<Version>> saving = saver.submit(
					() -> a.service.append("unterwegs", List.of(create(third, "Carla"), create(fourth, "Dora"))));
			assertTrue(entered.await(10, TimeUnit.SECONDS), "der Speichervorgang schreibt seine erste Version");

			a.service.tick();
			assertEquals(List.of(StoreMessages.duplicateMachine()), a.events.duplicates);
			clock.advance(Duration.ofSeconds(1));
			release.countDown();
			saved = saving.get(10, TimeUnit.SECONDS);
		} finally {
			saver.shutdownNow();
		}

		Ownership own = a.service.ownership();
		assertEquals(2, saved.size());
		assertTrue(own.isOwn(saved.get(0)), saved.get(0).id() + " gegen " + own);
		assertTrue(own.isOwn(saved.get(1)), saved.get(1).id() + " gegen " + own);
		// Neustart, bevor der Auftrag hochgeladen wurde: Der Start findet beide als ausstehend
		Node b = new Node(a.appData, "a", new NioFileLayer(), Runnable::run, Runnable::run);
		assertEquals(own, b.service.ownership());
		b.service.startup(shared);
		assertEquals(saved.get(0).id(), sharedClaim(third, "root").version());
		assertEquals(saved.get(1).id(), sharedClaim(fourth, "root").version());
	}

	/**
	 * Die Uhr ging vor SYS-011 zurück: Die Grenze der alten Kennung ist dann die letzte Millisekunde, die ihr Generator
	 * vergab, nicht die Uhrzeit der Neuvergabe. Die Speicherung davor bleibt eigen.
	 */
	@Test
	void sys011AfterTheClockWentBackKeepsEarlierVersionsOwn() throws IOException {
		Node a = node("a");
		a.service.startup(shared);
		save(a, create(BOOKING, "Anna"));
		a.service.tick();
		String oldId = a.service.machineId();
		Feed copy = new Feed(StoreCodec.FORMAT, oldId, 2, START, List.of());
		Files.write(sharedRoot.resolve("data/feeds/" + oldId + "/2.json"), StoreCodec.write(copy));
		Version saved = save(a, create(SECOND, "Berta"));
		clock.advance(Duration.ofSeconds(-5));

		a.service.tick();

		assertEquals(List.of(StoreMessages.duplicateMachine()), a.events.duplicates);
		Ownership own = a.service.ownership();
		assertEquals(List.of(new Ownership.Previous(oldId, START)), own.previous());
		assertTrue(own.isOwn(saved));
		assertEquals(own, new Node(a.appData, "a", new NioFileLayer(), Runnable::run, Runnable::run).service.ownership());
	}

	@Test
	void sectionDeletesOldTempsOfAPreviousIdToo() throws IOException {
		UnitKey hall = UnitKey.hall("h1");
		MachineIdentity before = MachineIdentity.loadOrCreate(dir.resolve("a"), () -> "a");
		before.regenerate(clock);
		Node a = nodeWithForeignHallInSharedFolder(hall);
		assertNotEquals(before.id(), a.service.machineId());
		Path unitDir = sharedFile(hall, "x").getParent();
		Duration old = SyncService.TEMP_AGE.plusHours(1);
		Path previousOld = tempFile(unitDir, before.id(), old);
		Path currentOld = tempFile(unitDir, a.service.machineId(), old);
		Path foreignOld = tempFile(unitDir, OTHER, old);

		a.service.syncNow();

		assertFalse(Files.exists(previousOld));
		assertFalse(Files.exists(currentOld));
		assertTrue(Files.exists(foreignOld));
	}

	@Test
	void readOnlySharedFolderIsErrorNotOffline() throws IOException {
		Node a = node("a");
		a.service.startup(shared);
		a.layer.failWhen(p -> p.startsWith(sharedRoot), "createNew", new AccessDeniedException("schreibgeschützt"));
		save(a, create(BOOKING, "Anna"));

		a.service.tick();
		a.service.tick();

		assertEquals(SyncState.FEHLER, a.service.syncState().get());
		assertEquals(List.of(StoreMessages.syncFailed(shared, "AccessDeniedException: schreibgeschützt")), a.events.errors);
		assertTrue(a.events.unreachable.isEmpty());
		assertTrue(a.service.unitState(BOOKING).provisional());
	}

	@Test
	void contentMismatchIsReportedNotOverwritten() throws IOException {
		Node a = node("a");
		a.service.startup(shared);
		Version v = save(a, create(BOOKING, "Anna"));
		Path there = sharedFile(BOOKING, v.id() + ".json");
		Files.createDirectories(there.getParent());
		Files.writeString(there, "anderer Inhalt");

		a.service.tick();
		a.service.tick();

		String rel = BOOKING.relativePath() + "/" + v.id() + ".json";
		assertEquals(List.of(StoreMessages.contentMismatch(rel)), a.events.errors);
		assertEquals("anderer Inhalt", Files.readString(there));
		assertTrue(a.service.unitState(BOOKING).provisional());
	}

	@Test
	void damagedChainIsRepairedOrLocked() throws IOException {
		UnitKey healable = UnitKey.event("heilbar");
		UnitKey broken = UnitKey.event("kaputt");
		Node a = node("a");
		a.service.startup(shared);
		Version h = save(a, create(healable, "gut"));
		Version k = save(a, create(broken, "weg"));
		a.service.tick();
		Path healableFile = localFile(a, healable, h.id() + ".json");
		byte[] good = Files.readAllBytes(healableFile);
		Files.writeString(healableFile, "kaputt");
		Files.writeString(localFile(a, broken, k.id() + ".json"), "kaputt");
		Files.writeString(sharedFile(broken, k.id() + ".json"), "auch kaputt");
		int callsBefore = a.layer.calls().size();

		// der Aufrufer (Oberfläche) bekommt sofort den gesperrten Stand und wartet nie auf den gemeinsamen Ordner
		assertEquals(Status.LOCKED_DAMAGED, a.service.unitState(healable).status());
		assertEquals(Status.LOCKED_DAMAGED, a.service.unitState(broken).status());
		assertTrue(a.layer.calls().subList(callsBefore, a.layer.calls().size()).stream().noneMatch(c -> c.contains(shared)));
		assertTrue(a.events.errors.isEmpty(), "ob es eine gute Kopie gibt, entscheidet erst der Takt");
		a.events.changed.clear();

		a.service.tick();

		assertTrue(a.events.changedUnits().contains(healable));
		assertFalse(a.events.changedUnits().contains(broken), "ohne gute Kopie hat sich nichts geändert");
		UnitState healed = a.service.unitState(healable);
		assertEquals(Status.OK, healed.status());
		assertEquals(h.id(), healed.head().id());
		assertArrayEquals(good, Files.readAllBytes(healableFile));
		assertTrue(names(healableFile.getParent()).stream().anyMatch(n -> n.startsWith(h.id() + ".json.corrupt-")));

		UnitState locked = a.service.unitState(broken);
		a.service.tick();
		a.service.unitState(broken);

		assertEquals(Status.LOCKED_DAMAGED, locked.status());
		assertEquals(k.id(), locked.blockedAt());
		assertEquals(List.of(StoreMessages.damaged(broken.relativePath())), a.events.errors);
	}

	@Test
	void permanentlyDamagedUnitIsNotRetriedInALoop() throws IOException {
		UnitKey broken = UnitKey.event("kaputt");
		Node a = node("a");
		a.service.startup(shared);
		Version k = save(a, create(broken, "weg"));
		a.service.tick();
		Files.writeString(localFile(a, broken, k.id() + ".json"), "kaputt");
		Files.writeString(sharedFile(broken, k.id() + ".json"), "auch kaputt");
		List<Set<UnitKey>> changed = new ArrayList<>();
		// eine Oberfläche, die bei jeder Änderungsmeldung den Stand neu holt
		a.service.setListener(new SyncListener() {
			@Override
			public void unitsChanged(Set<UnitKey> units) {
				changed.add(units);
				for (UnitKey unit : units) {
					try {
						a.service.unitState(unit);
					} catch (IOException e) {
						throw new UncheckedIOException(e);
					}
				}
			}
		});
		String sharedCopyRead = "read " + sharedFile(broken, k.id() + ".json");
		a.service.unitState(broken);
		a.service.tick();
		long reads = a.layer.calls().stream().filter(sharedCopyRead::equals).count();
		assertTrue(reads > 0, "der erste Heilversuch liest die Kopie im gemeinsamen Ordner");

		for (int i = 0; i < 3; i++) {
			a.service.unitState(broken);
			clock.advance(Duration.ofSeconds(2));
			a.service.tick();
		}

		assertEquals(reads, a.layer.calls().stream().filter(sharedCopyRead::equals).count());
		assertTrue(changed.stream().noneMatch(units -> units.contains(broken)));

		// ändert der Vollabgleich die Einheit, darf sie wieder geheilt werden
		Version foreign = version(OTHER, 1_000_000L, 0, "fremd", null);
		Files.write(sharedFile(broken, foreign.id() + ".json"), StoreCodec.write(foreign));
		a.service.syncNow();
		a.service.unitState(broken);
		a.service.tick();

		assertTrue(a.layer.calls().stream().filter(sharedCopyRead::equals).count() > reads);
	}

	@Test
	void damageInSingleModeIsReportedAtOnce() throws IOException {
		Node a = node("a");
		a.service.startup("");
		Version v = save(a, create(BOOKING, "Anna"));
		Files.writeString(localFile(a, BOOKING, v.id() + ".json"), "kaputt");

		assertEquals(Status.LOCKED_DAMAGED, a.service.unitState(BOOKING).status());

		assertEquals(List.of(StoreMessages.damaged(BOOKING.relativePath())), a.events.errors);
	}

	@Test
	void resetToLastReadableUnlocks() throws IOException {
		Node a = node("a");
		a.service.startup("");
		Version v1 = save(a, create(BOOKING, "Anna"));
		Version v2 = save(a, update(BOOKING, v1, "Anna B"));
		Files.writeString(localFile(a, BOOKING, v2.id() + ".json"), "kaputt");
		assertEquals(Status.LOCKED_DAMAGED, a.service.unitState(BOOKING).status());

		Version reset = a.service.resetToLastReadable(BOOKING);

		UnitState state = a.service.unitState(BOOKING);
		assertEquals(Status.OK, state.status());
		assertEquals(reset.id(), state.head().id());
		assertEquals(List.of(v2.id()), reset.parents());
		assertEquals(v1.payload(), reset.payload());
		assertFalse(reset.deleted());
	}

	@Test
	void resetToLastReadableInSharedModeIsClaimed() throws IOException {
		Node a = node("a");
		a.service.startup(shared);
		Version v1 = save(a, create(BOOKING, "Anna"));
		a.service.tick();
		Version v2 = save(a, update(BOOKING, v1, "Anna B"));
		a.service.tick();
		Files.writeString(localFile(a, BOOKING, v2.id() + ".json"), "kaputt");
		Files.writeString(sharedFile(BOOKING, v2.id() + ".json"), "auch kaputt");
		assertEquals(Status.LOCKED_DAMAGED, a.service.unitState(BOOKING).status());

		Version reset = a.service.resetToLastReadable(BOOKING);
		a.service.tick();

		assertEquals(reset.id(), sharedClaim(BOOKING, v2.id().value()).version());
		UnitState state = a.service.unitState(BOOKING);
		assertEquals(Status.OK, state.status());
		assertEquals(reset.id(), state.head().id());
		assertFalse(state.provisional());
		assertTrue(a.events.lost.isEmpty());
	}

	@Test
	void resetSurvivesARestartBeforeTheUpload() throws IOException {
		Node a = node("a");
		a.service.startup(shared);
		Version v1 = save(a, create(BOOKING, "Anna"));
		a.service.tick();
		Version v2 = save(a, update(BOOKING, v1, "Anna B"));
		a.service.tick();
		Files.writeString(localFile(a, BOOKING, v2.id() + ".json"), "kaputt");
		Files.writeString(sharedFile(BOOKING, v2.id() + ".json"), "auch kaputt");
		Version reset = a.service.resetToLastReadable(BOOKING);
		assertEquals(reset.id(), a.service.resetToLastReadable(BOOKING).id(), "kein zweites Zurücksetzen");
		assertTrue(a.service.unitState(BOOKING).provisional());

		// Neustart, bevor ein Takt das Zurücksetzen hochgeladen hat
		Node restarted = new Node(a.appData, "a", new NioFileLayer(), Runnable::run, task -> {
		});
		assertTrue(restarted.service.startup(shared));

		assertEquals(reset.id(), sharedClaim(BOOKING, v2.id().value()).version());
		UnitState state = restarted.service.unitState(BOOKING);
		assertEquals(Status.OK, state.status());
		assertEquals(reset.id(), state.head().id());
		assertTrue(restarted.events.lost.isEmpty());
	}

	@Test
	void resetWithoutDamageIsRejected() throws IOException {
		Node a = node("a");
		a.service.startup("");
		save(a, create(BOOKING, "Anna"));

		assertThrows(IllegalStateException.class, () -> a.service.resetToLastReadable(BOOKING));
	}

	// --- Vollabgleich

	@Test
	void fullSyncRunsEveryTenMinutes() throws IOException {
		Node a = node("a");
		a.service.startup(shared);
		UnitKey hall = UnitKey.hall("h1");
		Version foreign = version(OTHER, 1_000_000L, 0, "fremd", null);
		Files.createDirectories(sharedFile(hall, "x").getParent());
		Files.write(sharedFile(hall, foreign.id() + ".json"), StoreCodec.write(foreign));

		clock.advance(Duration.ofMinutes(9));
		a.service.tick();
		assertFalse(Files.exists(localFile(a, hall, foreign.id() + ".json")));

		clock.advance(Duration.ofMinutes(1));
		a.service.tick();
		assertTrue(Files.exists(localFile(a, hall, foreign.id() + ".json")));
	}

	@Test
	void feedWrittenDuringFullSyncIsReadByNextPoll() throws IOException {
		VersionStore sharedStore = new VersionStore(sharedRoot.resolve("data"), new NioFileLayer());
		Version p1 = version(OTHER, 1_000_000L, 0, "fremd-1", null);
		Version p2 = version(OTHER, 2_000_000L, 1, "fremd-2", p1.id());
		publish(sharedStore, 1, BOOKING, p1);
		Path appData = dir.resolve("a");
		HookLayer hook = new HookLayer();
		// sobald der Vollabgleich beide Bäume gelesen hat und zu kopieren beginnt, schreibt der andere Rechner weiter
		hook.once(p -> p.startsWith(appData.resolve("data")), () -> publish(sharedStore, 2, BOOKING, p2));
		Node a = new Node(appData, "a", hook, Runnable::run, Runnable::run);

		assertTrue(a.service.startup(shared));
		a.service.tick();

		assertEquals(p2.id(), a.service.unitState(BOOKING).head().id());
		assertTrue(a.events.changedUnits().contains(BOOKING));
	}

	@Test
	void syncNowRunsAFullSync() throws IOException {
		Node a = node("a");
		a.service.startup(shared);
		UnitKey hall = UnitKey.hall("h1");
		Version foreign = version(OTHER, 1_000_000L, 0, "fremd", null);
		Files.createDirectories(sharedFile(hall, "x").getParent());
		Files.write(sharedFile(hall, foreign.id() + ".json"), StoreCodec.write(foreign));

		a.service.syncNow();

		assertTrue(Files.exists(localFile(a, hall, foreign.id() + ".json")));
	}

	// --- Eigene alte temporäre Dateien im gemeinsamen Ordner

	/** Eine temporäre Datei {@code ~karv-<Kennung>-<UUID>.tmp} (ohne Kennung bei {@code null}), um {@code age} gealtert. */
	private static Path tempFile(Path folder, String tag, Duration age) throws IOException {
		Files.createDirectories(folder);
		Path temp = folder.resolve(FileLayer.TEMP_PREFIX + (tag == null ? "" : tag + "-") + UUID.randomUUID()
				+ FileLayer.TEMP_SUFFIX);
		Files.write(temp, new byte[] { 1 });
		Files.setLastModifiedTime(temp, FileTime.from(Instant.now().minus(age)));
		return temp;
	}

	/** Ein gestarteter Rechner und eine fremde Halle im gemeinsamen Ordner, die der nächste Vollabgleich holt. */
	private Node nodeWithForeignHallInSharedFolder(UnitKey hall) throws IOException {
		Node a = node("a");
		a.service.startup(shared);
		Version foreign = version(OTHER, 1_000_000L, 0, "fremd", null);
		Files.createDirectories(sharedFile(hall, "x").getParent());
		Files.write(sharedFile(hall, foreign.id() + ".json"), StoreCodec.write(foreign));
		return a;
	}

	@Test
	void sectionDeletesOnlyOwnOldTempsInTheSharedFolder() throws IOException {
		UnitKey hall = UnitKey.hall("h1");
		Node a = nodeWithForeignHallInSharedFolder(hall);
		String me = a.service.machineId();
		Path unitDir = sharedFile(hall, "x").getParent();
		Path claimsDir = unitDir.resolve("claims");
		Duration old = SyncService.TEMP_AGE.plusHours(1);
		Duration young = SyncService.TEMP_AGE.minusHours(1);
		Path ownOld = tempFile(unitDir, me, old);
		Path ownOldInClaims = tempFile(claimsDir, me, old);
		Path ownYoung = tempFile(unitDir, me, young);
		Path foreignOld = tempFile(unitDir, OTHER, old);
		Path foreignOldInClaims = tempFile(claimsDir, OTHER, old);
		Path untaggedOld = tempFile(unitDir, null, old);
		Path untaggedOldInClaims = tempFile(claimsDir, null, old);

		a.service.syncNow();

		assertFalse(Files.exists(ownOld));
		assertFalse(Files.exists(ownOldInClaims));
		assertTrue(Files.exists(ownYoung));
		assertTrue(Files.exists(foreignOld));
		assertTrue(Files.exists(foreignOldInClaims));
		assertTrue(Files.exists(untaggedOld));
		assertTrue(Files.exists(untaggedOldInClaims));
		// der Abschnitt ist trotzdem gelaufen
		assertEquals(0, a.events.errors.size());
		assertTrue(a.events.changedUnits().contains(hall));
	}

	@Test
	void failingTempCleanupInTheSharedFolderDoesNotFailTheSection() throws IOException {
		UnitKey hall = UnitKey.hall("h1");
		Node a = nodeWithForeignHallInSharedFolder(hall);
		Path unitDir = sharedFile(hall, "x").getParent();
		Path ownOld = tempFile(unitDir, a.service.machineId(), SyncService.TEMP_AGE.plusHours(1));
		a.layer.failWhen(p -> p.startsWith(sharedRoot), "deleteOldTemps", new IOException("Netzlaufwerk getrennt"));

		a.service.syncNow();

		assertTrue(a.layer.calls().stream().anyMatch(c -> c.startsWith("deleteOldTemps " + unitDir)));
		assertTrue(Files.exists(ownOld));
		assertTrue(Files.exists(localFile(a, hall, VersionId.create(1_000_000L, OTHER, 0) + ".json")));
		assertEquals(0, a.events.errors.size());
		assertEquals(0, a.events.unreachable.size());
	}

	@Test
	void failingTempCleanupInOneFolderDoesNotSkipTheOthers() throws IOException {
		UnitKey hall = UnitKey.hall("h1");
		Node a = nodeWithForeignHallInSharedFolder(hall);
		Path unitDir = sharedFile(hall, "x").getParent();
		Path claimsDir = unitDir.resolve("claims");
		Duration old = SyncService.TEMP_AGE.plusHours(1);
		Path stuck = tempFile(unitDir, a.service.machineId(), old);
		Path other = tempFile(claimsDir, a.service.machineId(), old);
		a.layer.failWhen(p -> p.equals(unitDir), "deleteOldTemps", new IOException("gesperrt"));

		a.service.syncNow();

		assertTrue(Files.exists(stuck));
		assertFalse(Files.exists(other));
		assertEquals(0, a.events.errors.size());
	}

	@Test
	void sectionListsEachSharedUnitFolderOnlyOnceAlsoForTheCleanup() throws IOException {
		UnitKey hall = UnitKey.hall("h1");
		Node a = nodeWithForeignHallInSharedFolder(hall);
		Path unitDir = sharedFile(hall, "x").getParent();
		Path claimsDir = unitDir.resolve("claims");
		Path ownOld = tempFile(unitDir, a.service.machineId(), SyncService.TEMP_AGE.plusHours(1));
		int before = a.layer.calls().size();

		a.service.syncNow();

		List<String> calls = a.layer.calls().subList(before, a.layer.calls().size());
		List<String> listings = calls.stream().filter(c -> c.startsWith("listNames")
				&& (c.endsWith(" " + unitDir) || c.endsWith(" " + claimsDir))).toList();
		// ein Listenzugriff je Ordner der Einheit, der Dateien und temporäre Reste zugleich liefert
		assertEquals(List.of("listNamesWithTemps " + unitDir, "listNamesWithTemps " + claimsDir), listings);
		// für das Alter wird nur die eigene Datei abgefragt
		assertEquals(List.of("deleteOldTemps " + unitDir), calls.stream().filter(c -> c.startsWith("deleteOldTemps")).toList());
		assertFalse(Files.exists(ownOld));
	}

	// --- Vollabgleich in Abschnitten über mehrere Takte

	/** So viel Zeit kostet ein Lesezugriff auf der Test-Uhr, wenn ein Test den Lauf über mehrere Takte verteilt. */
	private static final Duration READ_COST = Duration.ofMillis(20);

	/** Ab jetzt stellt jeder Lesezugriff des Rechners die Test-Uhr um {@code cost} vor. */
	private void slowReads(Node node, Duration cost) {
		node.layer.onEach("read", () -> clock.advance(cost));
	}

	/**
	 * Ein gestarteter Rechner mit {@code count} fremden Hallen auf beiden Seiten (sechs Abschnitte bei 300), dessen
	 * Lesezugriffe danach je {@link #READ_COST} kosten: Ein Abschnitt liest je Halle den lokalen Anspruch, also 50 × 20 ms.
	 * Den ersten Lauf hat der Takt aus {@code startup} schon erledigt (der Worker des Tests führt ihn sofort aus); der
	 * nächste Takt beginnt einen neuen, denn die 10 Minuten sind um.
	 */
	private Node slowNodeWithHalls(int count) throws IOException {
		return slowNodeWithHalls(count, READ_COST);
	}

	/** Wie {@link #slowNodeWithHalls(int)}, aber mit {@code cost} je Lesezugriff: Ein Abschnitt dauert 50 × {@code cost}. */
	private Node slowNodeWithHalls(int count, Duration cost) throws IOException {
		Path appData = dir.resolve("a");
		seedForeignHalls(appData, count);
		Node a = new Node(appData, "a", new NioFileLayer(), Runnable::run, Runnable::run);
		a.service.startup(shared);
		assertNull(a.service.fullSyncProgress().get());
		slowReads(a, cost);
		clock.advance(SyncService.FULL_SYNC_INTERVAL);
		return a;
	}

	/** Takte, bis der Rechner keinen Vollabgleich mehr laufen hat; liefert die Zahl der Takte. */
	private static int tickUntilRunEnds(Node node) {
		int ticks = 0;
		do {
			node.service.tick();
			ticks++;
			assertTrue(ticks < 100, "der Lauf endet nicht");
		} while (node.service.fullSyncProgress().get() != null);
		return ticks;
	}

	/** Eine fremde Einheit mit Version und Anspruch im genannten Bestand, ohne Liste. */
	private static Version seedForeign(VersionStore store, UnitKey unit, int counter) throws IOException {
		Version v = version(OTHER, START.toEpochMilli() - 3_600_000L, counter, "f" + counter, null);
		byte[] bytes = StoreCodec.write(v);
		assertTrue(store.writeVersion(unit, v.id(), bytes));
		assertTrue(store.createClaim(unit, "root", StoreCodec.write(new Claim(v.id(), StoreCodec.sha256(bytes), OTHER, START))));
		return v;
	}

	@Test
	void fullSyncSpreadsOverSeveralTicksWithinTheBudget() throws IOException {
		Node a = slowNodeWithHalls(300);
		Duration block = READ_COST.multipliedBy(FullSyncRun.HALL_BUNDLE);

		int ticks = 0;
		do {
			Instant before = clock.instant();
			a.service.tick();
			ticks++;
			Duration took = Duration.between(before, clock.instant());
			assertTrue(took.compareTo(SyncService.FULL_SYNC_SLICE_BUDGET.plus(block)) <= 0, "Takt " + ticks + " dauerte " + took);
			assertTrue(ticks < 100, "der Lauf endet nicht");
		} while (a.service.fullSyncProgress().get() != null);

		assertEquals(3, ticks, "sechs Abschnitte zu je 1 s, zwei je Takt");
		assertTrue(a.events.errors.isEmpty());
		assertEquals(SyncState.AKTUELL, a.service.syncState().get());
	}

	@Test
	void unknownUnitsSpreadOverSeveralTicksAndTheReportStaysInTheBudget() throws IOException {
		Node a = node("a");
		a.service.startup(shared);
		save(a, create(BOOKING, "Anna"));
		a.service.tick();
		// an der App vorbei in beide Bestände kopiert: 300 Hallen, die der Index nicht kennt
		List<UnitKey> halls = new ArrayList<>();
		for (int n = 0; n < 300; n++) {
			halls.add(UnitKey.hall(String.format("k%03d", n)));
		}
		copyPastTheApp(OTHER, halls, a.localData(), sharedRoot.resolve("data"));
		// je Halle liest der Abschnitt den lokalen Anspruch und löst sie einmal auf (Version und Anspruch): Ein Abschnitt
		// dauert 50 × 3 × 3 ms = 0,45 s, und das Melden am Ende des Takts löst die Hallen nicht noch einmal auf. Vier
		// Abschnitte passen in einen Takt (1,8 s); löste das Melden sie erneut auf (50 × 2 × 3 ms je Abschnitt), wären es 3 s
		slowReads(a, Duration.ofMillis(3));
		clock.advance(SyncService.FULL_SYNC_INTERVAL);
		a.events.changed.clear();

		int ticks = 0;
		do {
			Instant before = clock.instant();
			a.service.tick();
			ticks++;
			Duration took = Duration.between(before, clock.instant());
			// samt Melden am Ende des Takts im Budget, bis auf die übrige Arbeit des Takts (einzelne Lesezugriffe)
			Duration otherWork = Duration.ofMillis(100);
			assertTrue(took.compareTo(SyncService.FULL_SYNC_SLICE_BUDGET.plus(otherWork)) <= 0,
					"Takt " + ticks + " dauerte " + took);
			assertTrue(ticks < 100, "der Lauf endet nicht");
		} while (a.service.fullSyncProgress().get() != null);

		assertTrue(ticks > 1, "über mehrere Takte verteilt: " + ticks);
		assertIndexed(a, halls);
		assertTrue(a.events.changedUnits().containsAll(halls));
		assertTrue(a.events.errors.isEmpty(), a.events.errors.toString());
	}

	/**
	 * Je Einheit, wie oft der Rechner in {@code calls} eine ihrer Versionsdateien im lokalen Bestand gelesen hat; wer eine
	 * Einheit auflöst, liest jede davon einmal. Anspruchsdateien zählen nicht (der Abgleich liest sie auch selbst).
	 */
	private static Map<UnitKey, Integer> localVersionReads(Node node, Collection<UnitKey> units, List<String> calls) {
		String data = "read " + node.localData() + java.io.File.separator;
		Map<String, UnitKey> byFolder = new HashMap<>();
		units.forEach(unit -> byFolder.put(unit.relativePath(), unit));
		Map<UnitKey, Integer> reads = new HashMap<>();
		for (String call : calls) {
			if (call.startsWith(data)) {
				String rel = call.substring(data.length()).replace('\\', '/');
				UnitKey unit = byFolder.get(rel.substring(0, Math.max(0, rel.lastIndexOf('/'))));
				if (unit != null) {
					reads.merge(unit, 1, Integer::sum);
				}
			}
		}
		return reads;
	}

	@Test
	void changedUnitIsReadOncePerTick() throws IOException {
		Node a = node("a");
		a.service.startup(shared);
		save(a, create(BOOKING, "Anna"));
		a.service.tick();
		// an der App vorbei in beide Bestände kopiert: Jeder Block nimmt seine Hallen auf (geändert), kopiert aber nichts
		List<UnitKey> halls = new ArrayList<>();
		for (int n = 0; n < 150; n++) {
			halls.add(UnitKey.hall(String.format("k%03d", n)));
		}
		copyPastTheApp(OTHER, halls, a.localData(), sharedRoot.resolve("data"));
		// ein Abschnitt dauert 50 × 3 × 10 ms = 1,5 s: ein Abschnitt je Takt
		slowReads(a, Duration.ofMillis(10));
		clock.advance(SyncService.FULL_SYNC_INTERVAL);

		Map<UnitKey, Integer> total = new HashMap<>();
		int ticks = 0;
		do {
			int before = a.layer.calls().size();
			a.service.tick();
			ticks++;
			Map<UnitKey, Integer> inTick = localVersionReads(a, halls, a.layer.calls().subList(before, a.layer.calls().size()));
			String tick = "Takt " + ticks + ": ";
			inTick.forEach((unit, reads) -> assertEquals(1, reads, tick + unit.relativePath()));
			inTick.forEach((unit, reads) -> total.merge(unit, reads, Integer::sum));
			assertTrue(ticks < 100, "der Lauf endet nicht");
		} while (a.service.fullSyncProgress().get() != null);

		assertTrue(ticks > 1, "über mehrere Takte verteilt: " + ticks);
		assertEquals(halls.size(), total.size(), "jede Halle wurde gelesen");
		assertIndexed(a, halls);
		assertTrue(a.events.changedUnits().containsAll(halls));
		assertTrue(a.events.errors.isEmpty(), a.events.errors.toString());
	}

	/** 50 fremde Hallen nur im gemeinsamen Ordner: ein voller erster Abschnitt, der vor jeder Halle "x…" an der Reihe ist. */
	private void seedFiftyForeignHallsInShared() throws IOException {
		VersionStore sharedStore = new VersionStore(sharedRoot.resolve("data"), new NioFileLayer());
		for (int n = 0; n < FullSyncRun.HALL_BUNDLE; n++) {
			seedForeign(sharedStore, UnitKey.hall(String.format("a%03d", n)), n);
		}
	}

	@Test
	void unitChangedByAJobAfterItsBlockIsResolvedAgainAtTheEndOfTheTick() throws IOException {
		Node a = node("a");
		a.service.startup(shared);
		UnitKey hall = UnitKey.hall("x");
		seedFiftyForeignHallsInShared();
		Version theirs = seedForeign(new VersionStore(sharedRoot.resolve("data"), new NioFileLayer()), hall, 100);
		clock.advance(SyncService.FULL_SYNC_INTERVAL);
		// während des ersten Abschnitts (nach dem Hochladen vor dem Lauf) ein Verkauf auf der Halle des zweiten: Ihr Block
		// holt die fremde Version und löst die Halle auf, erst das Hochladen nach dem Lauf beansprucht den Verkauf
		AtomicBoolean armed = new AtomicBoolean(true);
		AtomicReference<Version> sale = new AtomicReference<>();
		a.layer.onEach("listNamesWithTemps", () -> {
			if (armed.compareAndSet(true, false)) {
				try {
					sale.set(save(a, update(hall, theirs, "Bert")));
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			}
		});

		a.service.tick();

		assertNull(a.service.fullSyncProgress().get(), "ein Takt, ein Lauf");
		assertEquals(sale.get().id(), sharedClaim(hall, theirs.id().value()).version(), "im selben Takt hochgeladen");
		UnitIndex.Entry entry = a.service.index().entry(hall);
		assertFalse(entry.pending(), "der Index kennt den Anspruch");
		assertEquals(List.of(theirs.id(), sale.get().id()), entry.chain().stream().map(ChainStep::version).toList());
		assertIndexed(a, List.of(hall));
		assertTrue(a.events.changedUnits().contains(hall));
		assertTrue(a.events.errors.isEmpty(), a.events.errors.toString());
	}

	@Test
	void unitChangedByThePollAfterItsBlockIsResolvedAgainAtTheEndOfTheTick() throws IOException {
		Node a = node("a");
		a.service.startup(shared);
		UnitKey hall = UnitKey.hall("x");
		VersionStore sharedStore = new VersionStore(sharedRoot.resolve("data"), new NioFileLayer());
		seedFiftyForeignHallsInShared();
		Version first = seedForeign(sharedStore, hall, 100);
		Version second = version(OTHER, START.toEpochMilli() - 1000, 101, "fremd-2", first.id());
		clock.advance(SyncService.FULL_SYNC_INTERVAL);
		// sobald der Block der Halle ihre Dateien hereinkopiert hat, legt der fremde Rechner eine neue Version mit Liste ab:
		// Das Abholen am Ende des Takts holt sie, nicht mehr der Block
		Path hallClaim = localFile(a, hall, "claims/root.json");
		AtomicBoolean armed = new AtomicBoolean(true);
		a.layer.onEach("listNames", () -> {
			if (Files.exists(hallClaim) && armed.compareAndSet(true, false)) {
				try {
					publish(sharedStore, 1, hall, second);
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			}
		});

		a.service.tick();

		assertFalse(armed.get(), "die neue Version kam während des Takts");
		assertNull(a.service.fullSyncProgress().get(), "ein Takt, ein Lauf");
		// zuerst der Index (unitState trüge den Stand selbst ein)
		assertEquals(second.id(), a.service.snapshots().get(hall).version(), "im Index nach dem Abholen im selben Takt");
		assertIndexed(a, List.of(hall));
		assertEquals(second.id(), a.service.unitState(hall).head().id());
		assertTrue(a.events.changedUnits().contains(hall));
	}

	@Test
	void sliceStartsNoBlockThatWouldEndAfterTheBudget() throws IOException {
		Node a = slowNodeWithHalls(300, Duration.ofMillis(16)); // ein Abschnitt dauert 0,8 s
		Duration block = Duration.ofMillis(800);

		Instant before = clock.instant();
		a.service.tick();
		Duration took = Duration.between(before, clock.instant());

		// zwei Blöcke (bis 1,6 s); ein dritter endete bei 2,4 s, also nach dem Budget von 2 s
		assertEquals(2.0 / 6, a.service.fullSyncProgress().get(), 1e-9);
		assertTrue(took.compareTo(SyncService.FULL_SYNC_SLICE_BUDGET) <= 0, "Takt dauerte " + took);
		assertTrue(took.compareTo(block.multipliedBy(2)) >= 0, "Takt dauerte " + took);
		assertEquals(2, tickUntilRunEnds(a), "zwei weitere Takte für die übrigen vier Abschnitte");
	}

	@Test
	void blockLongerThanTheBudgetStillRunsAndTheRunAdvances() throws IOException {
		Node a = slowNodeWithHalls(300, Duration.ofMillis(60)); // ein Abschnitt dauert 3 s
		Duration block = Duration.ofSeconds(3);

		double progress = 0;
		for (int tick = 1; tick <= 5; tick++) {
			Instant before = clock.instant();
			a.service.tick();
			Duration took = Duration.between(before, clock.instant());
			Double now = a.service.fullSyncProgress().get();
			assertNotNull(now, "Takt " + tick + ": der Lauf ist noch nicht fertig");
			assertEquals(tick / 6.0, now, 1e-9, "ein Block je Takt");
			assertTrue(now > progress);
			progress = now;
			assertTrue(took.compareTo(block) >= 0 && took.compareTo(block.plusMillis(500)) < 0, "Takt dauerte " + took);
		}
		a.service.tick();
		assertNull(a.service.fullSyncProgress().get(), "der sechste Block beendet den Lauf");
	}

	@Test
	void saleIsUploadedBetweenSlicesOfARun() throws IOException {
		Node a = slowNodeWithHalls(300);
		a.service.tick();
		assertNotNull(a.service.fullSyncProgress().get(), "der Lauf ist nach einem Takt nicht fertig");

		Version v = save(a, create(BOOKING, "Anna"));
		a.service.tick();

		assertEquals(v.id(), sharedClaim(BOOKING, "root").version());
		assertFalse(a.service.unitState(BOOKING).provisional());
		Double progress = a.service.fullSyncProgress().get();
		assertNotNull(progress, "der Lauf läuft noch");
		assertTrue(progress < 1.0);
	}

	@Test
	void runResumesAtThePointerAfterBeingOffline() throws IOException {
		Node a = slowNodeWithHalls(300);
		a.service.tick();
		double before = a.service.fullSyncProgress().get();

		a.down.set(true);
		a.service.tick();
		assertEquals(SyncState.OFFLINE, a.service.syncState().get());
		assertEquals(before, a.service.fullSyncProgress().get(), "der Lauf bleibt am Zeiger stehen");
		a.down.set(false);
		int calls = a.layer.calls().size();
		a.service.tick();

		assertEquals(SyncState.AKTUELL, a.service.syncState().get());
		assertTrue(a.service.fullSyncProgress().get() > before, "der Lauf geht am Zeiger weiter");
		// die erste Halle (Abschnitt 1, vor dem Ausfall erledigt) wird nicht noch einmal gelesen
		assertTrue(a.layer.calls().subList(calls, a.layer.calls().size()).stream()
				.noneMatch(c -> c.replace('\\', '/').contains("/halls/f0/")));
	}

	@Test
	void newConnectionDiscardsTheRun() throws IOException {
		Path appData = dir.resolve("a");
		seedForeignHalls(appData, 300);
		// die Takte, die startup und connect einplanen, verwirft der Worker: Der Test löst sie selbst aus (der erste Lauf der
		// neuen Verbindung liefe sonst am Stück)
		Node a = new Node(appData, "a", new NioFileLayer(), Runnable::run, task -> {
		});
		a.service.startup(shared);
		// der erste Lauf nach dem Start, ohne Kosten in einem Takt
		a.service.tick();
		assertNull(a.service.fullSyncProgress().get());
		slowReads(a, READ_COST);
		clock.advance(SyncService.FULL_SYNC_INTERVAL);
		a.service.tick();
		double before = a.service.fullSyncProgress().get();
		int calls = a.layer.calls().size();

		a.service.connect(shared, ConnectMode.MERGE);
		a.service.tick();

		assertTrue(a.service.fullSyncProgress().get() < before, "ein neuer Lauf");
		assertTrue(a.layer.calls().subList(calls, a.layer.calls().size()).stream()
				.anyMatch(c -> c.startsWith("read ") && c.replace('\\', '/').contains("/halls/f0/")), "der neue Lauf beginnt vorn");
	}

	@Test
	void mergeAcrossSeveralTicksClearsConnectModeOnlyAtTheEnd() throws IOException {
		Path appData = dir.resolve("a");
		VersionStore localStore = new VersionStore(appData.resolve("data"), new NioFileLayer());
		List<UnitKey> halls = new ArrayList<>();
		for (int i = 0; i < 300; i++) {
			halls.add(UnitKey.hall("f" + i));
			seedForeign(localStore, halls.get(i), i);
		}
		// der gemeinsame Ordner hat schon Einheiten: Es gilt der Modus
		seedForeign(new VersionStore(sharedRoot.resolve("data"), new NioFileLayer()), UnitKey.event("gemeinsam"), 999);
		// die Takte, die connect einplant, verwirft der Worker: Der Test löst sie selbst aus (der erste Lauf liefe sonst am Stück)
		Node a = new Node(appData, "a", new NioFileLayer(), Runnable::run, task -> {
		});
		a.service.startup("");
		slowReads(a, READ_COST);

		a.service.connect(shared, ConnectMode.MERGE);
		a.service.tick();
		assertNotNull(a.service.fullSyncProgress().get(), "der Lauf braucht mehrere Takte");
		// "f99" ist die letzte Halle nach Namen
		assertFalse(Files.exists(sharedFile(UnitKey.hall("f99"), "claims/root.json")));
		tickUntilRunEnds(a);

		// fremde lokale Ansprüche lädt nur das Mitnehmen hoch: Es galt bis zum letzten Abschnitt
		for (UnitKey hall : halls) {
			assertTrue(Files.exists(sharedFile(hall, "claims/root.json")), hall.relativePath());
		}
		// danach ist der Modus vergessen: kein neuer Lauf
		a.service.tick();
		assertNull(a.service.fullSyncProgress().get());
		assertTrue(a.events.errors.isEmpty());
	}

	@Test
	void setAsideAcrossSeveralTicksKeepsTheSetAsideFolder() throws IOException {
		VersionStore sharedStore = new VersionStore(sharedRoot.resolve("data"), new NioFileLayer());
		for (int i = 0; i < 300; i++) {
			seedForeign(sharedStore, UnitKey.hall("f" + i), i);
		}
		// die Takte, die connect einplant, verwirft der Worker: Der Test löst sie selbst aus (der erste Lauf liefe sonst am Stück)
		Node earlier = new Node(dir.resolve("a"), "a", new NioFileLayer(), Runnable::run, task -> {
		});
		earlier.service.startup("");
		save(earlier, create(UnitKey.event("lokal"), "A"));
		// ein neuer Programmlauf: Seit seinem Start ist nichts gespeichert, das Beiseitelegen ist erlaubt (Schutzregel)
		Node a = new Node(dir.resolve("a"), "a", new NioFileLayer(), Runnable::run, task -> {
		});
		a.service.startup("");
		slowReads(a, READ_COST);

		a.service.connect(shared, ConnectMode.SET_ASIDE);
		a.service.tick();
		assertNotNull(a.service.fullSyncProgress().get(), "der Lauf braucht mehrere Takte");
		// ein Verkauf mitten im Lauf liegt im neuen Bestand: Er wird nicht noch einmal beiseitegelegt
		Version sale = save(a, create(BOOKING, "Anna"));
		tickUntilRunEnds(a);

		List<String> aside = names(a.appData).stream().filter(n -> n.startsWith("data-beiseite-")).toList();
		assertEquals(1, aside.size());
		assertTrue(Files.isDirectory(a.appData.resolve(aside.get(0)).resolve("events/lokal")));
		assertFalse(Files.exists(sharedRoot.resolve("data/events/lokal")));
		assertEquals(sale.id(), a.service.unitState(BOOKING).head().id());
		assertEquals(sale.id(), sharedClaim(BOOKING, "root").version());
		assertEquals(300, a.service.hallIds().size());
		a.service.tick();
		assertNull(a.service.fullSyncProgress().get(), "der Modus ist nach dem Lauf vergessen");
	}

	@Test
	void wishDuringARunStartsANewRunRightAfter() throws IOException {
		Node a = slowNodeWithHalls(300);
		a.service.tick();
		double first = a.service.fullSyncProgress().get();

		// syncNow plant einen Takt ein, den der Worker des Tests sofort ausführt: Er setzt den Lauf fort
		a.service.syncNow();
		assertTrue(a.service.fullSyncProgress().get() > first, "ein laufender Lauf wird nicht neu begonnen");
		tickUntilRunEnds(a);

		a.service.tick();
		assertNotNull(a.service.fullSyncProgress().get(), "direkt nach dem Ende beginnt ein neuer Lauf");
		tickUntilRunEnds(a);
		a.service.tick();
		assertNull(a.service.fullSyncProgress().get(), "ein Wunsch, ein Lauf");
	}

	@Test
	void lateDefeatInALaterSectionIsFound() throws IOException {
		Path appData = dir.resolve("a");
		seedForeignHalls(appData, 300);
		Node a = new Node(appData, "a", new NioFileLayer(), Runnable::run, Runnable::run);
		a.service.startup(shared);
		Version va = save(a, create(BOOKING, "Anna"));
		a.service.tick();
		assertEquals(va.id(), sharedClaim(BOOKING, "root").version());
		// die Beobachtung des Anspruchs läuft ab; der nächste Lauf ist erst in 10 Minuten
		clock.advance(Duration.ofMinutes(20));
		a.service.tick();
		Version foreign = overrideSharedClaim(BOOKING, "root");
		clock.advance(SyncService.FULL_SYNC_INTERVAL);
		slowReads(a, READ_COST);

		a.service.tick();
		assertTrue(a.events.lost.isEmpty(), "die Buchung kommt in einem späteren Abschnitt");
		tickUntilRunEnds(a);

		assertEquals(List.of(new LostVersion(BOOKING, List.of(va.id()), foreign.id())), a.events.lost);
		assertEquals(foreign.id(), a.service.unitState(BOOKING).head().id());
	}

	@Test
	void emptiedSharedFolderIsRefilledByEachMachinesOwnRun() throws IOException {
		UnitKey seatA = UnitKey.booking("e1", "p1", "A-1");
		UnitKey seatB = UnitKey.booking("e1", "p2", "B-1");
		UnitKey seatC = UnitKey.booking("e2", "p1", "C-1");
		UnitKey contested = UnitKey.booking("e1", "p1", "A-9");
		Node a = node("a");
		Node b = node("b");
		Node c = node("c");
		List<Node> all = List.of(a, b, c);
		for (Node node : all) {
			node.service.startup(shared);
		}
		Version va = save(a, create(seatA, "Anna"));
		Version vb = save(b, create(seatB, "Bert"));
		Version vc = save(c, create(seatC, "Clara"));
		for (Node node : all) {
			node.service.tick();
		}
		// die Suche nach neuen Rechnern (alle 30 Sekunden): Danach hat jeder die Verkäufe der anderen
		clock.advance(SyncService.DISCOVERY_INTERVAL);
		for (Node node : all) {
			node.service.tick();
		}
		assertEquals(va.id(), c.service.unitState(seatA).head().id());
		// A verkauft noch einen Platz; bevor die anderen ihn abholen, wird der gemeinsame Ordner geleert
		Version a9 = save(a, create(contested, "Anton"));
		a.service.tick();
		deleteTree(sharedRoot.resolve("data"));
		// B kennt den Verkauf von A nicht und vergibt den Platz neu
		Version b9 = save(b, create(contested, "Berta"));
		b.service.tick();
		assertEquals(b9.id(), sharedClaim(contested, "root").version());

		// der nächste Lauf jedes Rechners, in Abschnitten über mehrere Takte
		clock.advance(SyncService.FULL_SYNC_INTERVAL);
		for (Node node : all) {
			slowReads(node, Duration.ofSeconds(1));
		}
		int rounds = 0;
		boolean running;
		do {
			running = false;
			for (Node node : all) {
				node.service.tick();
				running |= node.service.fullSyncProgress().get() != null;
			}
			rounds++;
			assertTrue(rounds < 50, "die Läufe enden nicht");
		} while (running);

		assertTrue(rounds > 1, "die Läufe verteilen sich über mehrere Takte");
		// kein Verkauf verschwindet: jeder Rechner hat seine Versionen und Ansprüche wieder hochgeladen
		assertEquals(va.id(), sharedClaim(seatA, "root").version());
		assertEquals(vb.id(), sharedClaim(seatB, "root").version());
		assertEquals(vc.id(), sharedClaim(seatC, "root").version());
		for (Map.Entry<UnitKey, Version> sold : Map.of(seatA, va, seatB, vb, seatC, vc, contested, b9).entrySet()) {
			assertTrue(Files.exists(sharedFile(sold.getKey(), sold.getValue().id() + ".json")), sold.getKey().relativePath());
		}
		assertTrue(Files.exists(sharedFile(contested, a9.id() + ".json")), "auch die verlorene Version bleibt erhalten");
		// der inzwischen neu vergebene Platz ist ein Konflikt, kein stiller Verlust
		assertEquals(b9.id(), sharedClaim(contested, "root").version());
		assertEquals(List.of(new LostVersion(contested, List.of(a9.id()), b9.id())), a.events.lost);
		// ohne Fachregel löst A den Verlust zu "Deren" auf
		assertEquals(payload("Berta"), a.service.unitState(contested).head().payload());
		assertTrue(b.events.lost.isEmpty());
		assertTrue(c.events.lost.isEmpty());
	}

	@Test
	void bookingAddedBehindThePointerIsFetchedByTheFeedNotLost() throws IOException {
		Path appData = dir.resolve("a");
		VersionStore localStore = new VersionStore(appData.resolve("data"), new NioFileLayer());
		VersionStore sharedStore = new VersionStore(sharedRoot.resolve("data"), new NioFileLayer());
		// eine Vorstellung mit zwei Blöcken
		for (int i = 0; i < FullSyncRun.BOOKING_BLOCK + 100; i++) {
			UnitKey booking = UnitKey.booking("e1", "p1", String.format("S-%04d", i));
			seedForeign(localStore, booking, i);
			seedForeign(sharedStore, booking, i);
		}
		// der fremde Rechner hat schon eine Liste: A findet ihn im ersten Takt
		publish(sharedStore, 1, UnitKey.hall("h1"), version(OTHER, START.toEpochMilli() - 2000, 9000, "liste-1", null));
		Node a = new Node(appData, "a", new NioFileLayer(), Runnable::run, Runnable::run);
		a.service.startup(shared);
		// der erste Lauf lief im Takt aus startup; der nächste beginnt nach 10 Minuten
		slowReads(a, READ_COST);
		clock.advance(SyncService.FULL_SYNC_INTERVAL);
		a.service.tick();
		assertNotNull(a.service.fullSyncProgress().get(), "der zweite Block der Vorstellung steht noch aus");

		// zwischen den Blöcken: vor dem Zeiger eine Buchung mit Liste und eine ohne, hinter dem Zeiger eine mit Liste
		UnitKey behind = UnitKey.booking("e1", "p1", "A-1");
		UnitKey unlisted = UnitKey.booking("e1", "p1", "A-2");
		UnitKey ahead = UnitKey.booking("e1", "p1", "T-1");
		Version vBehind = version(OTHER, START.toEpochMilli(), 9001, "liste-2", null);
		Version vAhead = version(OTHER, START.toEpochMilli(), 9002, "liste-3", null);
		publish(sharedStore, 2, behind, vBehind);
		publish(sharedStore, 3, ahead, vAhead);
		Version vUnlisted = seedForeign(sharedStore, unlisted, 9003);
		tickUntilRunEnds(a);

		assertEquals(vBehind.id(), a.service.unitState(behind).head().id(), "die Liste holt die Buchung vor dem Zeiger");
		assertEquals(vAhead.id(), a.service.unitState(ahead).head().id());
		assertTrue(a.events.lost.isEmpty());
		assertFalse(Files.exists(localFile(a, unlisted, vUnlisted.id() + ".json")), "ohne Liste: erst der nächste Lauf");

		a.service.syncNow();
		tickUntilRunEnds(a);
		assertEquals(vUnlisted.id(), a.service.unitState(unlisted).head().id());
		assertTrue(a.events.lost.isEmpty());
	}

	@Test
	void progressRisesToOneAndBecomesNull() throws IOException {
		Node a = slowNodeWithHalls(300);
		List<Double> seen = new ArrayList<>();
		a.service.fullSyncProgress().addListener((property, before, after) -> seen.add(after));
		assertNull(a.service.fullSyncProgress().get());

		tickUntilRunEnds(a);

		assertNull(seen.get(seen.size() - 1));
		assertEquals(1.0, seen.get(seen.size() - 2));
		List<Double> values = seen.subList(0, seen.size() - 1);
		assertTrue(values.size() > 2, "Zwischenstände: " + seen);
		for (int i = 1; i < values.size(); i++) {
			assertTrue(values.get(i) >= values.get(i - 1), "steigt: " + seen);
		}
		assertTrue(values.get(0) >= 0.0);
	}

	@Test
	void failedBeginAfterSetAsideDoesNotSetASaleAside() throws IOException {
		Node b = node("b");
		b.service.startup(shared);
		save(b, create(UnitKey.event("gemeinsam"), "B"));
		b.service.tick();
		Node earlier = node("a");
		earlier.service.startup("");
		save(earlier, create(UnitKey.event("lokal"), "A"));
		Node a = restartSingle(earlier);
		// gleich nach dem Beiseitelegen fällt der gemeinsame Ordner weg: Das Bestimmen der Abschnitte scheitert
		AtomicBoolean dropped = new AtomicBoolean();
		a.layer.onEach("rename", () -> {
			if (dropped.compareAndSet(false, true)) {
				a.down.set(true);
			}
		});

		a.service.connect(shared, ConnectMode.SET_ASIDE);
		assertTrue(dropped.get());
		assertEquals(SyncState.OFFLINE, a.service.syncState().get());
		assertNull(a.service.fullSyncProgress().get(), "kein Lauf begonnen");
		// ein Verkauf vor der Rückkehr legt data/ neu an
		Version sale = save(a, create(BOOKING, "Anna"));
		a.down.set(false);
		a.service.tick();

		List<String> aside = names(a.appData).stream().filter(n -> n.startsWith("data-beiseite-")).toList();
		assertEquals(1, aside.size(), "nur einmal beiseitegelegt");
		assertTrue(Files.isDirectory(a.appData.resolve(aside.get(0)).resolve("events/lokal")));
		assertEquals(sale.id(), a.service.unitState(BOOKING).head().id());
		assertEquals(sale.id(), sharedClaim(BOOKING, "root").version());
		assertFalse(Files.exists(sharedRoot.resolve("data/events/lokal")), "der beiseitegelegte Bestand wird nicht mitgenommen");
	}

	@Test
	void saleDuringARunIsUploadedWithinFiveSeconds() throws IOException {
		// je Block 50 × 38 ms = 1,9 s: Ein Stück des Laufs braucht zwei Blöcke, also 3,8 s
		Path appData = dir.resolve("a");
		seedForeignHalls(appData, 300);
		Node a = new Node(appData, "a", new NioFileLayer(), Runnable::run, Runnable::run);
		a.service.startup(shared);
		slowReads(a, Duration.ofMillis(38));
		clock.advance(SyncService.FULL_SYNC_INTERVAL);
		a.service.tick();
		assertNotNull(a.service.fullSyncProgress().get());
		// der ungünstigste Zeitpunkt: gleich nach dem Hochladen des Takts
		Instant saved = clock.instant();
		save(a, create(BOOKING, "Anna"));
		Path claim = sharedFile(BOOKING, "claims/root.json");
		AtomicReference<Instant> uploaded = new AtomicReference<>();
		a.layer.onEach("read", () -> {
			if (uploaded.get() == null && Files.exists(claim)) {
				uploaded.set(clock.instant());
			}
		});

		for (int i = 0; i < 5 && uploaded.get() == null; i++) {
			clock.advance(SyncService.TICK);
			a.service.tick();
			if (uploaded.get() == null && Files.exists(claim)) {
				uploaded.set(clock.instant());
			}
		}

		assertNotNull(uploaded.get());
		Duration took = Duration.between(saved, uploaded.get());
		assertTrue(took.compareTo(Duration.ofSeconds(5)) <= 0, "hochgeladen nach " + took);
		assertNotNull(a.service.fullSyncProgress().get(), "der Lauf läuft noch");
	}

	@Test
	void ownPendingVersionCopiedInByTheRunIsUploadedAtItsEnd() throws IOException {
		Node earlier = node("a");
		earlier.service.startup("");
		save(earlier, create(UnitKey.event("lokal"), "A"));
		Node a = restartSingle(earlier);
		// ein früheres Hochladen brach zwischen Versionsdatei und Anspruch ab: Die eigene Version liegt nur im gemeinsamen Ordner
		Version own = version(a.service.machineId(), START.toEpochMilli() - 60_000L, 0, "abgebrochen", null);
		assertTrue(new VersionStore(sharedRoot.resolve("data"), new NioFileLayer()).writeVersion(BOOKING, own.id(),
				StoreCodec.write(own)));

		// connect führt den Takt sofort aus; ohne Kosten erledigt er den ganzen Lauf
		a.service.connect(shared, ConnectMode.SET_ASIDE);

		assertNull(a.service.fullSyncProgress().get(), "der Lauf ist zu Ende");
		assertEquals(own.id(), sharedClaim(BOOKING, "root").version(), "im Takt, in dem der Lauf endet");
		assertFalse(a.service.unitState(BOOKING).provisional());
	}

	// --- Der gemeinsame Ordner verschwindet (KARV-101)

	/** Ein Rechner, dessen Erreichbarkeit wie in der Anwendung am Ordner selbst hängt. */
	private Node folderNode(String name) throws IOException {
		return new Node(dir.resolve(name), name, new NioFileLayer(), Runnable::run, Runnable::run, Files::isDirectory);
	}

	private static void deleteTree(Path folder) throws IOException {
		try (Stream<Path> entries = Files.walk(folder)) {
			for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) {
				Files.delete(entry);
			}
		}
	}

	@Test
	void vanishedSharedFolderIsNotRecreatedOnUpload() throws IOException {
		Node a = folderNode("a");
		assertTrue(a.service.startup(shared));
		deleteTree(sharedRoot);

		save(a, create(BOOKING, "Anna"));
		a.service.tick();

		assertFalse(Files.exists(sharedRoot), "der gemeinsame Ordner wird nie angelegt");
		assertEquals(SyncState.OFFLINE, a.service.syncState().get());
		assertEquals(List.of(shared), a.events.unreachable);
	}

	@Test
	void vanishedSharedFolderIsNotRecreatedByFullSync() throws IOException {
		Node a = folderNode("a");
		assertTrue(a.service.startup(shared));
		deleteTree(sharedRoot);

		a.service.syncNow();
		a.service.tick();

		assertFalse(Files.exists(sharedRoot), "der gemeinsame Ordner wird nie angelegt");
		assertEquals(SyncState.OFFLINE, a.service.syncState().get());
		assertEquals(List.of(shared), a.events.unreachable);
	}

	@Test
	void vanishedSharedFolderIsDetectedWithoutOwnChanges() throws IOException {
		Node a = folderNode("a");
		assertTrue(a.service.startup(shared));
		a.service.tick();
		assertEquals(SyncState.AKTUELL, a.service.syncState().get());
		deleteTree(sharedRoot);

		a.service.tick();

		assertEquals(SyncState.OFFLINE, a.service.syncState().get());
		assertEquals(List.of(shared), a.events.unreachable);
		assertFalse(Files.exists(sharedRoot));
	}

	@Test
	void restoredSharedFolderUploadsLikeAReturn() throws IOException {
		Node a = folderNode("a");
		assertTrue(a.service.startup(shared));
		deleteTree(sharedRoot);
		Version v = save(a, create(BOOKING, "Anna"));
		a.service.tick();
		assertEquals(SyncState.OFFLINE, a.service.syncState().get());
		assertTrue(a.service.unitState(BOOKING).provisional());

		Files.createDirectories(sharedRoot);
		a.service.tick();

		assertEquals(SyncState.AKTUELL, a.service.syncState().get());
		assertEquals(1, a.events.reconnected);
		assertEquals(List.of(shared), a.events.unreachable);
		assertFalse(a.service.unitState(BOOKING).provisional());
		assertEquals(v.id(), sharedClaim(BOOKING, "root").version());
		assertTrue(Files.exists(sharedFile(BOOKING, v.id() + ".json")));
	}

	@Test
	void missingDataBelowAnExistingFolderIsCreated() throws IOException {
		Node a = folderNode("a");
		assertTrue(a.service.startup(shared));
		save(a, create(BOOKING, "Anna"));
		a.service.tick();
		assertTrue(Files.isDirectory(sharedRoot.resolve("data")));
		deleteTree(sharedRoot.resolve("data"));

		Version v = save(a, create(UnitKey.booking("e1", "p1", "A-2"), "Berta"));
		a.service.tick();

		assertTrue(Files.isDirectory(sharedRoot.resolve("data")), "nur der Anker bleibt unangetastet, data/ wird angelegt");
		assertEquals(SyncState.AKTUELL, a.service.syncState().get());
		assertEquals(v.id(), sharedClaim(UnitKey.booking("e1", "p1", "A-2"), "root").version());
		assertTrue(a.events.unreachable.isEmpty());
	}

	// --- Verbinden und Trennen

	@Test
	void connectSetAsideStartsEmpty() throws IOException {
		UnitKey mine = UnitKey.event("lokal");
		UnitKey theirs = UnitKey.event("gemeinsam");
		Node b = node("b");
		b.service.startup(shared);
		Version vb = save(b, create(theirs, "B"));
		b.service.tick();
		Node earlier = node("a");
		earlier.service.startup("");
		save(earlier, create(mine, "A"));
		Node a = restartSingle(earlier);
		assertEquals(new ConnectOutcome(ConnectOutcome.Kind.ENTSCHEIDUNG, true, null, null),
				a.service.connect(shared, ConnectMode.ASK).getNow(null));

		a.service.decideConnect(ConnectMode.SET_ASIDE);

		assertEquals(List.of("gemeinsam"), a.service.eventIds());
		assertEquals(vb.id(), a.service.unitState(theirs).head().id());
		List<String> aside = names(a.appData).stream().filter(n -> n.startsWith("data-beiseite-")).toList();
		assertEquals(1, aside.size());
		assertTrue(Files.isDirectory(a.appData.resolve(aside.get(0)).resolve("events/lokal")));
		assertFalse(Files.exists(sharedRoot.resolve("data/events/lokal")));
		assertEquals(shared, a.service.sharedDir());
		assertEquals(SyncState.AKTUELL, a.service.syncState().get());
	}

	@Test
	void connectMergeKeepsBothSides() throws IOException {
		UnitKey mine = UnitKey.event("lokal");
		UnitKey theirs = UnitKey.event("gemeinsam");
		Node b = node("b");
		b.service.startup(shared);
		Version vb = save(b, create(theirs, "B"));
		b.service.tick();
		Node a = node("a");
		a.service.startup("");
		Version va = save(a, create(mine, "A"));
		// seit dem Start gespeichert: nur Mitnehmen ist erlaubt
		assertEquals(new ConnectOutcome(ConnectOutcome.Kind.ENTSCHEIDUNG, false, null, null),
				a.service.connect(shared, ConnectMode.ASK).getNow(null));

		a.service.decideConnect(ConnectMode.MERGE);

		assertEquals(List.of("gemeinsam", "lokal"), a.service.eventIds());
		UnitState own = a.service.unitState(mine);
		assertEquals(va.id(), own.head().id());
		assertFalse(own.provisional());
		assertEquals(vb.id(), a.service.unitState(theirs).head().id());
		assertEquals(va.id(), sharedClaim(mine, "root").version());
		assertEquals(SyncState.AKTUELL, a.service.syncState().get());
	}

	@Test
	void connectWithOneEmptySideNeedsNoDecision() throws IOException {
		Node a = node("a");
		a.service.startup("");
		Version va = save(a, create(BOOKING, "Anna"));

		assertEquals(ConnectOutcome.Kind.AKTUELL, a.service.connect(shared, ConnectMode.ASK).getNow(null).kind());

		// eine leere Seite wird ohne Rückfrage vereinigt: nichts beiseitegelegt
		assertTrue(names(a.appData).stream().noneMatch(n -> n.startsWith("data-beiseite-")));
		assertEquals(va.id(), sharedClaim(BOOKING, "root").version());
	}

	@Test
	void disconnectSwitchesToSingleMode() throws IOException {
		Node a = node("a");
		a.service.startup(shared);
		Version v1 = save(a, create(BOOKING, "Anna"));
		a.service.tick();
		List<String> before = tree(a.localData());

		a.service.disconnect();

		assertEquals(SyncState.AUS, a.service.syncState().get());
		assertNull(a.service.sharedDir());
		assertEquals(before, tree(a.localData()));
		Version v2 = save(a, update(BOOKING, v1, "Anna B"));
		UnitState state = a.service.unitState(BOOKING);
		assertEquals(v2.id(), state.head().id());
		assertFalse(state.provisional());
		a.service.tick();
		assertFalse(Files.exists(sharedFile(BOOKING, v2.id() + ".json")));
		assertEquals(SyncState.AUS, a.service.syncState().get());
	}

	@Test
	void disconnectClaimsPendingVersionsLocally() throws IOException {
		Node a = node("a");
		a.service.startup(shared);
		Version v = save(a, create(BOOKING, "Anna"));
		assertTrue(a.service.unitState(BOOKING).provisional());

		a.service.disconnect();

		UnitState state = a.service.unitState(BOOKING);
		assertEquals(v.id(), state.head().id());
		assertFalse(state.provisional());
		assertFalse(Files.exists(sharedFile(BOOKING, "claims/root.json")));
	}

	@Test
	void singleModeStartupClaimsVersionsLeftByACrash() throws IOException {
		Path appData = dir.resolve("a");
		MachineIdentity identity = MachineIdentity.loadOrCreate(appData, () -> "a");
		// Absturz zwischen dem Schreiben der Version und ihrem lokalen Anspruch
		Version v = version(identity.id(), 1_000_000L, 0, "abgestuerzt", null);
		writeLocal(appData, BOOKING, v);
		Node a = new Node(appData, "a", new NioFileLayer(), Runnable::run, Runnable::run);

		assertTrue(a.service.startup(""));

		UnitState state = a.service.unitState(BOOKING);
		assertEquals(v.id(), state.head().id());
		assertFalse(state.provisional());
	}

	// --- Abfragen

	@Test
	void listingsAndHistoryComeFromTheLocalStore() throws IOException {
		Node a = node("a");
		a.service.startup("");
		Version v1 = save(a, create(BOOKING, "Anna"));
		Version v2 = save(a, update(BOOKING, v1, "Anna B"));
		save(a, create(UnitKey.hall("h1"), "Halle"));

		assertEquals(List.of("e1"), a.service.eventIds());
		assertEquals(List.of("h1"), a.service.hallIds());
		assertEquals(List.of(UnitKey.event("e1"), UnitKey.presentation("e1", "p1"), BOOKING), a.service.eventUnits("e1"));
		assertEquals(List.of(v1.id(), v2.id()), a.service.history(BOOKING).stream().map(Version::id).toList());
	}

	@Test
	void versionAndMachineIdComeFromTheLocalStore() throws IOException {
		Node a = node("a");
		a.service.startup("");
		Version v1 = save(a, create(BOOKING, "Anna"));
		Version v2 = save(a, update(BOOKING, v1, "Anna B"));

		Version found = a.service.version(BOOKING, v2.id());
		assertEquals(v2.id(), found.id());
		assertEquals(List.of(v1.id()), found.parents());
		assertEquals(payload("Anna B"), found.payload());
		assertNull(a.service.version(BOOKING, VersionId.create(1L, "zzzzzzzz", 0)), "lokal nicht vorhanden");
		assertEquals(v1.machine(), a.service.machineId());
	}

	// --- Start-Index

	private static final UnitKey HALL = UnitKey.hall("h1");

	/** Der Stand der Einheiten, wie ihn {@link ChainResolver} aus den Dateien des Datenordners ermittelt. */
	private static Map<UnitKey, UnitSnapshot> snapshotsFromFiles(Path appData, String machineId) throws IOException {
		VersionStore store = new VersionStore(appData.resolve("data"), new NioFileLayer());
		Map<UnitKey, UnitSnapshot> expected = new java.util.HashMap<>();
		for (UnitKey unit : store.allUnits()) {
			expected.put(unit, UnitSnapshot.of(ChainResolver.resolve(store.readUnit(unit), new Ownership(machineId, List.of()))));
		}
		return expected;
	}

	@Test
	void startupBuildsSnapshotsOfAllUnits() throws IOException {
		Path appData = dir.resolve("a");
		SyncService first = TestSyncServices.singleMode(appData);
		first.startup(null, p -> {
		});
		Version v1 = first.append("s1", List.of(create(BOOKING, "Anna"))).get(0);
		Version v2 = first.append("s2", List.of(update(BOOKING, v1, "Anna B"))).get(0);
		Version vh = first.append("s3", List.of(create(HALL, "Halle"))).get(0);

		SyncService second = TestSyncServices.singleMode(appData);
		assertTrue(second.startup(null, p -> {
		}));

		Map<UnitKey, UnitSnapshot> snapshots = second.snapshots();
		// dazu die Ordner von Event und Vorstellung, die ohne eigene Dateien keinen Stand haben
		assertEquals(Set.of(UnitKey.event("e1"), UnitKey.presentation("e1", "p1"), BOOKING, HALL), snapshots.keySet());
		assertNull(snapshots.get(UnitKey.event("e1")).version());
		assertEquals(v2.id(), snapshots.get(BOOKING).version());
		assertEquals(payload("Anna B"), snapshots.get(BOOKING).payload());
		assertEquals(Status.OK, snapshots.get(BOOKING).status());
		assertEquals(vh.id(), snapshots.get(HALL).version());
		assertEquals(payload("Halle"), snapshots.get(HALL).payload());
		assertThrows(UnsupportedOperationException.class, () -> snapshots.remove(HALL));
	}

	@Test
	void startupClaimsPendingFromIndexCandidates() throws IOException {
		Node a = node("a");
		a.service.startup("");
		save(a, create(HALL, "Halle"));
		// Absturz zwischen dem Schreiben der Version und ihrem lokalen Anspruch
		a.layer.failWhen(p -> p.startsWith(a.localData()) && p.getParent().endsWith("claims"), "createNew",
				new IOException("Absturz"));
		assertThrows(IOException.class, () -> a.service.append("s2", List.of(create(BOOKING, "Anna"))));
		VersionId pending = a.service.history(BOOKING).get(0).id();
		a.service.flushIndex();

		Node b = new Node(a.appData, "a", new NioFileLayer(), Runnable::run, Runnable::run);
		assertTrue(b.service.startup(""));

		assertEquals(pending, b.service.unitState(BOOKING).head().id());
		assertEquals(pending, b.service.snapshots().get(BOOKING).version());
		assertEquals(Status.OK, b.service.snapshots().get(BOOKING).status());
		// die unveränderte Einheit wurde nicht angefasst: weder gelesen noch aufgelöst
		String hallFolder = b.localData().resolve(HALL.relativePath()).toString();
		assertTrue(b.layer.calls().stream().noneMatch(c -> c.startsWith("read ") && c.contains(hallFolder)));
	}

	@Test
	void appendUpdatesSnapshots() throws IOException {
		SyncService service = TestSyncServices.singleMode(dir.resolve("a"));
		service.startup(null, p -> {
		});

		Version v1 = service.append("s1", List.of(create(BOOKING, "Anna"))).get(0);
		assertEquals(v1.id(), service.snapshots().get(BOOKING).version());
		assertEquals(payload("Anna"), service.snapshots().get(BOOKING).payload());

		Version v2 = service.append("s2", List.of(update(BOOKING, v1, "Anna B"))).get(0);
		assertEquals(v2.id(), service.snapshots().get(BOOKING).version());
		assertEquals(payload("Anna B"), service.snapshots().get(BOOKING).payload());
	}

	@Test
	void appendInSharedModeUpdatesSnapshotsWithPendingVersion() throws IOException {
		Node a = node("a");
		a.service.startup(shared);

		Version v = save(a, create(BOOKING, "Anna"));

		assertEquals(v.id(), a.service.snapshots().get(BOOKING).version());
	}

	@Test
	void startupAfterLostFlushMatchesFiles() throws IOException {
		Path appData = dir.resolve("a");
		SyncService first = TestSyncServices.singleMode(appData);
		first.startup(null, p -> {
		});
		Version v1 = first.append("s1", List.of(create(BOOKING, "Anna"))).get(0);
		first.flushIndex();
		// diese Änderung erreicht den Index auf der Platte nie
		first.append("s2", List.of(update(BOOKING, v1, "Anna B")));
		first.append("s3", List.of(create(HALL, "Halle")));

		SyncService second = TestSyncServices.singleMode(appData);
		second.startup(null, p -> {
		});

		MachineIdentity identity = MachineIdentity.loadOrCreate(appData, () -> "test");
		assertEquals(snapshotsFromFiles(appData, identity.id()), second.snapshots());
		assertEquals(payload("Anna B"), second.snapshots().get(BOOKING).payload());
	}

	@Test
	void startupReadsUnitsOnlyOnceWithIndex() throws IOException {
		Path appData = dir.resolve("a");
		SyncService first = TestSyncServices.singleMode(appData);
		first.startup(null, p -> {
		});
		for (int i = 0; i < 20; i++) {
			first.append("s" + i, List.of(create(UnitKey.booking("e1", "p1", "A-" + i), "Gast " + i)));
		}
		first.flushIndex();

		Node b = new Node(appData, "a", new NioFileLayer(), Runnable::run, Runnable::run);
		assertTrue(b.service.startup(""));

		String data = b.localData().toString();
		assertTrue(b.layer.calls().stream().noneMatch(c -> c.startsWith("read ") && c.contains(data)));
		assertTrue(b.layer.calls().stream().anyMatch(c -> c.startsWith("read ") && c.contains("index")));
		for (int i = 0; i < 20; i++) {
			assertEquals("Gast " + i,
					b.service.snapshots().get(UnitKey.booking("e1", "p1", "A-" + i)).payload().get("name").asText());
		}
	}

	@Test
	void chainsComeFromIndexWithoutReadingVersionFiles() throws IOException {
		Path appData = dir.resolve("a");
		SyncService first = TestSyncServices.singleMode(appData);
		first.startup(null, p -> {
		});
		Version v1 = first.append("s1", List.of(create(BOOKING, "Anna"))).get(0);
		Version v2 = first.append("s2", List.of(update(BOOKING, v1, "Anna B"))).get(0);
		first.append("s3", List.of(create(HALL, "Halle")));
		first.flushIndex();

		Node b = new Node(appData, "a", new NioFileLayer(), Runnable::run, Runnable::run);
		assertTrue(b.service.startup(""));
		int callsBefore = b.layer.calls().size();

		Map<UnitKey, List<ChainStep>> chains = b.service.chains(List.of(BOOKING, HALL));

		assertEquals(Set.of(BOOKING, HALL), chains.keySet());
		assertEquals(List.of(v1.id(), v2.id()), chains.get(BOOKING).stream().map(ChainStep::version).toList());
		assertEquals(List.of("s1", "s2"), chains.get(BOOKING).stream().map(ChainStep::saveId).toList());
		assertEquals(v2.at(), chains.get(BOOKING).get(1).at());
		assertEquals(v2.machineName(), chains.get(BOOKING).get(1).machineName());
		assertEquals(1, chains.get(HALL).size());
		assertEquals(List.of(), b.layer.calls().subList(callsBefore, b.layer.calls().size()));
	}

	@Test
	void chainsOfUnitWithoutEntryAreResolvedFromFiles() throws IOException {
		Path appData = dir.resolve("a");
		Node a = new Node(appData, "a", new NioFileLayer(), Runnable::run, Runnable::run);
		a.service.startup("");
		Version known = save(a, create(BOOKING, "Anna"));
		// eine Einheit, die der Index nicht kennt: Datei und Anspruch von außen abgelegt
		UnitKey stranger = UnitKey.booking("e1", "p1", "Z-9");
		Version foreign = version("zzzzzzzz", 1_900_000_000_000L, 1, "fremd", null);
		VersionStore store = new VersionStore(a.localData(), new NioFileLayer());
		byte[] bytes = StoreCodec.write(foreign);
		assertTrue(store.writeVersion(stranger, foreign.id(), bytes));
		new LocalArbiter(store, clock).claim(stranger, foreign, bytes);
		assertFalse(a.service.snapshots().containsKey(stranger));

		Map<UnitKey, List<ChainStep>> chains = a.service.chains(List.of(BOOKING, stranger, UnitKey.hall("leer")));

		assertEquals(List.of(known.id()), chains.get(BOOKING).stream().map(ChainStep::version).toList());
		assertEquals(List.of(foreign.id()), chains.get(stranger).stream().map(ChainStep::version).toList());
		assertEquals(List.of(), chains.get(UnitKey.hall("leer")));
		// die Auflösung ging in den Index
		assertTrue(a.service.snapshots().containsKey(stranger));
		assertFalse(a.service.snapshots().containsKey(UnitKey.hall("leer")));
	}

	@Test
	void chainsOfAnUnknownDamagedUnitReportSys008InSingleMode() throws IOException {
		Node a = node("a");
		a.service.startup("");
		// eine Einheit, die der Index nicht kennt: Anspruch von außen abgelegt, die Versionsdatei danach beschädigt
		UnitKey stranger = UnitKey.booking("e1", "p1", "Z-9");
		Version foreign = version("zzzzzzzz", 1_900_000_000_000L, 1, "fremd", null);
		VersionStore store = new VersionStore(a.localData(), new NioFileLayer());
		byte[] bytes = StoreCodec.write(foreign);
		assertTrue(store.writeVersion(stranger, foreign.id(), bytes));
		new LocalArbiter(store, clock).claim(stranger, foreign, bytes);
		Files.writeString(localFile(a, stranger, foreign.id() + ".json"), "kaputt");
		assertFalse(a.service.snapshots().containsKey(stranger));

		a.service.chains(List.of(stranger));
		a.service.chains(List.of(stranger));

		assertEquals(List.of(StoreMessages.damaged(stranger.relativePath())), a.events.errors, "SYS-008 einmal je Einheit");
	}

	@Test
	void chainsOfAnUnknownDamagedUnitQueueARepairInSharedMode() throws IOException {
		Node a = node("a");
		a.service.startup(shared);
		UnitKey stranger = UnitKey.booking("e1", "p1", "Z-9");
		Version foreign = version("zzzzzzzz", 1_900_000_000_000L, 1, "fremd", null);
		VersionStore store = new VersionStore(a.localData(), new NioFileLayer());
		byte[] bytes = StoreCodec.write(foreign);
		assertTrue(store.writeVersion(stranger, foreign.id(), bytes));
		new LocalArbiter(store, clock).claim(stranger, foreign, bytes);
		// die gute Kopie liegt im gemeinsamen Ordner, die lokale Datei ist beschädigt
		Files.createDirectories(sharedFile(stranger, "x").getParent());
		Files.write(sharedFile(stranger, foreign.id() + ".json"), bytes);
		Path localFile = localFile(a, stranger, foreign.id() + ".json");
		Files.writeString(localFile, "kaputt");
		assertFalse(a.service.snapshots().containsKey(stranger));

		a.service.chains(List.of(stranger));
		assertTrue(a.events.errors.isEmpty(), "ob es eine gute Kopie gibt, entscheidet erst der Takt");
		a.service.tick();

		assertTrue(a.events.changedUnits().contains(stranger));
		assertArrayEquals(bytes, Files.readAllBytes(localFile));
		assertEquals(Status.OK, a.service.unitState(stranger).status());
	}

	@Test
	void chainsFollowTheIndexAfterAppend() throws IOException {
		Node a = node("a");
		a.service.startup("");
		Version v1 = save(a, create(BOOKING, "Anna"));
		Version v2 = save(a, update(BOOKING, v1, "Anna B"));

		assertEquals(List.of(v1.id(), v2.id()),
				a.service.chains(List.of(BOOKING)).get(BOOKING).stream().map(ChainStep::version).toList());
	}

	@Test
	void startupReportsProgressUpToOne() throws IOException {
		Path appData = dir.resolve("a");
		SyncService first = TestSyncServices.singleMode(appData);
		first.startup(null);
		first.append("s1", List.of(create(BOOKING, "Anna")));
		List<Double> progress = new ArrayList<>();

		TestSyncServices.singleMode(appData).startup(null, progress::add);

		assertEquals(1.0, progress.get(progress.size() - 1));
	}

	@Test
	void tickFlushesIndexAtMostEveryFiveSeconds() throws IOException {
		Node a = node("a");
		a.service.startup("");
		Version v1 = save(a, create(BOOKING, "Anna"));
		assertEquals(0, indexWrites(a));

		a.service.tick();
		assertEquals(1, indexWrites(a));

		Version v2 = save(a, update(BOOKING, v1, "Anna B"));
		clock.advance(Duration.ofSeconds(2));
		a.service.tick();
		assertEquals(1, indexWrites(a));

		clock.advance(Duration.ofSeconds(3));
		a.service.tick();
		assertEquals(2, indexWrites(a));

		// nichts geändert: nichts zu schreiben
		clock.advance(Duration.ofSeconds(5));
		a.service.tick();
		assertEquals(2, indexWrites(a));

		Node b = new Node(a.appData, "a", new NioFileLayer(), Runnable::run, Runnable::run);
		b.service.startup("");
		assertEquals(v2.id(), b.service.snapshots().get(BOOKING).version());
	}

	private static long indexWrites(Node node) {
		String index = node.appData.resolve("index").toString();
		return node.layer.calls().stream().filter(c -> c.startsWith("createNew ") && c.contains(index)).count();
	}

	@Test
	void flushIndexWritesNothingBeforeStartup() throws IOException {
		Path appData = dir.resolve("a");
		SyncService service = TestSyncServices.singleMode(appData);
		service.append("s1", List.of(create(BOOKING, "Anna")));

		service.flushIndex();

		assertFalse(Files.exists(appData.resolve("index")));

		service.startup(null);
		service.flushIndex();

		assertTrue(Files.exists(appData.resolve("index/events/e1.json")));
	}

	@Test
	void flushIndexDoesNotThrowWhenWritingFails() throws IOException {
		Node a = node("a");
		a.service.startup("");
		save(a, create(BOOKING, "Anna"));
		a.layer.failWhen(p -> p.startsWith(a.appData.resolve("index")), "createNew", new IOException("Platte voll"));

		a.service.flushIndex();
		a.service.tick();

		assertFalse(Files.exists(a.appData.resolve("index/events/e1.json")));
	}

	@Test
	void setAsideClearsIndex() throws IOException {
		UnitKey mine = UnitKey.event("lokal");
		UnitKey theirs = UnitKey.event("gemeinsam");
		Node b = node("b");
		b.service.startup(shared);
		save(b, create(theirs, "B"));
		b.service.tick();
		Node earlier = node("a");
		earlier.service.startup("");
		save(earlier, create(mine, "A"));
		Node a = restartSingle(earlier);
		a.service.flushIndex();
		assertEquals(Set.of(mine), a.service.snapshots().keySet());

		a.service.connect(shared, ConnectMode.SET_ASIDE);

		assertEquals(Set.of(theirs), a.service.snapshots().keySet());
		a.service.flushIndex();
		assertEquals(0, MAPPER.readTree(Files.readAllBytes(a.appData.resolve("index/events/lokal.json"))).get("units").size());
		Node again = new Node(a.appData, "a", new NioFileLayer(), Runnable::run, Runnable::run);
		assertTrue(again.service.startup(shared));
		assertEquals(Set.of(theirs), again.service.snapshots().keySet());
		assertEquals(snapshotsFromFiles(a.appData, a.identity.id()), again.service.snapshots());
	}

	// --- Marke "sauber beendet"

	private static Path cleanMarker(Node node) {
		return node.appData.resolve("index/clean");
	}

	private static boolean walkedTree(Node node) {
		return node.layer.calls().stream().anyMatch(c -> c.startsWith("listTree ") || c.startsWith("deleteOwnTemps "));
	}

	/** Ein Bestand mit vollständigem Index, sauber beendet: eine Buchung, eine Halle samt den Ordnern von Event und Vorstellung. */
	private Node cleanlyClosedNode() throws IOException {
		Node a = node("a");
		a.service.startup("");
		save(a, create(BOOKING, "Anna"));
		save(a, create(HALL, "Halle"));
		a.service.flushIndex();
		// der Abgleich nimmt auch die Ordner ohne eigene Dateien auf
		Node full = new Node(a.appData, "a", new NioFileLayer(), Runnable::run, Runnable::run);
		full.service.startup("");
		full.service.closeIndex();
		assertTrue(Files.isRegularFile(cleanMarker(a)));
		return a;
	}

	@Test
	void closeIndexWritesNoMarkerWhileASaveIsWriting() throws Exception {
		Node a = node("a");
		a.service.startup("");
		save(a, create(HALL, "Halle"));
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		a.layer.blockNext("createNew", entered, release);
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread saver = new Thread(() -> {
			try {
				save(a, create(BOOKING, "Anna"));
			} catch (Throwable e) {
				failure.set(e);
			}
		});
		saver.start();
		try {
			assertTrue(entered.await(10, TimeUnit.SECONDS), "der Speichervorgang schreibt");

			a.service.closeIndex();

			// die Marke stünde sonst über einer Version, die der Index noch nicht kennt
			assertFalse(Files.exists(cleanMarker(a)));
		} finally {
			release.countDown();
			saver.join(10_000);
		}
		assertNull(failure.get());
		a.service.closeIndex();
		assertTrue(Files.isRegularFile(cleanMarker(a)), "ohne laufenden Speichervorgang wie gewohnt");
	}

	@Test
	void startupAfterCleanShutdownSkipsTreeWalks() throws IOException {
		Node a = cleanlyClosedNode();

		Node b = new Node(a.appData, "a", new NioFileLayer(), Runnable::run, Runnable::run);
		assertTrue(b.service.startup(""));

		assertFalse(walkedTree(b), b.layer.calls().toString());
		assertTrue(b.service.isStarted());
		assertEquals(snapshotsFromFiles(a.appData, a.identity.id()), b.service.snapshots());
		assertEquals(payload("Anna"), b.service.snapshots().get(BOOKING).payload());
		assertFalse(Files.exists(cleanMarker(a)));
		// die Einheiten selbst wurden nicht gelesen, nur der Index
		String data = b.localData().toString();
		assertTrue(b.layer.calls().stream().noneMatch(c -> c.startsWith("read ") && c.contains(data)));
	}

	@Test
	void startupAfterCleanShutdownReportsProgressUpToOne() throws IOException {
		Node a = cleanlyClosedNode();
		List<Double> progress = new ArrayList<>();

		new Node(a.appData, "a", new NioFileLayer(), Runnable::run, Runnable::run).service.startup("", progress::add);

		assertEquals(1.0, progress.get(progress.size() - 1));
	}

	@Test
	void startupAfterCrashWalksAgain() throws IOException {
		Node a = cleanlyClosedNode();
		Node b = new Node(a.appData, "a", new NioFileLayer(), Runnable::run, Runnable::run);
		b.service.startup("");
		assertFalse(walkedTree(b));
		Version v1 = b.service.history(BOOKING).get(0);
		Version v2 = save(b, update(BOOKING, v1, "Anna B"));
		// kein closeIndex: Absturz

		Node c = new Node(a.appData, "a", new NioFileLayer(), Runnable::run, Runnable::run);
		c.service.startup("");

		assertTrue(c.layer.calls().stream().anyMatch(call -> call.startsWith("listTree ")), c.layer.calls().toString());
		assertEquals(snapshotsFromFiles(a.appData, a.identity.id()), c.service.snapshots());
		assertEquals(v2.id(), c.service.snapshots().get(BOOKING).version());
	}

	@Test
	void startupAfterACrashWalksTheTreeOnce() throws IOException {
		Node a = cleanlyClosedNode();
		Node b = new Node(a.appData, "a", new NioFileLayer(), Runnable::run, Runnable::run);
		b.service.startup("");
		save(b, update(BOOKING, b.service.history(BOOKING).get(0), "Anna B"));
		// kein closeIndex: Absturz, dazu alte und junge Reste im lokalen Bestand
		Path unitDir = localFile(b, BOOKING, "x").getParent();
		Path old = tempFile(unitDir, null, SyncService.TEMP_AGE.plusHours(1));
		Path young = tempFile(unitDir, null, SyncService.TEMP_AGE.minusHours(1));

		Node c = new Node(a.appData, "a", new NioFileLayer(), Runnable::run, Runnable::run);
		c.service.startup("");

		List<String> calls = c.layer.calls();
		assertEquals(1, calls.stream().filter(call -> call.startsWith("listTree ")).count(), calls.toString());
		assertTrue(calls.stream().noneMatch(call -> call.startsWith("deleteOwnTemps ")), calls.toString());
		assertEquals(List.of("deleteOldTemps " + unitDir), calls.stream().filter(call -> call.startsWith("deleteOldTemps")).toList());
		assertFalse(Files.exists(old));
		assertTrue(Files.exists(young));
	}

	@Test
	void markerIsGoneAfterAChangeSoACrashWalksAgain() throws IOException {
		Node a = cleanlyClosedNode();
		Node b = new Node(a.appData, "a", new NioFileLayer(), Runnable::run, Runnable::run);
		b.service.startup("");
		b.service.closeIndex();
		assertTrue(Files.isRegularFile(cleanMarker(a)));

		// nach dem Schließen ändert sich noch etwas (etwa ein später Takt): die Marke gilt nicht mehr
		save(b, create(UnitKey.booking("e1", "p1", "A-2"), "Berta"));

		assertFalse(Files.exists(cleanMarker(a)));
		Node c = new Node(a.appData, "a", new NioFileLayer(), Runnable::run, Runnable::run);
		c.service.startup("");
		assertTrue(c.layer.calls().stream().anyMatch(call -> call.startsWith("listTree ")));
		assertEquals(snapshotsFromFiles(a.appData, a.identity.id()), c.service.snapshots());
	}

	@Test
	void closeIndexWritesNoMarkerWhenFlushFails() throws IOException {
		Node a = node("a");
		a.service.startup("");
		save(a, create(BOOKING, "Anna"));
		a.layer.failWhen(p -> p.startsWith(a.appData.resolve("index")), "createNew", new IOException("Platte voll"));

		a.service.closeIndex();

		assertFalse(Files.exists(cleanMarker(a)));
		Node b = new Node(a.appData, "a", new NioFileLayer(), Runnable::run, Runnable::run);
		b.service.startup("");
		assertTrue(b.layer.calls().stream().anyMatch(call -> call.startsWith("listTree ")));
		assertEquals(payload("Anna"), b.service.snapshots().get(BOOKING).payload());
	}

	@Test
	void closeIndexWritesNothingBeforeStartup() throws IOException {
		Node a = node("a");

		a.service.closeIndex();

		assertFalse(Files.exists(a.appData.resolve("index")));
	}

	@Test
	void closeIndexNeverThrowsWhenWritingTheMarkerFails() throws IOException {
		Node a = node("a");
		a.service.startup("");
		save(a, create(BOOKING, "Anna"));
		// an der Stelle der Marke liegt ein nicht leerer Ordner
		Files.createDirectories(a.appData.resolve("index/clean/x"));

		a.service.closeIndex();

		assertTrue(Files.isDirectory(a.appData.resolve("index/clean")));
		assertTrue(Files.isRegularFile(a.appData.resolve("index/events/e1.json")));
	}

	@Test
	void pendingFromCleanIndexIsClaimed() throws IOException {
		Node a = node("a");
		a.service.startup("");
		save(a, create(HALL, "Halle"));
		// Absturz zwischen dem Schreiben der Version und ihrem lokalen Anspruch
		a.layer.failWhen(p -> p.startsWith(a.localData()) && p.getParent().endsWith("claims"), "createNew",
				new IOException("Absturz"));
		assertThrows(IOException.class, () -> a.service.append("s2", List.of(create(BOOKING, "Anna"))));
		VersionId pending = a.service.history(BOOKING).get(0).id();
		a.service.closeIndex();
		assertTrue(Files.isRegularFile(cleanMarker(a)));

		Node b = new Node(a.appData, "a", new NioFileLayer(), Runnable::run, Runnable::run);
		assertTrue(b.service.startup(""));

		assertFalse(walkedTree(b), b.layer.calls().toString());
		assertEquals(pending, b.service.unitState(BOOKING).head().id());
		assertEquals(pending, b.service.snapshots().get(BOOKING).version());
		assertEquals(Status.OK, b.service.snapshots().get(BOOKING).status());
		assertTrue(Files.isRegularFile(localFile(b, BOOKING, "claims/root.json")));
	}

	@Test
	void pendingFromCleanIndexIsUploadedInSharedMode() throws IOException {
		Node a = node("a");
		a.service.startup(shared);
		a.down.set(true);
		Version v = save(a, create(BOOKING, "Anna"));
		a.service.tick();
		a.service.closeIndex();
		assertTrue(Files.isRegularFile(cleanMarker(a)));
		a.down.set(false);

		Node b = new Node(a.appData, "a", new NioFileLayer(), Runnable::run, Runnable::run);
		assertTrue(b.service.startup(shared));

		assertFalse(walkedTree(b), b.layer.calls().toString());
		assertEquals(v.id(), sharedClaim(BOOKING, "root").version());
	}

	@Test
	void setAsideDeletesCleanMarker() throws IOException {
		UnitKey mine = UnitKey.event("lokal");
		UnitKey theirs = UnitKey.event("gemeinsam");
		Node b = node("b");
		b.service.startup(shared);
		save(b, create(theirs, "B"));
		b.service.tick();
		Node earlier = node("a");
		earlier.service.startup("");
		save(earlier, create(mine, "A"));
		Node a = restartSingle(earlier);
		// die Marke liegt auf der Platte, ohne dass der Dienst sie angelegt hat: nur das Beiseitelegen kann sie löschen
		Files.createDirectories(cleanMarker(a).getParent());
		Files.writeString(cleanMarker(a), a.identity.id());

		a.service.connect(shared, ConnectMode.SET_ASIDE);

		assertFalse(Files.exists(cleanMarker(a)));
		assertEquals(Set.of(theirs), a.service.snapshots().keySet());
	}

	@Test
	void duplicateMachineIdDeletesCleanMarker() throws IOException {
		Node a = node("a");
		a.service.startup(shared);
		Version v1 = save(a, create(BOOKING, "Anna"));
		a.service.tick();
		String oldId = a.identity.id();
		Feed copy = new Feed(StoreCodec.FORMAT, oldId, 2, START, List.of());
		Files.write(sharedRoot.resolve("data/feeds/" + oldId + "/2.json"), StoreCodec.write(copy));
		save(a, update(BOOKING, v1, "Anna B"));
		// die Marke liegt auf der Platte, ohne dass der Dienst sie angelegt hat: nur die neue Kennung kann sie löschen
		Files.writeString(cleanMarker(a), oldId);

		a.service.tick();

		assertEquals(1, a.events.duplicates.size());
		assertFalse(Files.exists(cleanMarker(a)));
	}

	@Test
	void tickAfterCloseIndexDoesNothing() throws IOException {
		Node a = node("a");
		a.service.startup(shared);
		Node b = node("b");
		b.service.startup(shared);
		Version v = save(b, create(BOOKING, "Anna"));
		b.service.tick();
		a.service.closeIndex();
		assertTrue(Files.isRegularFile(cleanMarker(a)));
		// lange genug später, dass der Takt neue Rechner sucht und ihre Listen liest
		clock.advance(Duration.ofSeconds(31));

		a.service.tick();

		assertFalse(Files.exists(localFile(a, BOOKING, v.id() + ".json")), "nach closeIndex wird nichts mehr kopiert");
		assertTrue(Files.isRegularFile(cleanMarker(a)));
	}

	@Test
	void appendAfterCloseIndexRemovesMarkerBeforeWriting() throws IOException {
		Node a = node("a");
		a.service.startup("");
		Version v1 = save(a, create(BOOKING, "Anna"));
		a.service.closeIndex();
		assertTrue(Files.isRegularFile(cleanMarker(a)));
		List<Boolean> markerAtWrite = new ArrayList<>();
		a.layer.failWhen(p -> {
			if (p.startsWith(a.localData())) {
				markerAtWrite.add(Files.exists(cleanMarker(a)));
			}
			return false;
		}, "createNew", new IOException("nie"));

		save(a, update(BOOKING, v1, "Anna B"));

		assertFalse(markerAtWrite.isEmpty());
		assertEquals(Set.of(false), Set.copyOf(markerAtWrite), "die Marke muss vor dem ersten Schreiben im Bestand weg sein");
		Node b = new Node(a.appData, "a", new NioFileLayer(), Runnable::run, Runnable::run);
		b.service.startup("");
		assertTrue(b.layer.calls().stream().anyMatch(call -> call.startsWith("listTree ")));
	}

	/** Die Marke "sauber beendet" lässt sich weder löschen noch leeren: Der Index löscht nicht, die Datei ist schreibgeschützt. */
	private static void lockCleanMarker(Node node) {
		node.service.index().useDeleter(file -> {
			throw new IOException("Datei gesperrt");
		});
		assumeTrue(cleanMarker(node).toFile().setWritable(false), "Schreibschutz lässt sich auf diesem System nicht setzen");
		assumeFalse(Files.isWritable(cleanMarker(node)), "Schreibschutz greift auf diesem System nicht");
	}

	/** Hebt {@link #lockCleanMarker} auf. */
	private static void unlockCleanMarker(Node node) {
		cleanMarker(node).toFile().setWritable(true);
		node.service.index().useDeleter(Files::deleteIfExists);
	}

	/** Der Text der Störung, solange die Marke "sauber beendet" stehen bleibt. */
	private static String cleanMarkerStuck(Node node) {
		return "Die Marke 'sauber beendet' ließ sich weder löschen noch leeren: " + cleanMarker(node);
	}

	@Test
	void rebuiltIndexStillWritesNothingOverAStuckCleanMarker() throws IOException {
		Node b = node("b");
		b.service.startup(shared);
		Node earlier = node("a");
		earlier.service.startup(shared);
		earlier.service.tick();
		earlier.service.closeIndex();
		Version v = save(b, create(BOOKING, "Anna"));
		b.service.tick();
		// Neustart: Die Marke bleibt stehen, und der Abgleich des Index scheitert, also baut der Start ihn neu auf
		Node a = new Node(earlier.appData, "a", new NioFileLayer(), Runnable::run, Runnable::run);
		lockCleanMarker(a);
		a.layer.failNext("listTree", new IOException("Bestand nicht auflistbar"));
		try {
			assertTrue(a.service.startup(shared));
			// der neue Index löscht wie der alte nicht
			lockCleanMarker(a);
			assumeTrue(Files.isRegularFile(cleanMarker(a)), "der Schreibschutz verhinderte das Löschen nicht");
			clock.advance(Duration.ofSeconds(31));

			a.service.tick();

			assertFalse(Files.exists(localFile(a, BOOKING, v.id() + ".json")), "über einer gültigen Marke wird nichts abgeholt");
			assertEquals(a.identity.id(), Files.readString(cleanMarker(a)));

			unlockCleanMarker(a);
			a.service.tick();

			assertTrue(Files.isRegularFile(localFile(a, BOOKING, v.id() + ".json")));
			assertFalse(Files.exists(cleanMarker(a)));
		} finally {
			unlockCleanMarker(a);
		}
	}

	@Test
	void tickWritesNothingWhileTheCleanMarkerCannotBeInvalidated() throws IOException {
		Node b = node("b");
		b.service.startup(shared);
		Node earlier = node("a");
		earlier.service.startup(shared);
		earlier.service.tick();
		earlier.service.closeIndex();
		assertTrue(Files.isRegularFile(cleanMarker(earlier)));
		Version v = save(b, create(BOOKING, "Anna"));
		b.service.tick();
		// Neustart: Die Marke lässt sich beim Start weder löschen noch leeren und kann gültig bleiben
		Node a = new Node(earlier.appData, "a", new NioFileLayer(), Runnable::run, Runnable::run);
		lockCleanMarker(a);
		try {
			assertTrue(a.service.startup(shared));
			// lange genug später, dass der Takt neue Rechner sucht und ihre Listen liest
			clock.advance(Duration.ofSeconds(31));

			a.service.tick();

			assertFalse(Files.exists(localFile(a, BOOKING, v.id() + ".json")), "über einer gültigen Marke wird nichts abgeholt");
			assertEquals(a.identity.id(), Files.readString(cleanMarker(a)));
			assertEquals(SyncState.FEHLER, a.service.syncState().get());
			assertEquals(List.of(cleanMarkerStuck(a)), a.events.errors, "die lokale Datei, nicht SYS-006");

			// der nächste Takt versucht es erneut und holt ab, sobald die Marke weg ist
			unlockCleanMarker(a);
			a.service.tick();

			assertTrue(Files.isRegularFile(localFile(a, BOOKING, v.id() + ".json")));
			assertFalse(Files.exists(cleanMarker(a)));
			assertEquals(SyncState.AKTUELL, a.service.syncState().get());
		} finally {
			unlockCleanMarker(a);
		}
	}

	@Test
	void connectOutcomeIsFehlerWhileTheCleanMarkerCannotBeInvalidated() throws IOException {
		Node b = node("b");
		b.service.startup(shared);
		Version v = save(b, create(BOOKING, "Anna"));
		b.service.tick();
		Node a = node("a");
		a.service.startup("");
		save(a, create(HALL, "Halle"));
		a.service.closeIndex();
		lockCleanMarker(a);
		try {
			// connect öffnet den Dienst wieder, kann die Marke aber nicht entfernen; der Takt läuft gleich hier
			CompletableFuture<ConnectOutcome> result = a.service.connect(shared, ConnectMode.MERGE);

			assertEquals(new ConnectOutcome(Kind.FEHLER, false, null, cleanMarkerStuck(a)), result.getNow(null),
					"das Ergebnis kommt, auch wenn die Marke bleibt");
			assertFalse(Files.exists(localFile(a, BOOKING, v.id() + ".json")));
			assertFalse(Files.exists(sharedFile(HALL, "")), "nichts hochgeladen");
			assertEquals(a.identity.id(), Files.readString(cleanMarker(a)));
			assertFalse(a.service.connectionComplete().get());

			// der Lauf versucht es weiter, wie nach jeder Störung
			unlockCleanMarker(a);
			a.service.tick();

			assertTrue(Files.isRegularFile(localFile(a, BOOKING, v.id() + ".json")));
			assertTrue(Files.isDirectory(sharedFile(HALL, "")), "mitgenommen");
			assertTrue(a.service.connectionComplete().get());
			assertEquals(Kind.FEHLER, result.getNow(null).kind(), "ein Ergebnis, genau einmal");
		} finally {
			unlockCleanMarker(a);
		}
	}

	@Test
	void singleModeTickResolvesNothingWhileTheCleanMarkerCannotBeInvalidated() throws IOException {
		Node earlier = node("a");
		earlier.service.startup("");
		save(earlier, create(BOOKING, "Anna"));
		earlier.service.closeIndex();
		Node a = new Node(earlier.appData, "a", new NioFileLayer(), Runnable::run, Runnable::run);
		lockCleanMarker(a);
		try {
			assertTrue(a.service.startup(""));
			a.service.resolver().enqueue(BOOKING);
			String unit = localFile(a, BOOKING, "").toString();
			int before = a.layer.calls().size();

			a.service.tick();

			assertTrue(a.layer.calls().subList(before, a.layer.calls().size()).stream().noneMatch(c -> c.contains(unit)),
					"die Auflösung wartet, bis die Marke weg ist");
			unlockCleanMarker(a);
			a.service.tick();
			assertTrue(a.layer.calls().subList(before, a.layer.calls().size()).stream().anyMatch(c -> c.contains(unit)));
			assertFalse(Files.exists(cleanMarker(a)));
		} finally {
			unlockCleanMarker(a);
		}
	}

	/**
	 * Hält einen Thread an, nachdem er die Anspruchsdatei {@code root.json} einer Einheit gelesen hat: Er hat dann die
	 * Versionen und den Anspruch gesehen, aber noch nicht in den Index eingetragen.
	 */
	private static final class GateLayer implements FileLayer {
		private final FileLayer delegate = new NioFileLayer();
		final CountDownLatch entered = new CountDownLatch(1);
		final CountDownLatch release = new CountDownLatch(1);
		private volatile Thread gated;
		private volatile Path unitDir;

		void arm(Thread thread, Path unit) {
			this.unitDir = unit;
			this.gated = thread;
		}

		@Override
		public Optional<byte[]> read(Path file) throws IOException {
			Optional<byte[]> result = delegate.read(file);
			if (Thread.currentThread() == gated && file.startsWith(unitDir) && file.getFileName().toString().equals("root.json")) {
				gated = null;
				entered.countDown();
				try {
					release.await(10, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new IOException(e);
				}
			}
			return result;
		}

		@Override
		public boolean createNew(Path file, byte[] content, Path anchor, String tempTag) throws IOException {
			return delegate.createNew(file, content, anchor, tempTag);
		}

		@Override
		public List<String> listNamesWithTemps(Path dir) throws IOException {
			return delegate.listNamesWithTemps(dir);
		}

		@Override
		public void deleteOldTemps(Path dir, Collection<String> names, Duration olderThan) throws IOException {
			delegate.deleteOldTemps(dir, names, olderThan);
		}

		@Override
		public boolean exists(Path file) {
			return delegate.exists(file);
		}

		@Override
		public List<String> listNames(Path dir) throws IOException {
			return delegate.listNames(dir);
		}

		@Override
		public Map<String, List<String>> listTree(Path root) throws IOException {
			return delegate.listTree(root);
		}

		@Override
		public boolean isDirectory(Path path) {
			return delegate.isDirectory(path);
		}

		@Override
		public void replaceDamaged(Path file, byte[] good, String corruptName, String tempTag) throws IOException {
			delegate.replaceDamaged(file, good, corruptName, tempTag);
		}

		@Override
		public void rename(Path from, Path to) throws IOException {
			delegate.rename(from, to);
		}

		@Override
		public void deleteOwnTemps(Path dir, Duration olderThan) throws IOException {
			delegate.deleteOwnTemps(dir, olderThan);
		}
	}

	@Test
	void olderResolutionNeverOverwritesNewerIndexEntry() throws Exception {
		Path appData = dir.resolve("a");
		GateLayer gate = new GateLayer();
		MachineIdentity identity = MachineIdentity.loadOrCreate(appData, () -> "a");
		SyncService service = new SyncService(appData, identity, gate, Runnable::run, Runnable::run, p -> true, clock);
		service.startup("");
		Version v1 = service.append("s1", List.of(create(BOOKING, "Anna"))).get(0);
		// der "Worker" löst die Einheit auf (Stand: v1) und bleibt stehen, bevor er den Stand einträgt
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread worker = new Thread(() -> {
			try {
				service.unitState(BOOKING);
			} catch (IOException | RuntimeException e) {
				failure.set(e);
			}
		});
		gate.arm(worker, appData.resolve("data").resolve(BOOKING.relativePath()));
		worker.start();
		assertTrue(gate.entered.await(10, TimeUnit.SECONDS));
		// die Oberfläche speichert v2 und trägt sie ein; ohne Serialisierung wäre sie sofort fertig
		AtomicReference<Version> saved = new AtomicReference<>();
		Thread ui = new Thread(() -> {
			try {
				saved.set(service.append("s2", List.of(update(BOOKING, v1, "Anna B"))).get(0));
			} catch (IOException | RuntimeException e) {
				failure.set(e);
			}
		});
		ui.start();
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (ui.isAlive() && ui.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
			Thread.sleep(5);
		}
		gate.release.countDown();
		worker.join(10_000);
		ui.join(10_000);

		assertNull(failure.get());
		assertEquals(saved.get().id(), service.snapshots().get(BOOKING).version());
		assertEquals(payload("Anna B"), service.snapshots().get(BOOKING).payload());
	}

	// --- Verbinden und Trennen warten auf den laufenden Takt (M1)

	/**
	 * Wartet, bis der Thread in der Warteschlange der Arbeitssperre des Dienstes steht; scheitert, wenn er vorher fertig ist
	 * (er hat dann nicht gewartet) oder an etwas anderem wartet.
	 */
	private static void assertWaitingForTheWorkLock(SyncService service, Thread thread) throws Exception {
		Field field = SyncService.class.getDeclaredField("workLock");
		field.setAccessible(true);
		ReentrantLock workLock = (ReentrantLock) field.get(service);
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (thread.isAlive() && !workLock.hasQueuedThread(thread) && System.nanoTime() < deadline) {
			Thread.sleep(5);
		}
		assertTrue(thread.isAlive(), "der Aufruf kehrte zurück, obwohl der Takt noch lief");
		assertTrue(workLock.hasQueuedThread(thread), "der Aufruf wartet nicht auf die Arbeitssperre");
		assertTrue(workLock.isFair());
	}

	@Test
	void disconnectWaitsForTheRunningUploadOfTheOldConnection() throws Exception {
		Node b = node("b");
		b.service.startup(shared);
		Node a = node("a");
		a.service.startup(shared);
		// der erste Takt macht den Vollabgleich; der nächste lädt nur noch hoch
		a.service.tick();
		Version other = save(b, create(BOOKING, "B"));
		b.service.tick();
		Version mine = save(a, create(BOOKING, "A"));
		// der Takt schreibt den Index vor der Arbeitssperre; hier ist das schon geschehen, die Uhr steht still
		a.service.flushIndex();
		// der Takt von a steht mitten im Hochladen: Die Versionsdatei ist unterwegs, der Anspruch noch nicht gesetzt
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		a.layer.blockNext("createNew", entered, release);
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread tick = new Thread(() -> {
			try {
				a.service.tick();
			} catch (RuntimeException e) {
				failure.set(e);
			}
		});
		tick.start();
		assertTrue(entered.await(10, TimeUnit.SECONDS));

		Thread disconnect = new Thread(() -> {
			try {
				a.service.disconnect();
			} catch (RuntimeException e) {
				failure.set(e);
			}
		});
		disconnect.start();
		assertWaitingForTheWorkLock(a.service, disconnect);
		release.countDown();
		tick.join(10_000);
		disconnect.join(10_000);

		assertNull(failure.get());
		// b war schneller: Die Version von a ist verloren, und der Einzelbetrieb beansprucht sie nicht gegen den gemeinsamen Anspruch
		assertEquals(other.id(), sharedClaim(BOOKING, "root").version());
		Path localClaim = localFile(a, BOOKING, "claims/root.json");
		if (Files.exists(localClaim)) {
			assertEquals(other.id(), StoreCodec.readClaim(Files.readAllBytes(localClaim)).version());
		}
		assertEquals(List.of(new LostVersion(BOOKING, List.of(mine.id()), other.id())), a.events.lost);
	}

	@Test
	void appendRejectedRightAfterDisconnectIsClaimedLocally() throws Exception {
		Node a = node("a");
		a.service.startup(shared);
		// append hat die Verbindung gelesen und schreibt gerade die Versionsdatei, als getrennt wird
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		a.layer.blockNext("createNew", entered, release);
		AtomicReference<Version> saved = new AtomicReference<>();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread ui = new Thread(() -> {
			try {
				saved.set(a.service.append("s1", List.of(create(BOOKING, "Anna"))).get(0));
			} catch (IOException | RuntimeException e) {
				failure.set(e);
			}
		});
		ui.start();
		assertTrue(entered.await(10, TimeUnit.SECONDS));
		a.service.disconnect();
		release.countDown();
		ui.join(10_000);

		assertNull(failure.get());
		UnitState state = a.service.unitState(BOOKING);
		assertEquals(saved.get().id(), state.head().id());
		assertFalse(state.provisional(), "ohne Verbindung beansprucht der lokale Bestand die Version");
		assertTrue(Files.exists(localFile(a, BOOKING, "claims/root.json")));
	}

	@Test
	void appendRejectedByANewConnectionStaysPendingAndIsUploadedByTheFirstRun() throws Exception {
		// der Hintergrund-Thread läuft nicht von selbst: Der Test führt die Takte aus, die connect einreiht
		List<Runnable> queued = new ArrayList<>();
		Node a = new Node(dir.resolve("a"), "a", new NioFileLayer(), Runnable::run, queued::add);
		a.service.startup(shared);
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		a.layer.blockNext("createNew", entered, release);
		AtomicReference<Version> saved = new AtomicReference<>();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread ui = new Thread(() -> {
			try {
				saved.set(a.service.append("s1", List.of(create(BOOKING, "Anna"))).get(0));
			} catch (IOException | RuntimeException e) {
				failure.set(e);
			}
		});
		ui.start();
		assertTrue(entered.await(10, TimeUnit.SECONDS));
		queued.clear();
		a.service.connect(shared, ConnectMode.MERGE);
		release.countDown();
		ui.join(10_000);

		assertNull(failure.get());
		UnitState before = a.service.unitState(BOOKING);
		assertTrue(before.provisional(), "mit der neuen Verbindung bleibt die Version ausstehend");
		assertFalse(Files.exists(localFile(a, BOOKING, "claims/root.json")), "kein lokaler Anspruch");

		assertFalse(queued.isEmpty());
		for (Runnable tick : List.copyOf(queued)) {
			tick.run();
		}
		assertEquals(saved.get().id(), sharedClaim(BOOKING, "root").version());
		assertFalse(a.service.unitState(BOOKING).provisional());
	}

	@Test
	void appendRejectedAfterTheFirstRunBeganIsUploadedByTheNextTick() throws Exception {
		List<Runnable> queued = new ArrayList<>();
		Node a = new Node(dir.resolve("a"), "a", new NioFileLayer(), Runnable::run, queued::add);
		a.service.startup(shared);
		// append hat die alte Verbindung gelesen und steht im lokalen Schreiben
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		a.layer.blockNext("createNew", entered, release);
		AtomicReference<Version> saved = new AtomicReference<>();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread ui = new Thread(() -> {
			try {
				saved.set(a.service.append("s1", List.of(create(BOOKING, "Anna"))).get(0));
			} catch (IOException | RuntimeException e) {
				failure.set(e);
			}
		});
		ui.start();
		assertTrue(entered.await(10, TimeUnit.SECONDS));
		queued.clear();
		a.service.connect(shared, ConnectMode.MERGE);
		// der erste Takt der neuen Verbindung beginnt und beendet den Vollabgleich, solange append noch schreibt: Der Index
		// kennt die Version noch nicht
		for (Runnable tick : List.copyOf(queued)) {
			tick.run();
		}
		assertFalse(Files.exists(sharedFile(BOOKING, "claims/root.json")));
		release.countDown();
		ui.join(10_000);
		assertNull(failure.get());

		// der nächste gewöhnliche Takt lädt hoch, nicht erst ein späterer Vollabgleich
		a.service.tick();

		assertEquals(saved.get().id(), sharedClaim(BOOKING, "root").version());
		assertFalse(a.service.unitState(BOOKING).provisional());
	}

	@Test
	void startupTakesTheWorkLock() throws Exception {
		Node a = node("a");
		a.service.startup(shared);
		a.service.tick();
		Version mine = save(a, create(BOOKING, "A"));
		// der Takt schreibt den Index vor der Arbeitssperre; hier ist das schon geschehen, die Uhr steht still
		a.service.flushIndex();
		// der Takt von a steht mitten im Hochladen
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		a.layer.blockNext("createNew", entered, release);
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread tick = new Thread(() -> {
			try {
				a.service.tick();
			} catch (RuntimeException e) {
				failure.set(e);
			}
		});
		tick.start();
		assertTrue(entered.await(10, TimeUnit.SECONDS));
		// der angehaltene Aufruf ist noch nicht aufgezeichnet: Jeder neue Eintrag stammt vom Start
		int before = a.layer.calls().size();

		AtomicBoolean started = new AtomicBoolean();
		Thread startup = new Thread(() -> {
			try {
				started.set(a.service.startup(shared));
			} catch (RuntimeException e) {
				failure.set(e);
			}
		});
		startup.start();
		assertWaitingForTheWorkLock(a.service, startup);
		assertEquals(before, a.layer.calls().size(), "der Start arbeitet nicht neben dem Takt: " + a.layer.calls());
		release.countDown();
		tick.join(10_000);
		startup.join(10_000);

		assertNull(failure.get());
		assertTrue(started.get());
		assertEquals(mine.id(), sharedClaim(BOOKING, "root").version());
	}

	/** Ein Absturz zwischen Schreiben und lokalem Anspruch: eine Halle ist fertig, eine Buchung hat eine Version ohne Anspruch. */
	private VersionId crashWithPendingBooking(Path appData) throws IOException {
		Node a = new Node(appData, "a", new NioFileLayer(), Runnable::run, Runnable::run);
		a.service.startup("");
		save(a, create(HALL, "Halle"));
		a.layer.failWhen(p -> p.startsWith(a.localData()) && p.getParent().endsWith("claims"), "createNew",
				new IOException("Absturz"));
		assertThrows(IOException.class, () -> a.service.append("s2", List.of(create(BOOKING, "Anna"))));
		return a.service.history(BOOKING).get(0).id();
	}

	@Test
	void startupFallsBackWhenRefreshFails() throws IOException {
		Path appData = dir.resolve("a");
		VersionId pending = crashWithPendingBooking(appData);
		Node b = new Node(appData, "a", new NioFileLayer(), Runnable::run, Runnable::run);
		b.layer.failNext("listTree", new IOException("Störung"));
		List<Double> progress = new ArrayList<>();

		assertTrue(b.service.startup("", progress::add));

		Map<UnitKey, UnitSnapshot> snapshots = b.service.snapshots();
		assertEquals(pending, snapshots.get(BOOKING).version());
		assertEquals(Status.OK, snapshots.get(BOOKING).status());
		assertEquals(payload("Halle"), snapshots.get(HALL).payload());
		assertEquals(pending, b.service.unitState(BOOKING).head().id());
		assertEquals(1.0, progress.get(progress.size() - 1));
	}

	@Test
	void rebuildReportsProgressPerUnit() throws IOException {
		Path appData = dir.resolve("a");
		crashWithPendingBooking(appData);
		Node b = new Node(appData, "a", new NioFileLayer(), Runnable::run, Runnable::run);
		b.layer.failNext("listTree", new IOException("Störung"));
		List<Double> progress = new ArrayList<>();

		b.service.startup("", progress::add);

		assertTrue(progress.stream().anyMatch(fraction -> fraction > 0 && fraction < 1), "Zwischenstand fehlt: " + progress);
		assertEquals(1.0, progress.get(progress.size() - 1));
	}

	@Test
	void startupFallsBackWhenAUnitReadFailsOnce() throws IOException {
		Path appData = dir.resolve("a");
		VersionId pending = crashWithPendingBooking(appData);
		Node b = new Node(appData, "a", new NioFileLayer(), Runnable::run, Runnable::run);
		AtomicBoolean armed = new AtomicBoolean(true);
		b.layer.failWhen(p -> p.startsWith(b.localData()) && armed.compareAndSet(true, false), "read",
				new IOException("Störung"));

		assertTrue(b.service.startup(""));

		assertEquals(pending, b.service.snapshots().get(BOOKING).version());
		assertEquals(payload("Halle"), b.service.snapshots().get(HALL).payload());
		assertEquals(pending, b.service.unitState(BOOKING).head().id());
	}

	@Test
	void startupThrowsWhenAUnitStaysUnreadable() throws IOException {
		Path appData = dir.resolve("a");
		crashWithPendingBooking(appData);
		Node b = new Node(appData, "a", new NioFileLayer(), Runnable::run, Runnable::run);
		Path unreadable = b.localData().resolve(BOOKING.relativePath());
		b.layer.failWhen(p -> p.startsWith(unreadable), "read", new IOException("Datei gesperrt"));

		UncheckedIOException e = assertThrows(UncheckedIOException.class, () -> b.service.startup(""));

		assertTrue(e.getMessage().contains(BOOKING.relativePath()), e.getMessage());
		assertEquals("Datei gesperrt", e.getCause().getMessage());
	}

	@Test
	void startupThrowsWhenUnitsCannotBeListed() throws IOException {
		Path appData = dir.resolve("a");
		crashWithPendingBooking(appData);
		Node b = new Node(appData, "a", new NioFileLayer(), Runnable::run, Runnable::run);
		b.layer.failWhen(p -> true, "listTree", new IOException("Störung"));
		b.layer.failWhen(p -> p.startsWith(b.localData()), "listNames", new IOException("Störung"));

		assertThrows(UncheckedIOException.class, () -> b.service.startup(""));
	}

	@Test
	void changedUnitsAreInTheIndexWhenReported() throws IOException {
		Node a = node("a");
		a.service.startup(shared);
		save(a, create(HALL, "Halle"));
		a.service.tick();
		Node b = node("b");
		b.service.startup(shared);
		b.events.changed.clear();
		Version va = save(a, create(BOOKING, "Anna"));
		a.service.tick();

		b.service.tick();

		assertEquals(Set.of(BOOKING), b.events.changedUnits());
		assertEquals(va.id(), b.service.snapshots().get(BOOKING).version());
		assertFalse(b.service.snapshots().get(BOOKING).deleted());
	}

	@Test
	void snapshotsOfUnitsReturnsOnlyRequestedUnits() throws IOException {
		Node a = node("a");
		a.service.startup("");
		UnitKey other = UnitKey.booking("e1", "p1", "A-2");
		Version v = save(a, create(BOOKING, "Anna"));
		save(a, create(other, "Bert"));

		Map<UnitKey, UnitSnapshot> snapshots = a.service.snapshots(List.of(BOOKING));

		assertEquals(Set.of(BOOKING), snapshots.keySet());
		assertEquals(v.id(), snapshots.get(BOOKING).version());
		assertEquals(payload("Anna"), snapshots.get(BOOKING).payload());
		assertThrows(UnsupportedOperationException.class, () -> snapshots.clear());
	}

	@Test
	void snapshotsOfUnknownUnitIsAbsent() throws IOException {
		Node a = node("a");
		a.service.startup("");
		save(a, create(BOOKING, "Anna"));

		Map<UnitKey, UnitSnapshot> snapshots = a.service.snapshots(List.of(UnitKey.booking("e1", "p1", "Z-9")));

		assertTrue(snapshots.isEmpty());
	}

	@Test
	void eventSnapshotsReturnsAllUnitsOfTheEvent() throws IOException {
		Node a = node("a");
		a.service.startup("");
		UnitKey head = UnitKey.event("e1");
		UnitKey presentation = UnitKey.presentation("e1", "p1");
		UnitKey second = UnitKey.booking("e1", "p1", "A-2");
		save(a, create(head, "Fasching"));
		save(a, create(presentation, "Sitzung"));
		save(a, create(BOOKING, "Anna"));
		save(a, create(second, "Bert"));
		save(a, create(UnitKey.booking("e2", "p1", "A-1"), "Clara"));
		save(a, create(UnitKey.hall("h1"), "Halle"));

		Map<UnitKey, UnitSnapshot> snapshots = a.service.eventSnapshots("e1");

		assertEquals(Set.of(head, presentation, BOOKING, second), snapshots.keySet());
		assertTrue(a.service.eventSnapshots("e3").isEmpty());
		assertThrows(UnsupportedOperationException.class, () -> snapshots.clear());
	}

	// --- Index Format 3: seen beim Löschen, Listennummern, über Liste eingelesen, aufgegangene Versionen

	private static final UnitKey PRESENTATION = UnitKey.presentation("e1", "p1");

	/** Schreibt den Index des Rechners und liefert den Eintrag der Einheit aus seiner Datei. */
	private static com.fasterxml.jackson.databind.JsonNode indexEntry(Node node, UnitKey unit) throws IOException {
		node.service.flushIndex();
		Path file = node.appData.resolve("index").resolve(unit.type() == UnitType.HALL ? "halls.json"
				: "events/" + unit.eventId() + ".json");
		for (com.fasterxml.jackson.databind.JsonNode item : MAPPER.readTree(Files.readAllBytes(file)).get("units")) {
			if (item.get("unit").asText().equals(unit.relativePath())) {
				return item;
			}
		}
		throw new AssertionError("kein Eintrag für " + unit.relativePath());
	}

	private static Change delete(UnitKey unit, Version parent) {
		return new Change(unit, List.of(parent.id()), null, true);
	}

	@Test
	void deletionOfPresentationRecordsSeenPositions() throws IOException {
		VersionStore sharedStore = new VersionStore(sharedRoot.resolve("data"), new NioFileLayer());
		Node b = node("b");
		b.service.startup(shared);
		for (int seq = 1; seq <= 3; seq++) {
			publish(sharedStore, seq, UnitKey.booking("e1", "p1", "A-" + seq),
					version(OTHER, 1_000_000L * seq, seq, "fremd-" + seq, null));
		}
		clock.advance(Duration.ofSeconds(30));
		b.service.tick();
		Version presentation = save(b, create(PRESENTATION, "Sitzung"));

		Version deletion = save(b, delete(PRESENTATION, presentation));

		assertTrue(deletion.deleted());
		assertEquals(Map.of(OTHER, 3L), deletion.seen());
		assertEquals(Map.of(OTHER, 3L),
				StoreCodec.readVersion(Files.readAllBytes(localFile(b, PRESENTATION, deletion.id() + ".json"))).seen());
		assertEquals(Map.of(), presentation.seen());
	}

	@Test
	void seenIsOnlyWrittenForDeletionsOfEventsAndPresentations() throws IOException {
		VersionStore sharedStore = new VersionStore(sharedRoot.resolve("data"), new NioFileLayer());
		Node b = node("b");
		b.service.startup(shared);
		publish(sharedStore, 1, BOOKING, version(OTHER, 1_000_000L, 1, "fremd", null));
		clock.advance(Duration.ofSeconds(30));
		b.service.tick();
		UnitKey event = UnitKey.event("e1");
		Version head = save(b, create(event, "Fasching"));
		Version booking = save(b, create(UnitKey.booking("e1", "p1", "B-1"), "Bert"));

		Version update = save(b, update(event, head, "Fasching 2"));
		Version bookingDeletion = save(b, delete(UnitKey.booking("e1", "p1", "B-1"), booking));
		Version eventDeletion = save(b, delete(event, update));

		assertEquals(Map.of(), update.seen());
		assertEquals(Map.of(), bookingDeletion.seen());
		assertEquals(Map.of(OTHER, 1L), eventDeletion.seen());
	}

	@Test
	void deletionInSingleModeHasNoSeen() throws IOException {
		Node a = node("a");
		assertTrue(a.service.startup(""));
		Version presentation = save(a, create(PRESENTATION, "Sitzung"));

		Version deletion = save(a, delete(PRESENTATION, presentation));

		assertTrue(deletion.deleted());
		assertEquals(Map.of(), deletion.seen());
	}

	@Test
	void ownVersionGetsItsListNumberAfterUpload() throws IOException {
		Node a = node("a");
		assertTrue(a.service.startup(shared));
		Version first = save(a, create(BOOKING, "Anna"));

		assertEquals(0L, indexEntry(a, BOOKING).get("ownSeq").asLong(), "noch in keiner Liste");
		clock.advance(Duration.ofSeconds(2));
		a.service.tick();

		assertEquals(1L, indexEntry(a, BOOKING).get("ownSeq").asLong());
		save(a, update(BOOKING, first, "Anna 2"));
		assertEquals(0L, indexEntry(a, BOOKING).get("ownSeq").asLong());
		clock.advance(Duration.ofSeconds(2));
		a.service.tick();

		assertEquals(2L, indexEntry(a, BOOKING).get("ownSeq").asLong());
		assertEquals(List.of("1.json", "2.json"), names(sharedRoot.resolve("data/feeds/" + a.identity.id())));
	}

	@Test
	void ownVersionInSingleModeIsInNoList() throws IOException {
		Node a = node("a");
		assertTrue(a.service.startup(""));

		save(a, create(BOOKING, "Anna"));

		assertEquals(0L, indexEntry(a, BOOKING).get("ownSeq").asLong());
	}

	@Test
	void foreignVersionIsMarkedListedAfterItsListIsProcessed() throws IOException {
		VersionStore sharedStore = new VersionStore(sharedRoot.resolve("data"), new NioFileLayer());
		Node a = node("a");
		a.service.startup(shared);
		Version p1 = version(OTHER, 1_000_000L, 0, "fremd-1", null);
		publish(sharedStore, 1, BOOKING, p1);
		// die zweite Liste nennt eine Version, deren Datei noch fehlt: die Liste ist nicht vollständig verarbeitet
		Version p2 = version(OTHER, 2_000_000L, 1, "fremd-2", p1.id());
		Feed second = new Feed(StoreCodec.FORMAT, OTHER, 2, START,
				List.of(new FeedEntry(BOOKING.relativePath(), p2.id(), p2.claimName())));
		assertTrue(sharedStore.createRelative("feeds/" + OTHER + "/2.json", StoreCodec.write(second)));
		clock.advance(Duration.ofSeconds(30));

		a.service.tick();

		com.fasterxml.jackson.databind.JsonNode listed = indexEntry(a, BOOKING).get("listed");
		assertEquals(1, listed.size());
		assertEquals(p1.id().value(), listed.get(0).asText());
		assertEquals(p1.id(), a.service.unitState(BOOKING).head().id());
	}

	@Test
	void absorbedVersionsAreAnsweredFromTheIndex() throws IOException {
		Path appData = dir.resolve("a");
		MachineIdentity identity = MachineIdentity.loadOrCreate(appData, () -> "a");
		Version w = version(OTHER, 1_000_000L, 0, "fremd-w", null);
		Version l = version(OTHER, 2_000_000L, 1, "fremd-l", w.id());
		Version m = new Version(StoreCodec.FORMAT, VersionId.create(3_000_000L, identity.id(), 0), List.of(w.id(), l.id()),
				identity.id(), "a", null, START, "eigen", false, payload("merge"));
		writeLocal(appData, BOOKING, w);
		writeLocal(appData, BOOKING, l);
		writeLocal(appData, BOOKING, m);
		VersionStore local = new VersionStore(appData.resolve("data"), new NioFileLayer());
		assertTrue(new LocalArbiter(local, clock).claim(BOOKING, w, StoreCodec.write(w)).won());
		Node a = node("a");
		assertTrue(a.service.startup(""));

		Map<UnitKey, Set<VersionId>> absorbed = a.service.absorbed(List.of(BOOKING, UnitKey.hall("h1")));

		assertEquals(Map.of(BOOKING, Set.of(l.id())), absorbed);
	}

	@Test
	void zeroListNumberWrittenBeforeTheUploadIsNotTrustedAfterACrash() throws IOException {
		Node a = node("a");
		assertTrue(a.service.startup(shared));
		save(a, create(BOOKING, "Anna"));
		// der Index mit "in keiner Liste" kommt auf die Platte, dann wird hochgeladen (Liste 1, lokaler Anspruch) ...
		assertEquals(0L, indexEntry(a, BOOKING).get("ownSeq").asLong());
		clock.advance(Duration.ofSeconds(2));
		a.service.tick();
		assertEquals(List.of("1.json"), names(sharedRoot.resolve("data/feeds/" + a.identity.id())));

		// ... und der Prozess endet, ohne den Index noch einmal zu schreiben: unsauberer Start
		Node restarted = node("a");
		assertTrue(restarted.service.startup(shared));

		com.fasterxml.jackson.databind.JsonNode own = indexEntry(restarted, BOOKING).get("ownSeq");
		assertTrue(own == null || own.isNull() || own.asLong() != 0, "nicht 'in keiner Liste': " + own);
	}

	@Test
	void explicitSeenOfAChangeIsWrittenVerbatimForDeletionsOfEventsAndPresentations() throws IOException {
		Node b = node("b");
		b.service.startup(shared);
		Version presentation = save(b, create(PRESENTATION, "Sitzung"));
		Version booking = save(b, create(BOOKING, "Anna"));
		Map<String, Long> explicit = Map.of(OTHER, 42L);

		Version update = save(b, new Change(PRESENTATION, List.of(presentation.id()), payload("Sitzung 2"), false, null, explicit));
		Version bookingDeletion = save(b, new Change(BOOKING, List.of(booking.id()), null, true, null, explicit));
		Version deletion = save(b, new Change(PRESENTATION, List.of(update.id()), null, true, null, explicit));

		assertEquals(Map.of(), update.seen());
		assertEquals(Map.of(), bookingDeletion.seen());
		assertEquals(explicit, deletion.seen());
		assertEquals(explicit,
				StoreCodec.readVersion(Files.readAllBytes(localFile(b, PRESENTATION, deletion.id() + ".json"))).seen());
	}

	@Test
	void sharedModeServicesSeeEachOthersVersions() {
		SyncService a = TestSyncServices.sharedMode(dir.resolve("a"), sharedRoot, "a", clock);
		SyncService b = TestSyncServices.sharedMode(dir.resolve("b"), sharedRoot, "b", clock);
		Version v;
		try {
			v = a.append(UUID.randomUUID().toString(), List.of(create(BOOKING, "Anna"))).get(0);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		TestSyncServices.tick(a);
		// B entdeckt neue Rechner im gemeinsamen Ordner höchstens alle 30 Sekunden
		clock.advance(Duration.ofSeconds(30));
		TestSyncServices.tick(b);

		UnitSnapshot seen = b.snapshots(List.of(BOOKING)).get(BOOKING);

		assertEquals(v.id(), seen.version());
		assertEquals(Status.OK, seen.status());
	}

	@Test
	void relativePathToTheSharedFolderWorks() throws IOException {
		// ein relativer Pfad vom Arbeitsordner aus, mit "." darin
		Files.createDirectories(Path.of("target"));
		Path base = Files.createTempDirectory(Path.of("target"), "relativ-");
		try {
			assertFalse(base.isAbsolute());
			Path relativeShared = Files.createDirectory(base.resolve("gemeinsam"));
			SyncService a = TestSyncServices.sharedMode(dir.resolve("a"), base.resolve(".").resolve("gemeinsam"), "a", clock);
			Version v = a.append(UUID.randomUUID().toString(), List.of(create(BOOKING, "Anna"))).get(0);
			TestSyncServices.tick(a);

			Path claim = relativeShared.resolve("data").resolve(BOOKING.relativePath()).resolve("claims/root.json");
			assertEquals(v.id(), StoreCodec.readClaim(Files.readAllBytes(claim)).version());
			assertEquals(SyncState.AKTUELL, a.syncState().get());
		} finally {
			try (Stream<Path> entries = Files.walk(base)) {
				for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) {
					Files.delete(entry);
				}
			}
		}
	}
}
