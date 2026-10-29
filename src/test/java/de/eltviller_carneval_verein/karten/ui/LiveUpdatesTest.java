package de.eltviller_carneval_verein.karten.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.node.ObjectNode;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.BookingSync;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.model.TableRow;
import de.eltviller_carneval_verein.karten.repository.JsonMapperFactory;
import de.eltviller_carneval_verein.karten.repository.RepositoryListener;
import de.eltviller_carneval_verein.karten.repository.StoreEventRepository;
import de.eltviller_carneval_verein.karten.repository.StoreHallRepository;
import de.eltviller_carneval_verein.karten.repository.TestRepositories;
import de.eltviller_carneval_verein.karten.store.ConflictNote;
import de.eltviller_carneval_verein.karten.store.ForeignVersions;
import de.eltviller_carneval_verein.karten.store.SyncService;
import de.eltviller_carneval_verein.karten.store.TestSyncServices;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.Version;
import de.eltviller_carneval_verein.karten.store.VersionId;
import de.eltviller_carneval_verein.karten.tracking.ChangeTracker;
import de.eltviller_carneval_verein.karten.tracking.StateRestorer;
import de.eltviller_carneval_verein.karten.validation.EntityLabels;

/**
 * Die Koordination fremder Änderungen in der App, mit echten Repositories auf einem Versionsspeicher im temporären
 * Ordner und eigenen Trackern. "Der andere Rechner" ist ein zweites Repository auf demselben Dienst: Es speichert, und
 * {@link LiveUpdates#listener()} bekommt die Einheiten gemeldet, wie es der Dienst täte. Wiederholt wird sofort
 * ({@code Runnable::run}); der Tracker-Aktualisierer ist vor {@link LiveUpdates} angemeldet, wie in der App.
 */
class LiveUpdatesTest {

	/** Das Event, wie es der andere Rechner geladen und für die Anzeige vorbereitet hat. */
	private record Other(StoreEventRepository repository, Event event) {
		void save() {
			repository.saveEvent(event);
		}

		Presentation friday() {
			return event.getPresentations().get(0);
		}
	}

	@TempDir
	Path appData;

	private SyncService sync;
	private StoreHallRepository halls;
	private StoreEventRepository events;
	private ChangeTracker<Hall> hallTracker;
	private ChangeTracker<Event> eventTracker;
	private StateRestorer<Event> eventRestorer;
	private StateRestorer<Hall> hallRestorer;
	private EditingUnits editing;
	private final List<String> hints = new ArrayList<>();
	// wohin die Wiederholungen gehen: sofort, wenn ein Test nichts anderes stellt
	private Executor later = Runnable::run;
	private LiveUpdates live;
	private Hall hall;
	private SeatingPlan plan;
	private Event event;
	private Presentation friday;
	private ForeignVersions foreign;

	/**
	 * Eine Halle mit 2 Tischen zu je 3 Sitzen und ein Event "Prunksitzung" mit der Vorstellung "Freitag" in dieser Halle,
	 * "Müller" auf dem ersten Sitz; gespeichert von einem Schreiber, dann geladen und vorbereitet wie in der App.
	 */
	@BeforeEach
	void setUp() {
		sync = TestSyncServices.singleMode(appData);
		foreign = new ForeignVersions(sync, appData);
		sync.startup(null, p -> {
		});
		Hall created = new Hall();
		created.setName("Stadthalle");
		SeatingPlan createdPlan = created.addSeatingPlan();
		TableRow row = createdPlan.addRow();
		row.setLabel("A");
		createdPlan.fillRow(row, 2, 3);
		TestRepositories.halls(sync).saveHall(created);

		Event createdEvent = new Event();
		createdEvent.setName("Prunksitzung");
		Presentation presentation = createdEvent.addPresentation("Freitag");
		presentation.setHallId(created.getId());
		presentation.setSeatingPlanId(createdPlan.getId());
		BookingSync.sync(presentation, createdPlan);
		presentation.getBookings().stream().filter(b -> createdPlan.getSeats().get(0).getId().equals(b.getSeatId())).findFirst()
				.orElseThrow().setLastName("Müller");
		TestRepositories.events(sync).saveEvent(createdEvent);

		halls = TestRepositories.halls(sync);
		events = TestRepositories.events(sync);
		halls.loadHalls();
		events.loadEvents();
		hallTracker = new ChangeTracker<>(Hall::getId);
		eventTracker = new ChangeTracker<>(Event::getId);
		events.addListener(eventTracker.repositoryUpdater());
		halls.addListener(hallTracker.repositoryUpdater());
		editing = new EditingUnits();
		live = new LiveUpdates(halls, events, hallTracker, eventTracker, editing, command -> later.execute(command), hints::add);
		eventRestorer = new StateRestorer<>(Event.class, eventTracker, events::replaceCachedEvent);
		hallRestorer = new StateRestorer<>(Hall.class, hallTracker, halls::replaceCachedHall);
		live.watch(eventRestorer, hallRestorer);

		hall = halls.findById(created.getId());
		plan = hall.getSeatingPlans().get(0);
		hallTracker.track(hall);
		event = events.loadEvents().get(0);
		BookingSyncService.prepare(event, halls::findById, eventTracker);
		friday = event.getPresentations().get(0);
	}

