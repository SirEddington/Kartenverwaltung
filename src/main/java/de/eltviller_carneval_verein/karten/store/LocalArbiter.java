package de.eltviller_carneval_verein.karten.store;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;

/**
 * Der Schiedsrichter im Einzelbetrieb: Der Anspruch entsteht im lokalen Bestand. Der Vorgänger wird nicht geprüft.
 */
public final class LocalArbiter implements Arbiter {

	private final VersionStore local;
	private final Clock clock;

	/**
	 * @param local der lokale Bestand
	 * @param clock die Uhr für den Zeitpunkt des Anspruchs
	 */
	public LocalArbiter(VersionStore local, Clock clock) {
		this.local = local;
		this.clock = clock;
	}

	@Override
	public ClaimOutcome claim(UnitKey unit, Version version, byte[] versionBytes) throws IOException {
		String name = version.claimName();
		String sha256 = StoreCodec.sha256(versionBytes);
		Claim own = new Claim(version.id(), sha256, version.machine(), Instant.now(clock));
		if (local.createClaim(unit, name, StoreCodec.write(own))) {
			return new ClaimOutcome(true, own);
		}
		byte[] existing = local.readClaimBytes(unit, name)
				.orElseThrow(() -> new IOException("Die Anspruchsdatei " + name + " fehlt trotz vorhandenem Namen."));
		return ClaimOutcome.judge(unit, name, version, sha256, existing);
	}
}
