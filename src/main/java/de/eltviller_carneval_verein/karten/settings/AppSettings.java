package de.eltviller_carneval_verein.karten.settings;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Die Einstellungen der Anwendung, wie sie in {@code settings.json} stehen. Jede Einstellung ist ein Feld mit
 * Standardwert: Fehlt sie in der Datei, gilt der Standardwert, und eine Datei aus einer neueren Version mit
 * unbekannten Feldern lässt sich trotzdem lesen. Ein Folgeticket, das eine Einstellung ergänzt, braucht
 * deshalb keine Migration.
 * <p>
 * Ein Objekt ohne gesetzte Einstellung wird als leeres JSON-Objekt gespeichert.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AppSettings {

	private String mirrorDir;

	public AppSettings() {
	}

	/**
	 * Der Spiegelordner, in den die Daten zusätzlich geschrieben werden. {@code null} oder leer heißt: keine
	 * Spiegelung. Leere Werte werden nicht gespeichert, damit ein unberührtes Objekt weiter als {@code {}} erscheint.
	 */
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	public String getMirrorDir() {
		return mirrorDir;
	}

	public void setMirrorDir(String mirrorDir) {
		this.mirrorDir = mirrorDir;
	}
}
