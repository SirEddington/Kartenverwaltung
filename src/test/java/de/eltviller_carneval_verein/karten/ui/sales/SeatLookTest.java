package de.eltviller_carneval_verein.karten.ui.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import de.eltviller_carneval_verein.karten.model.SeatStatus;
import de.eltviller_carneval_verein.karten.ui.UiColors;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Paint;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Circle;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.Path;
import javafx.scene.shape.PathElement;

/** Wie ein Sitz seinen Status zeigt, mit und ohne barrierefreie Darstellung (KARV-26). */
class SeatLookTest {

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

	// --- Sehen ohne Farbe: Simulation nach Machado et al. (2009), Schweregrad 1, im linearen RGB ---

	private static final double[][] DEUTERANOPIA = { { 0.367322, 0.860646, -0.227968 }, { 0.280085, 0.672501, 0.047413 },
			{ -0.011820, 0.042940, 0.968881 } };
	private static final double[][] PROTANOPIA = { { 0.152286, 1.052583, -0.204868 }, { 0.114503, 0.786281, 0.099216 },
			{ -0.003882, -0.048116, 1.051998 } };

	private static double linear(double channel) {
		return channel <= 0.04045 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
	}

	private static double[] linearRgb(Color color) {
		return new double[] { linear(color.getRed()), linear(color.getGreen()), linear(color.getBlue()) };
	}

	/** Relative Leuchtdichte nach WCAG aus linearen Kanälen. */
	private static double luminance(double[] rgb) {
		return 0.2126 * clamp(rgb[0]) + 0.7152 * clamp(rgb[1]) + 0.0722 * clamp(rgb[2]);
	}

	private static double clamp(double value) {
		return Math.max(0, Math.min(1, value));
	}

	private static UnaryOperator<double[]> simulate(double[][] matrix) {
		return rgb -> new double[] { matrix[0][0] * rgb[0] + matrix[0][1] * rgb[1] + matrix[0][2] * rgb[2],
				matrix[1][0] * rgb[0] + matrix[1][1] * rgb[1] + matrix[1][2] * rgb[2],
				matrix[2][0] * rgb[0] + matrix[2][1] * rgb[1] + matrix[2][2] * rgb[2] };
	}

	/** Normalsicht, Graustufen (nur Helligkeit zählt) und die beiden Rot-Grün-Schwächen. */
	private static List<UnaryOperator<double[]>> visions() {
		return List.of(UnaryOperator.identity(), simulate(DEUTERANOPIA), simulate(PROTANOPIA));
	}

	private static double contrast(Color a, Color b, UnaryOperator<double[]> vision) {
		double la = luminance(vision.apply(linearRgb(a)));
		double lb = luminance(vision.apply(linearRgb(b)));
		return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
	}

	private static List<Color> colors(Paint paint) {
		List<Color> colors = new ArrayList<>();
		if (paint instanceof Color color) {
			colors.add(color);
		} else if (paint instanceof RadialGradient radial) {
			radial.getStops().stream().map(Stop::getColor).forEach(colors::add);
		} else if (paint instanceof LinearGradient linear) {
			linear.getStops().stream().map(Stop::getColor).forEach(colors::add);
		}
		return colors;
	}

	// --- Mit barrierefreier Darstellung ---

	@Test
	void freeIsAWhiteSeatWithAGreenRing() {
		RadialGradient fill = assertInstanceOf(RadialGradient.class, SeatLook.fill(SeatStatus.FREE, true));

		assertTrue(fill.isProportional());
		assertEquals(0.5, fill.getCenterX());
		assertEquals(0.5, fill.getCenterY());
		List<Stop> stops = fill.getStops();
		assertEquals(Color.WHITE, stops.get(0).getColor(), "innen weiß, dort steht die Sitznummer");
		assertEquals(UiColors.SEAT_FREE.getFxColor(), stops.get(stops.size() - 1).getColor(), "außen das gewohnte Grün");
		// Harter Übergang: zwei Stopps an derselben Stelle, sonst verliefe das Grün ins Weiß
		long hardEdges = stops.stream().map(Stop::getOffset).distinct().count();
		assertEquals(stops.size() - 1, hardEdges);
	}

	@Test
	void reservedIsYellowWithDiagonalStripes() {
		LinearGradient fill = assertInstanceOf(LinearGradient.class, SeatLook.fill(SeatStatus.RESERVED, true));

		assertEquals(CycleMethod.REPEAT, fill.getCycleMethod());
		assertTrue(fill.isProportional(), "die Streifen wachsen mit dem Sitz und bleiben beim Zoomen scharf");
		assertNotEquals(0, fill.getEndX() - fill.getStartX(), "diagonal");
		assertNotEquals(0, fill.getEndY() - fill.getStartY(), "diagonal");
		assertTrue(colors(fill).contains(UiColors.SEAT_RESERVED.getFxColor()));
		assertEquals(2, colors(fill).stream().distinct().count(), "Gelb und ein dunkler Streifen");
	}

