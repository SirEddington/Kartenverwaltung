package de.eltviller_carneval_verein.karten.validation;

import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.HallObject;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.model.Table;
import de.eltviller_carneval_verein.karten.model.TableRow;

/**
 * Einheitliche Kurzbeschreibung von Entitäten für Meldungen (als Parameter {@code &1}), damit
 * ein Objekt in allen Meldungen gleich benannt wird. Die Beschreibung entsteht über die
 * Parent-Kette; fehlt ein Elternteil, wird er weggelassen.
 */
public final class EntityLabels {

	private EntityLabels() {
	}

	/** Platzhalter für einen leeren Namen in Anzeigen (Listen, Auswahlfelder). */
	public static final String NAME_PLACEHOLDER = "(ohne Name)";

	/** Der Name, oder {@value #NAME_PLACEHOLDER}, wenn er {@code null} oder leer ist. */
	public static String nameOrPlaceholder(String name) {
		return (name == null || name.isBlank()) ? NAME_PLACEHOLDER : name;
	}

	/**
	 * Anzeigename einer Halle in Tabellen: ein Strich, wenn keine (oder keine existierende) Halle zugeordnet ist,
	 * {@value #NAME_PLACEHOLDER} bei einer Halle ohne Namen.
	 */
	public static String hallDisplayName(Hall hall) {
		return hall == null ? "–" : nameOrPlaceholder(hall.getName());
	}

	/** z. B. "Halle Festhalle" (ohne Namen oder ohne Halle: "Halle ohne Namen"). */
	public static String hall(Hall hall) {
		return named("Halle", hall == null ? null : hall.getName());
	}

	/** z. B. "Hallenobjekt Bühne in Halle Festhalle" (ohne Namen: "Hallenobjekt ohne Namen in ..."). */
	public static String hallObject(HallObject object, Hall hall) {
		return named("Hallenobjekt", object.getName()) + " in " + hall(hall);
	}

	/** z. B. "Vorstellung Prunksitzung" (ohne Namen: "Vorstellung ohne Namen"). */
	public static String presentation(Presentation presentation) {
		return named("Vorstellung", presentation.getName());
	}

	/** "<Art> <Name>", ohne Namen "<Art> ohne Namen". */
	private static String named(String kind, String name) {
		return (name == null || name.isBlank()) ? kind + " ohne Namen" : kind + " " + name;
	}

	/** z. B. "Bestuhlung Prunksitzung in Halle Festhalle" (ohne Namen: "Bestuhlung ohne Namen in ..."). */
	public static String plan(SeatingPlan plan) {
		return named("Bestuhlung", plan.getName()) + " in " + hall(plan.getParent());
	}

	/** z. B. "Reihe A in Bestuhlung Prunksitzung in Halle Festhalle" (ohne Buchstaben: "Reihe ohne Buchstaben in ..."). */
	public static String row(TableRow row) {
		String label = row.getLabel();
		String kind = (label == null || label.isBlank()) ? "Reihe ohne Buchstaben" : "Reihe " + label.trim();
		SeatingPlan plan = row.getParent();
		return plan == null ? kind : kind + " in " + plan(plan);
	}

	/**
	 * z. B. "Prunksitzung, Tisch 4" (ohne Vorstellung: "Tisch 4"). Ein Tisch einer Bestuhlung heißt mit Reihenbuchstabe
	 * und Bestuhlung: "Tisch A3 in Bestuhlung Prunksitzung in Halle Festhalle".
	 */
	public static String table(Table table) {
		SeatingPlan plan = table.getPlan();
		if (table.getParent() == null && plan != null) {
			return table.getDisplayName() + " in " + plan(plan);
		}
		return presentationName(table) + "Tisch " + table.getTableNumber();
	}

	/** z. B. "Prunksitzung, Tisch A3, Sitz 2" (ohne Vorstellung oder ohne Namen derselben: "Tisch A3, Sitz 2"). */
	public static String booking(Booking booking) {
		Presentation presentation = booking.getParent();
		String prefix = (presentation == null || presentation.getName() == null || presentation.getName().isBlank()) ? ""
				: presentation.getName() + ", ";
		return prefix + booking.getPlaceLabel();
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
