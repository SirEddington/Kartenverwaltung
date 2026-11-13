package de.eltviller_carneval_verein.karten.ui.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.model.Table;
import de.eltviller_carneval_verein.karten.ui.hall.PlanLabels;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Text;

/** Die Markierung über einem Sitz (KARV-26: das Kreuz des gesperrten Sitzes). */
class HallPlanPaneTest {

	@BeforeAll
	static void startFx() {
		try {
			Platform.startup(() -> {
			});
		} catch (IllegalStateException alreadyStarted) {
			// der Toolkit läuft schon (anderer Test im selben Lauf)
		}
		Platform.setImplicitExit(false);
	}

	private static <T> T onFx(Supplier<T> action) throws Exception {
		CompletableFuture<T> result = new CompletableFuture<>();
		Platform.runLater(() -> {
			try {
				result.complete(action.get());
			} catch (Throwable e) {
				result.completeExceptionally(e);
			}
		});
		return result.get(10, TimeUnit.SECONDS);
	}

	private final SeatingPlan plan = new Hall().addSeatingPlan();
	private final Presentation presentation = new Event().addPresentation("Samstag");
	private final Seat seat;

	HallPlanPaneTest() {
		Table table = plan.addTable(plan.addRow());
		seat = table.addSeat();
		Booking booking = presentation.addBooking();
		booking.setSeatId(seat.getId());
	}

	/** Dimmt den Sitz wie ein Suchfilter und legt ein Rechteck als Markierung darüber. */
	private static final class Marking implements HallPlanPane.Decorator {
		final Rectangle mark = new Rectangle(4, 4);
		Node seatNode;

		@Override
		public void decorateSeat(Booking booking, Seat seat, Node node) {
			seatNode = node;
			node.setOpacity(0.25);
		}

		@Override
		public Node seatMark(Booking booking, Seat seat) {
			return mark;
		}
	}

	@Test
	void theMarkLiesBetweenSeatAndNumber() throws Exception {
		Marking decorator = new Marking();

		List<Node> children = onFx(() -> {
			HallPlanPane pane = new HallPlanPane();
			pane.render(null, plan, presentation, PlanLabels.ALL, decorator);
			return List.copyOf(pane.contentPane().getChildren());
		});

		int seatIndex = children.indexOf(decorator.seatNode);
		int markIndex = children.indexOf(decorator.mark);
		int numberIndex = indexOf(children, Text.class);
		assertTrue(seatIndex >= 0 && markIndex == seatIndex + 1 && numberIndex == markIndex + 1,
				"Sitz " + seatIndex + ", Markierung " + markIndex + ", Nummer " + numberIndex);
	}

	@Test
	void theMarkIsDimmedWithItsSeatAndTakesNoClicks() throws Exception {
		Marking decorator = new Marking();

		double[] opacities = onFx(() -> {
			HallPlanPane pane = new HallPlanPane();
			pane.render(null, plan, presentation, PlanLabels.ALL, decorator);
			double dimmed = decorator.mark.getOpacity();
			decorator.seatNode.setOpacity(1.0);
			return new double[] { dimmed, decorator.mark.getOpacity() };
		});

		assertEquals(0.25, opacities[0]);
		assertEquals(1.0, opacities[1]);
		assertTrue(decorator.mark.isMouseTransparent(), "der Klick gehört dem Sitz");
	}

	@Test
	void withoutMarkNothingLiesOnTheSeat() throws Exception {
		List<Node> children = onFx(() -> {
			HallPlanPane pane = new HallPlanPane();
			pane.render(null, plan, presentation, PlanLabels.ALL, new HallPlanPane.Decorator() {
			});
			return List.copyOf(pane.contentPane().getChildren());
		});

		int seatIndex = indexOf(children, Circle.class);
		assertEquals(seatIndex + 1, indexOf(children, Text.class), "die Nummer folgt direkt auf den Sitz");
	}

	private static int indexOf(List<Node> children, Class<?> type) {
		for (int i = 0; i < children.size(); i++) {
			if (type.isInstance(children.get(i))) {
				return i;
			}
		}
		return -1;
	}
}
