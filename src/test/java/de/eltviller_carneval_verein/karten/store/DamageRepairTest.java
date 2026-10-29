package de.eltviller_carneval_verein.karten.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.eltviller_carneval_verein.karten.store.DamageRepair.Outcome;
import de.eltviller_carneval_verein.karten.store.DamageRepair.Result;

class DamageRepairTest {

	private static final Instant AT = Instant.parse("2026-10-26T10:00:00Z");
	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-26T13:45:07Z"), ZoneOffset.UTC);
	private static final String STAMP = "2026-10-26_13-45-07";
	private static final String ME = "maschine-1";

	private static final UnitKey UNIT = UnitKey.event("e1");
	private static final VersionId V1 = VersionId.create(1_000_000L, "aaaaaaaa", 0);
	private static final VersionId V2 = VersionId.create(2_000_000L, "aaaaaaaa", 1);

	@TempDir
	Path dir;

	private FileLayer files;
	private VersionStore local;
	private VersionStore shared;

	@BeforeEach
	void setUp() {
		files = new NioFileLayer();
		local = new VersionStore(dir.resolve("local").resolve("data"), files);
		shared = new VersionStore(dir.resolve("shared").resolve("data"), files);
	}

	private DamageRepair repair() {
		return new DamageRepair(local, shared, files, CLOCK);
	}

	private static byte[] good() throws IOException {
		return StoreCodec.write(new Version(1, V1, List.of(), ME, "Kasse", "Anna", AT, "save-1", false,
				new ObjectMapper().createObjectNode().put("name", "Sitzung")));
	}

	private static byte[] damaged() {
		return "{ das ist kaputt".getBytes(StandardCharsets.UTF_8);
	}

	private static Claim claimFor(byte[] goodBytes) {
		return new Claim(V1, StoreCodec.sha256(goodBytes), ME, AT);
	}

	private Path versionPath(VersionStore store) {
		return store.resolve(UNIT.relativePath() + "/" + V1 + ".json");
	}

	private Path claimPath(VersionStore store, String name) {
		return store.resolve(UNIT.relativePath() + "/claims/" + name + ".json");
	}

	private List<String> namesNextTo(Path file) throws IOException {
		return files.listNames(file.getParent());
	}

	@Test
	void localDamagedIsReplacedFromShared() throws IOException {
		byte[] good = good();
		local.writeVersion(UNIT, V1, damaged());
		shared.writeVersion(UNIT, V1, good);

		Result result = repair().repairVersion(UNIT, claimFor(good));

		assertEquals(Outcome.REPAIRED_LOCAL, result.outcome());
		assertEquals(versionPath(local), result.replaced());
		assertEquals(versionPath(local).resolveSibling(V1 + ".json.corrupt-" + STAMP), result.corruptCopy());
		assertArrayEquals(good, local.readVersionBytes(UNIT, V1).orElseThrow());
		assertArrayEquals(good, shared.readVersionBytes(UNIT, V1).orElseThrow());
	}

	@Test
	void localMissingIsWrittenFromShared() throws IOException {
		byte[] good = good();
		shared.writeVersion(UNIT, V1, good);

		Result result = repair().repairVersion(UNIT, claimFor(good));

		// Nichts war beschädigt, also gibt es auch keine beschädigte Kopie daneben
		assertEquals(new Result(Outcome.REPAIRED_LOCAL, versionPath(local), null), result);
		assertArrayEquals(good, local.readVersionBytes(UNIT, V1).orElseThrow());
		assertEquals(List.of(V1 + ".json"), namesNextTo(versionPath(local)));
	}

	@Test
	void sharedDamagedIsReplacedFromLocal() throws IOException {
		byte[] good = good();
		local.writeVersion(UNIT, V1, good);
		shared.writeVersion(UNIT, V1, damaged());

		Result result = repair().repairVersion(UNIT, claimFor(good));

		assertEquals(Outcome.REPAIRED_SHARED, result.outcome());
		assertEquals(versionPath(shared), result.replaced());
		assertEquals(versionPath(shared).resolveSibling(V1 + ".json.corrupt-" + STAMP), result.corruptCopy());
		assertArrayEquals(good, shared.readVersionBytes(UNIT, V1).orElseThrow());
		assertArrayEquals(good, local.readVersionBytes(UNIT, V1).orElseThrow());
	}

