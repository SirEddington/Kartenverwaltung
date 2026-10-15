package de.eltviller_carneval_verein.karten.ui.sales;

import java.util.List;

import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.Table;

/**
 * Automatische Anordnung von Tischen und Stühlen im Saalplan: Berechnet die Standardpositionen, sofern Tische und
 * Stühle noch nicht manuell verschoben wurden, und schreibt sie ins Modell.
 */
final class HallLayout {

	// Randabstand der automatischen Anordnung zur Hallenwand (cm), wenn der Vorstellung eine Halle zugeordnet ist
	private static final double HALL_MARGIN = 50;

	private HallLayout() {
	}

	/**
	 * Berechnet die Standardpositionen für Tische und Stühle der Vorstellung. Die Abstände ergeben sich aus der
	 * tatsächlichen Tisch- und Stuhlgröße, damit sich Tische (und ihre Stühle) nie überschneiden und direkt
	 * aneinander gesetzt werden.
	 *
	 * @param hall die Halle der Vorstellung, {@code null} wenn keine zugeordnet ist
	 */
	static void apply(Presentation presentation, Hall hall) {
		List<Table> tables = presentation.getTables();
		if (tables == null || tables.isEmpty()) {
			return;
		}

		// Mit zugeordneter Halle beginnt die Anordnung mit Randabstand an der Hallenecke und bricht
		// Spalten nach der Hallenhöhe um; ohne Halle bleibt es bei 7 Tischen pro Spalte ab festem Startpunkt.
		boolean inHall = hall != null && hall.getHallWidth() > 0 && hall.getHallHeight() > 0;
		int tablesPerColumn = 7; // nur ohne Halle
		double startX = inHall ? HALL_MARGIN : 60; // Start-X im Pane
		double startY = inHall ? HALL_MARGIN : 40; // Start-Y im Pane
		double defaultWidth = 70; // Standard-Tischbreite
		double defaultHeight = 300; // Standard-Tischhöhe
		double tableRowGap = 0; // Sichtbarer Abstand zwischen benachbarten Tischen
		double tableColGap = 20; // Sichtbarer Abstand zwischen benachbarten Tischen

		// Fallback für Tischgrößen, falls diese 0 sind (muss vor der Breitenberechnung stehen)
		for (Table table : tables) {
			if (table.getWidth() <= 0)
				table.setWidth(defaultWidth);
			if (table.getHeight() <= 0)
				table.setHeight(defaultHeight);
		}

		// Breiteste Tisch-Stuhl-Kombination je Spalte ermitteln, damit die nächste
		// Spalte erst dahinter beginnt
		int[] columnOf = new int[tables.size()];
		int currentColumn = 0;
		double columnCursorY = startY;
		for (int i = 0; i < tables.size(); i++) {
			if (inHall) {
				double tableHeight = tables.get(i).getHeight();
				if (columnCursorY > startY && columnCursorY + tableHeight > hall.getHallHeight() - startY) {
					currentColumn++;
					columnCursorY = startY;
				}
				columnCursorY += tableHeight + tableRowGap;
				columnOf[i] = currentColumn;
			} else {
				columnOf[i] = i / tablesPerColumn;
			}
		}

		int columnCount = columnOf[tables.size() - 1] + 1;
		double[] columnFootprint = new double[columnCount];
		for (int i = 0; i < tables.size(); i++) {
			int col = columnOf[i];
			double footprint = tables.get(i).getWidth() + 2 * getSeatMargin(tables.get(i));
			columnFootprint[col] = Math.max(columnFootprint[col], footprint);
		}

		double[] columnX = new double[columnCount];
		columnX[0] = startX;
		for (int col = 1; col < columnCount; col++) {
			columnX[col] = columnX[col - 1] + columnFootprint[col - 1] + tableColGap;
		}

		double[] rowCursorY = new double[columnCount];
		for (int col = 0; col < columnCount; col++) {
			rowCursorY[col] = startY;
		}

		for (int i = 0; i < tables.size(); i++) {
			Table table = tables.get(i);
			int col = columnOf[i];

			// --- TISCH POSITIONIERUNG ---
			if (!table.isManualPos()) {
				table.setPosX(columnX[col] + getSeatMargin(table));
				table.setPosY(rowCursorY[col]);
			}
			rowCursorY[col] += table.getHeight() + tableRowGap;

			// --- STUHL POSITIONIERUNG ---
			if (table.getSeats() != null && !table.getSeats().isEmpty()) {
				applyDefaultSeatPositions(table);
			}
		}
	}

	/**
	 * Zusätzlicher Platzbedarf links/rechts eines Tischs durch die dort
	 * platzierten Stühle (siehe applyDefaultSeatPositions), damit Stühle
	 * benachbarter Tische sich nicht überschneiden.
	 */
	private static double getSeatMargin(Table table) {
		List<Seat> seats = table.getSeats();
		if (seats == null || seats.isEmpty()) {
			return 0;
		}
		double maxSeatRadius = seats.stream().mapToDouble(Seat::getWidth).max().orElse(0) / 2.0;
		return 15 + maxSeatRadius; // 15px Abstand, siehe applyDefaultSeatPositions
	}

	/**
	 * Platziert die Stühle paarweise links und rechts am Tisch.
	 */
	private static void applyDefaultSeatPositions(Table table) {
		List<Seat> seats = table.getSeats();
		int totalSeats = seats.size();

		// Anzahl der Reihen (Paare) am Tisch
		int rows = (int) Math.ceil(totalSeats / 2.0);

		double marginY = 20; // Abstand der äußeren Stühle zur Ober-/Unterkante des Tischs
		double usableHeight = Math.max(table.getHeight() - (2 * marginY), 20);
		double stepY = rows > 1 ? usableHeight / (rows - 1) : 0;

		for (int j = 0; j < totalSeats; j++) {
			Seat seat = seats.get(j);

			if (!seat.isManualPos()) {
				boolean isLeft = (j % 2 == 0); // Index 0, 2, 4... -> Links | Index 1, 3, 5... -> Rechts
				int rowIndex = j / 2; // 0, 0, 1, 1, 2, 2...

				// X-Position: Links oder Rechts vom Tisch mit 15px Abstand
				double posX = isLeft ? (table.getPosX() - 15) : (table.getPosX() + table.getWidth() + 15);

				// Y-Position: Bei 1 Stuhl-Paar zentriert, sonst gleichmäßig verteilt
				double posY = (rows == 1) ? (table.getPosY() + (table.getHeight() / 2.0)) : (table.getPosY() + marginY + (rowIndex * stepY));

				seat.setPosX(posX);
				seat.setPosY(posY);
			}
		}
	}
}
