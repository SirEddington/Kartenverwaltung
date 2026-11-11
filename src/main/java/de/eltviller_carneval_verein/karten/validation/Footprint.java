package de.eltviller_carneval_verein.karten.validation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import de.eltviller_carneval_verein.karten.model.HallObject;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.Shape;
import de.eltviller_carneval_verein.karten.model.Table;

/**
 * Grundfläche eines Tischs (Platte und Stühle) oder Hallenobjekts für die Überlappungs- und Lageprüfungen: eine nicht
 * leere Liste gedrehter Rechtecke und Kreise samt achsparallelem Hüllrechteck. Unveränderlich, ohne JavaFX.
 * <p>
 * Koordinaten in cm, y nach unten. Ein Rechteck dreht sich um seinen Mittelpunkt, in Grad im Uhrzeigersinn, mit derselben
 * Formel wie die Anordnung der Stühle ({@code SeatingPlanLayout.applySeatPositions}): ein Punkt {@code (lx, ly)} im
 * Rechteck liegt bei {@code x = cx + lx·cos − ly·sin}, {@code y = cy + lx·sin + ly·cos}.
 */
final class Footprint {

	/**
	 * Toleranz in cm: Überlappungen bis 1 cm zählen nicht (an Ecken bis etwa 1,2 cm), ebenso ein Überstand über den
	 * Hallenrand oder aus einer Fläche bis 1 cm. Begründung in {@code docs/decisions.md}, Abschnitt "Layout checks only
	 * warn".
	 */
	static final double TOLERANCE = 1.0;

	/** Verkleinerung jeder Form ringsum für {@link #overlaps}. */
	private static final double SHRINK = TOLERANCE / 2;

	private final List<Part> parts;
	/** Die um {@code TOLERANCE / 2} verkleinerten Teile für {@link #overlaps}; Teile, die dabei verschwinden, fehlen. */
	private final List<Part> shrunkParts;
	private final double minX;
	private final double minY;
	private final double maxX;
	private final double maxY;

	private Footprint(List<Part> parts) {
		if (parts.isEmpty()) {
			throw new IllegalArgumentException("Ein Footprint braucht mindestens ein Teil");
		}
		this.parts = List.copyOf(parts);
		this.shrunkParts = this.parts.stream().map(Part::shrunk).filter(Objects::nonNull).toList();
		double x0 = Double.POSITIVE_INFINITY;
		double y0 = Double.POSITIVE_INFINITY;
		double x1 = Double.NEGATIVE_INFINITY;
		double y1 = Double.NEGATIVE_INFINITY;
		for (Part part : this.parts) {
			double ex = part.extentX();
			double ey = part.extentY();
			x0 = Math.min(x0, part.cx() - ex);
			y0 = Math.min(y0, part.cy() - ey);
			x1 = Math.max(x1, part.cx() + ex);
			y1 = Math.max(y1, part.cy() + ey);
		}
		this.minX = x0;
		this.minY = y0;
		this.maxX = x1;
		this.maxY = y1;
	}

	// --- Fabriken ---

	/**
	 * Platte und Stühle des Tischs. Die Platte zählt nur mit Breite und Höhe über 0 (Rohmaße wie die Zeichnung), je Stuhl
	 * ein Kreis mit {@link Seat#radius()} um seine Position. Ein Teil mit einem nicht endlichen Wert (Position, Maß,
	 * Drehung) wird still weggelassen; gemeldet wird so ein Wert, wo nötig, von den Validatoren der Entität. Leer, wenn
	 * kein Teil bleibt.
	 */
	static Optional<Footprint> of(Table table) {
		List<Part> parts = new ArrayList<>();
		double width = table.getWidth();
		double height = table.getHeight();
		if (width > 0 && height > 0) {
			Rect plate = Rect.of(table.getPosX() + width / 2, table.getPosY() + height / 2, width / 2, height / 2,
					table.getRotation());
			if (plate.isFinite()) {
				parts.add(plate);
			}
		}
		List<Seat> seats = table.getSeats();
		if (seats != null) {
			for (Seat seat : seats) {
				if (seat == null) {
					continue;
				}
				Circle chair = new Circle(seat.getPosX(), seat.getPosY(), seat.radius());
				if (chair.isFinite()) {
					parts.add(chair);
				}
			}
		}
		return parts.isEmpty() ? Optional.empty() : Optional.of(new Footprint(parts));
	}

