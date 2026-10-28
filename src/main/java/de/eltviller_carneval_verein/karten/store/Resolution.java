package de.eltviller_carneval_verein.karten.store;

import java.util.List;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Das Ergebnis einer {@link ConflictPolicy}: die Fassung der Einheit, die als Zusammenführung mit den Vorgängern
 * {@code [Deren, Meine]} geschrieben wird, und was vorher geschrieben werden muss.
 *
 * @param payload der Inhalt der Zusammenführung; bei einer Löschung ohne Bedeutung
 * @param deleted ob die Zusammenführung die Einheit löscht
 * @param extra   weitere Änderungen, die vor der Zusammenführung geschrieben werden: neue Einheiten ({@code parents}
 *                leer) und Löschungen ({@code parents}: der aktuelle Stand). Hat die Einheit schon diesen Inhalt bzw.
 *                ist sie schon gelöscht, entfällt der Eintrag (Wiederholung nach einem Absturz)
 * @param note    der Konfliktvermerk der Zusammenführung; {@code null}: keiner
 */
public record Resolution(ObjectNode payload, boolean deleted, List<Change> extra, ConflictNote note) {

	/** Macht aus fehlenden weiteren Änderungen eine leere, unveränderliche Liste. */
	public Resolution {
		extra = extra == null ? List.of() : List.copyOf(extra);
	}
}
