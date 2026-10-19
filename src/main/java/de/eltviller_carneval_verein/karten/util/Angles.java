package de.eltviller_carneval_verein.karten.util;

/** Winkelrechnung in Grad, ohne JavaFX. */
public final class Angles {

	private static final double FULL_CIRCLE = 360.0;

	private Angles() {
	}

	/** Drehung in Grad auf 0 bis unter 360 bringen (nie -0 und nie 360). */
	public static double normalize(double degrees) {
		double normalized = degrees % FULL_CIRCLE;
		if (normalized < 0) {
			normalized += FULL_CIRCLE;
		}
		// Ein winziger negativer Rest ergibt beim Aufaddieren genau 360; + 0.0 macht aus -0 eine 0
		return normalized >= FULL_CIRCLE ? 0.0 : normalized + 0.0;
	}
}
