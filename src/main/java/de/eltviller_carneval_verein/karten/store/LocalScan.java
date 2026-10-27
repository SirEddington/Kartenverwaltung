package de.eltviller_carneval_verein.karten.store;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.eltviller_carneval_verein.karten.store.UnitState.Status;

/**
 * Was der {@link SyncService} aus dem lokalen Bestand ableitet, statt es sich zu merken: die ausstehenden eigenen
 * Versionen als Aufträge, die verlorenen eigenen Versionen mit ihrem Gewinner und die Stelle, an der eine gesperrte
 * Kette endet. Weil alles aus den Dateien kommt, übersteht es jeden Absturz.
 */
final class LocalScan {

	private static final String ROOT = "root";

	/**
	 * Ein eigener Anspruch, der auf eine späte Niederlage geprüft wird.
	 *
	 * @param at seit wann er beobachtet wird (Zeitpunkt des Anspruchs oder des Hochladens)
	 */
	record OwnClaim(UnitKey unit, String claimName, VersionId version, Instant at) {
	}

	private LocalScan() {
	}

	/** Alle lesbaren Anspruchsdateien des lokalen Bestands, die dieser Rechner geschrieben hat. */
	static List<OwnClaim> ownClaims(VersionStore local, String ownMachineId) throws IOException {
		List<OwnClaim> own = new ArrayList<>();
		for (UnitKey unit : local.allUnits()) {
			for (ClaimFile file : local.readUnit(unit).claims().values()) {
				Claim claim = file.claim();
				if (claim != null && ownMachineId.equals(claim.machine())) {
					own.add(new OwnClaim(unit, file.name(), claim.version(), claim.at()));
				}
			}
		}
		return own;
	}

	/**
	 * Die eigenen Anspruchsdateien, die jünger sind als {@code window}: nach einem Neustart weiter auf eine späte
	 * Niederlage zu prüfen.
	 */
	static List<OwnClaim> recentOwnClaims(VersionStore local, String ownMachineId, Instant now, Duration window)
			throws IOException {
		Instant since = now.minus(window);
		return ownClaims(local, ownMachineId).stream().filter(claim -> claim.at().isAfter(since)).toList();
	}

	/**
	 * Die ausstehenden eigenen Versionen der genannten Einheiten als Aufträge: je {@code saveId} ein Auftrag, die Aufträge
	 * nach ihrer kleinsten Versions-ID geordnet. Innerhalb eines Auftrags bleibt je Einheit die Reihenfolge aus
	 * {@link UnitState#pending()} (Vorgänger zuerst, nicht nach ID, weil Uhren springen können); die Einheiten stehen
	 * nach ihrer kleinsten Versions-ID. Jede Einheit wird aus den Dateien aufgelöst; eine Auswahl (etwa die Kandidaten aus
	 * dem Start-Index) spart nur das Lesen der übrigen.
	 *
	 * @param skip  Versionen, die schon in einem Auftrag stehen; sie kommen nicht noch einmal vor
	 * @param units die Einheiten, die untersucht werden (alle: {@link VersionStore#allUnits()})
	 */
	static List<UploadJob> pendingJobs(VersionStore local, String ownMachineId, Set<VersionId> skip,
			Collection<UnitKey> units) throws IOException {
		Map<String, Map<UnitKey, List<UploadJob.Item>>> groups = new LinkedHashMap<>();
		for (UnitKey unit : units) {
			UnitFiles files = local.readUnit(unit);
			for (Version version : ChainResolver.resolve(files, ownMachineId).pending()) {
				if (skip.contains(version.id())) {
					continue;
				}
				byte[] bytes = files.versions().get(version.id()).bytes();
				groups.computeIfAbsent(version.saveId(), id -> new LinkedHashMap<>())
						.computeIfAbsent(unit, u -> new ArrayList<>()).add(new UploadJob.Item(unit, version, bytes));
			}
		}
		List<UploadJob> jobs = new ArrayList<>();
		for (Map.Entry<String, Map<UnitKey, List<UploadJob.Item>>> group : groups.entrySet()) {
			List<List<UploadJob.Item>> perUnit = new ArrayList<>(group.getValue().values());
			perUnit.sort(Comparator.comparing(LocalScan::smallestId));
			List<UploadJob.Item> items = new ArrayList<>();
			perUnit.forEach(items::addAll);
			jobs.add(new UploadJob(group.getKey(), items));
		}
		jobs.sort(Comparator.comparing(job -> smallestId(job.items())));
		return jobs;
	}

