package de.eltviller_carneval_verein.karten.repository;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import de.eltviller_carneval_verein.karten.model.Event;

public class JsonTicketRepository implements TicketRepository {

	private static JsonTicketRepository instance;

	private final File storageDir;
	private final ObjectMapper objectMapper;

	// Im-Speicher-Cache der Events. null bedeutet "noch nicht von der Platte geladen".
	// Alle Controller bekommen über loadEvents() dieselben Event-Objektreferenzen,
	// damit ungespeicherte Änderungen eines Controllers auch für andere sichtbar sind.
	private List<Event> cachedEvents;

	// Warnungen aus dem letzten readEventsFromDisk()-Lauf (unlesbare Dateien, gefundene
	// Duplikate, gescheiterte Migration). Das Repository kennt keine UI, daher werden sie
	// hier nur gesammelt - anzeigen muss die aufrufende Schicht (siehe getAndClearLoadWarnings()).
	private final List<String> loadWarnings = new ArrayList<>();

	/**
	 * Liefert die einzige Instanz des Repositorys. Alle Controller sollen sich
	 * darüber die Instanz holen, statt selbst eine eigene zu erzeugen, damit
	 * nicht an mehreren Stellen unabhängig von der Festplatte gelesen wird.
	 */
	public static synchronized JsonTicketRepository getInstance() {
		if (instance == null) {
			instance = new JsonTicketRepository(resolveDefaultStorageDir());
		}
		return instance;
	}

	/**
	 * Fester, vom Arbeitsverzeichnis unabhängiger Speicherort unter %LOCALAPPDATA%.
	 * Fällt außerhalb von Windows (z.B. beim Entwickeln) auf das Nutzerverzeichnis zurück.
	 */
	private static File resolveDefaultStorageDir() {
		String localAppData = System.getenv("LOCALAPPDATA");
		File appDataDir = (localAppData != null && !localAppData.isBlank())
				? new File(localAppData, "EltvillerCarnevalVerein" + File.separator + "Kartenverwaltung")
				: new File(System.getProperty("user.home"), ".kartenverwaltung");
		return new File(appDataDir, "events_data");
	}

	// Konstuktoren -->
	/**
	 * Paketsichtbar statt privat, damit Tests direkt ein temporäres Verzeichnis
	 * injizieren können, ohne den Produktions-Singleton anzufassen.
	 */
	JsonTicketRepository(File storageDir) {
		this.storageDir = storageDir;

		this.objectMapper = new ObjectMapper();
		this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
		this.objectMapper.registerModule(new JavaTimeModule());
		this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

		boolean isNewStorageDir = !storageDir.exists();
		if (isNewStorageDir) {
			storageDir.mkdirs();
			migrateLegacyStorage();
		}
	}
	// <-- Konstuktoren

