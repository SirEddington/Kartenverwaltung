package de.eltviller_carneval_verein.karten.ui.sales;

import javafx.geometry.Side;

/**
 * Wo die Kartenübersicht im Kundenfenster steht (Menü "Anzeige"). {@link #AUTO} wählt nach der Form des Fensters: im
 * Hochformat unter dem Plan, sonst rechts daneben (dort wäre im Hochformat zu wenig Breite); die anderen Werte sind fest.
 */
public enum SummaryPlacement {

	AUTO("Automatisch"), TOP("Oben"), BOTTOM("Unten"), LEFT("Links"), RIGHT("Rechts");

	private final String label;

	SummaryPlacement(String label) {
		this.label = label;
	}

	/** Die Beschriftung im Menü. */
	public String label() {
		return label;
	}

	/** Die Seite des Plans, an der die Kartenübersicht steht; nie {@code null}. */
	public Side sideFor(double windowWidth, double windowHeight) {
		return switch (this) {
		case AUTO -> windowHeight > windowWidth ? Side.BOTTOM : Side.RIGHT;
		case TOP -> Side.TOP;
		case BOTTOM -> Side.BOTTOM;
		case LEFT -> Side.LEFT;
		case RIGHT -> Side.RIGHT;
		};
	}
}
