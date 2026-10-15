package de.eltviller_carneval_verein.karten.ui.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.Table;
import javafx.geometry.Rectangle2D;

class SalesViewStateTest {

	private final SalesViewState state = new SalesViewState();
	private final Event event = new Event();
	private final Presentation presentation = event.addPresentation("P");
	private final Table table = presentation.addTable(1);

	@Test
	void replaceSelectionKeepsCommonSeatsAndReportsOnlyDifferences() {
		Seat a = table.addSeat(1);
		Seat b = table.addSeat(2);
		Seat c = table.addSeat(3);
		state.replaceSelection(List.of(a, b));
		AtomicInteger changes = new AtomicInteger();
		state.selectedSeats().addListener((javafx.collections.SetChangeListener<Seat>) change -> changes.incrementAndGet());

		state.replaceSelection(List.of(b, c));

		assertEquals(2, changes.get());
		assertEquals(Set.of(b, c), Set.copyOf(state.selectedSeats()));
	}

	@Test
	void replaceSelectionWithSameSeatsReportsNothing() {
		Seat a = table.addSeat(1);
		Seat b = table.addSeat(2);
		state.replaceSelection(List.of(a, b));
		AtomicInteger changes = new AtomicInteger();
		state.selectedSeats().addListener((javafx.collections.SetChangeListener<Seat>) change -> changes.incrementAndGet());

		state.replaceSelection(List.of(b, a));

		assertEquals(0, changes.get());
	}

	@Test
	void resetViewClearsSelectionAndVisibleRectButKeepsEventAndPresentation() {
		state.eventProperty().set(event);
		state.presentationProperty().set(presentation);
		state.replaceSelection(List.of(table.addSeat(1)));
		state.visibleHallRectProperty().set(new Rectangle2D(0, 0, 10, 10));

		state.resetView();

		assertTrue(state.selectedSeats().isEmpty());
		assertNull(state.visibleHallRectProperty().get());
		assertSame(event, state.eventProperty().get());
		assertSame(presentation, state.presentationProperty().get());
	}
}