	// Bauteile -->

	/** Die Buchung auf dem n-ten Sitz der Bestuhlung. */
	private Booking seat(Presentation presentation, int n) {
		String seatId = plan.getSeats().get(n).getId();
		return presentation.getBookings().stream().filter(b -> seatId.equals(b.getSeatId())).findFirst().orElseThrow();
	}

	private UnitKey seatUnit(int n) {
		return UnitKey.booking(event.getId(), friday.getId(), plan.getSeats().get(n).getId());
	}

	private UnitKey presentationUnit() {
		return UnitKey.presentation(event.getId(), friday.getId());
	}

	/** Ein zweites Repository auf demselben Dienst lädt das Event und bereitet es vor wie die App. */
	private Other other() {
		StoreEventRepository repository = TestRepositories.events(sync);
		Event loaded = repository.loadEvents().stream().filter(e -> e.getId().equals(event.getId())).findFirst().orElseThrow();
		for (Presentation presentation : loaded.getPresentations()) {
			BookingSync.sync(presentation, plan);
		}
		return new Other(repository, loaded);
	}

	/** Der andere Rechner lädt die Halle, ändert sie und speichert. */
	private void otherChangesHall(Consumer<Hall> change) {
		StoreHallRepository repository = TestRepositories.halls(sync);
		Hall loaded = repository.findById(hall.getId());
		change.accept(loaded);
		repository.saveHall(loaded);
	}

	private void notifyUnits(UnitKey... units) {
		live.listener().unitsChanged(Set.of(units));
	}

	/** Die Anzahl aller Dateien (Versionen und Ansprüche) im lokalen Bestand. */
	private long files() throws IOException {
		return foreign.files();
	}

	/** Der gespeicherte Stand des Events, wie ihn der Tracker für "Nicht speichern" hält. */
	private Event baselineEvent() throws IOException {
		return JsonMapperFactory.create().readValue(eventTracker.baseline(event).bytes(), Event.class);
	}

	/** Die Buchung auf dem n-ten Sitz, wie sie ein frisch lesender Rechner sähe. */
	private Booking storedSeat(int n) {
		Event reloaded = TestRepositories.events(sync).loadEvents().stream().filter(e -> e.getId().equals(event.getId()))
				.findFirst().orElseThrow();
		return seat(reloaded.getPresentations().get(0), n);
	}

	/**
	 * Schreibt eine Version des Rechners {@code machineName} auf das Ende der Kette und bringt sie in den Index, wie es das
	 * Abholen aus dem gemeinsamen Ordner tut.
	 */
	private void foreignVersion(UnitKey unit, String machineName, Consumer<ObjectNode> changes) throws IOException {
		foreign.write(unit, machineName, changes);
	}

	private static String deferralHint(String entry, String machine) {
		return "„" + entry + "“ wurde auf " + machine + " geändert; deine Änderung ist noch nicht gespeichert.";
	}

	// <-- Bauteile

	// Änderungserkennung -->

