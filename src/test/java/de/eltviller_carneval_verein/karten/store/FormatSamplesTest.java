package de.eltviller_carneval_verein.karten.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.HallObject;
import de.eltviller_carneval_verein.karten.model.PaymentStatus;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.model.Shape;
import de.eltviller_carneval_verein.karten.model.TextAlignH;
import de.eltviller_carneval_verein.karten.model.TextAlignV;
import de.eltviller_carneval_verein.karten.repository.JsonMapperFactory;
import de.eltviller_carneval_verein.karten.repository.StoreEventRepository;
import de.eltviller_carneval_verein.karten.repository.StoreHallRepository;
import de.eltviller_carneval_verein.karten.repository.TestRepositories;
import de.eltviller_carneval_verein.karten.tracking.ChangeTracker;
import de.eltviller_carneval_verein.karten.ui.BookingSyncService;

/**
 * Beispieldateien je Version (KARV-136): Jeder Ordner unter {@code src/test/resources/format-samples} enthält in
 * {@code data.zip} den Datenordner einer früheren Version (siehe die README daneben); entpackt lädt er mit diesem
 * Programm unverändert. Dazu ein Ordner eines
 * „1.2-Rechners“, den der Test selbst erzeugt ({@link FutureWriter}): Zusatzfelder bleiben, gesperrt ist genau, was
 * dieses Programm nicht sicher verarbeiten kann. Regeln: Abschnitt „Forward-compatible data format“ in
 * {@code docs/decisions.md}.
 */
class FormatSamplesTest {

	/** Der Schlüssel des Zusatzfelds, das der 1.2-Rechner in jede Klasse schreibt. */
	private static final String EXTRA = "x-test-feld";
	/** Der Abstand zwischen zwei Takten: länger als das Intervall, in dem ein Dienst neue Rechner sucht. */
	private static final Duration ROUND = Duration.ofSeconds(31);

	/** Was der Dienst meldet, ohne dass es ein Repository einarbeitet. */
	private static final class Recorder implements SyncListener {
		final List<String> errors = new ArrayList<>();
		final Map<UnitKey, UnitState.Status> locked = new HashMap<>();
		final List<String> skipped = new ArrayList<>();

		@Override
		public void error(String message) {
			errors.add(message);
		}

		@Override
		public void locked(UnitKey unit, UnitState.Status status) {
			locked.put(unit, status);
		}

		@Override
		public void skipped(UnitKey unit, boolean subtree, String cause) {
			skipped.add(unit.relativePath() + ": " + cause);
		}

		@Override
		public void duplicateMachine(String message) {
			errors.add(message);
		}
	}

	/** Ein geladener Rechner: Dienst, Repositories und das geladene Modell, vorbereitet wie beim Öffnen in der App. */
	private record Loaded(SyncService sync, Recorder recorder, StoreHallRepository halls, StoreEventRepository events,
			Hall hall, Event event) {

		Presentation friday() {
			return event.getPresentations().get(0);
		}

		Booking booking(String lastName) {
			return friday().getBookings().stream().filter(b -> lastName.equals(b.getLastName())).findFirst()
					.orElseThrow(() -> new AssertionError("keine Buchung " + lastName));
		}

		boolean hasBooking(String lastName) {
			return friday().getBookings().stream().anyMatch(b -> lastName.equals(b.getLastName()));
		}
	}

	@TempDir
	Path dir;

	private final List<LogCapture> logs = new ArrayList<>();
	/** Je mitgeschnittenem Logger, ob er an die übergeordneten weitergab (während des Tests nicht: die Ausgabe bleibt leer). */
	private final Map<Logger, Boolean> parentHandlers = new LinkedHashMap<>();

	@BeforeEach
	void captureLogs() {
		for (Class<?> type : List.of(SyncService.class, StoreEventRepository.class, StoreHallRepository.class)) {
			logs.add(new LogCapture(type));
			Logger logger = Logger.getLogger(type.getName());
			parentHandlers.put(logger, logger.getUseParentHandlers());
			logger.setUseParentHandlers(false);
		}
	}