	@Test
	void soldStaysFullEcvRedAndBlockedFullGray() {
		assertEquals(UiColors.SEAT_SOLD.getFxColor(), SeatLook.fill(SeatStatus.SOLD, true));
		assertEquals(UiColors.SEAT_BLOCKED.getFxColor(), SeatLook.fill(SeatStatus.BLOCKED, true));
	}

	@ParameterizedTest
	@EnumSource(SeatStatus.class)
	void onlyBlockedGetsACross(SeatStatus status) {
		assertEquals(status == SeatStatus.BLOCKED, SeatLook.hasCross(status, true));
		assertFalse(SeatLook.hasCross(status, false), "ohne barrierefreie Darstellung nie");
	}

	@Test
	void freeAndSoldDifferInLightnessForEveryone() {
		Color freeInside = colors(SeatLook.fill(SeatStatus.FREE, true)).get(0);
		Color sold = (Color) SeatLook.fill(SeatStatus.SOLD, true);

		for (UnaryOperator<double[]> vision : visions()) {
			assertTrue(contrast(freeInside, sold, vision) >= 3, "frei und verkauft verschwimmen: " + contrast(freeInside, sold, vision));
		}
	}

	@Test
	void theStripesStayVisibleForEveryone() {
		List<Color> stripes = colors(SeatLook.fill(SeatStatus.RESERVED, true)).stream().distinct().toList();

		for (UnaryOperator<double[]> vision : visions()) {
			assertTrue(contrast(stripes.get(0), stripes.get(1), vision) >= 3, "Streifen verschwinden: " + contrast(stripes.get(0), stripes.get(1), vision));
		}
	}

	@Test
	void theCrossStandsOutAndTheNumberStaysReadableOnIt() {
		// Die Sitznummer ist schwarz und liegt über dem Kreuz (Handtest: ein dunkles Kreuz verschluckte die "1")
		Color gray = (Color) SeatLook.fill(SeatStatus.BLOCKED, true);

		for (UnaryOperator<double[]> vision : visions()) {
			assertTrue(contrast(SeatLook.CROSS, gray, vision) >= 2.5, "Kreuz verschwindet im Grau");
			assertTrue(contrast(SeatLook.CROSS, Color.BLACK, vision) >= 7, "Nummer verschwindet im Kreuz");
		}
	}

	@Test
	void withoutColorTheFourStatesDifferInForm() {
		// Was ohne Farbsehen bleibt: Art der Füllung und Kreuz
		List<String> forms = new ArrayList<>();
		for (SeatStatus status : SeatStatus.values()) {
			forms.add(SeatLook.fill(status, true).getClass().getSimpleName() + "/" + SeatLook.hasCross(status, true));
		}
		// Verkauft und gesperrt sind beide voll; das Kreuz trennt sie
		assertEquals(SeatStatus.values().length, forms.stream().distinct().count(), forms.toString());
	}

	@Test
	void theCrossIsTwoBarsInsideTheSeat() throws Exception {
		Path cross = onFx(() -> SeatLook.cross(100, 50, 20));

		List<PathElement> elements = cross.getElements();
		assertEquals(4, elements.size(), "zweimal Ansetzen und Ziehen");
		for (PathElement element : elements) {
			double x = element instanceof MoveTo m ? m.getX() : ((LineTo) element).getX();
			double y = element instanceof MoveTo m ? m.getY() : ((LineTo) element).getY();
			assertTrue(Math.hypot(x - 100, y - 50) < 20, "das Kreuz ragt über den Sitz hinaus");
		}
	}

	// --- Ohne barrierefreie Darstellung: wie bis 1.1.0 ---

	@Test
	void withoutPatternsEachStateIsItsPlainColor() {
		assertEquals(Color.web("#2ECC71"), SeatLook.fill(SeatStatus.FREE, false));
		assertEquals(Color.web("#FFE000"), SeatLook.fill(SeatStatus.RESERVED, false));
		assertEquals(Color.web("#E83B3B"), SeatLook.fill(SeatStatus.SOLD, false));
		assertEquals(Color.web("#999999"), SeatLook.fill(SeatStatus.BLOCKED, false));
	}

	// --- Legende ---

	@Test
	void theLegendNamesTheFourStatesWithTheirLook() throws Exception {
		// Ein Label zeigt seine Grafik erst mit Skin, also in einer Szene nach dem CSS-Durchgang
		Pane legend = onFx(() -> {
			Pane pane = SeatLook.legend();
			new Scene(pane);
			pane.applyCss();
			pane.layout();
			return pane;
		});

		List<String> names = legend.lookupAll(".label").stream().map(node -> ((Label) node).getText()).toList();
		assertEquals(List.of("Frei", "Reserviert", "Verkauft", "Gesperrt"), names);
		List<Paint> fills = legend.lookupAll("Circle").stream().map(node -> ((Circle) node).getFill()).toList();
		List<Paint> expected = new ArrayList<>();
		for (SeatStatus status : SeatStatus.values()) {
			expected.add(SeatLook.fill(status, true));
		}
		assertEquals(expected, fills);
		long crosses = legend.lookupAll("Path").stream().filter(node -> SeatLook.CROSS.equals(((Path) node).getStroke())).count();
		assertEquals(1, crosses, "ein Kreuz, beim gesperrten Sitz");
	}
}
