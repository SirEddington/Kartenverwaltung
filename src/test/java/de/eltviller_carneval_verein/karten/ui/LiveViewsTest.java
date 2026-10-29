package de.eltviller_carneval_verein.karten.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.repository.ForeignBatch;
import de.eltviller_carneval_verein.karten.repository.RepositoryListener;
import de.eltviller_carneval_verein.karten.repository.TestRepositories;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.UnitType;

class LiveViewsTest {

	private static final UnitKey BOOKING = UnitKey.booking("e1", "p1", "s1");
	private static final UnitKey OTHER_BOOKING = UnitKey.booking("e1", "p2", "s1");
	private static final UnitKey HALL = UnitKey.hall("h1");

	private final List<Runnable> queue = new ArrayList<>();
	private int refreshes = 0;

	private void tick() {
		List<Runnable> toRun = new ArrayList<>(queue);
		queue.clear();
		toRun.forEach(Runnable::run);
	}

	private RepositoryListener<Event> listener(Predicate<Set<UnitKey>> relevant) {
		return LiveViews.coalescing(queue::add, relevant, () -> refreshes++);
	}

	private static ForeignBatch<Event> batch(UnitKey... units) {
		return TestRepositories.batchOf(Set.of(units));
	}

	@Test
	void manyCallbacksInOneTickRefreshOnce() {
		RepositoryListener<Event> listener = listener(units -> true);
		Event event = new Event();

		for (int i = 0; i < 500; i++) {
			listener.changedByOthers(event, batch(BOOKING));
		}
		listener.addedByOthers(event);
		listener.removedByOthers(event);

		assertEquals(0, refreshes, "refreshed before the tick");
		assertEquals(1, queue.size());
		tick();
		assertEquals(1, refreshes);

		listener.changedByOthers(event, batch(BOOKING));
		tick();
		assertEquals(2, refreshes, "the next tick refreshes again");
	}

	@Test
	void irrelevantUnitsDoNotRefresh() {
		RepositoryListener<Event> listener = listener(LiveViews.ofTypes(UnitType.HALL));
		Event event = new Event();

		listener.changedByOthers(event, batch(BOOKING, OTHER_BOOKING));
		tick();

		assertEquals(0, refreshes);
		assertTrue(queue.isEmpty(), "nothing was scheduled");

		listener.changedByOthers(event, batch(BOOKING, HALL));
		tick();

		assertEquals(1, refreshes, "one relevant unit among irrelevant ones is enough");
	}

	@Test
	void addedAndRemovedAlwaysRefresh() {
		RepositoryListener<Event> listener = listener(units -> false);
		Event event = new Event();

		listener.addedByOthers(event);
		tick();
		assertEquals(1, refreshes);

		listener.removedByOthers(event);
		tick();
		assertEquals(2, refreshes);
	}

	@Test
	void ofTypesMatchesOnlyTheGivenTypes() {
		Predicate<Set<UnitKey>> relevant = LiveViews.ofTypes(UnitType.EVENT, UnitType.PRESENTATION);

		assertTrue(relevant.test(Set.of(UnitKey.event("e1"))));
		assertTrue(relevant.test(Set.of(UnitKey.presentation("e1", "p1"))));
		assertFalse(relevant.test(Set.of(BOOKING, HALL)));
		assertTrue(relevant.test(Set.of(BOOKING, UnitKey.event("e1"))));
	}

	@Test
	void touchesFindsUnitsOfTheShownPresentationAndItsEventHead() {
		Event event = new Event("e1");
		Presentation presentation = new Presentation("p1");

		assertTrue(LiveViews.touches(Set.of(BOOKING), event, presentation));
		assertTrue(LiveViews.touches(Set.of(UnitKey.presentation("e1", "p1")), event, presentation));
		assertTrue(LiveViews.touches(Set.of(UnitKey.event("e1")), event, presentation), "the head carries names and prices");
		assertFalse(LiveViews.touches(Set.of(OTHER_BOOKING), event, presentation));
		assertFalse(LiveViews.touches(Set.of(UnitKey.booking("e2", "p1", "s1")), event, presentation));
		assertFalse(LiveViews.touches(Set.of(HALL), event, presentation));
	}

	@Test
	void touchesWithoutPresentationMeansTheWholeEvent() {
		Event event = new Event("e1");

		assertTrue(LiveViews.touches(Set.of(OTHER_BOOKING), event, null));
		assertFalse(LiveViews.touches(Set.of(UnitKey.booking("e2", "p1", "s1")), event, null));
		assertFalse(LiveViews.touches(Set.of(OTHER_BOOKING), null, null));
	}

	@Test
	void touchesHallComparesTheHallId() {
		assertTrue(LiveViews.touchesHall(Set.of(HALL), "h1"));
		assertFalse(LiveViews.touchesHall(Set.of(HALL), "h2"));
		assertFalse(LiveViews.touchesHall(Set.of(HALL), null));
		assertFalse(LiveViews.touchesHall(Set.of(BOOKING), "h1"));
	}
}
