package de.eltviller_carneval_verein.karten.store;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Der aktuelle Stand einer Einheit in der Form, in der Repositorys und Start-Index ihn weitergeben: die Daten der
 * aktuellen Version samt Zustand der Kette.
 *
 * @param unit    die Einheit
 * @param version die aktuelle Version; {@code null}, wenn die Einheit keine gültige Version hat
 * @param status  Zustand der Kette (siehe {@link UnitState.Status})
 * @param deleted ob die aktuelle Version die Einheit als gelöscht kennzeichnet
 * @param payload die Daten der aktuellen Version; {@code null}, wenn es keine gibt, bei einer gelöschten Version leer
 */
public record UnitSnapshot(UnitKey unit, VersionId version, UnitState.Status status, boolean deleted,
		ObjectNode payload) {

	/** Der Stand aus dem ermittelten Zustand: {@link UnitState#current()} liefert Version und Daten. */
	public static UnitSnapshot of(UnitState state) {
		Version current = state.current();
		if (current == null) {
			return new UnitSnapshot(state.unit(), null, state.status(), false, null);
		}
		return new UnitSnapshot(state.unit(), current.id(), state.status(), current.deleted(), current.payload());
	}

	/** Der Stand einer gültigen Version der Einheit (Zustand {@code OK}). */
	public static UnitSnapshot of(UnitKey unit, Version version) {
		return new UnitSnapshot(unit, version.id(), UnitState.Status.OK, version.deleted(), version.payload());
	}
}
