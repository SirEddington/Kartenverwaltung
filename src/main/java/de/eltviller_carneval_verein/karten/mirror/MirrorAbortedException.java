package de.eltviller_carneval_verein.karten.mirror;

import java.io.IOException;

/**
 * Der Abgleich wurde abgebrochen, bevor etwas geändert wurde, weil er im Spiegelordner Daten gelöscht hätte,
 * ohne dass lokal Daten vorhanden sind (siehe {@link MirrorSync#reconcile(java.nio.file.Path, java.nio.file.Path)}).
 * Ein {@link IOException}, damit sie wie jeder andere Fehler des Abgleichs behandelt werden kann; wer sie
 * gesondert melden will (SYS-004), fängt sie vorher ab.
 */
public class MirrorAbortedException extends IOException {

	private static final long serialVersionUID = 1L;

	public MirrorAbortedException(String message) {
		super(message);
	}
}
