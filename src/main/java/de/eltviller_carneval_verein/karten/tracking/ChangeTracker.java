package de.eltviller_carneval_verein.karten.tracking;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.repository.EventMapperFactory;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;

/**
 * Erkennt ungespeicherte Änderungen an Events durch <strong>Vergleich mit dem gespeicherten Stand</strong>,
 * statt die Setter des Modells zu instrumentieren: Beim Laden und nach jedem Speichern wird der Zustand des
 * Events als JSON-Bytes (mit derselben Mapper-Konfiguration wie das Repository, siehe
 * {@link EventMapperFactory}) samt SHA-256 festgehalten. "Geändert" heißt: der aktuelle Zustand weicht vom
 * gemerkten ab. Eine zurückgenommene Änderung ist damit wieder "sauber".
 *
 * <p>
 * Je Event gibt es zwei Zustände: die <em>Baseline</em> (zuletzt geladen oder gespeichert) und den
 * <em>letzten bekannten Zustand</em> (beim letzten {@link #check(Event) Vergleich}). Beide behalten ihre Bytes,
 * nicht nur den Hash: Das ist die Voraussetzung, um einen Zustand später wiederherzustellen (Verwerfen,
 * Undo/Redo).
 *
 * <p>
 * Der Tracker beobachtet das Modell nicht selbst. Wann verglichen wird, entscheidet der Aufrufer (in der App ein
 * Event-Filter an der Scene, siehe {@code ChangeTrigger}). Nicht threadsicher: alle Aufrufe im JavaFX-Thread.
 */
public final class ChangeTracker {

	/** Zustand eines Events zu einem Zeitpunkt: serialisierte Bytes und deren SHA-256 (Hex). */
	public record Snapshot(byte[] bytes, String hash) {

		/** Gleich heißt gleicher Hash; die Bytes werden nicht erneut verglichen. */
		@Override
		public boolean equals(Object o) {
			return o instanceof Snapshot other && hash.equals(other.hash);
		}

		@Override
		public int hashCode() {
			return hash.hashCode();
		}

		@Override
		public String toString() {
			return "Snapshot[" + bytes.length + " Bytes, " + hash.substring(0, 12) + "...]";
		}
	}

	/** Wird aufgerufen, wenn der Vergleich einen anderen Zustand als beim letzten Mal findet. */
	@FunctionalInterface
	public interface Listener {
		/**
		 * @param event  das geänderte Event
		 * @param before der letzte bekannte Zustand
		 * @param after  der neue Zustand
		 */
		void onChanged(Event event, Snapshot before, Snapshot after);
	}

	private static final class Entry {
		Event event;
		Snapshot baseline;
		Snapshot last;
		final ReadOnlyBooleanWrapper dirty = new ReadOnlyBooleanWrapper(false);

		Entry(Event event, Snapshot snapshot) {
			this.event = event;
			this.baseline = snapshot;
			this.last = snapshot;
		}
	}

	private static final ChangeTracker INSTANCE = new ChangeTracker();

	private final ObjectMapper mapper = EventMapperFactory.create();
	// Schlüssel ist die Event-ID, nicht das Objekt: Das Objekt kann beim Wiederherstellen ausgetauscht werden.
	private final Map<String, Entry> entries = new LinkedHashMap<>();
	private final List<Listener> listeners = new ArrayList<>();
	private final ReadOnlyBooleanWrapper anyDirty = new ReadOnlyBooleanWrapper(false);

	public static ChangeTracker getInstance() {
		return INSTANCE;
	}

	/** Paketsichtbar, damit Tests eine eigene Instanz nutzen können, ohne den Singleton anzufassen. */
	ChangeTracker() {
	}

	/**
	 * Ein Screen lädt das Event: Der aktuelle Zustand wird Baseline und letzter Zustand, das Event gilt als
	 * sauber. Wird ein schon verfolgtes Event erneut übergeben, beginnt es von vorn.
	 */
	public void track(Event event) {
		Snapshot snapshot = snapshot(event);
		Entry entry = entries.get(event.getId());
		if (entry == null) {
			entries.put(event.getId(), new Entry(event, snapshot));
		} else {
			// Eintrag weiterverwenden, damit eine schon gebundene dirty-Eigenschaft dieselbe bleibt
			entry.event = event;
			entry.baseline = snapshot;
			entry.last = snapshot;
			entry.dirty.set(false);
		}
		updateAnyDirty();
	}

