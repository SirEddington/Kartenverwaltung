package de.eltviller_carneval_verein.karten.model;

import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Das Feld {@code requires} oben in einer Einheit (Halle, Event, Vorstellung, Buchung): Eine neuere Programmversion nennt
 * dort die Merkmale, die ein Programm verstehen muss, um die Einheit sicher zu verarbeiten. Diese Klasse sagt, ob die
 * Merkmale ausreichen.
 */
public final class Requires {

	/** Name des Feldes in der Datei. Reserviert; kein Modellfeld darf so heißen. */
	public static final String KEY = "requires";

	/**
	 * Das Merkmal am Event-Kopf, solange das Event Kassen-Einheiten hat (KARV-144): Version 1.1 kennt es nicht und sperrt
	 * das Event, statt die Kassen zu übergehen.
	 */
	public static final String CASH_REGISTERS = "cashRegisters";

	/** Die Merkmale, die diese Programmversion versteht. Version 1.1 kannte keine. */
	public static final Set<String> KNOWN = Set.of(CASH_REGISTERS);

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

	/**
	 * Ob dieser Wert von {@code requires} das Merkmal nennt: ein Array mit dem Text {@code feature}. Fehlend
	 * ({@code null}), JSON-{@code null}, ein anderer Wert und {@code []} heißen nein.
	 */
	public static boolean contains(JsonNode value, String feature) {
		if (value == null || !value.isArray()) {
			return false;
		}
		for (JsonNode entry : value) {
			if (feature.equals(entry.textValue())) {
				return true;
			}
		}
		return false;
	}

	/** Eine Kopie des Inhalts mit {@code "requires": [feature]} bzw. ohne das Feld. */
	public static ObjectNode with(ObjectNode payload, String feature, boolean present) {
		ObjectNode copy = payload.deepCopy();
		if (present) {
			copy.putArray(KEY).add(feature);
		} else {
			copy.remove(KEY);
		}
		return copy;
	}
}
