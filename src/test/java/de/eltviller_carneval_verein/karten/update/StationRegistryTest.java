package de.eltviller_carneval_verein.karten.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StationRegistryTest {

	private static final Instant NOW = Instant.parse("2026-11-10T13:00:00Z");
	private static final String OTHER = "bbbb2222-0000-0000-0000-000000000002";

	@TempDir
	Path folder;

	private FakeSharedFolder access;
	private MutableClock clock;
	private StationRegistry registry;

	private static AppVersion v(String version, int revision) {
		return new AppVersion(version, revision);
	}

	@BeforeEach
	void setUp() {
		access = new FakeSharedFolder(folder);
		clock = new MutableClock(NOW);
		registry = new StationRegistry(access, new OwnFiles(), clock);
	}

	private Path stations() {
		return folder.resolve("updates").resolve("kassen");
	}

	private Path foreign(String id, String json) throws IOException {
		Files.createDirectories(stations());
		return Files.writeString(stations().resolve(id + ".json"), json);
	}

	private static String entry(String id, String name, String fsname, String version, int revision) {
		return "{\"format\":1,\"machineId\":\"" + id + "\",\"name\":\"" + name + "\",\"fsname\":\"" + fsname
				+ "\",\"version\":\"" + version + "\",\"revision\":" + revision
				+ ",\"plattform\":\"WINDOWS_PAKET\",\"gestartet\":\"2026-11-09T10:00:00Z\"}";
	}

	@Test
	void writeThenRead() throws IOException {
		registry.writeOwn(folder, v("1.1.0", 2), "ecv-karten", Plattform.WINDOWS_PAKET);
		assertEquals(List.of(new StationEntry(access.machineId, "Kasse 1", "ecv-karten", v("1.1.0", 2), "WINDOWS_PAKET", NOW)),
				registry.readAll(folder));
		String json = Files.readString(stations().resolve(access.machineId + ".json"));
		assertTrue(json.startsWith("{\"format\":1,\"machineId\":"), json);
	}

	@Test
	void noRewriteWhenSameAndFresh() throws IOException {
		registry.writeOwn(folder, v("1.1.0", 0), "ecv-karten", Plattform.LINUX);
		Path file = stations().resolve(access.machineId + ".json");
		Files.setLastModifiedTime(file, FileTime.from(NOW.minusSeconds(60)));
		clock.now = NOW.plus(Duration.ofHours(23));
		registry.writeOwn(folder, v("1.1.0", 0), "ecv-karten", Plattform.LINUX);
		assertEquals(FileTime.from(NOW.minusSeconds(60)), Files.getLastModifiedTime(file));
		assertEquals(NOW, registry.readAll(folder).get(0).gestartet());
	}

	/** code-review: „zuletzt gestartet“ stimmt nach jedem Start, nicht erst nach einem Tag. */
	@Test
	void newRunRewritesStartTime() throws IOException {
		registry.writeOwn(folder, v("1.1.0", 0), "ecv-karten", Plattform.LINUX);
		clock.now = NOW.plus(Duration.ofHours(4));
		StationRegistry nextRun = new StationRegistry(access, new OwnFiles(), clock);
		nextRun.writeOwn(folder, v("1.1.0", 0), "ecv-karten", Plattform.LINUX);
		assertEquals(NOW.plus(Duration.ofHours(4)), nextRun.readAll(folder).get(0).gestartet());
	}

	@Test
	void rewriteAfterOneDay() throws IOException {
		registry.writeOwn(folder, v("1.1.0", 0), "ecv-karten", Plattform.LINUX);
		clock.now = NOW.plus(Duration.ofHours(25));
		registry.writeOwn(folder, v("1.1.0", 0), "ecv-karten", Plattform.LINUX);
		assertEquals(NOW.plus(Duration.ofHours(25)), registry.readAll(folder).get(0).gestartet());
	}

	@Test
	void rewriteWhenVersionChanged() throws IOException {
		registry.writeOwn(folder, v("1.1.0", 0), "ecv-karten", Plattform.LINUX);
		registry.writeOwn(folder, v("1.1.1", 0), "ecv-karten", Plattform.LINUX);
		assertEquals(v("1.1.1", 0), registry.readAll(folder).get(0).version());
	}

	@Test
	void missingFolderIsNotCreated() throws IOException {
		Path missing = folder.resolve("getrennt");
		registry.writeOwn(missing, v("1.1.0", 0), "ecv-karten", Plattform.LINUX);
		assertFalse(Files.exists(missing));
		assertEquals(List.of(), registry.readAll(missing));
	}

	@Test
	void unknownFieldsIgnored() throws IOException {
		foreign(OTHER, entry(OTHER, "Kasse 2", "ecv-karten", "1.1.1", 0).replace("}", ",\"neu\":{\"x\":1}}"));
		assertEquals(v("1.1.1", 0), registry.readAll(folder).get(0).version());
	}

	@Test
	void brokenForeignSkipped() throws IOException {
		foreign(OTHER, "{kaputt");
		foreign("cccc3333", entry("cccc3333", "Kasse 3", "ecv-karten", "1.1.0", 0));
		assertEquals(List.of("cccc3333"), registry.readAll(folder).stream().map(StationEntry::machineId).toList());
		assertEquals("{kaputt", Files.readString(stations().resolve(OTHER + ".json")));
	}

	@Test
	void brokenOwnRewritten() throws IOException {
		foreign(access.machineId, "{kaputt");
		registry.writeOwn(folder, v("1.1.0", 0), "ecv-karten", Plattform.LINUX);
		assertEquals(1, registry.readAll(folder).size());
	}

	@Test
	void oversizedSkipped() throws IOException {
		foreign(OTHER, entry(OTHER, "x".repeat(70_000), "ecv-karten", "1.1.1", 0));
		assertEquals(List.of(), registry.readAll(folder));
	}

	@Test
	void foreignNamesIgnored() throws IOException {
		Files.createDirectories(stations());
		Files.writeString(stations().resolve(OTHER + " (conflicted copy 2026-11-10 120000).json"),
				entry(OTHER, "Kasse 2", "ecv-karten", "9.9.9", 0));
		Files.writeString(stations().resolve("~karv-x.tmp"), entry(OTHER, "Kasse 2", "ecv-karten", "9.9.9", 0));
		assertEquals(List.of(), registry.readAll(folder));
	}

	@Test
	void missingVersionSkipped() throws IOException {
		foreign(OTHER, "{\"format\":1,\"machineId\":\"" + OTHER + "\",\"name\":\"Kasse 2\"}");
		assertEquals(List.of(), registry.readAll(folder));
	}

	@Test
	void newestForeignNeedsSameFsnameAndHigher() throws IOException {
		foreign(OTHER, entry(OTHER, "Kasse 2", "ecv-karten", "1.1.1", 0));
		foreign("cccc3333", entry("cccc3333", "Test", "ecv-karten-fx132", "9.9.9", 0));
		foreign("dddd4444", entry("dddd4444", "Kasse 4", "ecv-karten", "1.1.0", 5));
		registry.writeOwn(folder, v("2.0.0", 0), "ecv-karten", Plattform.LINUX);
		List<StationEntry> all = registry.readAll(folder);
		assertEquals(Optional.of(OTHER),
				StationRegistry.newestForeign(all, access.machineId, "ecv-karten", v("1.1.0", 0)).map(StationEntry::machineId));
		assertEquals(Optional.empty(), StationRegistry.newestForeign(all, access.machineId, "ecv-karten", v("1.1.1", 0)));
	}

	@Test
	void newestForeignIgnoresOwnId() throws IOException {
		registry.writeOwn(folder, v("2.0.0", 0), "ecv-karten", Plattform.LINUX);
		assertEquals(Optional.empty(),
				StationRegistry.newestForeign(registry.readAll(folder), access.machineId, "ecv-karten", v("1.1.0", 0)));
	}

	/** Eine verstellbare Uhr. */
	static final class MutableClock extends Clock {
		volatile Instant now;

		MutableClock(Instant now) {
			this.now = now;
		}

		@Override
		public java.time.ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(java.time.ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}
}
