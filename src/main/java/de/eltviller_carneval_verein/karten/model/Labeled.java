package de.eltviller_carneval_verein.karten.model;

/**
 * Ein Objekt im Plan mit Beschriftung nach {@link TextSettings} (Hallenobjekt, Tisch). In der Datei stehen die fünf
 * Textfelder einzeln am Objekt; über dieses Interface lesen und schreiben Anzeige und Eingabe sie gebündelt.
 */
public interface Labeled {

	/** Die fünf Textfelder; nicht in der Datei (die Umsetzung braucht {@code @JsonIgnore}). */
	TextSettings getTextSettings();

	/** Schreibt alle fünf Textfelder. */
	void setTextSettings(TextSettings settings);
}
