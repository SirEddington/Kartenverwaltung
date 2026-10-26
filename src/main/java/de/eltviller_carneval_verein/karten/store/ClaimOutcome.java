package de.eltviller_carneval_verein.karten.store;

import java.io.IOException;

/**
 * Der Ausgang eines Anspruchs auf den Platz hinter einem Vorgänger.
 *
 * @param won   {@code true}, wenn die eigene Version den Platz belegt
 * @param claim die Anspruchsdatei, die den Platz belegt: bei einem Sieg die eigene, bei einer Niederlage die des Gewinners
 *              ({@link Claim#version()} nennt dann die Gewinner-Version)
 */
public record ClaimOutcome(boolean won, Claim claim) {

	/**
	 * Beurteilt eine vorhandene Anspruchsdatei. Sie zählt als Sieg, wenn sie auf die eigene Version zeigt und deren
	 * Prüfwert stimmt (ein früherer Versuch derselben Version, der nach dem Anspruch abbrach); jede andere ist eine
	 * Niederlage.
	 *
	 * @throws IOException wenn die vorhandene Anspruchsdatei nicht gelesen werden kann: Der Gewinner steht dann nicht fest
	 */
	static ClaimOutcome judge(UnitKey unit, String claimName, Version version, String sha256, byte[] existingBytes)
			throws IOException {
		Claim existing;
		try {
			existing = StoreCodec.readClaim(existingBytes);
		} catch (IOException e) {
			throw new IOException("Die vorhandene Anspruchsdatei " + unit.relativePath() + "/claims/" + claimName
					+ ".json ist nicht lesbar: " + e.getMessage(), e);
		}
		boolean own = existing.version().equals(version.id()) && existing.sha256().equals(sha256);
		return new ClaimOutcome(own, existing);
	}
}
