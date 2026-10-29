package de.eltviller_carneval_verein.karten.ui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.BookingSync;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.model.Seat;
import de.eltviller_carneval_verein.karten.model.SeatingPlan;
import de.eltviller_carneval_verein.karten.repository.LockedUnitException;
import de.eltviller_carneval_verein.karten.repository.StoreEventRepository;
import de.eltviller_carneval_verein.karten.repository.StoreHallRepository;
import de.eltviller_carneval_verein.karten.store.ConflictInput;
import de.eltviller_carneval_verein.karten.store.ConflictNote;
import de.eltviller_carneval_verein.karten.store.ConflictNote.FieldConflict;
import de.eltviller_carneval_verein.karten.store.OpenConflict;
import de.eltviller_carneval_verein.karten.store.StoreMessages;
import de.eltviller_carneval_verein.karten.store.SyncService;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.UnitSnapshot;
import de.eltviller_carneval_verein.karten.store.UnitType;
import de.eltviller_carneval_verein.karten.store.Version;
import de.eltviller_carneval_verein.karten.store.VersionId;
import de.eltviller_carneval_verein.karten.tracking.ChangeTracker;
import de.eltviller_carneval_verein.karten.ui.ConflictList.Action;
import de.eltviller_carneval_verein.karten.ui.ConflictList.Row;
import de.eltviller_carneval_verein.karten.ui.event.PlanSwitchDialog;
import de.eltviller_carneval_verein.karten.ui.hall.PlanImpact;
import de.eltviller_carneval_verein.karten.ui.sales.AssignPlaceDialog;
import de.eltviller_carneval_verein.karten.ui.sales.SalesPlan;
import de.eltviller_carneval_verein.karten.validation.EntityLabels;

/**
 * Die Aktionen der Zeilen des Sammel-Dialogs ohne JavaFX: ob eine Aktion gerade möglich ist ({@link #availability}) und
 * was sie tut. Jede Aktion ändert das Modell des Verlierers, speichert sofort und bestätigt dann die Zeile
 * ({@link SyncService#acknowledge}); scheitert sie, wird das Modell zurückgesetzt, nichts bestätigt, und der Grund steht in
 * der {@link IllegalStateException} für die Zeile.
 * <p>
 * Eine Aktion arbeitet immer auf dem eingearbeiteten Stand: Hat das Event bzw. die Halle ungespeicherte Änderungen oder wird
 * darin gerade etwas bearbeitet, ist sie gesperrt (sonst speicherte sie fremde Eingaben mit; zurückgestellte fremde Stände
 * gibt es nur in diesen Fällen). Fehlt der Gegenstand inzwischen (die Buchung ist platziert, gelöscht oder lebt anderswo,
 * das Event ist weg), bleibt nur das Bestätigen ohne Aktion. Die Texte sind vorläufig (Überarbeitung in KARV-87). Nicht
 * threadsicher: alle Aufrufe im JavaFX-Thread.
 */
public final class ConflictActions {

	/**
	 * Ob eine Aktion der Zeile möglich ist.
	 *
	 * @param enabled ob die Aktion angeboten wird
	 * @param reason  warum nicht, wenn nicht; sonst leer
	 */
	public record Availability(boolean enabled, String reason) {
	}

	/** Der Hinweis, solange das Event bzw. die Halle ungespeichert ist oder bearbeitet wird. */
	static final String BLOCKED = "Erst speichern oder verwerfen";
	/** Der Hinweis, wenn es nichts mehr zu tun gibt. */
	static final String NOT_NEEDED = "Nicht mehr nötig";

	private static final Availability ENABLED = new Availability(true, "");
	private static final Availability IS_BLOCKED = new Availability(false, BLOCKED);
	private static final Availability IS_NOT_NEEDED = new Availability(false, NOT_NEEDED);
	/** Eine Zeile ohne Aktion bietet nichts an und braucht keinen Grund. */
	private static final Availability NO_ACTION = new Availability(false, "");

	/** Die Feldgruppe einer Vorstellung mit Halle und Bestuhlung ({@code FieldGroups}). */
	private static final String HALL_GROUP = "hall";

	private record VersionRef(UnitKey unit, VersionId id) {
	}

