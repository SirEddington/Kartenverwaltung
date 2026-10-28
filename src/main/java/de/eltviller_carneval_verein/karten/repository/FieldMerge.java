package de.eltviller_carneval_verein.karten.repository;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.eltviller_carneval_verein.karten.store.ConflictNote.FieldConflict;
import de.eltviller_carneval_verein.karten.store.Version;

/**
 * Der Dreiwegevergleich zweier Stände einer Einheit gegen ihre gemeinsame Basis, je {@link FieldGroups Feldgruppe}.
 * <p>
 * Ein fehlendes Feld und ein Feld mit dem Wert {@code null} gelten als gleich. Die Strategien arbeiten auf JSON und
 * kennen weder Modell noch Repository; alle Methoden sind zustandslos.
 */
final class FieldMerge {

	/**
	 * Das Ergebnis des Zusammenführens.
	 *
	 * @param merged    der zusammengeführte Inhalt (eine neue Kopie)
	 * @param conflicts die Gruppen, in denen beide Seiten verschieden geändert haben, mit den Gruppenfeldern beider
	 *                  Seiten; in der zusammengeführten Fassung gilt dort der Stand von "Deren"
	 */
	record Result(ObjectNode merged, List<FieldConflict> conflicts) {
	}

	private FieldMerge() {
	}

	/**
	 * Führt {@code mine} und {@code theirs} gegen {@code base} zusammen. Je Gruppe gilt: Hat nur eine Seite die Gruppe
	 * gegenüber der Basis geändert, gilt diese; sind beide gleich, ist es kein Konflikt; sonst gilt {@code theirs}, und
	 * die Gruppe kommt als Konflikt in das Ergebnis. Eine Gruppe wird nie gemischt. Ein Feld, das keine Gruppe kennt,
	 * bildet eine eigene Gruppe mit seinem Namen. Die Eingaben bleiben unverändert.
	 *
	 * @param base   der Stand, auf dem beide Seiten aufbauen; {@code null} gilt als leeres Objekt
	 * @param groups Gruppenname auf Feldnamen, etwa {@link FieldGroups#booking()}
	 */
	static Result merge(ObjectNode base, ObjectNode mine, ObjectNode theirs, Map<String, List<String>> groups) {
		Objects.requireNonNull(mine, "mine");
		Objects.requireNonNull(theirs, "theirs");
		ObjectNode before = base != null ? base : JsonNodeFactory.instance.objectNode();

		ObjectNode merged = theirs.deepCopy();
		List<FieldConflict> conflicts = new ArrayList<>();
		for (Map.Entry<String, List<String>> group : withUnknownFields(groups, before, mine, theirs).entrySet()) {
			List<String> fields = group.getValue();
			if (same(mine, theirs, fields) || same(mine, before, fields)) {
				continue; // beide gleich, oder nur "Deren" hat geändert: "Deren" gilt, und die Kopie hat sie schon
			}
			if (same(theirs, before, fields)) {
				for (String field : fields) {
					if (mine.has(field)) {
						merged.set(field, mine.get(field).deepCopy());
					} else {
						merged.remove(field);
					}
				}
			} else {
				conflicts.add(new FieldConflict(group.getKey(), slice(mine, fields), slice(theirs, fields)));
			}
		}
		return new Result(merged, List.copyOf(conflicts));
	}

	/**
	 * Die älteste Version der Kette, die {@code has} erfüllt: die, die einen Inhalt eingebracht hat. Für den Vermerk
	 * ("Gegenseite"): Ist "Deren" selbst die Zusammenführung eines dritten Rechners, ist es dessen ursprüngliche Version.
	 *
	 * @param chainSinceBase die Glieder der Kette seit der Basis, ältestes zuerst
	 * @return die Version, oder {@code null}, wenn keine passt
	 */
	static Version introducedBy(List<Version> chainSinceBase, Predicate<Version> has) {
		for (Version version : chainSinceBase) {
			if (has.test(version)) {
				return version;
			}
		}
		return null;
	}

	/**
	 * Die älteste Version der Kette, die in einer der strittigen Gruppen den Wert von "Deren" gesetzt hat: Sie hat die
	 * Gruppe so, wie der Konflikt sie für "Deren" festhält (nicht gelöscht).
	 *
	 * @return die Version, oder {@code null}, wenn keine passt
	 */
	static Version introducedByConflict(List<Version> chainSinceBase, Map<String, List<String>> groups,
			List<FieldConflict> conflicts) {
		return introducedBy(chainSinceBase, version -> {
			if (version.deleted() || version.payload() == null) {
				return false;
			}
			for (FieldConflict conflict : conflicts) {
				List<String> fields = new ArrayList<>(groups.getOrDefault(conflict.group(), List.of(conflict.group())));
				conflict.theirs().fieldNames().forEachRemaining(name -> {
					if (!fields.contains(name)) {
						fields.add(name);
					}
				});
				if (same(version.payload(), conflict.theirs(), fields)) {
					return true;
				}
			}
			return false;
		});
	}

	/** Die Gruppen und dazu eine eigene Gruppe je Feld in einem der Stände, das keine Gruppe kennt. */
	private static Map<String, List<String>> withUnknownFields(Map<String, List<String>> groups, ObjectNode... nodes) {
		Map<String, List<String>> all = new LinkedHashMap<>(groups);
		Set<String> known = new HashSet<>();
		groups.values().forEach(known::addAll);
		for (ObjectNode node : nodes) {
			for (Iterator<String> names = node.fieldNames(); names.hasNext();) {
				String name = names.next();
				if (known.add(name)) {
					// trägt eine Gruppe schon diesen Namen, gehört das Feld dazu (nie eine Gruppe ersetzen)
					all.merge(name, List.of(name), (old, added) -> {
						List<String> extended = new ArrayList<>(old);
						extended.addAll(added);
						return extended;
					});
				}
			}
		}
		return all;
	}

	/** Ob beide Stände in allen Feldern übereinstimmen; fehlend und {@code null} zählen gleich. */
	private static boolean same(ObjectNode a, ObjectNode b, List<String> fields) {
		for (String field : fields) {
			if (!Objects.equals(valueOf(a, field), valueOf(b, field))) {
				return false;
			}
		}
		return true;
	}

	private static JsonNode valueOf(ObjectNode node, String field) {
		JsonNode value = node.get(field);
		return value == null || value.isNull() ? null : value;
	}

	/** Die Felder der Gruppe, die der Stand enthält, als Kopie. */
	private static ObjectNode slice(ObjectNode node, List<String> fields) {
		ObjectNode slice = JsonNodeFactory.instance.objectNode();
		for (String field : fields) {
			if (node.has(field)) {
				slice.set(field, node.get(field).deepCopy());
			}
		}
		return slice;
	}
}
