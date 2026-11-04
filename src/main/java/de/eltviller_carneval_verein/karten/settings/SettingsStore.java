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

	// Der zuletzt gespeicherte Stand, unabhängig von current (das ungespeicherte Änderungen trägt). Grundlage des Teil-Speicherns.
	private AppSettings saved;

	// Warnungen aus dem Laden (defekte Datei). Der Store kennt keine UI, daher werden sie hier nur gesammelt -
	// anzeigen muss die aufrufende Schicht (siehe getAndClearLoadWarnings()).
	private final List<String> loadWarnings = new ArrayList<>();

	private final List<Consumer<AppSettings>> listeners = new ArrayList<>();
	private final List<Consumer<AppSettings>> partialListeners = new ArrayList<>();

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
		this.saved = copyOf(current);
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
		synchronized (this) {
			write(toSave);
			saved = copyOf(toSave);
		}
		notifyListeners(listeners, toSave, "nach dem Speichern");
	}

	/**
	 * Speichert nur eine Änderung, ohne andere offene Änderungen in {@link #current()} mitzuschreiben (Teil-Speichern, etwa
	 * für den gemeinsamen Ordner beim Verbinden): {@code change} wird auf eine Kopie des zuletzt gespeicherten Stands
	 * angewendet, diese Kopie geschrieben (wie bei {@link #save()}), danach {@code change} auch auf {@link #current()}.
	 * Die Listener aus {@link #addPartialListener} bekommen die geschriebene Kopie; die aus {@link #addListener} nicht.
	 * Scheitert das Schreiben, bleibt {@link #current()} unverändert.
	 *
	 * @throws RuntimeException wenn die Datei nicht geschrieben werden kann
	 */
	public void saveOnly(Consumer<AppSettings> change) {
		AppSettings written;
		synchronized (this) {
			written = copyOf(saved);
			change.accept(written);
			write(written);
			saved = written;
		}
		change.accept(current);
		notifyListeners(partialListeners, written, "nach dem Teil-Speichern");
	}

	/**
	 * Meldet einen Listener an, der nach jedem erfolgreichen Teil-Speichern ({@link #saveOnly}) mit der geschriebenen Kopie
	 * des gespeicherten Stands gerufen wird. Die Kopie ist nicht {@link #current()}: Sie trägt keine ungespeicherte Änderung.
	 */
	public void addPartialListener(Consumer<AppSettings> onPartiallySaved) {
		partialListeners.add(onPartiallySaved);
	}

	/**
	 * In eine temporäre Datei im selben Verzeichnis schreiben und erst danach atomar umbenennen, damit ein Absturz mitten
	 * im Schreiben nicht die Einstellungsdatei zerstört.
	 */
	private void write(AppSettings settings) {
		try {
			File tempFile = File.createTempFile("settings", ".tmp", settingsFile.getParentFile());
			try {
				objectMapper.writeValue(tempFile, settings);
				Files.move(tempFile.toPath(), settingsFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException | RuntimeException e) {
				tempFile.delete();
				throw e;
			}
		} catch (IOException e) {
			throw new RuntimeException("Fehler beim Speichern der Datei: " + FILE_NAME, e);
		}
	}

	private static void notifyListeners(List<Consumer<AppSettings>> targets, AppSettings settings, String when) {
		for (Consumer<AppSettings> listener : new ArrayList<>(targets)) {
			try {
				listener.accept(settings);
			} catch (RuntimeException e) {
				// Ein fehlerhafter Listener darf weder das Speichern scheitern lassen noch die übrigen Listener verhindern.
				LOGGER.log(Level.SEVERE, "Fehler in einem Listener " + when + " der Einstellungen", e);
			}
		}
	}

	/** Eine unabhängige Kopie, über dieselbe Umwandlung wie beim Schreiben und Lesen der Datei (Jackson-Rundreise). */
	private AppSettings copyOf(AppSettings settings) {
		try {
			return objectMapper.readValue(objectMapper.writeValueAsBytes(settings), AppSettings.class);
		} catch (IOException e) {
			throw new IllegalStateException("Einstellungen konnten nicht kopiert werden", e);
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

	/**
	 * Legt die defekte Datei beiseite (statt sie zu löschen oder beim nächsten Speichern zu überschreiben) und
	 * sammelt dazu genau eine Warnung. Lässt sie sich nicht umbenennen, bleibt sie liegen und wird beim nächsten
	 * Speichern überschrieben; die Warnung sagt das ausdrücklich.
	 */
	private void setAside(IOException cause) {
		File renamed = new File(settingsFile.getParentFile(), FILE_NAME + ".defekt-" + System.currentTimeMillis() + ".bak");
		if (settingsFile.renameTo(renamed)) {
			loadWarnings.add("Die Einstellungsdatei '" + FILE_NAME + "' konnte nicht gelesen werden. Sie wurde als '"
					+ renamed.getName() + "' beiseitegelegt, es gelten die Standardwerte. Ursache: "
					+ firstLine(cause.getMessage()));
		} else {
			loadWarnings.add("Die Einstellungsdatei '" + FILE_NAME + "' konnte nicht gelesen werden und auch nicht"
					+ " beiseitegelegt werden. Es gelten die Standardwerte; die defekte Datei wird beim nächsten"
					+ " Speichern überschrieben. Ursache: " + firstLine(cause.getMessage()));
		}
	}

	/**
	 * Nur die erste Zeile einer Fehlermeldung: Jackson hängt dahinter den Quelltext-Ausschnitt der Datei an, der
	 * in einer Warnung für Anwender nur stört.
	 */
	private static String firstLine(String message) {
		if (message == null || message.isBlank()) {
			return "unbekannt";
		}
		return message.strip().lines().findFirst().orElse("unbekannt").strip();
	}
}