	/**
	 * Fläche des Hallenobjekts wie in der Zeichnung: {@link Shape#CIRCLE} als Kreis mit {@code r = width / 2} um
	 * {@code (posX + r, posY + r)} (die Höhe zählt nicht), sonst ein Rechteck ohne Drehung. Leer bei {@code width ≤ 0},
	 * beim Rechteck bei {@code height ≤ 0} oder bei einem nicht endlichen Wert.
	 */
	static Optional<Footprint> of(HallObject object) {
		double width = object.getWidth();
		if (!(width > 0)) {
			return Optional.empty();
		}
		Part part;
		if (object.getShape() == Shape.CIRCLE) {
			double r = width / 2;
			part = new Circle(object.getPosX() + r, object.getPosY() + r, r);
		} else {
			double height = object.getHeight();
			if (!(height > 0)) {
				return Optional.empty();
			}
			part = Rect.of(object.getPosX() + width / 2, object.getPosY() + height / 2, width / 2, height / 2, 0);
		}
		return part.isFinite() ? Optional.of(new Footprint(List.of(part))) : Optional.empty();
	}

	/** Ein gedrehtes Rechteck mit Mittelpunkt, vollen Maßen und Drehung in Grad (im Uhrzeigersinn). */
	static Footprint rect(double cx, double cy, double width, double height, double degrees) {
		return new Footprint(List.of(Rect.of(cx, cy, width / 2, height / 2, degrees)));
	}

	/** Ein Kreis mit Mittelpunkt und Radius. */
	static Footprint circle(double cx, double cy, double radius) {
		return new Footprint(List.of(new Circle(cx, cy, radius)));
	}

	/** Alle Teile der übergebenen Footprints zusammen, in dieser Reihenfolge. */
	static Footprint union(Footprint... footprints) {
		List<Part> all = new ArrayList<>();
		for (Footprint footprint : footprints) {
			all.addAll(footprint.parts);
		}
		return new Footprint(all);
	}

	// --- Prüfungen ---

