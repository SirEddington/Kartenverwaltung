package de.eltviller_carneval_verein.karten.tracking;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.eltviller_carneval_verein.karten.model.Event;
import de.eltviller_carneval_verein.karten.model.Hall;
import de.eltviller_carneval_verein.karten.repository.JsonMapperFactory;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;

/**
 * Erkennt ungespeicherte Änderungen an Events und Hallen durch <strong>Vergleich mit dem gespeicherten
 * Stand</strong>, statt die Setter des Modells zu instrumentieren: Beim Laden und nach jedem Speichern wird der
 * Zustand der Entität als JSON-Bytes (mit derselben Mapper-Konfiguration wie das Repository, siehe
 * {@link JsonMapperFactory}) samt SHA-256 festgehalten. "Geändert" heißt: der aktuelle Zustand weicht vom
 * gemerkten ab. Eine zurückgenommene Änderung ist damit wieder "sauber".
 *
 * <p>
 * Es gibt einen Tracker je Art von Entität: {@link #events()} und {@link #halls()}. Beide arbeiten gleich, die
 * Instanzen sind aber getrennt, damit ein Event und eine Halle mit gleicher ID nie verwechselt werden und jede
 * Art ihre eigene Anzeige-Eigenschaft für die Speichern-Buttons hat.
 *
 * <p>
 * Je Entität gibt es zwei Zustände: die <em>Baseline</em> (zuletzt geladen oder gespeichert) und den
 * <em>letzten bekannten Zustand</em> (beim letzten {@link #check(Object) Vergleich}). Beide behalten ihre Bytes,
 * nicht nur den Hash: Das ist die Voraussetzung, um einen Zustand später wiederherzustellen (Verwerfen,
 * Undo/Redo).
 *
 * <p>
 * Der Tracker beobachtet das Modell nicht selbst. Wann verglichen wird, entscheidet der Aufrufer (in der App ein
 * Event-Filter an der Scene, siehe {@code ChangeTrigger}). Nicht threadsicher: alle Aufrufe im JavaFX-Thread.
 *
 * @param <T> {@link Event} oder {@link Hall}
 */
public final class ChangeTracker<T> {

