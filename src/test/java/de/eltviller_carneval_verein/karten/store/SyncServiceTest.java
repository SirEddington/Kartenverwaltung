package de.eltviller_carneval_verein.karten.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

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
		public boolean createNew(Path file, byte[] content) throws IOException {
			if (trigger != null && trigger.test(file)) {
				IoAction pending = action;
				trigger = null;
				action = null;
				pending.run();
			}
			return delegate.createNew(file, content);
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
		public boolean isDirectory(Path path) {
			return delegate.isDirectory(path);
		}

		@Override
		public void replaceDamaged(Path file, byte[] good, String corruptName) throws IOException {
			delegate.replaceDamaged(file, good, corruptName);
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
			this.appData = appData;
			this.identity = MachineIdentity.loadOrCreate(appData, () -> name);
			this.layer = new FaultyFileLayer(files);
			for (String operation : List.of("createNew", "read", "listNames")) {
				layer.failWhen(p -> down.get() && p.startsWith(sharedRoot), operation, new IOException("Netzlaufwerk getrennt"));
			}
			this.service = new SyncService(appData, identity, layer, ui, worker, p -> !down.get(), clock);
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

	// --- Verluste

	@Test
	void lossDetectedBeforeACrashIsReportedAfterRestart() throws IOException {
		Node a = node("a");
		a.service.startup(shared);
		Version va = save(a, create(BOOKING, "Anna"));
		a.service.tick();
		// B erkennt den Verlust, stürzt aber ab, bevor die Meldung die Oberfläche erreicht
		Path appDataB = dir.resolve("b");
		Node crashed = new Node(appDataB, "b", new NioFileLayer(), task -> {
		}, Runnable::run);
		crashed.service.startup(shared);
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
			Version v = version(me, 1_000_000L, i, "s" + i, null);
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

		// nach Ablauf der Fenster wird jeder noch einmal geprüft, auch hinter dem Rückstand
		clock.advance(Duration.ofMinutes(16));
		UnitKey late = UnitKey.hall("h7");
		Files.createDirectories(sharedFile(late, "claims"));
		Version foreign = overrideSharedClaim(late, "root");
		reads = sharedRootClaimReads(a);
		a.service.tick();
		assertEquals(SyncService.LATE_DEFEAT_BUDGET, sharedRootClaimReads(a) - reads);
		reads = sharedRootClaimReads(a);
		a.service.tick();
		assertEquals(count - SyncService.LATE_DEFEAT_BUDGET, sharedRootClaimReads(a) - reads);

		VersionId mine = VersionId.create(1_000_000L, me, 7);
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

	// --- Verbinden und Trennen

	@Test
	void connectSetAsideStartsEmpty() throws IOException {
		UnitKey mine = UnitKey.event("lokal");
		UnitKey theirs = UnitKey.event("gemeinsam");
		Node b = node("b");
		b.service.startup(shared);
		Version vb = save(b, create(theirs, "B"));
		b.service.tick();
		Node a = node("a");
		a.service.startup("");
		save(a, create(mine, "A"));
		assertTrue(a.service.needsConnectDecision(shared));

		a.service.connect(shared, ConnectMode.SET_ASIDE);

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
		assertTrue(a.service.needsConnectDecision(shared));

		a.service.connect(shared, ConnectMode.MERGE);

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

		assertFalse(a.service.needsConnectDecision(shared));
		a.service.connect(shared, ConnectMode.SET_ASIDE);

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
}