	@Test
	void bothDamagedHasNoGoodCopy() throws IOException {
		byte[] good = good();
		byte[] bad = damaged();
		local.writeVersion(UNIT, V1, bad);
		shared.writeVersion(UNIT, V1, bad);

		Result result = repair().repairVersion(UNIT, claimFor(good));

		assertEquals(new Result(Outcome.NO_GOOD_COPY, null, null), result);
		assertArrayEquals(bad, local.readVersionBytes(UNIT, V1).orElseThrow());
		assertArrayEquals(bad, shared.readVersionBytes(UNIT, V1).orElseThrow());
		assertEquals(List.of(V1 + ".json"), namesNextTo(versionPath(local)));
		assertEquals(List.of(V1 + ".json"), namesNextTo(versionPath(shared)));
	}

	@Test
	void damagedLocalAndMissingSharedHasNoGoodCopy() throws IOException {
		byte[] good = good();
		local.writeVersion(UNIT, V1, damaged());

		assertEquals(Outcome.NO_GOOD_COPY, repair().repairVersion(UNIT, claimFor(good)).outcome());
	}

	@Test
	void intactIsNotTouched() throws IOException {
		byte[] good = good();
		local.writeVersion(UNIT, V1, good);
		shared.writeVersion(UNIT, V1, good);

		Result result = repair().repairVersion(UNIT, claimFor(good));

		assertEquals(Outcome.NOT_DAMAGED, result.outcome());
		assertEquals(List.of(V1 + ".json"), namesNextTo(versionPath(local)));
		assertEquals(List.of(V1 + ".json"), namesNextTo(versionPath(shared)));
	}

	@Test
	void bothMissingIsNotDamaged() throws IOException {
		assertEquals(Outcome.NOT_DAMAGED, repair().repairVersion(UNIT, claimFor(good())).outcome());
	}

	@Test
	void goodLocalAndMissingSharedIsNotDamaged() throws IOException {
		byte[] good = good();
		local.writeVersion(UNIT, V1, good);

		assertEquals(Outcome.NOT_DAMAGED, repair().repairVersion(UNIT, claimFor(good)).outcome());
		assertFalse(shared.readVersionBytes(UNIT, V1).isPresent());
	}

	@Test
	void withoutSharedStoreLocalDamagedHasNoGoodCopy() throws IOException {
		byte[] good = good();
		local.writeVersion(UNIT, V1, damaged());
		DamageRepair single = new DamageRepair(local, null, files, CLOCK);

		assertEquals(Outcome.NO_GOOD_COPY, single.repairVersion(UNIT, claimFor(good)).outcome());
	}

	@Test
	void withoutSharedStoreIntactIsNotDamaged() throws IOException {
		byte[] good = good();
		local.writeVersion(UNIT, V1, good);
		DamageRepair single = new DamageRepair(local, null, files, CLOCK);

		assertEquals(Outcome.NOT_DAMAGED, single.repairVersion(UNIT, claimFor(good)).outcome());
	}

	@Test
	void corruptCopyIsKeptBeside() throws IOException {
		byte[] good = good();
		byte[] bad = damaged();
		local.writeVersion(UNIT, V1, bad);
		shared.writeVersion(UNIT, V1, good);

		Result result = repair().repairVersion(UNIT, claimFor(good));

		String corrupt = V1 + ".json.corrupt-" + STAMP;
		assertEquals(versionPath(local), result.replaced());
		assertEquals(versionPath(local).resolveSibling(corrupt), result.corruptCopy());
		assertEquals(List.of(V1 + ".json", corrupt), namesNextTo(versionPath(local)));
		assertArrayEquals(bad, Files.readAllBytes(versionPath(local).resolveSibling(corrupt)));
	}

	@Test
	void existingCorruptCopyIsNeverOverwritten() throws IOException {
		byte[] good = good();
		byte[] bad = damaged();
		local.writeVersion(UNIT, V1, bad);
		shared.writeVersion(UNIT, V1, good);
		Path first = versionPath(local).resolveSibling(V1 + ".json.corrupt-" + STAMP);
		Path second = versionPath(local).resolveSibling(V1 + ".json.corrupt-" + STAMP + "-2");
		Files.writeString(first, "alt1", StandardCharsets.UTF_8);
		Files.writeString(second, "alt2", StandardCharsets.UTF_8);

		Result result = repair().repairVersion(UNIT, claimFor(good));

		assertEquals("alt1", Files.readString(first));
		assertEquals("alt2", Files.readString(second));
		Path third = versionPath(local).resolveSibling(V1 + ".json.corrupt-" + STAMP + "-3");
		assertEquals(third, result.corruptCopy());
		assertArrayEquals(bad, Files.readAllBytes(third));
		assertArrayEquals(good, local.readVersionBytes(UNIT, V1).orElseThrow());
	}

