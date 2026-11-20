package de.eltviller_carneval_verein.karten.ui.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.event.Event;
import javafx.event.EventType;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

/** Die Bausteine der Leiste „Gruppieren“ (KARV-193, Spec Abschnitt 14.2). */
class GroupingBarTest {

	private static final TicketColumn A = TicketColumn.LAST_NAME;
	private static final TicketColumn B = TicketColumn.FIRST_NAME;
	private static final TicketColumn C = TicketColumn.TABLE;

	private final ObjectProperty<List<TicketColumn>> grouping = new SimpleObjectProperty<>(List.of());
	private HBox chips;
	private GroupingBar bar;
	private Stage stage;

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

	private static void run(Runnable action) throws Exception {
		onFx(() -> {
			action.run();
			return null;
		});
	}

	@AfterEach
	void closeStage() throws Exception {
		if (stage != null) {
			run(stage::hide);
		}
	}

	/** Baut die Leiste mit der Gruppierung in einem Fenster auf. */
	private void create(TicketColumn... initial) throws Exception {
		grouping.set(List.of(initial));
		run(() -> {
			chips = new HBox(6);
			bar = new GroupingBar(chips, grouping);
			stage = new Stage();
			stage.setScene(new Scene(chips, 600, 80));
			stage.show();
		});
		layout();
	}

	private void layout() throws Exception {
		run(() -> {
			chips.applyCss();
			chips.layout();
		});
	}

	private Region chip(TicketColumn column) throws Exception {
		return onFx(() -> chipQuietly(column));
	}

	/** Der Baustein der Spalte, nur auf dem FX-Thread. */
	private Region chipQuietly(TicketColumn column) {
		return bar.chip(column).orElseThrow(() -> new AssertionError("kein Baustein für " + column));
	}

	/**
	 * Ein Punkt im Baustein in Szenenkoordinaten; {@code fraction} ist der Anteil seiner Breite von links. Legt die Leiste
	 * vorher aus: Nach einer Änderung stehen neue Bausteine sonst erst mit dem nächsten Takt an ihrem Platz.
	 */
	private double[] at(TicketColumn column, double fraction) throws Exception {
		layout();
		Region chip = chip(column);
		return onFx(() -> {
			Bounds bounds = chip.localToScene(chip.getLayoutBounds());
			return new double[] { bounds.getMinX() + bounds.getWidth() * fraction, bounds.getMinY() + bounds.getHeight() / 2 };
		});
	}

	private void mouse(Node target, EventType<MouseEvent> type, double[] at) throws Exception {
		run(() -> {
			PickResult pick = new PickResult(target, at[0], at[1]);
			Event.fireEvent(target, new MouseEvent(this, target, type, at[0], at[1], at[0], at[1], MouseButton.PRIMARY, 1, false, false, false,
					false, type != MouseEvent.MOUSE_RELEASED, false, false, false, false, false, false, false, pick));
		});
	}

