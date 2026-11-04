package de.eltviller_carneval_verein.karten.store;

import java.io.IOException;

/**
 * Die Nummer der nächsten Änderungsliste ist im gemeinsamen Ordner schon belegt, obwohl dieser Programmlauf sie nicht
 * geschrieben hat: Ein anderer Rechner verwendet dieselbe Rechner-Kennung (wahrscheinlich eine kopierte Installation).
 */
public class DuplicateMachineException extends IOException {

	private static final long serialVersionUID = 1L;

	/**
	 * @param machineId die Kennung des Rechners
	 * @param seq       die belegte Nummer
	 */
	public DuplicateMachineException(String machineId, long seq) {
		super("Die Änderungsliste " + seq + " des Rechners " + machineId + " gibt es schon im gemeinsamen Ordner.");
	}
}
