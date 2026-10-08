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

	/** z. B. "Vorstellung Prunksitzung" (ohne Namen: "Vorstellung ohne Namen"). */
	public static String presentation(Presentation presentation) {
		String name = presentation.getName();
		return (name == null || name.isBlank()) ? "Vorstellung ohne Namen" : "Vorstellung " + name;
	}

	/** z. B. "Prunksitzung, Tisch 4" (ohne Vorstellung: "Tisch 4"). */
	public static String table(Table table) {
		return presentationName(table) + "Tisch " + table.getTableNumber();
	}

	/** z. B. "Prunksitzung, Tisch 4, Sitz 2" (ohne Vorstellung/Tisch: "Tisch 4, Sitz 2" bzw. "Sitz 2"). */
	public static String seat(Seat seat) {
		Table table = seat.getParent();
		String prefix = (table == null) ? "" : table(table) + ", ";
		return prefix + "Sitz " + seat.getSeatNumber();
	}

	/** "Prunksitzung, " oder leer, wenn Vorstellung oder Name fehlen. */
	private static String presentationName(Table table) {
		Presentation presentation = table.getParent();
		if (presentation == null || presentation.getName() == null || presentation.getName().isBlank()) {
			return "";
		}
		return presentation.getName() + ", ";
	}
}
