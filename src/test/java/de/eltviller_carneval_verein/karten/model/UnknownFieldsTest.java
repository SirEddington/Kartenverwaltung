package de.eltviller_carneval_verein.karten.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.eltviller_carneval_verein.karten.repository.JsonMapperFactory;

/**
 * Felder, die das Modell nicht kennt, bleiben beim Lesen und Schreiben erhalten (KARV-134), damit ein Rechner mit dieser
 * Version Daten einer neueren Version nicht beschädigt. Verworfene Felder (aus der Git-Historie ermittelt) bleiben
 * draußen. Erfundene Namen beginnen mit {@code x-test-}.
 */
class UnknownFieldsTest {

	private static final String NEW_FIELD = "x-test-neu";
	private static final String UNKNOWN_FIELD_JSON = "\"" + NEW_FIELD + "\":{\"a\":1}";

	private final ObjectMapper mapper = JsonMapperFactory.create();

	private <T> JsonNode roundTrip(Class<T> type, String json) throws Exception {
		return mapper.valueToTree(mapper.readValue(json, type));
	}

	private void assertKeepsUnknownField(Class<?> type, String json) throws Exception {
		JsonNode written = roundTrip(type, json);
		assertTrue(written.has(NEW_FIELD), type.getSimpleName() + " verliert das unbekannte Feld: " + written);
		assertEquals(mapper.readTree("{\"a\":1}"), written.get(NEW_FIELD));
	}

	private Object unknownOf(Object model) throws Exception {
		return model.getClass().getMethod("getUnknown").invoke(model);
	}

	// Je Klasse: ein unbekanntes Feld überlebt Lesen und Schreiben -->
	@Test
	void hall_keepsUnknownFieldThroughReadAndWrite() throws Exception {
		assertKeepsUnknownField(Hall.class, "{\"id\":\"h1\"," + UNKNOWN_FIELD_JSON + "}");
	}

	@Test
	void hallObject_keepsUnknownFieldThroughReadAndWrite() throws Exception {
		assertKeepsUnknownField(HallObject.class, "{\"id\":\"o1\"," + UNKNOWN_FIELD_JSON + "}");
	}

	@Test
	void seatingPlan_keepsUnknownFieldThroughReadAndWrite() throws Exception {
		assertKeepsUnknownField(SeatingPlan.class, "{\"id\":\"p1\"," + UNKNOWN_FIELD_JSON + "}");
	}

	@Test
	void tableRow_keepsUnknownFieldThroughReadAndWrite() throws Exception {
		assertKeepsUnknownField(TableRow.class, "{\"id\":\"r1\"," + UNKNOWN_FIELD_JSON + "}");
	}

	@Test
	void table_keepsUnknownFieldThroughReadAndWrite() throws Exception {
		assertKeepsUnknownField(Table.class, "{\"id\":\"t1\"," + UNKNOWN_FIELD_JSON + "}");
	}

	@Test
	void seat_keepsUnknownFieldThroughReadAndWrite() throws Exception {
		assertKeepsUnknownField(Seat.class, "{\"id\":\"s1\"," + UNKNOWN_FIELD_JSON + "}");
	}

	@Test
	void event_keepsUnknownFieldThroughReadAndWrite() throws Exception {
		assertKeepsUnknownField(Event.class, "{\"id\":\"e1\"," + UNKNOWN_FIELD_JSON + "}");
	}

	@Test
	void presentation_keepsUnknownFieldThroughReadAndWrite() throws Exception {
		assertKeepsUnknownField(Presentation.class, "{\"id\":\"v1\"," + UNKNOWN_FIELD_JSON + "}");
	}

	@Test
	void booking_keepsUnknownFieldThroughReadAndWrite() throws Exception {
		assertKeepsUnknownField(Booking.class, "{\"id\":\"b1\"," + UNKNOWN_FIELD_JSON + "}");
	}

