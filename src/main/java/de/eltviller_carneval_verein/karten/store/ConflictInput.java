package de.eltviller_carneval_verein.karten.store;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Was eine {@link ConflictPolicy} über eine verlorene Strecke erfährt. Alle Inhalte sind Kopien; die Strategie darf sie
 * verändern.
 *
 * @param unit           die Einheit
 * @param base           die Basis: der erste Vorgänger der ältesten Version der Strecke, also der Stand, auf dem dieser
 *                       Rechner aufgebaut hat (ein Glied der Kette oder eine schon zusammengeführte eigene Version);
 *                       {@code null}, wenn die älteste Version keinen Vorgänger hat
 * @param mine           "Meine": die jüngste Version der verlorenen Strecke
 * @param theirs         "Deren": das Ende der gültigen Kette
 * @param chainSinceBase die Glieder der gültigen Kette seit der Basis, ohne sie, ältestes zuerst; endet mit
 *                       {@code theirs}. Steht die Basis nicht selbst in der Kette, beginnt sie mit dem ersten Glied, das
 *                       die Basis über seine Vorgänger enthält. Ohne Basis die ganze Kette
 * @param ownMachineId   die ID dieses Rechners
 * @param siblings       nur bei Buchungen: der Stand jeder anderen Buchungs-Einheit derselben Vorstellung aus dem
 *                       Start-Index; sonst leer
 */
public record ConflictInput(UnitKey unit, Version base, Version mine, Version theirs, List<Version> chainSinceBase,
		String ownMachineId, Map<UnitKey, Sibling> siblings) {

	/**
	 * Eine andere Buchungs-Einheit derselben Vorstellung.
	 *
	 * @param snapshot ihr aktueller Stand (der Inhalt als Kopie)
	 * @param head     das letzte Glied ihrer gültigen Kette; {@code null}, wenn sie keine hat
	 */
	public record Sibling(UnitSnapshot snapshot, ChainStep head) {

		/** Prüft den Stand. */
		public Sibling {
			Objects.requireNonNull(snapshot, "snapshot");
		}
	}

	/** Prüft die Pflichtangaben und macht Kette und Geschwister unveränderlich. */
	public ConflictInput {
		Objects.requireNonNull(unit, "unit");
		Objects.requireNonNull(mine, "mine");
		Objects.requireNonNull(theirs, "theirs");
		Objects.requireNonNull(ownMachineId, "ownMachineId");
		chainSinceBase = List.copyOf(chainSinceBase);
		siblings = siblings == null ? Map.of() : Map.copyOf(siblings);
	}
}