	@Test
	void foreignSaleKeepsCleanEventClean() throws IOException {
		Other other = other();
		seat(other.friday(), 1).setLastName("Fremd");
		other.save();
		long before = files();

		notifyUnits(seatUnit(1));

		assertEquals("Fremd", seat(friday, 1).getLastName());
		assertFalse(eventTracker.isDirty(event));
		assertEquals(eventTracker.snapshotOf(event), eventTracker.baseline(event));
		assertFalse(eventTracker.check(event), "der Tracker kennt den eingearbeiteten Stand als letzten");
		assertFalse(eventTracker.anyDirty());
		events.saveEvent(event);
		assertEquals(before, files(), "der fremde Verkauf wird weder gelöscht noch neu geschrieben");
		assertTrue(hints.isEmpty());
	}

	@Test
	void catchUpAppliesAForeignSaleWhoseNotificationWasMissed() throws IOException {
		// der Verkauf liegt im Index, seine Meldung kam vor dem Einhängen an und lief ins Leere
		Other other = other();
		seat(other.friday(), 1).setLastName("Fremd");
		other.save();
		long before = files();

		live.catchUp(sync.snapshots().keySet());

		assertEquals("Fremd", seat(friday, 1).getLastName());
		assertFalse(eventTracker.isDirty(event));
		events.saveEvent(event);
		assertEquals(before, files(), "der nachgeholte Verkauf wird weder gelöscht noch neu geschrieben");
		assertTrue(hints.isEmpty());
	}

	@Test
	void catchUpWithoutForeignChangesChangesNothing() throws IOException {
		long before = files();
		String muellerId = seat(friday, 0).getId();

		live.catchUp(sync.snapshots().keySet());

		assertEquals("Müller", seat(friday, 0).getLastName());
		assertEquals(muellerId, seat(friday, 0).getId());
		assertFalse(eventTracker.isDirty(event));
		events.saveEvent(event);
		assertEquals(before, files());
		assertTrue(hints.isEmpty());
	}

	@Test
	void foreignSaleKeepsUserChangesDirtyAndDiscardKeepsTheSale() throws IOException {
		seat(friday, 0).setLastName("Eigen");
		eventTracker.check(event);
		assertTrue(eventTracker.isDirty(event));
		Other other = other();
		seat(other.friday(), 1).setLastName("Fremd");
		other.save();

		notifyUnits(seatUnit(1));

		assertEquals("Fremd", seat(friday, 1).getLastName());
		assertEquals("Eigen", seat(friday, 0).getLastName());
		assertTrue(eventTracker.isDirty(event), "ungespeichert ist nur die Änderung des Nutzers");
		Event baseline = baselineEvent();
		assertEquals("Müller", seat(baseline.getPresentations().get(0), 0).getLastName());
		assertEquals("Fremd", seat(baseline.getPresentations().get(0), 1).getLastName(), "der gespeicherte Stand kennt den Verkauf");

		Event restored = eventRestorer.restoreBaseline(event);

		assertEquals("Müller", seat(restored.getPresentations().get(0), 0).getLastName());
		assertEquals("Fremd", seat(restored.getPresentations().get(0), 1).getLastName());
		assertFalse(eventTracker.isDirty(restored));
		long before = files();
		events.saveEvent(restored);
		assertEquals(before, files(), "Nicht speichern und Speichern schreiben nichts Fremdes");
		assertEquals("Fremd", storedSeat(1).getLastName());
		assertEquals("Müller", storedSeat(0).getLastName());
	}

	@Test
	void uncheckedUserEditStaysDirtyAfterForeignMerge() throws IOException {
		List<Event> seen = new ArrayList<>();
		eventTracker.addListener((changed, before, after) -> seen.add(changed));
		seat(friday, 0).setLastName("Eigen");
		assertTrue(seen.isEmpty(), "noch nicht verglichen");
		Other other = other();
		seat(other.friday(), 1).setLastName("Fremd");
		other.save();

		notifyUnits(seatUnit(1));

		assertEquals(List.of(event), seen, "die Nutzeränderung wurde vor dem Einarbeiten erkannt und gemeldet");
		assertTrue(eventTracker.isDirty(event));
		assertEquals("Eigen", seat(friday, 0).getLastName());
		assertEquals("Fremd", seat(friday, 1).getLastName());
		Event baseline = baselineEvent();
		assertEquals("Müller", seat(baseline.getPresentations().get(0), 0).getLastName());
		assertEquals("Fremd", seat(baseline.getPresentations().get(0), 1).getLastName());
		assertEquals(1, seen.size(), "das Einarbeiten selbst meldet keine Änderung des Nutzers");
	}