	@Test
	void unknownFieldInsideNestedObjectsSurvives() throws Exception {
		String json = "{\"id\":\"h1\",\"hallObjects\":[{\"id\":\"o1\"," + UNKNOWN_FIELD_JSON + "}],\"seatingPlans\":[{\"id\":\"p1\","
				+ "\"rows\":[{\"id\":\"r1\"," + UNKNOWN_FIELD_JSON + "}],\"tables\":[{\"id\":\"t1\"," + UNKNOWN_FIELD_JSON + ",\"seats\":[{\"id\":\"s1\","
				+ UNKNOWN_FIELD_JSON + "}]}]}]}";
		JsonNode written = roundTrip(Hall.class, json);
		assertTrue(written.at("/hallObjects/0").has(NEW_FIELD), written.toString());
		assertTrue(written.at("/seatingPlans/0/rows/0").has(NEW_FIELD), written.toString());
		assertTrue(written.at("/seatingPlans/0/tables/0").has(NEW_FIELD), written.toString());
		assertTrue(written.at("/seatingPlans/0/tables/0/seats/0").has(NEW_FIELD), written.toString());
	}
	// <-- Je Klasse

	@Test
	void unknownNumbersKeepTheirNodeType() throws Exception {
		JsonNode written = roundTrip(Event.class, "{\"id\":\"e1\",\"x-test-zahl\":45,\"x-test-komma\":45.0,\"x-test-leer\":null}");
		assertInstanceOf(IntNode.class, written.get("x-test-zahl"));
		assertInstanceOf(DoubleNode.class, written.get("x-test-komma"));
		assertInstanceOf(NullNode.class, written.get("x-test-leer"));
	}

	@Test
	void unknownFieldsKeepTheirOrder() throws Exception {
		Event event = mapper.readValue("{\"x-test-c\":1,\"id\":\"e1\",\"x-test-a\":2,\"x-test-b\":3}", Event.class);
		assertEquals(List.of("x-test-c", "x-test-a", "x-test-b"), new ArrayList<>(event.getUnknown().asMap().keySet()));
	}

	@Test
	void ignoredOnlyNamesAreKeptAndWrittenBack() throws Exception {
		// "status" und "paid" sind an Booking nur per @JsonIgnore ausgeblendet; eine neuere Version darf sie schreiben
		Booking booking = mapper.readValue("{\"id\":\"b1\",\"status\":\"X\",\"paid\":true}", Booking.class);
		assertTrue(booking.getUnknown().has("status"));
		assertTrue(booking.getUnknown().has("paid"));
		JsonNode written = mapper.valueToTree(booking);
		assertEquals("X", written.get("status").asText());
		assertTrue(written.get("paid").asBoolean());
	}

	@Test
	void discardedFieldsAreNotKept() throws Exception {
		JsonNode seat = roundTrip(Seat.class, "{\"id\":\"s1\",\"width\":45,\"height\":30,\"price\":1000,\"paymentStatus\":\"CASH\","
				+ "\"collected\":true,\"lastName\":\"Muster\",\"firstName\":\"Max\",\"email\":\"a@b.de\",\"comment\":\"x\"}");
		for (String name : List.of("height", "price", "paymentStatus", "collected", "lastName", "firstName", "email", "comment")) {
			assertFalse(seat.has(name), "Seat schreibt verworfenes Feld " + name + ": " + seat);
		}
		assertEquals(45, seat.get("width").asInt());

		JsonNode plan = roundTrip(SeatingPlan.class, "{\"id\":\"p1\",\"defaultSeatWidth\":45,\"defaultSeatHeight\":30}");
		assertFalse(plan.has("defaultSeatHeight"), plan.toString());
		assertEquals(45, plan.get("defaultSeatWidth").asInt());

		JsonNode presentation = roundTrip(Presentation.class, "{\"id\":\"v1\",\"tables\":[],\"defaultTableWidth\":70,"
				+ "\"defaultTableHeight\":300,\"defaultSeatWidth\":30,\"defaultSeatHeight\":30,\"hallWidth\":1,\"hallHeight\":1,"
				+ "\"tableRows\":2,\"hallObjects\":[]}");
		for (String name : List.of("tables", "defaultTableWidth", "defaultTableHeight", "defaultSeatWidth", "defaultSeatHeight", "hallWidth",
				"hallHeight", "tableRows", "hallObjects")) {
			assertFalse(presentation.has(name), "Presentation schreibt verworfenes Feld " + name + ": " + presentation);
		}
	}

