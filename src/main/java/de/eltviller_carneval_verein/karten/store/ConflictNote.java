package de.eltviller_carneval_verein.karten.store;

import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Der Konfliktvermerk in einer Versionsdatei: Er hält fest, was beim Zusammenführen zweier Stände geschehen ist, damit
 * der Dialog mit den offenen Konflikten es später anzeigen kann.
 *
 * @param kind     Art des Konflikts
 * @param affected ID des betroffenen Rechners (danach filtert der Dialog)
 * @param other    die Gegenseite, deren Stand mit dem eigenen zusammentraf; darf {@code null} sein
 * @param fields   die widersprüchlichen Feldgruppen; leer, wenn die Art keine hat (nie {@code null})
 * @param moved    die Einheit, auf die eine versetzte Buchung ausgewichen ist; darf {@code null} sein
 * @param place    der Platz, an den die Buchung versetzt wurde; darf {@code null} sein
 * @param dropped  die Version, deren Inhalt weicht: bei Zusammenführungen meist der zweite Vorgänger, bei
 *                 {@link Kind#DELETE_WINS} gegen die Gegenseite der erste; darf {@code null} sein
 * @param lostSave der Speichervorgang der weichenden eigenen Version ("Meine", die neueste Version der verlorenen
 *                 Strecke): Konflikte desselben verlorenen Speichervorgangs gehören im Dialog zusammen. Die Auflösung
 *                 setzt ihn bei jedem Vermerk, den sie schreibt; darf {@code null} sein (ältere Vermerke), dann gilt der
 *                 Speichervorgang der Version, die den Vermerk trägt
 */
public record ConflictNote(Kind kind, String affected, Party other, List<FieldConflict> fields, UnitKey moved,
		ObjectNode place, VersionId dropped, String lostSave) {

	/** Ein Vermerk ohne {@code lostSave}; den setzt die Auflösung beim Schreiben ({@link #withLostSave}). */
	public ConflictNote(Kind kind, String affected, Party other, List<FieldConflict> fields, UnitKey moved,
			ObjectNode place, VersionId dropped) {
		this(kind, affected, other, fields, moved, place, dropped, null);
	}

	/** Ein Vermerk {@link Kind#FIELDS} mit den strittigen Gruppen; {@code moved} darf {@code null} sein. */
	public static ConflictNote ofFields(String affected, Party other, List<FieldConflict> fields, UnitKey moved,
			VersionId dropped) {
		return new ConflictNote(Kind.FIELDS, affected, other, fields, moved, null, dropped);
	}

	/** Ein Vermerk {@link Kind#DELETE_WINS}. */
	public static ConflictNote deleteWins(String affected, Party other, VersionId dropped) {
		return new ConflictNote(Kind.DELETE_WINS, affected, other, List.of(), null, null, dropped);
	}

	/** Ein Vermerk {@link Kind#UNMERGED}. */
	public static ConflictNote unmerged(String affected, Party other, VersionId dropped) {
		return new ConflictNote(Kind.UNMERGED, affected, other, List.of(), null, null, dropped);
	}

	/** Ein Vermerk {@link Kind#DOUBLE_SALE}: die Buchung ist auf {@code moved} ausgewichen, {@code place} war ihr Platz. */
	public static ConflictNote doubleSale(String affected, Party other, UnitKey moved, ObjectNode place,
			VersionId dropped) {
		return new ConflictNote(Kind.DOUBLE_SALE, affected, other, List.of(), moved, place, dropped);
	}

	/** Derselbe Vermerk mit dem Speichervorgang der weichenden eigenen Version. */
	public ConflictNote withLostSave(String lostSave) {
		return new ConflictNote(kind, affected, other, fields, moved, place, dropped, lostSave);
	}

	/** Die Art des Konflikts. */
	public enum Kind {
		/** Derselbe Platz wurde zweimal verkauft. */
		DOUBLE_SALE,
		/** Dieselben Felder wurden auf beiden Seiten verschieden geändert. */
		FIELDS,
		/** Die eine Seite löschte, die andere änderte; die Löschung setzte sich durch. */
		DELETE_WINS,
		/** Eine Zusammenführung war nicht möglich. */
		UNMERGED
	}

	/**
	 * Die Gegenseite eines Konflikts.
	 *
	 * @param machine     ID des Rechners
	 * @param machineName Anzeigename des Rechners; darf {@code null} sein
	 * @param operator    Name des Bedieners; darf {@code null} sein
	 * @param version     die Version der Gegenseite
	 */
	public record Party(String machine, String machineName, String operator, VersionId version) {

		/** Prüft Rechner und Version. */
		public Party {
			Objects.requireNonNull(machine, "machine");
			Objects.requireNonNull(version, "version");
		}

		/** Die Gegenseite nach den Angaben der Version. */
		public static Party of(Version version) {
			return new Party(version.machine(), version.machineName(), version.operator(), version.id());
		}
	}

	/**
	 * Eine Feldgruppe, in der beide Seiten verschieden geändert haben.
	 *
	 * @param group  Name der Feldgruppe
	 * @param mine   der eigene Stand der Gruppe
	 * @param theirs der fremde Stand der Gruppe
	 */
	public record FieldConflict(String group, ObjectNode mine, ObjectNode theirs) {

		/** Prüft Name und beide Stände. */
		public FieldConflict {
			Objects.requireNonNull(group, "group");
			Objects.requireNonNull(mine, "mine");
			Objects.requireNonNull(theirs, "theirs");
		}
	}

	/** Prüft Art und Betroffenes und macht aus fehlenden Feldern eine leere, unveränderliche Liste. */
	public ConflictNote {
		Objects.requireNonNull(kind, "kind");
		Objects.requireNonNull(affected, "affected");
		fields = fields == null ? List.of() : List.copyOf(fields);
	}
}