	/** Wie das Wiederherstellen vorgeht: Inhalt der gelöschten Buchung und der freie Sitz, oder {@code null} für "ohne Platz". */
	private record Restore(Event event, Presentation presentation, Booking content, Booking placeholder) {
	}

	private final StoreEventRepository events;
	private final StoreHallRepository halls;
	private final ChangeTracker<Event> eventTracker;
	private final ChangeTracker<Hall> hallTracker;
	private final EditingUnits editing;
	private final SyncService sync;
	private final Predicate<Event> eventSaver;
	private final Predicate<Hall> hallSaver;

	/** Die Inhalte der weichenden Versionen: Eine Version ändert sich nie, die Datei muss nur einmal gelesen werden. */
	private final Map<VersionRef, Version> dropped = new HashMap<>();

	/**
	 * @param saveEvent speichert das Event wie die Speichern-Schaltfläche und meldet, ob es gespeichert wurde (in der App
	 *                  {@code EventSaver::save}); darf auch eine Ausnahme werfen
	 * @param saveHall  wie {@code saveEvent} für die Halle ({@code HallSaver::save})
	 */
	public ConflictActions(StoreEventRepository events, StoreHallRepository halls, ChangeTracker<Event> eventTracker,
			ChangeTracker<Hall> hallTracker, EditingUnits editing, SyncService sync, Predicate<Event> saveEvent,
			Predicate<Hall> saveHall) {
		this.events = events;
		this.halls = halls;
		this.eventTracker = eventTracker;
		this.hallTracker = hallTracker;
		this.editing = editing;
		this.sync = sync;
		this.eventSaver = saveEvent;
		this.hallSaver = saveHall;
	}

	/**
	 * Ob die Aktion der Zeile gerade möglich ist: nicht bei einer Zeile ohne Aktion, nicht solange das Event bzw. die Halle
	 * ungespeichert ist oder bearbeitet wird ({@code "Erst speichern oder verwerfen"}), nicht ohne Gegenstand
	 * ({@code "Nicht mehr nötig"}).
	 */
	public Availability availability(Row row) {
		if (row.action() == Action.NONE) {
			return NO_ACTION;
		}
		UnitKey unit = row.conflict().unit();
		if (unit.type() == UnitType.HALL) {
			Hall hall = halls.findById(unit.hallId());
			if (hall != null && (hallTracker.isDirty(hall) || editing.isEditing(hall))) {
				return IS_BLOCKED;
			}
		} else {
			Event event = events.findEvent(unit.eventId());
			if (event != null && inUse(event)) {
				return IS_BLOCKED;
			}
		}
		boolean subject = switch (row.action()) {
		case ASSIGN_PLACE -> bookingToAssign(row) != null;
		case TAKE_MY_VALUE -> valueTarget(row) != null && !allAdopted(row.conflict());
		case TAKE_MY_HALL -> hallToReplace(row) != null;
		case RESTORE_BOOKING -> restoreOf(row) != null;
		case NONE -> false;
		};
		return subject ? ENABLED : IS_NOT_NEEDED;
	}

	/**
	 * Die Buchung "ohne Platz" eines Doppelverkaufs, wie das Modell sie hat (die Einheit aus dem Vermerk), damit der Nutzer
	 * ihr einen Platz zuweist. Der bisherige Platz steht im Vermerk ({@link ConflictNote#place()}).
	 *
	 * @return die Buchung; {@code null}, wenn die Zeile keinen Doppelverkauf hat oder die Buchung nicht mehr ohne Platz im
	 *         Modell steht (platziert, gelöscht, Event weg)
	 */
	public Booking bookingToAssign(Row row) {
		ConflictNote note = row.conflict().note();
		if (row.action() != Action.ASSIGN_PLACE || note == null || note.moved() == null) {
			return null;
		}
		Object model = events.modelObject(note.moved());
		return model instanceof Booking booking && booking.isUnassigned() && booking.hasData() ? booking : null;
	}

