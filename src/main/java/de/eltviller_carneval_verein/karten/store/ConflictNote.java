package de.eltviller_carneval_verein.karten.store;

import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Der Konfliktvermerk in einer Versionsdatei: Er hält fest, was beim Zusammenführen zweier Stände geschehen ist, damit
 * der Dialog mit den offenen Konflikten es später anzeigen kann.
 *
 * @param kind     Art des Konflikts
 * @param affected Anzeigetext der betroffenen Sache, etwa die Buchung oder der Sitz
 * @param other    die Gegenseite, deren Stand mit dem eigenen zusammentraf; darf {@code null} sein
 * @param fields   die widersprüchlichen Feldgruppen; leer, wenn die Art keine hat (nie {@code null})
 * @param moved    die Einheit, auf die eine versetzte Buchung ausgewichen ist; darf {@code null} sein
 * @param place    der Platz, an den die Buchung versetzt wurde; darf {@code null} sein
 * @param dropped  die Version, deren Inhalt weicht: bei Zusammenführungen meist der zweite Vorgänger, bei
 *                 {@link Kind#DELETE_WINS} gegen die Gegenseite der erste; darf {@code null} sein
 */
public record ConflictNote(Kind kind, String affected, Party other, List<FieldConflict> fields, UnitKey moved,
		ObjectNode place, VersionId dropped) {

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
