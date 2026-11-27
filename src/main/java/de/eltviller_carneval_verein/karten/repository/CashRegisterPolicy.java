package de.eltviller_carneval_verein.karten.repository;

import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.eltviller_carneval_verein.karten.store.ConflictInput;
import de.eltviller_carneval_verein.karten.store.ConflictPolicy;
import de.eltviller_carneval_verein.karten.store.Resolution;
import de.eltviller_carneval_verein.karten.store.Version;

/**
 * Die Fachregel für die Kasse (KARV-144): "Gleich" und "unlesbar" wie bei der Vorstellung, dann "Löschen gewinnt" mit
 * einer Ausnahme, sonst der Dreiwegevergleich je Feldgruppe ({@link FieldGroups#cashRegister()}).
 * <p>
 * <b>Ausnahme:</b> Ist eine Seite eine Löschung und hat die andere die Gruppe {@code cash} gegenüber der Basis geändert
 * (ohne Basis: sie trägt einen Ist-Betrag ungleich 0 oder einen Kommentar), gilt die nicht gelöschte Seite. Gelöscht
 * wird nur eine Kasse ohne Ist-Betrag und Kommentar; eine Zählung der anderen Seite darf dabei nicht verloren gehen. Ist
 * "Meine" die Löschung, gilt "Deren" mit dem Vermerk UNMERGED ({@link PolicyRules#unmerged}); ist "Meine" die Zählung,
 * gilt "Meine" ohne Vermerk. Zustandslos; läuft auf dem Hintergrund-Thread des Versionsspeichers und kennt weder Modell
 * noch Repository.
 */
public final class CashRegisterPolicy implements ConflictPolicy {

	private static final String AMOUNT = "actualCashAmount";
	private static final String COMMENT = "cashDifferenceComment";

	@Override
	public Resolution resolve(ConflictInput input) {
		Resolution resolution = PolicyRules.equal(input);
		if (resolution == null) {
			resolution = PolicyRules.unreadable(input);
		}
		if (resolution == null) {
			resolution = countBeatsDeletion(input);
		}
		return resolution != null ? resolution : PolicyRules.mergeFields(input, FieldGroups.cashRegister());
	}

	/** Die Ausnahme zu "Löschen gewinnt"; {@code null}, wenn sie nicht greift. */
	private static Resolution countBeatsDeletion(ConflictInput input) {
		Version mine = input.mine();
		Version theirs = input.theirs();
		ObjectNode base = PolicyRules.basePayload(input);
		if (mine.deleted() && !theirs.deleted() && cashChanged(base, theirs.payload())) {
			return PolicyRules.unmerged(input);
		}
		if (theirs.deleted() && !mine.deleted() && cashChanged(base, mine.payload())) {
			return new Resolution(mine.payload(), false, List.of(), null);
		}
		return null;
	}

	/**
	 * Ob {@code side} die Gruppe {@code cash} gegenüber {@code base} geändert hat; ohne Basis, ob sie einen Ist-Betrag
	 * ungleich 0 oder einen Kommentar trägt.
	 */
	private static boolean cashChanged(ObjectNode base, ObjectNode side) {
		if (base == null) {
			String comment = side.path(COMMENT).textValue();
			return side.path(AMOUNT).asLong(0) != 0 || (comment != null && !comment.isBlank());
		}
		return FieldGroups.cashRegister().get(FieldGroups.CASH).stream()
				.anyMatch(field -> !Objects.equals(valueOf(base, field), valueOf(side, field)));
	}

	/** Der Wert des Feldes; fehlt es oder ist es {@code null}, {@code null}. */
	private static JsonNode valueOf(ObjectNode node, String field) {
		JsonNode value = node.get(field);
		return value == null || value.isNull() ? null : value;
	}
}