	/**
	 * Die Bestuhlung der Vorstellung in ihrer Halle (für {@link #assignPlace}); {@code null} ohne Halle, ohne Bestuhlung und
	 * bei einer eingefrorenen Vorstellung ({@link SalesPlan#of}).
	 */
	public SeatingPlan planOf(Presentation presentation) {
		Hall hall = presentation.getHallId() == null ? null : halls.findById(presentation.getHallId());
		return SalesPlan.of(presentation, hall);
	}

	/**
	 * Weist die Buchung "ohne Platz" eines Doppelverkaufs dem gewählten freien Sitz zu ({@link BookingSync#assign}), speichert
	 * das Event und bestätigt die Zeile.
	 *
	 * @param seat ein Sitz der Bestuhlung aus {@link #planOf}
	 * @throws IllegalStateException wenn die Aktion gesperrt ist, der Sitz inzwischen belegt ist ({@code "Platz zuweisen nicht
	 *                               möglich: <Platz> ist bereits belegt."}) oder Speichern oder Bestätigen scheitert; nichts ist
	 *                               bestätigt. Das Modell ist wieder wie vorher, außer die Zuweisung wurde schon geschrieben:
	 *                               Dann bleibt es, und der Text beginnt mit "Gespeichert, aber nicht bestätigt".
	 */
	public void assignPlace(Row row, Seat seat) {
		require(row, Action.ASSIGN_PLACE);
		Booking booking = bookingToAssign(row);
		UnitKey moved = row.conflict().note().moved();
		Event event = events.findEvent(moved.eventId());
		Presentation presentation = event.findPresentation(moved.presentationId());
		SeatingPlan plan = planOf(presentation);
		if (plan == null) {
			throw new IllegalStateException(
					"Platz zuweisen nicht möglich: " + EntityLabels.presentation(presentation) + " hat keine Bestuhlung.");
		}
		List<Booking> bookingsBefore = new ArrayList<>(presentation.getBookings());
		Booking before = booking.copy();
		UnitKey seatUnit = UnitKey.booking(event.getId(), presentation.getId(), seat.getId());
		VersionId versionBefore = versionOf(seatUnit);
		if (!BookingSync.assign(presentation, plan, booking, seat)) {
			throw new IllegalStateException(
					"Platz zuweisen nicht möglich: " + AssignPlaceDialog.label(seat) + " ist bereits belegt.");
		}
		saveOrUndo(seatUnit, versionBefore, this::writtenDespite, () -> save(event), e -> {
			// die Buchung wieder ohne Platz, und der leere Platzhalter, den die Zuweisung entfernt hat, wieder an seiner Stelle
			booking.copyFrom(before);
			presentation.getBookings().clear();
			presentation.getBookings().addAll(bookingsBefore);
		});
		acknowledge(row);
	}

	/**
	 * Bestätigt die Zeile ohne Aktion ("Erledigt"): Sie verschwindet und kommt nach einem Neustart nicht wieder. Das Modell
	 * bleibt unberührt.
	 *
	 * @throws IllegalStateException wenn sich der Konflikt nicht bestätigen lässt; er bleibt dann offen
	 */
	public void dismiss(Row row) {
		acknowledge(row, "Der Konflikt ließ sich nicht bestätigen: ");
	}

	/**
	 * Was die Aktion der Zeile für Buchungen bedeutet, als Text für die Rückfrage, die der Nutzer bestätigen muss, bevor er sie
	 * wählt (dieselben Hinweise wie die Editoren): bei einer anderen Halle oder Bestuhlung einer Vorstellung ({@code group}
	 * {@code "hall"}) die Folgen für ihre Buchungen ({@link PlanSwitchDialog#impactMessage}), bei der eigenen Fassung einer
	 * Halle die Buchungen auf Sitzen, die die Fassung nicht mehr hat ({@link PlanImpact}).
	 *
	 * @param group die Feldgruppe bei {@link Action#TAKE_MY_VALUE}; sonst unbeachtet
	 * @return der Text; {@code null}, wenn die Aktion keine Buchung betrifft
	 */
	public String bookingImpact(Row row, String group) {
		if (row.action() == Action.TAKE_MY_HALL) {
			return hallImpact(row);
		}
		if (row.action() != Action.TAKE_MY_VALUE || !HALL_GROUP.equals(group) || row.conflict().note() == null) {
			return null;
		}
		UnitKey unit = row.conflict().target();
		FieldConflict field = row.conflict().note().fields().stream().filter(candidate -> candidate.group().equals(group))
				.findFirst().orElse(null);
		if (unit.type() != UnitType.PRESENTATION || field == null || !(events.modelObject(unit) instanceof Presentation presentation)) {
			return null;
		}
		String hallId = text(field.mine(), "hallId", presentation.getHallId());
		String planId = text(field.mine(), "seatingPlanId", presentation.getSeatingPlanId());
		Hall hall = hallId == null ? null : halls.findById(hallId);
		return PlanSwitchDialog.impactMessage(presentation, hall == null ? null : hall.findSeatingPlan(planId));
	}

