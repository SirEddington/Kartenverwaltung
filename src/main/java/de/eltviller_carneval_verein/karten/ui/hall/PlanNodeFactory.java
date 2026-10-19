package de.eltviller_carneval_verein.karten.ui.hall;

import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.Table;
import de.eltviller_carneval_verein.karten.model.TableRow;
import de.eltviller_carneval_verein.karten.util.Angles;
import javafx.geometry.VPos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;

/**
 * Baut die Knoten für Reihen, Tische und Sitze einer Bestuhlung in Hallenkoordinaten (cm, y nach unten, Winkel im
 * Uhrzeigersinn), wie sie {@link SeatingPlanLayout} berechnet. Nur Aussehen, kein Verhalten: Klicks und Ziehen hängt
 * der Aufrufer an.
 */
public final class PlanNodeFactory {

	/** Rand markierter Reihen und Tische (wie bei den Hallenobjekten). */
	public static final Color SELECTION_STROKE = Color.DODGERBLUE;

	private static final Color TABLE_FILL = Color.web("#E8E1CF");
	private static final Color TABLE_STROKE = Color.DARKGRAY;
	private static final Color SEAT_FILL = Color.WHITE;
	private static final Color SEAT_STROKE = Color.GRAY;
	private static final Color ROW_STROKE = Color.web("#2C2F88");
	// Sitze ohne Breite (0) blieben unsichtbar; der Validator meldet sie, gezeichnet werden sie mit diesem Radius
	private static final double FALLBACK_SEAT_RADIUS = 10;
	private static final double ROW_HIT_WIDTH = 30;
	private static final double ROW_LABEL_SIZE = 36;
	private static final double ROW_LABEL_DISTANCE = 30;

	private PlanNodeFactory() {
	}

	/**
	 * Tisch als Rechteck an {@code posX/posY} (obere linke Ecke des ungedrehten Rechtecks) mit der Größe des Tischs,
	 * beschriftet mit {@link Table#getDisplayName()}. Rechteck und Beschriftung drehen sich um die Tischmitte um
	 * {@link Table#getRotation()} Grad im Uhrzeigersinn; die Schrift steht dabei nie auf dem Kopf.
	 */
	public static Group tableNode(Table table, boolean selected) {
		Rectangle shape = new Rectangle(table.getPosX(), table.getPosY(), table.getWidth(), table.getHeight());
		shape.setArcWidth(10);
		shape.setArcHeight(10);
		shape.setFill(TABLE_FILL);
		shape.setStroke(selected ? SELECTION_STROKE : TABLE_STROKE);
		shape.setStrokeWidth(selected ? 4 : 1);
		// setRotate dreht um die Mitte der Grenzen, beim Rechteck also um die Tischmitte
		shape.setRotate(table.getRotation());

		Text label = new Text(table.getDisplayName());
		label.setFont(Font.font(16));
		label.setTextOrigin(VPos.CENTER);
		label.setTextAlignment(TextAlignment.CENTER);
		double centerX = table.getPosX() + table.getWidth() / 2.0;
		double centerY = table.getPosY() + table.getHeight() / 2.0;
		label.setX(centerX - label.getLayoutBounds().getWidth() / 2.0);
		label.setY(centerY);
		label.setRotate(readableRotation(table.getRotation()));

		return new Group(shape, label);
	}

	/** Sitz als Kreis um {@code posX/posY} (der Mittelpunkt des Sitzes) mit der halben Sitzbreite als Radius. */
	public static Node seatNode(Seat seat) {
		return seatNode(seat, false);
	}

	/** Wie {@link #seatNode(Seat)}, ein markierter Sitz mit dem Rand der Auswahl. */
	public static Node seatNode(Seat seat, boolean selected) {
		double radius = seat.getWidth() > 0 ? seat.getWidth() / 2.0 : FALLBACK_SEAT_RADIUS;
		Circle circle = new Circle(seat.getPosX(), seat.getPosY(), radius);
		circle.setFill(SEAT_FILL);
		circle.setStroke(selected ? SELECTION_STROKE : SEAT_STROKE);
		circle.setStrokeWidth(selected ? 4 : 1);
		return circle;
	}

	/**
	 * Reihe als Linie ab {@code posX/posY} in Richtung {@link TableRow#getAngle()} (0 Grad nach unten, im Uhrzeigersinn,
	 * siehe {@link SeatingPlanLayout#direction}) mit der Länge {@code extent}, mit Punkt am Startpunkt und dem aufrechten
	 * Buchstaben kurz davor (ohne Buchstaben keine Beschriftung). Eine breite unsichtbare Linie darunter macht die Reihe
	 * greifbar.
	 */
	public static Node rowNode(TableRow row, double extent, boolean selected) {
		double[] direction = SeatingPlanLayout.direction(row.getAngle());
		double ux = direction[0];
		double uy = direction[1];
		double startX = row.getPosX();
		double startY = row.getPosY();
		double endX = startX + extent * ux;
		double endY = startY + extent * uy;

		Line hitArea = new Line(startX, startY, endX, endY);
		hitArea.setStroke(Color.TRANSPARENT);
		hitArea.setStrokeWidth(ROW_HIT_WIDTH);
		hitArea.setStrokeLineCap(StrokeLineCap.ROUND);

		Line line = new Line(startX, startY, endX, endY);
		line.setStroke(selected ? SELECTION_STROKE : ROW_STROKE);
		line.setStrokeWidth(selected ? 6 : 3);
		line.getStrokeDashArray().addAll(20.0, 10.0);
		line.setMouseTransparent(true);

		Circle start = new Circle(startX, startY, selected ? 9 : 7);
		start.setFill(selected ? SELECTION_STROKE : ROW_STROKE);

		Group group = new Group(hitArea, line, start);
		String label = row.getLabel() == null ? "" : row.getLabel().trim();
		if (!label.isEmpty()) {
			Text text = new Text(label);
			text.setFont(Font.font(null, FontWeight.BOLD, ROW_LABEL_SIZE));
			text.setFill(selected ? SELECTION_STROKE : ROW_STROKE);
			text.setTextOrigin(VPos.CENTER);
			// Vor dem Startpunkt, entgegen der Reihenrichtung, aufrecht
			double labelX = startX - ROW_LABEL_DISTANCE * ux;
			double labelY = startY - ROW_LABEL_DISTANCE * uy;
			text.setX(labelX - text.getLayoutBounds().getWidth() / 2.0);
			text.setY(labelY);
			group.getChildren().add(text);
		}
		return group;
	}

	/** Dreht eine Beschriftung mit, aber nie auf den Kopf (zwischen 90 und 270 Grad um 180 Grad weiter). */
	static double readableRotation(double rotation) {
		double normalized = Angles.normalize(rotation);
		return normalized > 90 && normalized < 270 ? normalized - 180 : normalized;
	}
}
