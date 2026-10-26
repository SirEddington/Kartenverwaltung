package de.eltviller_carneval_verein.karten.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SimulatedCloudTest {

	@TempDir
	Path dir;

	private SimulatedCloud cloud;
	private Path server;
	private Path a;
	private Path b;

	@BeforeEach
	void setUp() {
		server = dir.resolve("server");
		cloud = new SimulatedCloud(server);
		a = cloud.addClient("A");
		b = cloud.addClient("B");
	}

	private static void write(Path file, String text) throws IOException {
		Files.createDirectories(file.getParent());
		Files.writeString(file, text);
	}

	private static List<String> names(Path folder) throws IOException {
		try (Stream<Path> entries = Files.list(folder)) {
			return entries.map(p -> p.getFileName().toString()).sorted().toList();
		}
	}

	@Test
	void newFilesFlowThroughTheServerToOtherClients() throws IOException {
		write(a.resolve("data/x/1.json"), "eins");

		cloud.step("A");
		cloud.step("B");

		assertEquals("eins", Files.readString(server.resolve("data/x/1.json")));
		assertEquals("eins", Files.readString(b.resolve("data/x/1.json")));
	}

	@Test
	void firstUploadWinsAndTheSecondBecomesAConflictCopy() throws IOException {
		write(a.resolve("data/claims/root.json"), "von A");
		write(b.resolve("data/claims/root.json"), "von B");

		cloud.step("A");
		cloud.step("B");

		assertEquals("von A", Files.readString(server.resolve("data/claims/root.json")));
		assertEquals("von A", Files.readString(b.resolve("data/claims/root.json")));
		assertEquals(List.of("root (conflicted copy B).json", "root.json"), names(b.resolve("data/claims")));
		assertEquals("von B", Files.readString(b.resolve("data/claims/root (conflicted copy B).json")));
		assertEquals(List.of("root.json"), names(a.resolve("data/claims")));
		assertEquals(List.of("root.json"), names(server.resolve("data/claims")));

		// eine Konfliktkopie wird nie hochgeladen, auch nicht im nächsten Durchlauf
		cloud.step("B");
		cloud.step("A");
		assertEquals(List.of("root.json"), names(server.resolve("data/claims")));
		assertEquals(List.of("root.json"), names(a.resolve("data/claims")));
	}

	@Test
	void changeByOneClientOverwritesTheServerAndReachesTheOther() throws IOException {
		write(a.resolve("data/x.json"), "alt");
		cloud.step("A");
		cloud.step("B");

		Files.writeString(a.resolve("data/x.json"), "neu");
		cloud.step("A");
		cloud.step("B");

		assertEquals("neu", Files.readString(server.resolve("data/x.json")));
		assertEquals("neu", Files.readString(b.resolve("data/x.json")));
		assertEquals(List.of("x.json"), names(b.resolve("data")));
	}

	@Test
	void stepFilesOnlyTransfersJustTheSelectedFiles() throws IOException {
		write(a.resolve("data/feeds/m/1.json"), "liste");
		write(a.resolve("data/units/v.json"), "version");
		cloud.step("A");

		cloud.stepFilesOnly("B", rel -> rel.startsWith("data/feeds/"));

		assertTrue(Files.exists(b.resolve("data/feeds/m/1.json")));
		assertFalse(Files.exists(b.resolve("data/units/v.json")));

		cloud.step("B");

		assertTrue(Files.exists(b.resolve("data/units/v.json")));
	}

	@Test
	void temporaryFilesAreIgnored() throws IOException {
		write(a.resolve("data/~karv-123.tmp"), "halb");
		write(a.resolve("data/x.json"), "fertig");

		cloud.step("A");
		cloud.step("B");

		assertEquals(List.of("x.json"), names(server.resolve("data")));
		assertEquals(List.of("x.json"), names(b.resolve("data")));
	}
}