	/** Der Text des Felds; ohne das Feld {@code fallback}, bei {@code null} im Feld {@code null}. */
	private static String text(ObjectNode values, String field, String fallback) {
		JsonNode value = values.get(field);
		if (value == null) {
			return fallback;
		}
		return value.isNull() ? null : value.asText();
	}

	/** Die Buchungen auf Sitzen, die die eigene Fassung der Halle nicht mehr hat, samt Hinweis; {@code null} ohne solche. */
	private String hallImpact(Row row) {
		Hall current = hallToReplace(row);
		Version version = dropped(row.conflict());
		Hall mine = current == null || version == null ? null : halls.readHall(version.payload());
		if (mine == null || current.getSeatingPlans() == null) {
			return null;
		}
		List<Event> loaded = events.loadEvents();
		List<PlanImpact> impacts = new ArrayList<>();
		for (SeatingPlan plan : current.getSeatingPlans()) {
			SeatingPlan kept = mine.findSeatingPlan(plan.getId());
			Set<String> keptIds = new HashSet<>();
			if (kept != null) {
				kept.getSeats().forEach(seat -> keptIds.add(seat.getId()));
			}
			Set<String> removed = new HashSet<>();
			plan.getSeats().forEach(seat -> removed.add(seat.getId()));
			removed.removeAll(keptIds);
			impacts.add(PlanImpact.of(plan.getId(), removed, loaded));
		}
		PlanImpact all = PlanImpact.combine(impacts);
		return all.isEmpty() ? null : "Die Halle wird durch deine Fassung ersetzt." + all.deleteNote();
	}

	/**
	 * Übernimmt den eigenen Wert einer Feldgruppe: Das Modell bekommt die Felder der Gruppe aus dem Vermerk
	 * ({@link FieldConflict#mine()}), dann wird das Event gespeichert. Die Änderung gilt für die Einheit, auf die der Vermerk
	 * sie übertragen hat ({@link ConflictNote#moved()}), sonst für die der Zeile. Hat der Vermerk nur eine Gruppe, ist die
	 * Zeile danach bestätigt; bei mehreren erst, wenn jede Gruppe übernommen ist ({@link #isAdopted}), sonst bleibt sie offen.
	 *
	 * @throws IllegalStateException wenn die Aktion gesperrt ist, die Gruppe nicht zur Zeile gehört oder Ändern, Speichern
	 *                               oder Bestätigen scheitert; nichts ist bestätigt. Das Modell ist wieder wie vorher, außer
	 *                               der Wert wurde schon geschrieben: Dann bleibt es, und der Text beginnt mit "Gespeichert,
	 *                               aber nicht bestätigt".
	 */
	public void takeMyValue(Row row, String group) {
		require(row, Action.TAKE_MY_VALUE);
		FieldConflict conflict = row.conflict().note().fields().stream().filter(field -> field.group().equals(group))
				.findFirst().orElseThrow(() -> new IllegalStateException(
						"Die Gruppe \"" + group + "\" gehört nicht zu diesem Konflikt."));
		if (isAdopted(row.conflict(), group)) {
			throw new IllegalStateException("Dieser Wert ist schon übernommen.");
		}
		UnitKey unit = row.conflict().target();
		Event event = events.findEvent(unit.eventId());
		ObjectNode before = events.payloadOf(unit);
		VersionId versionBefore = versionOf(unit);
		saveOrUndo(unit, versionBefore, this::writtenDespite, () -> {
			events.applyGroup(unit, group, conflict.mine());
			save(event);
		}, e -> {
			try {
				events.applyFields(unit, before);
			} catch (RuntimeException undoFailed) {
				e.addSuppressed(undoFailed);
			}
		});
		// mehrere Gruppen: Die Zeile bleibt offen, bis jede übernommen ist (oder der Nutzer sie mit "Erledigt" schließt)
		if (row.conflict().note().fields().size() == 1 || allAdopted(row.conflict())) {
			acknowledge(row);
		}
	}