	@Test
	void failedReplayKeepsTheEventDirtyWithItsOldBaselineAndWarns() {
		seat(friday, 0).setLastName("Eigen");
		eventTracker.check(event);
		ChangeTracker.Snapshot before = eventTracker.baseline(event);
		RepositoryListener<Event> listener = live.eventListener();

		listener.changingByOthers(event);
		listener.changedByOthers(event, TestRepositories.failingBatch(Set.of(seatUnit(1))));

		assertTrue(eventTracker.isDirty(event), "nichts geht still verloren");
		assertEquals(before, eventTracker.baseline(event), "der gespeicherte Stand bleibt der alte");
		assertEquals(List.of("Änderungen von einem anderen Rechner an „Prunksitzung“ konnten nicht nachgeführt werden. "
				+ "Bitte speichern statt verwerfen."), hints);
	}

	@Test
	void foreignDeletionUntracksTheEvent() {
		Other other = other();
		other.repository().deleteEvent(other.event());

		notifyUnits(UnitKey.event(event.getId()));

		assertFalse(events.loadEvents().contains(event));
		assertNull(eventTracker.baseline(event));
		assertFalse(eventTracker.anyDirty());
	}

	@Test
	void unreadableForeignHallWarnsAndLeavesTheTracker() throws IOException {
		foreignVersion(UnitKey.hall(hall.getId()), "Fremd", payload -> payload.put("id", "anderes"));

		notifyUnits(UnitKey.hall(hall.getId()));

		assertNull(halls.findById(hall.getId()));
		assertNull(hallTracker.baseline(hall));
		assertEquals(1, hints.size());
		assertTrue(hints.get(0).contains(hall.getId()), hints.get(0));
	}

	// <-- Änderungserkennung

	// Hallen -->

	@Test
	void foreignHallChangeReplacesTheHallRebasesItsTrackerAndPreparesTheEvents() {
		otherChangesHall(h -> {
			SeatingPlan changed = h.getSeatingPlans().get(0);
			changed.fillRow(changed.getRows().get(0), 1, 3);
		});

		notifyUnits(UnitKey.hall(hall.getId()));

		Hall replaced = halls.findById(hall.getId());
		assertNotSame(hall, replaced);
		assertEquals(9, replaced.getSeatingPlans().get(0).getSeats().size());
		assertFalse(hallTracker.isDirty(replaced));
		assertEquals(hallTracker.snapshotOf(replaced), hallTracker.baseline(replaced));
		assertEquals(9, friday.getBookings().size(), "die Buchungen der Vorstellung wurden mit der neuen Bestuhlung abgeglichen");
		assertFalse(eventTracker.isDirty(event), "der Abgleich ist keine Änderung des Nutzers");
		assertTrue(hints.isEmpty());
	}

	@Test
	void bookingRefreshedByAHallChangeThatTheOtherMachineSavedAlikeGivesNoHint() throws IOException {
		otherChangesHall(h -> h.getSeatingPlans().get(0).getRows().get(0).setLabel("B"));
		notifyUnits(UnitKey.hall(hall.getId()));
		assertEquals("B", seat(friday, 0).getRowLabel(), "der Abgleich hat die Momentaufnahme erneuert");
		// der andere Rechner öffnet das Event mit der geänderten Halle und speichert es: Die Momentaufnahme wird eine Version
		StoreEventRepository repository = TestRepositories.events(sync);
		Event loaded = repository.loadEvents().get(0);
		BookingSyncService.prepare(loaded, TestRepositories.halls(sync)::findById, new ChangeTracker<>(Event::getId));
		repository.saveEvent(loaded);
		long before = files();

		notifyUnits(seatUnit(0));

		assertTrue(hints.isEmpty(), hints.toString());
		assertEquals(Set.of(), events.locallyChangedUnits());
		assertFalse(eventTracker.isDirty(event));
		events.saveEvent(event);
		assertEquals(before, files(), "nichts wird als eigene Version geschrieben");
	}

