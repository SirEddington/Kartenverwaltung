package de.eltviller_carneval_verein.karten.repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.eltviller_carneval_verein.karten.model.CashRegister;
import de.eltviller_carneval_verein.karten.model.CashRegisters;
import de.eltviller_carneval_verein.karten.model.CashRegisters.VisibleCashRegister;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.UnitSnapshot;
import de.eltviller_carneval_verein.karten.store.UnitState;
import de.eltviller_carneval_verein.karten.store.UnitType;
import de.eltviller_carneval_verein.karten.store.Version;

/**
 * Wiederherstellen lässt Kassen stehen (KARV-144): Es setzt Vorstellungen und Buchungen zurück, Kassen bleiben, wie sie
 * sind. "Heute" ist der frisch gelesene Stand der Einheiten im Versionsspeicher, nicht das Modell; eine Einheit lebt
 * heute, wenn ihr aktueller Stand nicht gelöscht ist, auch unter einem gelöschten Kopf. Die Regeln stehen im Spec
 * "Kassen am Event", Abschnitt "Wiederherstellen". Zustandslos.
 */
final class CashRestore {

	private CashRestore() {
	}

	/**
	 * Der Zielstand mit dem heutigen Stand jeder lesbaren Kassen-Einheit des Events statt ihres Glieds zum Zeitpunkt, auch
	 * für Kassen, die es zum Zeitpunkt noch nicht gab, und für Kassen unter einem gelöschten Kopf. Eine gesperrte oder
	 * wartende Kassen-Einheit mit lesbarem Stand steht mit diesem darin, wie heute im Modell; eine ohne lesbaren Stand
	 * (unlesbar, inhaltlich zu neu, gelöscht) fehlt. So schreibt das Wiederherstellen eine Kasse nur, wenn
	 * {@link #adjust} sie ändert.
	 *
	 * @param states  die frisch gelesenen Einheiten des Events
	 * @param targets der Zielstand je Einheit ({@link RestorePlanner#targetsAt}); bleibt unverändert
	 */
	static Map<UnitKey, UnitSnapshot> keepCashRegisters(Collection<UnitState> states, Map<UnitKey, UnitSnapshot> targets) {
		Map<UnitKey, UnitSnapshot> result = new LinkedHashMap<>(targets);
		result.keySet().removeIf(unit -> unit.type() == UnitType.CASH_REGISTER);
		for (UnitState state : states) {
			UnitSnapshot today = UnitSnapshot.of(state);
			if (state.unit().type() == UnitType.CASH_REGISTER && UnitMapper.isReadable(today)) {
				result.put(state.unit(), today);
			}
		}
		return result;
	}

	/**
	 * Passt das zusammengesetzte Ziel-Event an den heutigen Stand an:
	 * <ul>
	 * <li><b>Alte Felder:</b> Eine Vorstellung des Ziels, die heute lebt und lesbar ist, bekommt deren heutigen Wert. Eine,
	 * die das Wiederherstellen zurückbringt, bekommt sie geleert, wenn die Einheit ihrer Kasse heute lebt (auch gesperrt
	 * oder unlesbar), sonst den Wert ihres letzten nicht gelöschten Stands, nicht den des Ziels: Das Ziel kann eine Zählung
	 * zeigen, die vor dem Löschen schon zurückgenommen war.</li>
	 * <li><b>Vorstellungen, die das Wiederherstellen entfernt</b> (heute lebend und lesbar, im Ziel nicht enthalten): die
	 * Regel {@link CashRegisters#presentationRemoved} am heutigen Stand; die Kasse bleibt mit Betrag oder Kommentar verwaist
	 * stehen, sonst verlässt sie die Liste. Ist ihre Kassen-Einheit gesperrt, wartend oder unlesbar, geschieht nichts.</li>
	 * </ul>
	 *
	 * @param target das Ziel-Event mit den Kassen aus {@link #keepCashRegisters}
	 * @param states die frisch gelesenen Einheiten des Events
	 */
	static void adjust(Event target, Collection<UnitState> states) {
		Map<UnitKey, UnitState> byUnit = new HashMap<>();
		states.forEach(state -> byUnit.put(state.unit(), state));
		Set<String> inTarget = new HashSet<>();
		for (Presentation presentation : target.getPresentations()) {
			inTarget.add(presentation.getId());
			UnitState state = byUnit.get(UnitKey.presentation(target.getId(), presentation.getId()));
			if (state != null) {
				oldFieldsFromToday(target, presentation, state, byUnit);
			}
		}
		for (UnitState state : states) {
			UnitKey unit = state.unit();
			if (unit.type() != UnitType.PRESENTATION || inTarget.contains(unit.presentationId())) {
				continue;
			}
			Presentation today = UnitMapper.readUnit(UnitSnapshot.of(state), Presentation.class);
			if (today != null && !blocked(byUnit.get(UnitKey.cashRegister(target.getId(), today.getId())))) {
				CashRegisters.presentationRemoved(target, today);
			}
		}
	}

