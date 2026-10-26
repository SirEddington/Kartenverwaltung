package de.eltviller_carneval_verein.karten.store;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Erzeugt Versions-IDs eines Rechners. Der Zähler läuft von 0 bis 36^4 - 1 und beginnt danach wieder bei 0; er
 * trennt IDs, die in derselben Millisekunde entstehen. Verschiedene Rechner unterscheiden sich im Kürzel, das aus
 * der Rechner-ID stammt (siehe {@link VersionId#create}). Threadsicher.
 */
public final class VersionIdGenerator {

	private final String machineId;
	private final Clock clock;
	private final AtomicInteger counter = new AtomicInteger();

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

	/** Die nächste ID: aktuelle Zeit der Uhr, Kürzel dieses Rechners, fortlaufender Zähler. */
	public VersionId next() {
		int count = counter.getAndUpdate(c -> (c + 1) % VersionId.COUNTER_LIMIT);
		return VersionId.create(clock.millis(), machineId, count);
	}
}
