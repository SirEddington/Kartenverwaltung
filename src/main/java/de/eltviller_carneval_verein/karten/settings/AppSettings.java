package de.eltviller_carneval_verein.karten.settings;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Die Einstellungen der Anwendung, wie sie in {@code settings.json} stehen. Jede Einstellung ist ein Feld mit
 * Standardwert: Fehlt sie in der Datei, gilt der Standardwert, und eine Datei aus einer neueren Version mit
 * unbekannten Feldern lässt sich trotzdem lesen. Ein Folgeticket, das eine Einstellung ergänzt, braucht
 * deshalb keine Migration.
 * <p>
 * Ein Objekt ohne gesetzte Einstellung wird als leeres JSON-Objekt gespeichert. Bisher gibt es keine Einstellung:
 * Der Spiegelordner ({@code mirrorDir}) ist mit KARV-89 entfallen, ein Feld dieses Namens in einer älteren
 * {@code settings.json} wird beim Lesen ignoriert. Die Annotation unten sorgt außerdem dafür, dass Jackson das leere
 * Objekt als {@code {}} schreibt, statt zu scheitern.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AppSettings {

	public AppSettings() {
	}
}