	@Test
	void foreignHallChangeDoesNotDeferLaterForeignBookingsOfThatHall() throws IOException {
		otherChangesHall(h -> h.getSeatingPlans().get(0).getRows().get(0).setLabel("B"));

		notifyUnits(UnitKey.hall(hall.getId()));

		assertEquals("B", seat(friday, 0).getRowLabel(), "der Abgleich hat die Buchungen auf die neue Bestuhlung umgesetzt");
		assertEquals(Set.of(), events.locallyChangedUnits(), "was der Abgleich umsetzte, hat der Nutzer nicht geändert");
		// der andere Rechner öffnet das Event mit der geänderten Halle, verkauft auf dem ersten Sitz und speichert
		StoreEventRepository repository = TestRepositories.events(sync);
		Event loaded = repository.loadEvents().get(0);
		BookingSyncService.prepare(loaded, TestRepositories.halls(sync)::findById, new ChangeTracker<>(Event::getId));
		seat(loaded.getPresentations().get(0), 0).setLastName("Fremd");
		repository.saveEvent(loaded);
		long before = files();

		notifyUnits(seatUnit(0));

		assertEquals("Fremd", seat(friday, 0).getLastName(), "der fremde Verkauf ist eingearbeitet");
		assertTrue(hints.isEmpty(), hints.toString());
		assertEquals(Set.of(), events.locallyChangedUnits());
		assertFalse(eventTracker.isDirty(event));
		events.saveEvent(event);
		assertEquals(before, files(), "nichts Fremdes wird als eigene Version geschrieben");
	}

	@Test
	void reconcileOnTopOfAnUnsavedBookingKeepsItALocalChange() {
		seat(friday, 0).setLastName("Eigen");
		otherChangesHall(h -> h.getSeatingPlans().get(0).getRows().get(0).setLabel("B"));
		Hall changed = TestRepositories.halls(sync).findById(hall.getId());

		BookingSyncService.prepare(event, id -> changed, eventTracker, events);

		assertEquals("B", seat(friday, 0).getRowLabel());
		assertEquals("B", seat(friday, 1).getRowLabel());
		assertEquals(Set.of(seatUnit(0)), events.locallyChangedUnits(),
				"die Eingabe des Nutzers bleibt eine Änderung, nur der Abgleich allein gilt nicht als solche");
		assertTrue(eventTracker.isDirty(event));
	}

	@Test
	void hallIsBlockedWhileItsPresentationsHaveUnsavedBookings() {
		seat(friday, 2).setLastName("Eigen");
		eventTracker.check(event);
		otherChangesHall(h -> h.setName("Bürgerhaus"));

		notifyUnits(UnitKey.hall(hall.getId()));

		assertSame(hall, halls.findById(hall.getId()), "solange Buchungen ungespeichert sind, bleibt die Halle");
		assertEquals(List.of(deferralHint("Stadthalle", "Rechner test")), hints);

		events.saveEvent(event);

		Hall replaced = halls.findById(hall.getId());
		assertNotSame(hall, replaced);
		assertEquals("Bürgerhaus", replaced.getName());
		assertFalse(hallTracker.isDirty(replaced));
		assertEquals(hallTracker.snapshotOf(replaced), hallTracker.baseline(replaced));
		assertEquals(1, hints.size(), "der Hinweis kommt nicht noch einmal");
	}

	@Test
	void hallInEditingIsDeferredUntilTheEditingEnds() {
		Object editor = new Object();
		editing.begin(editor, List.of(hall));
		otherChangesHall(h -> h.setName("Bürgerhaus"));

		notifyUnits(UnitKey.hall(hall.getId()));

		assertSame(hall, halls.findById(hall.getId()));
		assertEquals(1, hints.size());

		editing.end(editor);

		assertEquals("Bürgerhaus", halls.findById(hall.getId()).getName());
		assertEquals(1, hints.size());
	}

