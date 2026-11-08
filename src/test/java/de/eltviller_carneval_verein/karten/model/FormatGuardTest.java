package de.eltviller_carneval_verein.karten.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;

import de.eltviller_carneval_verein.karten.repository.ContentRules;
import de.eltviller_carneval_verein.karten.repository.JsonMapperFactory;

/**
 * Schutztests für das Datenformat (KARV-136): Sie finden die gespeicherten Klassen und Enums selbst über die
 * Bean-Beschreibungen des Mappers der App, damit eine neue Klasse, ein neues Feld oder ein entfernter Enum-Wert sofort
 * auffällt und eine Entscheidung nach den Regeln braucht, statt nur eine Liste zu ergänzen.
 */
public class FormatGuardTest {

	/** Wo die Regeln stehen; jede Fehlermeldung der Schutztests hängt diesen Verweis an. */
	public static final String RULES = "docs/decisions.md, Abschnitt \"Forward-compatible data format\"";

	/** Die gespeicherten Klassen, die der Lauf ab {@link Hall} und {@link Event} finden muss. */
	private static final Set<Class<?>> NINE = Set.of(Hall.class, HallObject.class, SeatingPlan.class, TableRow.class, Table.class, Seat.class,
			Event.class, Presentation.class, Booking.class);

	/** Je gespeichertem Enum die Werte seit 1.0. Neue Werte dürfen dazukommen (und kommen dann hierher), keiner darf fehlen. */
	private static final Map<Class<?>, List<String>> VALUES_SINCE_1_0 = Map.of(
			PaymentStatus.class, List.of("NONE", "CASH", "CARD", "TRANSFER"),
			Shape.class, List.of("RECTANGLE", "CIRCLE"),
			TextAlignH.class, List.of("LEFT", "CENTER", "RIGHT"),
			TextAlignV.class, List.of("TOP", "CENTER", "BOTTOM"));

	private final ObjectMapper mapper = JsonMapperFactory.create();

	/** Hängt den Verweis auf die Regeln an eine Meldung. */
	static String rules(String message) {
		return message + " – Regeln: " + RULES;
	}

	// Lauf über die Bean-Beschreibungen -->
	/**
	 * Die gespeicherten Klassen: von {@link Hall} und {@link Event} aus rekursiv durch die Eigenschaften der Serialisierungs-
	 * und der Deserialisierungs-Beschreibung (also auch reine Setter) und durch Listen. Ein Werttyp, der weder Blatt noch
	 * Modellklasse ist (etwa ein Record oder eine Map), lässt den Test scheitern.
	 */
	static Set<Class<?>> storedClasses(ObjectMapper mapper) {
		Set<Class<?>> found = new LinkedHashSet<>();
		Deque<Class<?>> open = new ArrayDeque<>(List.of(Hall.class, Event.class));
		while (!open.isEmpty()) {
			Class<?> type = open.removeFirst();
			if (!found.add(type)) {
				continue;
			}
			for (BeanPropertyDefinition property : properties(mapper, type)) {
				for (Class<?> next : storedClassesIn(property.getPrimaryType(), type.getSimpleName() + "." + property.getName())) {
					if (!found.contains(next)) {
						open.addLast(next);
					}
				}
			}
		}
		return found;
	}

	/** Die Eigenschaften beider Beschreibungen ohne Rückverweise (der Elternverweis wird nicht gespeichert). */
	static List<BeanPropertyDefinition> properties(ObjectMapper mapper, Class<?> type) {
		JavaType javaType = mapper.constructType(type);
		List<BeanPropertyDefinition> all = new ArrayList<>(mapper.getSerializationConfig().introspect(javaType).findProperties());
		all.addAll(mapper.getDeserializationConfig().introspect(javaType).findProperties());
		all.removeIf(property -> property.findReferenceType() != null && property.findReferenceType().isBackReference());
		return all;
	}

	/** Die Modellklassen in einem Werttyp; Blätter ergeben nichts, ein unbekannter Werttyp scheitert. */
	private static List<Class<?>> storedClassesIn(JavaType type, String where) {
		if (type.isMapLikeType()) {
			fail(rules(where + " hat den Werttyp " + type + "; eine Map ist kein erlaubter Werttyp"));
		}
		if (type.isCollectionLikeType() || type.isArrayType()) {
			return storedClassesIn(type.getContentType(), where);
		}
		Class<?> raw = type.getRawClass();
		if (isLeaf(raw)) {
			return List.of();
		}
		if (raw.isRecord() || raw.isInterface() || raw.getPackage() != Hall.class.getPackage()) {
			fail(rules(where + " hat den unbekannten Werttyp " + type));
		}
		return List.of(raw);
	}

	private static boolean isLeaf(Class<?> raw) {
		return raw.isPrimitive() || Number.class.isAssignableFrom(raw) || raw == String.class || raw == Boolean.class
				|| raw == LocalDate.class || raw == LocalTime.class || JsonNode.class.isAssignableFrom(raw) || raw.isEnum();
	}
	// <-- Lauf

