package de.eltviller_carneval_verein.karten.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Testdaten und Hilfen für die Tests des {@link VersionStore} ({@link VersionStoreTest}, {@link VersionStorePathTest})
 * und für {@link FullSyncTest}.
 *
 * <p>
 * Die Tests stehen in zwei Klassen und die Hilfen hier, weil der Virenscanner des Entwicklungsrechners (Norton) die
 * übersetzte {@code VersionStoreTest.class} wiederholt löschte, solange alle Tests in einer Klasse standen; jede Hälfte
 * für sich ließ er stehen. {@code FullSyncTest} scheiterte dann mit {@code NoClassDefFoundError}, obwohl es nur
 * {@link #allDataFiles} brauchte.
 */
final class VersionStoreFixtures {

	static final Instant AT = Instant.parse("2026-10-26T10:00:00Z");

	static final VersionId V1 = VersionId.create(1_000_000L, "aaaaaaaa", 0);
	static final VersionId V2 = VersionId.create(2_000_000L, "bbbbbbbb", 0);
	static final VersionId V3 = VersionId.create(3_000_000L, "cccccccc", 0);

	private VersionStoreFixtures() {
	}

	static byte[] bytes(String text) {
		return text.getBytes(StandardCharsets.UTF_8);
	}

	static Version version(VersionId id, VersionId... parents) {
		return new Version(1, id, List.of(parents), "maschine-1", "Kasse 1", "Anna", AT, "save-" + id, false,
				new ObjectMapper().createObjectNode().put("name", "Sitzung"));
	}

	static byte[] versionBytes(VersionId id, VersionId... parents) throws IOException {
		return StoreCodec.write(version(id, parents));
	}

	static byte[] claimBytes(VersionId id, byte[] versionBytes) throws IOException {
		return StoreCodec.write(new Claim(id, StoreCodec.sha256(versionBytes), "maschine-1", AT));
	}

	static void put(Path file, String content) throws IOException {
		Files.createDirectories(file.getParent());
		Files.write(file, bytes(content));
	}

	/**
	 * Die Pfade aller Versions- und Anspruchsdateien aller Einheiten des Bestands ({@link VersionStore#allUnits}, je
	 * Einheit {@link VersionStore#unitDataFiles}), relativ zum Datenordner und mit {@code /} getrennt, sortiert. Es sind
	 * genau die Dateien, die {@link VersionStore#readUnit} übernimmt; Listendateien unter {@code feeds/} gehören nicht dazu.
	 */
	static List<String> allDataFiles(VersionStore store) throws IOException {
		List<String> paths = new ArrayList<>();
		for (UnitKey unit : store.allUnits()) {
			paths.addAll(store.unitDataFiles(unit));
		}
		Collections.sort(paths);
		return paths;
	}
}
