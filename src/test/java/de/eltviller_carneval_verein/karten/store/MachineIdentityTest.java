package de.eltviller_carneval_verein.karten.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MachineIdentityTest {

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-26T13:45:07Z"), ZoneOffset.UTC);

	@TempDir
	Path dir;

	private Path machineFile() {
		return dir.resolve("machine.json");
	}

	private List<String> names() throws IOException {
		try (Stream<Path> stream = Files.list(dir)) {
			return stream.map(p -> p.getFileName().toString()).sorted().toList();
		}
	}

	@Test
	void createsOnceAndReloads() throws IOException {
		MachineIdentity first = MachineIdentity.loadOrCreate(dir, () -> "KASSE-1");
		MachineIdentity second = MachineIdentity.loadOrCreate(dir, () -> "ANDERER-NAME");

		assertEquals(first.id(), second.id());
		assertEquals("KASSE-1", first.name());
		assertEquals("KASSE-1", second.name());
		assertTrue(first.id().matches("[0-9a-z]{26}"), first.id());
		assertTrue(Files.isRegularFile(machineFile()));
		assertEquals(List.of("machine.json"), names());
	}

	@Test
	void readFailureKeepsTheIdentity() throws IOException {
		MachineIdentity original = MachineIdentity.loadOrCreate(dir, () -> "KASSE-1");
		byte[] before = Files.readAllBytes(machineFile());

		// Stat oder Lesen scheitern vorübergehend (z. B. Virenscanner): nie eine neue Kennung
		assertThrows(java.nio.file.AccessDeniedException.class, () -> MachineIdentity.loadOrCreate(dir, () -> "KASSE-1",
				CLOCK, file -> {
					throw new java.nio.file.AccessDeniedException(file.toString());
				}));

		assertEquals(List.of("machine.json"), names());
		assertEquals(new String(before, StandardCharsets.UTF_8),
				new String(Files.readAllBytes(machineFile()), StandardCharsets.UTF_8));
		assertEquals(original.id(), MachineIdentity.loadOrCreate(dir, () -> "KASSE-1").id());
	}

	@Test
	void fileCreatedMeanwhileIsReadNotReplaced() throws IOException {
		MachineIdentity original = MachineIdentity.loadOrCreate(dir, () -> "KASSE-1");
		int[] calls = { 0 };

		// der erste Lesezugriff meldet "fehlt", obwohl die Datei da ist (ein anderer Start legte sie gerade an)
		MachineIdentity loaded = MachineIdentity.loadOrCreate(dir, () -> "KASSE-2", CLOCK, file -> {
			if (calls[0]++ == 0) {
				throw new java.nio.file.NoSuchFileException(file.toString());
			}
			return Files.readAllBytes(file);
		});

		assertEquals(original.id(), loaded.id());
		assertEquals("KASSE-1", loaded.name());
		assertEquals(List.of("machine.json"), names());
	}

	@Test
	void idWorksForVersionIds() throws IOException {
		MachineIdentity machine = MachineIdentity.loadOrCreate(dir, () -> "KASSE-1");

		VersionId id = VersionId.create(1_700_000_000_000L, machine.id(), 0);

		assertTrue(id.value().contains("-" + machine.id().substring(0, 8) + "-"));
	}

	@Test
	void differentDirectoriesGiveDifferentIds() throws IOException {
		Path other = Files.createDirectory(dir.resolve("other"));

		assertNotEquals(MachineIdentity.loadOrCreate(dir, () -> "A").id(), MachineIdentity.loadOrCreate(other, () -> "A").id());
	}

	@Test
	void missingComputerNameFallsBackToRechner() throws IOException {
		assertEquals("Rechner", MachineIdentity.loadOrCreate(dir, () -> null).name());
		Path other = Files.createDirectory(dir.resolve("other"));
		assertEquals("Rechner", MachineIdentity.loadOrCreate(other, () -> "  ").name());
	}

	@Test
	void createsMissingDirectory() throws IOException {
		Path nested = dir.resolve("a").resolve("b");

		MachineIdentity machine = MachineIdentity.loadOrCreate(nested, () -> "X");

		assertTrue(Files.isRegularFile(nested.resolve("machine.json")));
		assertEquals(machine.id(), MachineIdentity.loadOrCreate(nested, () -> "Y").id());
	}

	@Test
	void brokenFileIsRecreated() throws IOException {
		Files.writeString(machineFile(), "das ist kein JSON", StandardCharsets.UTF_8);

		MachineIdentity machine = MachineIdentity.loadOrCreate(dir, () -> "KASSE-1", CLOCK);

		assertEquals(List.of("machine.json", "machine.json.corrupt-2026-10-26_13-45-07"), names());
		assertEquals("das ist kein JSON", Files.readString(dir.resolve("machine.json.corrupt-2026-10-26_13-45-07")));
		assertTrue(machine.id().matches("[0-9a-z]{26}"));
		assertEquals(machine.id(), MachineIdentity.loadOrCreate(dir, () -> "egal", CLOCK).id());
	}

	@Test
	void missingOrInvalidIdCountsAsBroken() throws IOException {
		List<String> broken = List.of("", "[]", "{}", "{\"name\":\"X\"}", "{\"id\":5,\"name\":\"X\"}",
				"{\"id\":\"ABCDEFGHIJKLMNOPQRSTUVWXYZ\",\"name\":\"X\"}", "{\"id\":\"abc\",\"name\":\"X\"}");
		for (String content : broken) {
			Files.writeString(machineFile(), content, StandardCharsets.UTF_8);

			MachineIdentity machine = MachineIdentity.loadOrCreate(dir, () -> "NEU", CLOCK);

			assertEquals("NEU", machine.name(), content);
			assertTrue(machine.id().matches("[0-9a-z]{26}"), content);
			assertEquals(List.of("machine.json", "machine.json.corrupt-2026-10-26_13-45-07"), names(), content);
			assertEquals(content, Files.readString(dir.resolve("machine.json.corrupt-2026-10-26_13-45-07")), content);
			Files.delete(machineFile());
			Files.delete(dir.resolve("machine.json.corrupt-2026-10-26_13-45-07"));
		}
	}

	@Test
	void validIdWithBadNameKeepsIdAndRepairsName() throws IOException {
		String id = "abcdefghijklmnopqrstuvwxyz";
		List<String> badNames = List.of("{\"id\":\"" + id + "\"}", "{\"id\":\"" + id + "\",\"name\":5}",
				"{\"id\":\"" + id + "\",\"name\":null}", "{\"id\":\"" + id + "\",\"name\":\" \"}",
				"{\"id\":\"" + id + "\",\"name\":\"\"}");
		for (String content : badNames) {
			Files.writeString(machineFile(), content, StandardCharsets.UTF_8);

			MachineIdentity machine = MachineIdentity.loadOrCreate(dir, () -> "NEU", CLOCK);

			assertEquals(id, machine.id(), content);
			assertEquals("NEU", machine.name(), content);
			assertEquals(List.of("machine.json"), names(), content);
			MachineIdentity reloaded = MachineIdentity.loadOrCreate(dir, () -> "egal", CLOCK);
			assertEquals(id, reloaded.id(), content);
			assertEquals("NEU", reloaded.name(), content);
			Files.delete(machineFile());
		}
	}

	@Test
	void validIdWithBadNameFallsBackToRechnerWithoutComputerName() throws IOException {
		Files.writeString(machineFile(), "{\"id\":\"abcdefghijklmnopqrstuvwxyz\"}", StandardCharsets.UTF_8);

		MachineIdentity machine = MachineIdentity.loadOrCreate(dir, () -> null, CLOCK);

		assertEquals("abcdefghijklmnopqrstuvwxyz", machine.id());
		assertEquals("Rechner", machine.name());
	}

	@Test
	void existingCorruptCopyIsNeverOverwritten() throws IOException {
		Files.writeString(dir.resolve("machine.json.corrupt-2026-10-26_13-45-07"), "alt1", StandardCharsets.UTF_8);
		Files.writeString(dir.resolve("machine.json.corrupt-2026-10-26_13-45-07-2"), "alt2", StandardCharsets.UTF_8);
		Files.writeString(machineFile(), "neu kaputt", StandardCharsets.UTF_8);

		MachineIdentity.loadOrCreate(dir, () -> "X", CLOCK);

		assertEquals("alt1", Files.readString(dir.resolve("machine.json.corrupt-2026-10-26_13-45-07")));
		assertEquals("alt2", Files.readString(dir.resolve("machine.json.corrupt-2026-10-26_13-45-07-2")));
		assertEquals("neu kaputt", Files.readString(dir.resolve("machine.json.corrupt-2026-10-26_13-45-07-3")));
	}

	@Test
	void unreadableMachineFileIsAnErrorAndNotRenamed() throws IOException {
		Files.createDirectory(machineFile());

		assertThrows(IOException.class, () -> MachineIdentity.loadOrCreate(dir, () -> "X", CLOCK));

		assertEquals(List.of("machine.json"), names());
	}

	@Test
	void regenerateChangesIdOnly() throws IOException {
		MachineIdentity machine = MachineIdentity.loadOrCreate(dir, () -> "KASSE-1");
		machine.setOperator("Anna");

		MachineIdentity fresh = machine.regenerate();

		assertNotEquals(machine.id(), fresh.id());
		assertTrue(fresh.id().matches("[0-9a-z]{26}"));
		assertEquals("KASSE-1", fresh.name());
		assertEquals("Anna", fresh.operator());
		MachineIdentity reloaded = MachineIdentity.loadOrCreate(dir, () -> "egal");
		assertEquals(fresh.id(), reloaded.id());
		assertEquals("KASSE-1", reloaded.name());
		assertEquals(List.of("machine.json"), names());
	}

	@Test
	void regenerateRemembersTheOldIdWithTheMoment() throws IOException {
		MachineIdentity machine = MachineIdentity.loadOrCreate(dir, () -> "KASSE-1");
		String oldId = machine.id();

		MachineIdentity fresh = machine.regenerate(CLOCK);

		assertEquals(List.of(new Ownership.Previous(oldId, CLOCK.instant())), fresh.ownership().previous());
		assertEquals(fresh.id(), fresh.ownership().currentId());
		assertTrue(fresh.ownership().isOwnId(VersionId.create(CLOCK.millis(), oldId, 0)));
		assertFalse(fresh.ownership().isOwnId(VersionId.create(CLOCK.millis() + 1, oldId, 0)));
		// die alte Instanz bleibt unverändert
		assertEquals(List.of(), machine.ownership().previous());
		var previous = new com.fasterxml.jackson.databind.ObjectMapper().readTree(Files.readAllBytes(machineFile()))
				.get("previous");
		assertEquals(1, previous.size());
		assertEquals(oldId, previous.get(0).get("id").asText());
		assertEquals("2026-10-26T13:45:07Z", previous.get(0).get("until").asText());
	}

	@Test
	void regenerateTwiceKeepsBothOldIds() throws IOException {
		MachineIdentity first = MachineIdentity.loadOrCreate(dir, () -> "KASSE-1");
		MachineIdentity second = first.regenerate(CLOCK);
		Clock later = Clock.fixed(CLOCK.instant().plusSeconds(60), ZoneOffset.UTC);

		MachineIdentity third = second.regenerate(later);

		assertEquals(List.of(new Ownership.Previous(first.id(), CLOCK.instant()),
				new Ownership.Previous(second.id(), later.instant())), third.ownership().previous());
	}

	@Test
	void regenerateWithAnExplicitUntilKeepsItAsTheLimit() throws IOException {
		MachineIdentity machine = MachineIdentity.loadOrCreate(dir, () -> "KASSE-1", CLOCK);
		Instant until = CLOCK.instant().plusSeconds(5).plusNanos(1_500_000);

		MachineIdentity fresh = machine.regenerate(until);

		Instant expected = CLOCK.instant().plusSeconds(5).plusMillis(1);
		assertEquals(List.of(new Ownership.Previous(machine.id(), expected)), fresh.ownership().previous());
		assertEquals(fresh.ownership(), MachineIdentity.loadOrCreate(dir, () -> "egal").ownership());
	}

	@Test
	void regenerateWithoutClockUsesTheSystemClock() throws IOException {
		MachineIdentity machine = MachineIdentity.loadOrCreate(dir, () -> "KASSE-1");
		Instant before = Instant.now();

		MachineIdentity fresh = machine.regenerate();

		Instant until = fresh.ownership().previous().get(0).until();
		assertFalse(until.isBefore(before.minusMillis(1)));
		assertFalse(until.isAfter(Instant.now().plusMillis(1)));
	}

	@Test
	void renameKeepsPreviousIds() throws IOException {
		MachineIdentity fresh = MachineIdentity.loadOrCreate(dir, () -> "KASSE-1").regenerate(CLOCK);
		List<Ownership.Previous> previous = fresh.ownership().previous();

		fresh.rename("Eingang Nord");

		assertEquals(previous, fresh.ownership().previous());
		assertEquals(previous, MachineIdentity.loadOrCreate(dir, () -> "egal").ownership().previous());
	}

	@Test
	void fileWithoutPreviousHasNone() throws IOException {
		Files.writeString(machineFile(), "{\"id\":\"abcdefghijklmnopqrstuvwxyz\",\"name\":\"KASSE-1\",\"unbekannt\":5}",
				StandardCharsets.UTF_8);

		MachineIdentity machine = MachineIdentity.loadOrCreate(dir, () -> "egal", CLOCK);

		assertEquals(List.of(), machine.ownership().previous());
		assertEquals("abcdefghijklmnopqrstuvwxyz", machine.ownership().currentId());
		assertEquals(List.of("machine.json"), names());
	}

	@Test
	void previousSurvivesReload() throws IOException {
		MachineIdentity fresh = MachineIdentity.loadOrCreate(dir, () -> "KASSE-1").regenerate(CLOCK);

		MachineIdentity reloaded = MachineIdentity.loadOrCreate(dir, () -> "egal");

		assertEquals(fresh.id(), reloaded.id());
		assertEquals(fresh.ownership(), reloaded.ownership());
		assertEquals(1, reloaded.ownership().previous().size());
	}

	@Test
	void previousWithBadEntriesIsIgnoredAndTheIdentityKept() throws IOException {
		String id = "abcdefghijklmnopqrstuvwxyz";
		String good = "{\"id\":\"zyxwvutsrqponmlkjihgfedcba\",\"until\":\"2026-10-26T13:45:07Z\"}";
		Files.writeString(machineFile(), "{\"id\":\"" + id + "\",\"name\":\"KASSE-1\",\"previous\":[" + good
				+ ",5,{\"id\":\"zu-kurz\",\"until\":\"2026-10-26T13:45:07Z\"},{\"id\":\"zyxwvutsrqponmlkjihgfedcba\","
				+ "\"until\":\"kein Zeitpunkt\"},{\"id\":\"zyxwvutsrqponmlkjihgfedcba\"}]}", StandardCharsets.UTF_8);

		MachineIdentity machine = MachineIdentity.loadOrCreate(dir, () -> "egal", CLOCK);

		assertEquals(id, machine.id());
		assertEquals(List.of(new Ownership.Previous("zyxwvutsrqponmlkjihgfedcba", CLOCK.instant())),
				machine.ownership().previous());
		assertEquals(List.of("machine.json"), names());
	}

	private static final String LOSSY_ID = "abcdefghijklmnopqrstuvwxyz";
	private static final String LOSSY_COPY = "machine.json.corrupt-2026-10-26_13-45-07";

	/** Eine Datei, deren "previous" einen brauchbaren und einen schadhaften Eintrag enthält. */
	private String writeLossyFile() throws IOException {
		String content = "{\"id\":\"" + LOSSY_ID + "\",\"name\":\"KASSE-1\",\"previous\":["
				+ "{\"id\":\"zyxwvutsrqponmlkjihgfedcba\",\"until\":\"2026-10-26T13:45:07Z\"},{\"id\":\"kaputt\"}]}";
		Files.writeString(machineFile(), content, StandardCharsets.UTF_8);
		return content;
	}

	@Test
	void skippedPreviousEntryKeepsACopyBeforeRename() throws IOException {
		String content = writeLossyFile();
		MachineIdentity machine = MachineIdentity.loadOrCreate(dir, () -> "egal", CLOCK);
		// Laden allein ändert nichts: noch keine Kopie nötig
		assertEquals(List.of("machine.json"), names());

		machine.rename("Eingang Nord");

		assertEquals(List.of("machine.json", LOSSY_COPY), names());
		assertEquals(content, Files.readString(dir.resolve(LOSSY_COPY)));
		assertEquals(1, MachineIdentity.loadOrCreate(dir, () -> "egal", CLOCK).ownership().previous().size());
		// nur einmal: ein weiteres Schreiben legt keine zweite Kopie an
		machine.rename("Eingang Süd");
		assertEquals(List.of("machine.json", LOSSY_COPY), names());
	}

	@Test
	void skippedPreviousEntryKeepsACopyBeforeRegenerate() throws IOException {
		String content = writeLossyFile();
		MachineIdentity machine = MachineIdentity.loadOrCreate(dir, () -> "egal", CLOCK);
		Clock later = Clock.fixed(CLOCK.instant().plusSeconds(3600), ZoneOffset.UTC);

		MachineIdentity fresh = machine.regenerate(later);

		assertEquals(List.of("machine.json", "machine.json.corrupt-2026-10-26_14-45-07"), names());
		assertEquals(content, Files.readString(dir.resolve("machine.json.corrupt-2026-10-26_14-45-07")));
		assertEquals(2, fresh.ownership().previous().size());
	}

	@Test
	void previousThatIsNotAnArrayKeepsACopyBeforeRewrite() throws IOException {
		String content = "{\"id\":\"" + LOSSY_ID + "\",\"name\":\"KASSE-1\",\"previous\":{\"id\":\"x\"}}";
		Files.writeString(machineFile(), content, StandardCharsets.UTF_8);
		MachineIdentity machine = MachineIdentity.loadOrCreate(dir, () -> "egal", CLOCK);
		assertEquals(List.of(), machine.ownership().previous());

		machine.rename("Eingang Nord");

		assertEquals(List.of("machine.json", LOSSY_COPY), names());
		assertEquals(content, Files.readString(dir.resolve(LOSSY_COPY)));
	}

	@Test
	void skippedEntryCopyIsAlsoMadeWhenTheNameIsRepaired() throws IOException {
		String content = "{\"id\":\"" + LOSSY_ID + "\",\"previous\":[5]}";
		Files.writeString(machineFile(), content, StandardCharsets.UTF_8);

		MachineIdentity.loadOrCreate(dir, () -> "NEU", CLOCK);

		assertEquals(List.of("machine.json", LOSSY_COPY), names());
		assertEquals(content, Files.readString(dir.resolve(LOSSY_COPY)));
	}

	@Test
	void cleanPreviousLeavesNoCopy() throws IOException {
		MachineIdentity fresh = MachineIdentity.loadOrCreate(dir, () -> "KASSE-1").regenerate(CLOCK);

		fresh.rename("Eingang Nord");
		MachineIdentity.loadOrCreate(dir, () -> "egal", CLOCK).rename("Noch einer");

		assertEquals(List.of("machine.json"), names());
	}

	@Test
	void untilBeyondTheMillisecondRangeIsSkippedAndKept() throws IOException {
		String content = "{\"id\":\"" + LOSSY_ID + "\",\"name\":\"KASSE-1\",\"previous\":["
				+ "{\"id\":\"zyxwvutsrqponmlkjihgfedcba\",\"until\":\"+1000000000-12-31T23:59:59Z\"}]}";
		Files.writeString(machineFile(), content, StandardCharsets.UTF_8);

		MachineIdentity machine = MachineIdentity.loadOrCreate(dir, () -> "egal", CLOCK);

		assertEquals(List.of(), machine.ownership().previous());
		// kein ArithmeticException beim Prüfen
		assertFalse(machine.ownership().isOwnId(VersionId.create(1_000L, "zyxwvutsrqponmlkjihgfedcba", 0)));
		machine.rename("Eingang Nord");
		assertEquals(content, Files.readString(dir.resolve(LOSSY_COPY)));
	}

	@Test
	void regenerateTruncatesToMilliseconds() throws IOException {
		Clock subMilli = Clock.fixed(Instant.parse("2026-10-26T13:45:07.123456789Z"), ZoneOffset.UTC);

		MachineIdentity fresh = MachineIdentity.loadOrCreate(dir, () -> "KASSE-1").regenerate(subMilli);

		assertEquals(Instant.parse("2026-10-26T13:45:07.123Z"), fresh.ownership().previous().get(0).until());
		var previous = new com.fasterxml.jackson.databind.ObjectMapper().readTree(Files.readAllBytes(machineFile()))
				.get("previous");
		assertEquals("2026-10-26T13:45:07.123Z", previous.get(0).get("until").asText());
		assertEquals(fresh.ownership(), MachineIdentity.loadOrCreate(dir, () -> "egal").ownership());
	}

	@Test
	void renameTrimsAndPersists() throws IOException {
		MachineIdentity machine = MachineIdentity.loadOrCreate(dir, () -> "KASSE-1");
		String id = machine.id();

		machine.rename("  Eingang Nord ");

		assertEquals("Eingang Nord", machine.name());
		assertEquals(id, machine.id());
		MachineIdentity reloaded = MachineIdentity.loadOrCreate(dir, () -> "egal");
		assertEquals("Eingang Nord", reloaded.name());
		assertEquals(id, reloaded.id());
		assertEquals(List.of("machine.json"), names());
	}

	@Test
	void renameRejectsBlankAndKeepsName() throws IOException {
		MachineIdentity machine = MachineIdentity.loadOrCreate(dir, () -> "KASSE-1");

		assertThrows(IllegalArgumentException.class, () -> machine.rename("  "));
		assertThrows(IllegalArgumentException.class, () -> machine.rename(""));
		assertThrows(IllegalArgumentException.class, () -> machine.rename(null));

		assertEquals("KASSE-1", machine.name());
		assertEquals("KASSE-1", MachineIdentity.loadOrCreate(dir, () -> "egal").name());
	}

	@Test
	void operatorIsKeptInMemoryOnly() throws IOException {
		MachineIdentity machine = MachineIdentity.loadOrCreate(dir, () -> "KASSE-1");
		assertNull(machine.operator());

		machine.setOperator("  Anna ");
		assertEquals("Anna", machine.operator());
		machine.setOperator("   ");
		assertNull(machine.operator());
		machine.setOperator("Bernd");
		machine.setOperator(null);
		assertNull(machine.operator());

		machine.setOperator("Clara");
		assertFalse(Files.readString(machineFile()).contains("Clara"));
		assertNull(MachineIdentity.loadOrCreate(dir, () -> "egal").operator());
	}
}
