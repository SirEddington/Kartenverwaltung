package de.eltviller_carneval_verein.karten.model;

import static de.eltviller_carneval_verein.karten.model.FormatGuardTest.RULES;
import static de.eltviller_carneval_verein.karten.model.FormatGuardTest.rules;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;

import de.eltviller_carneval_verein.karten.repository.JsonMapperFactory;

/**
 * Die Schlüssel, die die gespeicherten Klassen in die Datei schreiben (KARV-128, KARV-136). Rechner mit v1.0.x lesen
 * dieselben Dateien: Ein zusätzlicher Schlüssel am Hallenobjekt (dort in v1.0.x ohne {@code ignoreUnknown}) machte die
 * Halle für sie unlesbar, und Hilfswerte wie {@code textSettings} gehören nicht in die Datei. Ein neuer Schlüssel braucht
 * eine Entscheidung nach den Regeln ({@link FormatGuardTest#RULES}).
 */
class JsonKeysTest {

	/** Je gespeicherter Klasse die Schlüssel eines frisch gebauten Objekts. */
	private static final Map<Class<?>, Set<String>> KEYS = Map.of(
			Hall.class, Set.of("id", "name", "description", "hallWidth", "hallHeight", "hallObjects", "seatingPlans"),
			HallObject.class, Set.of("id", "name", "desc", "posX", "posY", "width", "height", "color", "shape", "textAuto", "textSize",
					"textAlignH", "textAlignV", "textRotation"),
			SeatingPlan.class, Set.of("id", "name", "description", "defaultTableWidth", "defaultTableHeight", "defaultSeatWidth", "rows", "tables"),
			TableRow.class, Set.of("id", "label", "posX", "posY", "angle", "tableRotation", "tableGap", "aisleWidth", "aisleAfter"),
			Table.class, Set.of("id", "rowId", "tableNumber", "desc", "category", "manualPos", "posX", "posY", "width", "height", "rotation",
					"textAuto", "textSize", "textAlignH", "textAlignV", "textRotation", "seats"),
			Seat.class, Set.of("id", "seatNumber", "wheelchairAccessible", "manualPos", "posX", "posY", "width"),
			Event.class, Set.of("id", "name", "description", "archived", "presentations"),
			Presentation.class, Set.of("id", "name", "date", "time", "description", "hallId", "seatingPlanId", "price", "actualCashAmount",
					"cashDifferenceComment", "bookings"),
			Booking.class, Set.of("id", "seatId", "rowLabel", "tableNumber", "seatNumber", "category", "wheelchairAccessible", "price",
					"paymentStatus", "paidAmount", "collected", "reservationDate", "paymentDate", "collectionDate", "lastName", "firstName", "email",
					"comment", "unassigned"));

	/**
	 * Verbrannte Namen je Klasse (wie in {@code @JsonIgnoreProperties} der Klassen): Sie werden beim Lesen verworfen und
	 * dürfen nie wieder für etwas anderes verwendet werden.
	 */
	private static final Map<Class<?>, Set<String>> BURNED = Map.of(
			Seat.class, Set.of("height", "price", "paymentStatus", "collected", "lastName", "firstName", "email", "comment"),
			SeatingPlan.class, Set.of("defaultSeatHeight"),
			Presentation.class, Set.of("tables", "defaultTableWidth", "defaultTableHeight", "defaultSeatWidth", "defaultSeatHeight", "hallWidth",
					"hallHeight", "tableRows", "hallObjects"));

	private final ObjectMapper mapper = JsonMapperFactory.create();

	private Set<String> keys(Object value) {
		Set<String> keys = new TreeSet<>();
		JsonNode node = mapper.valueToTree(value);
		node.fieldNames().forEachRemaining(keys::add);
		return keys;
	}

	private static Object fresh(Class<?> type) throws Exception {
		return type.getDeclaredConstructor().newInstance();
	}

	@Test
	void hallObject_writesExactlyTheKeysOfVersion1_0() {
		assertEquals(new TreeSet<>(Set.of("id", "name", "desc", "posX", "posY", "width", "height", "color", "shape", "textAuto", "textSize",
				"textAlignH", "textAlignV", "textRotation")), keys(new HallObject()), RULES);
	}