	@Test
	void sharedCorruptCopyIsKeptInSharedFolder() throws IOException {
		byte[] good = good();
		byte[] bad = damaged();
		local.writeVersion(UNIT, V1, good);
		shared.writeVersion(UNIT, V1, bad);

		Result result = repair().repairVersion(UNIT, claimFor(good));

		String corrupt = V1 + ".json.corrupt-" + STAMP;
		assertEquals(versionPath(shared), result.replaced());
		assertEquals(versionPath(shared).resolveSibling(corrupt), result.corruptCopy());
		assertEquals(List.of(V1 + ".json", corrupt), namesNextTo(versionPath(shared)));
		assertArrayEquals(bad, Files.readAllBytes(versionPath(shared).resolveSibling(corrupt)));
	}

	private static byte[] claimBytes(VersionId version) throws IOException {
		return StoreCodec.write(new Claim(version, StoreCodec.sha256(new byte[] { 1 }), ME, AT));
	}

	@Test
	void unreadableLocalClaimIsReplaced() throws IOException {
		byte[] claim = claimBytes(V1);
		byte[] bad = damaged();
		local.createClaim(UNIT, "root", bad);
		shared.createClaim(UNIT, "root", claim);

		Result result = repair().repairClaim(UNIT, "root");

		assertEquals(Outcome.REPAIRED_LOCAL, result.outcome());
		assertArrayEquals(claim, local.readClaimBytes(UNIT, "root").orElseThrow());
		String corrupt = "root.json.corrupt-" + STAMP;
		assertEquals(claimPath(local, "root"), result.replaced());
		assertEquals(claimPath(local, "root").resolveSibling(corrupt), result.corruptCopy());
		assertEquals(List.of("root.json", corrupt), namesNextTo(claimPath(local, "root")));
		assertArrayEquals(bad, Files.readAllBytes(claimPath(local, "root").resolveSibling(corrupt)));
	}

	@Test
	void unreadableSharedClaimIsReplaced() throws IOException {
		byte[] claim = claimBytes(V1);
		byte[] bad = damaged();
		local.createClaim(UNIT, V1.value(), claim);
		shared.createClaim(UNIT, V1.value(), bad);

		Result result = repair().repairClaim(UNIT, V1.value());

		assertEquals(Outcome.REPAIRED_SHARED, result.outcome());
		assertArrayEquals(claim, shared.readClaimBytes(UNIT, V1.value()).orElseThrow());
		String corrupt = V1 + ".json.corrupt-" + STAMP;
		assertEquals(claimPath(shared, V1.value()), result.replaced());
		assertEquals(claimPath(shared, V1.value()).resolveSibling(corrupt), result.corruptCopy());
		assertArrayEquals(bad, Files.readAllBytes(result.corruptCopy()));
	}

	/** Der gemeinsame Bestand schreibt wie im Betrieb mit Anker und Kennung; beide Bestände gehen über dieselbe Schicht. */
	private DamageRepair repairRecordedBy(FaultyFileLayer layer) {
		VersionStore recordedLocal = new VersionStore(local.dataRoot(), layer);
		VersionStore recordedShared = new VersionStore(shared.dataRoot(), layer, dir.resolve("shared"), () -> ME);
		return new DamageRepair(recordedLocal, recordedShared, layer, CLOCK);
	}

	@Test
	void sharedReplacementCarriesTheMachineIdAsTempTag() throws IOException {
		byte[] good = good();
		local.writeVersion(UNIT, V1, good);
		shared.writeVersion(UNIT, V1, damaged());
		FaultyFileLayer layer = new FaultyFileLayer(files);

		Result result = repairRecordedBy(layer).repairVersion(UNIT, claimFor(good));

		assertEquals(Outcome.REPAIRED_SHARED, result.outcome());
		assertEquals(List.of(new FaultyFileLayer.Replacement(versionPath(shared), ME)), layer.replacements());
		assertEquals(List.of(), files.listNamesWithTemps(versionPath(shared).getParent()).stream()
				.filter(n -> n.startsWith(FileLayer.TEMP_PREFIX)).toList());
	}

