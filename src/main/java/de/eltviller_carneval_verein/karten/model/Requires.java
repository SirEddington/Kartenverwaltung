package de.eltviller_carneval_verein.karten.model;

import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Das Feld {@code requires} oben in einer Einheit (Halle, Event, Vorstellung, Buchung): Eine neuere Programmversion nennt
 * dort die Merkmale, die ein Programm verstehen muss, um die Einheit sicher zu verarbeiten. Diese Klasse sagt, ob die
 * Merkmale ausreichen.
 */
public final class Requires {

	/** Name des Feldes in der Datei. Reserviert; kein Modellfeld darf so heißen. */
	public static final String KEY = "requires";

	/** Die Merkmale, die diese Programmversion versteht. In Version 1.1 gibt es noch keine. */
	public static final Set<String> KNOWN = Set.of();

	private Requires() {
	}

	/**
	 * Ob eine Einheit mit diesem Wert von {@code requires} verarbeitet werden darf: wenn das Feld fehlt ({@code null}),
	 * JSON-{@code null} ist oder ein Array aus Texten ist, die alle in {@link #KNOWN} stehen (also auch das leere Array).
	 * Jeder andere Wert (Zahl, Text, Objekt, Array mit unbekanntem oder nicht-textuellem Eintrag) gilt als nicht erfüllt.
	 */
	public static boolean satisfied(JsonNode value) {
		if (value == null || value.isNull()) {
			return true;
		}
		if (!value.isArray()) {
			return false;
		}
		for (JsonNode feature : value) {
			if (!feature.isTextual() || !KNOWN.contains(feature.asText())) {
				return false;
			}
		}
		return true;
	}
}
