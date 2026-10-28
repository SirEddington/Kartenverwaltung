package de.eltviller_carneval_verein.karten.repository;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

import de.eltviller_carneval_verein.karten.store.ChainStep;
import de.eltviller_carneval_verein.karten.store.StoreMessages;
import de.eltviller_carneval_verein.karten.store.SyncService;
import de.eltviller_carneval_verein.karten.store.UnitKey;
import de.eltviller_carneval_verein.karten.store.UnitSnapshot;

/**
 * Die Warteliste eines Repositorys für fremde Stände, die noch nicht eingearbeitet sind, und der gemeinsame Rahmen von
 * {@code applyForeign} (Spec KARV-85). Eine Einheit ist
 * <ul>
 * <li><em>zurückgestellt</em> ({@link #defer}): Der Nutzer hat sie ungespeichert geändert oder bearbeitet sie gerade. Daraus
 * entsteht der Hinweis in der Fußzeile ({@link #deferrals});</li>
 * <li><em>wartend</em> ({@link #hold}): Sie wartet still, ohne Hinweis, etwa eine Buchung, bis ihr Platz im Modell da ist,
 * oder eine Einheit, bei der das Einarbeiten scheiterte.</li>
 * </ul>
 * Beide werden bei jedem Aufruf von {@code applyForeign} erneut geprüft ({@link #recheck}). Nur auf dem Oberflächen-Thread
 * benutzen.
 */
final class ForeignBacklog {

	private static final Logger LOG = Logger.getLogger(ForeignBacklog.class.getName());

	/** Die zurückgestellten Stände in der Reihenfolge, in der sie zurückgestellt wurden. */
	private final Map<UnitKey, Deferral> deferred = new LinkedHashMap<>();

	/** Die still wartenden Einheiten in Einfügereihenfolge. */
	private final Set<UnitKey> held = new LinkedHashSet<>();

	/** Stellt den Stand zurück, mit Hinweis. Die Einheit wartet damit nicht mehr still. */
	void defer(Deferral deferral) {
		held.remove(deferral.unit());
		deferred.put(deferral.unit(), deferral);
	}

	/** Lässt die Einheit still warten, ohne Hinweis (eine zurückgestellte wartet damit nur noch); geloggt wird beim ersten Mal. */
	void hold(UnitKey unit, String reason) {
		deferred.remove(unit);
		if (held.add(unit)) {
			LOG.info("Fremder Stand von " + unit.relativePath() + " wartet: " + reason + ".");
		}
	}

	/** Die Einheit ist erledigt: Sie ist weder zurückgestellt noch wartend. */
	void settle(UnitKey unit) {
		deferred.remove(unit);
		held.remove(unit);
	}

	/** Alle Einheiten des Events bzw. der Halle sind erledigt. */
	void forgetOwner(String ownerId) {
		deferred.keySet().removeIf(unit -> ownedBy(ownerId, unit));
		held.removeIf(unit -> ownedBy(ownerId, unit));
	}

	boolean isDeferred(UnitKey unit) {
		return deferred.containsKey(unit);
	}

	boolean isHeld(UnitKey unit) {
		return held.contains(unit);
	}

	/** Ob eine Einheit des Events bzw. der Halle zurückgestellt ist oder wartet. */
	boolean anyFor(String ownerId) {
		return deferred.keySet().stream().anyMatch(unit -> ownedBy(ownerId, unit))
				|| held.stream().anyMatch(unit -> ownedBy(ownerId, unit));
	}

	/** Die Einheiten, die bei jedem Aufruf erneut geprüft werden: zuerst die zurückgestellten, dann die wartenden. */
	Set<UnitKey> recheck() {
		Set<UnitKey> units = new LinkedHashSet<>(deferred.keySet());
		units.addAll(held);
		return units;
	}