	/**
	 * Ob das Modell den eigenen Wert der Feldgruppe schon hat ("übernommen"): Jedes Feld des eigenen Werts aus dem Vermerk
	 * steht so im Modell der Einheit, auf der die Aktion wirkt. Abgeleitet aus dem Modell, nichts davon ist gespeichert.
	 * Nur für Feldkonflikte von Event, Vorstellung und Buchung; sonst und bei einer Gruppe ohne Felder {@code false}.
	 */
	public boolean isAdopted(OpenConflict conflict, String group) {
		ConflictNote note = conflict.note();
		if (note == null || note.kind() != ConflictNote.Kind.FIELDS || conflict.unit().type() == UnitType.HALL) {
			return false;
		}
		FieldConflict field = note.fields().stream().filter(candidate -> candidate.group().equals(group)).findFirst()
				.orElse(null);
		if (field == null || field.mine().isEmpty()) {
			return false;
		}
		ObjectNode payload;
		try {
			payload = events.payloadOf(conflict.target());
		} catch (IllegalStateException e) {
			return false;
		}
		if (payload == null) {
			return false;
		}
		Iterator<Map.Entry<String, JsonNode>> values = field.mine().fields();
		while (values.hasNext()) {
			Map.Entry<String, JsonNode> entry = values.next();
			if (!sameValue(payload.get(entry.getKey()), entry.getValue())) {
				return false;
			}
		}
		return true;
	}

	/** Gleiche Werte; ein fehlendes Feld gilt als {@code null}, Zahlen zählen nach ihrem Wert. */
	private static boolean sameValue(JsonNode now, JsonNode wanted) {
		JsonNode left = now == null ? NullNode.getInstance() : now;
		if (left.equals(wanted)) {
			return true;
		}
		return left.isNumber() && wanted.isNumber() && left.decimalValue().compareTo(wanted.decimalValue()) == 0;
	}

	/** Ob jede Gruppe des Vermerks schon übernommen ist. */
	private boolean allAdopted(OpenConflict conflict) {
		return !conflict.note().fields().isEmpty()
				&& conflict.note().fields().stream().allMatch(field -> isAdopted(conflict, field.group()));
	}

	/**
	 * Schreibt die eigene Fassung der Halle wieder: Der Inhalt der weichenden Version ({@link ConflictNote#dropped()}) wird
	 * die Halle im Modell, dann wird sie gespeichert.
	 *
	 * @throws IllegalStateException wie bei {@link #takeMyValue}
	 */
	public void takeMyHall(Row row) {
		require(row, Action.TAKE_MY_HALL);
		Hall current = hallToReplace(row);
		Hall mine = halls.readHall(dropped(row.conflict()).payload());
		UnitKey unit = row.conflict().unit();
		VersionId versionBefore = versionOf(unit);
		halls.replaceCachedHall(mine);
		// geschrieben, dann gescheitert: Die Halle im Cache ist die geschriebene; die alte zurückzusetzen ließe das nächste
		// Speichern die fremde Fassung über die eigene schreiben (daher genügt hier eine andere Version)
		saveOrUndo(unit, versionBefore, this::changed, () -> {
			if (!hallSaver.test(mine)) {
				throw new IllegalStateException("Die Halle wurde nicht gespeichert.");
			}
		}, e -> halls.replaceCachedHall(current));
		acknowledge(row);
	}