	@Test
	void hallIsDeferredWhileABookingOfItsPresentationsIsInEditing() {
		// ein Popup ist auf dem leeren Platzhalter eines Sitzes offen, den der andere Rechner entfernt
		Booking placeholder = seat(friday, 5);
		Object popup = new Object();
		editing.begin(popup, List.of(placeholder));
		otherChangesHall(h -> {
			SeatingPlan changed = h.getSeatingPlans().get(0);
			changed.getTables().get(1).getSeats().remove(2);
		});

		notifyUnits(UnitKey.hall(hall.getId()));

		assertSame(hall, halls.findById(hall.getId()), "die Eingabe schriebe sonst in einen entfernten Platzhalter");
		assertTrue(friday.getBookings().contains(placeholder));
		assertEquals(List.of(deferralHint("Stadthalle", "Rechner test")), hints);

		editing.end(popup);

		assertNotSame(hall, halls.findById(hall.getId()));
		assertEquals(5, friday.getBookings().size());
		assertFalse(friday.getBookings().contains(placeholder));
	}

	@Test
	void hallIsDeferredWhileAPresentationInItIsInEditing() {
		Object dialog = new Object();
		editing.begin(dialog, List.of(friday));
		otherChangesHall(h -> h.setName("Bürgerhaus"));

		notifyUnits(UnitKey.hall(hall.getId()));

		assertSame(hall, halls.findById(hall.getId()));
		editing.end(dialog);
		assertEquals("Bürgerhaus", halls.findById(hall.getId()).getName());
	}

	@Test
	void discardOfAHallRetriesItsDeferredUnit() {
		hall.setName("Eigen");
		hallTracker.check(hall);
		otherChangesHall(h -> h.setName("Bürgerhaus"));
		notifyUnits(UnitKey.hall(hall.getId()));
		assertSame(hall, halls.findById(hall.getId()));

		Hall restored = hallRestorer.restoreBaseline(hall);

		Hall now = halls.findById(hall.getId());
		assertNotSame(restored, now);
		assertEquals("Bürgerhaus", now.getName());
		assertFalse(hallTracker.isDirty(now));
		assertEquals(hallTracker.snapshotOf(now), hallTracker.baseline(now));
	}

	// <-- Hallen

	// Zurückgestellte Einheiten -->

	@Test
	void deferralHintIsShownOncePerVersion() throws IOException {
		Booking mine = seat(friday, 0);
		mine.setLastName("Eigen");
		eventTracker.check(event);
		foreignVersion(seatUnit(0), "Fremd", payload -> payload.put("lastName", "Erste"));

		notifyUnits(seatUnit(0));
		notifyUnits(seatUnit(0));
		live.listener().unitsChanged(Set.of());

		String label = EntityLabels.booking(mine);
		assertEquals(List.of(deferralHint(label, "Rechner Fremd")), hints);
		assertEquals("Eigen", mine.getLastName());

		foreignVersion(seatUnit(0), "Fremd", payload -> payload.put("lastName", "Zweite"));
		notifyUnits(seatUnit(0));
		notifyUnits(seatUnit(0));

		assertEquals(List.of(deferralHint(label, "Rechner Fremd"), deferralHint(label, "Rechner Fremd")), hints,
				"eine neue Version bekommt einen neuen Hinweis");
	}

	@Test
	void deferralHintComesAfterTheWarningsOfTheSameBatch() throws IOException {
		Hall second = new Hall();
		second.setName("Bürgerhaus");
		TestRepositories.halls(sync).saveHall(second);
		UnitKey secondUnit = UnitKey.hall(second.getId());
		notifyUnits(secondUnit);
		Booking mine = seat(friday, 0);
		mine.setLastName("Eigen");
		eventTracker.check(event);
		foreignVersion(secondUnit, "Fremd", payload -> payload.put("id", "anderes"));
		foreignVersion(seatUnit(0), "Fremd", payload -> payload.put("lastName", "Erste"));

		notifyUnits(secondUnit, seatUnit(0));

		assertEquals(2, hints.size(), hints.toString());
		assertTrue(hints.get(0).contains(second.getId()), "die Warnung zur unlesbaren Halle");
		assertEquals(deferralHint(EntityLabels.booking(mine), "Rechner Fremd"), hints.get(1),
				"die Fußzeile zeigt nur den letzten Hinweis: der zum Zurückstellen bleibt stehen");
	}

