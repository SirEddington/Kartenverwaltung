package de.eltviller_carneval_verein.karten.repository;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.eltviller_carneval_verein.karten.store.ConflictInput;
import de.eltviller_carneval_verein.karten.store.ConflictNote;
import de.eltviller_carneval_verein.karten.store.ConflictNote.FieldConflict;
import de.eltviller_carneval_verein.karten.store.ConflictPolicy;
import de.eltviller_carneval_verein.karten.store.Resolution;
import de.eltviller_carneval_verein.karten.store.Version;

/**
 * Die Fachregel für die Halle: Sie gilt als ein einziges Feld. "Gleich" und "Löschen gewinnt" wie bei jeder Einheit;
 * Änderung gegen Änderung: "Deren" gilt, der Vermerk FIELDS hat die eine Gruppe {@code hall} mit den beiden Hallennamen.
 * Der Inhalt von "Meine" bleibt in dessen Version, die die Zusammenführung als Vorgänger nennt. Zustandslos; läuft auf
 * dem Hintergrund-Thread des Versionsspeichers und kennt weder Modell noch Repository.
 */
public final class HallPolicy implements ConflictPolicy {

	/** Name der einzigen Feldgruppe im Vermerk. */
	static final String GROUP = "hall";

	@Override
	public Resolution resolve(ConflictInput input) {
		Resolution common = PolicyRules.common(input);
		if (common != null) {
			return common;
		}
		Version mine = input.mine();
		Version theirs = input.theirs();
		Version introducer = PolicyRules.introducerOr(input.chainSinceBase(),
				version -> !version.deleted() && theirs.payload().equals(version.payload()), theirs);
		ConflictNote note = ConflictNote.ofFields(input.ownMachineId(), ConflictNote.Party.of(introducer),
				List.of(new FieldConflict(GROUP, nameOf(mine.payload()), nameOf(theirs.payload()))), null, mine.id());
		return new Resolution(theirs.payload(), false, List.of(), note);
	}

	/** Der Hallenname als {@code {"name": …}}. */
	private static ObjectNode nameOf(ObjectNode payload) {
		JsonNode name = payload.get("name");
		ObjectNode node = JsonNodeFactory.instance.objectNode();
		node.set("name", name != null ? name.deepCopy() : NullNode.getInstance());
		return node;
	}
}
