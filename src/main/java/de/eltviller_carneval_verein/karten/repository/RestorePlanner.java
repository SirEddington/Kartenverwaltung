package de.eltviller_carneval_verein.karten.repository;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.node.ObjectNode;

import de.eltviller_carneval_verein.karten.store.SyncService;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.UnitSnapshot;
import de.eltviller_carneval_verein.karten.store.UnitState;
import de.eltviller_carneval_verein.karten.store.UnitState.Status;
import de.eltviller_carneval_verein.karten.store.UnitType;
import de.eltviller_carneval_verein.karten.store.Version;

/**
 * Die Rechnung hinter dem Wiederherstellen: aus den gültigen Ketten der Einheiten eines Events bzw. einer Halle die
 * Speicherstände und, zu einem Speicherstand, der Zielstand jeder Einheit. Gespeichert wird hier nichts; das
 * Wiederherstellen selbst ist das normale Speichern des Zielstands. Verlorene Versionen zählen nie, denn es zählt nur
 * die gültige Kette ({@link UnitState#chain()}).
 */
final class RestorePlanner {

	private static final Logger LOG = Logger.getLogger(RestorePlanner.class.getName());

	private static final Comparator<KnownEntity> BY_NAME = Comparator
			.comparing(KnownEntity::name, String.CASE_INSENSITIVE_ORDER).thenComparing(KnownEntity::id);

	private RestorePlanner() {
	}

	/**
	 * Die Speicherstände aus den Versionen der gültigen Ketten, neueste zuerst (bei gleichem Zeitpunkt nach
	 * {@code saveId}). Je {@code saveId} ein Speicherstand: Zeitpunkt, Rechner und Bediener der spätesten Version,
	 * dazu die Zahl der Einheiten, die der Speichervorgang geschrieben hat.
	 */
	static List<SavePoint> savePoints(Collection<UnitState> states) {
		Map<String, Version> latest = new LinkedHashMap<>();
		Map<String, Set<UnitKey>> units = new HashMap<>();
		for (UnitState state : states) {
			for (Version version : state.chain()) {
				latest.merge(version.saveId(), version, (a, b) -> isLater(b, a) ? b : a);
				units.computeIfAbsent(version.saveId(), k -> new HashSet<>()).add(state.unit());
			}
		}
		List<SavePoint> result = new ArrayList<>();
		for (Map.Entry<String, Version> entry : latest.entrySet()) {
			Version version = entry.getValue();
			result.add(new SavePoint(entry.getKey(), version.at(), version.machineName(), version.operator(),
					units.get(entry.getKey()).size()));
		}
		result.sort(Comparator.comparing(SavePoint::at).reversed().thenComparing(SavePoint::saveId));
		return result;
	}

	/** Ob {@code candidate} später ist als {@code current}; bei gleichem Zeitpunkt entscheidet die Versions-ID. */
	private static boolean isLater(Version candidate, Version current) {
		int byTime = candidate.at().compareTo(current.at());
		return byTime != 0 ? byTime > 0 : candidate.id().compareTo(current.id()) > 0;
	}

	/**
	 * Der Zielstand je Einheit zum Zeitpunkt {@code t}: das letzte Glied der gültigen Kette (nach Position) mit
	 * {@code at <= t}, als Stand mit Zustand {@code OK}. Hat die Einheit kein solches Glied (sie entstand erst nach
	 * {@code t}), fehlt sie im Ergebnis. Ein Zielstand kann auch "gelöscht" sein.
	 */
	static Map<UnitKey, UnitSnapshot> targetsAt(Collection<UnitState> states, Instant t) {
		Map<UnitKey, UnitSnapshot> targets = new LinkedHashMap<>();
		for (UnitState state : states) {
			List<Version> chain = state.chain();
			for (int i = chain.size() - 1; i >= 0; i--) {
				Version version = chain.get(i);
				if (!version.at().isAfter(t)) {
					targets.put(state.unit(),
							new UnitSnapshot(state.unit(), version.id(), Status.OK, version.deleted(), version.payload()));
					break;
				}
			}
		}
		return targets;
	}

	/**
	 * Eine gesperrte oder wartende Einheit lässt sich nicht wiederherstellen. Die Texte sind die von
	 * {@link UnitMapper#requireWritable}.
	 *
	 * @throws LockedUnitException wenn eine der Einheiten nicht den Zustand {@code OK} hat
	 */
	static void requireRestorable(Collection<UnitState> states) {
		for (UnitState state : states) {
			UnitMapper.requireWritable(new UnitSnapshot(state.unit(), null, state.status(), false, null));
		}
	}

	/**
	 * Die Events bzw. Hallen zu den Köpfen im Vergleichsstand des Repositorys, nach Name (ohne Rücksicht auf
	 * Groß-/Kleinschreibung), dann nach ID. Der Name eines gelöschten Kopfes stammt aus der letzten nicht gelöschten
	 * Version seiner Kette, sonst gilt die ID.
	 */
	static List<KnownEntity> knownEntities(SyncService sync, Collection<UnitSnapshot> heads) {
		List<KnownEntity> result = new ArrayList<>();
		for (UnitSnapshot head : heads) {
			if (head.version() == null && head.status() == Status.OK) {
				continue;
			}
			String id = idOf(head.unit());
			String name = head.deleted() ? lastLiveName(sync, head.unit()) : nameOf(head.payload());
			result.add(new KnownEntity(id, name != null ? name : id, head.deleted()));
		}
		result.sort(BY_NAME);
		return result;
	}

	private static String idOf(UnitKey unit) {
		return unit.type() == UnitType.HALL ? unit.hallId() : unit.eventId();
	}

	private static String lastLiveName(SyncService sync, UnitKey unit) {
		try {
			List<Version> chain = sync.unitState(unit).chain();
			for (int i = chain.size() - 1; i >= 0; i--) {
				if (!chain.get(i).deleted()) {
					return nameOf(chain.get(i).payload());
				}
			}
		} catch (IOException e) {
			LOG.log(Level.WARNING, "Der Name von " + unit.relativePath() + " ließ sich nicht lesen", e);
		}
		return null;
	}

	private static String nameOf(ObjectNode payload) {
		if (payload == null) {
			return null;
		}
		String name = payload.path("name").asText("");
		return name.isBlank() ? null : name;
	}
}