	/** Behält nur Einheiten, die der Index noch kennt; was er nicht mehr kennt, lässt sich nicht einarbeiten. */
	void retainKnown(Set<UnitKey> known) {
		deferred.keySet().retainAll(known);
		held.retainAll(known);
	}

	/** Alle zurückgestellten Stände, in der Reihenfolge, in der sie zurückgestellt wurden. */
	List<Deferral> deferrals() {
		return new ArrayList<>(deferred.values());
	}

	private static boolean ownedBy(String ownerId, UnitKey unit) {
		return ownerId.equals(unit.eventId()) || ownerId.equals(unit.hallId());
	}

	/** Was ein Repository im Rahmen {@link #run} für einen Besitzer (Event bzw. Halle) tut. */
	interface Step {

		/** Der Besitzer der Einheit: die ID ihres Events bzw. ihrer Halle. */
		String ownerOf(UnitKey unit);

		/** Entscheidet und arbeitet die Stände eines Besitzers ein. */
		void apply(List<UnitSnapshot> ofOwner, Map<UnitKey, List<ChainStep>> chains, List<String> warnings);

		/**
		 * Das Einarbeiten für den Besitzer ist mit einer Ausnahme gescheitert: meldet es dem Nutzer und merkt sich die
		 * Einheiten zum erneuten Versuch.
		 */
		void failed(String ownerId, List<UnitSnapshot> ofOwner, RuntimeException e, List<String> warnings);
	}

	/**
	 * Der gemeinsame Rahmen von {@code applyForeign}: Geprüft werden die gemeldeten Einheiten, die {@code accepts} annimmt,
	 * die zurückgestellten und die wartenden. Die Stände kommen frisch aus dem Index; was er nicht mehr kennt, fällt aus der
	 * Warteliste. Je Besitzer ruft der Rahmen {@link Step#apply}; ein Fehler dort hält die übrigen Besitzer nicht auf
	 * ({@link Step#failed}). Lassen sich die Ketten nicht lesen, wird nichts eingearbeitet und der Nutzer gewarnt.
	 *
	 * @return alle danach zurückgestellten Stände und die Warnungen für den Nutzer
	 */
	ForeignResult run(Collection<UnitKey> units, Predicate<UnitKey> accepts, SyncService sync, Step step) {
		Set<UnitKey> wanted = new LinkedHashSet<>();
		for (UnitKey unit : units) {
			if (accepts.test(unit)) {
				wanted.add(unit);
			}
		}
		wanted.addAll(deferred.keySet());
		wanted.addAll(held);
		List<String> warnings = new ArrayList<>();
		if (!wanted.isEmpty()) {
			Map<UnitKey, UnitSnapshot> current = sync.snapshots(wanted);
			retainKnown(current.keySet());
			Map<String, List<UnitSnapshot>> byOwner = new LinkedHashMap<>();
			for (UnitKey unit : wanted) {
				UnitSnapshot snapshot = current.get(unit);
				if (snapshot != null) {
					byOwner.computeIfAbsent(step.ownerOf(unit), k -> new ArrayList<>()).add(snapshot);
				}
			}
			try {
				Map<UnitKey, List<ChainStep>> chains = sync.chains(current.keySet());
				for (List<UnitSnapshot> ofOwner : byOwner.values()) {
					// ein Fehler bei einem Besitzer hält die übrigen nicht auf; seine Einheiten kommen beim nächsten Mal wieder
					try {
						step.apply(ofOwner, chains, warnings);
					} catch (RuntimeException e) {
						step.failed(step.ownerOf(ofOwner.get(0).unit()), ofOwner, e, warnings);
					}
				}
			} catch (IOException e) {
				LOG.log(Level.WARNING, "Die Ketten fremd geänderter Einheiten ließen sich nicht lesen", e);
				warnings.add("Änderungen von einem anderen Rechner ließen sich nicht einarbeiten: " + StoreMessages.describe(e));
			}
		}
		return new ForeignResult(deferrals(), warnings);
	}
}
