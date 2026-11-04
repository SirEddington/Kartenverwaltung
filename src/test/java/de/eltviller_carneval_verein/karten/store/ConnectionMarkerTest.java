package de.eltviller_carneval_verein.karten.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Die Marke "verbunden" ({@code <AppData>/connection.json}): schreiben, lesen, löschen; unlesbar heißt keine Marke. */
class ConnectionMarkerTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@TempDir
	Path dir;

	private final List<LogRecord> logs = new ArrayList<>();
	private Logger logger;
	private Handler capture;
	private boolean parentHandlers;

	@BeforeEach
	void captureLog() {
		logger = Logger.getLogger(SyncService.class.getName());
		capture = new Handler() {
			@Override
			public void publish(LogRecord record) {
				synchronized (logs) {
					logs.add(record);
				}
			}

			@Override
			public void flush() {
			}

			@Override
			public void close() {
			}
		};
		parentHandlers = logger.getUseParentHandlers();
		logger.addHandler(capture);
		logger.setUseParentHandlers(false);
	}

	@AfterEach
	void releaseLog() {
		logger.removeHandler(capture);
		logger.setUseParentHandlers(parentHandlers);
	}

	private Path marker() {
		return dir.resolve("connection.json");
	}

	@Test
	void writeReadDeleteRoundTrip() throws IOException {
		assertEquals(Optional.empty(), ConnectionMarker.read(dir), "ohne Datei keine Marke");
		Instant at = Instant.parse("2026-10-30T09:15:00Z");
		ConnectionMarker.State incomplete = new ConnectionMarker.State("\\\\server\\karten", at, false);

		ConnectionMarker.write(dir, new NioFileLayer(), incomplete);

		assertEquals(Optional.of(incomplete), ConnectionMarker.read(dir));
		JsonNode json = MAPPER.readTree(Files.readAllBytes(marker()));
		assertEquals("\\\\server\\karten", json.get("dir").asText());
		assertEquals("2026-10-30T09:15:00Z", json.get("at").asText());
		assertFalse(json.get("complete").asBoolean());

		ConnectionMarker.State complete = new ConnectionMarker.State("\\\\server\\karten", at.plusSeconds(60), true);
		ConnectionMarker.write(dir, new NioFileLayer(), complete);
		assertEquals(Optional.of(complete), ConnectionMarker.read(dir), "atomar ersetzt");
		try (Stream<Path> entries = Files.list(dir)) {
			assertEquals(List.of("connection.json"), entries.map(p -> p.getFileName().toString()).toList(),
					"keine temporäre Datei bleibt liegen");
		}

		ConnectionMarker.delete(dir);
		assertFalse(Files.exists(marker()));
		assertEquals(Optional.empty(), ConnectionMarker.read(dir));
		// ohne Marke ist Löschen ohne Wirkung
		ConnectionMarker.delete(dir);
		assertTrue(logs.isEmpty(), "keine Warnung: " + logs);
	}

	@Test
	void unreadableMarkerIsEmpty() throws IOException {
		for (String content : List.of("kein json", "[]", "{\"dir\":\"X\",\"complete\":true}",
				"{\"dir\":\"X\",\"at\":\"gestern\",\"complete\":true}", "{\"dir\":\"X\",\"at\":\"2026-10-30T09:15:00Z\",\"complete\":\"ja\"}",
				"{\"dir\":\"\",\"at\":\"2026-10-30T09:15:00Z\",\"complete\":true}")) {
			Files.writeString(marker(), content, StandardCharsets.UTF_8);
			synchronized (logs) {
				logs.clear();
			}

			assertEquals(Optional.empty(), ConnectionMarker.read(dir), content);

			synchronized (logs) {
				assertEquals(1, logs.size(), content);
				assertEquals(Level.WARNING, logs.get(0).getLevel());
				assertEquals("Die Marke 'verbunden' ist unlesbar: " + marker(), logs.get(0).getMessage());
			}
		}
	}
}
