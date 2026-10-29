package de.eltviller_carneval_verein.karten.store;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
 * @param mineSaveIds    die Speichervorgänge aller Versionen der verlorenen Strecke, nicht nur der von "Meine": Hat dieser
 *                       Rechner nach einem Umzug weiter gespeichert, stammt "Meine" aus einem späteren Speichervorgang als
 *                       der Umzug (Ruling R35)
 */
public record ConflictInput(UnitKey unit, Version base, Version mine, Version theirs, List<Version> chainSinceBase,
		String ownMachineId, Map<UnitKey, Sibling> siblings, Set<String> mineSaveIds) {

	/**
	 * Das Feld der Buchungs-ID im Inhalt einer Buchung, wie das Repository es schreibt; dieselbe ID benennt eine Buchung
	 * ohne Platz ({@link UnitKey#unassignedBooking}). Die einzige Stelle, an der der Speicher in den Inhalt einer Buchung
	 * schaut: für die Auswahl der Geschwister, die ihre Speichervorgänge mitbekommen.
	 */
	public static final String BOOKING_ID = "id";

	/**
	 * Eine andere Buchungs-Einheit derselben Vorstellung.
	 *
	 * @param snapshot ihr aktueller Stand (der Inhalt als Kopie)
	 * @param head     das letzte Glied ihrer gültigen Kette; {@code null}, wenn sie keine hat
	 * @param saveIds  die Speichervorgänge aller lesbaren Versionen in ihrem Ordner (Kette, aufgegangene, eigene
	 *                 ausstehende und verlorene); gefüllt nur, wenn ihr Stand die Buchung der Basis oder die von "Meine"
	 *                 trägt (gleiche Buchungs-ID, nicht gelöscht), und für die Einheit {@code _<ID>} der Buchung von
	 *                 "Meine" (auch gelöscht), sonst leer. Daran erkennt die Strategie, ob ein Speichervorgang die Buchung
	 *                 dorthin verschoben hat, auch wenn die Kopie seitdem geändert wurde oder noch aussteht, und ob "Meine"
	 *                 aus "ohne Platz" zugewiesen wurde
	 * @param lostMove  das Ziel eines Umzugs, der hier nicht gilt (Ruling R31): die jüngste lesbare Version in ihrem Ordner
	 *                  aus einem Speichervorgang der verlorenen Strecke ({@link ConflictInput#mineSaveIds()}), die die
	 *                  Buchung der Basis trägt und nicht in ihrer gültigen Kette steht, während ihr Stand die Buchung nicht
	 *                  trägt (die Version hat verloren oder ist in einer Zusammenführung aufgegangen). Gefüllt nur, wenn
	 *                  "Meine" eine Löschung ist, und nur für Einheiten, die laut Index eigene Versionen außerhalb ihrer
	 *                  Kette haben; sonst {@code null}. Eine Kopie, die die Strategie verändern darf
	 * @param deletions nur für die Einheit {@code _<ID>} der Buchung von "Meine": die Löschungen in ihrem Ordner, die nicht
	 *                  schon vor den Versionen der verlorenen Strecke dort lagen (keine Vorgänger davon), ohne
	 *                  Zusammenführungen (deren Löschung stammt immer aus einem Vorgänger). Daran erkennt die Strategie, ob
	 *                  die Löschung dort allein dieses Rechners Zuweisen war oder ob ein anderer Rechner die Buchung dort
	 *                  ebenfalls gelöscht hat (Ruling R35); sonst leer
	 */
	public record Sibling(UnitSnapshot snapshot, ChainStep head, Set<String> saveIds, Version lostMove,
			List<Deletion> deletions) {

		/** Prüft den Stand und macht Speichervorgänge und Löschungen unveränderlich. */
		public Sibling {
			Objects.requireNonNull(snapshot, "snapshot");
			saveIds = saveIds == null ? Set.of() : Set.copyOf(saveIds);
			deletions = deletions == null ? List.of() : List.copyOf(deletions);
		}

		/** Eine Geschwister-Einheit ohne Löschungen. */
		public Sibling(UnitSnapshot snapshot, ChainStep head, Set<String> saveIds, Version lostMove) {
			this(snapshot, head, saveIds, lostMove, List.of());
		}

		/** Eine Geschwister-Einheit ohne verlorenes Umzugsziel und ohne Löschungen. */
		public Sibling(UnitSnapshot snapshot, ChainStep head, Set<String> saveIds) {
			this(snapshot, head, saveIds, null, List.of());
		}
	}

	/**
	 * Eine Löschung im Ordner einer Geschwister-Einheit (siehe {@link Sibling#deletions()}).
	 *
	 * @param saveId   der Speichervorgang der Löschung
	 * @param standing wo sie steht
	 */
	public record Deletion(String saveId, Standing standing) {

		/** Prüft die Angaben. */
		public Deletion {
			Objects.requireNonNull(standing, "standing");
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

	/** Prüft die Pflichtangaben und macht Kette, Geschwister und Speichervorgänge unveränderlich. */
	public ConflictInput {
		Objects.requireNonNull(unit, "unit");
		Objects.requireNonNull(mine, "mine");
		Objects.requireNonNull(theirs, "theirs");
		Objects.requireNonNull(ownMachineId, "ownMachineId");
		chainSinceBase = List.copyOf(chainSinceBase);
		siblings = siblings == null ? Map.of() : Map.copyOf(siblings);
		if (mineSaveIds == null) {
			mineSaveIds = mine.saveId() == null ? Set.of() : Set.of(mine.saveId());
		} else {
			mineSaveIds = Set.copyOf(mineSaveIds);
		}
	}

	/** Eine Eingabe, deren Strecke nur aus Speichervorgängen von "Meine" besteht (eine Version oder ein Speichervorgang). */
	public ConflictInput(UnitKey unit, Version base, Version mine, Version theirs, List<Version> chainSinceBase,
			String ownMachineId, Map<UnitKey, Sibling> siblings) {
		this(unit, base, mine, theirs, chainSinceBase, ownMachineId, siblings, null);
	}
}