	/**
	 * Stellt die gelöschte Buchung wieder her: auf ihrem Sitz, wenn der frei ist, sonst "ohne Platz" (unter ihrer Kennung,
	 * wie beim Doppelverkauf), dann wird das Event gespeichert. Angeboten wird es nur, wenn die Kennung der Buchung in keiner
	 * Einheit der Vorstellung lebt und in keiner offenen verlorenen Strecke steht: Es gibt nie zwei lebende Einheiten
	 * derselben Buchung.
	 *
	 * @throws IllegalStateException wie bei {@link #takeMyValue}
	 */
	public void restoreBooking(Row row) {
		require(row, Action.RESTORE_BOOKING);
		Restore restore = restoreOf(row);
		Booking content = restore.content();
		Booking placeholder = restore.placeholder();
		Booking before = placeholder == null ? null : placeholder.copy();
		// die angelegte Buchung "ohne Platz", damit das Zurücksetzen sie wieder entfernt
		Booking[] added = new Booking[1];
		UnitKey unit = placeholder != null ? row.conflict().unit()
				: UnitKey.unassignedBooking(restore.event().getId(), restore.presentation().getId(), content.getId());
		VersionId versionBefore = versionOf(unit);
		saveOrUndo(unit, versionBefore, this::writtenDespite, () -> {
			if (placeholder != null) {
				// der Sitz bleibt, wie das Modell ihn kennt; nur die Buchung kommt zurück
				placeholder.copyFrom(content);
				placeholder.setSeatId(before.getSeatId());
				placeholder.setRowLabel(before.getRowLabel());
				placeholder.setTableNumber(before.getTableNumber());
				placeholder.setSeatNumber(before.getSeatNumber());
				placeholder.setCategory(before.getCategory());
				placeholder.setWheelchairAccessible(before.isWheelchairAccessible());
				placeholder.setUnassigned(false);
			} else {
				added[0] = restore.presentation().addBooking();
				added[0].copyFrom(content);
				added[0].setUnassigned(true);
				added[0].setSeatId(null);
				added[0].setRowLabel("");
				added[0].setTableNumber(0);
				added[0].setSeatNumber(0);
			}
			save(restore.event());
		}, e -> {
			if (placeholder != null) {
				placeholder.copyFrom(before);
			}
			if (added[0] != null) {
				restore.presentation().getBookings().remove(added[0]);
			}
		});
		acknowledge(row);
	}

	// Gegenstand der Aktionen -->

	/**
	 * Das Objekt im Modell, das die eigenen Felder bekommt; {@code null}, wenn es sie nicht mehr gibt. Eine Buchung zählt nur,
	 * wenn sie noch Daten trägt und dieselbe Buchung ist wie die des Vermerks (auf dem Sitz kann inzwischen ein anderer
	 * Kunde sitzen, dem man den Namen nicht überschreiben darf).
	 */
	private Object valueTarget(Row row) {
		if (row.conflict().note() == null) {
			return null;
		}
		Object model = events.modelObject(row.conflict().target());
		if (model instanceof Booking booking) {
			Version version = dropped(row.conflict());
			return booking.hasData() && version != null && !version.deleted()
					&& booking.getId().equals(ConflictInput.bookingIdOf(version.payload())) ? booking : null;
		}
		return model;
	}

	/** Die Halle im Modell, die die eigene Fassung ersetzt; {@code null}, wenn es sie nicht gibt oder sie schon so ist. */
	private Hall hallToReplace(Row row) {
		UnitKey unit = row.conflict().unit();
		Version version = dropped(row.conflict());
		Hall hall = halls.findById(unit.hallId());
		if (hall == null || version == null || version.deleted() || version.payload() == null) {
			return null;
		}
		UnitSnapshot stored = sync.snapshots(List.of(unit)).get(unit);
		if (stored != null && version.payload().equals(stored.payload()) && !stored.deleted()) {
			return null;
		}
		return halls.readHall(version.payload()) == null ? null : hall;
	}

