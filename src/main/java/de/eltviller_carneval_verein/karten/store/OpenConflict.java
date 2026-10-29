package de.eltviller_carneval_verein.karten.store;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Ein offener Konflikt dieses Rechners, abgeleitet aus dem Start-Index ({@link ConflictLedger}): entweder ein
 * Konfliktvermerk, der diesen Rechner betrifft, oder eine Elternlöschung über eigenen, dem Löschenden unbekannten
 * Verkäufen. Genau eins von {@code note} und {@code deletion} ist gesetzt.
 *
 * @param id       die Version, die den Konflikt trägt: die Version mit dem Vermerk bzw. die Löschversion; mit der Art
 *                 ({@link #key()}) wird der Konflikt bestätigt ({@link SyncService#acknowledge})
 * @param unit     die Einheit dieser Version
 * @param at       der Zeitpunkt des Speicherns dieser Version
 * @param saveId   der Speichervorgang dieser Version; im Dialog gehören Konflikte desselben verlorenen Speichervorgangs
 *                 zusammen ({@link ConflictNote#lostSave()}), dieser gilt nur bei Vermerken ohne ihn
 * @param note     der Konfliktvermerk; {@code null} bei einer Elternlöschung
 * @param deletion die Elternlöschung; {@code null} bei einem Vermerk
 */
public record OpenConflict(VersionId id, UnitKey unit, Instant at, String saveId, ConflictNote note,
		ParentDeletion deletion) {

	/**
	 * Eine fremde Löschung eines Event-Kopfs oder einer Vorstellung über eigenen Verkäufen, die der Löschende nicht kannte.
	 *
	 * @param parent      das gelöschte Event bzw. die gelöschte Vorstellung
	 * @param machineName der Anzeigename des Rechners, der gelöscht hat; darf {@code null} sein
	 * @param operator    der Bediener, der gelöscht hat; darf {@code null} sein
	 * @param affected    die eigenen Einheiten darunter (Buchungen, bei einem Event auch Vorstellungen), die er nicht kannte,
	 *                    nach Pfad sortiert (nie {@code null})
	 */
	public record ParentDeletion(UnitKey parent, String machineName, String operator, List<UnitKey> affected) {

		/** Prüft die gelöschte Einheit und macht die Liste unveränderlich. */
		public ParentDeletion {
			Objects.requireNonNull(parent, "parent");
			affected = List.copyOf(affected);
		}
	}

	/** Der Schlüssel, unter dem der Konflikt bestätigt wird ({@link SyncService#acknowledge}): Version und Art. */
	public ConflictKey key() {
		return new ConflictKey(id, note != null ? ConflictKey.Kind.NOTE : ConflictKey.Kind.PARENT_DELETION);
	}

	/** Prüft, dass genau eins von Vermerk und Elternlöschung gesetzt ist. */
	public OpenConflict {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(unit, "unit");
		Objects.requireNonNull(at, "at");
		if ((note == null) == (deletion == null)) {
			throw new IllegalArgumentException("Genau eins von Vermerk und Elternlöschung muss gesetzt sein.");
		}
	}
}
