package de.eltviller_carneval_verein.karten.store;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Was dieser Rechner als "eigen" ansieht: seine aktuelle ID und die früheren, die er vor einer Neuvergabe trug.
 * <p>
 * Eine Version gilt als eigen, wenn sie von der aktuellen ID geschrieben wurde, oder von einer früheren ID, solange sie
 * bis zum Zeitpunkt der Neuvergabe entstand. Spätere Versionen mit einer früheren ID stammen von dem anderen Rechner,
 * der die alte ID (etwa durch einen kopierten Datenordner) weiter trägt, und sind fremd.
 *
 * @param currentId die aktuelle Rechner-ID
 * @param previous  die früheren IDs mit dem Zeitpunkt, bis zu dem sie galten (nie {@code null}, unveränderlich)
 * @param ids       die aktuelle und alle früheren IDs, einmal berechnet (unveränderlich); siehe {@link #ids()}
 */
public record Ownership(String currentId, List<Previous> previous, Set<String> ids) {

	/**
	 * Eine frühere Rechner-ID.
	 *
	 * @param id    die frühere ID
	 * @param until Zeitpunkt der Neuvergabe; Versionen bis einschließlich dieses Zeitpunkts (auf die Millisekunde genau)
	 *              sind eigen
	 */
	public record Previous(String id, Instant until) {

		/** @throws NullPointerException wenn ID oder Zeitpunkt fehlen */
		public Previous {
			Objects.requireNonNull(id, "id");
			Objects.requireNonNull(until, "until");
		}
	}

	/**
	 * Macht aus einer fehlenden Liste der früheren IDs eine leere, kopiert die Liste und berechnet die Menge aller IDs
	 * einmal ({@link #ids()}).
	 *
	 * @param ids {@code null} oder schon dieselbe Menge, die sich aus den beiden anderen ergibt (etwa aus einer Kopie)
	 * @throws NullPointerException     wenn die aktuelle ID fehlt
	 * @throws IllegalArgumentException wenn {@code ids} nicht zu den beiden anderen passt
	 */
	public Ownership {
		Objects.requireNonNull(currentId, "currentId");
		previous = previous == null ? List.of() : List.copyOf(previous);
		Set<String> all = new LinkedHashSet<>();
		all.add(currentId);
		for (Previous old : previous) {
			all.add(old.id());
		}
		if (ids != null && !ids.equals(all)) {
			throw new IllegalArgumentException("Die IDs " + ids + " passen nicht zu " + all);
		}
		ids = Set.copyOf(all);
	}

	/**
	 * Was eigen ist: die aktuelle ID und die früheren mit ihrer Grenze.
	 *
	 * @throws NullPointerException wenn die aktuelle ID fehlt
	 */
	public Ownership(String currentId, List<Previous> previous) {
		this(currentId, previous, null);
	}

	/**
	 * Ob die Version von einer eigenen ID geschrieben wurde: von der aktuellen, oder von einer früheren bis zu deren Grenze.
	 *
	 * @param machineId die Rechner-ID, die in der Versionsdatei steht
	 * @param version   die ID der Version (liefert den Zeitpunkt)
	 */
	public boolean isOwn(String machineId, VersionId version) {
		if (machineId == null) {
			return false;
		}
		if (machineId.equals(currentId)) {
			return true;
		}
		for (Previous old : previous) {
			if (machineId.equals(old.id()) && withinLimit(version, old)) {
				return true;
			}
		}
		return false;
	}

	/** Ob die Version von einer eigenen ID geschrieben wurde; wie {@link #isOwn(String, VersionId)}. */
	public boolean isOwn(Version version) {
		return isOwn(version.machine(), version.id());
	}

	/**
	 * Wie {@link #isOwn(String, VersionId)}, nur über das Rechner-Kürzel in der Versions-ID (siehe
	 * {@link VersionId#isFrom(String)}), mit derselben Zeitgrenze. Für Stellen, die nur die ID einer Version kennen und
	 * keine Datei lesen wollen.
	 */
	public boolean isOwnId(VersionId version) {
		if (version.isFrom(currentId)) {
			return true;
		}
		for (Previous old : previous) {
			if (version.isFrom(old.id()) && withinLimit(version, old)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Ob die Kennung einer Zwischendatei (die Rechner-ID im Namen) die aktuelle oder eine frühere ID ist; eine Abfrage der
	 * einmal berechneten Menge {@link #ids()}, deshalb auch je Datei billig.
	 */
	public boolean isOwnTempTag(String tag) {
		return tag != null && ids.contains(tag);
	}

	/** Die aktuelle und alle früheren IDs; im Konstruktor einmal berechnet, unveränderlich. */
	@Override
	public Set<String> ids() {
		return ids;
	}

	private static boolean withinLimit(VersionId version, Previous old) {
		return version.epochMillis() <= old.until().toEpochMilli();
	}
}
