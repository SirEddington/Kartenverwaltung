package de.eltviller_carneval_verein.karten.store;

/**
 * Die Arten von Einheiten, die der Versionsspeicher getrennt voneinander versioniert. Jede Einheit hat einen
 * eigenen Ordner mit unveränderlichen Versionsdateien (siehe {@link UnitKey}).
 */
public enum UnitType {

	/** Ein Event (Stammdaten, Auftritte, Preise). */
	EVENT,

	/** Ein Auftritt eines Events. */
	PRESENTATION,

	/** Eine Buchung: entweder auf einem Sitz oder ohne Sitzzuordnung. */
	BOOKING,

	/** Eine Halle samt Sitzplan. */
	HALL;

	/** Ob Einheiten dieser Art Einheiten unter sich haben: ein Event (Vorstellungen) und eine Vorstellung (Buchungen). */
	public boolean hasChildren() {
		return this == EVENT || this == PRESENTATION;
	}
}
