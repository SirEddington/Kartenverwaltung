package de.eltviller_carneval_verein.karten.store;

import java.io.IOException;

/**
 * Der Schiedsrichter über den Platz hinter einem Vorgänger: Wer die Anspruchsdatei zuerst anlegt, gewinnt.
 */
public interface Arbiter {

	/**
	 * Beansprucht den Platz hinter dem ersten Vorgänger der Version ({@code root} bei der ersten Version).
	 *
	 * @param unit         die Einheit der Version
	 * @param version      die Version
	 * @param versionBytes die Bytes der Versionsdatei, wie sie geschrieben wurden (aus ihnen entsteht der Prüfwert)
	 * @return gewonnen oder verloren, mit der Anspruchsdatei, die den Platz belegt
	 * @throws IOException wenn der Ausgang wegen einer Störung oder eines uneindeutigen Zustands nicht feststeht; der
	 *                     Anspruch kann später wiederholt werden
	 */
	ClaimOutcome claim(UnitKey unit, Version version, byte[] versionBytes) throws IOException;
}
