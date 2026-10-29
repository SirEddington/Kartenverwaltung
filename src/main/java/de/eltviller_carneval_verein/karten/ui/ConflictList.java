package de.eltviller_carneval_verein.karten.ui;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.PaymentStatus;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.store.ConflictKey;
import de.eltviller_carneval_verein.karten.store.ConflictNote;
import de.eltviller_carneval_verein.karten.store.ConflictNote.FieldConflict;
import de.eltviller_carneval_verein.karten.store.OpenConflict;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.UnitType;
import de.eltviller_carneval_verein.karten.validation.EntityLabels;

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
	 * @param adopted ob das Modell diesen eigenen Wert schon hat ("übernommen"): Dann gibt es für die Gruppe keine Aktion
	 *                mehr. Wird bei jedem {@link ConflictList#rows()} aus dem Modell abgeleitet, nichts davon ist gespeichert.
	 */
	public record FieldRow(String group, String label, String mine, String valid, boolean adopted) {
	}

	/**
	 * Eine Zeile des Dialogs.
	 *
	 * @param conflict    der Konflikt
	 * @param kindText    die Art als Text
	 * @param entry       der Eintrag (Event, Vorstellung, Platz bzw. Kunde; bei einer Halle ihr Name)
	 * @param counterpart die Gegenseite: Rechner und Bediener, soweit bekannt
	 * @param at          der Zeitpunkt der Gegenseite (ihre Version); ohne bekannte Version der Zeitpunkt der Version, die den
	 *                    Konflikt trägt
	 * @param fields      die strittigen Feldgruppen (nur bei {@link ConflictNote.Kind#FIELDS})
	 * @param action      die Aktion der Zeile
	 * @param group       bei einem Doppelverkauf der verlorene Speichervorgang, dessen Zeilen zusammengehören
	 *                    ({@link ConflictNote#lostSave()}, bei älteren Vermerken der Speichervorgang der Version mit dem
	 *                    Vermerk); sonst {@code null}
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
	private final BiPredicate<OpenConflict, String> adopted;
	private final Function<String, Hall> findHall;

	/** Die Konflikte, die der Nutzer im Dialog gesehen hat. */
	private final Set<ConflictKey> seen = new HashSet<>();
	/** Die Konflikte, die {@link #rows()} zuletzt geliefert hat. */
	private Set<ConflictKey> delivered = Set.of();

	/**
	 * @param source   liefert die offenen Konflikte, neu bei jedem Aufruf (in der App {@code SyncService::openConflicts})
	 * @param labelOf  der Anzeigename einer Einheit: Event, Vorstellung, Platz bzw. Kunde; bei einer Halle der Hallenname
	 * @param adopted  sagt zu einem Konflikt und dem Namen einer seiner Feldgruppen, ob das Modell den eigenen Wert schon hat
	 *                 (in der App {@code ConflictActions::isAdopted}); {@link FieldRow#adopted()}
	 * @param findHall die Halle zur Kennung oder {@code null} (in der App {@code StoreHallRepository::findById}): Halle und
	 *                 Bestuhlung einer Feldgruppe erscheinen mit ihren Namen statt mit ihren Kennungen
	 */
	public ConflictList(Supplier<List<OpenConflict>> source, Function<UnitKey, String> labelOf,
			BiPredicate<OpenConflict, String> adopted, Function<String, Hall> findHall) {
		this.source = source;
		this.labelOf = labelOf;
		this.adopted = adopted;
		this.findHall = findHall;
	}

	/**
	 * Die Zeilen der offenen Konflikte in der Reihenfolge der Quelle; Doppelverkäufe desselben Speichervorgangs stehen
	 * hintereinander, wo der erste von ihnen stand. Merkt, welche Konflikte damit geliefert sind ({@link #markSeen()}).
	 */
	public List<Row> rows() {
		return rows(source.get());
	}

	/** Wie {@link #rows()}, für die schon abgeleiteten offenen Konflikte (in der App einmal je Rückmeldung). */
	public List<Row> rows(List<OpenConflict> open) {
		// je Zeile ohne Gruppe ein Platz, je Gruppe einer, wo ihre erste Zeile stand
		List<List<Row>> slots = new ArrayList<>();
		Map<String, List<Row>> groups = new HashMap<>();
		for (OpenConflict conflict : open) {
			Row row = row(conflict);
			if (row.group() == null) {
				slots.add(List.of(row));
				continue;
			}
			List<Row> members = groups.get(row.group());
			if (members == null) {
				members = new ArrayList<>();
				groups.put(row.group(), members);
				slots.add(members);
			}
			members.add(row);
		}
		List<Row> ordered = new ArrayList<>();
		slots.forEach(ordered::addAll);
		Set<ConflictKey> keys = new HashSet<>();
		ordered.forEach(row -> keys.add(row.conflict().key()));
		delivered = keys;
		return ordered;
	}

	/** Ob es einen offenen Konflikt gibt, den der Nutzer noch nie gesehen hat ({@link #markSeen()}). */
	public boolean hasUnseen() {
		return hasUnseen(source.get());
	}

	/** Wie {@link #hasUnseen()}, für die schon abgeleiteten offenen Konflikte. */
	public boolean hasUnseen(List<OpenConflict> open) {
		for (OpenConflict conflict : open) {
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

	/** Der Hinweis der Fußzeile: {@code 1 Konflikt offen} bzw. {@code <N> Konflikte offen}. */
	public static String indicatorText(int count) {
		return count == 1 ? "1 Konflikt offen" : count + " Konflikte offen";
	}

	/**
	 * Der Platz, den der Vermerk festhält (bei einem Doppelverkauf der bisherige der Buchung), wie ihn die App nennt:
	 * {@code Tisch A3, Sitz 2} bzw. ohne Reihenbuchstaben {@code Tisch 3, Sitz 2}.
	 *
	 * @return der Text; {@code null}, wenn der Vermerk keinen Platz mit Tischnummer hat
	 */
	public static String placeText(ConflictNote note) {
		ObjectNode place = note == null ? null : note.place();
		if (place == null || place.path("tableNumber").asInt(0) <= 0) {
			return null;
		}
		return "Tisch " + place.path("rowLabel").asText("") + place.path("tableNumber").asInt() + ", Sitz "
				+ place.path("seatNumber").asInt();
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
		Instant at = other == null ? conflict.at() : Instant.ofEpochMilli(other.version().epochMillis());
		List<FieldRow> fields = note.fields().stream()
				.map(field -> fieldRow(field, adopted.test(conflict, field.group()))).toList();
		String group = note.kind() != ConflictNote.Kind.DOUBLE_SALE ? null
				: note.lostSave() != null ? note.lostSave() : conflict.saveId();
		return new Row(conflict, kindText(note.kind()), entry, counterpart, at, fields,
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

	private FieldRow fieldRow(FieldConflict conflict, boolean adopted) {
		return new FieldRow(conflict.group(), groupLabel(conflict.group()), valueText(conflict.mine()),
				valueText(conflict.theirs()), adopted);
	}

	private static String groupLabel(String group) {
		return GROUP_LABELS.getOrDefault(group, group);
	}

	/**
	 * Die Werte einer Gruppe als Text: die gesetzten Werte, durch Komma getrennt; ohne Werte {@code (leer)}. Halle und
	 * Bestuhlung stehen mit ihren Namen da (unbekannt wie in den Tabellen als Strich), der Zahlstatus mit seinem Text aus
	 * den Ansichten; die Sitz-ID fehlt, weil Reihe, Tisch und Sitz daneben stehen.
	 */
	private String valueText(ObjectNode values) {
		List<String> parts = new ArrayList<>();
		values.fields().forEachRemaining(field -> {
			JsonNode value = field.getValue();
			if (value.isNull() || field.getKey().equals("seatId")) {
				return;
			}
			String text = switch (field.getKey()) {
			case "hallId" -> EntityLabels.hallDisplayName(findHall.apply(value.asText()));
			case "seatingPlanId" -> planName(values.path("hallId").asText(null), value.asText());
			case "paymentStatus" -> paymentText(value.asText());
			default -> value.isBoolean() ? (value.asBoolean() ? "ja" : "nein") : value.asText();
			};
			if (!text.isBlank()) {
				parts.add(text);
			}
		});
		return parts.isEmpty() ? "(leer)" : String.join(", ", parts);
	}

	/** Der Name der Bestuhlung in ihrer Halle; ein Strich, wenn es Halle oder Bestuhlung nicht (mehr) gibt. */
	private String planName(String hallId, String planId) {
		Hall hall = hallId == null ? null : findHall.apply(hallId);
		SeatingPlan plan = hall == null ? null : hall.findSeatingPlan(planId);
		return plan == null ? EntityLabels.hallDisplayName(null) : EntityLabels.nameOrPlaceholder(plan.getName());
	}

	/** Der Zahlstatus wie in den Ansichten ("Offen", "Bar", …); ein unbekannter Wert bleibt, wie er ist. */
	private static String paymentText(String name) {
		try {
			return PaymentStatus.valueOf(name).getDisplayName();
		} catch (IllegalArgumentException e) {
			return name;
		}
	}
}
