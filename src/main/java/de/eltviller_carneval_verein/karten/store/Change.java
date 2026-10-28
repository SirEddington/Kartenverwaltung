package de.eltviller_carneval_verein.karten.store;

import java.util.List;
import java.util.Map;
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
 * @param note    der Konfliktvermerk, der in die Version übernommen wird; {@code null} bei einer gewöhnlichen Änderung
 * @param seen    die gelesenen Listen (Rechner-ID auf Nummer), die {@link SyncService#append} bei der Löschung eines
 *                Event-Kopfs oder einer Vorstellung unverändert übernimmt, etwa die der Löschung, die eine Zusammenführung
 *                übernimmt; {@code null}: die Positionen dieses Rechners zum Zeitpunkt des Schreibens. Bei jeder anderen
 *                Änderung ohne Wirkung
 */
public record Change(UnitKey unit, List<VersionId> parents, ObjectNode payload, boolean deleted, ConflictNote note,
		Map<String, Long> seen) {

	/** Prüft Einheit und Vorgänger und macht die Liste unveränderlich. */
	public Change {
		Objects.requireNonNull(unit, "unit");
		parents = List.copyOf(parents);
		seen = seen == null ? null : Map.copyOf(seen);
	}

	/** Eine gewöhnliche Änderung ohne Konfliktvermerk. */
	public Change(UnitKey unit, List<VersionId> parents, ObjectNode payload, boolean deleted) {
		this(unit, parents, payload, deleted, null, null);
	}

	/** Eine Änderung mit Konfliktvermerk; bei einer Löschung gelten die Positionen dieses Rechners als {@code seen}. */
	public Change(UnitKey unit, List<VersionId> parents, ObjectNode payload, boolean deleted, ConflictNote note) {
		this(unit, parents, payload, deleted, note, null);
	}
}
