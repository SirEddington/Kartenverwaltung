package de.eltviller_carneval_verein.karten.settings;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Die Einstellungen der Anwendung, wie sie in {@code settings.json} stehen. Jede Einstellung ist ein Feld mit
 * Standardwert: Fehlt sie in der Datei, gilt der Standardwert, und eine Datei aus einer neueren Version mit
 * unbekannten Feldern lässt sich trotzdem lesen. Ein Folgeticket, das eine Einstellung ergänzt, braucht
 * deshalb keine Migration.
 * <p>
 * Zunächst gibt es noch keine Einstellungen, das Objekt wird als leeres JSON-Objekt gespeichert.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AppSettings {

	public AppSettings() {
	}
}
