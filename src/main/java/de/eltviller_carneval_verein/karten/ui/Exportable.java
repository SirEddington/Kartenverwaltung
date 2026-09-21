package de.eltviller_carneval_verein.karten.ui;

import java.io.File;
import java.io.IOException;

/**
 * Von Inhalts-Controllern implementiert, die sich über einen gemeinsamen
 * Export-Knopf auf Shell-Ebene exportieren lassen (z. B. Kassenabgleich und
 * Statistik in der Abrechnung). Die Shell kümmert sich um Dialog, Format-Wahl
 * und das Merken der zuletzt genutzten Methode; der Inhalt weiß nur, wie er
 * sich selbst in eine Datei schreibt.
 */
public interface Exportable {

	void exportCsv(File file) throws IOException;

	void exportPdf(File file) throws IOException;

	/** Basisname für den Dateiauswahl-Dialog, ohne Datum/Endung. */
	String suggestedFileBaseName();

	/** Ob im aktuellen Zustand überhaupt etwas Sinnvolles exportiert werden kann. */
	default boolean canExport() {
		return true;
	}

	static String sanitizeFileName(String name) {
		return name.replaceAll("[^a-zA-Z0-9._-]", "_");
	}
}
