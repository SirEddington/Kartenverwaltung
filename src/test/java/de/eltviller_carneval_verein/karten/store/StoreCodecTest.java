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
