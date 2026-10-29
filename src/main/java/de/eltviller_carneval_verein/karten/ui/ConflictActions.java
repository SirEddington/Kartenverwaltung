package de.eltviller_carneval_verein.karten.ui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.node.ObjectNode;

import de.eltviller_carneval_verein.karten.model.Booking;
import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.model.Presentation;
import de.eltviller_carneval_verein.karten.repository.LockedUnitException;
import de.eltviller_carneval_verein.karten.repository.StoreEventRepository;
import de.eltviller_carneval_verein.karten.repository.StoreHallRepository;
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

	/** Der Ordnername einer Buchung ohne Platz beginnt mit diesem Zeichen (siehe {@link UnitKey}). */
	private static final String UNASSIGNED_PREFIX = "_";

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
		case TAKE_MY_VALUE -> valueTarget(row) != null;
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
	 * Übernimmt den eigenen Wert einer Feldgruppe: Das Modell bekommt die Felder der Gruppe aus dem Vermerk
	 * ({@link FieldConflict#mine()}), dann wird das Event gespeichert. Die Änderung gilt für die Einheit, auf die der Vermerk
	 * sie übertragen hat ({@link ConflictNote#moved()}), sonst für die der Zeile.
	 *
	 * @throws IllegalStateException wenn die Aktion gesperrt ist, die Gruppe nicht zur Zeile gehört oder Ändern, Speichern
	 *                               oder Bestätigen scheitert; das Modell ist dann wieder wie vorher, nichts bestätigt
	 */
	public void takeMyValue(Row row, String group) {
		require(row, Action.TAKE_MY_VALUE);
		FieldConflict conflict = row.conflict().note().fields().stream().filter(field -> field.group().equals(group))
				.findFirst().orElseThrow(() -> new IllegalStateException(
						"Die Gruppe \"" + group + "\" gehört nicht zu diesem Konflikt."));
		UnitKey unit = valueUnit(row);
		Event event = events.findEvent(unit.eventId());
		ObjectNode before = events.payloadOf(unit);
		try {
			events.applyGroup(unit, group, conflict.mine());
			save(event);
		} catch (RuntimeException e) {
			try {
				events.applyFields(unit, before);
			} catch (RuntimeException undoFailed) {
				e.addSuppressed(undoFailed);
			}
			throw failure(e);
		}
		acknowledge(row);
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
		halls.replaceCachedHall(mine);
		try {
			if (!hallSaver.test(mine)) {
				throw new IllegalStateException("Die Halle wurde nicht gespeichert.");
			}
		} catch (RuntimeException e) {
			halls.replaceCachedHall(current);
			throw failure(e);
		}
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
		Booking added = null;
		try {
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
				added = restore.presentation().addBooking();
				added.copyFrom(content);
				added.setUnassigned(true);
				added.setSeatId(null);
				added.setRowLabel("");
				added.setTableNumber(0);
				added.setSeatNumber(0);
			}
			save(restore.event());
		} catch (RuntimeException e) {
			if (placeholder != null) {
				placeholder.copyFrom(before);
			}
			if (added != null) {
				restore.presentation().getBookings().remove(added);
			}
			throw failure(e);
		}
		acknowledge(row);
	}

	// Gegenstand der Aktionen -->

	/** Die Einheit, auf der die eigenen Felder gelten: die, auf die der Vermerk sie übertragen hat, sonst die der Zeile. */
	private static UnitKey valueUnit(Row row) {
		ConflictNote note = row.conflict().note();
		return note.moved() != null ? note.moved() : row.conflict().unit();
	}

	/**
	 * Das Objekt im Modell, das die eigenen Felder bekommt; {@code null}, wenn es sie nicht mehr gibt. Eine Buchung zählt nur,
	 * wenn sie noch Daten trägt und dieselbe Buchung ist wie die des Vermerks (auf dem Sitz kann inzwischen ein anderer
	 * Kunde sitzen, dem man den Namen nicht überschreiben darf).
	 */
	private Object valueTarget(Row row) {
		if (row.conflict().note() == null) {
			return null;
		}
		Object model = events.modelObject(valueUnit(row));
		if (model instanceof Booking booking) {
			Version version = dropped(row.conflict());
			return booking.hasData() && version != null && !version.deleted()
					&& booking.getId().equals(bookingId(version.payload())) ? booking : null;
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
		if (!unit.bookingDir().startsWith(UNASSIGNED_PREFIX) && events.modelObject(unit) instanceof Booking seat
				&& !seat.hasData() && !storedAt(unit)) {
			placeholder = seat;
		}
		return new Restore(event, presentation, content, placeholder);
	}

	/** Ob der Index auf der Einheit einen lebenden Stand hat (der Sitz ist dann vergeben, auch wenn das Modell ihn noch nicht kennt). */
	private boolean storedAt(UnitKey unit) {
		UnitSnapshot stored = sync.snapshots(List.of(unit)).get(unit);
		return stored != null && stored.version() != null && !stored.deleted();
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
						if (version != null && !version.deleted() && bookingId.equals(bookingId(version.payload()))) {
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

	private static String bookingId(ObjectNode payload) {
		return payload == null ? null : payload.path("id").asText(null);
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
		try {
			sync.acknowledge(Set.of(row.conflict().key()));
		} catch (IOException e) {
			throw new IllegalStateException("Gespeichert, aber der Konflikt ließ sich nicht bestätigen: "
					+ StoreMessages.describe(e), e);
		}
	}

	/** Der Grund für die Zeile: die Meldung einer gesperrten oder veralteten Einheit, sonst die Ursache. */
	private static IllegalStateException failure(RuntimeException cause) {
		boolean forUser = cause instanceof LockedUnitException || cause instanceof IllegalStateException;
		String text = forUser && cause.getMessage() != null ? cause.getMessage() : StoreMessages.describe(cause);
		return new IllegalStateException(text, cause);
	}
}
