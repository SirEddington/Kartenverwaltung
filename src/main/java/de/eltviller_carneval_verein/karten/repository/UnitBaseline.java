package de.eltviller_carneval_verein.karten.repository;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import de.eltviller_carneval_verein.karten.repository.UnitMapper.UnitPayload;
import de.eltviller_carneval_verein.karten.store.Change;
import de.eltviller_carneval_verein.karten.store.StoreMessages;
import de.eltviller_carneval_verein.karten.store.SyncService;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.UnitSnapshot;
import de.eltviller_carneval_verein.karten.store.UnitState;
import de.eltviller_carneval_verein.karten.store.UnitState.Status;
import de.eltviller_carneval_verein.karten.store.UnitType;
import de.eltviller_carneval_verein.karten.store.Version;

/**
 * Das Buch, das ein Repository über seine Events bzw. Hallen führt, damit Speichern nur schreibt, was sich geändert
 * hat: je Event bzw. Halle der <b>Vergleichsstand</b> (alle Einheiten, wie sie beim Laden vorlagen) und die Einheiten, die
 * dem Nutzer <b>angezeigt</b> wurden ({@link UnitMapper.Assembly#shown()}).
 * <p>
 * Der Vergleichsstand ist der Stand, aus dem das Modell geladen wurde, fortgeschrieben nur durch eigene Speichervorgänge
 * und, nach einem gescheiterten {@link SyncService#append}, durch den Stand der betroffenen Einheiten. Er wird beim
 * Speichern nicht frisch aus dem Versionsspeicher geholt: Eine inzwischen fremde Buchung würde sonst als vom Nutzer
 * entfernt gelten und gelöscht.
 */
final class UnitBaseline {

	private static final Logger LOG = Logger.getLogger(UnitBaseline.class.getName());

	private final SyncService sync;

	/** Der Vergleichsstand je Event bzw. Halle (Schlüssel: deren ID). */
	private final Map<String, Map<UnitKey, UnitSnapshot>> known = new HashMap<>();

	/** Die angezeigten Einheiten je Event bzw. Halle. */
	private final Map<String, Set<UnitKey>> shown = new HashMap<>();

	UnitBaseline(SyncService sync) {
		this.sync = sync;
	}

	/** Das Event bzw. die Halle, zu der die Einheit gehört. */
	private static String ownerOf(UnitKey unit) {
		return unit.type() == UnitType.HALL ? unit.hallId() : unit.eventId();
	}

	/** Baut den Vergleichsstand neu aus den Ständen auf, die gerade geladen wurden. */
	void reset(Collection<UnitSnapshot> snapshots, Set<UnitKey> shownUnits) {
		known.clear();
		shown.clear();
		for (UnitSnapshot snapshot : snapshots) {
			known.computeIfAbsent(ownerOf(snapshot.unit()), k -> new HashMap<>()).put(snapshot.unit(), snapshot);
		}
		for (UnitKey unit : shownUnits) {
			shown.computeIfAbsent(ownerOf(unit), k -> new HashSet<>()).add(unit);
		}
	}

	/**
	 * Schreibt die Unterschiede zwischen dem Vergleichsstand und {@code units} als ein Speichervorgang. Gibt es keine,
	 * wird nichts geschrieben. Danach gelten die Einheiten von {@code units} als angezeigt.
	 *
	 * @param ownerId die ID des Events bzw. der Halle
	 * @param name    der Name, für die Fehlermeldung
	 * @throws LockedUnitException wenn eine betroffene Einheit gesperrt ist; es wurde nichts geschrieben
	 * @throws RuntimeException    wenn das Schreiben scheitert; der Vergleichsstand zeigt dann, was auf der Platte liegt
	 */
	void save(String ownerId, String name, List<UnitPayload> units) {
		List<Change> changes = UnitMapper.changes(baselineOf(ownerId), shownForSave(ownerId, units), units);
		if (!changes.isEmpty()) {
			append(ownerId, changes, "Fehler beim Speichern von \"" + name + "\": ");
		}
		Set<UnitKey> now = new HashSet<>();
		for (UnitPayload part : units) {
			now.add(part.unit());
		}
		shown.put(ownerId, now);
	}

