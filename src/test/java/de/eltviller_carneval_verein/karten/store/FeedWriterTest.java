package de.eltviller_carneval_verein.karten.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FeedWriterTest {

	private static final Instant AT = Instant.parse("2026-10-26T10:00:00Z");
	private static final Clock CLOCK = Clock.fixed(AT, ZoneOffset.UTC);
	private static final String ME = "maschine-1";

	private static final VersionId V1 = VersionId.create(1_000_000L, "aaaaaaaa", 0);
	private static final VersionId V2 = VersionId.create(2_000_000L, "aaaaaaaa", 1);

	@TempDir
	Path dir;

	private VersionStore shared() {
		return new VersionStore(dir.resolve("shared"), new NioFileLayer());
	}

	private static void put(VersionStore store, String rel, String content) throws IOException {
		store.createRelative(rel, content.getBytes(StandardCharsets.UTF_8));
	}

	@Test
	void numbersContinueAfterExisting() throws IOException {
		VersionStore shared = shared();
		put(shared, "feeds/" + ME + "/1.json", "{}");
		put(shared, "feeds/" + ME + "/7.json", "{}");
		// Fremde Namen zählen nicht
		put(shared, "feeds/" + ME + "/notes.txt", "x");
		put(shared, "feeds/" + ME + "/99.txt", "x");
		put(shared, "feeds/" + ME + "/0.json", "{}");
		put(shared, "feeds/" + ME + "/-3.json", "{}");
		put(shared, "feeds/" + ME + "/abc.json", "{}");
		// Die Liste eines anderen Rechners zählt nicht
		put(shared, "feeds/maschine-2/50.json", "{}");
		FeedWriter writer = new FeedWriter(shared, ME, CLOCK);
		FeedEntry first = new FeedEntry("events/e1", V1, "root");
		FeedEntry second = new FeedEntry("events/e1", V2, V1.value());

		assertEquals(8L, writer.append(List.of(first)));
		assertEquals(9L, writer.append(List.of(first, second)));

		Feed feed = StoreCodec.readFeed(shared.readRelative("feeds/" + ME + "/9.json").orElseThrow());
		assertEquals(StoreCodec.FORMAT, feed.format());
		assertEquals(ME, feed.machine());
		assertEquals(9L, feed.seq());
		assertEquals(AT, feed.at());
		assertEquals(List.of(first, second), feed.entries());
	}

	@Test
	void firstNumberIsOneWithoutFeeds() throws IOException {
		FeedWriter writer = new FeedWriter(shared(), ME, CLOCK);

		assertEquals(1L, writer.append(List.of(new FeedEntry("events/e1", V1, "root"))));
	}

	@Test
	void foreignWriterWithSameIdIsDetected() throws IOException {
		VersionStore shared = shared();
		FeedWriter writer = new FeedWriter(shared, ME, CLOCK);
		List<FeedEntry> entries = List.of(new FeedEntry("events/e1", V1, "root"));
		assertEquals(1L, writer.append(entries));
		// Ein anderer Rechner mit derselben Kennung belegt die nächste Nummer
		put(shared, "feeds/" + ME + "/2.json", "fremd");

		DuplicateMachineException e = assertThrows(DuplicateMachineException.class, () -> writer.append(entries));

		assertTrue(e.getMessage().contains("2"), e.getMessage());
		assertEquals("fremd", new String(shared.readRelative("feeds/" + ME + "/2.json").orElseThrow(), StandardCharsets.UTF_8));
	}
}