	/**
	 * {@code true}, wenn sich die beiden Flächen um mehr als die {@link #TOLERANCE} überschneiden. Erst der Vorfilter
	 * {@link #boundsOverlap}, dann jedes Teil gegen jedes, beide ringsum um {@code TOLERANCE / 2} verkleinert; ein Teil,
	 * bei dem dabei ein Maß ≤ 0 würde, nimmt nicht teil. An Kanten bleibt so eine Überlappung bis 1 cm stumm, wo eine
	 * Ecke trifft bis etwa 1,2 cm.
	 */
	boolean overlaps(Footprint other) {
		if (!boundsOverlap(other)) {
			return false;
		}
		for (Part a : shrunkParts) {
			for (Part b : other.shrunkParts) {
				if (intersect(a, b)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Vorfilter von {@link #overlaps}: {@code true}, wenn sich die Hüllrechtecke in x und in y um mehr als die
	 * {@link #TOLERANCE} überschneiden. Nie strenger als der genaue Test, denn ein um {@code TOLERANCE / 2} verkleinertes
	 * Teil hat ein um mindestens so viel je Seite kleineres Hüllrechteck.
	 */
	boolean boundsOverlap(Footprint other) {
		double overlapX = Math.min(maxX, other.maxX) - Math.max(minX, other.minX);
		double overlapY = Math.min(maxY, other.maxY) - Math.max(minY, other.minY);
		return overlapX > TOLERANCE && overlapY > TOLERANCE;
	}

	/**
	 * {@code true}, wenn jedes Teil (unverkleinert) im <b>ersten</b> Teil von {@code container} liegt, dieses ringsum um
	 * die {@link #TOLERANCE} vergrößert. Gedacht für Hallenobjekte mit einem Teil; gerechnet wird im Koordinatensystem
	 * des Containers, damit auch ein gedrehter Container stimmt.
	 */
	boolean within(Footprint container) {
		Part outer = container.parts.get(0);
		for (Part part : parts) {
			if (!contains(outer, part)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * {@code true}, wenn jedes Teil in {@code [−TOLERANCE, width + TOLERANCE] × [−TOLERANCE, height + TOLERANCE]} liegt
	 * (Rechteck: alle vier Ecken, Kreis: Mittelpunkt ± Radius). Ob die Halle Maße hat, prüft der Aufrufer.
	 */
	boolean insideHall(double width, double height) {
		double lowX = -TOLERANCE;
		double lowY = -TOLERANCE;
		double highX = width + TOLERANCE;
		double highY = height + TOLERANCE;
		for (Part part : parts) {
			switch (part) {
			case Rect rect -> {
				for (double[] corner : rect.corners()) {
					if (RangeCheck.outside(corner[0], lowX, highX) || RangeCheck.outside(corner[1], lowY, highY)) {
						return false;
					}
				}
			}
			case Circle c -> {
				if (!(c.cx() - c.r() >= lowX && c.cx() + c.r() <= highX && c.cy() - c.r() >= lowY
						&& c.cy() + c.r() <= highY)) {
					return false;
				}
			}
			}
		}
		return true;
	}

	/** Zahl der Teile (Platte und Stühle bzw. eine Form). */
	int partCount() {
		return parts.size();
	}

	// --- Geometrie der Teile ---

	/** Echte Überschneidung zweier (schon verkleinerter) Teile; Berührung zählt nicht. */
	private static boolean intersect(Part a, Part b) {
		return switch (a) {
		case Rect ra -> switch (b) {
			case Rect rb -> rectsIntersect(ra, rb);
			case Circle cb -> circleIntersectsRect(cb, ra);
			};
		case Circle ca -> switch (b) {
			case Rect rb -> circleIntersectsRect(ca, rb);
			case Circle cb -> {
				double dx = cb.cx() - ca.cx();
				double dy = cb.cy() - ca.cy();
				double sum = ca.r() + cb.r();
				yield dx * dx + dy * dy < sum * sum;
			}
			};
		};
	}

	/**
	 * Trennachsen-Test über die zwei Kantenrichtungen jedes Rechtecks: Überschneiden sich die Projektionen auf einer
	 * Achse nicht echt, sind die Rechtecke frei.
	 */
	private static boolean rectsIntersect(Rect a, Rect b) {
		double dx = b.cx() - a.cx();
		double dy = b.cy() - a.cy();
		double[][] axes = { a.axisX(), a.axisY(), b.axisX(), b.axisY() };
		for (double[] axis : axes) {
			double distance = Math.abs(dx * axis[0] + dy * axis[1]);
			if (a.halfProjection(axis) + b.halfProjection(axis) - distance <= 0) {
				return false;
			}
		}
		return true;
	}

	/** Mittelpunkt ins Rechteck-System drehen, auf die halben Maße klemmen, Abstand {@code < r}. */
	private static boolean circleIntersectsRect(Circle c, Rect rect) {
		double[] local = rect.toLocal(c.cx(), c.cy());
		double nearestX = Math.max(-rect.hw(), Math.min(rect.hw(), local[0]));
		double nearestY = Math.max(-rect.hh(), Math.min(rect.hh(), local[1]));
		double dx = local[0] - nearestX;
		double dy = local[1] - nearestY;
		return dx * dx + dy * dy < c.r() * c.r();
	}

	/** Liegt {@code inner} (unverkleinert) in {@code outer}, dieses um {@link #TOLERANCE} vergrößert? */
	private static boolean contains(Part outer, Part inner) {
		return switch (outer) {
		case Rect box -> {
			double hw = box.hw() + TOLERANCE;
			double hh = box.hh() + TOLERANCE;
			yield switch (inner) {
			case Rect rect -> {
				for (double[] corner : rect.corners()) {
					double[] local = box.toLocal(corner[0], corner[1]);
					if (RangeCheck.outside(local[0], -hw, hw) || RangeCheck.outside(local[1], -hh, hh)) {
						yield false;
					}
				}
				yield true;
			}
			case Circle c -> {
				double[] local = box.toLocal(c.cx(), c.cy());
				yield !RangeCheck.outside(local[0], -hw + c.r(), hw - c.r())
						&& !RangeCheck.outside(local[1], -hh + c.r(), hh - c.r());
			}
			};
		}
		case Circle ring -> {
			double radius = ring.r() + TOLERANCE;
			yield switch (inner) {
			case Rect rect -> {
				for (double[] corner : rect.corners()) {
					double dx = corner[0] - ring.cx();
					double dy = corner[1] - ring.cy();
					if (!(dx * dx + dy * dy <= radius * radius)) {
						yield false;
					}
				}
				yield true;
			}
			case Circle c -> Math.hypot(c.cx() - ring.cx(), c.cy() - ring.cy()) + c.r() <= radius;
			};
		}
		};
	}

	/** Ein Teil der Fläche: gedrehtes Rechteck oder Kreis. */
	private sealed interface Part permits Rect, Circle {

		double cx();

		double cy();

		/** Halbe Breite des achsparallelen Hüllrechtecks. */
		double extentX();

		/** Halbe Höhe des achsparallelen Hüllrechtecks. */
		double extentY();

		/** Ringsum um {@code TOLERANCE / 2} verkleinert, {@code null}, wenn dabei ein Maß ≤ 0 würde. */
		Part shrunk();

		/** Alle Werte endlich (kein NaN, nicht unendlich)? */
		boolean isFinite();
	}

	/**
	 * Rechteck mit Mittelpunkt, halber Breite und Höhe und der Drehung um den Mittelpunkt (im Uhrzeigersinn) als Kosinus
	 * und Sinus, einmal in {@link #of} berechnet.
	 */
	private record Rect(double cx, double cy, double hw, double hh, double cos, double sin) implements Part {

		/** Rechteck mit Drehung in Grad. */
		static Rect of(double cx, double cy, double hw, double hh, double degrees) {
			double radians = Math.toRadians(degrees);
			return new Rect(cx, cy, hw, hh, Math.cos(radians), Math.sin(radians));
		}

		/** Richtung der lokalen x-Achse (Breite) in der Halle. */
		double[] axisX() {
			return new double[] { cos, sin };
		}

		/** Richtung der lokalen y-Achse (Höhe) in der Halle. */
		double[] axisY() {
			return new double[] { -sin, cos };
		}

		/** Halbe Länge der Projektion auf eine Achse (Einheitsvektor). */
		double halfProjection(double[] axis) {
			return hw * Math.abs(cos * axis[0] + sin * axis[1]) + hh * Math.abs(-sin * axis[0] + cos * axis[1]);
		}

		/** Punkt der Halle im Koordinatensystem des Rechtecks (um den Mittelpunkt mit −Drehung zurückgedreht). */
		double[] toLocal(double x, double y) {
			double dx = x - cx;
			double dy = y - cy;
			return new double[] { dx * cos + dy * sin, -dx * sin + dy * cos };
		}

		/** Die vier Ecken in der Halle. */
		double[][] corners() {
			double[][] corners = new double[4][];
			int i = 0;
			for (double lx : new double[] { -hw, hw }) {
				for (double ly : new double[] { -hh, hh }) {
					corners[i++] = new double[] { cx + lx * cos - ly * sin, cy + lx * sin + ly * cos };
				}
			}
			return corners;
		}

		@Override
		public double extentX() {
			return hw * Math.abs(cos) + hh * Math.abs(sin);
		}

		@Override
		public double extentY() {
			return hw * Math.abs(sin) + hh * Math.abs(cos);
		}

		@Override
		public Part shrunk() {
			double w = hw - SHRINK;
			double h = hh - SHRINK;
			return w > 0 && h > 0 ? new Rect(cx, cy, w, h, cos, sin) : null;
		}

		/** Kosinus und Sinus sind nur bei endlicher Drehung endlich (sonst NaN). */
		@Override
		public boolean isFinite() {
			return Double.isFinite(cx) && Double.isFinite(cy) && Double.isFinite(hw) && Double.isFinite(hh)
					&& Double.isFinite(cos) && Double.isFinite(sin);
		}
	}

	/** Kreis mit Mittelpunkt und Radius. */
	private record Circle(double cx, double cy, double r) implements Part {

		@Override
		public double extentX() {
			return r;
		}

		@Override
		public double extentY() {
			return r;
		}

		@Override
		public Part shrunk() {
			double shrunk = r - SHRINK;
			return shrunk > 0 ? new Circle(cx, cy, shrunk) : null;
		}

		@Override
		public boolean isFinite() {
			return Double.isFinite(cx) && Double.isFinite(cy) && Double.isFinite(r);
		}
	}
}
