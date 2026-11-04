package de.eltviller_carneval_verein.karten.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.repository.ForeignResult;
import de.eltviller_carneval_verein.karten.repository.StoreEventRepository;
import de.eltviller_carneval_verein.karten.repository.TestRepositories;
import de.eltviller_carneval_verein.karten.store.SyncService;
import de.eltviller_carneval_verein.karten.store.TestSyncServices;
import de.eltviller_carneval_verein.karten.store.UnitKey;

/**
 * Die Kopf-Boxen einer Shell nach fremden Änderungen, ohne Oberfläche: Die Boxen sind Attrappen, die wie eine ComboBox
 * beim Setzen der Liste die Auswahl leeren, die Shell ist nachgebildet (Event wählen setzt die Vorstellungsliste neu und
 * wählt "alle"). Das Event-Repository arbeitet auf einem Versionsspeicher im temporären Ordner; "der andere Rechner" ist
 * ein zweites Repository auf demselben Dienst.
 */
class EventSelectionSyncTest {

	/** Eine Box, die zählt, wie oft Liste und Auswahl gesetzt werden. */
	private static final class FakeBox<T> implements EventSelectionSync.Box<T> {
		private final Function<T, String> labels;
		final List<T> items = new ArrayList<>();
		int selected = -1;
		int replaced;
		int selections;
		boolean valueCleared;

		FakeBox(Function<T, String> labels) {
			this.labels = labels;
		}

		@Override
		public List<T> items() {
			return items;
		}

		@Override
		public void setItems(List<T> newItems) {
			items.clear();
			items.addAll(newItems);
			selected = -1;
			replaced++;
		}

		@Override
		public int selectedIndex() {
			return selected;
		}

		@Override
		public void select(int index) {
			selected = index;
			selections++;
		}

		@Override
		public String label(T item) {
			return labels.apply(item);
		}

		@Override
		public void clearValue() {
			valueCleared = true;
			selected = -1;
		}

		T selectedItem() {
			return selected < 0 ? null : items.get(selected);
		}

		void resetCounts() {
			replaced = 0;
			selections = 0;
		}
	}

	@TempDir
	Path appData;

	private SyncService sync;
	private StoreEventRepository repository;
	private Event event;
	private Presentation friday;
	private final FakeBox<Event> eventBox = new FakeBox<>(e -> e.getName());
	private final FakeBox<Presentation> presentationBox = new FakeBox<>(p -> p == null ? "alle" : p.getName());
	private final List<String> warnings = new ArrayList<>();
	private final List<Event> appliedEvents = new ArrayList<>();
	private final List<Presentation> appliedPresentations = new ArrayList<>();
	private Event shownEvent;
	private Presentation shownPresentation;
	private EventSelectionSync selectionSync;

	/**
	 * Ein Event "Prunksitzung" mit der Vorstellung "Freitag" und einem Verkauf ohne Platz, gespeichert; die Shell zeigt es
	 * mit "Freitag", die Boxen sind einmal nachgeführt.
	 */
	@BeforeEach
	void setUp() {
		sync = TestSyncServices.singleMode(appData);
		sync.startup(null, p -> {
		});
		Event created = new Event();
		created.setName("Prunksitzung");
		Booking sold = created.addPresentation("Freitag").addBooking();
		sold.setUnassigned(true);
		sold.setLastName("Müller");
		TestRepositories.events(sync).saveEvent(created);

		repository = TestRepositories.events(sync);
		event = repository.loadEvents().get(0);
		friday = event.getPresentations().get(0);
		selectionSync = new EventSelectionSync(repository, eventBox, presentationBox, e -> !e.isArchived(), () -> shownEvent,
				() -> shownPresentation, this::applyEventSelection, p -> {
					appliedPresentations.add(p);
					shownPresentation = p;
				}, warnings::add);
		eventBox.setItems(repository.loadEvents());
		eventBox.select(0);
		applyEventSelection(event);
		presentationBox.select(1);
		shownPresentation = friday;
		selectionSync.sync();
		appliedEvents.clear();
		eventBox.resetCounts();
		presentationBox.resetCounts();
	}

