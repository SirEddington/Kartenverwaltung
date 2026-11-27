package de.eltviller_carneval_verein.karten.repository;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.node.ObjectNode;

import de.eltviller_carneval_verein.karten.model.Requires;
import de.eltviller_carneval_verein.karten.store.Change;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.UnitSnapshot;
import de.eltviller_carneval_verein.karten.store.UnitType;

/**
 * Die Sperre für v1.1 (KARV-144): das Merkmal {@link Requires#CASH_REGISTERS} in {@code requires} am Event-Kopf. Das
 * Speichern leitet es aus dem Stand des Versionsspeichers ab, nicht aus dem Modell (das Modell verwirft es beim Lesen,
 * {@code UnknownFields.putUnitField}), und ordnet die Änderungen eines Speichervorgangs so, dass ein Abbruch v1.1 nie ein
 * Event mit geleerten alten Feldern öffnen lässt.
 * <ul>
 * <li><b>Setzen:</b> Der gespeicherte Kopf trägt das Merkmal nicht, und nach dem Speichervorgang steht eine lebende
 * Kassen-Einheit des Events im Versionsspeicher (der Vorgang legt eine an oder ändert sie, oder der Versionsspeicher kennt
 * schon eine, auch eine gesperrte, unlesbare oder zurückgestellte).</li>
 * <li><b>Entfernen:</b> nur in einem Speichervorgang, der selbst eine Kassen-Einheit löscht und nach dem keine lebende
 * mehr steht. Sonst bleibt ein gespeichertes Merkmal, auch wenn dieser Rechner keine Kasse des Events kennt: Am
 * gemeinsamen Ordner kann eine noch unterwegs sein.</li>
 * <li><b>Schreiben:</b> Weicht das abgeleitete Merkmal vom gespeicherten ab, wird der Kopf mitgeschrieben, auch wenn sich
 * sonst nichts an ihm geändert hat, auf seiner bekannten Version. Ist er gesperrt oder wartend, unterbleibt nur das
 * Mitschreiben; der nächste Speichervorgang holt es nach. Fehlend, {@code null} und {@code []} heißen "kein Merkmal".</li>
 * </ul>
 * Zustandslos.
 */
final class HeadRequires {

	private HeadRequires() {
	}

	/** Ob {@code requires} im Inhalt des Kopfs das Merkmal enthält; fehlend, {@code null} und {@code []} heißen nein. */
	static boolean carries(ObjectNode headPayload) {
		return headPayload != null && Requires.contains(headPayload.get(Requires.KEY), Requires.CASH_REGISTERS);
	}

	/** Eine Kopie des Inhalts mit {@code "requires": ["cashRegisters"]} bzw. ohne das Feld. */
	static ObjectNode with(ObjectNode headPayload, boolean required) {
		return Requires.with(headPayload, Requires.CASH_REGISTERS, required);
	}

	/**
	 * Der Inhalt des Kopfs ohne {@code requires}: entfernt das Feld an Ort und Stelle und gibt denselben Inhalt zurück (für
	 * Vergleiche und das Zusammenführen, in denen das Merkmal nicht zählt).
	 */
	static ObjectNode withoutRequires(ObjectNode headPayload) {
		headPayload.remove(Requires.KEY);
		return headPayload;
	}