	@AfterEach
	void closeLogs() {
		logs.forEach(LogCapture::close);
		parentHandlers.forEach(Logger::setUseParentHandlers);
	}

	// Bauteile -->

	/** Die Beispielordner unter {@code format-samples} mit einem {@code data.zip}, nach Name sortiert. */
	private static List<Path> samples() throws IOException, URISyntaxException {
		Path root = Path.of(FormatSamplesTest.class.getResource("/format-samples").toURI());
		try (Stream<Path> list = Files.list(root)) {
			return list.filter(dir -> Files.isRegularFile(dir.resolve(FormatSampleGenerator.ARCHIVE))).sorted()
					.toList();
		}
	}

	/** Entpackt das Archiv eines Beispiels unverändert nach {@code appData} (darin dann {@code data/…}). */
	private static void unzip(Path archive, Path appData) throws IOException {
		Path base = appData.toAbsolutePath().normalize();
		try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
			for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
				Path target = base.resolve(entry.getName()).normalize();
				assertTrue(target.startsWith(base.resolve("data")), "Eintrag außerhalb von data/: " + entry.getName());
				if (entry.isDirectory()) {
					Files.createDirectories(target);
				} else {
					Files.createDirectories(target.getParent());
					Files.copy(zip, target);
				}
			}
		}
	}

	/** Die Anzahl aller Dateien (Versionen, Ansprüche) im lokalen Bestand des Datenordners. */
	private static long files(Path appData) throws IOException {
		try (Stream<Path> walk = Files.walk(appData.resolve("data"))) {
			return walk.filter(Files::isRegularFile).count();
		}
	}

	/** Die Warnungen (und schwerer) aus Dienst und Repositories seit Testbeginn. */
	private List<String> warningsLogged() {
		return logs.stream().flatMap(log -> log.records.stream())
				.filter(r -> r.getLevel().intValue() >= Level.WARNING.intValue()).map(LogRecord::getMessage).toList();
	}

	/**
	 * Lädt Halle und Event wie die App: Repositories mit Hallenabfrage, danach der Abgleich der Buchungen beim Öffnen
	 * ({@link BookingSyncService}). Der Dienst muss gestartet sein.
	 */
	private static Loaded load(SyncService sync, Recorder recorder) {
		StoreHallRepository halls = TestRepositories.halls(sync);
		StoreEventRepository events = TestRepositories.events(sync);
		events.setHallLookup(halls::findById);
		events.setHallCurrent(halls::isCurrent);
		List<Hall> hallList = halls.loadHalls();
		List<Event> eventList = events.loadEvents();
		assertEquals(1, hallList.size(), "Hallen");
		assertEquals(1, eventList.size(), "Events");
		Event event = eventList.get(0);
		BookingSyncService.prepare(event, halls::findById, new ChangeTracker<>(Event::getId), events);
		return new Loaded(sync, recorder, halls, events, hallList.get(0), event);
	}

	/** Prüft den Inhalt, den {@link FormatSampleGenerator} (und der Generator von 1.0.1) schreibt, stichprobenartig. */
	private static void assertSampleContent(String sample, Loaded loaded) {
		Hall hall = loaded.hall();
		assertEquals(FormatSampleGenerator.HALL, hall.getName(), sample);
		List<HallObject> objects = hall.getHallObjects();
		assertEquals(List.of("Bühne", "Theke"), objects.stream().map(HallObject::getName).toList(), sample);
		assertEquals(Shape.RECTANGLE, objects.get(0).getShape(), sample);
		assertEquals(TextAlignH.LEFT, objects.get(0).getTextAlignH(), sample);
		assertEquals(TextAlignV.TOP, objects.get(0).getTextAlignV(), sample);
		assertEquals(Shape.CIRCLE, objects.get(1).getShape(), sample);
		assertEquals(TextAlignH.RIGHT, objects.get(1).getTextAlignH(), sample);
		assertEquals(TextAlignV.BOTTOM, objects.get(1).getTextAlignV(), sample);
		SeatingPlan plan = hall.getSeatingPlans().get(0);
		assertEquals(1, plan.getRows().size(), sample);
		assertEquals(List.of(3, 3), plan.getTables().stream().map(t -> t.getSeats().size()).toList(), sample);

		assertEquals(FormatSampleGenerator.EVENT, loaded.event().getName(), sample);
		Presentation friday = loaded.friday();
		assertEquals("Freitag", friday.getName(), sample);
		assertEquals(plan.getId(), friday.getSeatingPlanId(), sample);
		Map<String, PaymentStatus> statuses = friday.getBookings().stream().filter(Booking::hasData)
				.collect(Collectors.toMap(Booking::getLastName, Booking::getPaymentStatus));
		assertEquals(Map.of("Müller", PaymentStatus.NONE, "Meier", PaymentStatus.CASH, "Schmitz", PaymentStatus.CARD,
				"Becker", PaymentStatus.TRANSFER, "Schulz", PaymentStatus.CASH), statuses, sample);
		assertEquals(7, friday.getBookings().size(), sample + ": sechs Sitze und eine Buchung ohne Platz");
		Booking schulz = loaded.booking("Schulz");
		assertTrue(schulz.isUnassigned(), sample + ": Schulz ist ohne Platz");
		assertEquals(4, schulz.getSeatNumber(), sample);
		assertTrue(friday.getBookings().stream().filter(b -> !b.isUnassigned()).allMatch(b -> b.getSeatId() != null),
				sample + ": jede andere Buchung hat einen Sitz");
	}

	/** Die Buchungs-Einheit mit dem Nachnamen im Stand des Dienstes. */
	private static UnitKey bookingUnit(SyncService sync, String lastName) {
		return sync.snapshots().values().stream()
				.filter(s -> s.unit().type() == UnitType.BOOKING && !s.deleted() && s.payload() != null
						&& lastName.equals(s.payload().path("lastName").asText(null)))
				.map(UnitSnapshot::unit).findFirst().orElseThrow(() -> new AssertionError("keine Buchung " + lastName));
	}

	/** Die Daten des Kopfs der Einheit, als Kopie zum Ändern. */
	private static ObjectNode headPayload(SyncService sync, UnitKey unit) throws IOException {
		return sync.unitState(unit).head().payload().deepCopy();
	}

	private static ObjectNode child(JsonNode parent, String list, int index) {
		return (ObjectNode) parent.get(list).get(index);
	}

	// <-- Bauteile

	/**
	 * Jedes Beispiel (entpackt in einen temporären Ordner) lädt im Einzelbetrieb vollständig und ohne Sperre, nichts
	 * gilt danach als verändert, Speichern ohne Änderung schreibt keine Datei, und weder Dienst noch Repositories warnen.
	 */
	@Test
	void everySampleLoadsUnchanged() throws Exception {
		List<Path> samples = samples();
		assertTrue(samples.stream().anyMatch(s -> s.getFileName().toString().equals("1.0.1")), "Beispiel 1.0.1");
		for (Path sample : samples) {
			String name = sample.getFileName().toString();
			Path appData = dir.resolve(name);
			unzip(sample.resolve(FormatSampleGenerator.ARCHIVE), appData);
			Recorder recorder = new Recorder();
			SyncService sync = TestSyncServices.singleMode(appData);
			sync.setListener(recorder);
			sync.startup(null);

			Loaded loaded = load(sync, recorder);

			assertSampleContent(name, loaded);
			for (UnitSnapshot snapshot : sync.snapshots().values()) {
				assertEquals(UnitState.Status.OK, snapshot.status(), name + ": " + snapshot.unit().relativePath());
				assertFalse(snapshot.contentLocked(), name + ": " + snapshot.unit().relativePath());
			}
			assertEquals(Set.of(), loaded.events().locallyChangedUnits(), name);
			assertTrue(loaded.halls().isCurrent(loaded.hall().getId()), name + ": die Halle gilt nicht als verändert");
			long before = files(appData);
			loaded.halls().saveHall(loaded.hall());
			loaded.events().saveEvent(loaded.event());
			assertEquals(before, files(appData), name + ": Speichern ohne Änderung schreibt nichts");
			assertEquals(List.of(), loaded.halls().getAndClearLoadWarnings(), name);
			assertEquals(List.of(), loaded.events().getAndClearLoadWarnings(), name);
			assertEquals(Map.of(), loaded.halls().getAndClearLockedAtLoad(), name);
			assertEquals(Map.of(), loaded.events().getAndClearLockedAtLoad(), name);
			assertEquals(List.of(), recorder.errors, name);
			assertEquals(Map.of(), recorder.locked, name);
			assertEquals(List.of(), recorder.skipped, name);
			assertEquals(List.of(), warningsLogged(), name);
			sync.closeIndex();
		}
	}

	/**
	 * Ein „1.2-Rechner“ schreibt über die echte Schreibschicht ({@link FutureWriter}) auf den Inhalt von
	 * {@link FormatSampleGenerator}: Zusatzfelder in jeder Klasse, unbekannte Werte für Form und Ausrichtung, einen
	 * unbekannten Zahlstatus, ein {@code requires} und eine Kette V1 → V2 (unbekannter Zahlstatus) → V3 (CASH). Dieses
	 * Programm sperrt genau die beiden Buchungen mit unbekanntem Zahlstatus bzw. {@code requires} ({@code LOCKED_FORMAT}),
	 * alles andere ist {@code OK}. Nichts gilt als verändert, und nach eigenen Änderungen stehen die fremden Felder und
	 * Werte unverändert in den neuen Versionen. Gewarnt wird nur je Sperre einmal (SYS-009), sonst nichts.
	 * <p>
	 * Der 1.2-Rechner ist ein Dienst mit der durchlässigen Inhaltsprüfung ({@link ContentGate#NONE}), der den Inhalt
	 * schreibt und den Kopf jeder Einheit kennt; der Ordner {@code future} ist der gemeinsame Ordner.
	 */
	@Test
	void futureSampleKeepsFieldsAndLocksExactly() throws Exception {
		TestClock clock = new TestClock();
		Path shared = Files.createDirectories(dir.resolve("future"));
		SyncService newer = TestSyncServices.unstarted(dir.resolve("app-neu"), "neu", new NioFileLayer(), p -> true,
				clock, ContentGate.NONE);
		assertTrue(newer.startup(shared.toString()));
		FormatSampleGenerator.Sample base = FormatSampleGenerator.fill(newer);
		clock.advance(ROUND);
		TestSyncServices.tick(newer);
		String eventId = base.event().getId();
		String fridayId = base.event().getPresentations().get(0).getId();
		UnitKey hallUnit = UnitKey.hall(base.hall().getId());
		UnitKey eventUnit = UnitKey.event(eventId);
		UnitKey fridayUnit = UnitKey.presentation(eventId, fridayId);
		UnitKey mueller = bookingUnit(newer, "Müller");
		UnitKey meier = bookingUnit(newer, "Meier");
		UnitKey schmitz = bookingUnit(newer, "Schmitz");
		UnitKey becker = bookingUnit(newer, "Becker");
		UnitKey schulz = bookingUnit(newer, "Schulz");

		ObjectNode hall = headPayload(newer, hallUnit);
		hall.put(EXTRA, "x-test-halle");
		ObjectNode stage = child(hall, "hallObjects", 0);
		stage.put(EXTRA, "x-test-hallenobjekt");
		stage.put("shape", "x-test-ellipse");
		child(hall, "hallObjects", 1).put("textAlignH", "x-test-blocksatz");
		ObjectNode plan = child(hall, "seatingPlans", 0);
		plan.put(EXTRA, "x-test-bestuhlung");
		child(plan, "rows", 0).put(EXTRA, "x-test-reihe");
		ObjectNode table = child(plan, "tables", 0);
		table.put(EXTRA, "x-test-tisch");
		table.put("textAlignH", "x-test-blocksatz");
		child(table, "seats", 0).put(EXTRA, "x-test-sitz");
		FutureWriter.write(shared, hallUnit, hall, newer.unitState(hallUnit).head());
		ObjectNode event = headPayload(newer, eventUnit).put(EXTRA, "x-test-event");
		FutureWriter.write(shared, eventUnit, event, newer.unitState(eventUnit).head());
		ObjectNode friday = headPayload(newer, fridayUnit).put(EXTRA, "x-test-vorstellung");
		FutureWriter.write(shared, fridayUnit, friday, newer.unitState(fridayUnit).head());
		FutureWriter.write(shared, mueller, headPayload(newer, mueller).put(EXTRA, "x-test-buchung"),
				newer.unitState(mueller).head());
		FutureWriter.write(shared, schulz, headPayload(newer, schulz).put(EXTRA, "x-test-ohne-platz"),
				newer.unitState(schulz).head());
		FutureWriter.write(shared, becker, headPayload(newer, becker).put("paymentStatus", "x-test-voucher"),
				newer.unitState(becker).head());
		ObjectNode requiring = headPayload(newer, schmitz);
		requiring.set("requires", JsonMapperFactory.create().createArrayNode().add("x-test-merkmal"));
		FutureWriter.write(shared, schmitz, requiring, newer.unitState(schmitz).head());
		Version voucher = FutureWriter.write(shared, meier,
				headPayload(newer, meier).put("paymentStatus", "x-test-voucher"), newer.unitState(meier).head());
		Version cash = FutureWriter.write(shared, meier, voucher.payload().deepCopy().put("paymentStatus", "CASH"),
				voucher);

		// dieses Programm kommt als neuer Rechner dazu und holt alles ab
		Recorder recorder = new Recorder();
		Path appData = dir.resolve("app-alt");
		SyncService older = TestSyncServices.unstarted(appData, "alt", new NioFileLayer(), p -> true, clock);
		older.setListener(recorder);
		assertTrue(older.startup(shared.toString()));
		for (int round = 0; round < 3; round++) {
			clock.advance(ROUND);
			TestSyncServices.tick(older);
		}
		Loaded loaded = load(older, recorder);

		Set<UnitKey> locked = Set.of(becker, schmitz);
		assertEquals(newer.snapshots().keySet(), older.snapshots().keySet(), "alle Einheiten sind angekommen");
		for (UnitSnapshot snapshot : older.snapshots().values()) {
			boolean expected = locked.contains(snapshot.unit());
			assertEquals(expected ? UnitState.Status.LOCKED_FORMAT : UnitState.Status.OK, snapshot.status(),
					snapshot.unit().relativePath());
			assertEquals(expected, snapshot.contentLocked(), snapshot.unit().relativePath());
		}
		assertEquals(cash.id(), older.unitState(meier).head().id(), "die Kette endet mit V3");
		assertEquals(Map.of(becker, UnitState.Status.LOCKED_FORMAT, schmitz, UnitState.Status.LOCKED_FORMAT),
				loaded.events().getAndClearLockedAtLoad());
		assertEquals(Map.of(), loaded.halls().getAndClearLockedAtLoad());
		assertEquals(List.of(), loaded.halls().getAndClearLoadWarnings());
		assertEquals(List.of(), loaded.events().getAndClearLoadWarnings());
		assertEquals(List.of(), recorder.errors);
		assertEquals(List.of(), recorder.skipped);

		// das Modell: Rückfallwerte, Meier wieder lesbar, die gesperrten Buchungen fehlen, ihre Sitze sind gesperrt
		HallObject shownStage = loaded.hall().getHallObjects().get(0);
		assertEquals(Shape.RECTANGLE, shownStage.getShape(), "Rückfall für x-test-ellipse");
		assertEquals(TextAlignH.CENTER, loaded.hall().getHallObjects().get(1).getTextAlignH(), "Rückfall");
		assertEquals(PaymentStatus.CASH, loaded.booking("Meier").getPaymentStatus());
		assertFalse(loaded.hasBooking("Becker"), "gesperrt");
		assertFalse(loaded.hasBooking("Schmitz"), "gesperrt");
		assertTrue(loaded.booking("Schulz").isUnassigned());
		assertEquals(Set.of(becker.bookingDir(), schmitz.bookingDir()), loaded.events().lockedSeatIds(eventId, fridayId));
		assertEquals(Set.of(), loaded.events().locallyChangedUnits());
		assertTrue(loaded.halls().isCurrent(loaded.hall().getId()), "die Halle gilt nicht als verändert");
		long before = files(appData);
		loaded.halls().saveHall(loaded.hall());
		loaded.events().saveEvent(loaded.event());
		assertEquals(before, files(appData), "Speichern ohne Änderung schreibt nichts");

		// eigene Änderungen an anderen Feldern: die fremden Felder und Werte bleiben
		shownStage.setPosX(42);
		loaded.halls().saveHall(loaded.hall());
		loaded.event().setDescription("Geändert in 1.1");
		loaded.friday().setDescription("Geändert in 1.1");
		loaded.booking("Müller").setComment("Geändert in 1.1");
		loaded.booking("Schulz").setComment("Geändert in 1.1");
		loaded.events().saveEvent(loaded.event());
		// hochladen: Erst dann steht die eigene Version am Ende der Kette
		clock.advance(ROUND);
		TestSyncServices.tick(older);

		for (UnitKey unit : List.of(hallUnit, eventUnit, fridayUnit, mueller, schulz)) {
			assertNotEquals(FutureWriter.MACHINE, older.unitState(unit).head().machine(),
					unit.relativePath() + ": neue Version dieses Programms");
		}
		ObjectNode savedHall = older.unitState(hallUnit).head().payload();
		assertEquals("x-test-halle", savedHall.path(EXTRA).asText());
		ObjectNode savedStage = child(savedHall, "hallObjects", 0);
		assertEquals(42.0, savedStage.get("posX").asDouble());
		assertEquals("x-test-hallenobjekt", savedStage.path(EXTRA).asText());
		assertEquals("x-test-ellipse", savedStage.path("shape").asText());
		assertEquals("x-test-blocksatz", child(savedHall, "hallObjects", 1).path("textAlignH").asText());
		ObjectNode savedPlan = child(savedHall, "seatingPlans", 0);
		assertEquals("x-test-bestuhlung", savedPlan.path(EXTRA).asText());
		assertEquals("x-test-reihe", child(savedPlan, "rows", 0).path(EXTRA).asText());
		ObjectNode savedTable = child(savedPlan, "tables", 0);
		assertEquals("x-test-tisch", savedTable.path(EXTRA).asText());
		assertEquals("x-test-blocksatz", savedTable.path("textAlignH").asText());
		assertEquals("x-test-sitz", child(savedTable, "seats", 0).path(EXTRA).asText());
		assertEquals("x-test-event", older.unitState(eventUnit).head().payload().path(EXTRA).asText());
		assertEquals("x-test-vorstellung", older.unitState(fridayUnit).head().payload().path(EXTRA).asText());
		assertEquals("x-test-buchung", older.unitState(mueller).head().payload().path(EXTRA).asText());
		assertEquals("x-test-ohne-platz", older.unitState(schulz).head().payload().path(EXTRA).asText());
		for (UnitKey unit : List.of(becker, schmitz, meier)) {
			assertEquals(FutureWriter.MACHINE, older.unitState(unit).head().machine(), unit.relativePath() + ": unberührt");
		}
		assertEquals(Set.of(), loaded.events().locallyChangedUnits());
		assertEquals(List.of(), recorder.errors);
		assertEquals(List.of(), recorder.skipped);
		assertEquals(Map.of(becker, UnitState.Status.LOCKED_FORMAT, schmitz, UnitState.Status.LOCKED_FORMAT),
				recorder.locked);
		assertEquals(Set.of(StoreMessages.newerFormat(becker.relativePath()),
				StoreMessages.newerFormat(schmitz.relativePath())), Set.copyOf(warningsLogged()),
				"nur die Sperren, keine Unlesbar-Warnung");
		assertEquals(2, warningsLogged().size(), "jede Sperre einmal");
	}
}
