package de.eltviller_carneval_verein.karten.repository;

import de.eltviller_carneval_verein.karten.validation.EntityLabels;

/**
 * Eine Einheit des Versionsspeichers ist gesperrt (beschädigt oder mit einer neueren Programmversion gespeichert) und
 * lässt sich nicht ändern. Die Meldung ist für den Nutzer bestimmt.
 */
public class LockedUnitException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public LockedUnitException(String message) {
		super(message);
	}

	/** Ein anderer Rechner hat das Event bzw. die Halle inzwischen ersetzt oder gelöscht: Das alte Objekt zu speichern überschriebe seinen Stand. */
	static LockedUnitException replacedMeanwhile(String name) {
		return new LockedUnitException("„" + EntityLabels.nameOrPlaceholder(name)
				+ "“ wurde inzwischen auf einem anderen Rechner geändert. Bitte die Ansicht neu öffnen.");
	}
}