	/** Das Vorgehen beim Wiederherstellen; {@code null}, wenn es nichts wiederherzustellen gibt oder die Buchung noch lebt. */
	private Restore restoreOf(Row row) {
		ConflictNote note = row.conflict().note();
		UnitKey unit = row.conflict().unit();
		Event event = note == null ? null : events.findEvent(unit.eventId());
		Presentation presentation = event == null ? null : event.findPresentation(unit.presentationId());
		Version version = presentation == null ? null : dropped(row.conflict());
		if (version == null || version.deleted() || version.payload() == null) {
			return null;
		}
		Booking content = events.readBooking(version.payload());
		if (content == null || content.getId() == null || !content.hasData()) {
			return null;
		}
		String bookingId = content.getId();
		if (events.bookingLives(event.getId(), presentation.getId(), bookingId)
				|| inOpenLostStretch(unit, bookingId)) {
			return null;
		}
		Booking placeholder = null;
		if (!unit.isUnassigned() && events.modelObject(unit) instanceof Booking seat
				&& !seat.hasData() && !storedAt(unit)) {
			placeholder = seat;
		}
		return new Restore(event, presentation, content, placeholder);
	}

	/** Ob der Index auf der Einheit einen lebenden Stand hat (der Sitz ist dann vergeben, auch wenn das Modell ihn noch nicht kennt). */
	private boolean storedAt(UnitKey unit) {
		UnitSnapshot stored = sync.snapshots(List.of(unit)).get(unit);
		return stored != null && stored.isLive();
	}

	/**
	 * Ob die Buchung in einer verlorenen eigenen Strecke einer Einheit der Vorstellung steht, die noch nicht zusammengeführt
	 * ist: Ihre Auflösung könnte sie in eine Einheit schreiben. Lässt sich das nicht prüfen, gilt sie als vorhanden.
	 */
	private boolean inOpenLostStretch(UnitKey unit, String bookingId) {
		List<UnitKey> siblings = new ArrayList<>();
		for (UnitKey other : sync.eventSnapshots(unit.eventId()).keySet()) {
			if (other.type() == UnitType.BOOKING && unit.presentationId().equals(other.presentationId())) {
				siblings.add(other);
			}
		}
		try {
			for (UnitKey lostUnit : sync.lostUnits(siblings)) {
				for (List<VersionId> stretch : sync.unitState(lostUnit).lost()) {
					for (VersionId id : stretch) {
						Version version = sync.version(lostUnit, id);
						if (version != null && !version.deleted()
								&& bookingId.equals(ConflictInput.bookingIdOf(version.payload()))) {
							return true;
						}
					}
				}
			}
			return false;
		} catch (IOException e) {
			return true;
		}
	}

	/**
	 * Die Version, deren Inhalt der Vermerk weichen ließ, aus dem lokalen Bestand der Einheit der Zeile (bei einer Übertragung
	 * auch der Einheit, auf die sie ging); {@code null}, wenn der Vermerk keine nennt oder sie lokal nicht lesbar ist.
	 */
	private Version dropped(OpenConflict conflict) {
		ConflictNote note = conflict.note();
		if (note == null || note.dropped() == null) {
			return null;
		}
		List<UnitKey> candidates = note.moved() == null ? List.of(conflict.unit()) : List.of(conflict.unit(), note.moved());
		for (UnitKey unit : candidates) {
			VersionRef ref = new VersionRef(unit, note.dropped());
			Version known = dropped.get(ref);
			if (known != null) {
				return known;
			}
			try {
				Version read = sync.version(unit, note.dropped());
				if (read != null) {
					dropped.put(ref, read);
					return read;
				}
			} catch (IOException e) {
				// nicht lesbar heißt hier: kein Gegenstand; der nächste Aufruf versucht es erneut
			}
		}
		return null;
	}

	/** Die aktuelle Version der Einheit nach der Kette im lokalen Bestand; {@code null} ohne Version oder wenn sie sich nicht lesen lässt. */
	private VersionId versionOf(UnitKey unit) {
		try {
			Version current = sync.unitState(unit).current();
			return current == null ? null : current.id();
		} catch (IOException e) {
			return null;
		}
	}

	/** Ob die Einheit seit {@code before} eine andere aktuelle Version hat; lässt sie sich nicht lesen, gilt sie als geändert. */
	private boolean changed(UnitKey unit, VersionId before) {
		try {
			Version current = sync.unitState(unit).current();
			return !Objects.equals(current == null ? null : current.id(), before);
		} catch (IOException e) {
			return true;
		}
	}