	/** Zustand einer Entität zu einem Zeitpunkt: serialisierte Bytes und deren SHA-256 (Hex). */
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
			return "Snapshot[" + bytes.length + " Bytes, " + hash.substring(0, Math.min(12, hash.length())) + "...]";
		}
	}

	/** Wird aufgerufen, wenn der Vergleich einen anderen Zustand als beim letzten Mal findet. */
	@FunctionalInterface
	public interface Listener<T> {
		/**
		 * @param entity die geänderte Entität
		 * @param before der letzte bekannte Zustand
		 * @param after  der neue Zustand
		 */
		void onChanged(T entity, Snapshot before, Snapshot after);
	}

	private static final class Entry<T> {
		T entity;
		Snapshot baseline;
		Snapshot last;
		final ReadOnlyBooleanWrapper dirty = new ReadOnlyBooleanWrapper(false);

		Entry(T entity, Snapshot snapshot) {
			this.entity = entity;
			this.baseline = snapshot;
			this.last = snapshot;
		}
	}

	private static ChangeTracker<Event> eventTracker;
	private static ChangeTracker<Hall> hallTracker;

	private final ObjectMapper mapper = JsonMapperFactory.create();
	private final Function<T, String> idOf;
	// Schlüssel ist die ID, nicht das Objekt: Das Objekt kann beim Wiederherstellen ausgetauscht werden.
	private final Map<String, Entry<T>> entries = new LinkedHashMap<>();
	private final List<Listener<T>> listeners = new ArrayList<>();
	private final ReadOnlyBooleanWrapper anyDirty = new ReadOnlyBooleanWrapper(false);

	/** Der Tracker der App für Events. */
	public static synchronized ChangeTracker<Event> events() {
		if (eventTracker == null) {
			eventTracker = new ChangeTracker<>(Event::getId);
		}
		return eventTracker;
	}

	/** Der Tracker der App für Hallen. */
	public static synchronized ChangeTracker<Hall> halls() {
		if (hallTracker == null) {
			hallTracker = new ChangeTracker<>(Hall::getId);
		}
		return hallTracker;
	}

	/**
	 * Die App nutzt {@link #events()} und {@link #halls()}; eine eigene Instanz ist für Tests gedacht, ohne die
	 * Singletons anzufassen.
	 *
	 * @param idOf liefert die ID der Entität (Schlüssel des Verfolgens)
	 */
	public ChangeTracker(Function<T, String> idOf) {
		this.idOf = idOf;
	}

	/**
	 * Ein Screen lädt die Entität: Der aktuelle Zustand wird Baseline und letzter Zustand, sie gilt als sauber.
	 * Wird eine schon verfolgte Entität erneut übergeben, beginnt sie von vorn.
	 */
	public void track(T entity) {
		Snapshot snapshot = snapshot(entity);
		String id = idOf.apply(entity);
		Entry<T> entry = entries.get(id);
		if (entry == null) {
			entries.put(id, new Entry<>(entity, snapshot));
		} else {
			// Eintrag weiterverwenden, damit eine schon gebundene dirty-Eigenschaft dieselbe bleibt
			entry.entity = entity;
			entry.baseline = snapshot;
			entry.last = snapshot;
			entry.dirty.set(false);
		}
		updateAnyDirty();
	}

	/**
	 * Wie {@link #track(Object)}, aber nur für eine noch nicht verfolgte Entität: Ein Screen meldet damit alles, was
	 * er anzeigt oder bearbeitet, ohne dass eine schon erkannte Änderung dabei verloren geht.
	 */
	public void ensureTracked(T entity) {
		if (!entries.containsKey(idOf.apply(entity))) {
			track(entity);
		}
	}

	/**
	 * Die Entität wurde gespeichert (das Repository meldet jedes Speichern, auch am {@code EventSaver} vorbei): Der
	 * aktuelle Zustand wird zur neuen Baseline. War sie noch nicht verfolgt, wird sie jetzt verfolgt.
	 */
	public void markSaved(T entity) {
		track(entity);
	}

	/** Die Entität wurde gelöscht: Sie wird nicht mehr verfolgt und kann beim Verlassen nicht wieder angeboten werden. */
	public void untrack(T entity) {
		Entry<T> entry = entries.remove(idOf.apply(entity));
		if (entry != null) {
			entry.dirty.set(false);
			updateAnyDirty();
		}
	}

	/**
	 * Die verfolgte Entität wurde durch ein anderes Objekt im Zustand {@code state} ersetzt (Wiederherstellen, siehe
	 * {@link StateRestorer}). Die Baseline bleibt, damit ein Zustand vor dem Speichern wieder "geändert" heißen
	 * kann und der gespeicherte Stand wieder "sauber". Die Listener erfahren den Zustandswechsel wie bei jeder
	 * anderen Änderung.
	 */
	public void replaceEntity(T replacement, Snapshot state) {
		Entry<T> entry = entries.get(idOf.apply(replacement));
		if (entry == null) {
			throw new IllegalArgumentException("Wird nicht verfolgt: " + replacement);
		}
		Snapshot before = entry.last;
		entry.entity = replacement;
		entry.last = state;
		entry.dirty.set(!state.equals(entry.baseline));
		updateAnyDirty();
		if (!state.equals(before)) {
			for (Listener<T> listener : new ArrayList<>(listeners)) {
				listener.onChanged(replacement, before, state);
			}
		}
	}

	/**
	 * Vergleicht die Entität mit ihrem letzten bekannten Zustand und meldet eine Abweichung den Listenern.
	 * Nicht verfolgte Entitäten werden ignoriert.
	 *
	 * @return {@code true}, wenn sich der Zustand seit dem letzten Vergleich geändert hat
	 */
	public boolean check(T entity) {
		Entry<T> entry = entries.get(idOf.apply(entity));
		if (entry == null) {
			return false;
		}
		return check(entry);
	}

	/** Vergleicht alle verfolgten Entitäten (siehe {@link #check(Object)}). */
	public void checkAll() {
		// Kopie, weil ein Listener track()/reset() aufrufen darf
		for (Entry<T> entry : new ArrayList<>(entries.values())) {
			if (entries.get(idOf.apply(entry.entity)) == entry) {
				check(entry);
			}
		}
	}

	/** {@code true}, wenn der letzte bekannte Zustand von der Baseline abweicht. Nicht verfolgte Entitäten sind sauber. */
	public boolean isDirty(T entity) {
		Entry<T> entry = entries.get(idOf.apply(entity));
		return entry != null && entry.dirty.get();
	}

	/**
	 * Beobachtbare Eigenschaft "ungespeicherte Änderungen" der Entität.
	 *
	 * @throws IllegalArgumentException wenn die Entität nicht verfolgt wird
	 */
	public ReadOnlyBooleanProperty dirtyProperty(T entity) {
		Entry<T> entry = entries.get(idOf.apply(entity));
		if (entry == null) {
			throw new IllegalArgumentException("Wird nicht verfolgt: " + entity);
		}
		return entry.dirty.getReadOnlyProperty();
	}

	/** Die Entitäten mit ungespeicherten Änderungen (nach dem letzten Vergleich), in der Reihenfolge des Verfolgens. */
	public List<T> dirtyEntities() {
		return entries.values().stream().filter(e -> e.dirty.get()).map(e -> e.entity).toList();
	}

	/** {@code true}, wenn mindestens eine verfolgte Entität ungespeicherte Änderungen hat. */
	public boolean anyDirty() {
		return anyDirty.get();
	}

	/** Beobachtbare Variante von {@link #anyDirty()}, für die Anzeige im Speichern-Button. */
	public ReadOnlyBooleanProperty anyDirtyProperty() {
		return anyDirty.getReadOnlyProperty();
	}

	/** Baseline der Entität (zuletzt geladen/gespeichert), oder {@code null}, wenn sie nicht verfolgt wird. */
	public Snapshot baseline(T entity) {
		Entry<T> entry = entries.get(idOf.apply(entity));
		return entry == null ? null : entry.baseline;
	}

	/** Letzter bekannter Zustand der Entität, oder {@code null}, wenn sie nicht verfolgt wird. */
	public Snapshot last(T entity) {
		Entry<T> entry = entries.get(idOf.apply(entity));
		return entry == null ? null : entry.last;
	}

	/** Beim Verlassen eines Screens: vergisst alle Entitäten. Die Listener bleiben angemeldet. */
	public void reset() {
		for (Entry<T> entry : entries.values()) {
			entry.dirty.set(false);
		}
		entries.clear();
		updateAnyDirty();
	}

	public void addListener(Listener<T> listener) {
		listeners.add(listener);
	}

	public void removeListener(Listener<T> listener) {
		listeners.remove(listener);
	}

	private boolean check(Entry<T> entry) {
		Snapshot current = snapshot(entry.entity);
		if (current.equals(entry.last)) {
			return false;
		}
		Snapshot before = entry.last;
		entry.last = current;
		entry.dirty.set(!current.equals(entry.baseline));
		updateAnyDirty();
		for (Listener<T> listener : new ArrayList<>(listeners)) {
			listener.onChanged(entry.entity, before, current);
		}
		return true;
	}

	private void updateAnyDirty() {
		anyDirty.set(entries.values().stream().anyMatch(e -> e.dirty.get()));
	}

	private Snapshot snapshot(T entity) {
		try {
			byte[] bytes = mapper.writeValueAsBytes(entity);
			return new Snapshot(bytes, sha256(bytes));
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("Konnte nicht serialisiert werden: " + entity, e);
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
