package de.eltviller_carneval_verein.karten.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
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
import java.util.Set;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Szenarien mit einem nachgebauten Nextcloud-Ordner: Jeder Dienst nimmt seinen eigenen, lokal synchronisierten
 * Client-Ordner als gemeinsamen Ordner, und {@link SimulatedCloud} spielt Sync-Client und Server dazwischen.
 */
class CloudScenarioTest {

	private static final Instant START = Instant.parse("2026-10-26T10:00:00Z");
	private static final UnitKey BOOKING = UnitKey.booking("e1", "p1", "A-1");
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** Eine Uhr, die der Test vorstellt; beide Rechner teilen sie. */
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
		final List<String> errors = new ArrayList<>();

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
		public void error(String message) {
			errors.add(message);
		}

		Set<UnitKey> changedUnits() {
			Set<UnitKey> all = new HashSet<>();
			changed.forEach(all::addAll);
			return all;
		}
	}

	/** Ein simulierter Rechner: eigener Datenordner, eigener Dienst, eigener Client-Ordner als gemeinsamer Ordner. */
	private final class Machine {
		final String name;
		final Path clientFolder;
		final Path appData;
		final Recorder events = new Recorder();
		final SyncService service;

		Machine(String name) throws IOException {
			this.name = name;
			this.clientFolder = cloud.addClient(name);
			this.appData = dir.resolve("appdata-" + name);
			MachineIdentity identity = MachineIdentity.loadOrCreate(appData, () -> name);
			this.service = new SyncService(appData, identity, new NioFileLayer(), Runnable::run, Runnable::run, p -> true,
					clock);
			service.setListener(events);
			assertTrue(service.startup(clientFolder.toString()));
		}

		Version sell(UnitKey unit, String text) throws IOException {
			List<Version> saved = service.append(UUID.randomUUID().toString(), List.of(
					new Change(unit, List.of(), MAPPER.createObjectNode().put("name", text), false)));
			assertEquals(1, saved.size());
			return saved.get(0);
		}

		Path localData() {
			return appData.resolve("data");
		}
	}

	@TempDir
	Path dir;

	private TestClock clock;
	private SimulatedCloud cloud;

	@BeforeEach
	void setUp() {
		clock = new TestClock();
		cloud = new SimulatedCloud(dir.resolve("server"));
	}

	private static List<String> names(Path folder) throws IOException {
		try (Stream<Path> entries = Files.list(folder)) {
			return entries.map(p -> p.getFileName().toString()).sorted().toList();
		}
	}

	private static List<String> tree(Path folder) throws IOException {
		try (Stream<Path> entries = Files.walk(folder)) {
			return entries.filter(Files::isRegularFile).map(p -> folder.relativize(p).toString().replace('\\', '/')).sorted()
					.toList();
		}
	}

	// --- Szenarien

	/**
	 * Beide verkaufen denselben Platz, beide Ansprüche liegen zunächst nur im eigenen Client-Ordner. Der Server nimmt
	 * den ersten; der Sync-Client des zweiten legt seinen Anspruch als Konfliktkopie beiseite und holt den fremden.
	 */
	@Test
	void lateDefeatThroughCloud() throws IOException {
		// die Warnung des Dienstes wird aufgefangen: Sie belegt den Weg der späten Niederlage und hält die Ausgabe sauber
		Logger logger = Logger.getLogger(SyncService.class.getName());
		List<LogRecord> warnings = new ArrayList<>();
		Handler capture = new Handler() {
			@Override
			public void publish(LogRecord record) {
				if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
					warnings.add(record);
				}
			}

			@Override
			public void flush() {
			}

			@Override
			public void close() {
			}
		};
		boolean parentHandlers = logger.getUseParentHandlers();
		logger.addHandler(capture);
		logger.setUseParentHandlers(false);
		try {
			runLateDefeatThroughCloud();
		} finally {
			logger.removeHandler(capture);
			logger.setUseParentHandlers(parentHandlers);
		}

		assertEquals(1, warnings.size(), "genau eine Warnung: " + warnings.stream().map(LogRecord::getMessage).toList());
		assertEquals(Level.WARNING, warnings.get(0).getLevel());
		assertTrue(warnings.get(0).getMessage().contains("Späte Niederlage"), warnings.get(0).getMessage());
	}

	private void runLateDefeatThroughCloud() throws IOException {
		Machine a = new Machine("a");
		Machine b = new Machine("b");
		Version va = a.sell(BOOKING, "Anna");
		Version vb = b.sell(BOOKING, "Bert");
		clock.advance(Duration.ofSeconds(2));
		a.service.tick();
		b.service.tick();
		assertTrue(a.events.lost.isEmpty());
		assertTrue(b.events.lost.isEmpty(), "bis hierher kennt B nur den eigenen Anspruch");

		cloud.step("a");
		cloud.step("b");

		Path claimsB = b.clientFolder.resolve("data").resolve(BOOKING.relativePath()).resolve("claims");
		assertEquals(List.of("root (conflicted copy b).json", "root.json"), names(claimsB));
		Claim winner = StoreCodec.readClaim(Files.readAllBytes(claimsB.resolve("root.json")));
		assertEquals(va.id(), winner.version());

		clock.advance(Duration.ofSeconds(2));
		b.service.tick();

		assertEquals(List.of(new LostVersion(BOOKING, List.of(vb.id()), va.id())), b.events.lost);
		assertTrue(b.events.errors.isEmpty());
		assertEquals(va.id(), b.service.unitState(BOOKING).head().id());

		// die späte Niederlage selbst: B hat seinen verdrängten Anspruch neben den neuen gelegt
		Path localClaims = b.localData().resolve(BOOKING.relativePath()).resolve("claims");
		assertEquals(va.id(), StoreCodec.readClaim(Files.readAllBytes(localClaims.resolve("root.json"))).version());
		assertEquals(1, names(localClaims).stream().filter(n -> n.startsWith("root.json.verdraengt-")).count(),
				"verdrängter Anspruch neben dem neuen: " + names(localClaims));

		// der Vollabgleich übergeht die Konfliktkopie: kein Fehler, keine Kopie im lokalen Bestand, kein zweiter Verlust
		b.service.syncNow();
		b.service.tick();

		assertTrue(b.events.errors.isEmpty());
		assertEquals(1, b.events.lost.size());
		assertTrue(tree(b.localData()).stream().noneMatch(p -> p.contains("conflicted")), "Konfliktkopie im lokalen Bestand");
		assertTrue(Files.exists(claimsB.resolve("root (conflicted copy b).json")), "die Konfliktkopie bleibt liegen");

		// A hat gewonnen und meldet auch nach dem nächsten Abgleich nichts
		cloud.step("b");
		cloud.step("a");
		clock.advance(Duration.ofSeconds(30));
		a.service.tick();
		a.service.syncNow();
		assertTrue(a.events.lost.isEmpty());
		assertTrue(a.events.errors.isEmpty());
		assertEquals(va.id(), a.service.unitState(BOOKING).head().id());
	}

	/**
	 * A ist lange ohne Verbindung zum Server (sein Client-Ordner ist lokal, also nie OFFLINE) und "gewinnt" den Platz nur
	 * in seiner Kopie; B ist auf dem Server zuerst. Erst nach 16 Minuten, wenn A's Beobachtung längst abgelaufen ist,
	 * übernimmt A's Client die Fassung des Servers, zusammen mit B's Liste. Der Eintrag des Gewinners löst die
	 * Neuberechnung aus.
	 */
	@Test
	void lateDefeatAfterTheWatchWindowIsFoundThroughTheWinnersFeed() throws IOException {
		Machine a = new Machine("a");
		Machine b = new Machine("b");
		Version va = a.sell(BOOKING, "Anna");
		Version vb = b.sell(BOOKING, "Bert");
		clock.advance(Duration.ofSeconds(2));
		b.service.tick();
		cloud.step("b");
		a.service.tick();
		clock.advance(Duration.ofMinutes(16));
		a.service.tick();
		assertTrue(a.events.lost.isEmpty());

		cloud.step("a");
		clock.advance(Duration.ofSeconds(30));
		a.service.tick();
		a.service.tick();

		assertEquals(List.of(new LostVersion(BOOKING, List.of(va.id()), vb.id())), a.events.lost);
		Path localClaims = a.localData().resolve(BOOKING.relativePath()).resolve("claims");
		assertEquals(vb.id(), StoreCodec.readClaim(Files.readAllBytes(localClaims.resolve("root.json"))).version());
		assertEquals(1, names(localClaims).stream().filter(n -> n.startsWith("root.json.verdraengt-")).count());
		assertEquals(vb.id(), a.service.unitState(BOOKING).head().id());
	}

	/**
	 * Wie oben, aber B's Liste kommt vor der Konfliktauflösung an: Der lokale Anspruch ist noch A's eigener, die Liste
	 * nennt B's Version. A beobachtet den Platz deshalb neu und erkennt die Niederlage, sobald der Client sie übernimmt.
	 */
	@Test
	void winnersFeedBeforeTheConflictRearmsTheWatch() throws IOException {
		Machine a = new Machine("a");
		Machine b = new Machine("b");
		Version va = a.sell(BOOKING, "Anna");
		Version vb = b.sell(BOOKING, "Bert");
		clock.advance(Duration.ofSeconds(2));
		b.service.tick();
		cloud.step("b");
		a.service.tick();
		clock.advance(Duration.ofMinutes(16));
		a.service.tick();

		cloud.stepFilesOnly("a", rel -> !rel.contains("/claims/"));
		clock.advance(Duration.ofSeconds(30));
		a.service.tick();
		assertTrue(a.events.lost.isEmpty(), "der Client hat den Anspruch noch nicht übernommen");

		cloud.step("a");
		clock.advance(Duration.ofSeconds(2));
		a.service.tick();
		a.service.tick();

		assertEquals(List.of(new LostVersion(BOOKING, List.of(va.id()), vb.id())), a.events.lost);
		assertEquals(vb.id(), a.service.unitState(BOOKING).head().id());
	}

	/**
	 * A gewinnt nur in seinem Client-Ordner und wird beendet. Mehr als 15 Minuten später, während das Programm aus ist,
	 * bringt der Client B's Anspruch und B's Liste. Beim Neustart darf der Vollabgleich die Liste nicht überspringen:
	 * Erst ihr Eintrag deckt die Niederlage auf.
	 */
	@Test
	void winnersFeedAlreadyThereAtRestartIsNotSkipped() throws IOException {
		Machine a = new Machine("a");
		Machine b = new Machine("b");
		Version vb = b.sell(BOOKING, "Bert");
		clock.advance(Duration.ofSeconds(2));
		b.service.tick();
		cloud.step("b");
		Version va = a.sell(BOOKING, "Anna");
		clock.advance(Duration.ofSeconds(2));
		a.service.tick();
		assertEquals(va.id(), a.service.unitState(BOOKING).head().id(), "A hält den Platz in seiner Kopie");

		// A ist beendet; der Client bringt 20 Minuten später die Entscheidung des Servers und B's Liste
		clock.advance(Duration.ofMinutes(20));
		cloud.step("a");
		Recorder events = new Recorder();
		SyncService restarted = new SyncService(a.appData, MachineIdentity.loadOrCreate(a.appData, () -> "a"),
				new NioFileLayer(), Runnable::run, Runnable::run, p -> true, clock);
		restarted.setListener(events);
		assertTrue(restarted.startup(a.clientFolder.toString()));
		clock.advance(Duration.ofSeconds(2));
		restarted.tick();

		assertEquals(List.of(new LostVersion(BOOKING, List.of(va.id()), vb.id())), events.lost);
		Path localClaims = a.localData().resolve(BOOKING.relativePath()).resolve("claims");
		assertEquals(vb.id(), StoreCodec.readClaim(Files.readAllBytes(localClaims.resolve("root.json"))).version());
		assertEquals(1, names(localClaims).stream().filter(n -> n.startsWith("root.json.verdraengt-")).count());
		assertEquals(vb.id(), restarted.unitState(BOOKING).head().id());
	}

	/** Bis hierher gleich in den folgenden Szenarien: B ist auf dem Server zuerst, A gewinnt nur lokal, 16 Minuten vergehen. */
	private Version[] aWinsOnlyLocallyAndWaits(Machine a, Machine b) throws IOException {
		Version vb = b.sell(BOOKING, "Bert");
		clock.advance(Duration.ofSeconds(2));
		b.service.tick();
		cloud.step("b");
		Version va = a.sell(BOOKING, "Anna");
		clock.advance(Duration.ofSeconds(2));
		a.service.tick();
		clock.advance(Duration.ofMinutes(16));
		a.service.tick();
		assertTrue(a.events.lost.isEmpty());
		return new Version[] { va, vb };
	}

	private void assertDisplacedOnce(Machine a, Version va, Version vb) throws IOException {
		assertEquals(List.of(new LostVersion(BOOKING, List.of(va.id()), vb.id())), a.events.lost);
		Path localClaims = a.localData().resolve(BOOKING.relativePath()).resolve("claims");
		assertEquals(vb.id(), StoreCodec.readClaim(Files.readAllBytes(localClaims.resolve("root.json"))).version());
		assertEquals(1, names(localClaims).stream().filter(n -> n.startsWith("root.json.verdraengt-")).count());
		assertEquals(vb.id(), a.service.unitState(BOOKING).head().id());
	}

	/**
	 * B's Anspruch und Liste kommen an, B's Versionsdatei hängt mehr als 5 Minuten nach: A überspringt die Liste, ohne
	 * ihre Ansprüche anzusehen. Der angeforderte Vollabgleich vergleicht die eigenen Ansprüche und deckt die Niederlage
	 * auf.
	 */
	@Test
	void winnersFeedSkippedAfterWaitingIsCaughtByTheFullSync() throws IOException {
		Machine a = new Machine("a");
		Machine b = new Machine("b");
		Version[] versions = aWinsOnlyLocallyAndWaits(a, b);
		Version va = versions[0];
		Version vb = versions[1];

		String vbFile = vb.id() + ".json";
		cloud.stepFilesOnly("a", rel -> !rel.endsWith(vbFile));
		clock.advance(Duration.ofSeconds(30));
		a.service.tick();
		clock.advance(Duration.ofMinutes(5));
		a.service.tick();
		assertTrue(a.events.lost.isEmpty(), "die Liste wurde übersprungen");

		cloud.step("a");
		clock.advance(Duration.ofSeconds(2));
		a.service.tick();
		a.service.tick();

		assertDisplacedOnce(a, va, vb);
	}

	/**
	 * B's Liste kommt, solange A's Client noch A's Anspruch hat (neu beobachtet), B's Anspruch erst nach Ablauf auch dieser
	 * Beobachtung: Keine Liste und keine Beobachtung deckt es auf, der nächste Vollabgleich schon.
	 */
	@Test
	void winnersClaimAfterTheRearmedWindowIsCaughtByTheFullSync() throws IOException {
		Machine a = new Machine("a");
		Machine b = new Machine("b");
		Version[] versions = aWinsOnlyLocallyAndWaits(a, b);
		Version va = versions[0];
		Version vb = versions[1];

		cloud.stepFilesOnly("a", rel -> !rel.contains("/claims/"));
		clock.advance(Duration.ofSeconds(30));
		a.service.tick();
		clock.advance(Duration.ofMinutes(16));
		a.service.tick();
		cloud.step("a");
		clock.advance(Duration.ofSeconds(2));
		a.service.tick();
		assertTrue(a.events.lost.isEmpty(), "bis zum nächsten Vollabgleich bleibt es unentdeckt");

		clock.advance(Duration.ofMinutes(10));
		a.service.tick();
		a.service.tick();

		assertDisplacedOnce(a, va, vb);
	}

	/** Die Liste von A kommt bei B an, bevor die Versionsdatei da ist: B wartet und meldet erst nach dem Eintreffen. */
	@Test
	void feedBeforeFilesWaits() throws IOException {
		Machine a = new Machine("a");
		Machine b = new Machine("b");
		Version va = a.sell(BOOKING, "Anna");
		clock.advance(Duration.ofSeconds(2));
		a.service.tick();
		cloud.step("a");

		cloud.stepFilesOnly("b", rel -> rel.startsWith("data/feeds/"));
		clock.advance(Duration.ofSeconds(30));
		b.service.tick();

		assertTrue(Files.exists(b.clientFolder.resolve("data/feeds/" + va.machine())),
				"die Liste ist angekommen");
		assertFalse(b.events.changedUnits().contains(BOOKING));
		assertTrue(b.events.pending.isEmpty());
		assertTrue(b.events.errors.isEmpty());
		assertFalse(Files.exists(b.localData().resolve(BOOKING.relativePath()).resolve(va.id() + ".json")));

		clock.advance(Duration.ofSeconds(2));
		b.service.tick();
		assertFalse(b.events.changedUnits().contains(BOOKING), "B wartet weiter, solange die Datei fehlt");

		cloud.step("b");
		clock.advance(Duration.ofSeconds(2));
		b.service.tick();

		assertTrue(b.events.changedUnits().contains(BOOKING));
		assertEquals(va.id(), b.service.unitState(BOOKING).head().id());
		assertFalse(b.service.unitState(BOOKING).provisional());
		assertTrue(b.events.errors.isEmpty());
	}

	/** Version und Liste sind da, der Anspruch noch nicht: B meldet "wartend" mit dem Namen von A, dann gilt die Version. */
	@Test
	void pendingSightingBeforeClaim() throws IOException {
		Machine a = new Machine("a");
		Machine b = new Machine("b");
		Version va = a.sell(BOOKING, "Anna");
		clock.advance(Duration.ofSeconds(2));
		a.service.tick();
		cloud.step("a");

		cloud.stepFilesOnly("b", rel -> !rel.contains("/claims/"));
		clock.advance(Duration.ofSeconds(30));
		b.service.tick();

		assertEquals(List.of(new PendingSighting(BOOKING, va.id(), "a")), b.events.pending);
		assertEquals("a", va.machineName());
		// ohne Anspruch ist die Version noch nicht gültig (die Änderungsmeldung für die kopierte Datei ist erlaubt)
		Version head = b.service.unitState(BOOKING).head();
		assertTrue(head == null || !head.id().equals(va.id()));
		assertTrue(b.events.errors.isEmpty());

		cloud.step("b");
		clock.advance(Duration.ofSeconds(2));
		b.service.tick();

		assertTrue(b.events.changedUnits().contains(BOOKING));
		assertEquals(va.id(), b.service.unitState(BOOKING).head().id());
		assertEquals(1, b.events.pending.size(), "die Beobachtung wird nur einmal gemeldet");
		assertTrue(b.events.lost.isEmpty());
	}
}