	/**
	 * Setzt am Kopf das abgeleitete Merkmal (siehe Klasse) und ordnet die Änderungen:
	 * <ol>
	 * <li>der Kopf, wenn er nicht wiederbelebt wird und das Merkmal bekommt oder behält;</li>
	 * <li>die Kassen-Einheiten, die angelegt oder geändert werden;</li>
	 * <li>die Löschungen unter einer wiederbelebten Eltern-Einheit, die {@link UnitMapper#changes} voranstellt;</li>
	 * <li>sonst der Kopf an dieser Stelle (wiederbelebt: bis er lebt, ist alles darunter unsichtbar);</li>
	 * <li>alles Übrige in seiner Reihenfolge;</li>
	 * <li>der Kopf, wenn er das Merkmal verliert: nach den Löschungen der Kassen.</li>
	 * </ol>
	 *
	 * @param changes          die Änderungen, wie {@link UnitMapper#changes} sie bildet; der Kopf steht darin, wenn er
	 *                         anders ist als sein Stand ({@link UnitMapper#sameAsModel}, ohne {@code requires})
	 * @param baseline         der Vergleichsstand des Events
	 * @param model            der Inhalt des Kopfs im Modell (ohne {@code requires}), wie {@link UnitMapper#changes} ihn
	 *                         unter den Einheiten findet
	 * @param storeEventStates die Stände der Kassen-Einheiten des Events im Versionsspeicher; andere Einheiten darin zählen
	 *                         nicht
	 * @return die Änderungen in der neuen Reihenfolge; ohne Kopf oder mit einem unbekannten Merkmal an ihm unverändert
	 */
	static List<Change> adjust(String eventId, List<Change> changes, Map<UnitKey, UnitSnapshot> baseline,
			ObjectNode model, Map<UnitKey, UnitSnapshot> storeEventStates) {
		UnitKey head = UnitKey.event(eventId);
		// ein unbekanntes Merkmal im Modell bliebe sonst nicht stehen; die Prüfung des eigenen Inhalts lehnt es danach ab
		if (model == null || !Requires.satisfied(model.get(Requires.KEY))) {
			return changes;
		}
		UnitSnapshot known = baseline.get(head);
		boolean revived = known != null && known.version() != null && known.deleted();
		boolean stored = known != null && known.isLive() && carries(known.payload());

		Set<UnitKey> writtenCash = new HashSet<>();
		Set<UnitKey> deletedCash = new HashSet<>();
		Change headChange = null;
		for (Change change : changes) {
			if (change.unit().equals(head)) {
				headChange = change;
			} else if (change.unit().type() == UnitType.CASH_REGISTER) {
				(change.deleted() ? deletedCash : writtenCash).add(change.unit());
			}
		}
		boolean liveAfter = !writtenCash.isEmpty() || storeEventStates.values().stream()
				.anyMatch(state -> state.unit().type() == UnitType.CASH_REGISTER && !deletedCash.contains(state.unit())
						&& !UnitMapper.isAbsent(state));
		boolean required = liveAfter || (stored && deletedCash.isEmpty());

		ObjectNode payload = with(model, required);
		if (headChange != null) {
			headChange = new Change(head, headChange.parents(), payload, false, headChange.note(), headChange.seen(),
					headChange.marks());
		} else if (stored != required && UnitMapper.blockedStatus(known) == null) {
			// sonst gleich (sameAsModel), nur das Merkmal weicht ab: mitschreiben auf der bekannten Version, wenn der Kopf
			// weder gesperrt ist noch wartet (wie UnitMapper.requireWritable)
			headChange = new Change(head, List.of(known.version()), payload, false);
		}
		return ordered(changes, headChange, revived, stored && !required, baseline);
	}

	/** Die Reihenfolge von {@link #adjust}. */
	private static List<Change> ordered(List<Change> changes, Change headChange, boolean revived, boolean losing,
			Map<UnitKey, UnitSnapshot> baseline) {
		List<Change> cashWrites = new ArrayList<>();
		List<Change> cleanup = new ArrayList<>();
		List<Change> rest = new ArrayList<>();
		for (Change change : changes) {
			UnitKey unit = change.unit();
			if (unit.type() == UnitType.EVENT) {
				continue;
			}
			if (unit.type() == UnitType.CASH_REGISTER && !change.deleted()) {
				cashWrites.add(change);
			} else if (change.deleted() && UnitMapper.revivesParent(baseline, unit)) {
				cleanup.add(change);
			} else {
				rest.add(change);
			}
		}
		boolean first = headChange != null && !revived && carries(headChange.payload());
		boolean last = headChange != null && !revived && losing;
		List<Change> result = new ArrayList<>(changes.size() + 1);
		if (first) {
			result.add(headChange);
		}
		result.addAll(cashWrites);
		result.addAll(cleanup);
		if (headChange != null && !first && !last) {
			result.add(headChange);
		}
		result.addAll(rest);
		if (last) {
			result.add(headChange);
		}
		return result;
	}
}
