package de.eltviller_carneval_verein.karten.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.BookingSync;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.model.TableRow;
import de.eltviller_carneval_verein.karten.store.ConflictNote;
import de.eltviller_carneval_verein.karten.store.FaultyFileLayer;
import de.eltviller_carneval_verein.karten.store.NioFileLayer;
import de.eltviller_carneval_verein.karten.store.OpenConflict;
import de.eltviller_carneval_verein.karten.store.SimulatedCloud;
import de.eltviller_carneval_verein.karten.store.SyncListener;
import de.eltviller_carneval_verein.karten.store.SyncService;
import de.eltviller_carneval_verein.karten.store.SyncState;
import de.eltviller_carneval_verein.karten.store.TestClock;
import de.eltviller_carneval_verein.karten.store.TestSyncServices;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.UnitSnapshot;
import de.eltviller_carneval_verein.karten.store.UnitState;
import de.eltviller_carneval_verein.karten.store.UnitType;
import de.eltviller_carneval_verein.karten.store.Version;
import de.eltviller_carneval_verein.karten.store.VersionId;

/**
 * Konflikte Ende zu Ende über zwei oder drei Rechner: Jeder Rechner hat einen eigenen Dienst mit den Strategien aus
 * {@link ConflictPolicies}, speichert über seine Repositories und tauscht über einen gemeinsamen Ordner (oder einen
 * nachgebauten Nextcloud-Ordner, {@link SimulatedCloud}) aus. Geprüft wird der Stand im Speicher (Index, Verlauf, offene
 * Konflikte); das Einarbeiten der Zusammenführungen ins Modell des Verlierers ist Sache der Repositories.
 */
class ConflictScenarioTest {

	/** So viele Runden laufen höchstens, bis Ruhe herrscht; zusammen weniger als die 5 Minuten Wartezeit auf Listen. */
	private static final int MAX_ROUNDS = 8;
	/** Der Abstand zwischen zwei Runden: länger als das Intervall, in dem ein Dienst neue Rechner sucht. */
	private static final Duration ROUND = Duration.ofSeconds(31);

	/** Zeichnet die Rückmeldungen eines Dienstes auf, die die Szenarien prüfen. */
	private static final class Recorder implements SyncListener {
		final List<String> errors = new ArrayList<>();

		@Override
		public void error(String message) {
			errors.add(message);
		}
	}

	/**
	 * Ein Rechner: eigener Datenordner, eigener Dienst mit den Strategien, eigene Repositories mit dem geladenen Event.
	 * Mit {@link #down} ist der gemeinsame Ordner für ihn weg: Die Prüfung meldet "nicht erreichbar", und jeder Zugriff
	 * darauf scheitert.
	 */
	private final class Machine {
		final String name;
		final AtomicBoolean down = new AtomicBoolean();
		final Recorder events = new Recorder();
		final SyncService sync;
		final StoreHallRepository halls;
		final StoreEventRepository repository;
		Hall hall;
		Event event;

		Machine(String name, Path sharedDir) {
			this.name = name;
			FaultyFileLayer layer = new FaultyFileLayer(new NioFileLayer());
			for (String operation : List.of("createNew", "read", "listNames")) {
				layer.failWhen(p -> down.get() && p.startsWith(sharedDir), operation,
						new IOException("Netzlaufwerk getrennt"));
			}
			sync = TestSyncServices.unstarted(dir.resolve("app-" + name), name, layer, p -> !down.get(), clock);
			sync.setListener(events);
			ConflictPolicies.install(sync);
			assertTrue(sync.startup(sharedDir.toString()));
			halls = TestRepositories.halls(sync);
			repository = TestRepositories.events(sync);
			repository.setHallLookup(id -> hall != null && hall.getId().equals(id) ? hall : null);
			started.add(this);
		}

		/** Lädt Halle und Event aus dem Speicher und bereitet das Event vor wie die App (leere Platzhalter). */
		void load() {
			hall = halls.loadHalls().get(0);
			event = repository.loadEvents().get(0);
			for (Presentation presentation : event.getPresentations()) {
				BookingSync.sync(presentation, plan());
			}
		}