	private static VersionId smallestId(List<UploadJob.Item> items) {
		return items.stream().map(item -> item.version().id()).min(Comparator.naturalOrder()).orElseThrow();
	}

	/**
	 * Die verlorenen Strecken eigener Versionen mit ihrem Gewinner. Nur bei einer bestimmbaren Kette ({@code OK}); eine
	 * Strecke, deren Gewinner sich nicht bestimmen lässt, fehlt.
	 */
	static List<LostVersion> losses(UnitFiles files, UnitState state) {
		List<LostVersion> losses = new ArrayList<>();
		if (state.status() != Status.OK) {
			return losses;
		}
		for (List<VersionId> stretch : state.lost()) {
			VersionId winner = winner(files, state, stretch.get(0));
			if (winner != null) {
				losses.add(new LostVersion(files.unit(), stretch, winner));
			}
		}
		return losses;
	}

	/**
	 * Der Gewinner gegen eine verlorene Version: die Version in der Anspruchsdatei des Platzes, um den sie sich beworben
	 * hat. Das ist {@code claims/<erster Vorgänger | root>}; liegt der Vorgänger selbst außerhalb der Kette (er hat
	 * ebenfalls verloren), zählt der Platz hinter dem nächsten beanspruchten Vorfahren, wie bei {@link SharedArbiter}.
	 *
	 * @return die Gewinner-Version; {@code null}, wenn ein Vorfahr fehlt oder die Anspruchsdatei fehlt oder unlesbar ist
	 */
	private static VersionId winner(UnitFiles files, UnitState state, VersionId first) {
		VersionFile firstFile = files.versions().get(first);
		VersionId current = firstFile == null || firstFile.version() == null ? null : firstFile.version().firstParent();
		Set<VersionId> seen = new HashSet<>();
		while (current != null && !state.claimed().contains(current)) {
			VersionFile file = files.versions().get(current);
			if (file == null || file.version() == null || !seen.add(current)) {
				return null;
			}
			current = file.version().firstParent();
		}
		ClaimFile slot = files.claims().get(current == null ? ROOT : current.value());
		return slot == null || slot.claim() == null ? null : slot.claim().version();
	}

	/**
	 * Der Name der Anspruchsdatei, an der die Kette endet, auf demselben Weg wie {@link ChainResolver}: bei einer
	 * unlesbaren Anspruchsdatei sie selbst, bei einer beschädigten, fehlenden oder wiederholten Version die
	 * Anspruchsdatei, die sie nennt. Bei einer vollständigen Kette ist es der Platz hinter ihrem Ende.
	 */
	static String stopSlot(UnitFiles files) {
		String name = ROOT;
		Set<VersionId> seen = new HashSet<>();
		while (true) {
			ClaimFile claimFile = files.claims().get(name);
			if (claimFile == null || claimFile.claim() == null) {
				return name;
			}
			VersionId target = claimFile.claim().version();
			VersionFile file = files.versions().get(target);
			if (!seen.add(target) || file == null || file.unknownFormat() != null) {
				return name;
			}
			boolean intact = file.version() != null && StoreCodec.sha256(file.bytes()).equals(claimFile.claim().sha256());
			if (!intact && !files.claims().containsKey(target.value())) {
				return name;
			}
			name = target.value();
		}
	}
}