	/**
	 * Die Einheiten, die für diesen Speichervorgang als angezeigt gelten. Bringt das Objekt einen gelöschten Event-Kopf
	 * oder eine gelöschte Vorstellung zurück, kamen die lesbaren Einheiten darunter dem Nutzer nie vor Augen (nach einem
	 * Neustart, oder weil sie seit dem Löschen nicht mehr gespeichert wurden). Sie gehören jetzt zum Objekt: Fehlen sie
	 * darin, hat der Nutzer sie entfernt und sie werden gelöscht, statt wieder aufzutauchen.
	 */
	private Set<UnitKey> shownForSave(String ownerId, List<UnitPayload> units) {
		Map<UnitKey, UnitSnapshot> baseline = baselineOf(ownerId);
		Set<UnitKey> result = shown.getOrDefault(ownerId, Set.of());
		Set<UnitKey> readable = null;
		for (UnitPayload part : units) {
			UnitKey revived = part.unit();
			UnitSnapshot known = baseline.get(revived);
			boolean hasChildren = revived.type() == UnitType.EVENT || revived.type() == UnitType.PRESENTATION;
			if (known == null || !known.deleted() || !hasChildren) {
				continue;
			}
			if (readable == null) {
				readable = UnitMapper.readableUnits(baseline.values());
				result = new HashSet<>(result);
			}
			for (UnitKey unit : readable) {
				if (isBelow(unit, revived)) {
					result.add(unit);
				}
			}
		}
		return result;
	}

	/** Ob die Einheit unter dem Event-Kopf bzw. der Vorstellung liegt. */
	private static boolean isBelow(UnitKey unit, UnitKey parent) {
		if (parent.type() == UnitType.EVENT) {
			return unit.type() != UnitType.EVENT && parent.eventId().equals(unit.eventId());
		}
		return unit.type() == UnitType.BOOKING && parent.eventId().equals(unit.eventId())
				&& parent.presentationId().equals(unit.presentationId());
	}

	/**
	 * Löscht die Einheit (Event-Kopf bzw. Halle). Ist sie nie gespeichert worden oder schon gelöscht, passiert nichts.
	 *
	 * @throws LockedUnitException wenn die Einheit gesperrt ist oder auf eine Datei wartet; es wurde nichts geschrieben
	 * @throws RuntimeException    wenn das Schreiben scheitert
	 */
	void delete(String ownerId, UnitKey unit, String name) {
		UnitSnapshot snapshot = baselineOf(ownerId).get(unit);
		if (snapshot == null || snapshot.version() == null || snapshot.deleted()) {
			return;
		}
		UnitMapper.requireWritable(snapshot);
		append(ownerId, List.of(UnitMapper.deletion(snapshot)), "Fehler beim Löschen von \"" + name + "\": ");
	}

	private Map<UnitKey, UnitSnapshot> baselineOf(String ownerId) {
		return known.computeIfAbsent(ownerId, k -> new HashMap<>());
	}

	private void append(String ownerId, List<Change> changes, String failureText) {
		Map<UnitKey, UnitSnapshot> baseline = baselineOf(ownerId);
		String saveId = UUID.randomUUID().toString();
		List<Version> written;
		try {
			written = sync.append(saveId, changes);
		} catch (IOException e) {
			refresh(ownerId, changes, saveId);
			throw new RuntimeException(failureText + StoreMessages.describe(e), e);
		}
		for (int i = 0; i < written.size(); i++) {
			UnitKey unit = changes.get(i).unit();
			Version version = written.get(i);
			baseline.put(unit, new UnitSnapshot(unit, version.id(), Status.OK, version.deleted(), version.payload()));
		}
	}

	/**
	 * Nach einem gescheiterten Schreiben: Eine Einheit der Änderungen, von der ein Teil geschrieben wurde, steht so im
	 * Vergleichsstand, wie sie auf der Platte liegt, und gilt als angezeigt (sie steht im Modell des Nutzers). Nur der eigene
	 * Versuch zählt: Trägt der aktuelle Stand nicht die {@code saveId} des Versuchs und ist er nicht der bisherige, hat
	 * ein anderer Rechner die Einheit inzwischen geändert (in den Versionsspeicher geholt). Diesen Stand hat der Nutzer
	 * nie gesehen; der Vergleichsstand bleibt, wie er war, sonst würde der nächste Speichervorgang die fremde Änderung
	 * überschreiben.
	 */
	private void refresh(String ownerId, List<Change> changes, String saveId) {
		Map<UnitKey, UnitSnapshot> baseline = baselineOf(ownerId);
		for (Change change : changes) {
			UnitKey unit = change.unit();
			try {
				UnitSnapshot before = baseline.get(unit);
				UnitState state = sync.unitState(unit);
				Version current = state.current();
				boolean ours = current != null && saveId.equals(current.saveId());
				boolean unchanged = current == null ? before == null || before.version() == null
						: before != null && current.id().equals(before.version());
				if (ours || unchanged) {
					baseline.put(unit, UnitSnapshot.of(state));
				}
				if (ours && !current.deleted()) {
					shown.computeIfAbsent(ownerId, k -> new HashSet<>()).add(unit);
				}
			} catch (IOException | RuntimeException e) {
				LOG.log(Level.WARNING, "Der Stand von " + unit.relativePath() + " ließ sich nicht neu lesen", e);
			}
		}
	}
}
