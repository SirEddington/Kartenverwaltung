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
 * <p>
 * Die erste Einstellung ist {@link #getMirrorDir() mirrorDir}, der Basisordner der Spiegelung (leer oder
 * {@code null} heißt: keine Spiegelung). Sie ändert sich nur durch den Nutzer in der Einstellungsansicht, nie durch
 * die Anwendung selbst, damit der {@code ChangeTracker} sauber bleibt. {@code NON_EMPTY} sorgt dafür, dass ein leerer
 * Wert nicht in die Datei kommt und ein unberührtes Objekt weiter als {@code {}} erscheint.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AppSettings {

	private String mirrorDir;

	public AppSettings() {
	}

	/**
	 * Der Basisordner der Spiegelung: Darin liegen {@code events_data} und {@code halls_data} wie im Standardordner.
	 * Die Anwendung arbeitet weiter im Standardordner, der Spiegelordner ist eine zusätzliche Kopie. {@code null}
	 * oder leer heißt: keine Spiegelung. Leere Werte werden nicht gespeichert, damit ein unberührtes Objekt weiter
	 * als {@code {}} erscheint.
	 */
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	public String getMirrorDir() {
		return mirrorDir;
	}

	public void setMirrorDir(String mirrorDir) {
		this.mirrorDir = mirrorDir;
	}
}