	/**
	 * Das Event wurde erfolgreich gespeichert (aufgerufen vom {@code EventSaver}): Der aktuelle Zustand wird zur
	 * neuen Baseline. War das Event noch nicht verfolgt, wird es jetzt verfolgt.
	 */
	public void markSaved(Event event) {
		track(event);
	}

	/**
	 * Vergleicht das Event mit seinem letzten bekannten Zustand und meldet eine Abweichung den Listenern.
	 * Nicht verfolgte Events werden ignoriert.
	 *
	 * @return {@code true}, wenn sich der Zustand seit dem letzten Vergleich geändert hat
	 */
	public boolean check(Event event) {
		Entry entry = entries.get(event.getId());
		if (entry == null) {
			return false;
		}
		return check(entry);
	}

	/** Vergleicht alle verfolgten Events (siehe {@link #check(Event)}). */
	public void checkAll() {
		// Kopie, weil ein Listener track()/reset() aufrufen darf
		for (Entry entry : new ArrayList<>(entries.values())) {
			if (entries.get(entry.event.getId()) == entry) {
				check(entry);
			}
		}
	}

	/** {@code true}, wenn der letzte bekannte Zustand von der Baseline abweicht. Nicht verfolgte Events sind sauber. */
	public boolean isDirty(Event event) {
		Entry entry = entries.get(event.getId());
		return entry != null && entry.dirty.get();
	}

	/**
	 * Beobachtbare Eigenschaft "ungespeicherte Änderungen" des Events (für die Anzeige im Speichern-Button).
	 *
	 * @throws IllegalArgumentException wenn das Event nicht verfolgt wird
	 */
	public ReadOnlyBooleanProperty dirtyProperty(Event event) {
		Entry entry = entries.get(event.getId());
		if (entry == null) {
			throw new IllegalArgumentException("Event wird nicht verfolgt: " + event);
		}
		return entry.dirty.getReadOnlyProperty();
	}

	/** {@code true}, wenn mindestens ein verfolgtes Event ungespeicherte Änderungen hat. */
	public boolean anyDirty() {
		return anyDirty.get();
	}

	/** Beobachtbare Variante von {@link #anyDirty()}, z. B. fürs Schließen der App. */
	public ReadOnlyBooleanProperty anyDirtyProperty() {
		return anyDirty.getReadOnlyProperty();
	}

	/** Baseline des Events (zuletzt geladen/gespeichert), oder {@code null}, wenn es nicht verfolgt wird. */
	public Snapshot baseline(Event event) {
		Entry entry = entries.get(event.getId());
		return entry == null ? null : entry.baseline;
	}

	/** Letzter bekannter Zustand des Events, oder {@code null}, wenn es nicht verfolgt wird. */
	public Snapshot last(Event event) {
		Entry entry = entries.get(event.getId());
		return entry == null ? null : entry.last;
	}

	/** Beim Verlassen eines Screens: vergisst alle Events. Die Listener bleiben angemeldet. */
	public void reset() {
		for (Entry entry : entries.values()) {
			entry.dirty.set(false);
		}
		entries.clear();
		updateAnyDirty();
	}

	public void addListener(Listener listener) {
		listeners.add(listener);
	}

	public void removeListener(Listener listener) {
		listeners.remove(listener);
	}

	private boolean check(Entry entry) {
		Snapshot current = snapshot(entry.event);
		if (current.equals(entry.last)) {
			return false;
		}
		Snapshot before = entry.last;
		entry.last = current;
		entry.dirty.set(!current.equals(entry.baseline));
		updateAnyDirty();
		for (Listener listener : new ArrayList<>(listeners)) {
			listener.onChanged(entry.event, before, current);
		}
		return true;
	}


	private void updateAnyDirty() {
		anyDirty.set(entries.values().stream().anyMatch(e -> e.dirty.get()));
	}

	private Snapshot snapshot(Event event) {
		try {
			byte[] bytes = mapper.writeValueAsBytes(event);
			return new Snapshot(bytes, sha256(bytes));
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("Event konnte nicht serialisiert werden: " + event, e);
		}
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 nicht verfügbar", e);
		}
	}

}