	/** Die Regel "Alte Felder" von {@link #adjust} für eine Vorstellung des Ziels. */
	private static void oldFieldsFromToday(Event target, Presentation presentation, UnitState state,
			Map<UnitKey, UnitState> byUnit) {
		UnitSnapshot today = UnitSnapshot.of(state);
		if (today.isLive()) {
			Presentation now = UnitMapper.readUnit(today, Presentation.class);
			if (now != null) {
				copyOldFields(now, presentation);
			}
			return;
		}
		UnitState cash = byUnit.get(UnitKey.cashRegister(target.getId(), presentation.getId()));
		if (cash != null && !UnitMapper.isAbsent(UnitSnapshot.of(cash))) {
			presentation.setActualCashAmount(0);
			presentation.setCashDifferenceComment(null);
			return;
		}
		Presentation last = lastLive(state);
		if (last != null) {
			copyOldFields(last, presentation);
		}
	}

	/**
	 * Der letzte nicht gelöschte Stand der Einheit als Vorstellung; die eigenen ausstehenden Versionen bauen auf dem Ende
	 * der Kette auf. {@code null}, wenn er sich nicht lesen lässt; dann bleiben die alten Felder des Ziels.
	 */
	private static Presentation lastLive(UnitState state) {
		List<Version> versions = new ArrayList<>(state.chain());
		versions.addAll(state.pending());
		for (int i = versions.size() - 1; i >= 0; i--) {
			if (!versions.get(i).deleted()) {
				return UnitMapper.readUnit(UnitSnapshot.of(state.unit(), versions.get(i)), Presentation.class);
			}
		}
		return null;
	}

	private static void copyOldFields(Presentation from, Presentation to) {
		to.setActualCashAmount(from.getActualCashAmount());
		to.setCashDifferenceComment(from.getCashDifferenceComment());
	}

	/**
	 * Ob die Kassen-Einheit heute lebt, sich aber nicht schreiben lässt: gesperrt ({@link UnitMapper#lockOf}), wartend
	 * oder unlesbar. Das Wiederherstellen schreibt eine solche Einheit nicht.
	 */
	private static boolean blocked(UnitState cash) {
		if (cash == null) {
			return false;
		}
		UnitSnapshot snapshot = UnitSnapshot.of(cash);
		return !UnitMapper.isAbsent(snapshot) && (UnitMapper.lockOf(snapshot) != null
				|| snapshot.status() == UnitState.Status.WAITING || !UnitMapper.isReadable(snapshot));
	}

	/**
	 * Die sichtbaren Kassen von {@code today}, deren wirksamer Ist-Betrag von dem in {@code then} abweicht, mit Anzeigename
	 * und Betrag von heute (auch 0). Zum Zeitpunkt gilt für eine Kasse ihr Objekt, sonst die alten Felder ihrer Vorstellung,
	 * sonst 0. Eine Änderung nur am Kommentar zählt nicht.
	 */
	static List<CountedCash> countedSince(Event today, Event then) {
		List<CountedCash> counted = new ArrayList<>();
		for (VisibleCashRegister line : CashRegisters.visible(today)) {
			int cents = line.cash().actualCashAmount();
			if (cents != amountAt(then, line.id())) {
				counted.add(new CountedCash(line.displayName(), cents));
			}
		}
		return counted;
	}

	private static int amountAt(Event then, String id) {
		CashRegister register = then.findCashRegister(id);
		if (register != null) {
			return register.getActualCashAmount();
		}
		Presentation presentation = then.findPresentation(id);
		return presentation != null ? presentation.getActualCashAmount() : 0;
	}
}
