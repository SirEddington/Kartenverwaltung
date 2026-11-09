package de.eltviller_carneval_verein.karten.store;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** Testhilfe: Stände ohne das Merkmal „inhaltlich zu neu“, für Tests, die es nicht betrifft. Nur in Tests. */
public final class Snapshots {

	private Snapshots() {
	}

	/** Ein Stand mit {@code contentLocked = false}. */
	public static UnitSnapshot snapshot(UnitKey unit, VersionId version, UnitState.Status status, boolean deleted,
			ObjectNode payload) {
		return new UnitSnapshot(unit, version, status, deleted, payload, false);
	}
}
