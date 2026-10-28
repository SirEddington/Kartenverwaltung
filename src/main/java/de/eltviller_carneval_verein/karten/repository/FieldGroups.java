package de.eltviller_carneval_verein.karten.repository;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Die Feldgruppen, in denen {@link FieldMerge} zwei Stände einer Einheit zusammenführt: Felder, die zusammengehören,
 * werden nie gemischt (etwa Nach- und Vorname zweier verschiedener Kunden). Die Namen sind die JSON-Feldnamen, wie
 * {@link UnitMapper#normalize} sie schreibt; ein Test gleicht sie mit den Modellklassen ab. Ein gespeichertes Feld, das
 * hier keine Gruppe hat (etwa aus einer neueren App), bildet beim Zusammenführen eine eigene Gruppe mit seinem Namen.
 */
final class FieldGroups {

	private static final Map<String, List<String>> BOOKING = ordered(
			group("customer", "lastName", "firstName", "email"),
			group("place", "seatId", "rowLabel", "tableNumber", "seatNumber", "category", "wheelchairAccessible",
					"unassigned"),
			group("payment", "paymentStatus", "paymentDate"),
			group("collection", "collected", "collectionDate"),
			group("price", "price"),
			group("reservation", "reservationDate"),
			group("comment", "comment"),
			group("id", "id"));

	/** Ohne {@code bookings}: Die Buchungen sind eigene Einheiten. */
	private static final Map<String, List<String>> PRESENTATION = ordered(
			group("hall", "hallId", "seatingPlanId"),
			group("schedule", "date", "time"),
			group("cash", "actualCashAmount", "cashDifferenceComment"),
			group("id", "id"),
			group("name", "name"),
			group("description", "description"),
			group("price", "price"));

	/** Ohne {@code presentations}: Die Vorstellungen sind eigene Einheiten. */
	private static final Map<String, List<String>> EVENT_HEAD = ordered(
			group("id", "id"),
			group("name", "name"),
			group("description", "description"),
			group("archived", "archived"));

	private FieldGroups() {
	}

	/** Die Feldgruppen einer Buchung. */
	static Map<String, List<String>> booking() {
		return BOOKING;
	}

	/** Die Feldgruppen einer Vorstellung (ohne ihre Buchungen). */
	static Map<String, List<String>> presentation() {
		return PRESENTATION;
	}

	/** Die Feldgruppen eines Event-Kopfs, also des Events ohne seine Vorstellungen: jedes Feld eine Gruppe. */
	static Map<String, List<String>> eventHead() {
		return EVENT_HEAD;
	}

	private static Map.Entry<String, List<String>> group(String name, String... fields) {
		return Map.entry(name, List.of(fields));
	}

	/** Die unveränderliche Zuordnung in der Reihenfolge der Angabe. */
	@SafeVarargs
	private static Map<String, List<String>> ordered(Map.Entry<String, List<String>>... groups) {
		Map<String, List<String>> ordered = new LinkedHashMap<>();
		for (Map.Entry<String, List<String>> group : groups) {
			ordered.put(group.getKey(), group.getValue());
		}
		return Collections.unmodifiableMap(ordered);
	}
}
