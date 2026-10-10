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

import de.eltviller_carneval_verein.karten.AppPaths;
import de.eltviller_carneval_verein.karten.model.Hall;

public class JsonHallRepository implements HallRepository {

	private static JsonHallRepository instance;

	private final File storageDir;
	private final ObjectMapper objectMapper;

	// Im-Speicher-Cache der Hallen. null bedeutet "noch nicht von der Platte geladen".
	// Mehrere Vorstellungen (auch über verschiedene Events hinweg) teilen sich über
	// dieselbe hallId dieselbe Hall-Objektreferenz aus diesem Cache.
	private List<Hall> cachedHalls;

	// Warnungen aus dem letzten readHallsFromDisk()-Lauf (unlesbare Dateien, gefundene
	// Duplikate). Das Repository kennt keine UI, daher werden sie hier nur gesammelt -
	// anzeigen muss die aufrufende Schicht (siehe getAndClearLoadWarnings()).
	private final List<String> loadWarnings = new ArrayList<>();

	private final List<RepositoryListener<Hall>> listeners = new ArrayList<>();

	/**
	 * Liefert die einzige Instanz des Repositorys. Alle Controller sollen sich
	 * darüber die Instanz holen, statt selbst eine eigene zu erzeugen, damit
	 * nicht an mehreren Stellen unabhängig von der Festplatte gelesen wird.
	 */
	public static synchronized JsonHallRepository getInstance() {
		if (instance == null) {
			instance = new JsonHallRepository(resolveDefaultStorageDir());
		}
		return instance;
	}

	/**
	 * Fester, vom Arbeitsverzeichnis unabhängiger Speicherort unter %LOCALAPPDATA%,
	 * als Geschwister-Ordner von events_data. Fällt außerhalb von Windows (z.B. beim
	 * Entwickeln) auf das Nutzerverzeichnis zurück.
	 */
	private static File resolveDefaultStorageDir() {
		return new File(AppPaths.appDataDir(), "halls_data");
	}

	// Konstuktoren -->
	/**
	 * Paketsichtbar statt privat, damit Tests direkt ein temporäres Verzeichnis
	 * injizieren können, ohne den Produktions-Singleton anzufassen.
	 */
	JsonHallRepository(File storageDir) {
		this.storageDir = storageDir;

		this.objectMapper = JsonMapperFactory.create();

		if (!storageDir.exists()) {
			storageDir.mkdirs();
		}
	}
	// <-- Konstuktoren

	@Override
	public List<Hall> loadHalls() {
		if (cachedHalls == null) {
			cachedHalls = readHallsFromDisk();
		}
		// Kopie der Liste zurückgeben, damit niemand von außen den Cache selbst
		// verändern kann (add/remove) - die enthaltenen Hall-Objekte sind aber
		// bewusst dieselben Referenzen wie im Cache.
		return new ArrayList<>(cachedHalls);
	}

	/**
	 * Verwirft den Cache und liest alle Hallen zwangsweise neu von der Festplatte.
	 * Ungespeicherte Änderungen an den bisherigen Hall-Objekten gehen dabei verloren.
	 */
	public List<Hall> reloadHalls() {
		cachedHalls = readHallsFromDisk();
		return new ArrayList<>(cachedHalls);
	}

	@Override
	public Hall findById(String hallId) {
		if (hallId == null) {
			return null;
		}
		for (Hall hall : loadHalls()) {
			if (hallId.equals(hall.getId())) {
				return hall;
			}
		}
		return null;
	}

	/**
	 * Warnungen aus dem letzten Lesevorgang (unlesbare oder doppelte Hall-Dateien).
	 * Leert die Liste beim Abholen, damit dieselbe Warnung nicht mehrfach angezeigt wird.
	 * Anzeigen (z.B. per Alert) ist Sache der aufrufenden UI-Schicht.
	 */
	public synchronized List<String> getAndClearLoadWarnings() {
		List<String> warnings = new ArrayList<>(loadWarnings);
		loadWarnings.clear();
		return warnings;
	}

