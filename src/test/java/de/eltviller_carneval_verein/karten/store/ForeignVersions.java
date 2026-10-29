package de.eltviller_carneval_verein.karten.store;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Test-Hilfe: schreibt Versionen eines anderen Rechners ("Fremd") in den lokalen Bestand und bringt sie in den Index, wie
 * es das Abholen aus dem gemeinsamen Ordner tut. Jede Hilfe zählt ihre Versionen selbst hoch; je Test eine.
 */
public final class ForeignVersions {

	private final SyncService sync;
	private final Path appData;
	private int count;

	/**
	 * @param sync    der Dienst, in dessen Index die Versionen kommen
	 * @param appData der Datenordner des Dienstes
	 */
	public ForeignVersions(SyncService sync, Path appData) {
		this.sync = sync;
		this.appData = appData;
	}

	/**
	 * Schreibt eine Version des Rechners {@code machineName} auf das Ende der Kette der Einheit.
	 *
	 * @param changes ändert die Daten der Vorgängerversion
	 */
	public Version write(UnitKey unit, String machineName, Consumer<ObjectNode> changes) throws IOException {
		return write(unit, "0123456789abcdefghijklmnop", machineName, List.of(), changes);
	}

	/**
	 * Schreibt eine Zusammenführung auf das Ende der Kette der Einheit: erster Vorgänger ist das letzte Glied, weitere
	 * Vorgänger sind {@code absorbed}.
	 *
	 * @param machine die Rechner-ID des Schreibenden (etwa die eigene, für eine eigene Zusammenführung)
	 * @param changes ändert die Daten der Vorgängerversion
	 */
	public Version writeMerge(UnitKey unit, String machine, String machineName, List<VersionId> absorbed,
			Consumer<ObjectNode> changes) throws IOException {
		return write(unit, machine, machineName, absorbed, changes);
	}

	private Version write(UnitKey unit, String machine, String machineName, List<VersionId> absorbed,
			Consumer<ObjectNode> changes) throws IOException {
		Version parent = sync.unitState(unit).head();
		ObjectNode payload = parent.payload().deepCopy();
		changes.accept(payload);
		count++;
		List<VersionId> parents = new ArrayList<>();
		parents.add(parent.id());
		parents.addAll(absorbed);
		Version version = new Version(StoreCodec.FORMAT, VersionId.create(1_900_000_000_000L + count, "abcdefgh", count),
				List.copyOf(parents), machine, machineName, null, Instant.now(), "fremd-" + count, false, payload);
		byte[] bytes = StoreCodec.write(version);
		VersionStore store = new VersionStore(appData.resolve("data"), new NioFileLayer());
		assertTrue(store.writeVersion(unit, version.id(), bytes));
		new LocalArbiter(store, Clock.systemUTC()).claim(unit, version, bytes);
		sync.unitState(unit);
		return version;
	}

	/** Die Anzahl aller Dateien (Versionen und Ansprüche) im lokalen Bestand. */
	public long files() throws IOException {
		try (Stream<Path> walk = Files.walk(appData.resolve("data"))) {
			return walk.filter(Files::isRegularFile).count();
		}
	}
}