	/** Eine eigene Zusammenführung (eigene Rechner-ID, zwei Vorgänger) auf einem fremden Stand der Buchung. */
	private void ownMerge(ConflictNote note) throws IOException {
		VersionId sold = sync.unitState(seatUnit(0)).head().id();
		foreignVersion(seatUnit(0), "Fremd", payload -> payload.put("lastName", "Fremd"));
		foreign.writeMerge(seatUnit(0), sync.machineId(), "test", List.of(sold), note,
				payload -> payload.put("comment", "M"));
	}

	@Test
	void deferredOwnMergeShowsNoHint() throws IOException {
		Booking mine = seat(friday, 0);
		mine.setLastName("Eigen");
		eventTracker.check(event);
		ownMerge(new ConflictNote(ConflictNote.Kind.FIELDS, sync.machineId(), null, List.of(), null, null, null));

		notifyUnits(seatUnit(0));

		assertEquals(List.of(), hints, "den Konflikt zeigt der Dialog, nicht die Fußzeile");
		assertEquals("Eigen", mine.getLastName(), "zurückgestellt");
		assertEquals(1, events.retryDeferred().deferred().size(), "die Einheit bleibt zurückgestellt");
	}

	/**
	 * Ruling R27: Eine eigene Zusammenführung ohne Vermerk für diesen Rechner (hier: ein Vermerk, der einen anderen
	 * Rechner betrifft) bringt nur fremde Änderungen; sie bekommt den gewohnten Hinweis.
	 */
	@Test
	void ownMergeWithoutNoteShowsTheNormalHint() throws IOException {
		Booking mine = seat(friday, 0);
		mine.setLastName("Eigen");
		eventTracker.check(event);
		ownMerge(new ConflictNote(ConflictNote.Kind.FIELDS, "anderer-rechner", null, List.of(), null, null, null));

		notifyUnits(seatUnit(0));

		assertEquals(List.of(deferralHint(EntityLabels.booking(mine), "Rechner test")), hints);
		assertEquals("Eigen", mine.getLastName(), "zurückgestellt");
	}

	@Test
	void ownMergeWithoutAnyNoteShowsTheNormalHint() throws IOException {
		Booking mine = seat(friday, 0);
		mine.setLastName("Eigen");
		eventTracker.check(event);
		ownMerge(null);

		notifyUnits(seatUnit(0));

		assertEquals(List.of(deferralHint(EntityLabels.booking(mine), "Rechner test")), hints);
	}

	@Test
	void lostShowsNoFooterMessage() {
		Logger logger = Logger.getLogger(LiveUpdates.class.getName());
		List<LogRecord> records = new ArrayList<>();
		Handler capture = new Handler() {
			@Override
			public void publish(LogRecord record) {
				records.add(record);
			}

			@Override
			public void flush() {
			}

			@Override
			public void close() {
			}
		};
		logger.addHandler(capture);
		StatusMessage.Entry before = StatusMessage.getInstance().currentProperty().get();
		VersionId lost = VersionId.create(1_000L, "aaaaaaaa", 0);
		VersionId winner = VersionId.create(1_000L, "bbbbbbbb", 0);
		try {
			// ohne Fußzeile öffnete eine Meldung einen Dialog; hier gibt es keinen Toolkit
			live.listener().lost(seatUnit(0), List.of(lost), winner);
		} finally {
			logger.removeHandler(capture);
		}

		assertSame(before, StatusMessage.getInstance().currentProperty().get());
		assertEquals(1, records.size(), records.toString());
		LogRecord record = records.get(0);
		assertEquals(Level.INFO, record.getLevel());
		assertTrue(record.getMessage().contains(seatUnit(0).relativePath()), record.getMessage());
		assertTrue(record.getMessage().contains(lost.value()), record.getMessage());
		assertTrue(record.getMessage().contains(winner.value()), record.getMessage());
	}

	@Test
	void conflictsChangedIsPassedOnToTheDialogHook() {
		List<String> calls = new ArrayList<>();
		live.listener().conflictsChanged();
		live.setOnConflictsChanged(() -> calls.add("geändert"));

		live.listener().conflictsChanged();
		live.listener().conflictsChanged();

		assertEquals(List.of("geändert", "geändert"), calls);
	}

