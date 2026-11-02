package de.eltviller_carneval_verein.karten.store;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

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
 * @param stretch        die Versionen der verlorenen Strecke, ältestes zuerst, endet mit {@code mine}: Die Angaben eines
 *                       Umzugs stehen nicht immer an "Meine" (KARV-93)
 * @param trail          nur bei Buchungen: die Einheiten auf der Umzugsspur ({@link TrailCollector}), mit der Einheit
 *                       selbst; sonst leer
 */
public record ConflictInput(UnitKey unit, Version base, Version mine, Version theirs, List<Version> chainSinceBase,
		String ownMachineId, Map<UnitKey, Sibling> siblings, List<Version> stretch, Map<UnitKey, TrailUnit> trail) {

	/**
	 * Das Feld der Buchungs-ID im Inhalt einer Buchung, wie das Repository es schreibt; dieselbe ID benennt eine Buchung
	 * ohne Platz ({@link UnitKey#unassignedBooking}). Der Speicher schaut nur hierfür in den Inhalt einer Buchung: für die
	 * Umzugsspur ({@link TrailCollector}).
	 */
	public static final String BOOKING_ID = "id";

	/**
	 * Die Buchungs-ID im Inhalt einer Buchung ({@link #BOOKING_ID}); {@code null} ohne Inhalt, ohne ID oder wenn sie kein
	 * Text ist.
	 */
	public static String bookingIdOf(ObjectNode payload) {
		JsonNode id = payload == null ? null : payload.get(BOOKING_ID);
		return id != null && id.isTextual() ? id.asText() : null;
	}

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

	/** Wo eine Version im Ordner ihrer Einheit steht. */
	public enum Standing {
		/** In der gültigen Kette. */
		CHAIN,
		/** Außerhalb der Kette, aber in einer Zusammenführung aufgegangen (vom aktuellen Stand aus erreichbar). */
		ABSORBED,
		/** Daneben: verloren oder ausstehend, noch nicht zusammengeführt. */
		BESIDE
	}

	/** Prüft die Pflichtangaben und macht Kette, Geschwister, Strecke und Spur unveränderlich. */
	public ConflictInput {
		Objects.requireNonNull(unit, "unit");
		Objects.requireNonNull(mine, "mine");
		Objects.requireNonNull(theirs, "theirs");
		Objects.requireNonNull(ownMachineId, "ownMachineId");
		chainSinceBase = List.copyOf(chainSinceBase);
		siblings = siblings == null ? Map.of() : Map.copyOf(siblings);
		stretch = stretch == null ? List.of(mine) : List.copyOf(stretch);
		trail = trail == null ? Map.of() : Map.copyOf(trail);
	}

	/** Eine Eingabe ohne Strecke (nur "Meine") und ohne Umzugsspur. */
	public ConflictInput(UnitKey unit, Version base, Version mine, Version theirs, List<Version> chainSinceBase,
			String ownMachineId, Map<UnitKey, Sibling> siblings) {
		this(unit, base, mine, theirs, chainSinceBase, ownMachineId, siblings, null, null);
	}
}
