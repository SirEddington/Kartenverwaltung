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
 */
public record Ownership(String currentId, List<Previous> previous) {

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
	 * Macht aus einer fehlenden Liste der früheren IDs eine leere und kopiert die Liste.
	 *
	 * @throws NullPointerException wenn die aktuelle ID fehlt
	 */
	public Ownership {
		Objects.requireNonNull(currentId, "currentId");
		previous = previous == null ? List.of() : List.copyOf(previous);
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

	/** Ob die Kennung einer Zwischendatei (die Rechner-ID im Namen) die aktuelle oder eine frühere ID ist. */
	public boolean isOwnTempTag(String tag) {
		return tag != null && ids().contains(tag);
	}

	/** Die aktuelle und alle früheren IDs. */
	public Set<String> ids() {
		Set<String> ids = new LinkedHashSet<>();
		ids.add(currentId);
		for (Previous old : previous) {
			ids.add(old.id());
		}
		return Set.copyOf(ids);
	}

	private static boolean withinLimit(VersionId version, Previous old) {
		return version.epochMillis() <= old.until().toEpochMilli();
	}
}