	private void key(Node target, KeyCode code, boolean ctrl) throws Exception {
		run(() -> Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, ctrl, false, false)));
	}

	/** Drückt auf {@code dragged}, zieht in zwei Schritten nach {@code to} und lässt dort los. */
	private void drag(TicketColumn dragged, double[] to) throws Exception {
		Region chip = chip(dragged);
		double[] from = at(dragged, 0.5);
		mouse(chip, MouseEvent.MOUSE_PRESSED, from);
		mouse(chip, MouseEvent.MOUSE_DRAGGED, new double[] { (from[0] + to[0]) / 2, (from[1] + to[1]) / 2 });
		mouse(chip, MouseEvent.MOUSE_DRAGGED, to);
		mouse(chip, MouseEvent.MOUSE_RELEASED, to);
	}

	private List<String> texts() throws Exception {
		return onFx(() -> chips.getChildren().stream().filter(Node::isVisible).map(node -> node instanceof Label label ? label.getText()
				: ((Label) ((HBox) node).getChildren().get(0)).getText()).toList());
	}

	@Test
	void zeigtBausteineInReihenfolge() throws Exception {
		create(A, B, C);

		assertEquals(List.of(A, B, C), onFx(() -> bar.shown()));
		assertEquals(List.of("Gruppiert nach:", "Nachname", "›", "Vorname", "›", "Tisch"), texts());
		Region chip = chip(B);
		assertTrue(onFx(chip::isFocusTraversable), "die Bausteine lassen sich mit Tab fokussieren");
		Button remove = onFx(() -> (Button) ((HBox) chip).getChildren().get(1));
		assertEquals("×", onFx(remove::getText));
		assertFalse(onFx(remove::isFocusTraversable), "Tab geht von Baustein zu Baustein, nicht über die Kreuze");

		run(() -> grouping.set(List.of()));
		assertEquals(List.of(), texts(), "ohne Gruppierung steht dort nichts");
	}

	@Test
	void verschiebenPerMethode() throws Exception {
		create(A, B, C);

		run(() -> bar.move(0, 2));
		assertEquals(List.of(B, C, A), grouping.get());
		assertEquals(List.of(B, C, A), onFx(() -> bar.shown()));

		run(() -> bar.move(1, 1));
		assertEquals(List.of(B, C, A), grouping.get(), "an dieselbe Stelle ändert nichts");
	}

	@Test
	void ziehenVorDenErsten() throws Exception {
		create(A, B, C);
		Region chip = chip(C);

		double[] from = at(C, 0.5);
		mouse(chip, MouseEvent.MOUSE_PRESSED, from);
		mouse(chip, MouseEvent.MOUSE_DRAGGED, at(A, 0.25));
		assertEquals(0, onFx(() -> bar.dropIndex()), "linke Hälfte: davor");
		assertTrue(onFx(() -> bar.marker().isVisible()), "die Einfügemarke zeigt, wo der Baustein landet");
		double markerX = onFx(() -> bar.marker().getBoundsInParent().getMaxX());
		double chipX = onFx(() -> chipQuietly(A).getBoundsInParent().getMinX());
		assertTrue(markerX <= chipX, "die Marke steht vor dem ersten Baustein");
		assertEquals(List.of(A, B, C), grouping.get(), "während des Ziehens ändert sich nichts");

		mouse(chip, MouseEvent.MOUSE_RELEASED, at(A, 0.25));

		assertEquals(List.of(C, A, B), grouping.get());
		assertFalse(onFx(() -> bar.marker().isVisible()));
		assertSame(onFx(() -> chipQuietly(C)), onFx(() -> stage.getScene().getFocusOwner()), "der Fokus bleibt auf dem verschobenen Baustein");
	}

	@Test
	void ziehenHinterDenLetzten() throws Exception {
		create(A, B, C);

		drag(A, at(C, 0.75));

		assertEquals(List.of(B, C, A), grouping.get(), "rechte Hälfte: dahinter");
	}

	@Test
	void ziehenInDieMitte() throws Exception {
		create(A, B, C);

		drag(A, at(C, 0.25));
		assertEquals(List.of(B, A, C), grouping.get(), "linke Hälfte von C: vor C");

		drag(C, at(B, 0.75));
		assertEquals(List.of(B, C, A), grouping.get(), "rechte Hälfte von B: hinter B");
	}

	@Test
	void ziehenOhneZielAendertNichts() throws Exception {
		create(A, B, C);
		double[] behind = at(C, 0.5);
		behind[0] += 200; // rechts neben den Bausteinen

		drag(A, behind);
		assertEquals(List.of(A, B, C), grouping.get(), "Loslassen außerhalb der Bausteine");
		assertFalse(onFx(() -> bar.marker().isVisible()));

		double[] below = at(B, 0.75);
		below[1] += 100; // unter der Leiste
		drag(A, below);
		assertEquals(List.of(A, B, C), grouping.get());

		// ein Druck ohne Ziehen, auch auf der rechten Hälfte
		Region chip = chip(A);
		mouse(chip, MouseEvent.MOUSE_PRESSED, at(A, 0.75));
		mouse(chip, MouseEvent.MOUSE_RELEASED, at(A, 0.75));
		assertEquals(List.of(A, B, C), grouping.get(), "ein Druck ohne Ziehen ändert nichts");

		// über den eigenen Platz gezogen
		drag(B, at(A, 0.75));
		assertEquals(List.of(A, B, C), grouping.get(), "hinter A ist B schon");
	}

	@Test
	void escAendertNichts() throws Exception {
		create(A, B, C);
		Region chip = chip(A);

		mouse(chip, MouseEvent.MOUSE_PRESSED, at(A, 0.5));
		mouse(chip, MouseEvent.MOUSE_DRAGGED, at(C, 0.75));
		assertTrue(onFx(() -> bar.marker().isVisible()));
		key(chip, KeyCode.ESCAPE, false);
		assertFalse(onFx(() -> bar.marker().isVisible()), "Esc bricht das Ziehen ab");
		mouse(chip, MouseEvent.MOUSE_DRAGGED, at(C, 0.75));
		mouse(chip, MouseEvent.MOUSE_RELEASED, at(C, 0.75));

		assertEquals(List.of(A, B, C), grouping.get());
		assertEquals(-1, onFx(() -> bar.dropIndex()));
	}

	@Test
	void kreuzEntfernt() throws Exception {
		create(A, B, C);
		Region chip = chip(B);

		run(() -> ((Button) ((HBox) chip).getChildren().get(1)).fire());

		assertEquals(List.of(A, C), grouping.get(), "wie Abhaken im Menü");
		assertEquals(List.of("Gruppiert nach:", "Nachname", "›", "Tisch"), texts());
	}

	@Test
	void druckAufsKreuzZiehtNicht() throws Exception {
		create(A, B, C);
		Region chip = chip(A);
		Button remove = onFx(() -> (Button) ((HBox) chip).getChildren().get(1));
		double[] onCross = onFx(() -> {
			Bounds bounds = remove.localToScene(remove.getLayoutBounds());
			return new double[] { bounds.getCenterX(), bounds.getCenterY() };
		});

		mouse(remove, MouseEvent.MOUSE_PRESSED, onCross);
		mouse(remove, MouseEvent.MOUSE_DRAGGED, at(C, 0.75));

		// kein Loslassen: Der gedrückte Knopf löste dabei aus, ein echter Zeiger hätte ihn vorher verlassen
		assertFalse(onFx(() -> bar.marker().isVisible()), "das Kreuz ist kein Griff zum Ziehen");
		assertEquals(-1, onFx(() -> bar.dropIndex()));
		assertEquals(List.of(A, B, C), grouping.get());
	}

	@Test
	void strgLinksRechtsVerschiebt() throws Exception {
		create(A, B, C);
		run(() -> chipQuietly(B).requestFocus());

		key(chip(B), KeyCode.LEFT, true);
		assertEquals(List.of(B, A, C), grouping.get());
		assertSame(onFx(() -> chipQuietly(B)), onFx(() -> stage.getScene().getFocusOwner()), "der Fokus bleibt auf dem verschobenen Baustein");

		key(chip(B), KeyCode.LEFT, true);
		assertEquals(List.of(B, A, C), grouping.get(), "ganz vorn bleibt er vorn");

		key(chip(B), KeyCode.RIGHT, true);
		key(chip(B), KeyCode.RIGHT, true);
		assertEquals(List.of(A, C, B), grouping.get());
		assertSame(onFx(() -> chipQuietly(B)), onFx(() -> stage.getScene().getFocusOwner()));

		key(chip(B), KeyCode.RIGHT, true);
		assertEquals(List.of(A, C, B), grouping.get(), "ganz hinten bleibt er hinten");

		key(chip(B), KeyCode.LEFT, false);
		assertEquals(List.of(A, C, B), grouping.get(), "ohne Strg verschiebt nichts");
	}

	@Test
	void entfEntfernt() throws Exception {
		create(A, B, C);
		run(() -> chipQuietly(B).requestFocus());

		key(chip(B), KeyCode.DELETE, false);

		assertEquals(List.of(A, C), grouping.get());
		assertSame(onFx(() -> chipQuietly(C)), onFx(() -> stage.getScene().getFocusOwner()), "der Fokus bleibt in der Leiste");
	}

	@Test
	void folgtAenderungVonAussen() throws Exception {
		create();
		assertEquals(List.of(), onFx(() -> bar.shown()));

		run(() -> grouping.set(List.of(C, A))); // etwa das Menü oder der Start
		assertEquals(List.of(C, A), onFx(() -> bar.shown()));
		assertEquals(List.of("Gruppiert nach:", "Tisch", "›", "Nachname"), texts());

		run(() -> grouping.set(List.of()));
		assertEquals(List.of(), onFx(() -> bar.shown()));
		assertEquals(List.of(), texts());
	}
}
