package de.eltviller_carneval_verein.karten.store;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Erzeugt Versions-IDs eines Rechners. Der Zähler läuft von 0 bis 36^4 - 1 und beginnt danach wieder bei 0; er
 * trennt IDs, die in derselben Millisekunde entstehen. Verschiedene Rechner unterscheiden sich im Kürzel, das aus
 * der Rechner-ID stammt (siehe {@link VersionId#create}). Threadsicher.
 * <p>
 * Für die Neuvergabe der Rechner-ID (SYS-011) lässt sich der Generator anhalten ({@link #freeze()}): Danach liegt keine
 * ID mehr nach der Grenze, die er dabei nennt. {@link #retire()} legt ihn danach still.
 */
public final class VersionIdGenerator {

	private final String machineId;
	private final Clock clock;
	private int counter;
	/** Die späteste Millisekunde, die eine vergebene ID trägt; {@link Long#MIN_VALUE}, solange keine vergeben ist. */
	private long last = Long.MIN_VALUE;
	/** Keine ID trägt eine spätere Millisekunde (nach {@link #freeze()}); sonst {@link Long#MAX_VALUE}. */
	private long limit = Long.MAX_VALUE;
	private boolean retired;

	/**
	 * @param machineId ID dieses Rechners (mindestens 8 Zeichen, die ersten 8 aus {@code [0-9A-Za-z]})
	 * @param clock     Uhr, aus der der Zeitanteil der IDs stammt
	 * @throws IllegalArgumentException wenn die Rechner-ID nicht verwendbar ist
	 */
	public VersionIdGenerator(String machineId, Clock clock) {
		this.clock = Objects.requireNonNull(clock, "clock");
		VersionId.create(0, machineId, 0);
		this.machineId = machineId;
	}

	/**
	 * Die nächste ID: aktuelle Zeit der Uhr (nach {@link #freeze()} höchstens die Grenze), Kürzel dieses Rechners,
	 * fortlaufender Zähler.
	 *
	 * @throws IllegalStateException wenn der Generator stillgelegt ist ({@link #retire()})
	 */
	public synchronized VersionId next() {
		if (retired) {
			throw new IllegalStateException("Der Generator der Rechner-ID " + machineId + " ist stillgelegt.");
		}
		int count = counter;
		counter = (counter + 1) % VersionId.COUNTER_LIMIT;
		long millis = Math.min(clock.millis(), limit);
		last = Math.max(last, millis);
		return VersionId.create(millis, machineId, count);
	}

	/**
	 * Hält die Zeit der IDs an: Ab jetzt trägt keine ID eine spätere Millisekunde als die zurückgegebene Grenze. Die Grenze
	 * ist die aktuelle Zeit der Uhr, aber nie früher als die späteste schon vergebene ID (die Uhr kann zurückgehen). IDs
	 * in derselben Millisekunde trennt weiter der Zähler.
	 *
	 * @return die Grenze, auf die Millisekunde genau: Jede ID dieses Generators, vergeben oder künftig, liegt nicht danach
	 */
	synchronized Instant freeze() {
		limit = Math.max(clock.millis(), last);
		return Instant.ofEpochMilli(limit);
	}

	/** Hebt {@link #freeze()} auf, wenn die Neuvergabe scheiterte: Die IDs tragen wieder die Zeit der Uhr. */
	synchronized void thaw() {
		limit = Long.MAX_VALUE;
	}

	/**
	 * Legt den Generator still: Jedes weitere {@link #next()} wirft. Nach der Neuvergabe der Rechner-ID, damit keine ID
	 * der alten mehr entsteht.
	 */
	synchronized void retire() {
		retired = true;
	}
}
