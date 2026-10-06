package de.eltviller_carneval_verein.karten;

import java.io.File;

/** Feste Speicherorte der Anwendung, unabhängig vom Arbeitsverzeichnis. */
public final class AppPaths {

	private AppPaths() {
	}

	/**
	 * Basisverzeichnis für alle Anwendungsdaten (Events, Hallen, Logs) unter %LOCALAPPDATA%.
	 * Fällt außerhalb von Windows (z.B. beim Entwickeln) auf das Nutzerverzeichnis zurück.
	 */
	public static File appDataDir() {
		String localAppData = System.getenv("LOCALAPPDATA");
		return (localAppData != null && !localAppData.isBlank())
				? new File(localAppData, "EltvillerCarnevalVerein" + File.separator + "Kartenverwaltung")
				: new File(System.getProperty("user.home"), ".kartenverwaltung");
	}
}
