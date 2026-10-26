package de.eltviller_carneval_verein.karten.store;

import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Eine Änderung für {@link SyncService#append}: daraus entsteht eine neue Version der Einheit.
 *
 * @param unit    die Einheit
 * @param parents die Vorgänger; leer für die erste Version, sonst zuerst der gültige Vorgänger, auf dem die Änderung
 *                aufbaut
 * @param payload der neue Inhalt; bei einer Löschung ohne Bedeutung (darf {@code null} sein)
 * @param deleted ob die Einheit mit dieser Änderung gelöscht wird
 */
public record Change(UnitKey unit, List<VersionId> parents, ObjectNode payload, boolean deleted) {

	/** Prüft Einheit und Vorgänger und macht die Liste unveränderlich. */
	public Change {
		Objects.requireNonNull(unit, "unit");
		parents = List.copyOf(parents);
	}
}
