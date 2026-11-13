package de.eltviller_carneval_verein.karten.model;

/**
 * Die Texteinstellungen eines beschrifteten Objekts im Plan (Hallenobjekt, Tisch), gebündelt für die Rechnung der
 * Beschriftung. In der Datei stehen sie als einzelne Felder am Objekt; dieser Werttyp wird nicht gespeichert.
 *
 * @param auto     die Anzeige bestimmt Größe, Lage und Drehung selbst; die übrigen Werte gelten dann nur als Vorbelegung
 * @param size     Schriftgröße in cm
 * @param rotation Drehung in Grad im Uhrzeigersinn
 */
public record TextSettings(boolean auto, double size, TextAlignH alignH, TextAlignV alignV, double rotation) {
}