	/**
	 * Übernimmt Event-Dateien aus dem alten, arbeitsverzeichnis-relativen "events_data"-Ordner
	 * in den neuen festen Speicherort, falls dieser gerade erst angelegt wurde. Kopiert nur -
	 * die alten Dateien bleiben unangetastet liegen, damit dabei nichts verloren gehen kann.
	 */
	private void migrateLegacyStorage() {
		File legacyDir = new File("events_data");
		if (!legacyDir.isDirectory()) {
			return;
		}

		try {
			if (legacyDir.getCanonicalFile().equals(storageDir.getCanonicalFile())) {
				return;
			}
		} catch (IOException e) {
			return;
		}

		File[] legacyFiles = legacyDir.listFiles((dir, name) -> name.endsWith(".json"));
		if (legacyFiles == null) {
			return;
		}

		for (File legacyFile : legacyFiles) {
			File target = new File(storageDir, legacyFile.getName());
			try {
				Files.copy(legacyFile.toPath(), target.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
			} catch (IOException e) {
				loadWarnings.add("Alte Datei '" + legacyFile.getName() + "' konnte nicht in den neuen Datenordner übernommen werden: " + e.getMessage());
			}
		}
	}

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

	/**
	 * Warnungen aus dem letzten Lesevorgang (unlesbare oder doppelte Event-Dateien).
	 * Leert die Liste beim Abholen, damit dieselbe Warnung nicht mehrfach angezeigt wird.
	 * Anzeigen (z.B. per Alert) ist Sache der aufrufenden UI-Schicht.
	 */
	public synchronized List<String> getAndClearLoadWarnings() {
		List<String> warnings = new ArrayList<>(loadWarnings);
		loadWarnings.clear();
		return warnings;
	}

	private List<Event> readEventsFromDisk() {
		List<Event> events = new ArrayList<>();
		Map<String, File> fileByEventId = new HashMap<>();

		// Alle .json-Dateien im Ordner suchen
		File[] files = storageDir.listFiles((dir, name) -> name.endsWith(".json"));

		if (files != null) {
			for (File file : files) {
				Event event;
				try {
					event = objectMapper.readValue(file, Event.class);
				} catch (IOException e) {
					loadWarnings.add("Datei '" + file.getName() + "' konnte nicht gelesen werden und wurde übersprungen: " + e.getMessage());
					continue;
				}

				File previousFile = fileByEventId.get(event.getId());
				if (previousFile != null) {
					// Gleiche Event-ID in zwei Dateien (z.B. Altlast einer früheren
					// namensbasierten Datei) - die ältere Datei beiseitelegen, nicht löschen.
					boolean currentIsOlder = file.lastModified() < previousFile.lastModified();
					File olderFile = currentIsOlder ? file : previousFile;
					File newerFile = currentIsOlder ? previousFile : file;
					setAside(olderFile);
					loadWarnings.add("Doppeltes Event gefunden ('" + event.getName() + "'): '" + olderFile.getName() + "' war eine veraltete Kopie und wurde beiseitegelegt, '" + newerFile.getName() + "' wird verwendet.");

					if (currentIsOlder) {
						// Die gerade gelesene Datei ist die veraltete - das schon geladene Event behalten.
						continue;
					}
					// Die vorher geladene Datei war die veraltete - ihr Event durch das aktuelle ersetzen.
					events.removeIf(e -> e.getId().equals(event.getId()));
				}

				events.add(event);
				fileByEventId.put(event.getId(), file);
			}
		}
		return events;
	}

	private void setAside(File file) {
		File renamed = new File(file.getParentFile(), file.getName() + ".duplicate-" + System.currentTimeMillis() + ".bak");
		if (!file.renameTo(renamed)) {
			loadWarnings.add("Veraltete Duplikat-Datei '" + file.getName() + "' konnte nicht umbenannt werden.");
		}
	}

	@Override
	public void saveEvents(List<Event> events) {
		for (Event event : events) {
			saveEvent(event);
		}
	}

	@Override
	public void saveEvent(Event event) {
		if (event == null) {
			throw new IllegalArgumentException("Das Event-Objekt darf nicht null sein.");
		}

		if (event.getName() == null || event.getName().isBlank()) {
			throw new IllegalArgumentException("Event-Name darf nicht leer oder null sein.");
		}

		String fileName = event.getId() + ".json";
		File eventFile = new File(storageDir, fileName);

		try {
			if (eventFile.exists()) {
				// Letzte bekannte gute Fassung sichern, bevor sie überschrieben wird.
				File backupFile = new File(storageDir, fileName + ".bak");
				Files.copy(eventFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
			}

			// In eine temporäre Datei im selben Verzeichnis schreiben und erst danach atomar
			// umbenennen, damit ein Absturz mitten im Schreiben nicht die Event-Datei zerstört.
			File tempFile = File.createTempFile(event.getId(), ".tmp", storageDir);
			objectMapper.writeValue(tempFile, event);
			Files.move(tempFile.toPath(), eventFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
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
		if (event == null) {
			return;
		}

		String fileName = event.getId() + ".json";
		File eventFile = new File(storageDir, fileName);
		File backupFile = new File(storageDir, fileName + ".bak");

		if (eventFile.exists() && !eventFile.delete()) {
			throw new RuntimeException("Fehler beim Löschen der Datei: " + fileName);
		}
		if (backupFile.exists()) {
			backupFile.delete();
		}

		if (cachedEvents != null) {
			cachedEvents.remove(event);
		}
	}

}