	// Enums -->
	/**
	 * Die gespeicherten Enums je Fundstelle ("Klasse.feld"): an den deklarierten Feldern (auch als Typargument, etwa
	 * {@code ObjectProperty<PaymentStatus>} oder {@code TolerantEnum<Shape>}) und an den Typen der Bean-Eigenschaften.
	 */
	private Map<String, Set<Class<?>>> enumsBySite(Set<Class<?>> stored) {
		Map<String, Set<Class<?>>> sites = new LinkedHashMap<>();
		for (Class<?> type : stored) {
			for (Field field : type.getDeclaredFields()) {
				if (Modifier.isStatic(field.getModifiers())) {
					continue;
				}
				Set<Class<?>> enums = new LinkedHashSet<>();
				collectEnums(field.getGenericType(), enums);
				if (!enums.isEmpty()) {
					sites.computeIfAbsent(type.getSimpleName() + "." + field.getName(), k -> new LinkedHashSet<>()).addAll(enums);
				}
			}
			for (BeanPropertyDefinition property : properties(mapper, type)) {
				Set<Class<?>> enums = new LinkedHashSet<>();
				collectEnums(property.getPrimaryType(), enums);
				if (!enums.isEmpty()) {
					sites.computeIfAbsent(type.getSimpleName() + "." + property.getName(), k -> new LinkedHashSet<>()).addAll(enums);
				}
			}
		}
		return sites;
	}

	private static void collectEnums(Type type, Set<Class<?>> enums) {
		if (type instanceof Class<?> raw) {
			if (raw.isEnum()) {
				enums.add(raw);
			} else if (raw.isArray()) {
				collectEnums(raw.getComponentType(), enums);
			}
		} else if (type instanceof ParameterizedType parameterized) {
			collectEnums(parameterized.getRawType(), enums);
			for (Type argument : parameterized.getActualTypeArguments()) {
				collectEnums(argument, enums);
			}
		}
	}

	private static void collectEnums(JavaType type, Set<Class<?>> enums) {
		if (type.isEnumType()) {
			enums.add(type.getRawClass());
		}
		for (int i = 0; i < type.containedTypeCount(); i++) {
			collectEnums(type.containedType(i), enums);
		}
		if (type.getContentType() != null) {
			collectEnums(type.getContentType(), enums);
		}
	}

	/** Ob {@code type} ein deklariertes Feld dieses Namens vom Typ {@link TolerantEnum} hat. */
	private static boolean isTolerantField(Class<?> type, String name) {
		return Arrays.stream(type.getDeclaredFields()).anyMatch(field -> field.getName().equals(name) && field.getType() == TolerantEnum.class);
	}
	// <-- Enums

	@Test
	void everyStoredClassFollowsTheRules() throws Exception {
		Set<Class<?>> stored = storedClasses(mapper);
		assertEquals(names(NINE), names(stored), rules("Die gespeicherten Klassen haben sich geändert"));

		for (Class<?> type : stored) {
			JavaType javaType = mapper.constructType(type);
			BeanDescription forReading = mapper.getDeserializationConfig().introspect(javaType);
			BeanDescription forWriting = mapper.getSerializationConfig().introspect(javaType);
			String name = type.getSimpleName();
			assertTrue(mapper.getDeserializationConfig().getDefaultPropertyIgnorals(type, forReading.getClassInfo()).getIgnoreUnknown(),
					rules(name + " braucht @JsonIgnoreProperties(ignoreUnknown = true)"));
			assertNotNull(forReading.findAnySetterAccessor(), rules(name + " braucht einen @JsonAnySetter auf seine UnknownFields"));
			assertNotNull(forWriting.findAnyGetter(), rules(name + " braucht einen @JsonAnyGetter auf seine UnknownFields"));
			assertTrue(Arrays.stream(type.getDeclaredFields()).anyMatch(field -> field.getType() == UnknownFields.class),
					rules(name + " braucht ein Feld vom Typ UnknownFields"));
		}
	}

	@Test
	void everyEnumPropertyIsTolerantOrStrict() {
		Set<Class<?>> stored = storedClasses(mapper);
		Map<String, Class<?>> byName = stored.stream().collect(Collectors.toMap(Class::getSimpleName, type -> type));
		for (String site : enumsBySite(stored).keySet()) {
			Class<?> type = byName.get(site.substring(0, site.indexOf('.')));
			String name = site.substring(site.indexOf('.') + 1);
			assertTrue(isTolerantField(type, name) || ContentRules.STRICT_FIELDS.contains(site),
					rules(site + " hat einen Enum-Typ und ist weder ein TolerantEnum noch in ContentRules.STRICT_FIELDS"));
		}
	}

	@Test
	void enumValuesOnlyGrow() {
		Set<Class<?>> found = new TreeSet<>((a, b) -> a.getName().compareTo(b.getName()));
		enumsBySite(storedClasses(mapper)).values().forEach(found::addAll);
		for (Class<?> type : found) {
			if (!VALUES_SINCE_1_0.containsKey(type)) {
				fail(rules("Das gespeicherte Enum " + type.getSimpleName() + " braucht eine Liste seiner Werte in VALUES_SINCE_1_0"));
			}
		}
		VALUES_SINCE_1_0.forEach((type, values) -> {
			Set<String> current = Arrays.stream(type.getEnumConstants()).map(constant -> ((Enum<?>) constant).name()).collect(Collectors.toSet());
			for (String value : values) {
				assertTrue(current.contains(value), rules(type.getSimpleName() + " hat den Wert " + value + " verloren; Enum-Werte dürfen nur dazukommen"));
			}
		});
	}

	@Test
	void rulesIdMatchesTheStrictEnum() {
		assertEquals("gate-1;payment=CARD,CASH,NONE,TRANSFER;requires=", ContentRules.GATE.rulesId(), RULES);
	}

	private static Set<String> names(Set<Class<?>> types) {
		return types.stream().map(Class::getSimpleName).collect(Collectors.toCollection(TreeSet::new));
	}
}
