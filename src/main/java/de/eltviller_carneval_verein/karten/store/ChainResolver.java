package de.eltviller_carneval_verein.karten.store;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.eltviller_carneval_verein.karten.store.UnitState.Status;

/**
 * Entscheidet aus den Dateien einer Einheit, welche Version gültig ist, welche eigenen Versionen noch auf ihren
 * Anspruch warten und welche verloren sind. Reine Logik ohne Dateizugriff.
 * <p>
 * Die gültige Kette beginnt bei {@code claims/root} und folgt den Anspruchsdateien: Die Anspruchsdatei eines Gliedes
 * nennt das nächste. Wer zuerst beansprucht, gewinnt; eigene Versionen außerhalb der Kette sind entweder
 * ausstehend (sie bauen auf dem Ende der Kette auf) oder verloren (sie bauen auf einem älteren Stand auf).
 */
public final class ChainResolver {

	private ChainResolver() {
	}

	/**
	 * Eine Strecke eigener Versionen außerhalb der Kette.
	 *
	 * @param versions die Versionen, älteste zuerst (jede baut mit ihrem ersten Vorgänger auf der davor auf)
	 * @param base     der erste Vorgänger der ältesten Version; {@code null}, wenn sie keinen hat
	 */
	private record Stretch(List<Version> versions, VersionId base) {
	}

	/**
	 * Ermittelt den gültigen Stand einer Einheit.
	 *
	 * @param files        die Versions- und Anspruchsdateien der Einheit
	 * @param ownMachineId die ID des eigenen Rechners; eigene Versionen sind die mit dieser {@code machine}
	 * @return der Zustand mit Kette, ausstehenden und verlorenen Versionen
	 */
	public static UnitState resolve(UnitFiles files, String ownMachineId) {
		Map<VersionId, VersionFile> versions = files.versions();
		Map<String, ClaimFile> claims = files.claims();

		List<Version> chain = new ArrayList<>();
		Set<VersionId> visited = new HashSet<>();
		Status status = Status.OK;
		VersionId blockedAt = null;

		String name = "root";
		while (claims.containsKey(name)) {
			Claim claim = claims.get(name).claim();
			if (claim == null) {
				status = Status.LOCKED_DAMAGED;
				break;
			}
			VersionId target = claim.version();
			if (!visited.add(target)) {
				status = Status.LOCKED_DAMAGED;
				blockedAt = target;
				break;
			}
			VersionFile file = versions.get(target);
			if (file == null) {
				status = Status.WAITING;
				blockedAt = target;
				break;
			}
			if (file.unknownFormat() != null) {
				status = Status.LOCKED_FORMAT;
				blockedAt = target;
				break;
			}
			boolean intact = file.version() != null && StoreCodec.sha256(file.bytes()).equals(claim.sha256());
			if (intact) {
				chain.add(file.version());
			} else if (!claims.containsKey(target.value())) {
				status = Status.LOCKED_DAMAGED;
				blockedAt = target;
				break;
			}
			// Beschädigte Version mit Nachfolger-Anspruch (Zurücksetzen nach Heilung Stufe 2): übersprungen.
			name = target.value();
		}

		VersionId headId = chain.isEmpty() ? null : chain.get(chain.size() - 1).id();
		List<Version> pending = new ArrayList<>();
		List<List<VersionId>> lost = new ArrayList<>();
		Set<VersionId> assigned = new HashSet<>();
		for (Stretch stretch : ownStretches(versions, visited, ownMachineId)) {
			// Auf der beschädigten Version, an der die Kette endet, baut das Zurücksetzen auf (Heilung Stufe 2): Ihr Platz ist
			// frei, die Strecke wartet aufs Beanspruchen.
			boolean isPending = status == Status.WAITING || buildsOnHead(stretch.base(), headId)
					|| (status == Status.LOCKED_DAMAGED && blockedAt != null && blockedAt.equals(stretch.base()));
			// Gehört eine Version zu mehreren Spitzen, steht sie nur in der ersten Strecke.
			List<Version> fresh = stretch.versions().stream().filter(v -> assigned.add(v.id())).toList();
			if (fresh.isEmpty()) {
				continue;
			}
			if (isPending) {
				pending.addAll(fresh);
			} else {
				lost.add(fresh.stream().map(Version::id).toList());
			}
		}
		return new UnitState(files.unit(), status, chain, pending, lost, blockedAt, visited);
	}

