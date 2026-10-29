package de.eltviller_carneval_verein.karten.ui;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.eltviller_carneval_verein.karten.store.ConflictKey;
import de.eltviller_carneval_verein.karten.store.ConflictNote;
import de.eltviller_carneval_verein.karten.store.ConflictNote.FieldConflict;
import de.eltviller_carneval_verein.karten.store.OpenConflict;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.UnitType;

/**
 * Das Modell des Sammel-Dialogs ohne JavaFX: macht aus den offenen Konflikten des Rechners ({@code SyncService.openConflicts})
 * die Zeilen der Anzeige, gruppiert Doppelverkäufe eines Speichervorgangs und merkt sich, welche Konflikte schon gezeigt
 * wurden (der Dialog öffnet sich nur bei neuen). Welche Aktion einer Zeile zusteht, folgt aus der Art des Vermerks; ob sie
 * gerade möglich ist, entscheidet {@link ConflictActions}. Die Texte sind vorläufig (Überarbeitung in KARV-87). Nicht
 * threadsicher: alle Aufrufe im JavaFX-Thread.
 */
public final class ConflictList {

	/** Die Aktion, die eine Zeile anbietet. */
	public enum Action {
		/** Doppelverkauf: der Buchung "ohne Platz" einen Platz zuweisen. */
		ASSIGN_PLACE,
		/** Feldkonflikt: den eigenen Wert einer Feldgruppe übernehmen. */
		TAKE_MY_VALUE,
		/** Hallenkonflikt: die eigene Fassung der Halle übernehmen. */
		TAKE_MY_HALL,
		/** Gelöschte Buchung: wiederherstellen. */
		RESTORE_BOOKING,
		/** Keine Aktion; die Zeile lässt sich nur bestätigen ("Erledigt"). */
		NONE
	}

	/**
	 * Eine strittige Feldgruppe einer Zeile.
	 *
	 * @param group der Name der Gruppe (wie im Vermerk; für {@link ConflictActions#takeMyValue})
	 * @param label der Name der Gruppe für die Anzeige
	 * @param mine  der eigene Wert als Text ("dein Wert")
	 * @param valid der gültige Wert, der des Gewinners, als Text
	 */
	public record FieldRow(String group, String label, String mine, String valid) {
	}

	/**
	 * Eine Zeile des Dialogs.
	 *
	 * @param conflict    der Konflikt
	 * @param kindText    die Art als Text
	 * @param entry       der Eintrag (Event, Vorstellung, Platz bzw. Kunde; bei einer Halle ihr Name)
	 * @param counterpart die Gegenseite: Rechner und Bediener, soweit bekannt
	 * @param at          der Zeitpunkt, an dem die Version gespeichert wurde, die den Konflikt trägt
	 * @param fields      die strittigen Feldgruppen (nur bei {@link ConflictNote.Kind#FIELDS})
	 * @param action      die Aktion der Zeile
	 * @param group       bei einem Doppelverkauf der Speichervorgang, dessen Zeilen zusammengehören; sonst {@code null}
	 */
	public record Row(OpenConflict conflict, String kindText, String entry, String counterpart, Instant at,
			List<FieldRow> fields, Action action, String group) {
	}

	private static final String UNKNOWN_MACHINE = "ein anderer Rechner";

	/** Die Feldgruppen des Vermerks auf Deutsch; eine unbekannte Gruppe heißt wie ihr Feld. */
	private static final Map<String, String> GROUP_LABELS = Map.ofEntries(Map.entry("customer", "Kunde"),
			Map.entry("place", "Platz"), Map.entry("payment", "Zahlung"), Map.entry("collection", "Abholung"),
			Map.entry("price", "Preis"), Map.entry("reservation", "Reservierung"), Map.entry("comment", "Kommentar"),
			Map.entry("id", "Kennung"), Map.entry("hall", "Halle"), Map.entry("schedule", "Termin"),
			Map.entry("cash", "Kasse"), Map.entry("name", "Name"), Map.entry("description", "Beschreibung"),
			Map.entry("archived", "Archiviert"));

	private final Supplier<List<OpenConflict>> source;
	private final Function<UnitKey, String> labelOf;

	/** Die Konflikte, die der Nutzer im Dialog gesehen hat. */
	private final Set<ConflictKey> seen = new HashSet<>();
	/** Die Konflikte, die {@link #rows()} zuletzt geliefert hat. */
	private Set<ConflictKey> delivered = Set.of();

	/**
	 * @param source liefert die offenen Konflikte, neu bei jedem Aufruf (in der App {@code SyncService::openConflicts})
	 * @param labelOf der Anzeigename einer Einheit: Event, Vorstellung, Platz bzw. Kunde; bei einer Halle der Hallenname
	 */
	public ConflictList(Supplier<List<OpenConflict>> source, Function<UnitKey, String> labelOf) {
		this.source = source;
		this.labelOf = labelOf;
	}

