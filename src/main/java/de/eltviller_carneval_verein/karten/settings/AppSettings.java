package de.eltviller_carneval_verein.karten.settings;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Die Einstellungen der Anwendung, wie sie in {@code settings.json} stehen. Jede Einstellung ist ein Feld mit
 * Standardwert: Fehlt sie in der Datei, gilt der Standardwert, und eine Datei aus einer neueren Version mit
 * unbekannten Feldern lässt sich trotzdem lesen. Ein Folgeticket, das eine Einstellung ergänzt, braucht
 * deshalb keine Migration.
 * <p>
 * Die Einstellungen sind der gemeinsame Ordner ({@code sharedDir}, {@code null} heißt Einzelbetrieb), ob Buchungen
 * sofort gespeichert werden ({@code saveBookingsImmediately}, Standard {@code true}) und die zuletzt angemeldeten Bediener
 * ({@code recentOperators}, nie {@code null}). Der Spiegelordner ({@code mirrorDir}) ist mit KARV-89 entfallen, ein
 * Feld dieses Namens in einer älteren {@code settings.json} wird beim Lesen ignoriert.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AppSettings {

	private String sharedDir;
	private boolean saveBookingsImmediately = true;
	private List<String> recentOperators = new ArrayList<>();

	public AppSettings() {
	}

	/** Der gemeinsame Ordner, so wie er eingegeben wurde; {@code null} heißt Einzelbetrieb. */
	public String getSharedDir() {
		return sharedDir;
	}

	public void setSharedDir(String sharedDir) {
		this.sharedDir = sharedDir;
	}

	/** Ob jede Änderung an einer Buchung sofort gespeichert wird; sonst gilt sie erst nach "Speichern". */
	public boolean isSaveBookingsImmediately() {
		return saveBookingsImmediately;
	}

	public void setSaveBookingsImmediately(boolean saveBookingsImmediately) {
		this.saveBookingsImmediately = saveBookingsImmediately;
	}

	/** Die zuletzt verwendeten Bediener, der letzte zuerst; nie {@code null}. */
	public List<String> getRecentOperators() {
		return recentOperators;
	}

	/** {@code null} (etwa {@code "recentOperators": null} in der Datei) gilt als leere Liste. */
	public void setRecentOperators(List<String> recentOperators) {
		this.recentOperators = recentOperators == null ? new ArrayList<>() : new ArrayList<>(recentOperators);
	}
}
