package de.eltviller_carneval_verein.karten.ui.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;
import javafx.geometry.Rectangle2D;

class SalesViewStateTest {

	private final SalesViewState state = new SalesViewState();
	private final Event event = new Event();
	private final Presentation presentation = event.addPresentation("P");
	private final TestBookings bookings = new TestBookings();

	private Booking seat(int number) {
		return bookings.booking(presentation, 1, number);
	}

	@Test
	void replaceSelectionKeepsCommonSeatsAndReportsOnlyDifferences() {
		Booking a = seat(1);
		Booking b = seat(2);
		Booking c = seat(3);
		state.replaceSelection(List.of(a, b));
		AtomicInteger changes = new AtomicInteger();
		state.selectedBookings().addListener((javafx.collections.SetChangeListener<Booking>) change -> changes.incrementAndGet());

		state.replaceSelection(List.of(b, c));

		assertEquals(2, changes.get());
		assertEquals(Set.of(b, c), Set.copyOf(state.selectedBookings()));
	}

	@Test
	void replaceSelectionWithSameSeatsReportsNothing() {
		Booking a = seat(1);
		Booking b = seat(2);
		state.replaceSelection(List.of(a, b));
		AtomicInteger changes = new AtomicInteger();
		state.selectedBookings().addListener((javafx.collections.SetChangeListener<Booking>) change -> changes.incrementAndGet());

		state.replaceSelection(List.of(b, a));

		assertEquals(0, changes.get());
	}

	@Test
	void resetViewClearsSelectionAndVisibleRectButKeepsEventAndPresentation() {
		state.eventProperty().set(event);
		state.presentationProperty().set(presentation);
		state.replaceSelection(List.of(seat(1)));
		state.visibleHallRectProperty().set(new Rectangle2D(0, 0, 10, 10));

		state.resetView();

		assertTrue(state.selectedBookings().isEmpty());
		assertNull(state.visibleHallRectProperty().get());
		assertSame(event, state.eventProperty().get());
		assertSame(presentation, state.presentationProperty().get());
	}
}
