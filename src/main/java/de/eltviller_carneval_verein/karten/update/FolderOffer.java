package de.eltviller_carneval_verein.karten.update;

import java.nio.file.Path;
import java.time.Instant;

/** Ein geprüfter Installer im gemeinsamen Ordner, den die App anbieten kann (KARV-132). */
public interface FolderOffer {

	/** Die {@code .msix} im Ordner {@code updates/}. */
	Path file();

	/** Die Version aus dem Manifest. */
	AppVersion version();

	/** Wann diese Kasse die Datei (mit dieser Größe und Änderungszeit) zuerst sah. */
	Instant firstSeen();

	/**
	 * Prüft direkt vor dem Öffnen erneut: unverändert seit der Prüfung → gültig ohne Lesen; sonst volle Prüfung. Wirft nie;
	 * bei jedem Fehler {@code false}. Liest die Datei, also nicht auf dem FX-Thread aufrufen.
	 */
	boolean stillValid();
}
