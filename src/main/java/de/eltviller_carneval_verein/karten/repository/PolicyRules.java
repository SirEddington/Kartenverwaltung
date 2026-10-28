package de.eltviller_carneval_verein.karten.repository;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.eltviller_carneval_verein.karten.store.ConflictInput;
import de.eltviller_carneval_verein.karten.store.ConflictNote;
import de.eltviller_carneval_verein.karten.store.ConflictNote.Party;
import de.eltviller_carneval_verein.karten.store.Resolution;
import de.eltviller_carneval_verein.karten.store.Version;

/**
 * Die Regeln, die für alle Einheitenarten gelten, und die Bausteine der Strategien: "Gleich" und "Löschen gewinnt" (die
 * Buchung stellt davor ihre eigene Regel für zwei Löschungen). Jede Regel liefert die {@link Resolution}, wenn sie
 * greift, sonst {@code null}. Zustandslos.
 */
final class PolicyRules {

	private PolicyRules() {
	}

	/**
	 * Die Regeln in ihrer Reihenfolge: unlesbarer Inhalt, "Gleich", "Löschen gewinnt".
	 *
	 * @return die Auflösung, oder {@code null}, wenn keine Regel greift (beide Seiten haben Inhalt und verschiedenen)
	 */
	static Resolution common(ConflictInput input) {
		Resolution resolution = unreadable(input);
		if (resolution == null) {
			resolution = equal(input);
		}
		if (resolution == null) {
			resolution = deleteWins(input);
		}
		return resolution;
	}

	/**
	 * Unlesbarer Inhalt (kein Payload bei einer nicht gelöschten Version): "Deren" bleibt, der Vermerk UNMERGED sagt es
	 * diesem Rechner. Ohne Inhalt von "Deren" (eine Auflösung ohne Inhalt würde der Dienst verwerfen) bleibt dessen
	 * Datei, wie sie ist: ein leeres Objekt.
	 */
	static Resolution unreadable(ConflictInput input) {
		Version mine = input.mine();
		Version theirs = input.theirs();
		if ((mine.deleted() || mine.payload() != null) && (theirs.deleted() || theirs.payload() != null)) {
			return null;
		}
		ConflictNote note = new ConflictNote(ConflictNote.Kind.UNMERGED, input.ownMachineId(), party(theirs), List.of(), null,
				null, mine.id());
		ObjectNode payload = theirs.payload() != null ? theirs.payload() : JsonNodeFactory.instance.objectNode();
		return new Resolution(payload, theirs.deleted(), List.of(), note);
	}

	/** "Gleich": Ist "Meine" gleich "Deren" oder gleich der Basis, gilt "Deren", ohne Vermerk. */
	static Resolution equal(ConflictInput input) {
		if (sameContent(input.mine(), input.theirs()) || sameContent(input.mine(), input.base())) {
			return new Resolution(input.theirs().payload(), input.theirs().deleted(), List.of(), null);
		}
		return null;
	}

	/**
	 * "Löschen gewinnt":
	 * <ul>
	 * <li>"Deren" ist gelöscht, "Meine" eine Änderung: "Deren" gilt; betroffen ist dieser Rechner, die Gegenseite die
	 * Löschung in der Kette.</li>
	 * <li>"Meine" ist gelöscht, "Deren" eine Änderung gegen die Basis: Die Löschung gilt; betroffen ist der Rechner von
	 * "Deren", die Gegenseite die eigene Löschung. Hat "Deren" nichts gegen die Basis geändert, entfällt der
	 * Vermerk.</li>
	 * </ul>
	 */
	static Resolution deleteWins(ConflictInput input) {
		Version mine = input.mine();
		Version theirs = input.theirs();
		if (theirs.deleted() && !mine.deleted()) {
			Version deletion = FieldMerge.introducedBy(input.chainSinceBase(), Version::deleted);
			ConflictNote note = new ConflictNote(ConflictNote.Kind.DELETE_WINS, input.ownMachineId(),
					party(deletion != null ? deletion : theirs), List.of(), null, null, mine.id());
			return new Resolution(theirs.payload(), true, List.of(), note);
		}
		if (mine.deleted() && !theirs.deleted()) {
			ConflictNote note = sameContent(theirs, input.base()) ? null
					: new ConflictNote(ConflictNote.Kind.DELETE_WINS, theirs.machine(), party(mine), List.of(), null, null,
							theirs.id());
			return new Resolution(null, true, List.of(), note);
		}
		return null;
	}

	/** Ob beide Stände dasselbe sagen: beide gelöscht, oder beide mit gleichem Inhalt. Ein fehlender Stand ist keinem gleich. */
	static boolean sameContent(Version a, Version b) {
		if (a == null || b == null || a.deleted() != b.deleted()) {
			return false;
		}
		return a.deleted() || Objects.equals(a.payload(), b.payload());
	}

	/** Die Gegenseite des Vermerks nach den Angaben der Version. */
	static Party party(Version version) {
		return new Party(version.machine(), version.machineName(), version.operator(), version.id());
	}

	/**
	 * Die Auflösung für Event-Kopf und Vorstellung nach den gemeinsamen Regeln und sonst dem Dreiwegevergleich je
	 * Feldgruppe. Strittige Gruppen kommen mit beiden Ständen in den Vermerk FIELDS (betroffen ist dieser Rechner),
	 * dort gilt "Deren".
	 */
	static Resolution mergeFields(ConflictInput input, Map<String, List<String>> groups) {
		Resolution common = common(input);
		if (common != null) {
			return common;
		}
		Version base = input.base();
		ObjectNode basePayload = base == null || base.deleted() ? null : base.payload();
		FieldMerge.Result result = FieldMerge.merge(basePayload, input.mine().payload(), input.theirs().payload(), groups);
		if (result.conflicts().isEmpty()) {
			return new Resolution(result.merged(), false, List.of(), null);
		}
		Version introducer = FieldMerge.introducedByConflict(input.chainSinceBase(), groups, result.conflicts());
		ConflictNote note = new ConflictNote(ConflictNote.Kind.FIELDS, input.ownMachineId(),
				party(introducer != null ? introducer : input.theirs()), result.conflicts(), null, null, input.mine().id());
		return new Resolution(result.merged(), false, List.of(), note);
	}
}