	/**
	 * Die Strecken eigener Versionen außerhalb der Kette: je Spitze (eigene lesbare Version, die weder beansprucht
	 * noch zusammengeführt ist noch von einer lesbaren Version als Vorgänger genannt wird) die Versionen zurück
	 * entlang des ersten Vorgängers, solange sie eigen, nicht beansprucht und nicht zusammengeführt sind. Die
	 * Strecken sind nach der ID ihrer ältesten Version geordnet.
	 * <p>
	 * Beansprucht ist jede Version, die eine Anspruchsdatei der Kette nennt (auch eine beschädigte oder übersprungene);
	 * sie hat ihren Platz bekommen und ist nie ausstehend oder verloren. Zusammengeführt ist eine Version, die eine
	 * lesbare Version als weiteren (nicht ersten) Vorgänger nennt; ihr Inhalt steckt schon in dieser Version und sie wird
	 * nicht noch einmal gemeldet. In beiden Fällen endet die Strecke dort, und diese Version ist ihre Basis.
	 *
	 * @param claimedIds die Versionen, die eine Anspruchsdatei der Kette nennt
	 */
	private static List<Stretch> ownStretches(Map<VersionId, VersionFile> versions, Set<VersionId> claimedIds,
			String ownMachineId) {
		Set<VersionId> named = new HashSet<>();
		Set<VersionId> merged = new HashSet<>();
		for (VersionFile file : versions.values()) {
			if (file.version() != null) {
				List<VersionId> parents = file.version().parents();
				named.addAll(parents);
				if (parents.size() > 1) {
					merged.addAll(parents.subList(1, parents.size()));
				}
			}
		}
		List<Stretch> stretches = new ArrayList<>();
		for (VersionFile file : versions.values()) {
			Version tip = file.version();
			if (tip == null || !isOwn(tip, ownMachineId) || claimedIds.contains(tip.id()) || merged.contains(tip.id())
					|| named.contains(tip.id())) {
				continue;
			}
			List<Version> stretch = new ArrayList<>();
			Set<VersionId> seen = new HashSet<>();
			Version current = tip;
			while (current != null && isOwn(current, ownMachineId) && !claimedIds.contains(current.id())
					&& !merged.contains(current.id()) && seen.add(current.id())) {
				stretch.add(0, current);
				VersionId parent = current.firstParent();
				VersionFile parentFile = parent == null ? null : versions.get(parent);
				current = parentFile == null ? null : parentFile.version();
			}
			stretches.add(new Stretch(stretch, stretch.get(0).firstParent()));
		}
		stretches.sort(Comparator.<Stretch, VersionId>comparing(s -> s.versions().get(0).id())
				.thenComparing(s -> s.versions().get(s.versions().size() - 1).id()));
		return stretches;
	}

	/**
	 * Ob eine Strecke mit dieser Basis auf dem Ende der Kette aufbaut: Die Basis ist das Ende der Kette, oder es gibt
	 * weder Basis noch Kette. Jede andere Basis (älteres Kettenglied, fehlende, fremde oder unlesbare Version) heißt,
	 * dass ein anderer vorher beansprucht hat.
	 */
	private static boolean buildsOnHead(VersionId base, VersionId headId) {
		return base == null ? headId == null : base.equals(headId);
	}

	private static boolean isOwn(Version version, String ownMachineId) {
		return ownMachineId.equals(version.machine());
	}
}
