package de.eltviller_carneval_verein.karten.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Felder einer Datei, die das Modell dieser Programmversion nicht kennt (z. B. von einer neueren Version geschrieben).
 * Jede gespeicherte Modellklasse hält eines und reicht es über {@code @JsonAnySetter}/{@code @JsonAnyGetter} durch, damit
 * die Felder beim Speichern unverändert (Werte, Knotentypen, Reihenfolge) zurück in die Datei kommen.
 * <p>
 * Die Werte sind {@link JsonNode}s und bleiben so, wie sie in der Datei stehen (eine {@code 45} bleibt ganzzahlig, eine
 * {@code 45.0} bleibt eine Kommazahl, {@code null} bleibt {@code null}).
 */
public final class UnknownFields {

	private final Map<String, JsonNode> fields = new LinkedHashMap<>();

	/** Merkt sich das Feld; ein vorhandener Schlüssel behält seinen Platz in der Reihenfolge. */
	public void put(String key, JsonNode value) {
		fields.put(key, value);
	}

	/**
	 * Wie {@link #put}, aber für die Klassen, die eine Einheit bilden (Halle, Event, Vorstellung, Buchung): Ein
	 * {@code requires}, das das Gate durchlässt ({@link Requires#satisfied}), wird verworfen. Es soll weder
	 * zurückgeschrieben noch in Kopien getragen werden. Ein Wert, den das Gate nicht durchlässt, bleibt stehen, damit das
	 * Gate vor dem Speichern ablehnt, statt dass still eine streng gemachte Einheit geschrieben wird. Geschachtelte
	 * Klassen (Hallenobjekt, Bestuhlung, Reihe, Tisch, Sitz) rufen {@link #put} und bewahren {@code requires} immer.
	 */
	public void putUnitField(String key, JsonNode value) {
		if (Requires.KEY.equals(key) && Requires.satisfied(value)) {
			return;
		}
		fields.put(key, value);
	}

	/** Unveränderliche Sicht auf die Felder in der Reihenfolge, in der sie gelesen wurden. */
	public Map<String, JsonNode> asMap() {
		return Collections.unmodifiableMap(fields);
	}

	/**
	 * Ersetzt alle Felder durch tiefe Kopien der Felder von {@code source} in deren Reihenfolge. Es wird nicht gemischt:
	 * Sonst kämen Felder zurück, die eine neuere Version entfernt hat, und die Reihenfolge änderte die gespeicherten
	 * Bytes.
	 */
	public void replaceWith(UnknownFields source) {
		if (source == this) {
			return;
		}
		fields.clear();
		source.fields.forEach((key, value) -> fields.put(key, value == null ? null : value.deepCopy()));
	}

	public void remove(String key) {
		fields.remove(key);
	}

	public void clear() {
		fields.clear();
	}

	public boolean isEmpty() {
		return fields.isEmpty();
	}

	public boolean has(String key) {
		return fields.containsKey(key);
	}
}