	@Test
	void discardedNamesAreBurnedPerClassOnly() throws Exception {
		// "height" ist an Seat verworfen, an HallObject ein gewöhnliches Feld; "price" ist an Booking ein gewöhnliches Feld
		assertTrue(roundTrip(HallObject.class, "{\"id\":\"o1\",\"height\":30}").get("height").asDouble() == 30);
		assertEquals(1000, roundTrip(Booking.class, "{\"id\":\"b1\",\"price\":1000}").get("price").asInt());
	}

	// requires -->
	@Test
	void unitClassDropsSatisfiedRequires() throws Exception {
		for (String value : List.of("null", "[]")) {
			for (Class<?> type : List.of(Hall.class, Event.class, Presentation.class, Booking.class)) {
				Object model = mapper.readValue("{\"id\":\"x1\",\"requires\":" + value + "}", type);
				JsonNode written = mapper.valueToTree(model);
				assertFalse(written.has("requires"), type.getSimpleName() + " behält requires " + value + ": " + written);
				assertTrue(((UnknownFields) unknownOf(model)).isEmpty(), type.getSimpleName() + " " + value);
			}
		}
		// auch mit weiteren unbekannten Feldern daneben
		Presentation presentation = mapper.readValue("{\"id\":\"v1\",\"requires\":[]," + UNKNOWN_FIELD_JSON + "}", Presentation.class);
		assertFalse(presentation.getUnknown().has("requires"));
		assertTrue(presentation.getUnknown().has(NEW_FIELD));
	}

	@Test
	void unitClassKeepsUnsatisfiedRequires() throws Exception {
		for (Class<?> type : List.of(Hall.class, Event.class, Presentation.class, Booking.class)) {
			Object model = mapper.readValue("{\"id\":\"x1\",\"requires\":[\"x-test-merkmal\"]}", type);
			UnknownFields unknown = (UnknownFields) unknownOf(model);
			assertTrue(unknown.has("requires"), type.getSimpleName());
			assertEquals(mapper.readTree("[\"x-test-merkmal\"]"), mapper.valueToTree(model).get("requires"));
		}
		// kein Array bzw. kein Text im Array: ebenfalls nicht erfüllt
		assertTrue(mapper.readValue("{\"id\":\"b1\",\"requires\":\"x-test-merkmal\"}", Booking.class).getUnknown().has("requires"));
		assertTrue(mapper.readValue("{\"id\":\"b1\",\"requires\":[1]}", Booking.class).getUnknown().has("requires"));
	}

	@Test
	void nestedClassKeepsRequires() throws Exception {
		for (Class<?> type : List.of(HallObject.class, SeatingPlan.class, TableRow.class, Table.class, Seat.class)) {
			JsonNode written = roundTrip(type, "{\"id\":\"x1\",\"requires\":[]}");
			assertEquals(mapper.readTree("[]"), written.get("requires"), type.getSimpleName());
		}
	}

	@Test
	void requiresSatisfiedRule() throws Exception {
		assertTrue(Requires.satisfied(null));
		assertTrue(Requires.satisfied(NullNode.getInstance()));
		assertTrue(Requires.satisfied(mapper.readTree("[]")));
		assertFalse(Requires.satisfied(mapper.readTree("[\"x-test-merkmal\"]")));
		assertFalse(Requires.satisfied(mapper.readTree("[1]")));
		assertFalse(Requires.satisfied(mapper.readTree("[null]")));
		assertFalse(Requires.satisfied(mapper.readTree("\"x-test-merkmal\"")));
		assertFalse(Requires.satisfied(mapper.readTree("{}")));
		assertFalse(Requires.satisfied(mapper.readTree("0")));
		assertEquals("requires", Requires.KEY);
		assertTrue(Requires.KNOWN.isEmpty());
	}
	// <-- requires