		SeatingPlan plan() {
			return hall.getSeatingPlans().get(0);
		}

		Presentation friday() {
			return event.getPresentations().get(0);
		}

		/** Die Buchung auf dem n-ten Sitz der Bestuhlung. */
		Booking seat(int n) {
			String seatId = plan().getSeats().get(n).getId();
			return friday().getBookings().stream().filter(b -> seatId.equals(b.getSeatId())).findFirst().orElseThrow();
		}

		/** Verkauft den n-ten Sitz und speichert. */
		Booking sell(int n, String lastName) {
			Booking booking = seat(n);
			booking.setLastName(lastName);
			repository.saveEvent(event);
			return booking;
		}

		void tick() {
			if (cloud != null) {
				cloud.step(name);
			}
			TestSyncServices.tick(sync);
			if (cloud != null) {
				cloud.step(name);
			}
		}

		Map<UnitKey, UnitSnapshot> eventState() {
			return sync.eventSnapshots(event.getId());
		}

		List<OpenConflict> open() {
			return sync.openConflicts();
		}
	}

	@TempDir
	Path dir;

	private final TestClock clock = new TestClock();
	/** Der nachgebaute Nextcloud-Ordner; {@code null}, solange die Rechner einen gemeinsamen Ordner teilen. */
	private SimulatedCloud cloud;
	/** Alle Rechner des Szenarios. */
	private final List<Machine> started = new ArrayList<>();
	private final List<LogRecord> warnings = new ArrayList<>();
	private final Handler capture = new Handler() {
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
	private final Logger logger = Logger.getLogger(SyncService.class.getName());
	private boolean parentHandlers;

	@BeforeEach
	void captureWarnings() {
		parentHandlers = logger.getUseParentHandlers();
		logger.addHandler(capture);
		logger.setUseParentHandlers(false);
	}

	/**
	 * Keine Strategie scheiterte (SEVERE), keine Strecke wurde erst nach 5 Minuten mit dem Vorhandenen aufgelöst, und kein
	 * Rechner meldete einen Fehler.
	 */
	@AfterEach
	void noFailuresAndNoGivingUp() {
		logger.removeHandler(capture);
		logger.setUseParentHandlers(parentHandlers);
		for (LogRecord record : warnings) {
			assertTrue(record.getLevel().intValue() < Level.SEVERE.intValue(), "SEVERE: " + record.getMessage());
			assertFalse(record.getMessage().contains("5 Minuten"), record.getMessage());
		}
		for (Machine machine : started) {
			assertTrue(machine.events.errors.isEmpty(), machine.name + ": " + machine.events.errors);
		}
	}

	// Bauteile -->

	/** Rechner im gemeinsamen Ordner {@code shared}. */
	private Machine[] machines(String... names) throws IOException {
		Path shared = Files.createDirectories(dir.resolve("shared"));
		return Arrays.stream(names).map(name -> new Machine(name, shared)).toArray(Machine[]::new);
	}

	/** Rechner, deren Client-Ordner {@link #cloud} abgleicht. */
	private Machine[] cloudMachines(String... names) {
		cloud = new SimulatedCloud(dir.resolve("server"));
		return Arrays.stream(names).map(name -> new Machine(name, cloud.addClient(name))).toArray(Machine[]::new);
	}

	/**
	 * Der erste Rechner legt die Halle (eine Reihe A mit 2 Tischen zu je 3 Sitzen) und das Event "Prunksitzung" mit der
	 * Vorstellung "Freitag" an, "Müller" auf dem ersten Sitz; die anderen holen beides ab und laden es.
	 */
	private void setUp(Machine first, Machine... others) {
		Hall hall = new Hall();
		hall.setName("Stadthalle");
		SeatingPlan plan = hall.addSeatingPlan();
		TableRow row = plan.addRow();
		row.setLabel("A");
		plan.fillRow(row, 2, 3);
		first.halls.saveHall(hall);
		first.hall = hall;
		Event event = new Event();
		event.setName("Prunksitzung");
		Presentation friday = event.addPresentation("Freitag");
		friday.setHallId(hall.getId());
		friday.setSeatingPlanId(plan.getId());
		BookingSync.sync(friday, plan);
		first.event = event;
		first.seat(0).setLastName("Müller");
		first.repository.saveEvent(event);
		first.tick();
		clock.advance(ROUND);
		for (Machine other : others) {
			other.tick();
			other.load();
		}
	}

	/**
	 * Takte alle Rechner reihum, bis zwei Runden nacheinander nichts mehr ändern und kein Rechner noch eine verlorene oder
	 * ausstehende Version hat. Zwei, weil das Hochladen einer Zusammenführung ihren Stand beim Verlierer nicht ändert: Erst
	 * die Runde danach bringt sie zu den anderen.
	 */
	private void untilQuiet(Machine... machines) throws IOException {
		int quiet = 0;
		for (int round = 0; round < MAX_ROUNDS; round++) {
			List<Map<UnitKey, UnitSnapshot>> before = allSnapshots(machines);
			clock.advance(ROUND);
			for (Machine machine : machines) {
				machine.tick();
			}
			quiet = before.equals(allSnapshots(machines)) && settled(machines) ? quiet + 1 : 0;
			if (quiet == 2) {
				return;
			}
		}
		fail("Keine Ruhe nach " + MAX_ROUNDS + " Runden");
	}

	private static List<Map<UnitKey, UnitSnapshot>> allSnapshots(Machine... machines) {
		return Arrays.stream(machines).map(machine -> machine.sync.snapshots()).toList();
	}

	private static boolean settled(Machine... machines) throws IOException {
		for (Machine machine : machines) {
			if (!unsettled(machine).isEmpty()) {
				return false;
			}
		}
		return true;
	}

	/** Die Einheiten des Rechners mit einer verlorenen oder ausstehenden Version. */
	private static List<String> unsettled(Machine machine) throws IOException {
		List<String> units = new ArrayList<>();
		for (UnitKey unit : machine.sync.snapshots().keySet()) {
			UnitState state = machine.sync.unitState(unit);
			if (!state.lost().isEmpty() || !state.pending().isEmpty()) {
				units.add(unit.relativePath());
			}
		}
		return units;
	}

	/** Alle Rechner haben denselben Stand des Events, und keine Einheit ist gegabelt oder steht aus. */
	private static void assertConverged(Machine... machines) throws IOException {
		Map<UnitKey, UnitSnapshot> expected = machines[0].eventState();
		for (Machine machine : machines) {
			assertEquals(List.of(), differences(expected, machine.eventState()), "Stand auf " + machine.name);
			assertEquals(List.of(), unsettled(machine), "verloren oder ausstehend auf " + machine.name);
		}
	}

	/** Die Einheiten, deren Stand sich unterscheidet, kurz beschrieben: Ordner, Version und Name auf beiden Seiten. */
	private static List<String> differences(Map<UnitKey, UnitSnapshot> expected, Map<UnitKey, UnitSnapshot> actual) {
		Set<UnitKey> units = new HashSet<>(expected.keySet());
		units.addAll(actual.keySet());
		return units.stream().filter(unit -> !Objects.equals(expected.get(unit), actual.get(unit)))
				.map(unit -> unit.relativePath() + ": " + brief(expected.get(unit)) + " / " + brief(actual.get(unit)))
				.sorted().toList();
	}

	private static String brief(UnitSnapshot snapshot) {
		if (snapshot == null) {
			return "fehlt";
		}
		String content = snapshot.deleted() ? "gelöscht"
				: snapshot.payload() == null ? "ohne Inhalt"
						: String.valueOf(snapshot.payload().get(snapshot.payload().has("lastName") ? "lastName" : "name"));
		return snapshot.version() + " " + snapshot.status() + " " + content;
	}

	private static UnitKey seatUnit(Machine machine, int n) {
		return UnitKey.booking(machine.event.getId(), machine.friday().getId(),
				machine.plan().getSeats().get(n).getId());
	}

	private static UnitKey unassignedUnit(Machine machine, Booking booking) {
		return UnitKey.unassignedBooking(machine.event.getId(), machine.friday().getId(), booking.getId());
	}

	private static String lastName(UnitSnapshot snapshot) {
		return snapshot.payload().get("lastName").asText();
	}

	/** Die Buchungen "ohne Platz" (Einheiten {@code _<ID>}) des Events, die leben. */
	private static Map<UnitKey, UnitSnapshot> unassigned(Machine machine) {
		Map<UnitKey, UnitSnapshot> result = new LinkedHashMap<>();
		machine.eventState().forEach((unit, snapshot) -> {
			if (unit.type() == UnitType.BOOKING && unit.bookingDir().startsWith("_") && !snapshot.deleted()) {
				result.put(unit, snapshot);
			}
		});
		return result;
	}

	/** Prüft eine Buchung "ohne Platz": Name, kein Sitz, keine Platzangaben. */
	private static void assertWithoutPlace(UnitSnapshot snapshot, String lastName) {
		assertEquals(lastName, lastName(snapshot));
		assertTrue(snapshot.payload().get("unassigned").asBoolean());
		assertTrue(snapshot.payload().get("seatId").isNull());
		assertEquals("", snapshot.payload().get("rowLabel").asText());
		assertEquals(0, snapshot.payload().get("tableNumber").asInt());
		assertEquals(0, snapshot.payload().get("seatNumber").asInt());
	}

	/** Prüft den einzigen offenen Konflikt eines Verlierers: Doppelverkauf, seine Buchung "ohne Platz" in {@code moved}. */
	private static OpenConflict assertDoubleSale(Machine loser, UnitKey moved, UnitKey seat) {
		List<OpenConflict> open = loser.open();
		assertEquals(1, open.size(), loser.name + ": " + open);
		OpenConflict conflict = open.get(0);
		assertNotNull(conflict.note());
		assertEquals(ConflictNote.Kind.DOUBLE_SALE, conflict.note().kind());
		assertEquals(moved, conflict.note().moved());
		assertEquals(seat, conflict.unit());
		return conflict;
	}

	/** Die Versionen der Einheit mit einem Vermerk. */
	private static long notes(Machine machine, UnitKey unit) throws IOException {
		return machine.sync.history(unit).stream().filter(version -> version.conflict() != null).count();
	}

	// <-- Bauteile

	/** A und B verkaufen denselben Platz, B lädt später hoch: Der Sitz bleibt A's, B's Buchung steht "ohne Platz". */
	@Test
	void doubleSaleConvergesWithLoserBookingWithoutPlace() throws IOException {
		Machine[] m = machines("a", "b");
		Machine a = m[0];
		Machine b = m[1];
		setUp(a, b);
		a.sell(1, "Anna");
		Booking bert = b.sell(1, "Bert");
		a.tick();
		b.tick();

		untilQuiet(a, b);

		assertConverged(a, b);
		UnitKey seat = seatUnit(a, 1);
		assertEquals("Anna", lastName(a.eventState().get(seat)));
		UnitKey moved = unassignedUnit(b, bert);
		assertEquals(Set.of(moved), unassigned(a).keySet());
		assertWithoutPlace(unassigned(a).get(moved), "Bert");
		assertDoubleSale(b, moved, seat);
		assertTrue(a.open().isEmpty(), "der Gewinner hat keinen Konflikt: " + a.open());
	}

	/**
	 * Drei Rechner verkaufen denselben Platz. Die Zusammenführungen der beiden Verlierer gabeln ihrerseits; die zweite
	 * Runde läuft ohne neuen Vermerk zusammen. Jeder Verlierer hat genau seine eigene Buchung "ohne Platz".
	 */
	@Test
	void threeMachinesSellingOneSeatConverge() throws IOException {
		Machine[] m = machines("a", "b", "c");
		Machine a = m[0];
		Machine b = m[1];
		Machine c = m[2];
		setUp(a, b, c);
		a.sell(1, "Anna");
		Booking bert = b.sell(1, "Bert");
		Booking carla = c.sell(1, "Carla");
		a.tick();
		b.tick();
		c.tick();

		untilQuiet(a, b, c);

		assertConverged(a, b, c);
		UnitKey seat = seatUnit(a, 1);
		assertEquals("Anna", lastName(a.eventState().get(seat)));
		UnitKey bertMoved = unassignedUnit(b, bert);
		UnitKey carlaMoved = unassignedUnit(c, carla);
		Map<UnitKey, UnitSnapshot> withoutPlace = unassigned(a);
		assertEquals(Set.of(bertMoved, carlaMoved), withoutPlace.keySet());
		assertWithoutPlace(withoutPlace.get(bertMoved), "Bert");
		assertWithoutPlace(withoutPlace.get(carlaMoved), "Carla");
		assertDoubleSale(b, bertMoved, seat);
		assertDoubleSale(c, carlaMoved, seat);
		assertTrue(a.open().isEmpty(), "der Gewinner hat keinen Konflikt: " + a.open());
		// die Zusammenführungen beider Verlierer gabelten; die zweite Runde führte sie ohne neuen Vermerk zusammen
		Version head = a.sync.unitState(seat).head();
		assertNull(head.conflict());
		assertEquals(2, head.parents().size());
		Map<VersionId, Version> byId = new HashMap<>();
		a.sync.history(seat).forEach(version -> byId.put(version.id(), version));
		for (VersionId parent : head.parents()) {
			assertEquals(ConflictNote.Kind.DOUBLE_SALE, byId.get(parent).conflict().kind());
		}
		assertEquals(2, notes(a, seat));
	}

	/**
	 * B erkennt die Niederlage noch online, der gemeinsame Ordner fällt weg, bevor B zusammenführt: Die Auflösung läuft
	 * lokal, der Konflikt ist sofort offen. Nach der Rückkehr wird die Zusammenführung hochgeladen; es entsteht weder ein
	 * zweiter Vermerk noch ein zweiter offener Konflikt.
	 */
	@Test
	void offlineLossIsResolvedLocallyAndStaysOneConflict() throws IOException {
		Machine[] m = machines("a", "b");
		Machine a = m[0];
		Machine b = m[1];
		setUp(a, b);
		a.sell(1, "Anna");
		Booking bert = b.sell(1, "Bert");
		a.tick();
		clock.advance(ROUND);
		b.tick();
		UnitKey seat = seatUnit(a, 1);
		assertEquals(1, b.sync.unitState(seat).lost().size(), "B hat verloren und wartet noch auf A's Liste");
		assertTrue(b.open().isEmpty());

		b.down.set(true);
		clock.advance(Duration.ofSeconds(2));
		b.tick();

		assertEquals(SyncState.OFFLINE, b.sync.syncState().get());
		UnitState offline = b.sync.unitState(seat);
		assertTrue(offline.lost().isEmpty(), "lokal aufgelöst");
		assertEquals(1, offline.pending().size(), "die Zusammenführung steht aus");
		UnitKey moved = unassignedUnit(b, bert);
		assertTrue(b.sync.unitState(moved).provisional());
		OpenConflict conflict = assertDoubleSale(b, moved, seat);
		assertEquals(offline.pending().get(0).id(), conflict.id());

		clock.advance(ROUND);
		b.tick();
		assertEquals(List.of(conflict), b.open(), "ohne Verbindung bleibt es ein Konflikt");

		b.down.set(false);
		untilQuiet(a, b);

		assertConverged(a, b);
		assertEquals(List.of(conflict.key()), b.open().stream().map(OpenConflict::key).toList());
		assertEquals(1, notes(b, seat));
		assertEquals(1, notes(a, seat));
		assertEquals(conflict.id(), a.sync.unitState(seat).head().id());
		assertEquals(Set.of(moved), unassigned(a).keySet());
		assertWithoutPlace(unassigned(a).get(moved), "Bert");
		assertTrue(a.open().isEmpty());
	}

	/** Beide ändern den Namen derselben Buchung verschieden: Der Wert des Gewinners gilt, der Vermerk hat beide Werte. */
	@Test
	void fieldConflictKeepsWinnerValueAndNotesBoth() throws IOException {
		Machine[] m = machines("a", "b");
		Machine a = m[0];
		Machine b = m[1];
		setUp(a, b);
		a.sell(0, "Meier");
		b.sell(0, "Maier");
		a.tick();
		b.tick();

		untilQuiet(a, b);

		assertConverged(a, b);
		UnitKey seat = seatUnit(a, 0);
		assertEquals("Meier", lastName(a.eventState().get(seat)));
		assertTrue(unassigned(a).isEmpty(), "dieselbe Buchung: kein Doppelverkauf");
		List<OpenConflict> open = b.open();
		assertEquals(1, open.size(), open.toString());
		ConflictNote note = open.get(0).note();
		assertEquals(ConflictNote.Kind.FIELDS, note.kind());
		assertEquals(seat, open.get(0).unit());
		assertEquals(1, note.fields().size());
		ConflictNote.FieldConflict customer = note.fields().get(0);
		assertEquals("customer", customer.group());
		assertEquals("Maier", customer.mine().get("lastName").asText());
		assertEquals("Meier", customer.theirs().get("lastName").asText());
		assertTrue(a.open().isEmpty(), a.open().toString());
	}

	/**
	 * A löscht die Vorstellung, B verkauft darin gleichzeitig einen Platz: Nichts gabelt, der Verkauf bleibt im Speicher, und
	 * B hat die Elternlöschung als offenen Konflikt.
	 */
	@Test
	void deletionOfPresentationAgainstConcurrentSaleIsOpenForSeller() throws IOException {
		Machine[] m = machines("a", "b");
		Machine a = m[0];
		Machine b = m[1];
		setUp(a, b);
		UnitKey presentation = UnitKey.presentation(a.event.getId(), a.friday().getId());
		UnitKey seat = seatUnit(a, 1);
		a.event.deletePresentation(a.friday());
		a.repository.saveEvent(a.event);
		b.sell(1, "Bert");
		a.tick();
		b.tick();

		untilQuiet(a, b);

		assertConverged(a, b);
		assertTrue(a.eventState().get(presentation).deleted());
		assertEquals("Bert", lastName(a.eventState().get(seat)));
		List<OpenConflict> open = b.open();
		assertEquals(1, open.size(), open.toString());
		OpenConflict.ParentDeletion deletion = open.get(0).deletion();
		assertNotNull(deletion);
		assertEquals(presentation, deletion.parent());
		assertEquals(List.of(seat), deletion.affected());
		assertEquals("a", deletion.machineName());
		assertTrue(a.open().isEmpty(), a.open().toString());
	}

	/** Beide benennen die Halle verschieden um: Der Name des Gewinners gilt, der Vermerk nennt den eigenen. */
	@Test
	void hallConflictKeepsWinnerAndNotesMine() throws IOException {
		Machine[] m = machines("a", "b");
		Machine a = m[0];
		Machine b = m[1];
		setUp(a, b);
		a.hall.setName("Anna-Halle");
		a.halls.saveHall(a.hall);
		b.hall.setName("Bert-Halle");
		b.halls.saveHall(b.hall);
		a.tick();
		b.tick();

		untilQuiet(a, b);

		UnitKey unit = UnitKey.hall(a.hall.getId());
		for (Machine machine : m) {
			UnitState state = machine.sync.unitState(unit);
			assertEquals("Anna-Halle", state.head().payload().get("name").asText(), machine.name);
			assertTrue(state.lost().isEmpty());
			assertTrue(state.pending().isEmpty());
		}
		assertEquals(a.sync.unitState(unit).head().id(), b.sync.unitState(unit).head().id());
		List<OpenConflict> open = b.open();
		assertEquals(1, open.size(), open.toString());
		ConflictNote note = open.get(0).note();
		assertEquals(ConflictNote.Kind.FIELDS, note.kind());
		assertEquals(unit, open.get(0).unit());
		assertEquals(1, note.fields().size());
		assertEquals(HallPolicy.GROUP, note.fields().get(0).group());
		assertEquals("Bert-Halle", note.fields().get(0).mine().get("name").asText());
		assertEquals("Anna-Halle", note.fields().get(0).theirs().get("name").asText());
		assertTrue(a.open().isEmpty(), a.open().toString());
	}

	/**
	 * Beide verkaufen denselben Platz und gewinnen zunächst nur im eigenen Client-Ordner. Der Server nimmt A's Anspruch;
	 * B's Client legt seinen als Konfliktkopie beiseite. B erkennt die späte Niederlage und führt zusammen.
	 */
	@Test
	void lateLossAfterConflictedCopyIsResolved() throws IOException {
		Machine[] m = cloudMachines("a", "b");
		Machine a = m[0];
		Machine b = m[1];
		setUp(a, b);
		a.sell(1, "Anna");
		Booking bert = b.sell(1, "Bert");
		clock.advance(Duration.ofSeconds(2));
		TestSyncServices.tick(a.sync);
		TestSyncServices.tick(b.sync);
		UnitKey seat = seatUnit(a, 1);
		assertTrue(b.sync.unitState(seat).lost().isEmpty(), "B gewinnt zunächst im eigenen Client-Ordner");

		untilQuiet(a, b);

		assertTrue(warnings.stream().anyMatch(r -> r.getMessage().contains("Späte Niederlage")),
				"der Weg über die späte Niederlage");
		assertConverged(a, b);
		assertEquals("Anna", lastName(a.eventState().get(seat)));
		UnitKey moved = unassignedUnit(b, bert);
		assertEquals(Set.of(moved), unassigned(a).keySet());
		assertWithoutPlace(unassigned(a).get(moved), "Bert");
		assertDoubleSale(b, moved, seat);
		assertTrue(a.open().isEmpty(), a.open().toString());
	}

	/**
	 * H3: Nach einer Hallenänderung (Reihe A neu angelegt, die Sitze haben neue IDs) setzen beide Rechner Müller gleich um
	 * und speichern. Alter Sitz (zwei Löschungen), neuer Sitz (zwei Erstversionen derselben Buchung) und keine Einheit
	 * bekommt einen Vermerk.
	 */
	@Test
	void sameReseatingOnBothMachinesIsNoConflict() throws IOException {
		Machine[] m = machines("a", "b");
		Machine a = m[0];
		Machine b = m[1];
		setUp(a, b);
		UnitKey oldSeat = seatUnit(a, 0);
		String muellerId = a.seat(0).getId();
		SeatingPlan plan = a.plan();
		plan.deleteRow(plan.getRows().get(0));
		TableRow row = plan.addRow();
		row.setLabel("A");
		plan.fillRow(row, 2, 3);
		a.halls.saveHall(a.hall);
		a.tick();
		clock.advance(ROUND);
		b.tick();
		b.hall = b.halls.reloadHalls().get(0);
		assertEquals(a.plan().getSeats().get(0).getId(), b.plan().getSeats().get(0).getId());

		for (Machine machine : m) {
			BookingSync.sync(machine.friday(), machine.plan());
			assertEquals(muellerId, machine.seat(0).getId(), "Müller sitzt auf " + machine.name + " auf dem neuen Sitz");
			machine.repository.saveEvent(machine.event);
		}
		a.tick();
		b.tick();
		UnitKey newSeat = seatUnit(a, 0);
		assertEquals(1, b.sync.unitState(oldSeat).lost().size(), "B's Löschung am alten Sitz hat verloren");
		assertEquals(1, b.sync.unitState(newSeat).lost().size(), "B's Erstversion am neuen Sitz hat verloren");

		untilQuiet(a, b);

		assertConverged(a, b);
		assertTrue(a.eventState().get(oldSeat).deleted());
		assertEquals("Müller", lastName(a.eventState().get(newSeat)));
		assertEquals(muellerId, a.eventState().get(newSeat).payload().get("id").asText());
		Set<UnitKey> carriers = new HashSet<>();
		a.eventState().forEach((unit, snapshot) -> {
			if (!snapshot.deleted() && snapshot.payload() != null && snapshot.payload().has("id")
					&& muellerId.equals(snapshot.payload().get("id").asText())) {
				carriers.add(unit);
			}
		});
		assertEquals(Set.of(newSeat), carriers, "Müller lebt nur auf dem neuen Sitz");
		for (Machine machine : m) {
			assertTrue(machine.open().isEmpty(), machine.name + ": " + machine.open());
			for (UnitKey unit : machine.eventState().keySet()) {
				assertEquals(0, notes(machine, unit), machine.name + ": Vermerk an " + unit.relativePath());
			}
		}
	}
}
