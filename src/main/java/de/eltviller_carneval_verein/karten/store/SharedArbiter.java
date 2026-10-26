package de.eltviller_carneval_verein.karten.store;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Der Schiedsrichter im gemeinsamen Betrieb: "Wer zuerst hochlädt, gewinnt." Der Anspruch entsteht im gemeinsamen
 * Ordner; das Ergebnis wird in den lokalen Bestand übernommen.
 * <p>
 * Eine Version darf nur hinter einem Vorgänger beansprucht werden, der selbst gültig beansprucht ist. Hat der Vorgänger
 * den Platz hinter seinem eigenen Vorgänger verloren, bekommt die Version keine Anspruchsdatei, sondern gilt ebenfalls
 * als verloren; der Gewinner ist die Version, auf die der Anspruch des Vorgängers zeigt.
 */
public final class SharedArbiter implements Arbiter {

	private final VersionStore shared;
	private final VersionStore local;
	private final Clock clock;

	/**
	 * @param shared der gemeinsame Bestand
	 * @param local  der lokale Bestand
	 * @param clock  die Uhr für den Zeitpunkt des Anspruchs
	 */
	public SharedArbiter(VersionStore shared, VersionStore local, Clock clock) {
		this.shared = shared;
		this.local = local;
		this.clock = clock;
	}

	@Override
	public ClaimOutcome claim(UnitKey unit, Version version, byte[] versionBytes) throws IOException {
		VersionId parent = version.firstParent();
		if (parent != null) {
			ClaimOutcome parentLost = checkParent(unit, parent);
			if (parentLost != null) {
				return parentLost;
			}
		}
		String name = version.claimName();
		String sha256 = StoreCodec.sha256(versionBytes);
		Claim own = new Claim(version.id(), sha256, version.machine(), Instant.now(clock));
		byte[] ownBytes = StoreCodec.write(own);
		if (shared.createClaim(unit, name, ownBytes)) {
			local.createClaim(unit, name, ownBytes);
			return new ClaimOutcome(true, own);
		}
		byte[] existing = shared.readClaimBytes(unit, name)
				.orElseThrow(() -> new IOException("Die Anspruchsdatei " + name + " fehlt trotz vorhandenem Namen."));
		ClaimOutcome outcome = ClaimOutcome.judge(unit, name, version, sha256, existing);
		if (outcome.won()) {
			local.createClaim(unit, name, existing);
		} else {
			copyLoss(unit, name, existing, outcome.claim());
		}
		return outcome;
	}

	/**
	 * Prüft, dass der erste Vorgänger gültig beansprucht ist.
	 *
	 * @return {@code null}, wenn der Vorgänger gültig ist; sonst die Niederlage mit dem Anspruch, der seinen Platz belegt
	 * @throws IOException wenn die Versionsdatei oder der Anspruch des Vorgängers fehlt oder unlesbar ist: Der Zustand ist
	 *                     uneindeutig, ein späterer Versuch oder ein Vollabgleich muss ihn klären
	 */
	private ClaimOutcome checkParent(UnitKey unit, VersionId parent) throws IOException {
		byte[] parentBytes = shared.readVersionBytes(unit, parent).orElseThrow(() -> new IOException(
				"Die Versionsdatei des Vorgängers " + parent + " fehlt im gemeinsamen Ordner (" + unit.relativePath() + ")."));
		String parentClaimName = StoreCodec.readVersion(parentBytes).claimName();
		byte[] claimBytes = shared.readClaimBytes(unit, parentClaimName).orElseThrow(() -> new IOException(
				"Der Vorgänger " + parent + " ist nicht beansprucht: " + unit.relativePath() + "/claims/" + parentClaimName
						+ ".json fehlt."));
		Claim parentClaim;
		try {
			parentClaim = StoreCodec.readClaim(claimBytes);
		} catch (IOException e) {
			throw new IOException("Die Anspruchsdatei " + unit.relativePath() + "/claims/" + parentClaimName
					+ ".json des Vorgängers ist nicht lesbar: " + e.getMessage(), e);
		}
		if (parentClaim.version().equals(parent)) {
			return null;
		}
		copyLoss(unit, parentClaimName, claimBytes, parentClaim);
		return new ClaimOutcome(false, parentClaim);
	}

	/** Übernimmt den Anspruch des Gewinners und, soweit im gemeinsamen Ordner vorhanden, dessen Versionsdatei. */
	private void copyLoss(UnitKey unit, String claimName, byte[] claimBytes, Claim winner) throws IOException {
		local.createClaim(unit, claimName, claimBytes);
		Optional<byte[]> winnerBytes = shared.readVersionBytes(unit, winner.version());
		if (winnerBytes.isPresent()) {
			local.writeVersion(unit, winner.version(), winnerBytes.get());
		}
	}
}