	@Test
	void failingDialogHookDoesNotBreakTheListener() {
		live.setOnConflictsChanged(() -> {
			throw new IllegalStateException("Dialog kaputt");
		});
		Logger logger = Logger.getLogger(LiveUpdates.class.getName());
		Level level = logger.getLevel();
		logger.setLevel(Level.OFF);
		try {
			live.listener().conflictsChanged();
		} finally {
			logger.setLevel(level);
		}

		assertEquals(List.of(), hints, "kein Hinweis, nur das Log");
	}

	@Test
	void deferralWithoutKnownMachineNameSaysAnotherMachine() throws IOException {
		Booking mine = seat(friday, 0);
		mine.setLastName("Eigen");
		eventTracker.check(event);
		foreignVersion(seatUnit(0), null, payload -> payload.put("lastName", "Erste"));

		notifyUnits(seatUnit(0));

		assertEquals(List.of(deferralHint(EntityLabels.booking(mine), "einem anderen Rechner")), hints);
	}

	@Test
	void deferralHintNamesThePresentationByItsName() {
		seat(friday, 0).setLastName("Eigen");
		eventTracker.check(event);
		Other other = other();
		other.event().deletePresentation(other.friday());
		other.save();

		notifyUnits(presentationUnit());

		assertEquals(List.of(deferralHint("Freitag", "Rechner test")), hints);
	}

	@Test
	void deferredUnitIsRetriedAfterSaveAndAfterEditingEnds() throws IOException {
		// bearbeitet: zurückgestellt, bis die Bearbeitung endet
		Object owner = new Object();
		editing.begin(owner, List.of(seat(friday, 1)));
		Other other = other();
		seat(other.friday(), 1).setLastName("Fremd");
		other.save();
		notifyUnits(seatUnit(1));
		assertFalse(seat(friday, 1).hasData());
		assertEquals(1, hints.size());

		editing.end(owner);

		assertEquals("Fremd", seat(friday, 1).getLastName());
		assertFalse(eventTracker.isDirty(event));
		assertEquals(eventTracker.snapshotOf(event), eventTracker.baseline(event));
		assertEquals(1, hints.size());

		// gespeichert: Die zurückgestellte Löschung der Vorstellung greift, sobald nichts darunter ungespeichert ist
		seat(friday, 0).setLastName("Eigen");
		eventTracker.check(event);
		Other deleter = other();
		deleter.event().deletePresentation(deleter.friday());
		deleter.save();
		notifyUnits(presentationUnit());
		assertEquals(1, event.getPresentations().size());
		assertEquals(2, hints.size());

		events.saveEvent(event);

		assertTrue(event.getPresentations().isEmpty());
		assertFalse(eventTracker.isDirty(event));
		assertEquals(eventTracker.snapshotOf(event), eventTracker.baseline(event));
		assertEquals(2, hints.size());
	}

	@Test
	void discardRetriesTheDeferredUnit() {
		seat(friday, 0).setLastName("Eigen");
		eventTracker.check(event);
		Other other = other();
		seat(other.friday(), 0).setLastName("Fremd");
		other.save();
		notifyUnits(seatUnit(0));
		assertEquals("Eigen", seat(friday, 0).getLastName());

		Event restored = eventRestorer.restoreBaseline(event);

		Event now = events.loadEvents().get(0);
		assertSame(restored, now);
		assertEquals("Fremd", seat(now.getPresentations().get(0), 0).getLastName());
		assertFalse(eventTracker.isDirty(now));
		assertEquals(eventTracker.snapshotOf(now), eventTracker.baseline(now));
	}

	@Test
	void retryRunsThroughTheLaterExecutorNotInsideTheCall() {
		List<Runnable> queued = new ArrayList<>();
		later = queued::add;
		Object owner = new Object();
		editing.begin(owner, List.of(seat(friday, 1)));
		Other other = other();
		seat(other.friday(), 1).setLastName("Fremd");
		other.save();
		notifyUnits(seatUnit(1));
		queued.clear();

		editing.end(owner);

		assertFalse(seat(friday, 1).hasData(), "noch nicht im Aufruf von end");
		assertFalse(queued.isEmpty());
		queued.forEach(Runnable::run);
		assertEquals("Fremd", seat(friday, 1).getLastName());
	}

	// <-- Zurückgestellte Einheiten
}
