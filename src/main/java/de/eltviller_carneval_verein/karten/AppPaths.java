package de.eltviller_carneval_verein.karten;

import java.io.File;

/** Feste Speicherorte der Anwendung, unabhängig vom Arbeitsverzeichnis. */
public final class AppPaths {

	private AppPaths() {
	}

	/** Name der Systemeigenschaft, die den Datenordner festlegt (nur für Handtests und Entwickler). */
	static final String APP_DATA_PROPERTY = "karten.appDataDir";

	/**
	 * Basisverzeichnis für alle Anwendungsdaten (Events, Hallen, Logs) unter %LOCALAPPDATA%.
	 * Fällt außerhalb von Windows (z.B. beim Entwickeln) auf das Nutzerverzeichnis zurück.
	 * <p>
	 * Ist die Systemeigenschaft {@code karten.appDataDir} gesetzt und nicht leer, gilt dieser Ordner stattdessen. Das dient
	 * nur dem Handtest mit zwei Instanzen auf einem Rechner (je eigener Datenordner, damit eigene Sperre, eigene
	 * Rechner-Kennung und eigene Einstellungen samt gemeinsamem Ordner).
	 */
	public static File appDataDir() {
		String override = System.getProperty(APP_DATA_PROPERTY);
		if (override != null && !override.isBlank()) {
			return new File(override);
		}
		String localAppData = System.getenv("LOCALAPPDATA");
		return (localAppData != null && !localAppData.isBlank())
				? new File(localAppData, "EltvillerCarnevalVerein" + File.separator + "Kartenverwaltung")
				: new File(System.getProperty("user.home"), ".kartenverwaltung");
	}
}
