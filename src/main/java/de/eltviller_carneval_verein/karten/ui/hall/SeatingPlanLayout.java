package de.eltviller_carneval_verein.karten.ui.hall;

import java.util.List;

import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.model.Table;
import de.eltviller_carneval_verein.karten.model.TableRow;
import de.eltviller_carneval_verein.karten.ui.input.RotationInput;

/**
 * Automatische Anordnung der Bestuhlung: Legt die Tische jeder Reihe nach Nummer sortiert entlang der Reihenachse
 * hintereinander und setzt die Stühle paarweise links und rechts an die Tische. Die Berechnung ist reine Geometrie
 * (ohne JavaFX), schreibt die Ergebnisse ins Modell und ist idempotent. Koordinaten: y zeigt nach unten, Winkel
 * laufen im Uhrzeigersinn, Grad.
 */
public final class SeatingPlanLayout {

	// Rückfall für Tischmaße von 0 oder kleiner (cm)
	private static final double DEFAULT_TABLE_WIDTH = 70;
	private static final double DEFAULT_TABLE_HEIGHT = 300;

	// Abstand eines Stuhlmittelpunkts zur Tischkante (cm)
	private static final double SEAT_GAP = 15;
	// Abstand der äußeren Stühle zur Ober-/Unterkante des Tischs (cm)
	private static final double SEAT_MARGIN_Y = 20;

	private SeatingPlanLayout() {
	}

	/**
	 * Berechnet Position und Drehung der Tische ohne {@code manualPos} und die Position der Stühle ohne
	 * {@code manualPos}. Tische, deren {@code rowId} keine Reihe der Bestuhlung trifft, bleiben unberührt.
	 */
	public static void apply(SeatingPlan plan) {
		if (plan == null || plan.getRows() == null || plan.getTables() == null) {
			return;
		}
		for (TableRow row : plan.getRows()) {
			layoutRow(plan, row);
		}
	}

	private static void layoutRow(SeatingPlan plan, TableRow row) {
		double angle = Math.toRadians(row.getAngle());
		double tableRotation = Math.toRadians(row.getTableRotation());
		double ux = Math.cos(angle);
		double uy = Math.sin(angle);

		List<Table> tables = plan.getTablesOf(row);
		double s = 0; // Laufmaß entlang der Reihenachse
		for (int i = 0; i < tables.size(); i++) {
			Table table = tables.get(i);
			if (table.getWidth() <= 0) {
				table.setWidth(DEFAULT_TABLE_WIDTH);
			}
			if (table.getHeight() <= 0) {
				table.setHeight(DEFAULT_TABLE_HEIGHT);
			}

			double extent = extent(table, tableRotation);

			if (!table.isManualPos()) {
				double centerX = row.getPosX() + (s + extent / 2) * ux;
				double centerY = row.getPosY() + (s + extent / 2) * uy;
				table.setPosX(centerX - table.getWidth() / 2);
				table.setPosY(centerY - table.getHeight() / 2);
				table.setRotation(RotationInput.normalize(row.getAngle() + row.getTableRotation()));
			}

			s += extent + row.getTableGap();
			Integer aisleAfter = row.getAisleAfter();
			if (aisleAfter != null && aisleAfter == i + 1) {
				s += row.getAisleWidth();
			}

			applySeatPositions(table);
		}
	}

	/**
	 * Länge der Reihe entlang ihrer Achse vom Startpunkt bis zum Ende des letzten Tischs (mit Abständen und Mittelgang,
	 * ohne Abstand nach dem letzten Tisch), 0 ohne Tische. Rechnet wie {@link #apply(SeatingPlan)}, verändert aber nichts.
	 */
	public static double rowLength(SeatingPlan plan, TableRow row) {
		double tableRotation = Math.toRadians(row.getTableRotation());
		List<Table> tables = plan.getTablesOf(row);
		double s = 0;
		double end = 0;
		for (int i = 0; i < tables.size(); i++) {
			end = s + extent(tables.get(i), tableRotation);
			s = end + row.getTableGap();
			Integer aisleAfter = row.getAisleAfter();
			if (aisleAfter != null && aisleAfter == i + 1) {
				s += row.getAisleWidth();
			}
		}
		return end;
	}

	/** Ausdehnung eines Tischs samt Stuhlrand entlang der Reihenachse; Maße von 0 oder kleiner wie in {@code apply}. */
	private static double extent(Table table, double tableRotation) {
		double width = table.getWidth() > 0 ? table.getWidth() : DEFAULT_TABLE_WIDTH;
		double height = table.getHeight() > 0 ? table.getHeight() : DEFAULT_TABLE_HEIGHT;
		return Math.abs((width + 2 * getSeatMargin(table)) * Math.cos(tableRotation)) + Math.abs(height * Math.sin(tableRotation));
	}

	/**
	 * Zusätzlicher Platzbedarf links/rechts eines Tischs durch die dort platzierten Stühle (siehe
	 * {@link #applySeatPositions(Table)}), damit Stühle benachbarter Tische sich nicht überschneiden.
	 */
	private static double getSeatMargin(Table table) {
		List<Seat> seats = table.getSeats();
		if (seats == null || seats.isEmpty()) {
			return 0;
		}
		double maxSeatRadius = seats.stream().mapToDouble(Seat::getWidth).max().orElse(0) / 2.0;
		return SEAT_GAP + maxSeatRadius;
	}

	/**
	 * Platziert die Stühle paarweise links und rechts am Tisch. Berechnet wird im Tischkoordinatensystem um den
	 * Tischmittelpunkt, danach um die Drehung des Tischs gedreht. Die Position eines Stuhls ist sein Mittelpunkt.
	 */
	private static void applySeatPositions(Table table) {
		List<Seat> seats = table.getSeats();
		if (seats == null || seats.isEmpty()) {
			return;
		}
		int totalSeats = seats.size();
		int pairs = (int) Math.ceil(totalSeats / 2.0);

		double usableHeight = Math.max(table.getHeight() - (2 * SEAT_MARGIN_Y), 20);
		double stepY = pairs > 1 ? usableHeight / (pairs - 1) : 0;

		double centerX = table.getPosX() + table.getWidth() / 2;
		double centerY = table.getPosY() + table.getHeight() / 2;
		double rotation = Math.toRadians(table.getRotation());
		double cos = Math.cos(rotation);
		double sin = Math.sin(rotation);

		for (int j = 0; j < totalSeats; j++) {
			Seat seat = seats.get(j);
			if (seat.isManualPos()) {
				continue;
			}
			boolean isLeft = (j % 2 == 0); // Index 0, 2, 4... -> links | Index 1, 3, 5... -> rechts
			int pairIndex = j / 2;

			double localX = (table.getWidth() / 2 + SEAT_GAP) * (isLeft ? -1 : 1);
			// Bei einem Paar mittig, sonst gleichmäßig verteilt
			double localY = pairs == 1 ? 0 : -(table.getHeight() / 2) + SEAT_MARGIN_Y + pairIndex * stepY;

			seat.setPosX(centerX + localX * cos - localY * sin);
			seat.setPosY(centerY + localX * sin + localY * cos);
		}
	}
}
