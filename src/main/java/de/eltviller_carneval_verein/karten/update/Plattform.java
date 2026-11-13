package de.eltviller_carneval_verein.karten.update;

/** Wie die laufende App installiert ist; bestimmt, welchen Update-Weg sie anbieten kann (KARV-131). */
public enum Plattform {
	/** MSIX-Paket mit Paketidentität: Update über den App Installer. */
	WINDOWS_PAKET,
	/** Windows ohne Paket (die ZIP-Version): nur Hinweis, Update von Hand. */
	WINDOWS_OHNE_PAKET,
	/** Linux (.deb oder Archiv): nur Hinweis. */
	LINUX,
	/** Jedes andere System: nur Hinweis. */
	SONSTIGE
}
