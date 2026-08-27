package de.eltviller_carneval_verein.karten.repository;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import de.eltviller_carneval_verein.karten.model.Event;

public class JsonTicketRepository implements TicketRepository {
	
	private final File storageDir;
	private final ObjectMapper objectMapper;

	// Konstuktoren -->
	public JsonTicketRepository() {
    	this("events_data");
	}
    
	public JsonTicketRepository(String dirPath) {
		this.storageDir = new File(dirPath);
		
		//Ordner zum ablegen der Events erstellen
		if (!storageDir.exists()) {
			storageDir.mkdirs();
		}
		
		this.objectMapper = new ObjectMapper();
		this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
	}
	// <-- Konstuktoren
	
	@Override
	public List<Event> loadEvents() {
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
	
	public void saveEvent(Event event) {
		if (event.getName() == null) {
			throw new IllegalArgumentException("Das Event-Objekt darf nicht null sein.");
		}
		
		if (event.getName().isBlank()) {
			throw new IllegalArgumentException("Event-Name darf nicht leer oder null sein.");
		}
		
		String fileName = event.getName().replaceAll("[^a-zA-Z0-9._-]", "_") + ".json";
		File eventFile = new File(storageDir, fileName);
		
		//Prüfen ob die Datei bereits existiert
		if (eventFile.exists()) {
			try {
				//Versuchen die Datei als Event-Objekt einzulesen
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
		
		//Datei existiert noch nicht oder es handelt sich um das selbe Event -> speichern fortsetzen
		try {
			objectMapper.writeValue(eventFile, event);
		} catch (IOException e) {
			throw new RuntimeException("Fehler beim Speichern der Datei: " + fileName, e);
		}
	}

}