	@Test
	void sharedClaimReplacementCarriesTheMachineIdAsTempTag() throws IOException {
		byte[] claim = claimBytes(V1);
		local.createClaim(UNIT, V1.value(), claim);
		shared.createClaim(UNIT, V1.value(), damaged());
		FaultyFileLayer layer = new FaultyFileLayer(files);

		Result result = repairRecordedBy(layer).repairClaim(UNIT, V1.value());

		assertEquals(Outcome.REPAIRED_SHARED, result.outcome());
		assertEquals(List.of(new FaultyFileLayer.Replacement(claimPath(shared, V1.value()), ME)), layer.replacements());
	}

	@Test
	void localReplacementStaysWithoutTempTag() throws IOException {
		byte[] good = good();
		local.writeVersion(UNIT, V1, damaged());
		shared.writeVersion(UNIT, V1, good);
		FaultyFileLayer layer = new FaultyFileLayer(files);

		Result result = repairRecordedBy(layer).repairVersion(UNIT, claimFor(good));

		assertEquals(Outcome.REPAIRED_LOCAL, result.outcome());
		assertEquals(List.of(new FaultyFileLayer.Replacement(versionPath(local), null)), layer.replacements());
	}

	@Test
	void differingReadableClaimsAreALossNotDamage() throws IOException {
		byte[] sharedClaim = claimBytes(V1);
		byte[] localClaim = claimBytes(V2);
		local.createClaim(UNIT, "root", localClaim);
		shared.createClaim(UNIT, "root", sharedClaim);

		Result result = repair().repairClaim(UNIT, "root");

		// Zwei lesbare Anspruchsdateien mit verschiedenem Ziel sind eine Niederlage: Abgleich und Hochladen entscheiden
		assertEquals(new Result(Outcome.NOT_DAMAGED, null, null), result);
		assertArrayEquals(localClaim, local.readClaimBytes(UNIT, "root").orElseThrow());
		assertArrayEquals(sharedClaim, shared.readClaimBytes(UNIT, "root").orElseThrow());
		assertEquals(List.of("root.json"), namesNextTo(claimPath(local, "root")));
		assertEquals(List.of("root.json"), namesNextTo(claimPath(shared, "root")));
	}

	@Test
	void equalClaimsAreNotDamaged() throws IOException {
		byte[] claim = claimBytes(V1);
		local.createClaim(UNIT, "root", claim);
		shared.createClaim(UNIT, "root", claim);

		assertEquals(Outcome.NOT_DAMAGED, repair().repairClaim(UNIT, "root").outcome());
		assertEquals(List.of("root.json"), namesNextTo(claimPath(local, "root")));
	}

	@Test
	void bothClaimsUnreadableHasNoGoodCopy() throws IOException {
		local.createClaim(UNIT, "root", damaged());
		shared.createClaim(UNIT, "root", "{ auch kaputt".getBytes(StandardCharsets.UTF_8));

		assertEquals(Outcome.NO_GOOD_COPY, repair().repairClaim(UNIT, "root").outcome());
		assertEquals(List.of("root.json"), namesNextTo(claimPath(local, "root")));
		assertEquals(List.of("root.json"), namesNextTo(claimPath(shared, "root")));
	}

	@Test
	void missingLocalClaimIsNotARepairCase() throws IOException {
		shared.createClaim(UNIT, "root", claimBytes(V1));

		assertEquals(Outcome.NOT_DAMAGED, repair().repairClaim(UNIT, "root").outcome());
		assertFalse(local.readClaimBytes(UNIT, "root").isPresent());
	}

	@Test
	void claimOfNewerFormatIsNotReplaced() throws IOException {
		byte[] newer = "{\"format\":2,\"version\":\"x\"}".getBytes(StandardCharsets.UTF_8);
		local.createClaim(UNIT, "root", newer);
		shared.createClaim(UNIT, "root", claimBytes(V1));

		assertEquals(Outcome.NOT_DAMAGED, repair().repairClaim(UNIT, "root").outcome());
		assertArrayEquals(newer, local.readClaimBytes(UNIT, "root").orElseThrow());
	}
}
