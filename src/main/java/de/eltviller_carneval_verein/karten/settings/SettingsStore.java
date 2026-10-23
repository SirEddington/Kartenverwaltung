package de.eltviller_carneval_verein.karten.settings;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.eltviller_carneval_verein.karten.AppPaths;
import de.eltviller_carneval_verein.karten.repository.JsonMapperFactory;

/**
 * Der zentrale Speicher für die {@link AppSettings}, gelesen aus und geschrieben nach {@code settings.json}.
 * <p>
 * Wer die Einstellungen braucht, hält die Referenz nie, sondern ruft jedes Mal {@code
 * SettingsStore.getInstance().current()} auf: "Nicht speichern" tauscht das Objekt über
 * {@link #replaceCurrent(AppSettings)} aus, eine gemerkte Referenz zeigte danach auf einen veralteten Stand.
 */
public final class SettingsStore {

	private static final Logger LOGGER = Logger.getLogger(SettingsStore.class.getName());

	private static final String FILE_NAME = "settings.json";

	private static SettingsStore instance;

	private final File settingsFile;
	private final ObjectMapper objectMapper;

	private AppSettings current;

	// Warnungen aus dem Laden (defekte Datei). Der Store kennt keine UI, daher werden sie hier nur gesammelt -
	// anzeigen muss die aufrufende Schicht (siehe getAndClearLoadWarnings()).
	private final List<String> loadWarnings = new ArrayList<>();

	private final List<Consumer<AppSettings>> listeners = new ArrayList<>();

	/**
	 * Liefert die einzige Instanz des Speichers. Die Datei liegt fest in {@link AppPaths#appDataDir()} und kann
	 * nicht selbst verschoben werden, sonst fände man sie nach einem Wechsel des Speicherorts nicht mehr.
	 */
	public static synchronized SettingsStore getInstance() {
		if (instance == null) {
			instance = new SettingsStore(AppPaths.appDataDir());
		}
		return instance;
	}

	// Konstuktoren -->
	/**
	 * Paketsichtbar statt privat, damit Tests direkt ein temporäres Verzeichnis injizieren können, ohne den
	 * Produktions-Singleton anzufassen. Lädt die Datei sofort (nicht erst beim ersten Zugriff), damit die
	 * Warnungen beim Start feststehen.
	 */
	SettingsStore(File storageDir) {
		this.settingsFile = new File(storageDir, FILE_NAME);
		this.objectMapper = JsonMapperFactory.create();

		if (!storageDir.exists()) {
			storageDir.mkdirs();
		}

		this.current = load();
	}
	// <-- Konstuktoren

	/** Die aktuellen Einstellungen. Die Referenz nicht aufbewahren, siehe Klassenbeschreibung. */
	public AppSettings current() {
		return current;
	}

	/**
	 * Tauscht die aktuellen Einstellungen gegen ein anderes Objekt aus, z. B. nach dem Verwerfen von Änderungen.
	 * Die Datei bleibt unberührt, und es wird kein Listener benachrichtigt.
	 */
	public void replaceCurrent(AppSettings replacement) {
		this.current = replacement;
	}

	/** Meldet einen Listener an, der nach jedem erfolgreichen Speichern mit den gespeicherten Einstellungen gerufen wird. */
	public void addListener(Consumer<AppSettings> onSaved) {
		listeners.add(onSaved);
	}

	/**
	 * Warnungen aus dem Laden (defekte Datei). Leert die Liste beim Abholen, damit dieselbe Warnung nicht
	 * mehrfach angezeigt wird. Anzeigen (z. B. per Alert) ist Sache der aufrufenden UI-Schicht.
	 */
	public synchronized List<String> getAndClearLoadWarnings() {
		List<String> warnings = new ArrayList<>(loadWarnings);
		loadWarnings.clear();
		return warnings;
	}

	/**
	 * Schreibt die aktuellen Einstellungen nach {@code settings.json} und benachrichtigt danach die Listener.
	 *
	 * @throws RuntimeException wenn die Datei nicht geschrieben werden kann
	 */
	public void save() {
		AppSettings toSave = current;
		try {
			// In eine temporäre Datei im selben Verzeichnis schreiben und erst danach atomar umbenennen,
			// damit ein Absturz mitten im Schreiben nicht die Einstellungsdatei zerstört.
			File tempFile = File.createTempFile("settings", ".tmp", settingsFile.getParentFile());
			try {
				objectMapper.writeValue(tempFile, toSave);
				Files.move(tempFile.toPath(), settingsFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException | RuntimeException e) {
				tempFile.delete();
				throw e;
			}
		} catch (IOException e) {
			throw new RuntimeException("Fehler beim Speichern der Datei: " + FILE_NAME, e);
		}

		for (Consumer<AppSettings> listener : new ArrayList<>(listeners)) {
			try {
				listener.accept(toSave);
			} catch (RuntimeException e) {
				// Ein fehlerhafter Listener darf weder das Speichern scheitern lassen noch die übrigen Listener verhindern.
				LOGGER.log(Level.SEVERE, "Fehler in einem Listener nach dem Speichern der Einstellungen", e);
			}
		}
	}

	private AppSettings load() {
		if (!settingsFile.exists()) {
			return new AppSettings();
		}

		try {
			// Erst als Baum lesen: Gültiges JSON, das kein Objekt ist (z. B. []), gilt ebenfalls als defekt.
			JsonNode tree = objectMapper.readTree(settingsFile);
			if (tree == null || !tree.isObject()) {
				throw new IOException("Die Datei enthält kein JSON-Objekt.");
			}
			return objectMapper.treeToValue(tree, AppSettings.class);
		} catch (IOException e) {
			setAside(e);
			return new AppSettings();
		}
	}

	/** Legt die defekte Datei beiseite (statt sie zu löschen oder beim nächsten Speichern zu überschreiben). */
	private void setAside(IOException cause) {
		File renamed = new File(settingsFile.getParentFile(), FILE_NAME + ".defekt-" + System.currentTimeMillis() + ".bak");
		loadWarnings.add("Die Datei '" + FILE_NAME + "' konnte nicht gelesen werden, es gelten die Standardeinstellungen: " + cause.getMessage());
		if (!settingsFile.renameTo(renamed)) {
			loadWarnings.add("Die defekte Datei '" + FILE_NAME + "' konnte nicht umbenannt werden.");
		}
	}
}
