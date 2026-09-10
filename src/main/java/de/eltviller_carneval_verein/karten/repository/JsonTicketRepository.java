package de.eltviller_carneval_verein.karten.repository;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import de.eltviller_carneval_verein.karten.model.Event;

public class JsonTicketRepository implements TicketRepository {

	private static JsonTicketRepository instance;

	private final File storageDir;
	private final ObjectMapper objectMapper;

	// Im-Speicher-Cache der Events. null bedeutet "noch nicht von der Platte geladen".
	// Alle Controller bekommen über loadEvents() dieselben Event-Objektreferenzen,
	// damit ungespeicherte Änderungen eines Controllers auch für andere sichtbar sind.
	private List<Event> cachedEvents;

	/**
	 * Liefert die einzige Instanz des Repositorys. Alle Controller sollen sich
	 * darüber die Instanz holen, statt selbst eine eigene zu erzeugen, damit
	 * nicht an mehreren Stellen unabhängig von der Festplatte gelesen wird.
	 */
	public static synchronized JsonTicketRepository getInstance() {
		if (instance == null) {
			instance = new JsonTicketRepository("events_data");
		}
		return instance;
	}

	// Konstuktoren -->
	private JsonTicketRepository(String dirPath) {
		this.storageDir = new File(dirPath);

		// Ordner zum ablegen der Events erstellen
		if (!storageDir.exists()) {
			storageDir.mkdirs();
		}

		this.objectMapper = new ObjectMapper();
		this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
	}
	// <-- Konstuktoren

	@Override
	public List<Event> loadEvents() {
		if (cachedEvents == null) {
			cachedEvents = readEventsFromDisk();
		}
		// Kopie der Liste zurückgeben, damit niemand von außen den Cache selbst
		// verändern kann (add/remove) - die enthaltenen Event-Objekte sind aber
		// bewusst dieselben Referenzen wie im Cache.
		return new ArrayList<>(cachedEvents);
	}

	/**
	 * Verwirft den Cache und liest alle Events zwangsweise neu von der Festplatte.
	 * Ungespeicherte Änderungen an den bisherigen Event-Objekten gehen dabei verloren.
	 */
	public List<Event> reloadEvents() {
		cachedEvents = readEventsFromDisk();
		return new ArrayList<>(cachedEvents);
	}

	private List<Event> readEventsFromDisk() {
		List<Event> events = new ArrayList<>();

		// Alle .json-Dateien im Ordner suchen
		File[] files = storageDir.listFiles((dir, name) -> name.endsWith(".json"));

		if (files != null) {
			for (File file : files) {
				try {
					Event event = objectMapper.readValue(file, Event.class);
					events.add(event);
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
		return events;
	}

	@Override
	public void saveEvents(List<Event> events) {
		for (Event event : events) {
			saveEvent(event);
		}
	}

	@Override
	public void saveEvent(Event event) {
		if (event.getName() == null) {
			throw new IllegalArgumentException("Das Event-Objekt darf nicht null sein.");
		}

		if (event.getName().isBlank()) {
			throw new IllegalArgumentException("Event-Name darf nicht leer oder null sein.");
		}

		String fileName = event.getName().replaceAll("[^a-zA-Z0-9._-]", "_") + ".json";
		File eventFile = new File(storageDir, fileName);

		// Prüfen ob die Datei bereits existiert
		if (eventFile.exists()) {
			try {
				// Versuchen die Datei als Event-Objekt einzulesen
				Event existingEvent = objectMapper.readValue(eventFile, Event.class);

				// Prüfen, ob es sich um genau dasselbe Event handelt (exakter Namensvergleich)
				if (existingEvent.getName() == null || !existingEvent.getName().equals(event.getName())) {
					throw new IllegalStateException("Namenskollision: Die Datei '" + fileName + "' existiert bereits für ein anderes Event ('" + existingEvent.getName() + "'). Speichern abgebrochen.");
				}

			} catch (IOException e) {
				// Datei existiert, ist aber kein gültiges JSON oder keine Event-Datei
				throw new IllegalStateException("Die Datei '" + fileName + "' existiert bereits, ist jedoch keine gültige Event-Datei.", e);
			}
		}

		// Datei existiert noch nicht oder es handelt sich um das selbe Event -> speichern fortsetzen
		try {
			objectMapper.writeValue(eventFile, event);
		} catch (IOException e) {
			throw new RuntimeException("Fehler beim Speichern der Datei: " + fileName, e);
		}

		// Cache aktualisieren, falls das Event dort noch nicht enthalten ist
		// (z.B. ein neu erstelltes Event)
		if (cachedEvents == null) {
			cachedEvents = new ArrayList<>();
		}
		if (!cachedEvents.contains(event)) {
			cachedEvents.add(event);
		}
	}

	@Override
	public void deleteEvents(List<Event> events) {
		for (Event event : events) {
			deleteEvent(event);
		}
	}

	@Override
	public void deleteEvent(Event event) {
		if (event == null || event.getName() == null) {
			return;
		}

		String fileName = event.getName().replaceAll("[^a-zA-Z0-9._-]", "_") + ".json";
		File eventFile = new File(storageDir, fileName);

		if (eventFile.exists() && !eventFile.delete()) {
			throw new RuntimeException("Fehler beim Löschen der Datei: " + fileName);
		}

		if (cachedEvents != null) {
			cachedEvents.remove(event);
		}
	}

}
