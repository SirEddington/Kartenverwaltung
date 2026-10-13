package de.eltviller_carneval_verein.karten.model;

/** Vertikale Lage des Textes in einem Hallenobjekt. */
public enum TextAlignV {
	TOP("Oben"), CENTER("Mitte"), BOTTOM("Unten");

	private final String displayName;

	TextAlignV(String displayName) {
		this.displayName = displayName;
	}

	public String getDisplayName() {
		return displayName;
	}
}