	@Test
	void unknownKeysNeverOverlapBeanProperties() throws Exception {
		List<Class<?>> types = List.of(Hall.class, HallObject.class, SeatingPlan.class, TableRow.class, Table.class, Seat.class, Event.class,
				Presentation.class, Booking.class);
		for (Class<?> type : types) {
			Object fresh = type.getDeclaredConstructor().newInstance();
			JsonNode freshJson = mapper.valueToTree(fresh);
			Set<String> beanNames = new HashSet<>();
			freshJson.fieldNames().forEachRemaining(beanNames::add);
			assertFalse(beanNames.contains(NEW_FIELD), type.getSimpleName());
			assertFalse(beanNames.contains(Requires.KEY), type.getSimpleName() + " schreibt requires selbst");

			// alle Eigenschaften der Klasse plus ein unbekanntes Feld lesen: nur das unbekannte darf in der Map landen
			ObjectNode withExtra = ((ObjectNode) freshJson).deepCopy();
			withExtra.putObject(NEW_FIELD).put("a", 1);
			Object read = mapper.treeToValue(withExtra, type);
			Set<String> unknownKeys = ((UnknownFields) unknownOf(read)).asMap().keySet();
			assertEquals(Set.of(NEW_FIELD), unknownKeys, type.getSimpleName());

			Set<String> overlap = new HashSet<>(unknownKeys);
			overlap.retainAll(beanNames);
			assertTrue(overlap.isEmpty(), type.getSimpleName() + ": " + overlap);
		}
	}

	// UnknownFields selbst -->
	@Test
	void replaceWithCopiesDeeplyInOrderAndReplacesExistingFields() throws Exception {
		UnknownFields source = new UnknownFields();
		JsonNode object = mapper.readTree("{\"a\":1}");
		source.put("x-test-b", object);
		source.put("x-test-a", IntNode.valueOf(2));

		UnknownFields target = new UnknownFields();
		target.put("x-test-alt", IntNode.valueOf(9));
		target.replaceWith(source);

		assertEquals(List.of("x-test-b", "x-test-a"), new ArrayList<>(target.asMap().keySet()));
		assertEquals(object, target.asMap().get("x-test-b"));
		assertNotSame(object, target.asMap().get("x-test-b"));
		assertFalse(target.has("x-test-alt"));

		target.replaceWith(target); // sich selbst ersetzen verliert nichts
		assertEquals(2, target.asMap().size());
	}

	@Test
	void asMapIsReadOnlyAndRemoveClearAndIsEmptyWork() {
		UnknownFields fields = new UnknownFields();
		assertTrue(fields.isEmpty());
		fields.put("x-test-a", IntNode.valueOf(1));
		fields.put("x-test-b", IntNode.valueOf(2));
		assertThrows(UnsupportedOperationException.class, () -> fields.asMap().put("x-test-c", IntNode.valueOf(3)));
		assertFalse(fields.isEmpty());
		fields.remove("x-test-a");
		assertFalse(fields.has("x-test-a"));
		assertTrue(fields.has("x-test-b"));
		fields.clear();
		assertTrue(fields.isEmpty());
	}

	@Test
	void putUnitFieldDropsOnlyASatisfiedRequires() throws Exception {
		UnknownFields fields = new UnknownFields();
		fields.putUnitField("requires", mapper.readTree("[]"));
		fields.putUnitField("requires", NullNode.getInstance());
		fields.putUnitField(NEW_FIELD, IntNode.valueOf(1));
		assertEquals(List.of(NEW_FIELD), new ArrayList<>(fields.asMap().keySet()));
		fields.putUnitField("requires", mapper.readTree("[\"x-test-merkmal\"]"));
		assertTrue(fields.has("requires"));
	}

	@Test
	void getUnknownReturnsTheSameInstance() {
		Seat seat = new Seat();
		assertSame(seat.getUnknown(), seat.getUnknown());
	}
	// <-- UnknownFields selbst
}