	/** Wie die Shell: Das Event gilt, die Vorstellungsliste wird neu gefüllt und steht auf "alle". */
	private void applyEventSelection(Event selected) {
		appliedEvents.add(selected);
		shownEvent = selected;
		shownPresentation = null;
		List<Presentation> items = new ArrayList<>();
		items.add(null);
		if (selected != null) {
			items.addAll(selected.getPresentations());
		}
		presentationBox.setItems(items);
		presentationBox.select(0);
	}

	/** Der andere Rechner lädt das Event, ändert es und speichert; das Repository der Shell arbeitet die Einheiten ein. */
	private ForeignResult otherChanges(java.util.function.Consumer<Event> change) throws IOException {
		StoreEventRepository other = TestRepositories.events(sync);
		Event loaded = other.loadEvents().get(0);
		change.accept(loaded);
		other.saveEvent(loaded);
		return repository.applyForeign(sync.eventUnits(event.getId()));
	}

	@Test
	void unchangedListsAreNotResetOnAForeignChange() throws IOException {
		otherChanges(e -> e.getPresentations().get(0).getBookings().get(0).setFirstName("Hans"));
		assertEquals("Hans", friday.getBookings().get(0).getFirstName(), "eingearbeitet, dasselbe Event-Objekt");

		selectionSync.sync();

		assertEquals(0, eventBox.replaced, "eine offene Liste bliebe sonst nicht offen");
		assertEquals(0, eventBox.selections);
		assertEquals(0, presentationBox.replaced);
		assertEquals(0, presentationBox.selections);
		assertSame(event, eventBox.selectedItem());
		assertSame(friday, presentationBox.selectedItem());
		assertTrue(appliedEvents.isEmpty());
		assertTrue(appliedPresentations.isEmpty());
	}

	@Test
	void renamedEventIsShownWithItsNewName() throws IOException {
		otherChanges(e -> e.setName("Große Prunksitzung"));

		selectionSync.sync();

		assertEquals(1, eventBox.replaced, "neu gesetzt zeigt die Box den neuen Namen");
		assertSame(event, eventBox.selectedItem());
		assertEquals(0, presentationBox.replaced);
		assertTrue(appliedEvents.isEmpty(), "dasselbe Objekt, der Inhalt bleibt");
	}

	@Test
	void addedPresentationResetsOnlyThePresentationListAndKeepsItsSelection() throws IOException {
		otherChanges(e -> e.addPresentation("Samstag"));
		assertEquals(2, event.getPresentations().size());

		selectionSync.sync();

		assertEquals(0, eventBox.replaced);
		assertEquals(1, presentationBox.replaced);
		assertEquals(3, presentationBox.items.size());
		assertSame(friday, presentationBox.selectedItem());
		assertTrue(appliedPresentations.isEmpty(), "die gezeigte Vorstellung bleibt dieselbe");
	}

	@Test
	void eventArchivedElsewhereClearsTheSelectionAndSaysSo() throws IOException {
		otherChanges(e -> e.setArchived(true));

		selectionSync.sync();

		assertEquals(1, appliedEvents.size());
		assertNull(appliedEvents.get(0), "die Auswahl ist leer");
		assertNull(shownEvent);
		assertTrue(eventBox.items.isEmpty());
		assertTrue(eventBox.valueCleared);
		assertTrue(presentationBox.valueCleared);
		assertEquals(List.of("„Prunksitzung“ wurde auf einem anderen Rechner archiviert."), warnings);
	}

	@Test
	void archivingWaitsWhileASaleOfTheEventIsUnsaved() throws IOException {
		friday.getBookings().get(0).setLastName("Eigen");

		ForeignResult result = otherChanges(e -> e.setArchived(true));
		selectionSync.sync();

		assertEquals(1, result.deferred().size(), "das Archivieren wartet");
		assertFalse(event.isArchived());
		assertSame(event, shownEvent, "die Shell zeigt das Event weiter, Speichern speichert den Verkauf");
		assertSame(event, eventBox.selectedItem());
		assertTrue(appliedEvents.isEmpty());
		assertTrue(warnings.isEmpty());
	}

	@Test
	void eventDeletedElsewhereSaysDeleted() {
		StoreEventRepository other = TestRepositories.events(sync);
		other.deleteEvent(other.loadEvents().get(0));
		repository.applyForeign(List.of(UnitKey.event(event.getId())));

		selectionSync.sync();

		assertNull(shownEvent);
		assertEquals(List.of("„Prunksitzung“ wurde auf einem anderen Rechner gelöscht."), warnings);
	}
}
