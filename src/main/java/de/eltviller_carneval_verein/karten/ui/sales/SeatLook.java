package de.eltviller_carneval_verein.karten.ui.sales;

import de.eltviller_carneval_verein.karten.model.SeatStatus;
import de.eltviller_carneval_verein.karten.ui.UiColors;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
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
import javafx.scene.shape.StrokeLineCap;

/**
 * Wie ein Sitz im Saalplan seinen Status zeigt (Saalübersicht, Kundenfenster, Legende). Mit barrierefreier Darstellung
 * (KARV-26, Menü "Anzeige") hängt der Status nicht an der Farbe allein: Frei ist ein weißer Sitz mit grünem Ring,
 * reserviert gelb mit dunklen Streifen, verkauft voll rot, gesperrt grau mit weißem Kreuz. So bleiben die vier Zustände auch
 * bei Rot-Grün-Schwäche und in Graustufen unterscheidbar. Ohne sie sind es die vier vollen Farben wie bis 1.1.0.
 * <p>
 * Ring und Streifen sind Verläufe mit harten Übergängen, also reine Füllung: Sie bleiben beim Zoomen scharf und
 * brauchen keinen eigenen Knoten. Nur das Kreuz ist ein eigener Knoten ({@link #cross}); in der Saalansicht legt die
 * {@link HallPlanPane} es über den Sitz und lässt Klicks zum Sitz durch.
 */
public final class SeatLook {

	/** Bis wohin (Anteil des Radius) der freie Sitz innen weiß ist; außen folgt der grüne Ring bis zum schwarzen Rand. */
	private static final double FREE_INNER = 0.62;
	/** Dunkler Streifen der Reservierung: dunkles Gelb, hebt sich auch ohne Farbsehen vom Gelb ab. */
	private static final Color STRIPE = Color.web("#8A6D00");
	/** Anteil des dunklen Streifens an einer Periode. */
	private static final double STRIPE_SHARE = 0.35;
	/** Länge einer Streifenperiode als Anteil der Sitzgröße (proportional): etwa vier Streifen über den Sitz. */
	private static final double STRIPE_PERIOD = 0.25;
	/** Das Kreuz des gesperrten Sitzes: Halbe Länge eines Balkens und Strichstärke als Anteil des Radius. */
	private static final double CROSS_REACH = 0.55;
	private static final double CROSS_WIDTH = 0.16;
	/** Weiß: hebt sich vom Grau ab, und die schwarze Sitznummer darüber bleibt lesbar (ein dunkles Kreuz verschluckte sie). */
	static final Color CROSS = Color.WHITE;

	private static final RadialGradient FREE_RING = new RadialGradient(0, 0, 0.5, 0.5, 0.5, true, CycleMethod.NO_CYCLE,
			new Stop(0, Color.WHITE), new Stop(FREE_INNER, Color.WHITE), new Stop(FREE_INNER, UiColors.SEAT_FREE.getFxColor()),
			new Stop(1, UiColors.SEAT_FREE.getFxColor()));
	private static final LinearGradient RESERVED_STRIPES = new LinearGradient(0, 0, STRIPE_PERIOD, STRIPE_PERIOD, true, CycleMethod.REPEAT,
			new Stop(0, UiColors.SEAT_RESERVED.getFxColor()), new Stop(1 - STRIPE_SHARE, UiColors.SEAT_RESERVED.getFxColor()),
			new Stop(1 - STRIPE_SHARE, STRIPE), new Stop(1, STRIPE));

	/** Radius der Muster-Sitze in der Legende (px). */
	private static final double LEGEND_RADIUS = 8;

	private SeatLook() {
	}

	/**
	 * Die Füllung eines Sitzes.
	 *
	 * @param patterns ob die barrierefreie Darstellung an ist
	 */
	public static Paint fill(SeatStatus status, boolean patterns) {
		return switch (status) {
		case FREE -> patterns ? FREE_RING : UiColors.SEAT_FREE.getFxColor();
		case RESERVED -> patterns ? RESERVED_STRIPES : UiColors.SEAT_RESERVED.getFxColor();
		case SOLD -> UiColors.SEAT_SOLD.getFxColor();
		case BLOCKED -> UiColors.SEAT_BLOCKED.getFxColor();
		};
	}

	/** Ob über dem Sitz ein Kreuz liegt ({@link #cross}): nur beim gesperrten Sitz mit barrierefreier Darstellung. */
	public static boolean hasCross(SeatStatus status, boolean patterns) {
		return patterns && status == SeatStatus.BLOCKED;
	}

	/**
	 * Das Kreuz eines gesperrten Sitzes, ganz innerhalb des Sitzes: ein Pfad aus zwei Balken.
	 *
	 * @param centerX Mittelpunkt des Sitzes
	 * @param centerY Mittelpunkt des Sitzes
	 * @param radius  Radius des Sitzes
	 */
	public static Path cross(double centerX, double centerY, double radius) {
		// Die Enden liegen CROSS_REACH * radius vom Mittelpunkt entfernt, auf den Diagonalen
		double d = radius * CROSS_REACH / Math.sqrt(2);
		Path cross = new Path(new MoveTo(centerX - d, centerY - d), new LineTo(centerX + d, centerY + d), new MoveTo(centerX + d, centerY - d),
				new LineTo(centerX - d, centerY + d));
		cross.setStroke(CROSS);
		cross.setStrokeWidth(radius * CROSS_WIDTH);
		cross.setStrokeLineCap(StrokeLineCap.BUTT);
		return cross;
	}

	/** Die Legende unter der Saalübersicht: je Status ein Muster-Sitz mit Namen, in der Reihenfolge von {@link SeatStatus}. */
	public static Pane legend() {
		HBox legend = new HBox(16);
		legend.setAlignment(Pos.CENTER_LEFT);
		legend.getStyleClass().add("seat-legend");
		for (SeatStatus status : SeatStatus.values()) {
			Circle seat = new Circle(LEGEND_RADIUS, LEGEND_RADIUS, LEGEND_RADIUS, fill(status, true));
			seat.setStroke(Color.BLACK);
			Group sample = hasCross(status, true) ? new Group(seat, cross(LEGEND_RADIUS, LEGEND_RADIUS, LEGEND_RADIUS)) : new Group(seat);
			Label name = new Label(status.getDisplayName(), sample);
			name.setGraphicTextGap(6);
			legend.getChildren().add(name);
		}
		return legend;
	}
}
