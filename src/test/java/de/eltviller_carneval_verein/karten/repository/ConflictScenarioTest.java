package de.eltviller_carneval_verein.karten.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
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
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.BookingSync;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.PaymentStatus;
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
import de.eltviller_carneval_verein.karten.tracking.ChangeTracker;
import de.eltviller_carneval_verein.karten.ui.ConflictActions;
import de.eltviller_carneval_verein.karten.ui.ConflictList;
import de.eltviller_carneval_verein.karten.ui.EditingUnits;

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

	/**
	 * Zeichnet die Rückmeldungen eines Dienstes auf, die die Szenarien prüfen, und lässt die Repositories des Rechners jede
	 * Meldung einarbeiten wie die App ({@code LiveUpdates}): erst die Hallen, dann die Events. Die Warnungen des
	 * Einarbeitens stehen in {@link #warnings}; zurückgestellte Stände fragen die Szenarien beim Repository ab.
	 */
	private static final class Recorder implements SyncListener {
		final List<String> errors = new ArrayList<>();
		final List<String> warnings = new ArrayList<>();
		/** Die Repositories, sobald es sie gibt; vor dem Start kommt nichts an, was sie einarbeiten könnten. */
		StoreHallRepository halls;
		StoreEventRepository events;

		@Override
		public void unitsChanged(Set<UnitKey> units) {
			if (halls == null) {
				return;
			}
			warnings.addAll(halls.applyForeign(units).warnings());
			warnings.addAll(events.applyForeign(units).warnings());
		}

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
		final Path sharedDir;
		final AtomicBoolean down = new AtomicBoolean();
		/** Die Dateischicht des Rechners, damit ein Szenario Haken setzen kann (etwa eine Uhr, die mit jedem Lesen weiterläuft). */
		final FaultyFileLayer layer;
		final Recorder events = new Recorder();
		final SyncService sync;
		final StoreHallRepository halls;
		final StoreEventRepository repository;
		Hall hall;
		Event event;

		Machine(String name, Path sharedDir) {
			this(name, sharedDir, true);
		}

		/** Mit {@code reachable == false} startet der Rechner ohne den gemeinsamen Ordner ({@link #down}). */
		Machine(String name, Path sharedDir, boolean reachable) {
			this.name = name;
			this.sharedDir = sharedDir;
			down.set(!reachable);
			layer = new FaultyFileLayer(new NioFileLayer());
			for (String operation : List.of("createNew", "read", "listNames")) {
				layer.failWhen(p -> down.get() && p.startsWith(sharedDir), operation,
						new IOException("Netzlaufwerk getrennt"));
			}
			sync = TestSyncServices.unstarted(dir.resolve("app-" + name), name, layer, p -> !down.get(), clock);
			sync.setListener(events);
			ConflictPolicies.install(sync);
			assertEquals(reachable, sync.startup(sharedDir.toString()));
			halls = TestRepositories.halls(sync);
			repository = TestRepositories.events(sync);
			repository.setHallLookup(id -> hall != null && hall.getId().equals(id) ? hall : null);
			events.halls = halls;
			events.events = repository;
			started.add(this);
		}

		/**
		 * Beendet den Rechner sauber und startet ihn mit demselben Datenordner neu, während der gemeinsame Ordner nicht
		 * erreichbar ist; der neue Rechner hat Halle und Event geladen.
		 */
		Machine restartOffline() {
			sync.closeIndex();
			Machine restarted = new Machine(name, sharedDir, false);
			restarted.load();
			return restarted;
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
	/** Der Dienst und die Repositories, die einarbeiten; je Logger, ob er an die übergeordneten weitergab. */
	private final Map<Logger, Boolean> loggers = new LinkedHashMap<>();

	@BeforeEach
	void captureWarnings() {
		for (Class<?> type : List.of(SyncService.class, StoreEventRepository.class, StoreHallRepository.class)) {
			Logger logger = Logger.getLogger(type.getName());
			loggers.put(logger, logger.getUseParentHandlers());
			logger.addHandler(capture);
			logger.setUseParentHandlers(false);
		}
	}

	/**
	 * Keine Strategie und kein Einarbeiten scheiterte (SEVERE), keine Strecke wurde erst nach 5 Minuten mit dem Vorhandenen
	 * aufgelöst, und kein Rechner meldete einen Fehler.
	 */
	@AfterEach
	void noFailuresAndNoGivingUp() {
		loggers.forEach((logger, parentHandlers) -> {
			logger.removeHandler(capture);
			logger.setUseParentHandlers(parentHandlers);
		});
		for (LogRecord record : warnings) {
			assertTrue(record.getLevel().intValue() < Level.SEVERE.intValue(), "SEVERE: " + record.getMessage());
			assertFalse(record.getMessage().contains("5 Minuten"), record.getMessage());
		}
		for (Machine machine : started) {
			assertTrue(machine.events.errors.isEmpty(), machine.name + ": " + machine.events.errors);
			assertTrue(machine.events.warnings.isEmpty(), machine.name + ": " + machine.events.warnings);
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
		setUp(2, first, others);
	}

	/** Wie {@link #setUp(Machine, Machine...)}, mit {@code tables} Tischen zu je 3 Sitzen in der Reihe A. */
	private void setUp(int tables, Machine first, Machine... others) {
		Hall hall = new Hall();
		hall.setName("Stadthalle");
		SeatingPlan plan = hall.addSeatingPlan();
		TableRow row = plan.addRow();
		row.setLabel("A");
		plan.fillRow(row, tables, 3);
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

	/**
	 * Die Einheit "ohne Platz" der Buchung mit dieser Kennung. Die Kennung beim Verkauf festhalten: Arbeitet das Repository
	 * des Verlierers die Zusammenführung ein, trägt sein Buchungsobjekt auf dem Sitz danach die des Gewinners.
	 */
	private static UnitKey unassignedUnit(Machine machine, String bookingId) {
		return UnitKey.unassignedBooking(machine.event.getId(), machine.friday().getId(), bookingId);
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

	/**
	 * Das Modell des Rechners zeigt den Doppelverkauf auf dem n-ten Sitz: dort die Buchung des Gewinners, die des Verlierers
	 * genau einmal "ohne Platz". Der Vergleichsstand des Sitzes ist sein aktueller Stand, nichts gilt als ungespeichert und
	 * nichts ist zurückgestellt.
	 */
	private static void assertModelShowsDoubleSale(Machine machine, int n, String winner, String loserId, String loser)
			throws IOException {
		assertEquals(winner, machine.seat(n).getLastName(), machine.name);
		List<Booking> withoutPlace = machine.friday().getBookings().stream().filter(Booking::isUnassigned).toList();
		assertEquals(1, withoutPlace.size(), machine.name + ": " + withoutPlace);
		assertEquals(loserId, withoutPlace.get(0).getId(), machine.name);
		assertEquals(loser, withoutPlace.get(0).getLastName(), machine.name);
		assertNull(withoutPlace.get(0).getSeatId(), machine.name);
		UnitKey seat = seatUnit(machine, n);
		assertEquals(machine.sync.unitState(seat).current().id(), machine.repository.knownUnit(seat).version(),
				machine.name);
		assertEquals(Set.of(), machine.repository.locallyChangedUnits(), machine.name);
		assertEquals(List.of(), machine.repository.retryDeferred().deferred(), machine.name);
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
		String bertId = b.sell(1, "Bert").getId();
		a.tick();
		b.tick();

		untilQuiet(a, b);

		assertConverged(a, b);
		UnitKey seat = seatUnit(a, 1);
		assertEquals("Anna", lastName(a.eventState().get(seat)));
		UnitKey moved = unassignedUnit(b, bertId);
		assertEquals(Set.of(moved), unassigned(a).keySet());
		assertWithoutPlace(unassigned(a).get(moved), "Bert");
		assertDoubleSale(b, moved, seat);
		assertTrue(a.open().isEmpty(), "der Gewinner hat keinen Konflikt: " + a.open());
		// beide Repositories haben die Zusammenführung eingearbeitet
		assertModelShowsDoubleSale(a, 1, "Anna", bertId, "Bert");
		assertModelShowsDoubleSale(b, 1, "Anna", bertId, "Bert");
	}

	/**
	 * B verkauft zwei Plätze in einem Speichervorgang, A hat beide schon verkauft: Jede Zusammenführung hat ihren eigenen
	 * Speichervorgang, die Vermerke nennen aber B's verlorenen, und der Dialog zeigt beide Zeilen in einer Gruppe.
	 */
	@Test
	void doubleSalesOfOneSaveFormOneGroupInTheDialog() throws IOException {
		Machine[] m = machines("a", "b");
		Machine a = m[0];
		Machine b = m[1];
		setUp(a, b);
		a.seat(1).setLastName("Anna");
		a.sell(2, "Anna");
		b.seat(1).setLastName("Bert");
		b.sell(2, "Bert");
		String bertSave = b.sync.unitState(seatUnit(b, 1)).current().saveId();
		a.tick();
		b.tick();

		untilQuiet(a, b);

		assertConverged(a, b);
		List<OpenConflict> open = b.open();
		assertEquals(2, open.size(), open.toString());
		assertEquals(Set.of(seatUnit(b, 1), seatUnit(b, 2)), Set.of(open.get(0).unit(), open.get(1).unit()));
		assertNotEquals(open.get(0).saveId(), open.get(1).saveId(), "jede Zusammenführung hat ihren Speichervorgang");
		List<ConflictList.Row> rows = new ConflictList(b::open, unit -> unit.relativePath(), (conflict, group) -> false,
				hallId -> null).rows();
		assertEquals(List.of(bertSave, bertSave), rows.stream().map(ConflictList.Row::group).toList());
	}

	/**
	 * Der Verlierer arbeitet seine eigene Zusammenführung ein, sobald sie geschrieben ist, noch bevor der Gewinner sie hat:
	 * Auf dem Sitz steht A's Buchung, B's Buchung "ohne Platz", und nichts gilt als ungespeichert.
	 */
	@Test
	void loserIncorporatesOwnMergeAndShowsBookingWithoutPlace() throws IOException {
		Machine[] m = machines("a", "b");
		Machine a = m[0];
		Machine b = m[1];
		setUp(a, b);
		a.sell(1, "Anna");
		String bertId = b.sell(1, "Bert").getId();
		a.tick();
		b.tick();
		UnitKey seat = seatUnit(a, 1);

		for (int round = 0; round < MAX_ROUNDS && notes(b, seat) == 0; round++) {
			clock.advance(ROUND);
			b.tick();
		}

		assertEquals(1, notes(b, seat), "B hat zusammengeführt");
		assertEquals(0, notes(a, seat), "A hat die Zusammenführung noch nicht");
		assertModelShowsDoubleSale(b, 1, "Anna", bertId, "Bert");
	}

	/**
	 * Review Focus 2: Der Gewinner ändert den Kommentar seiner Buchung, ohne zu speichern. Die Zusammenführung des Verlierers
	 * hat auf dem Sitz denselben Inhalt wie sein Stand: Sie geht still in den Vergleichsstand, ohne Zurückstellen und ohne
	 * das Modell anzufassen, und sein nächstes Speichern baut auf ihr auf, statt erneut zu gabeln.
	 */
	@Test
	void winnerWithUnsavedEditAdoptsTheLosersMergeSilently() throws IOException {
		Machine[] m = machines("a", "b");
		Machine a = m[0];
		Machine b = m[1];
		setUp(a, b);
		Booking anna = a.sell(1, "Anna");
		b.sell(1, "Bert");
		anna.setComment("Notiz");
		a.tick();
		b.tick();

		untilQuiet(a, b);

		UnitKey seat = seatUnit(a, 1);
		VersionId merge = a.sync.unitState(seat).head().id();
		assertEquals(1, notes(a, seat), "B's Zusammenführung ist beim Gewinner");
		assertNotNull(a.sync.unitState(seat).head().conflict(), "das letzte Glied ist die Zusammenführung");
		assertEquals(merge, a.repository.knownUnit(seat).version(), "der Vergleichsstand hat die Zusammenführung");
		assertEquals(List.of(), a.repository.retryDeferred().deferred(), "nichts zurückgestellt, kein Hinweis");
		assertEquals("Anna", anna.getLastName());
		assertEquals("Notiz", anna.getComment(), "die Eingabe bleibt");
		assertEquals(Set.of(seat), a.repository.locallyChangedUnits(), "ungespeichert ist nur der Kommentar");

		a.repository.saveEvent(a.event);

		Version saved = a.sync.unitState(seat).current();
		assertEquals(List.of(merge), saved.parents(), "auf der Zusammenführung, nicht auf der alten Version");
		assertEquals("Notiz", saved.payload().get("comment").asText());
		untilQuiet(a, b);
		assertConverged(a, b);
		assertTrue(a.open().isEmpty(), a.open().toString());
	}

	/**
	 * Der Verlierer ändert seine Buchung, ohne zu speichern, als seine Zusammenführung eintrifft: Sie wird zurückgestellt,
	 * aber still; den Konflikt zeigt der Dialog, nicht die Fußzeile ("auf Rechner b geändert" wäre falsch).
	 */
	@Test
	void deferredOwnMergeShowsNoHint() throws IOException {
		Machine[] m = machines("a", "b");
		Machine a = m[0];
		Machine b = m[1];
		setUp(a, b);
		a.sell(1, "Anna");
		Booking bert = b.sell(1, "Bert");
		bert.setComment("Notiz");
		a.tick();
		b.tick();

		untilQuiet(a, b);

		UnitKey seat = seatUnit(a, 1);
		List<Deferral> deferred = b.repository.retryDeferred().deferred();
		assertEquals(1, deferred.size(), deferred.toString());
		assertEquals(seat, deferred.get(0).unit());
		assertEquals(b.sync.unitState(seat).current().id(), deferred.get(0).version());
		assertTrue(deferred.get(0).silent(), "eine eigene Zusammenführung bekommt keinen Hinweis");
		assertEquals("Bert", bert.getLastName(), "die Eingabe bleibt");
		assertEquals("Notiz", bert.getComment());
		String bertId = bert.getId();
		UnitKey moved = unassignedUnit(b, bertId);
		OpenConflict sale = assertDoubleSale(b, moved, seat);
		// solange zurückgestellt, zeigt B's Modell Bert zweimal: auf dem Sitz (mit der Eingabe) und "ohne Platz"
		assertEquals(2, b.friday().getBookings().stream().filter(booking -> bertId.equals(booking.getId())).count());

		// B speichert seine Eingabe auf der alten Version; sie tritt gegen die Zusammenführung an
		b.repository.saveEvent(b.event);
		untilQuiet(a, b);

		assertConverged(a, b);
		assertEquals("Anna", lastName(a.eventState().get(seat)));
		assertEquals(Set.of(moved), carriers(a, bertId), "Bert lebt in genau einer Einheit");
		assertEquals("Notiz", a.eventState().get(moved).payload().get("comment").asText(), "die Eingabe ist gespeichert");
		assertEquals(List.of(sale.key()), b.open().stream().map(OpenConflict::key).toList(), b.open().toString());
		assertTrue(a.open().isEmpty(), a.open().toString());
		assertModelShowsDoubleSale(b, 1, "Anna", bertId, "Bert");
	}

	/**
	 * Absicherung Aufgabe 14: Während B's Zusammenführung zurückgestellt ist, zeigt B's Modell Bert zweimal (auf dem Sitz mit
	 * der Eingabe und "ohne Platz"). A storniert Bert "ohne Platz". Nach dem Einarbeiten und B's Speichern lebt keine falsche
	 * Kopie von Bert, B's Eingabe geht nicht still verloren (Vermerk an B), und nichts bleibt gegabelt.
	 */
	@Test
	void foreignCancellationDuringDeferralLeavesNoFalseCopy() throws IOException {
		Machine[] m = machines("a", "b");
		Machine a = m[0];
		Machine b = m[1];
		setUp(a, b);
		a.sell(1, "Anna");
		Booking bert = b.sell(1, "Bert");
		String bertId = bert.getId();
		bert.setComment("Notiz");
		a.tick();
		b.tick();
		untilQuiet(a, b);
		UnitKey seat = seatUnit(a, 1);
		UnitKey moved = unassignedUnit(b, bertId);
		assertEquals(1, b.repository.retryDeferred().deferred().size(), "B's Zusammenführung ist zurückgestellt");
		assertEquals(2, b.friday().getBookings().stream().filter(booking -> bertId.equals(booking.getId())).count());

		Booking aBert = a.friday().getBookings().stream().filter(booking -> bertId.equals(booking.getId())).findFirst()
				.orElseThrow();
		a.friday().getBookings().remove(aBert);
		a.repository.saveEvent(a.event);
		untilQuiet(a, b);
		// die Stornierung trifft bei B die Kopie "ohne Platz"; die Eingabe auf dem Sitz bleibt, weiter zurückgestellt
		List<Booking> bBerts = b.friday().getBookings().stream().filter(booking -> bertId.equals(booking.getId())).toList();
		assertEquals(1, bBerts.size(), bBerts.toString());
		assertFalse(bBerts.get(0).isUnassigned());
		assertEquals("Notiz", bBerts.get(0).getComment(), "die ungespeicherte Eingabe bleibt");
		assertEquals(1, b.repository.retryDeferred().deferred().size());
		b.repository.saveEvent(b.event);
		untilQuiet(a, b);

		assertConverged(a, b);
		assertTrue(a.eventState().get(moved).deleted(), "A's Stornierung gilt");
		assertEquals("Anna", lastName(a.eventState().get(seat)));
		assertEquals(Set.of(), carriers(a, bertId), "keine falsche Kopie von Bert");
		assertTrue(b.open().stream().anyMatch(conflict -> conflict.note() != null
				&& conflict.note().kind() == ConflictNote.Kind.DELETE_WINS && conflict.unit().equals(seat)),
				"B's Eingabe weicht mit Vermerk: " + b.open());
		for (Machine machine : m) {
			assertEquals(0, machine.friday().getBookings().stream().filter(booking -> bertId.equals(booking.getId())).count(),
					machine.name);
			assertEquals("Anna", machine.seat(1).getLastName(), machine.name);
			assertEquals(Set.of(), machine.repository.locallyChangedUnits(), machine.name);
			assertEquals(List.of(), machine.repository.retryDeferred().deferred(), machine.name);
		}
	}

	/**
	 * KARV-118, zwei Rechner: A verschiebt Müller von Hand und ändert ihn (ungespeichert), B storniert ihn auf dem alten
	 * Sitz. Das Storno wird bei A zurückgestellt und beim Speichern vergessen; die Auflösung am alten Sitz (verschoben gegen
	 * storniert) lässt es trotzdem gelten.
	 */
	@Test
	void moveByHandAgainstACancellationLosesWithANoteAfterSaving() throws IOException {
		Machine[] m = machines("a", "b");
		Machine a = m[0];
		Machine b = m[1];
		setUp(a, b);
		Booking muller = a.seat(0);
		String mullerId = muller.getId();
		assertTrue(BookingSync.assign(a.friday(), a.plan(), muller, a.plan().getSeats().get(1)));
		muller.setFirstName("Hans");
		b.seat(0).clearCustomerData();
		b.repository.saveEvent(b.event);
		for (int round = 0; round < 4 && a.repository.retryDeferred().deferred().isEmpty(); round++) {
			clock.advance(ROUND);
			b.tick();
			clock.advance(ROUND);
			a.tick();
		}
		assertEquals(List.of(seatUnit(a, 0)), a.repository.retryDeferred().deferred().stream().map(Deferral::unit).toList(),
				"das Storno wartet auf A's Eingabe");

		a.repository.saveEvent(a.event);
		untilQuiet(a, b);

		assertCancellationWinsWithRestorableNote(a, mullerId, m);
		for (Machine machine : m) {
			assertEquals(mullerId, machine.seat(1).getId(), machine.name + ": wiederhergestellt auf A's neuem Sitz");
		}
	}

	/**
	 * KARV-118, zwei Rechner: B storniert Müller und stellt danach die Vorstellung auf eine andere Halle um. A bekommt zuerst
	 * nur die Umstellung (Müller wird versetzt, ohne Sitz in der neuen Bestuhlung "ohne Platz") und ändert Müller
	 * ungespeichert; das Storno wird zurückgestellt, dann speichert A.
	 */
	@ParameterizedTest
	@ValueSource(booleans = { true, false })
	void editOfABookingMovedByASwitchLosesToTheCancellationBeforeIt(boolean withSeats) throws IOException {
		Machine[] m = machines("a", "b");
		Machine a = m[0];
		Machine b = m[1];
		setUp(a, b);
		Hall other = secondHall(withSeats, m);
		String mullerId = a.seat(0).getId();
		UnitKey presentation = UnitKey.presentation(a.event.getId(), a.friday().getId());
		b.seat(0).clearCustomerData();
		b.repository.saveEvent(b.event);
		b.friday().setHallId(other.getId());
		BookingSync.switchPlan(b.friday(), other.getSeatingPlans().get(0));
		b.repository.saveEvent(b.event);
		clock.advance(ROUND);
		b.tick();
		// A arbeitet die Meldungen von Hand ein, um das Zeitfenster zu treffen
		a.events.halls = null;
		clock.advance(ROUND);
		a.tick();
		Booking moved = editMovedBooking(a, mullerId, presentation);
		assertEquals(!withSeats, moved.isUnassigned());
		String newSeatId = moved.getSeatId();

		saveAfterDeferral(a, m);

		assertCancellationWinsWithRestorableNote(a, mullerId, m);
		for (Machine machine : m) {
			Booking restored = booking(machine, mullerId);
			assertEquals(!withSeats, restored.isUnassigned(), machine.name);
			if (withSeats) {
				assertEquals(newSeatId, restored.getSeatId(), machine.name + ": wiederhergestellt auf A's neuem Sitz");
			}
		}
	}

	/**
	 * KARV-118, zwei Rechner: A ändert Müller auf seinem Sitz (ungespeichert). B storniert Müller und löscht danach dessen
	 * Tisch aus der Halle. Bei A kommt zuerst die Halle an, und der Abgleich setzt Müller "ohne Platz"; dann kommt das
	 * Storno und wird zurückgestellt, und A speichert.
	 */
	@Test
	void editOfABookingDerivedAwayAfterwardsLosesToTheCancellation() throws IOException {
		Machine[] m = machines("a", "b");
		Machine a = m[0];
		Machine b = m[1];
		setUp(a, b);
		Booking muller = a.seat(0);
		String mullerId = muller.getId();
		muller.setFirstName("Hans");
		b.seat(0).clearCustomerData();
		b.repository.saveEvent(b.event);
		b.plan().deleteTable(b.plan().getTables().get(0));
		b.halls.saveHall(b.hall);
		clock.advance(ROUND);
		b.tick();
		// A arbeitet die Meldungen von Hand ein: zuerst die Halle samt Abgleich wie die App, dann das Event
		a.events.halls = null;
		clock.advance(ROUND);
		a.tick();
		a.hall = a.halls.reloadHalls().get(0);
		a.repository.deriving(a.event, () -> BookingSync.sync(a.friday(), a.plan()));
		assertTrue(muller.isUnassigned(), "der Abgleich setzt Müller ohne Platz");
		assertEquals("Hans", muller.getFirstName());

		saveAfterDeferral(a, m);

		assertCancellationWinsWithRestorableNote(a, mullerId, m);
		for (Machine machine : m) {
			assertTrue(booking(machine, mullerId).isUnassigned(), machine.name + ": wiederhergestellt ohne Platz");
		}
	}

	/**
	 * KARV-118, drei Rechner: C stellt die Vorstellung auf eine andere Halle um, B kennt die Umstellung nicht und storniert
	 * Müller auf dem alten Sitz. A bekommt zuerst nur die Umstellung (Müller wird versetzt) und ändert Müller ungespeichert;
	 * C's neuer Sitz und das Storno werden zurückgestellt, dann speichert A. Mit {@code othersResolveFirst} sind B und C sich
	 * am alten Sitz schon einig (die Kopie ist gelöscht), bevor A speichert; früher galt A's Erstfassung am neuen Sitz dann
	 * als neuer Verkauf, und das Storno ging verloren. Sonst speichert A vorher, und A's Eingabe wich mit dem falschen
	 * Vermerk.
	 */
	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void editOfABookingMovedByAForeignSwitchLosesToACancellationWithNote(boolean othersResolveFirst) throws IOException {
		Machine[] m = machines("a", "b", "c");
		Machine a = m[0];
		Machine b = m[1];
		Machine c = m[2];
		setUp(a, b, c);
		Hall other = secondHall(true, m);
		String mullerId = a.seat(0).getId();
		String oldSeatId = a.seat(0).getSeatId();
		UnitKey presentation = UnitKey.presentation(a.event.getId(), a.friday().getId());
		// A arbeitet die Meldungen von Hand ein, um das Zeitfenster zu treffen
		a.events.halls = null;
		c.friday().setHallId(other.getId());
		BookingSync.switchPlan(c.friday(), other.getSeatingPlans().get(0));
		c.repository.saveEvent(c.event);
		b.seat(0).clearCustomerData();
		b.repository.saveEvent(b.event);
		clock.advance(ROUND);
		c.tick();
		b.tick();
		if (othersResolveFirst) {
			untilQuiet(b, c);
		}
		for (int round = 0; round < 3; round++) {
			clock.advance(ROUND);
			a.tick();
		}
		Booking moved = editMovedBooking(a, mullerId, presentation);
		assertNotEquals(oldSeatId, moved.getSeatId(), "Müller ist versetzt");
		UnitKey newSeat = UnitKey.booking(a.event.getId(), a.friday().getId(), moved.getSeatId());

		saveAfterDeferral(a, m);

		// KARV-93: je nach Verlauf steht der Vermerk am alten Sitz (gegabelte Quelle) oder am neuen; er wirkt am neuen
		assertEquals(List.of(newSeat), a.open().stream().map(OpenConflict::target).toList(), a.open().toString());
		assertCancellationWinsWithRestorableNote(a, mullerId, m);
		for (Machine machine : m) {
			assertEquals(newSeat.bookingDir(), booking(machine, mullerId).getSeatId(), machine.name);
		}
	}

	/**
	 * Zwei Rechner: B stellt die Vorstellung auf eine andere Halle um. A bekommt zuerst nur die Umstellung (Müller wird
	 * versetzt) und storniert Müller auf dem neuen Sitz, bevor dessen Einheit eintrifft. Mit {@code saveFirst} speichert A
	 * vorher, sonst trifft der Rest erst ein und A speichert danach.
	 */
	@ParameterizedTest
	@ValueSource(booleans = { true, false })
	void cancellationOfABookingMovedByAForeignSwitchSurvives(boolean saveFirst) throws IOException {
		Machine[] m = machines("a", "b");
		Machine a = m[0];
		Machine b = m[1];
		setUp(a, b);
		Hall other = secondHall(true, m);
		String mullerId = a.seat(0).getId();
		UnitKey presentation = UnitKey.presentation(a.event.getId(), a.friday().getId());
		b.friday().setHallId(other.getId());
		BookingSync.switchPlan(b.friday(), other.getSeatingPlans().get(0));
		b.repository.saveEvent(b.event);
		clock.advance(ROUND);
		b.tick();
		// A arbeitet die Meldungen von Hand ein, um das Zeitfenster zu treffen
		a.events.halls = null;
		clock.advance(ROUND);
		a.tick();
		a.repository.applyForeign(List.of(presentation));
		Booking moved = booking(a, mullerId);
		UnitKey newSeat = UnitKey.booking(a.event.getId(), a.friday().getId(), moved.getSeatId());
		moved.clearCustomerData();
		assertEquals(Set.of(), carriersInModel(a, mullerId), "storniert");

		if (saveFirst) {
			a.repository.saveEvent(a.event);
			a.repository.applyForeign(List.copyOf(a.eventState().keySet()));
		} else {
			a.repository.applyForeign(List.copyOf(a.eventState().keySet()));
			assertEquals(Set.of(), carriersInModel(a, mullerId), "das Storno bleibt im Modell");
			a.repository.saveEvent(a.event);
		}
		a.events.halls = a.halls;
		untilQuiet(m);

		assertConverged(m);
		assertEquals(Set.of(), carriers(a, mullerId), "das Storno gilt");
		assertEquals(List.of(), a.open(), "A");
		if (saveFirst) {
			// A's Löschung am alten Sitz gabelt gegen B's Umstellung: verschoben gegen storniert, das Storno gewinnt
			List<OpenConflict> open = b.open();
			assertEquals(1, open.size(), open.toString());
			assertEquals(ConflictNote.Kind.DELETE_WINS, open.get(0).note().kind());
			assertEquals(newSeat, open.get(0).note().moved(), "B kann Müller auf dem neuen Sitz wiederherstellen");
		} else {
			assertEquals(List.of(), b.open(), "die Löschung setzt auf B's Fassung auf: keine Gabelung");
		}
		for (Machine machine : m) {
			assertEquals(Set.of(), carriersInModel(machine, mullerId), machine.name);
			assertEquals(Set.of(), machine.repository.locallyChangedUnits(), machine.name);
			assertEquals(List.of(), machine.repository.retryDeferred().deferred(), machine.name);
		}
	}

	/**
	 * KARV-119, wie {@link #cancellationOfABookingMovedByAForeignSwitchSurvives}, aber B ändert Müller nach der Umstellung
	 * noch einmal und speichert erneut: Mit {@code moved} setzt B ihn von Hand auf den anderen neuen Sitz, sonst heißt er
	 * danach "Meier". Bei A trifft also nicht der Stand ein, den der Abgleich abgeleitet hat. "Meier" weicht mit Vermerk bei
	 * B. Das Versetzen von Hand geht im Storno auf, wenn A nach dem Eintreffen speichert; speichert A vorher, folgt die
	 * Auflösung am alten Sitz dem zweiten Umzug (KARV-120), und es weicht ebenfalls mit Vermerk.
	 */
	@ParameterizedTest
	@CsvSource({ "false,false", "false,true", "true,false", "true,true" })
	void cancellationOfABookingMovedByAForeignSwitchSurvivesALaterForeignChange(boolean moved, boolean saveFirst)
			throws IOException {
		Machine[] m = machines("a", "b");
		Machine a = m[0];
		Machine b = m[1];
		setUp(a, b);
		Hall other = secondHall(true, m);
		SeatingPlan otherPlan = other.getSeatingPlans().get(0);
		String mullerId = a.seat(0).getId();
		UnitKey presentation = UnitKey.presentation(a.event.getId(), a.friday().getId());
		b.friday().setHallId(other.getId());
		BookingSync.switchPlan(b.friday(), otherPlan);
		b.repository.saveEvent(b.event);
		Booking atB = booking(b, mullerId);
		if (moved) {
			String target = otherPlan.getSeats().stream().map(seat -> seat.getId())
					.filter(id -> !id.equals(atB.getSeatId())).findFirst().orElseThrow();
			assertTrue(BookingSync.assign(b.friday(), otherPlan, atB, otherPlan.findSeat(target)));
		} else {
			atB.setLastName("Meier");
		}
		b.repository.saveEvent(b.event);
		clock.advance(ROUND);
		b.tick();
		// A arbeitet die Meldungen von Hand ein, um das Zeitfenster zu treffen
		a.events.halls = null;
		clock.advance(ROUND);
		a.tick();
		a.repository.applyForeign(List.of(presentation));
		booking(a, mullerId).clearCustomerData();

		if (saveFirst) {
			a.repository.saveEvent(a.event);
			a.repository.applyForeign(List.copyOf(a.eventState().keySet()));
		} else {
			a.repository.applyForeign(List.copyOf(a.eventState().keySet()));
			assertEquals(Set.of(), carriersInModel(a, mullerId), "das Storno bleibt im Modell");
			a.repository.saveEvent(a.event);
		}
		a.repository.retryDeferred();
		a.events.halls = a.halls;
		untilQuiet(m);

		assertConverged(m);
		assertEquals(Set.of(), carriers(a, mullerId), "das Storno gilt");
		assertEquals(List.of(), a.open(), "A");
		List<OpenConflict> open = b.open();
		assertEquals(moved && !saveFirst ? 0 : 1, open.size(), open.toString());
		if (!open.isEmpty()) {
			assertEquals(ConflictNote.Kind.DELETE_WINS, open.get(0).note().kind(), "B's Änderung weicht mit Vermerk");
		}
		for (Machine machine : m) {
			assertEquals(Set.of(), carriersInModel(machine, mullerId), machine.name);
			assertEquals(Set.of(), machine.repository.locallyChangedUnits(), machine.name);
			assertEquals(List.of(), machine.repository.retryDeferred().deferred(), machine.name);
		}
	}

	/**
	 * KARV-120, zwei Rechner: A storniert Müller und speichert. B verschiebt ihn zur gleichen Zeit zweimal hintereinander und
	 * speichert jedes Mal. Das Storno gilt auf beiden Rechnern; Bs Verschieben weicht mit Vermerk.
	 */
	@Test
	void cancellationWinsAgainstTwoMoves() throws IOException {
		Machine[] m = machines("a", "b");
		Machine a = m[0];
		Machine b = m[1];
		setUp(a, b);
		String mullerId = a.seat(0).getId();
		a.seat(0).clearCustomerData();
		a.repository.saveEvent(a.event);
		Booking atB = booking(b, mullerId);
		assertTrue(BookingSync.assign(b.friday(), b.plan(), atB, b.plan().getSeats().get(1)));
		b.repository.saveEvent(b.event);
		assertTrue(BookingSync.assign(b.friday(), b.plan(), atB, b.plan().getSeats().get(2)));
		b.repository.saveEvent(b.event);

		untilQuiet(m);

		assertConverged(m);
		assertEquals(Set.of(), carriers(a, mullerId), "das Storno gilt");
		assertEquals(List.of(), a.open(), "A");
		List<OpenConflict> open = b.open();
		assertEquals(1, open.size(), open.toString());
		assertEquals(ConflictNote.Kind.DELETE_WINS, open.get(0).note().kind());
		assertEquals(seatUnit(b, 2), open.get(0).note().moved(), "B stellt Müller auf dem letzten Sitz wieder her");
		for (Machine machine : m) {
			assertEquals(Set.of(), carriersInModel(machine, mullerId), machine.name);
			assertEquals(Set.of(), machine.repository.locallyChangedUnits(), machine.name);
			assertEquals(List.of(), machine.repository.retryDeferred().deferred(), machine.name);
		}
	}

	/** Die Sitze (bzw. "ohne Platz") der Buchungen mit Daten und dieser Kennung im Modell des Rechners. */
	private static Set<String> carriersInModel(Machine machine, String bookingId) {
		Set<String> seats = new HashSet<>();
		machine.friday().getBookings().stream().filter(x -> x.hasData() && bookingId.equals(x.getId()))
				.forEach(x -> seats.add(String.valueOf(x.getSeatId())));
		return seats;
	}

	/**
	 * Das Storno der Buchung gilt auf allen Rechnern. {@code loser} hat genau einen offenen Konflikt, den Vermerk
	 * "Gelöscht" mit seinem Stand (Vorname "Hans"), und nichts ist ungespeichert oder zurückgestellt. "Buchung
	 * wiederherstellen" bringt diesen Stand auf allen Rechnern zurück.
	 */
	private void assertCancellationWinsWithRestorableNote(Machine loser, String bookingId, Machine... machines)
			throws IOException {
		assertConverged(machines);
		assertEquals(Set.of(), carriers(loser, bookingId), "das Storno gilt");
		List<OpenConflict> open = loser.open();
		assertEquals(1, open.size(), open.toString());
		OpenConflict conflict = open.get(0);
		assertEquals(ConflictNote.Kind.DELETE_WINS, conflict.note().kind(), open.toString());
		// die verworfene Version liegt auf dem Ziel des Vermerks; dort sucht und schreibt das Wiederherstellen
		assertEquals("Hans", loser.sync.version(conflict.target(), conflict.note().dropped()).payload().get("firstName")
				.asText(), "der Vermerk trägt den eigenen Stand zum Wiederherstellen");
		for (Machine machine : machines) {
			assertTrue(machine.friday().getBookings().stream().noneMatch(x -> bookingId.equals(x.getId())), machine.name);
			assertEquals(Set.of(), machine.repository.locallyChangedUnits(), machine.name);
			assertEquals(List.of(), machine.repository.retryDeferred().deferred(), machine.name);
		}

		List<ConflictList.Row> rows = new ConflictList(loser::open, unit -> unit.relativePath(),
				(adopted, group) -> false, hallId -> null).rows();
		assertEquals(1, rows.size(), rows.toString());
		ConflictList.Row row = rows.get(0);
		assertEquals(ConflictList.Action.RESTORE_BOOKING, row.action());
		new ConflictActions(loser.repository, loser.halls, new ChangeTracker<>(Event::getId),
				new ChangeTracker<>(Hall::getId), new EditingUnits(), loser.sync, event -> {
					loser.repository.saveEvent(event);
					return true;
				}, hall -> true).restoreBooking(row);
		untilQuiet(machines);

		assertConverged(machines);
		assertEquals(List.of(), loser.open());
		for (Machine machine : machines) {
			assertEquals("Hans", booking(machine, bookingId).getFirstName(), machine.name);
		}
	}

	/** Die Buchung mit dieser Kennung im Modell des Rechners. */
	private static Booking booking(Machine machine, String bookingId) {
		return machine.friday().getBookings().stream().filter(x -> bookingId.equals(x.getId())).findFirst()
				.orElseThrow(() -> new AssertionError(machine.name + ": die Buchung fehlt"));
	}

	/**
	 * Bei {@code machine} kommt nur die Vorstellung an (der Abgleich versetzt die Buchung), dann ändert der Nutzer den
	 * Vornamen der Buchung ungespeichert in "Hans". Der Rechner arbeitet nichts selbsttätig ein ({@code events.halls} ist
	 * {@code null}).
	 */
	private static Booking editMovedBooking(Machine machine, String bookingId, UnitKey presentation) {
		machine.repository.applyForeign(List.of(presentation));
		Booking moved = booking(machine, bookingId);
		moved.setFirstName("Hans");
		return moved;
	}

	/**
	 * Bei {@code machine} kommt alles Übrige an und wartet auf die Eingabe des Nutzers; dann speichert er, arbeitet wieder
	 * selbsttätig ein, und alle Rechner takten, bis Ruhe herrscht.
	 */
	private void saveAfterDeferral(Machine machine, Machine... machines) throws IOException {
		assertFalse(machine.repository.applyForeign(List.copyOf(machine.eventState().keySet())).deferred().isEmpty(),
				"die fremden Stände warten auf die Eingabe");
		assertEquals("Hans", machine.friday().getBookings().stream().filter(Booking::hasData).findFirst().orElseThrow()
				.getFirstName(), "die Eingabe bleibt");
		machine.repository.saveEvent(machine.event);
		machine.events.halls = machine.halls;
		untilQuiet(machines);
	}

	/**
	 * Eine zweite Halle "Bürgerhaus", angelegt vom ersten Rechner und bei allen angekommen; die Repositories aller Rechner
	 * finden ihre Hallen über ihr Hallen-Repository. Mit {@code withSeats} hat ihre Bestuhlung eine Reihe A mit einem Tisch
	 * zu 2 Sitzen, sonst keinen Sitz.
	 */
	private Hall secondHall(boolean withSeats, Machine... machines) {
		Hall other = new Hall();
		other.setName("Bürgerhaus");
		SeatingPlan plan = other.addSeatingPlan();
		if (withSeats) {
			TableRow row = plan.addRow();
			row.setLabel("A");
			plan.fillRow(row, 1, 2);
		}
		machines[0].halls.saveHall(other);
		machines[0].tick();
		clock.advance(ROUND);
		for (Machine machine : machines) {
			if (machine != machines[0]) {
				machine.tick();
			}
			machine.repository.setHallLookup(machine.halls::findById);
		}
		return other;
	}

	/** Die lebenden Einheiten des Events, deren Buchung diese Kennung trägt. */
	private static Set<UnitKey> carriers(Machine machine, String bookingId) {
		Set<UnitKey> carriers = new HashSet<>();
		machine.eventState().forEach((unit, snapshot) -> {
			if (!snapshot.deleted() && snapshot.payload() != null && snapshot.payload().has("id")
					&& bookingId.equals(snapshot.payload().get("id").asText())) {
				carriers.add(unit);
			}
		});
		return carriers;
	}

	/**
	 * B kennt die Niederlage schon (sie steht in seinem Index), wird beendet und startet neu, während der gemeinsame Ordner
	 * fehlt: Der Start löst lokal auf, binnen der 5 Minuten ist genau ein Doppelverkauf offen und das Modell zeigt ihn. Nach
	 * der Rückkehr bleibt es ein Vermerk und ein offener Konflikt, und alle Rechner laufen zusammen.
	 */
	@Test
	void offlineStartWithKnownLossResolvesAndShowsOneConflict() throws IOException {
		Machine[] m = machines("a", "b");
		Machine a = m[0];
		Machine b = m[1];
		setUp(a, b);
		a.sell(1, "Anna");
		String bertId = b.sell(1, "Bert").getId();
		a.tick();
		clock.advance(ROUND);
		b.tick();
		UnitKey seat = seatUnit(a, 1);
		assertEquals(1, b.sync.unitState(seat).lost().size(), "B hat verloren und wartet noch auf A's Liste");
		assertTrue(b.open().isEmpty());

		Machine restarted = b.restartOffline();

		UnitKey moved = unassignedUnit(restarted, bertId);
		OpenConflict conflict = assertDoubleSale(restarted, moved, seat);
		assertModelShowsDoubleSale(restarted, 1, "Anna", bertId, "Bert");
		clock.advance(ROUND);
		restarted.tick();
		assertEquals(SyncState.OFFLINE, restarted.sync.syncState().get());
		assertEquals(List.of(conflict), restarted.open(), "ohne Verbindung bleibt es ein Konflikt");

		restarted.down.set(false);
		untilQuiet(a, restarted);

		assertConverged(a, restarted);
		assertEquals(List.of(conflict.key()), restarted.open().stream().map(OpenConflict::key).toList());
		assertEquals(1, notes(restarted, seat));
		assertEquals(1, notes(a, seat));
		assertTrue(a.open().isEmpty(), a.open().toString());
		assertModelShowsDoubleSale(a, 1, "Anna", bertId, "Bert");
		assertModelShowsDoubleSale(restarted, 1, "Anna", bertId, "Bert");
	}

	/**
	 * Beide ändern dasselbe Feld des Event-Kopfs und dieselbe Feldgruppe der Vorstellung verschieden: Je Einheit entsteht ein
	 * FIELDS-Vermerk aus ihrer eigenen Strategie. Die Gruppe "schedule" (Datum und Uhrzeit) kennt nur
	 * {@link PresentationPolicy}; fehlte eine Strategie, gäbe es nur "Deren" mit einem UNMERGED-Vermerk.
	 */
	@Test
	void headAndPresentationFieldConflictsUseTheirPolicies() throws IOException {
		Machine[] m = machines("a", "b");
		Machine a = m[0];
		Machine b = m[1];
		setUp(a, b);
		a.event.setDescription("Annas Beschreibung");
		a.friday().setTime(LocalTime.of(19, 11));
		a.repository.saveEvent(a.event);
		b.event.setDescription("Berts Beschreibung");
		b.friday().setTime(LocalTime.of(20, 11));
		b.repository.saveEvent(b.event);
		a.tick();
		b.tick();

		untilQuiet(a, b);

		assertConverged(a, b);
		UnitKey head = UnitKey.event(a.event.getId());
		UnitKey presentation = UnitKey.presentation(a.event.getId(), a.friday().getId());
		Map<UnitKey, ConflictNote> notes = new HashMap<>();
		b.open().forEach(conflict -> notes.put(conflict.unit(), conflict.note()));
		assertEquals(Set.of(head, presentation), notes.keySet(), b.open().toString());
		ConflictNote headNote = notes.get(head);
		assertEquals(ConflictNote.Kind.FIELDS, headNote.kind());
		assertEquals(List.of("description"), headNote.fields().stream().map(ConflictNote.FieldConflict::group).toList());
		assertEquals("Berts Beschreibung", headNote.fields().get(0).mine().get("description").asText());
		assertEquals("Annas Beschreibung", headNote.fields().get(0).theirs().get("description").asText());
		ConflictNote presentationNote = notes.get(presentation);
		assertEquals(ConflictNote.Kind.FIELDS, presentationNote.kind());
		assertEquals(List.of("schedule"),
				presentationNote.fields().stream().map(ConflictNote.FieldConflict::group).toList());
		assertTrue(a.open().isEmpty(), a.open().toString());
		// beide Modelle zeigen die Werte des Gewinners
		for (Machine machine : m) {
			assertEquals("Annas Beschreibung", machine.event.getDescription(), machine.name);
			assertEquals(LocalTime.of(19, 11), machine.friday().getTime(), machine.name);
			assertEquals(Set.of(), machine.repository.locallyChangedUnits(), machine.name);
		}
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
		String bertId = b.sell(1, "Bert").getId();
		String carlaId = c.sell(1, "Carla").getId();
		a.tick();
		b.tick();
		c.tick();

		untilQuiet(a, b, c);

		assertConverged(a, b, c);
		UnitKey seat = seatUnit(a, 1);
		assertEquals("Anna", lastName(a.eventState().get(seat)));
		UnitKey bertMoved = unassignedUnit(b, bertId);
		UnitKey carlaMoved = unassignedUnit(c, carlaId);
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
		String bertId = b.sell(1, "Bert").getId();
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
		UnitKey moved = unassignedUnit(b, bertId);
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
		String bertId = b.sell(1, "Bert").getId();
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
		UnitKey moved = unassignedUnit(b, bertId);
		assertEquals(Set.of(moved), unassigned(a).keySet());
		assertWithoutPlace(unassigned(a).get(moved), "Bert");
		assertDoubleSale(b, moved, seat);
		assertTrue(a.open().isEmpty(), a.open().toString());
	}

	/** Setzt die Buchung des Sitzes {@code from} auf den freien Sitz {@code to} um und speichert (ein Speichervorgang). */
	private static Booking move(Machine machine, int from, int to) {
		Booking booking = machine.seat(from);
		assertTrue(BookingSync.assign(machine.friday(), machine.plan(), booking, machine.plan().getSeats().get(to)));
		BookingSync.sync(machine.friday(), machine.plan());
		machine.repository.saveEvent(machine.event);
		return booking;
	}

	/** Die neueste Änderungsliste des Rechners im gemeinsamen Ordner. */
	private static Path newestFeed(Machine machine) throws IOException {
		Path folder = machine.sharedDir.resolve("data").resolve("feeds").resolve(machine.sync.machineId());
		try (var feeds = Files.list(folder)) {
			return feeds.max((x, y) -> Long.compare(feedNumber(x), feedNumber(y))).orElseThrow();
		}
	}

	private static long feedNumber(Path feed) {
		String name = feed.getFileName().toString();
		return Long.parseLong(name.substring(0, name.length() - ".json".length()));
	}

	/**
	 * M4: A setzt Müller um, seine Liste kommt bei B nicht an. B ändert Müller auf dem alten Sitz und verliert gegen A's
	 * Löschung; die Strecke wartet auf die Liste. Nach den 5 Minuten bringt erst ein Vollabgleich A's neuen Sitz, im selben
	 * Takt läuft die Auflösung: Sie sieht den neuen Geschwisterstand und überträgt B's Änderung auf die umgesetzte Buchung,
	 * statt sie als Stornierung zu behandeln (DELETE_WINS).
	 */
	@Test
	void fullSyncBringsForeignMoveBeforeTheLossIsResolved() throws IOException {
		Machine[] m = machines("a", "b");
		Machine a = m[0];
		Machine b = m[1];
		setUp(a, b);
		UnitKey oldSeat = seatUnit(a, 0);
		UnitKey newSeat = seatUnit(a, 2);
		String muellerId = move(a, 0, 2).getId();
		a.tick();
		Path feed = newestFeed(a);
		Path hidden = Files.move(feed, dir.resolve("versteckt.json"));
		b.seat(0).setComment("Notiz");
		b.repository.saveEvent(b.event);
		clock.advance(Duration.ofSeconds(2));
		b.tick();
		assertEquals(1, b.sync.unitState(oldSeat).lost().size(), "B's Änderung hat gegen A's Löschung verloren");
		assertNull(b.sync.unitState(newSeat).current(), "A's neuer Sitz ist bei B noch nicht angekommen");

		// die Wartezeit auf Listen (5 Minuten) ist um, der regelmäßige Vollabgleich (10 Minuten) noch nicht fällig
		clock.advance(Duration.ofMinutes(5).plusSeconds(1));
		b.sync.syncNow();

		assertTrue(warnings.removeIf(record -> record.getMessage().contains("5 Minuten")), "die Strecke wartete aus");
		Files.move(hidden, feed);
		untilQuiet(a, b);
		assertConverged(a, b);
		assertTrue(a.eventState().get(oldSeat).deleted());
		assertEquals(Set.of(newSeat), carriers(a, muellerId), "Müller lebt nur auf dem neuen Sitz");
		assertEquals("Notiz", a.eventState().get(newSeat).payload().get("comment").asText(), "B's Änderung ist übertragen");
		assertTrue(b.open().isEmpty(), "keine Stornierung, die niemand gemacht hat: " + b.open());
		assertTrue(a.open().isEmpty(), a.open().toString());
	}

	/**
	 * Wie M4, aber die Vorstellung hat so viele Buchungen, dass der Vollabgleich sie in zwei Blöcken über zwei Takte abgleicht:
	 * A setzt eine Buchung vom Ordner mit dem kleinsten Namen (1. Block) auf den mit dem größten (2. Block) um. Nach dem
	 * 1. Block kennt B die Löschung der Quelle, aber noch nicht das Ziel; die Wartezeit auf die Liste ist um. Die Auflösung
	 * von B's verlorener Änderung darf nicht in diesem Takt laufen, sonst sähe das Umsetzen wie eine Stornierung aus
	 * (DELETE_WINS); sie kommt im Takt nach dem letzten Block dran und überträgt B's Änderung auf das Ziel.
	 */
	@Test
	void foreignMoveAcrossTwoBlocksIsNotResolvedAsCancellation() throws IOException {
		Machine[] m = machines("a", "b");
		Machine a = m[0];
		Machine b = m[1];
		// 180 Tische zu 3 Sitzen: mit einem freien Sitz mehr, als ein Block (500) Buchungen fasst
		int seats = 540;
		setUp(seats / 3, a, b);
		List<Integer> byFolder = IntStream.range(0, seats).boxed()
				.sorted(Comparator.comparing(n -> a.plan().getSeats().get(n).getId())).toList();
		int from = byFolder.get(0);
		// das Ziel ist der letzte Ordner (nie Müllers Sitz 0, den setUp schon verkauft hat): Es liegt sicher im 2. Block
		int to = byFolder.get(byFolder.size() - 1) != 0 ? byFolder.get(byFolder.size() - 1) : byFolder.get(seats - 2);
		for (int n = 1; n < seats; n++) {
			if (n != to) {
				a.seat(n).setLastName("Gast " + n);
			}
		}
		a.repository.saveEvent(a.event);
		untilQuiet(a, b);
		UnitKey oldSeat = seatUnit(a, from);
		UnitKey newSeat = seatUnit(a, to);
		assertNull(b.sync.unitState(newSeat).current(), "das Ziel ist noch frei");

		String movedId = move(a, from, to).getId();
		a.tick();
		Path feed = newestFeed(a);
		Path hidden = Files.move(feed, dir.resolve("versteckt.json"));
		b.seat(from).setComment("Notiz");
		b.repository.saveEvent(b.event);
		clock.advance(Duration.ofSeconds(2));
		b.tick();
		assertEquals(1, b.sync.unitState(oldSeat).lost().size(), "B's Änderung hat gegen A's Löschung verloren");
		assertNull(b.sync.unitState(newSeat).current(), "A's neuer Sitz ist bei B noch nicht angekommen");

		// die Wartezeit auf Listen (5 Minuten) ist um; jedes Lesen kostet B Zeit, damit ein Takt nach einem Block endet
		clock.advance(Duration.ofMinutes(5).plusSeconds(1));
		AtomicBoolean slow = new AtomicBoolean(true);
		b.layer.onEach("read", () -> {
			if (slow.get()) {
				clock.advance(Duration.ofMillis(20));
			}
		});
		b.sync.syncNow();
		assertNotNull(b.sync.fullSyncProgress().get(), "der 2. Block der Vorstellung steht noch aus");
		assertNull(b.sync.unitState(newSeat).current(), "das Ziel liegt im 2. Block");
		assertEquals(1, b.sync.unitState(oldSeat).lost().size(), "die Auflösung wartet auf den Rest der Vorstellung");
		int ticks = 1;
		while (b.sync.fullSyncProgress().get() != null) {
			TestSyncServices.tick(b.sync);
			assertTrue(++ticks < 20, "der Lauf endet nicht");
		}
		slow.set(false);

		assertTrue(warnings.removeIf(record -> record.getMessage().contains("5 Minuten")), "die Strecke wartete aus");
		Files.move(hidden, feed);
		untilQuiet(a, b);
		assertConverged(a, b);
		assertTrue(a.eventState().get(oldSeat).deleted());
		assertEquals(Set.of(newSeat), carriers(a, movedId), "die Buchung lebt nur auf dem neuen Sitz");
		assertEquals("Notiz", a.eventState().get(newSeat).payload().get("comment").asText(), "B's Änderung ist übertragen");
		assertTrue(b.open().isEmpty(), "keine Stornierung, die niemand gemacht hat: " + b.open());
		assertTrue(a.open().isEmpty(), a.open().toString());
	}

	/**
	 * I1, Platz zuweisen gegen Änderung plus Verkauf (Ruling R31): Mia steht "ohne Platz". B weist ihr Sitz 1 zu und ändert
	 * dabei den Kommentar (ein Speichervorgang: Löschung von {@code _Mia}, Mia auf Sitz 1); A setzt Mia gleichzeitig auf
	 * "bezahlt" und verkauft Sitz 1 an Tom, A lädt zuerst hoch. B verliert auf beiden Einheiten. Danach lebt Mia genau
	 * einmal, "ohne Platz", mit A's und B's Änderung; Sitz 1 trägt Tom; B hat den Doppelverkauf offen (auf {@code _Mia}), A
	 * keinen Konflikt.
	 */
	@Test
	void assigningAPlaceAgainstEditPlusSaleKeepsTheBookingWithoutPlace() throws IOException {
		Machine[] m = machines("a", "b");
		Machine a = m[0];
		Machine b = m[1];
		setUp(a, b);
		Booking mia = a.friday().addBooking();
		mia.setLastName("Mia");
		mia.setUnassigned(true);
		a.repository.saveEvent(a.event);
		String miaId = mia.getId();
		UnitKey miaUnit = unassignedUnit(a, miaId);
		untilQuiet(a, b);
		Booking bMia = b.friday().getBookings().stream().filter(booking -> miaId.equals(booking.getId())).findFirst()
				.orElseThrow();

		assertTrue(BookingSync.assign(b.friday(), b.plan(), bMia, b.plan().getSeats().get(1)));
		bMia.setComment("Fenster");
		b.repository.saveEvent(b.event);
		mia.setPaymentStatus(PaymentStatus.CARD);
		a.seat(1).setLastName("Tom");
		a.repository.saveEvent(a.event);
		a.tick();
		b.tick();

		untilQuiet(a, b);

		assertConverged(a, b);
		UnitKey seat = seatUnit(a, 1);
		assertEquals("Tom", lastName(a.eventState().get(seat)));
		assertEquals(Set.of(miaUnit), carriers(a, miaId), "Mia lebt genau einmal");
		UnitSnapshot kept = a.eventState().get(miaUnit);
		assertWithoutPlace(kept, "Mia");
		assertEquals("CARD", kept.payload().get("paymentStatus").asText(), "A's Änderung");
		assertEquals("Fenster", kept.payload().get("comment").asText(), "B's Änderung beim Zuweisen");
		// KARV-93: der Vermerk steht an der gegabelten Quelle
		assertDoubleSale(b, miaUnit, miaUnit);
		assertTrue(a.open().isEmpty(), "keine Stornierung, die niemand gemacht hat: " + a.open());
		assertModelShowsDoubleSale(a, 1, "Tom", miaId, "Mia");
		assertModelShowsDoubleSale(b, 1, "Tom", miaId, "Mia");
	}

	/**
	 * R31a, Platz zuweisen gegen Stornierung plus Verkauf: Mia steht "ohne Platz". B weist ihr Sitz 1 zu; A storniert Mia
	 * gleichzeitig und verkauft Sitz 1 an Tom, A lädt zuerst hoch. Storno gewinnt: Mia lebt nirgends, Sitz 1 trägt Tom. B
	 * hat genau einen offenen Konflikt, DELETE_WINS auf {@code _Mia} mit A als Gegenseite (kein Doppelverkauf), A keinen.
	 */
	@Test
	void cancellationWinsAgainstAssigningAPlace() throws IOException {
		cancellationAgainstAssigningAPlace(1);
	}

	/**
	 * N2 (R35): wie {@link #cancellationWinsAgainstAssigningAPlace}, aber B ändert Mia auf Sitz 1 in einem zweiten
	 * Speichervorgang, bevor es hochlädt. Die Stornierung gilt trotzdem und kehrt nicht still zurück.
	 */
	@Test
	void cancellationWinsAgainstAssigningAPlaceSavedTwice() throws IOException {
		cancellationAgainstAssigningAPlace(2);
	}

	/** Mia steht "ohne Platz"; B weist ihr Sitz 1 zu (bei zwei Speichervorgängen danach noch ein Kommentar). */
	private static void assignOnB(Machine b, String miaId, int ownSaves) {
		Booking bMia = b.friday().getBookings().stream().filter(booking -> miaId.equals(booking.getId())).findFirst()
				.orElseThrow();
		assertTrue(BookingSync.assign(b.friday(), b.plan(), bMia, b.plan().getSeats().get(1)));
		b.repository.saveEvent(b.event);
		if (ownSaves == 2) {
			bMia.setComment("Fenster");
			b.repository.saveEvent(b.event);
		}
	}

	private void cancellationAgainstAssigningAPlace(int ownSaves) throws IOException {
		Machine[] m = machines("a", "b");
		Machine a = m[0];
		Machine b = m[1];
		setUp(a, b);
		Booking mia = a.friday().addBooking();
		mia.setLastName("Mia");
		mia.setUnassigned(true);
		a.repository.saveEvent(a.event);
		String miaId = mia.getId();
		UnitKey miaUnit = unassignedUnit(a, miaId);
		untilQuiet(a, b);

		assignOnB(b, miaId, ownSaves);
		a.friday().getBookings().remove(mia);
		a.seat(1).setLastName("Tom");
		a.repository.saveEvent(a.event);
		a.tick();
		b.tick();

		untilQuiet(a, b);

		assertConverged(a, b);
		UnitKey seat = seatUnit(a, 1);
		assertEquals("Tom", lastName(a.eventState().get(seat)));
		assertTrue(a.eventState().get(miaUnit).deleted(), "_Mia bleibt storniert");
		assertEquals(Set.of(), carriers(a, miaId), "Mia lebt nirgends");
		List<OpenConflict> open = b.open();
		assertEquals(1, open.size(), open.toString());
		ConflictNote note = open.get(0).note();
		assertEquals(ConflictNote.Kind.DELETE_WINS, note.kind());
		// KARV-93: an der gegabelten Quelle, nicht am Sitz, an dem B verlor; "Wiederherstellen" führt dort ohne Platz zurück
		assertEquals(miaUnit, open.get(0).unit());
		assertEquals(a.sync.machineId(), note.other().machine());
		assertTrue(a.open().isEmpty(), a.open().toString());
		for (Machine machine : m) {
			assertEquals(0, machine.friday().getBookings().stream().filter(booking -> miaId.equals(booking.getId())).count(),
					machine.name);
			assertEquals("Tom", machine.seat(1).getLastName(), machine.name);
			assertEquals(Set.of(), machine.repository.locallyChangedUnits(), machine.name);
			assertEquals(List.of(), machine.repository.retryDeferred().deferred(), machine.name);
		}
	}

	/**
	 * R31b, Platz zuweisen gegen fremden Umzug plus Verkauf: Mia steht "ohne Platz". B weist ihr Sitz 1 zu; A setzt sie
	 * gleichzeitig auf Sitz 4 und verkauft Sitz 1 an Tom, A lädt zuerst hoch. A's Umzug gilt: Mia lebt genau einmal, auf
	 * Sitz 4; Sitz 1 trägt Tom. B hat genau einen offenen Konflikt (FIELDS "place"), A keinen.
	 */
	@Test
	void foreignMoveWinsAgainstAssigningAPlace() throws IOException {
		foreignMoveAgainstAssigningAPlace(1);
	}

	/**
	 * N2 (R35): wie {@link #foreignMoveWinsAgainstAssigningAPlace}, aber B ändert Mia auf Sitz 1 in einem zweiten
	 * Speichervorgang, bevor es hochlädt. A's Umzug gilt trotzdem, und Mia lebt nicht zweimal.
	 */
	@Test
	void foreignMoveWinsAgainstAssigningAPlaceSavedTwice() throws IOException {
		foreignMoveAgainstAssigningAPlace(2);
	}

	private void foreignMoveAgainstAssigningAPlace(int ownSaves) throws IOException {
		Machine[] m = machines("a", "b");
		Machine a = m[0];
		Machine b = m[1];
		setUp(a, b);
		Booking mia = a.friday().addBooking();
		mia.setLastName("Mia");
		mia.setUnassigned(true);
		a.repository.saveEvent(a.event);
		String miaId = mia.getId();
		UnitKey miaUnit = unassignedUnit(a, miaId);
		untilQuiet(a, b);

		assignOnB(b, miaId, ownSaves);
		assertTrue(BookingSync.assign(a.friday(), a.plan(), mia, a.plan().getSeats().get(4)));
		a.seat(1).setLastName("Tom");
		a.repository.saveEvent(a.event);
		a.tick();
		b.tick();

		untilQuiet(a, b);

		assertConverged(a, b);
		UnitKey seat = seatUnit(a, 1);
		UnitKey seat4 = seatUnit(a, 4);
		assertEquals("Tom", lastName(a.eventState().get(seat)));
		assertTrue(a.eventState().get(miaUnit).deleted(), "_Mia bleibt gelöscht");
		assertEquals(Set.of(seat4), carriers(a, miaId), "Mia lebt genau einmal, auf A's Sitz");
		assertEquals("Mia", lastName(a.eventState().get(seat4)));
		List<OpenConflict> open = b.open();
		assertEquals(1, open.size(), open.toString());
		ConflictNote note = open.get(0).note();
		assertEquals(ConflictNote.Kind.FIELDS, note.kind());
		assertEquals(List.of("place"), note.fields().stream().map(ConflictNote.FieldConflict::group).toList());
		assertTrue(a.open().isEmpty(), a.open().toString());
		for (Machine machine : m) {
			List<Booking> mias = machine.friday().getBookings().stream().filter(booking -> miaId.equals(booking.getId()))
					.toList();
			assertEquals(1, mias.size(), machine.name + ": " + mias);
			assertFalse(mias.get(0).isUnassigned(), machine.name);
			assertEquals("Mia", machine.seat(4).getLastName(), machine.name);
			assertEquals("Tom", machine.seat(1).getLastName(), machine.name);
			assertEquals(Set.of(), machine.repository.locallyChangedUnits(), machine.name);
			assertEquals(List.of(), machine.repository.retryDeferred().deferred(), machine.name);
		}
	}

	/**
	 * N1, getrennte Gewinner (R35): Mia steht "ohne Platz". A verkauft Sitz 1 an Tom und lädt hoch; seine Liste kommt bei B
	 * vorerst nicht an. B weist Mia Sitz 1 zu (mit Kommentar) und lädt hoch: B gewinnt {@code _Mia} und verliert Sitz 1,
	 * die Strecke wartet auf A's Liste. Inzwischen setzt A Mia auf "bezahlt", verliert auf {@code _Mia} gegen B's Löschung
	 * und löst auf: B's Löschung war ein Umzug, dessen Ziel verloren hat (KARV-93, E9), also bleibt Mia auf {@code _Mia},
	 * bezahlt und mit B's Kommentar; B bekommt den Doppelverkauf dort. Erst danach löst B Sitz 1 auf und findet das Ergebnis
	 * schon vor. Niemand hat storniert, Mia lebt genau einmal "ohne Platz", A's Änderung weicht nicht.
	 */
	@Test
	void assigningAPlaceWithSplitWinnersKeepsTheBookingOnce() throws IOException {
		Machine[] m = machines("a", "b");
		Machine a = m[0];
		Machine b = m[1];
		setUp(a, b);
		Booking mia = a.friday().addBooking();
		mia.setLastName("Mia");
		mia.setUnassigned(true);
		a.repository.saveEvent(a.event);
		String miaId = mia.getId();
		UnitKey miaUnit = unassignedUnit(a, miaId);
		UnitKey seat = seatUnit(a, 1);
		untilQuiet(a, b);

		a.seat(1).setLastName("Tom");
		a.repository.saveEvent(a.event);
		a.tick();
		Path feed = newestFeed(a);
		Path hidden = Files.move(feed, dir.resolve("versteckt.json"));
		Booking bMia = b.friday().getBookings().stream().filter(booking -> miaId.equals(booking.getId())).findFirst()
				.orElseThrow();
		assertTrue(BookingSync.assign(b.friday(), b.plan(), bMia, b.plan().getSeats().get(1)));
		bMia.setComment("Fenster");
		b.repository.saveEvent(b.event);
		clock.advance(Duration.ofSeconds(2));
		b.tick();
		assertEquals(1, b.sync.unitState(seat).lost().size(), "B hat Sitz 1 verloren, die Strecke wartet auf A's Liste");
		assertTrue(b.sync.unitState(miaUnit).current().deleted(), "B's Löschung von _Mia gilt");

		mia.setPaymentStatus(PaymentStatus.CARD);
		a.repository.saveEvent(a.event);
		for (int round = 0; round < 3; round++) {
			clock.advance(ROUND);
			a.tick();
		}
		assertTrue(a.sync.unitState(miaUnit).lost().isEmpty(), "A hat _Mia aufgelöst");
		assertFalse(a.sync.unitState(miaUnit).current().deleted(), "B's Umzug ist gescheitert, Mia bleibt auf _Mia");

		// die Wartezeit auf Listen ist um: B löst Sitz 1 mit dem auf, was der Vollabgleich bringt (A's Zusammenführung)
		clock.advance(Duration.ofMinutes(5).plusSeconds(1));
		b.sync.syncNow();
		assertTrue(warnings.removeIf(record -> record.getMessage().contains("5 Minuten")), "die Strecke wartete aus");
		Files.move(hidden, feed);
		untilQuiet(a, b);

		assertConverged(a, b);
		assertEquals("Tom", lastName(a.eventState().get(seat)));
		assertEquals(Set.of(miaUnit), carriers(a, miaId), "Mia lebt genau einmal, niemand hat storniert");
		UnitSnapshot kept = a.eventState().get(miaUnit);
		assertWithoutPlace(kept, "Mia");
		assertEquals("Fenster", kept.payload().get("comment").asText(), "B's Änderung beim Zuweisen");
		assertEquals("CARD", kept.payload().get("paymentStatus").asText(), "A's Änderung bleibt");
		assertDoubleSale(b, miaUnit, miaUnit);
		assertEquals(List.of(), a.open(), "A's Änderung weicht nicht");
		assertModelShowsDoubleSale(a, 1, "Tom", miaId, "Mia");
		assertModelShowsDoubleSale(b, 1, "Tom", miaId, "Mia");
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
		assertEquals(Set.of(newSeat), carriers(a, muellerId), "Müller lebt nur auf dem neuen Sitz");
		for (Machine machine : m) {
			assertTrue(machine.open().isEmpty(), machine.name + ": " + machine.open());
			for (UnitKey unit : machine.eventState().keySet()) {
				assertEquals(0, notes(machine, unit), machine.name + ": Vermerk an " + unit.relativePath());
			}
		}
	}
}
