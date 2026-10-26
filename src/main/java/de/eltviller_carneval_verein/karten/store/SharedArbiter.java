package de.eltviller_carneval_verein.karten.store;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import de.eltviller_carneval_verein.karten.store.UnitState.Status;

/**
 * Der Schiedsrichter im gemeinsamen Betrieb: "Wer zuerst hochlädt, gewinnt." Der Anspruch entsteht im gemeinsamen
 * Ordner; das Ergebnis wird in den lokalen Bestand übernommen.
 * <p>
 * Eine Version darf nur hinter einem Vorgänger beansprucht werden, der in der gültigen Kette der Einheit im gemeinsamen
 * Ordner liegt. Steht der Vorgänger außerhalb der Kette, bekommt die Version keine Anspruchsdatei, sondern gilt ebenfalls
 * als verloren; der Gewinner ist die Version, die den Platz des ältesten Vorfahren außerhalb der Kette belegt (laut
 * dessen Anspruchsdatei, auch wenn es eine beschädigte, bei der Heilung übersprungene Version ist). Als beansprucht
 * gilt auch eine solche übersprungene Version: Ein Anspruch hinter ihr wird wie jeder andere beurteilt.
 * <p>
 * Ist der erste Vorgänger beansprucht, entscheidet allein die Anspruchsdatei, auch wenn die Kette an ihm gesperrt endet
 * (beschädigte Version: so entsteht der Anspruch beim Zurücksetzen). Nur wenn er es nicht ist, wird der Rückweg zur Kette
 * gebraucht; lässt sich die Kette dafür nicht sicher bestimmen (nicht {@code OK}) oder sind die Vorfahren noch nicht
 * beansprucht, wirft der Anspruch eine {@link IOException}: Ein späterer Versuch entscheidet.
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
			ClaimOutcome parentOutside = checkParent(unit, version, parent);
			if (parentOutside != null) {
				return parentOutside;
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
	 * Prüft anhand der gültigen Kette im gemeinsamen Ordner, dass der erste Vorgänger darin beansprucht ist.
	 *
	 * @return {@code null}, wenn der Vorgänger beansprucht ist (in der Kette, als übersprungene beschädigte Version oder
	 *         als Version, an der die Kette gesperrt endet; dann gilt das unabhängig vom Status); sonst die Niederlage mit
	 *         dem Anspruch, der den Platz seines ältesten Vorfahren außerhalb der Kette belegt
	 * @throws IOException wenn der Vorgänger nicht beansprucht ist und die Kette nicht bestimmt werden kann (nicht
	 *                     {@code OK}), eine Versionsdatei auf dem Weg zur Kette fehlt oder unlesbar ist oder die Vorfahren
	 *                     noch nicht beansprucht sind
	 */
	private ClaimOutcome checkParent(UnitKey unit, Version version, VersionId parent) throws IOException {
		UnitFiles files = shared.readUnit(unit);
		UnitState state = ChainResolver.resolve(files, version.machine());
		String where = unit.relativePath();
		// Beansprucht ist auch eine beschädigte, übersprungene Version: Sie hat ihren Platz bekommen und steht nur nicht in
		// der Kette (Heilung Stufe 2). Ein Anspruch dahinter wird wie jeder andere beurteilt, auch wenn die Kette gerade an
		// ihr endet (gesperrt): So entsteht der Anspruch hinter der beschädigten Version beim Zurücksetzen.
		Set<VersionId> claimed = state.claimed();
		if (claimed.contains(parent)) {
			return null;
		}
		if (state.status() != Status.OK) {
			throw new IOException("Die gültige Kette von " + where + " ist im gemeinsamen Ordner nicht bestimmbar ("
					+ state.status() + ").");
		}
		// Entlang des ersten Vorgängers bis zu einer beanspruchten Version oder zur ersten Version zurückgehen
		VersionId base = null;
		Set<VersionId> seen = new HashSet<>();
		VersionId current = parent;
		while (current != null) {
			if (claimed.contains(current)) {
				base = current;
				break;
			}
			VersionFile file = files.versions().get(current);
			if (file == null || file.version() == null || !seen.add(current)) {
				throw new IOException("Die Versionsdatei " + current + " (" + where + ") fehlt im gemeinsamen Ordner oder ist unbrauchbar.");
			}
			current = file.version().firstParent();
		}
		// Der Gewinner steht in der Anspruchsdatei des Platzes hinter der Basis (auch wenn dort eine übersprungene
		// beschädigte Version steht); fehlt sie, sind die Vorfahren noch nicht beansprucht.
		String slot = base == null ? "root" : base.value();
		ClaimFile slotFile = files.claims().get(slot);
		if (slotFile == null) {
			throw new IOException("Die Vorfahren von " + version.id() + " (" + where + ") sind im gemeinsamen Ordner noch nicht beansprucht.");
		}
		if (slotFile.claim() == null) {
			throw new IOException("Die Anspruchsdatei " + where + "/claims/" + slot + ".json ist nicht lesbar.");
		}
		copyLoss(unit, slot, slotFile.bytes(), slotFile.claim());
		return new ClaimOutcome(false, slotFile.claim());
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
