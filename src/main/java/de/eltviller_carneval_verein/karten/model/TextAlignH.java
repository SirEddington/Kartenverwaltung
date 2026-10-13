package de.eltviller_carneval_verein.karten.model;

/** Horizontale Lage des Textes in einem Hallenobjekt. */
public enum TextAlignH {
	LEFT("Links"), CENTER("Mitte"), RIGHT("Rechts");

	private final String displayName;

	TextAlignH(String displayName) {
		this.displayName = displayName;
	}

	public String getDisplayName() {
		return displayName;
	}
}
