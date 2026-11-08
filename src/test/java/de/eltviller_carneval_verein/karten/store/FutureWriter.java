package de.eltviller_carneval_verein.karten.store;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Test-Hilfe: schreibt Versionen eines Rechners mit einer neueren Programmversion („1.2-Rechner“) roh in den gemeinsamen
 * Ordner, über die echte Schreibschicht ({@link StoreCodec}, Listen, Ansprüche) und in der Reihenfolge des
 * {@link Uploader}: Versionsdatei, Änderungsliste, Anspruch. Der Inhalt geht an keinem Modell und keiner Inhaltsprüfung
 * vorbei; so entstehen Stände, die dieses Programm nicht selbst schreiben würde (Zusatzfelder, unbekannte Enum-Werte,
 * unbekannter Zahlstatus, {@code requires}).
 * <p>
 * Der 1.2-Rechner hat eine eigene Rechner-ID und eigene Listen. Wer seine Sicht braucht (etwa den Kopf, auf den er als
 * Nächstes schreibt), baut ihm einen eigenen Dienst mit dem durchlässigen {@link ContentGate#NONE}; mit der Regel dieses
 * Programms sperrte der Dienst, was der 1.2-Rechner eben geschrieben hat.
 */
public final class FutureWriter {

	/** Die Rechner-ID des 1.2-Rechners. */
	public static final String MACHINE = "zukunftrechner120000000000";
	/** Der Name des 1.2-Rechners in seinen Versionen. */
	public static final String MACHINE_NAME = "Kasse 1.2";

	/** Zählt die Versionen aller Aufrufe, damit jede ihre eigene ID hat. */
	private static final AtomicInteger COUNT = new AtomicInteger();

	private FutureWriter() {
	}

	/**
	 * Schreibt eine Version der Einheit auf {@code parent}, samt Eintrag in der Änderungsliste und Anspruch, wie es ein
	 * 1.2-Rechner täte. Ihr Zeitpunkt liegt eine Sekunde nach dem von {@code parent}.
	 *
	 * @param sharedDir der gemeinsame Ordner (darin {@code data})
	 * @param unit      die Einheit
	 * @param payload   die Daten der Version, unverändert übernommen
	 * @param parent    der Vorgänger, auf den der Anspruch zielt; nicht {@code null}
	 * @return die geschriebene Version
	 */
	public static Version write(Path sharedDir, UnitKey unit, ObjectNode payload, Version parent) throws IOException {
		Objects.requireNonNull(parent, "parent");
		int count = COUNT.incrementAndGet();
		Instant at = parent.at().plusSeconds(1);
		Version version = new Version(StoreCodec.FORMAT, VersionId.create(at.toEpochMilli(), MACHINE, count),
				List.of(parent.id()), MACHINE, MACHINE_NAME, null, at, "zukunft-" + count, false, payload, null, Map.of());
		byte[] bytes = StoreCodec.write(version);
		VersionStore shared = new VersionStore(sharedDir.resolve("data"), new NioFileLayer(), sharedDir, () -> MACHINE);
		assertTrue(shared.writeVersion(unit, version.id(), bytes), "die Versionsdatei gibt es schon");
		new FeedWriter(shared, MACHINE, Clock.systemUTC())
				.append(List.of(new FeedEntry(unit.relativePath(), version.id(), version.claimName())));
		Claim claim = new Claim(version.id(), StoreCodec.sha256(bytes), MACHINE, at);
		assertTrue(shared.createClaim(unit, version.claimName(), StoreCodec.write(claim)),
				"der Vorgänger ist schon beansprucht: " + parent.id());
		return version;
	}
}
