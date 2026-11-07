package de.eltviller_carneval_verein.karten.store;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Was {@link LocalScan} aus den Dateien einer Einheit ableitet. */
class LocalScanTest {

	private static final Instant AT = Instant.parse("2026-10-26T10:00:00Z");
	private static final UnitKey UNIT = UnitKey.event("e1");
	private static final String OTHER = "bbbbbbbb";

	private final Map<VersionId, VersionFile> versions = new TreeMap<>();
	private final Map<String, ClaimFile> claims = new TreeMap<>();

	private static VersionId id(int n) {
		return VersionId.create(n * 1000L, OTHER, 0);
	}

	private void version(VersionId id, boolean newerContent, VersionId... parents) throws IOException {
		Version version = new Version(1, id, List.of(parents), OTHER, "Kasse", "Anna", AT, "save-" + id, false,
				new ObjectMapper().createObjectNode().put("name", id.value()));
		versions.put(id, new VersionFile(id, StoreCodec.write(version), version.withNewerContent(newerContent), null,
				newerContent ? VersionFile.Reason.CONTENT : VersionFile.Reason.OK));
	}

	private void claim(String name, VersionId target, String sha256) throws IOException {
		Claim claim = new Claim(target, sha256, OTHER, AT);
		claims.put(name, new ClaimFile(name, StoreCodec.write(claim), claim));
	}

	private void claim(String name, VersionId target) throws IOException {
		claim(name, target, StoreCodec.sha256(versions.get(target).bytes()));
	}

	/**
	 * Die Kette geht wie im {@link ChainResolver} über eine inhaltlich zu neue Version hinweg: Sonst meldete der Arbiter bei
	 * einer späteren beschädigten Version den falschen Platz an die Heilung.
	 */
	@Test
	void stopSlotWalksOverContentToDamagedVersion() throws IOException {
		version(id(1), false);
		version(id(2), true, id(1));
		versions.put(id(3), new VersionFile(id(3), "kaputt".getBytes(StandardCharsets.UTF_8), null, null,
				VersionFile.Reason.DAMAGED));
		claim("root", id(1));
		claim(id(1).value(), id(2));
		claim(id(2).value(), id(3), "abc");

		String slot = LocalScan.stopSlot(new UnitFiles(UNIT, versions, claims, Map.of(), Map.of()));

		assertEquals(id(2).value(), slot, "der Platz, der die beschädigte Version nennt");
	}
}
