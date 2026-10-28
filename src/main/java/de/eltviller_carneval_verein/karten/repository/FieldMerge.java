package de.eltviller_carneval_verein.karten.repository;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
	 * Die Version, die einen Inhalt eingebracht hat: der Anfang der jüngsten ununterbrochenen Reihe von Versionen, die
	 * {@code has} erfüllen. Wurde ein Wert gesetzt, überschrieben und wieder gesetzt (V, W, V), zählt das zweite V, nicht
	 * das erste. Für den Vermerk ("Gegenseite"): Ist "Deren" selbst die Zusammenführung eines dritten Rechners, ist es
	 * dessen ursprüngliche Version.
	 *
	 * @param chainSinceBase die Glieder der Kette seit der Basis, ältestes zuerst
	 * @return die Version, oder {@code null}, wenn keine passt
	 */
	static Version introducedBy(List<Version> chainSinceBase, Predicate<Version> has) {
		for (int end = chainSinceBase.size() - 1; end >= 0; end--) {
			if (has.test(chainSinceBase.get(end))) {
				int start = end;
				while (start > 0 && has.test(chainSinceBase.get(start - 1))) {
					start--;
				}
				return chainSinceBase.get(start);
			}
		}
		return null;
	}

	/**
	 * Die Version für die Gegenseite eines Vermerks FIELDS. Ein Vermerk nennt nur eine Gegenseite: Je strittiger Gruppe
	 * zählt der Anfang der jüngsten Reihe von Versionen, die den Wert von "Deren" tragen ({@link #introducedBy}); bei
	 * mehreren strittigen Gruppen gilt davon die jüngste, also die Version, die zuletzt einen gewinnenden Wert gesetzt hat.
	 *
	 * @return die Version, oder {@code null}, wenn keine passt
	 */
	static Version introducedByConflict(List<Version> chainSinceBase, Map<String, List<String>> groups,
			List<FieldConflict> conflicts) {
		Version latest = null;
		int latestIndex = -1;
		for (FieldConflict conflict : conflicts) {
			List<String> fields = fieldsOf(groups, conflict);
			Version start = introducedBy(chainSinceBase,
					version -> !version.deleted() && same(version.payload(), conflict.theirs(), fields));
			int index = start == null ? -1 : chainSinceBase.indexOf(start);
			if (index > latestIndex) {
				latest = start;
				latestIndex = index;
			}
		}
		return latest;
	}

	/** Die Felder der strittigen Gruppe: die der Gruppe, bei einem unbekannten Feld die Felder im Konflikt. */
	private static List<String> fieldsOf(Map<String, List<String>> groups, FieldConflict conflict) {
		List<String> fields = groups.get(conflict.group());
		if (fields != null) {
			return fields;
		}
		Set<String> names = new LinkedHashSet<>();
		conflict.mine().fieldNames().forEachRemaining(names::add);
		conflict.theirs().fieldNames().forEachRemaining(names::add);
		return List.copyOf(names);
	}

	/**
	 * Die Gruppen und dazu eine eigene Gruppe je Feld in einem der Stände, das keine Gruppe kennt. Sie heißt wie das Feld;
	 * trägt schon eine Gruppe diesen Namen, hängt ein Stern an, denn das Feld gehört nicht zu dieser Gruppe.
	 */
	private static Map<String, List<String>> withUnknownFields(Map<String, List<String>> groups, ObjectNode... nodes) {
		Map<String, List<String>> all = new LinkedHashMap<>(groups);
		Set<String> known = new HashSet<>();
		groups.values().forEach(known::addAll);
		for (ObjectNode node : nodes) {
			for (Iterator<String> names = node.fieldNames(); names.hasNext();) {
				String name = names.next();
				if (known.add(name)) {
					String key = name;
					while (all.containsKey(key)) {
						key += "*";
					}
					all.put(key, List.of(name));
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
	static ObjectNode slice(ObjectNode node, List<String> fields) {
		ObjectNode slice = JsonNodeFactory.instance.objectNode();
		for (String field : fields) {
			if (node.has(field)) {
				slice.set(field, node.get(field).deepCopy());
			}
		}
		return slice;
	}
}
