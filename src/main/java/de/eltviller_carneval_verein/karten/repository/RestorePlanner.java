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

import de.eltviller_carneval_verein.karten.store.ChainStep;
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
	 * Die Speicherstände aus den Gliedern der gültigen Ketten, neueste zuerst (bei gleichem Zeitpunkt nach
	 * {@code saveId}). Je {@code saveId} ein Speicherstand: Zeitpunkt, Rechner und Bediener des spätesten Glieds,
	 * dazu die Zahl der Einheiten, die der Speichervorgang geschrieben hat.
	 *
	 * @param chains je Einheit ihre gültige Kette, ältestes Glied zuerst
	 */
	static List<SavePoint> savePoints(Collection<List<ChainStep>> chains) {
		Map<String, ChainStep> latest = new LinkedHashMap<>();
		Map<String, Integer> units = new HashMap<>();
		for (List<ChainStep> chain : chains) {
			Set<String> seen = new HashSet<>();
			for (ChainStep step : chain) {
				latest.merge(step.saveId(), step, (a, b) -> isLater(b, a) ? b : a);
				if (seen.add(step.saveId())) {
					units.merge(step.saveId(), 1, Integer::sum);
				}
			}
		}
		List<SavePoint> result = new ArrayList<>();
		for (Map.Entry<String, ChainStep> entry : latest.entrySet()) {
			ChainStep step = entry.getValue();
			result.add(new SavePoint(entry.getKey(), step.at(), step.machineName(), step.operator(),
					units.get(entry.getKey())));
		}
		result.sort(Comparator.comparing(SavePoint::at).reversed().thenComparing(SavePoint::saveId));
		return result;
	}

	/** Ob {@code candidate} später ist als {@code current}; bei gleichem Zeitpunkt entscheidet die Versions-ID. */
	private static boolean isLater(ChainStep candidate, ChainStep current) {
		int byTime = candidate.at().compareTo(current.at());
		return byTime != 0 ? byTime > 0 : candidate.version().compareTo(current.version()) > 0;
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
	 * Das eine Event bzw. die eine Halle aus dem zusammengesetzten Zielstand. Enthält der Zielstand unlesbare Teile, wird
	 * er nicht wiederhergestellt: Sonst würden diese Teile beim Speichern wie vom Nutzer entfernt behandelt.
	 *
	 * @param deletedText die Meldung, wenn zu diesem Speicherstand nichts da war (gelöscht)
	 * @throws LockedUnitException wenn der Zielstand unlesbare Teile enthält oder das Gesuchte fehlt
	 */
	static <T> T single(UnitMapper.Assembly<T> target, String deletedText) {
		if (!target.warnings().isEmpty()) {
			throw new LockedUnitException("Der Speicherstand enthält unlesbare Teile und wird nicht wiederhergestellt: "
					+ String.join(" ", target.warnings()));
		}
		if (target.items().isEmpty()) {
			throw new LockedUnitException(deletedText);
		}
		return target.items().get(0);
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
