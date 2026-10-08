package de.eltviller_carneval_verein.karten.validation;

import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.Table;

/**
 * Einheitliche Kurzbeschreibung von Entitäten für Meldungen (als Parameter {@code &1}), damit
 * ein Objekt in allen Meldungen gleich benannt wird. Die Beschreibung entsteht über die
 * Parent-Kette; fehlt ein Elternteil, wird er weggelassen.
 */
public final class EntityLabels {

	private EntityLabels() {
	}

	/** z. B. "Prunksitzung, Tisch 4, Sitz 2" (ohne Vorstellung/Tisch: "Tisch 4, Sitz 2" bzw. "Sitz 2"). */
	public static String seat(Seat seat) {
		StringBuilder label = new StringBuilder();
		Table table = seat.getParent();
		if (table != null) {
			Presentation presentation = table.getParent();
			if (presentation != null && presentation.getName() != null && !presentation.getName().isBlank()) {
				label.append(presentation.getName()).append(", ");
			}
			label.append("Tisch ").append(table.getTableNumber()).append(", ");
		}
		return label.append("Sitz ").append(seat.getSeatNumber()).toString();
	}
}