	@Test
	void seat_writesTheWidthAsDiameterAndNoHeight() {
		assertEquals(new TreeSet<>(Set.of("id", "seatNumber", "wheelchairAccessible", "manualPos", "posX", "posY", "width")), keys(new Seat()),
				RULES);
	}

	@Test
	void table_writesTheTextFieldsButNoTextSettings() {
		Set<String> keys = keys(new Table());
		assertTrue(keys.containsAll(Set.of("textAuto", "textSize", "textAlignH", "textAlignV", "textRotation")), rules(keys.toString()));
		assertFalse(keys.contains("textSettings"), rules(keys.toString()));
	}

	@Test
	void seatingPlan_writesTheSeatWidthButNoSeatHeight() {
		Set<String> keys = keys(new SeatingPlan());
		assertTrue(keys.contains("defaultSeatWidth"), rules(keys.toString()));
		assertFalse(keys.contains("defaultSeatHeight"), rules(keys.toString()));
	}

	@Test
	void everyStoredClassWritesExactlyItsKeys() throws Exception {
		Set<Class<?>> stored = FormatGuardTest.storedClasses(mapper);
		assertEquals(names(stored), names(KEYS.keySet()), rules("Jede gespeicherte Klasse braucht ihre feste Schlüsselliste in KEYS"));
		for (Class<?> type : stored) {
			assertEquals(new TreeSet<>(KEYS.get(type)), keys(fresh(type)),
					rules(type.getSimpleName() + " schreibt andere Schlüssel; ein neuer Schlüssel braucht eine Entscheidung"));
		}
	}

	@Test
	void noHiddenDiscardedOrReservedNamesAreWritten() throws Exception {
		for (Class<?> type : FormatGuardTest.storedClasses(mapper)) {
			String name = type.getSimpleName();
			Set<String> keys = keys(fresh(type));

			// per @JsonIgnore ausgeblendet und ohne ausdrückliches @JsonProperty (also nicht shape/textAlignH/textAlignV);
			// die Namen sammelt Jackson nur in der Deserialisierungs-Beschreibung, und erst nach findProperties
			Set<String> properties = FormatGuardTest.properties(mapper, type).stream().map(BeanPropertyDefinition::getName)
					.collect(Collectors.toSet());
			BeanDescription forReading = mapper.getDeserializationConfig().introspect(mapper.constructType(type));
			forReading.findProperties();
			Set<String> hidden = new HashSet<>(forReading.getIgnoredPropertyNames());
			hidden.removeAll(properties);
			assertEquals(Set.of(), intersection(keys, hidden), rules(name + " schreibt ausgeblendete Namen"));

			Set<String> burned = BURNED.getOrDefault(type, Set.of());
			assertEquals(Set.of(), intersection(keys, burned), rules(name + " schreibt verbrannte Namen"));
			JsonIgnoreProperties ignorals = type.getAnnotation(JsonIgnoreProperties.class);
			Set<String> stillBurned = ignorals == null ? Set.of() : new TreeSet<>(Set.of(ignorals.value()));
			assertTrue(stillBurned.containsAll(burned), rules(name + " muss die verbrannten Namen " + new TreeSet<>(burned)
					+ " weiter in @JsonIgnoreProperties verwerfen, hat " + stillBurned));

			assertFalse(keys.contains(Requires.KEY), rules(name + " schreibt den reservierten Namen " + Requires.KEY));
			assertFalse(properties.contains(Requires.KEY), rules(name + " hat eine Eigenschaft mit dem reservierten Namen " + Requires.KEY));
		}
	}

	private static Set<String> intersection(Set<String> a, Set<String> b) {
		Set<String> both = new TreeSet<>(a);
		both.retainAll(b);
		return both;
	}

	private static Set<String> names(Set<Class<?>> types) {
		return types.stream().map(Class::getSimpleName).collect(Collectors.toCollection(TreeSet::new));
	}
}
