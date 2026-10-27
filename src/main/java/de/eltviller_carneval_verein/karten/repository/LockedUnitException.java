package de.eltviller_carneval_verein.karten.repository;

/**
 * Eine Einheit des Versionsspeichers ist gesperrt (beschädigt oder mit einer neueren Programmversion gespeichert) und
 * lässt sich nicht ändern. Die Meldung ist für den Nutzer bestimmt.
 */
public class LockedUnitException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public LockedUnitException(String message) {
		super(message);
	}
}
