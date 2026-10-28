package de.eltviller_carneval_verein.karten.repository;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.node.ObjectNode;

import de.eltviller_carneval_verein.karten.store.ConflictInput;
import de.eltviller_carneval_verein.karten.store.ConflictNote;
import de.eltviller_carneval_verein.karten.store.ConflictNote.Party;
import de.eltviller_carneval_verein.karten.store.Resolution;
import de.eltviller_carneval_verein.karten.store.UnitSnapshot;
import de.eltviller_carneval_verein.karten.store.UnitState.Status;
import de.eltviller_carneval_verein.karten.store.Version;

/**
 * Die Regeln für Event-Kopf, Vorstellung und Halle und Bausteine für die Buchung. Jede Regel liefert die
 * {@link Resolution}, wenn sie greift, sonst {@code null}. Zustandslos.
 * <p>
 * Für alle Einheitenarten gilt {@link #equal} ("Gleich", die Buchung stellt davor ihre Regel für zwei Löschungen).
 * {@link #unreadable} ist die Regel für Kopf, Vorstellung und Halle: Bei der Buchung heißt "Deren" unlesbar
 * Doppelverkauf (K1), nicht UNMERGED. {@link #deleteWins} gilt für Einheiten, deren Gegenüber an der Identität erkannt
 * wird (siehe dort).
 */
final class PolicyRules {

	private PolicyRules() {
	}

	/**
	 * Die Regeln für Kopf, Vorstellung und Halle in ihrer Reihenfolge: "Gleich", unlesbarer Inhalt, "Löschen gewinnt".
	 *
	 * @return die Auflösung, oder {@code null}, wenn keine Regel greift (beide Seiten haben lesbaren, verschiedenen Inhalt)
	 */
	static Resolution common(ConflictInput input) {
		Resolution resolution = equal(input);
		if (resolution == null) {
			resolution = unreadable(input);
		}
		if (resolution == null) {
			resolution = deleteWins(input, theirs -> true);
		}
		return resolution;
	}

	/**
	 * Unlesbarer Inhalt: Eine nicht gelöschte Version lässt sich nicht als Objekt der Einheitenart lesen oder ihre ID
	 * passt nicht zum Ordner der Einheit ({@link UnitMapper#isReadable}). Mit einer solchen Seite gibt es keinen
	 * Feldvergleich: "Deren" bleibt mit seinem gespeicherten Inhalt, der Vermerk UNMERGED sagt es diesem Rechner (betroffen
	 * ist er auch dann, wenn "Deren" unlesbar ist: Sein Inhalt weicht).
	 */
	static Resolution unreadable(ConflictInput input) {
		Version mine = input.mine();
		Version theirs = input.theirs();
		if (isReadable(input, mine) && isReadable(input, theirs)) {
			return null;
		}
		ConflictNote note = new ConflictNote(ConflictNote.Kind.UNMERGED, input.ownMachineId(), party(theirs), List.of(), null,
				null, mine.id());
		return new Resolution(theirs.payload(), theirs.deleted(), List.of(), note);
	}

	/** Ob die Version lesbar ist; eine Löschung hat keinen Inhalt zu lesen. */
	private static boolean isReadable(ConflictInput input, Version version) {
		return version.deleted()
				|| UnitMapper.isReadable(new UnitSnapshot(input.unit(), version.id(), Status.OK, false, version.payload()));
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
	 * <li>"Meine" ist gelöscht, "Deren" eine Änderung gegen die Basis <em>derselben Sache</em>: Die Löschung gilt;
	 * betroffen ist der Rechner von "Deren", die Gegenseite die eigene Löschung. Hat "Deren" nichts gegen die Basis
	 * geändert, entfällt der Vermerk. Ist "Deren" nicht dieselbe Sache ({@code sameIdentity} falsch), greift die Regel
	 * nicht ({@code null}): Die Löschung dürfte sonst einen Inhalt löschen, den ihr Rechner nie kannte.</li>
	 * </ul>
	 *
	 * @param sameIdentity ob "Deren" dieselbe Sache ist wie die Basis; Kopf, Vorstellung und Halle haben nur eine und
	 *                     übergeben immer wahr, die Buchung prüft ihre Buchungs-ID (ein anderer Verkauf auf demselben Sitz
	 *                     ist ein Doppelverkauf, keine Löschung)
	 */
	static Resolution deleteWins(ConflictInput input, Predicate<Version> sameIdentity) {
		Version mine = input.mine();
		Version theirs = input.theirs();
		if (theirs.deleted() && !mine.deleted()) {
			Version deletion = FieldMerge.introducedBy(input.chainSinceBase(), Version::deleted);
			ConflictNote note = new ConflictNote(ConflictNote.Kind.DELETE_WINS, input.ownMachineId(),
					party(deletion != null ? deletion : theirs), List.of(), null, null, mine.id());
			return new Resolution(theirs.payload(), true, List.of(), note);
		}
		if (mine.deleted() && !theirs.deleted() && sameIdentity.test(theirs)) {
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
