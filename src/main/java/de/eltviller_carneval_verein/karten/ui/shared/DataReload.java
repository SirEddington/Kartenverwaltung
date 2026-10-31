package de.eltviller_carneval_verein.karten.ui.shared;

import java.util.ArrayList;
import java.util.List;

import de.eltviller_carneval_verein.karten.repository.StoreEventRepository;
import de.eltviller_carneval_verein.karten.repository.StoreHallRepository;

/**
 * Das Neuladen des Bestands nach einem Verbinden mit Knopf und nach jeder Entscheidung im Modus ASK, jeweils erst nach dem
 * Ende des ersten Laufs (oder nach "Trotzdem öffnen"): Hallen, dann Events (die Events beziehen sich auf die Hallen, wie
 * beim Start), dann die Merklisten leeren, die alte Objekte halten.
 * <p>
 * Die Vorbedingung prüft der Aufrufer: keine ungespeicherten Änderungen ({@code LeaveGuard}) und kein Popup in Bearbeitung;
 * ungespeicherte Änderungen an den bisherigen Objekten gingen sonst verloren. Das Kundenfenster schließt mit der
 * Verkaufsansicht.
 */
public final class DataReload {

	private final StoreHallRepository halls;
	private final StoreEventRepository events;
	private final Runnable clearHeldStates;
	private final Runnable conflictsChanged;

	/**
	 * @param clearHeldStates  leert die Merklisten, die alte Objekte halten (in der App {@code LiveUpdates::reset})
	 * @param conflictsChanged was nach dem Laden geschieht, damit die Konflikte neu geprüft werden (in der App wie am Ende des
	 *                         Starts)
	 */
	public DataReload(StoreHallRepository halls, StoreEventRepository events, Runnable clearHeldStates,
			Runnable conflictsChanged) {
		this.halls = halls;
		this.events = events;
		this.clearHeldStates = clearHeldStates;
		this.conflictsChanged = conflictsChanged;
	}

	/**
	 * Lädt Hallen und Events neu aus dem Versionsspeicher.
	 *
	 * @return die Ladewarnungen beider Repositories (Hallen zuerst) zum Anzeigen wie beim Start
	 */
	public List<String> reload() {
		halls.reloadHalls();
		// schon vor dem Laden der Events: Beim Laden bleiben Buchungen, die nicht zur Bestuhlung ihrer Vorstellung passen, draußen
		events.setHallLookup(halls::findById);
		events.reloadEvents();
		clearHeldStates.run();
		List<String> warnings = new ArrayList<>(halls.getAndClearLoadWarnings());
		warnings.addAll(events.getAndClearLoadWarnings());
		conflictsChanged.run();
		return warnings;
	}
}