	/**
	 * Ob nach einem gescheiterten Speichern des Events der Stand der Einheit doch geschrieben ist: Sie hat eine andere
	 * Version als vorher und gilt im Vergleichsstand nicht mehr als lokal verändert (das Repository gleicht den Vergleichsstand
	 * nach einem Fehler an die Platte an). Dann darf das Modell nicht zurückgesetzt werden, sonst schriebe das nächste Speichern
	 * den fremden Wert über den gewählten.
	 */
	private boolean writtenDespite(UnitKey unit, VersionId before) {
		return changed(unit, before) && !events.locallyChangedUnits().contains(unit);
	}

	/**
	 * Das Gerüst jeder Aktion: {@code change} ändert das Modell und speichert. Scheitert das, ist der Stand der Einheit laut
	 * {@code written} aber doch geschrieben, bleibt das Modell, und der Text beginnt mit "Gespeichert, aber nicht
	 * bestätigt". Sonst setzt {@code undo} das Modell zurück (es bekommt den Fehler, etwa um einen Fehler beim Zurücksetzen
	 * anzuhängen), und der Grund kommt als {@link IllegalStateException}. Bestätigt wird hier nichts.
	 *
	 * @param versionBefore die aktuelle Version der Einheit vor der Aktion ({@link #versionOf})
	 * @param written       ob die Einheit seitdem als geschrieben gilt: beim Event {@link #writtenDespite}, bei der Halle
	 *                      {@link #changed}
	 */
	private static void saveOrUndo(UnitKey unit, VersionId versionBefore, BiPredicate<UnitKey, VersionId> written,
			Runnable change, Consumer<RuntimeException> undo) {
		try {
			change.run();
		} catch (RuntimeException e) {
			if (written.test(unit, versionBefore)) {
				throw writtenButNotAcknowledged(e);
			}
			undo.accept(e);
			throw failure(e);
		}
	}

	/** Die Aktion ist geschrieben, aber danach gescheitert: Das Modell bleibt, die Zeile bleibt unbestätigt offen. */
	private static IllegalStateException writtenButNotAcknowledged(RuntimeException cause) {
		return new IllegalStateException("Gespeichert, aber nicht bestätigt: " + failure(cause).getMessage(), cause);
	}

	// <-- Gegenstand der Aktionen

	/** Ob das Event ungespeicherte Änderungen hat oder darin etwas bearbeitet wird. */
	private boolean inUse(Event event) {
		if (eventTracker.isDirty(event) || editing.isEditing(event)) {
			return true;
		}
		if (event.getPresentations() == null) {
			return false;
		}
		for (Presentation presentation : event.getPresentations()) {
			if (editing.isEditing(presentation)) {
				return true;
			}
			if (presentation.getBookings() != null) {
				for (Booking booking : presentation.getBookings()) {
					if (editing.isEditing(booking)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private void require(Row row, Action action) {
		if (row.action() != action) {
			throw new IllegalStateException("Diese Zeile bietet diese Aktion nicht an.");
		}
		Availability availability = availability(row);
		if (!availability.enabled()) {
			throw new IllegalStateException(availability.reason());
		}
	}

	private void save(Event event) {
		if (!eventSaver.test(event)) {
			throw new IllegalStateException("Das Event wurde nicht gespeichert.");
		}
	}

	private void acknowledge(Row row) {
		acknowledge(row, "Gespeichert, aber der Konflikt ließ sich nicht bestätigen: ");
	}

	/** Bestätigt den Konflikt der Zeile; scheitert das, steht der Grund hinter {@code prefix}. */
	private void acknowledge(Row row, String prefix) {
		try {
			sync.acknowledge(Set.of(row.conflict().key()));
		} catch (IOException e) {
			throw new IllegalStateException(prefix + StoreMessages.describe(e), e);
		}
	}

	/** Der Grund für die Zeile: die Meldung einer gesperrten oder veralteten Einheit, sonst die Ursache. */
	private static IllegalStateException failure(RuntimeException cause) {
		boolean forUser = cause instanceof LockedUnitException || cause instanceof IllegalStateException;
		String text = forUser && cause.getMessage() != null ? cause.getMessage() : StoreMessages.describe(cause);
		return new IllegalStateException(text, cause);
	}
}
