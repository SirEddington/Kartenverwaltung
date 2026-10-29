package de.eltviller_carneval_verein.karten.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

class StoreCodecTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final VersionId V1 = VersionId.of("00000000001-k4m9q2x8-0001");
	private static final VersionId V2 = VersionId.of("00000000002-k4m9q2x8-0001");
	private static final VersionId V3 = VersionId.of("00000000003-p7r2t5w1-0000");
	private static final Instant AT = Instant.parse("2026-10-26T19:04:11.512Z");

	private static ObjectNode payload(String json) throws IOException {
		return (ObjectNode) MAPPER.readTree(json);
	}

	private static String text(byte[] bytes) {
		return new String(bytes, StandardCharsets.UTF_8);
	}

	private static byte[] bytes(String json) {
		return json.getBytes(StandardCharsets.UTF_8);
	}

	@Test
	void roundTripVersion() throws IOException {
		Version version = new Version(StoreCodec.FORMAT, V3, List.of(V1, V2), "k4m9q2x8-1234", "Kasse 1", "Anna", AT,
				"save-1", false, payload("{\"name\":\"Anna\"}"));

		byte[] written = StoreCodec.write(version);
		assertTrue(text(written).contains("\"format\":1"));

		assertEquals(version, StoreCodec.readVersion(written));
	}

	@Test
	void versionWritesInstantAndIdsAsStrings() throws IOException {
		Version version = new Version(StoreCodec.FORMAT, V3, List.of(V1), "m", "Kasse 1", "Anna", AT, "save-1", false,
				payload("{}"));

		String json = text(StoreCodec.write(version));

		assertTrue(json.contains("\"at\":\"2026-10-26T19:04:11.512Z\""), json);
		assertTrue(json.contains("\"id\":\"" + V3.value() + "\""), json);
		assertTrue(json.contains("\"parents\":[\"" + V1.value() + "\"]"), json);
	}

	@Test
	void versionWithoutParentsRoundTrips() throws IOException {
		Version version = new Version(StoreCodec.FORMAT, V1, List.of(), "m", "Kasse 1", "Anna", AT, "save-1", false,
				payload("{\"a\":1}"));

		Version read = StoreCodec.readVersion(StoreCodec.write(version));

		assertEquals(version, read);
		assertNull(read.firstParent());
		assertEquals("root", read.claimName());
	}

	@Test
	void nullOperatorAndMachineNameRoundTripAsNull() throws IOException {
		Version noOperator = new Version(StoreCodec.FORMAT, V1, List.of(), "m", "Kasse 1", null, AT, "s", false,
				payload("{}"));
		assertEquals(noOperator, StoreCodec.readVersion(StoreCodec.write(noOperator)));

		Version noMachineName = new Version(StoreCodec.FORMAT, V1, List.of(), "m", null, "Anna", AT, "s", false,
				payload("{}"));
		assertEquals(noMachineName, StoreCodec.readVersion(StoreCodec.write(noMachineName)));
	}

	@Test
	void missingOperatorAndMachineNameReadAsNull() throws IOException {
		String json = "{\"format\":1,\"id\":\"" + V1.value() + "\",\"parents\":[],\"machine\":\"m\","
				+ "\"at\":\"2026-10-26T19:04:11.512Z\",\"saveId\":\"s\",\"deleted\":false,\"payload\":{}}";

		Version read = StoreCodec.readVersion(bytes(json));

		assertNull(read.operator());
		assertNull(read.machineName());
	}

	@Test
	void nonTextOperatorIsIOException() {
		String json = "{\"format\":1,\"id\":\"" + V1.value() + "\",\"parents\":[],\"machine\":\"m\","
				+ "\"operator\":5,\"at\":\"2026-10-26T19:04:11.512Z\",\"saveId\":\"s\",\"deleted\":false}";

		assertThrows(IOException.class, () -> StoreCodec.readVersion(bytes(json)));
	}

	@Test
	void firstParentAndClaimNameFollowFirstParent() throws IOException {
		Version version = new Version(StoreCodec.FORMAT, V3, List.of(V2, V1), "m", "Kasse 1", "Anna", AT, "save-1",
				false, payload("{}"));

		assertEquals(V2, version.firstParent());
		assertEquals(V2.value(), version.claimName());
	}

	@Test
	void deletedVersionHasEmptyPayload() throws IOException {
		Version version = new Version(StoreCodec.FORMAT, V2, List.of(V1), "m", "Kasse 1", "Anna", AT, "save-2", true,
				payload("{\"name\":\"Anna\"}"));

		byte[] written = StoreCodec.write(version);
		assertTrue(text(written).contains("\"payload\":{}"));

		Version read = StoreCodec.readVersion(written);
		assertTrue(read.deleted());
		assertEquals(payload("{}"), read.payload());
	}

	@Test
	void missingOrNullPayloadReadsAsEmptyObject() throws IOException {
		String head = "{\"format\":1,\"id\":\"" + V1.value() + "\",\"parents\":[],\"machine\":\"m\","
				+ "\"machineName\":\"K\",\"operator\":\"A\",\"at\":\"2026-10-26T19:04:11.512Z\",\"saveId\":\"s\","
				+ "\"deleted\":false";

		assertEquals(payload("{}"), StoreCodec.readVersion(bytes(head + "}")).payload());
		assertEquals(payload("{}"), StoreCodec.readVersion(bytes(head + ",\"payload\":null}")).payload());
	}

	@Test
	void unknownExtraFieldsAreIgnored() throws IOException {
		String json = "{\"format\":1,\"zukunft\":{\"x\":1},\"id\":\"" + V1.value() + "\",\"parents\":[],"
				+ "\"machine\":\"m\",\"machineName\":\"K\",\"operator\":\"A\",\"at\":\"2026-10-26T19:04:11.512Z\","
				+ "\"saveId\":\"s\",\"deleted\":false,\"payload\":{\"a\":1},\"mehr\":true}";

		Version read = StoreCodec.readVersion(bytes(json));

		assertEquals(V1, read.id());
		assertEquals(payload("{\"a\":1}"), read.payload());
	}

	private static Version versionWith(ConflictNote conflict, Map<String, Long> seen) throws IOException {
		return new Version(StoreCodec.FORMAT, V3, List.of(V1, V2), "k4m9q2x8-1234", "Kasse 1", "Anna", AT, "save-1", false,
				payload("{\"a\":1}"), conflict, seen);
	}

	private static ConflictNote fullNote() throws IOException {
		return new ConflictNote(ConflictNote.Kind.FIELDS, "k4m9q2x8-1234",
				new ConflictNote.Party("p7r2t5w1-9999", "Kasse 2", "Berta", V2),
				List.of(new ConflictNote.FieldConflict("Kunde", payload("{\"name\":\"A\"}"), payload("{\"name\":\"B\"}")),
						new ConflictNote.FieldConflict("Bezahlung", payload("{\"paid\":true}"), payload("{\"paid\":false}"))),
				UnitKey.booking("e1", "p1", "s7"), payload("{\"seat\":\"s8\",\"row\":3}"), V1);
	}

	@Test
	void versionWithConflictNoteRoundTrips() throws IOException {
		Version version = versionWith(fullNote(), Map.of());

		byte[] written = StoreCodec.write(version);
		String json = text(written);
		assertTrue(json.contains("\"conflict\":{\"kind\":\"FIELDS\""), json);
		assertTrue(json.contains("\"moved\":\"events/e1/presentations/p1/bookings/s7\""), json);

		Version read = StoreCodec.readVersion(written);
		assertEquals(version, read);
		assertEquals(fullNote(), read.conflict());
	}

	@Test
	void conflictNoteWithOnlyRequiredPartsRoundTrips() throws IOException {
		ConflictNote note = new ConflictNote(ConflictNote.Kind.DOUBLE_SALE, "k4m9q2x8-1234", null, null, null, null, null);
		Version version = versionWith(note, Map.of());

		String json = text(StoreCodec.write(version));
		assertFalse(json.contains("\"other\""), json);
		assertFalse(json.contains("\"fields\""), json);
		assertFalse(json.contains("\"moved\""), json);
		assertFalse(json.contains("\"place\""), json);
		assertFalse(json.contains("\"dropped\""), json);

		Version read = StoreCodec.readVersion(StoreCodec.write(version));
		assertEquals(version, read);
		assertEquals(List.of(), read.conflict().fields());
	}

	@Test
	void conflictNoteWithoutNamesInPartyRoundTrips() throws IOException {
		ConflictNote note = new ConflictNote(ConflictNote.Kind.DELETE_WINS, "k4m9q2x8-1234",
				new ConflictNote.Party("p7r2t5w1-9999", null, null, V2), List.of(), null, null, V2);
		Version version = versionWith(note, Map.of());

		assertEquals(version, StoreCodec.readVersion(StoreCodec.write(version)));
	}

	@Test
	void everyConflictKindRoundTrips() throws IOException {
		for (ConflictNote.Kind kind : ConflictNote.Kind.values()) {
			Version version = versionWith(new ConflictNote(kind, "k4m9q2x8-1234", null, null, null, null, null), Map.of());

			assertEquals(version, StoreCodec.readVersion(StoreCodec.write(version)));
		}
	}

	@Test
	void versionWithSeenRoundTrips() throws IOException {
		Version version = versionWith(null, Map.of("k4m9q2x8-1234", 17L, "p7r2t5w1-9999", 3L));

		String json = text(StoreCodec.write(version));
		assertTrue(json.contains("\"seen\":{\"k4m9q2x8-1234\":17,\"p7r2t5w1-9999\":3}"), json);

		Version read = StoreCodec.readVersion(StoreCodec.write(version));
		assertEquals(version, read);
		assertEquals(Map.of("k4m9q2x8-1234", 17L, "p7r2t5w1-9999", 3L), read.seen());
	}

	@Test
	void versionWithoutNewFieldsWritesNeitherField() throws IOException {
		Version version = new Version(StoreCodec.FORMAT, V3, List.of(V1), "m", "Kasse 1", "Anna", AT, "save-1", false,
				payload("{\"a\":1}"));

		String json = text(StoreCodec.write(version));
		assertFalse(json.contains("conflict"), json);
		assertFalse(json.contains("seen"), json);

		Version read = StoreCodec.readVersion(StoreCodec.write(version));
		assertNull(read.conflict());
		assertEquals(Map.of(), read.seen());
	}

	@Test
	void versionWithNullSeenHasEmptySeen() throws IOException {
		assertEquals(Map.of(), versionWith(null, null).seen());
	}

	@Test
	void unknownFieldsInVersionAreIgnored() throws IOException {
		String json = "{\"format\":1,\"future\":1,\"id\":\"" + V1.value() + "\",\"parents\":[],\"machine\":\"m\","
				+ "\"at\":\"2026-10-26T19:04:11.512Z\",\"saveId\":\"s\",\"deleted\":false,\"payload\":{},"
				+ "\"conflict\":{\"kind\":\"UNMERGED\",\"affected\":\"k4m9q2x8-1234\",\"future\":[1]},\"seen\":{\"m\":1},\"later\":{}}";

		Version read = StoreCodec.readVersion(bytes(json));

		assertEquals(1, read.format());
		assertEquals(ConflictNote.Kind.UNMERGED, read.conflict().kind());
		assertEquals(Map.of("m", 1L), read.seen());
	}

	@Test
	void unknownConflictKindDropsTheNoteButKeepsTheVersionReadable() throws IOException {
		String json = "{\"format\":1,\"id\":\"" + V1.value() + "\",\"parents\":[],\"machine\":\"m\","
				+ "\"at\":\"2026-10-26T19:04:11.512Z\",\"saveId\":\"s\",\"deleted\":false,\"payload\":{\"a\":1},"
				+ "\"conflict\":{\"kind\":\"NEUE_ART\",\"affected\":\"k4m9q2x8-1234\",\"neu\":[1]},\"seen\":{\"m\":4}}";

		Version read = StoreCodec.readVersion(bytes(json));

		assertNull(read.conflict());
		assertEquals(payload("{\"a\":1}"), read.payload());
		assertEquals(Map.of("m", 4L), read.seen());
	}

	@Test
	void unknownMovedPathDropsOnlyMovedButKeepsTheNote() throws IOException {
		String json = "{\"format\":1,\"id\":\"" + V1.value() + "\",\"parents\":[],\"machine\":\"m\","
				+ "\"at\":\"2026-10-26T19:04:11.512Z\",\"saveId\":\"s\",\"deleted\":false,\"payload\":{\"a\":1},"
				+ "\"conflict\":{\"kind\":\"DOUBLE_SALE\",\"affected\":\"k4m9q2x8-1234\",\"moved\":\"zukunft/neue/art\","
				+ "\"dropped\":\"" + V2.value() + "\"}}";

		Version read = StoreCodec.readVersion(bytes(json));

		assertEquals(ConflictNote.Kind.DOUBLE_SALE, read.conflict().kind());
		assertNull(read.conflict().moved());
		assertEquals(V2, read.conflict().dropped());
		assertEquals(payload("{\"a\":1}"), read.payload());
	}

	@Test
	void versionWithoutNewFieldsHasExactlyTheKnownBytes() throws IOException {
		Version version = new Version(StoreCodec.FORMAT, V3, List.of(V1), "m", "Kasse 1", "Anna", AT, "save-1", false,
				payload("{\"a\":1}"));

		assertEquals("{\"format\":1,\"id\":\"00000000003-p7r2t5w1-0000\",\"parents\":[\"00000000001-k4m9q2x8-0001\"],"
				+ "\"machine\":\"m\",\"machineName\":\"Kasse 1\",\"operator\":\"Anna\",\"at\":\"2026-10-26T19:04:11.512Z\","
				+ "\"saveId\":\"save-1\",\"deleted\":false,\"payload\":{\"a\":1}}", text(StoreCodec.write(version)));
	}

	@Test
	void malformedConflictOrSeenIsIOException() {
		String head = "{\"format\":1,\"id\":\"" + V1.value() + "\",\"parents\":[],\"machine\":\"m\","
				+ "\"at\":\"2026-10-26T19:04:11.512Z\",\"saveId\":\"s\",\"deleted\":false,\"payload\":{},";

		String note = "\"conflict\":{\"kind\":\"FIELDS\",\"affected\":\"x\",";
		for (String members : List.of("\"conflict\":5", "\"conflict\":{\"affected\":\"x\"}",
				"\"conflict\":{\"kind\":5,\"affected\":\"x\"}", note + "\"fields\":{}}",
				note + "\"fields\":[{\"group\":\"g\",\"mine\":{},\"theirs\":1}]}", note + "\"moved\":5}",
				note + "\"place\":[]}", note + "\"dropped\":\"kaputt\"}", note + "\"other\":{\"machine\":\"m\"}}",
				"\"seen\":[1]", "\"seen\":{\"m\":\"viele\"}", "\"seen\":{\"m\":1.5}")) {
			String json = head + members + "}";
			assertThrows(IOException.class, () -> StoreCodec.readVersion(bytes(json)), json);
		}
	}

	@Test
	void roundTripClaimAndFeed() throws IOException {
		Claim claim = new Claim(V2, StoreCodec.sha256(bytes("abc")), "k4m9q2x8-1234", AT);
		byte[] claimBytes = StoreCodec.write(claim);
		assertTrue(text(claimBytes).contains("\"format\":1"));
		assertEquals(claim, StoreCodec.readClaim(claimBytes));

		Feed feed = new Feed(StoreCodec.FORMAT, "k4m9q2x8-1234", 17L, AT,
				List.of(new FeedEntry("event/e1/customers/c1", V2, "root"), new FeedEntry("event/e1/hall", V3, V2.value())));
		byte[] feedBytes = StoreCodec.write(feed);
		assertTrue(text(feedBytes).contains("\"format\":1"));
		assertEquals(feed, StoreCodec.readFeed(feedBytes));
	}

	@Test
	void emptyFeedRoundTrips() throws IOException {
		Feed feed = new Feed(StoreCodec.FORMAT, "m", 0L, AT, List.of());

		assertEquals(feed, StoreCodec.readFeed(StoreCodec.write(feed)));
	}

	@Test
	void unknownFormatIsReported() {
		UnknownFormatException version = assertThrows(UnknownFormatException.class,
				() -> StoreCodec.readVersion(bytes("{\"format\":2,\"id\":\"neu\"}")));
		assertEquals(2, version.format());

		UnknownFormatException claim = assertThrows(UnknownFormatException.class,
				() -> StoreCodec.readClaim(bytes("{\"format\":3}")));
		assertEquals(3, claim.format());

		UnknownFormatException feed = assertThrows(UnknownFormatException.class,
				() -> StoreCodec.readFeed(bytes("{\"format\":2,\"seq\":\"anders\"}")));
		assertEquals(2, feed.format());
	}

	@Test
	void malformedJsonIsIOException() {
		IOException e = assertThrows(IOException.class, () -> StoreCodec.readVersion(bytes("{nicht json")));
		assertFalse(e instanceof UnknownFormatException);
		assertThrows(IOException.class, () -> StoreCodec.readClaim(bytes("{nicht json")));
		assertThrows(IOException.class, () -> StoreCodec.readFeed(bytes("")));
	}

	@Test
	void missingFormatIsMalformedNotUnknownFormat() {
		IOException e = assertThrows(IOException.class, () -> StoreCodec.readClaim(bytes("{\"version\":\"x\"}")));
		assertFalse(e instanceof UnknownFormatException);

		IOException notAnObject = assertThrows(IOException.class, () -> StoreCodec.readFeed(bytes("[1]")));
		assertFalse(notAnObject instanceof UnknownFormatException);

		IOException notNumeric = assertThrows(IOException.class, () -> StoreCodec.readFeed(bytes("{\"format\":\"1\"}")));
		assertFalse(notNumeric instanceof UnknownFormatException);
	}

	@Test
	void invalidFieldsAreIOException() {
		String badId = "{\"format\":1,\"version\":\"kaputt\",\"sha256\":\"x\",\"machine\":\"m\",\"at\":\"2026-10-26T19:04:11.512Z\"}";
		assertThrows(IOException.class, () -> StoreCodec.readClaim(bytes(badId)));

		String badAt = "{\"format\":1,\"version\":\"" + V1.value() + "\",\"sha256\":\"x\",\"machine\":\"m\",\"at\":\"gestern\"}";
		assertThrows(IOException.class, () -> StoreCodec.readClaim(bytes(badAt)));

		String missingAt = "{\"format\":1,\"version\":\"" + V1.value() + "\",\"sha256\":\"x\",\"machine\":\"m\"}";
		assertThrows(IOException.class, () -> StoreCodec.readClaim(bytes(missingAt)));
	}

	@Test
	void sha256OfKnownBytes() {
		assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
				StoreCodec.sha256("abc".getBytes(StandardCharsets.UTF_8)));
	}

	@Test
	void writeIsDeterministic() throws IOException {
		Version version = new Version(StoreCodec.FORMAT, V3, List.of(V1, V2), "m", "Kasse 1", "Anna", AT, "save-1", false,
				payload("{\"b\":1,\"a\":2}"));

		assertEquals(text(StoreCodec.write(version)), text(StoreCodec.write(version)));
	}
}