	private List<Hall> readHallsFromDisk() {
		List<Hall> halls = new ArrayList<>();
		Map<String, File> fileByHallId = new HashMap<>();

		// Alle .json-Dateien im Ordner suchen
		File[] files = storageDir.listFiles((dir, name) -> name.endsWith(".json"));

		if (files != null) {
			for (File file : files) {
				Hall hall;
				try {
					hall = objectMapper.readValue(file, Hall.class);
				} catch (IOException e) {
					loadWarnings.add("Datei '" + file.getName() + "' konnte nicht gelesen werden und wurde übersprungen: " + e.getMessage());
					continue;
				}

				File previousFile = fileByHallId.get(hall.getId());
				if (previousFile != null) {
					// Gleiche Hall-ID in zwei Dateien - die ältere Datei beiseitelegen, nicht löschen.
					boolean currentIsOlder = file.lastModified() < previousFile.lastModified();
					File olderFile = currentIsOlder ? file : previousFile;
					File newerFile = currentIsOlder ? previousFile : file;
					setAside(olderFile);
					loadWarnings.add("Doppelte Halle gefunden ('" + hall.getName() + "'): '" + olderFile.getName() + "' war eine veraltete Kopie und wurde beiseitegelegt, '" + newerFile.getName() + "' wird verwendet.");

					if (currentIsOlder) {
						// Die gerade gelesene Datei ist die veraltete - die schon geladene Halle behalten.
						continue;
					}
					// Die vorher geladene Datei war die veraltete - ihre Halle durch die aktuelle ersetzen.
					halls.removeIf(h -> h.getId().equals(hall.getId()));
				}

				halls.add(hall);
				fileByHallId.put(hall.getId(), file);
			}
		}
		return halls;
	}

	private void setAside(File file) {
		File renamed = new File(file.getParentFile(), file.getName() + ".duplicate-" + System.currentTimeMillis() + ".bak");
		if (!file.renameTo(renamed)) {
			loadWarnings.add("Veraltete Duplikat-Datei '" + file.getName() + "' konnte nicht umbenannt werden.");
		}
	}

	@Override
	public void saveHalls(List<Hall> halls) {
		for (Hall hall : halls) {
			saveHall(hall);
		}
	}

	@Override
	public void saveHall(Hall hall) {
		if (hall == null) {
			throw new IllegalArgumentException("Das Hall-Objekt darf nicht null sein.");
		}

		String fileName = hall.getId() + ".json";
		File hallFile = new File(storageDir, fileName);

		try {
			if (hallFile.exists()) {
				// Letzte bekannte gute Fassung sichern, bevor sie überschrieben wird.
				File backupFile = new File(storageDir, fileName + ".bak");
				Files.copy(hallFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
			}

			// In eine temporäre Datei im selben Verzeichnis schreiben und erst danach atomar
			// umbenennen, damit ein Absturz mitten im Schreiben nicht die Hall-Datei zerstört.
			File tempFile = File.createTempFile(hall.getId(), ".tmp", storageDir);
			objectMapper.writeValue(tempFile, hall);
			Files.move(tempFile.toPath(), hallFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			throw new RuntimeException("Fehler beim Speichern der Datei: " + fileName, e);
		}

		// Cache aktualisieren, falls die Halle dort noch nicht enthalten ist
		// (z.B. eine neu erstellte Halle)
		if (cachedHalls == null) {
			cachedHalls = new ArrayList<>();
		}
		if (!cachedHalls.contains(hall)) {
			cachedHalls.add(hall);
		}

		for (RepositoryListener<Hall> listener : new ArrayList<>(listeners)) {
			listener.saved(hall);
		}
	}

	/** Meldet einen Listener an, der nach jedem Speichern und Löschen einer Halle benachrichtigt wird. */
	public void addListener(RepositoryListener<Hall> listener) {
		listeners.add(listener);
	}

	/**
	 * Ersetzt die Halle mit derselben ID im Cache durch ein anderes Objekt, z. B. nach dem Wiederherstellen eines
	 * früheren Zustands. Die Datei bleibt unberührt, und es wird kein Listener benachrichtigt. Ist die Halle nicht
	 * im Cache (noch nie gespeichert), passiert nichts.
	 */
	public void replaceCachedHall(Hall replacement) {
		if (cachedHalls == null) {
			return;
		}
		int index = cachedHalls.indexOf(replacement);
		if (index >= 0) {
			cachedHalls.set(index, replacement);
		}
	}

	@Override
	public void deleteHalls(List<Hall> halls) {
		for (Hall hall : halls) {
			deleteHall(hall);
		}
	}

	@Override
	public void deleteHall(Hall hall) {
		if (hall == null) {
			return;
		}

		String fileName = hall.getId() + ".json";
		File hallFile = new File(storageDir, fileName);
		File backupFile = new File(storageDir, fileName + ".bak");

		if (hallFile.exists() && !hallFile.delete()) {
			throw new RuntimeException("Fehler beim Löschen der Datei: " + fileName);
		}
		if (backupFile.exists()) {
			backupFile.delete();
		}

		if (cachedHalls != null) {
			cachedHalls.remove(hall);
		}

		for (RepositoryListener<Hall> listener : new ArrayList<>(listeners)) {
			listener.deleted(hall);
		}
	}

}