	/**
	 * Die Zeilen der offenen Konflikte in der Reihenfolge der Quelle; Doppelverkäufe desselben Speichervorgangs stehen
	 * hintereinander, wo der erste von ihnen stand. Merkt, welche Konflikte damit geliefert sind ({@link #markSeen()}).
	 */
	public List<Row> rows() {
		List<Row> all = source.get().stream().map(this::row).toList();
		List<Row> ordered = new ArrayList<>();
		Set<String> placed = new HashSet<>();
		for (Row row : all) {
			if (row.group() == null) {
				ordered.add(row);
			} else if (placed.add(row.group())) {
				all.stream().filter(member -> row.group().equals(member.group())).forEach(ordered::add);
			}
		}
		Set<ConflictKey> keys = new HashSet<>();
		ordered.forEach(row -> keys.add(row.conflict().key()));
		delivered = keys;
		return ordered;
	}

	/** Ob es einen offenen Konflikt gibt, den der Nutzer noch nie gesehen hat ({@link #markSeen()}). */
	public boolean hasUnseen() {
		for (OpenConflict conflict : source.get()) {
			if (!seen.contains(conflict.key())) {
				return true;
			}
		}
		return false;
	}

	/** Der Dialog hat gezeigt, was {@link #rows()} zuletzt geliefert hat: Diese Konflikte gelten nicht mehr als neu. */
	public void markSeen() {
		seen.addAll(delivered);
	}

	private Row row(OpenConflict conflict) {
		ConflictNote note = conflict.note();
		String entry = labelOf.apply(conflict.unit());
		if (entry == null) {
			entry = conflict.unit().relativePath();
		}
		if (note == null) {
			OpenConflict.ParentDeletion deletion = conflict.deletion();
			String kind = deletion.parent().type() == UnitType.EVENT ? "Event gelöscht" : "Vorstellung gelöscht";
			return new Row(conflict, kind, entry, counterpart(deletion.machineName(), deletion.operator()), conflict.at(),
					List.of(), Action.NONE, null);
		}
		ConflictNote.Party other = note.other();
		String counterpart = other == null ? UNKNOWN_MACHINE : counterpart(other.machineName(), other.operator());
		List<FieldRow> fields = note.fields().stream().map(ConflictList::fieldRow).toList();
		String group = note.kind() == ConflictNote.Kind.DOUBLE_SALE ? conflict.saveId() : null;
		return new Row(conflict, kindText(note.kind()), entry, counterpart, conflict.at(), fields,
				actionOf(note.kind(), conflict.unit()), group);
	}

	private static String kindText(ConflictNote.Kind kind) {
		return switch (kind) {
		case DOUBLE_SALE -> "Doppelverkauf";
		case FIELDS -> "Gleichzeitig geändert";
		case DELETE_WINS -> "Gelöscht";
		case UNMERGED -> "Nicht zusammengeführt";
		};
	}

	/**
	 * Die Aktion zur Art des Vermerks: Doppelverkauf weist einen Platz zu; strittige Felder übernimmt man je Gruppe, bei
	 * der Halle als ganze Fassung; eine gelöschte Buchung lässt sich wiederherstellen. Gelöschte Vorstellungen, Events und
	 * Hallen stellt das Wiederherstellen aus dem Verlauf wieder her; bei Nicht-Zusammengeführtem gibt es nichts zu tun.
	 */
	private static Action actionOf(ConflictNote.Kind kind, UnitKey unit) {
		return switch (kind) {
		case DOUBLE_SALE -> Action.ASSIGN_PLACE;
		case FIELDS -> unit.type() == UnitType.HALL ? Action.TAKE_MY_HALL : Action.TAKE_MY_VALUE;
		case DELETE_WINS -> unit.type() == UnitType.BOOKING ? Action.RESTORE_BOOKING : Action.NONE;
		case UNMERGED -> Action.NONE;
		};
	}

	/** {@code Rechner <Name>} und, wenn bekannt, {@code (<Bediener>)}; ohne Namen {@code ein anderer Rechner}. */
	private static String counterpart(String machineName, String operator) {
		String text = blank(machineName) ? UNKNOWN_MACHINE : "Rechner " + machineName.trim();
		return blank(operator) ? text : text + " (" + operator.trim() + ")";
	}

	private static boolean blank(String text) {
		return text == null || text.isBlank();
	}

	private static FieldRow fieldRow(FieldConflict conflict) {
		return new FieldRow(conflict.group(), groupLabel(conflict.group()), valueText(conflict.mine()),
				valueText(conflict.theirs()));
	}

	private static String groupLabel(String group) {
		return GROUP_LABELS.getOrDefault(group, group);
	}

	/** Die Werte einer Gruppe als Text: die gesetzten Werte, durch Komma getrennt; ohne Werte {@code (leer)}. */
	private static String valueText(ObjectNode values) {
		List<String> parts = new ArrayList<>();
		values.fields().forEachRemaining(field -> {
			JsonNode value = field.getValue();
			String text = value.isBoolean() ? (value.asBoolean() ? "ja" : "nein") : value.asText();
			if (!value.isNull() && !text.isBlank()) {
				parts.add(text);
			}
		});
		return parts.isEmpty() ? "(leer)" : String.join(", ", parts);
	}
}
