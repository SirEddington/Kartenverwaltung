package de.eltviller_carneval_verein.karten.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HttpUpdateSourceTest {

	private static final Instant NOW = Instant.parse("2026-11-07T13:00:00Z");
	private static final String APPINSTALLER = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
			+ "<AppInstaller xmlns=\"http://schemas.microsoft.com/appx/appinstaller/2018\" Version=\"1.1.0.2\"></AppInstaller>\n";

	@TempDir
	Path temp;

	private MiniHttpServer server;

	@BeforeEach
	void startServer() throws IOException {
		server = new MiniHttpServer();
	}

	@AfterEach
	void stopServer() throws IOException {
		server.close();
	}

	private HttpUpdateSource source(Path dir, Duration timeout) {
		Map<String, String> props = Map.of("app.version", "1.1.0", "app.revision", "1", "app.fsname", "ecv-karten-test",
				"app.repositoryUrl", server.base("/upd").toString(), "os.name", "Linux");
		Installation inst = Installation.from(props::get, Map.<String, String>of()::get).orElseThrow();
		return new HttpUpdateSource(inst, timeout, 65536, dir, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private HttpUpdateSource source() {
		return source(temp.resolve("dl"), Duration.ofSeconds(5));
	}

	private static long count(Path dir) throws IOException {
		if (!Files.isDirectory(dir)) {
			return 0;
		}
		try (Stream<Path> files = Files.list(dir)) {
			return files.count();
		}
	}

	@Test
	void fetchInfoReads200() throws IOException {
		server.route("/upd/metadata.properties", 200, "app.version=1.1.0\napp.revision=2\n");
		assertEquals("1.1.0 (Rev. 2)", source().fetchInfo().version().display());
	}

	@Test
	void notFoundIsHttpStatus404() {
		HttpStatusException e = assertThrows(HttpStatusException.class, () -> source().fetchInfo());
		assertEquals(404, e.code());
	}

	@Test
	void followsRedirect() throws IOException {
		server.redirect("/upd/metadata.properties", "/real/metadata.properties");
		server.route("/real/metadata.properties", 200, "app.version=1.2\n");
		assertEquals("1.2", source().fetchInfo().version().display());
	}

	@Test
	void slowServerTimesOut() {
		server.route("/upd/metadata.properties",
				new MiniHttpServer.Route(200, Map.of(), "app.version=1.2\n".getBytes(StandardCharsets.UTF_8), 2000));
		assertThrows(SocketTimeoutException.class, () -> source(temp, Duration.ofMillis(200)).fetchInfo());
	}

	@Test
	void sendsNoCacheHeader() throws IOException {
		server.route("/upd/metadata.properties", 200, "app.version=1.2\n");
		source().fetchInfo();
		assertEquals("no-cache", server.requests("/upd/metadata.properties").get(0).get("cache-control"));
	}

	@Test
	void downloadsIntoNewDirectory() throws IOException {
		server.route("/upd/ecv-karten-test.appinstaller", 200, APPINSTALLER);
		Path dir = temp.resolve("noch/nicht/da");
		Path file = source(dir, Duration.ofSeconds(5)).downloadAppInstaller();
		assertEquals(dir, file.getParent());
		assertTrue(file.getFileName().toString().startsWith("ecv-update-ecv-karten-test-"));
		assertTrue(file.getFileName().toString().endsWith(".appinstaller"));
		assertEquals(APPINSTALLER, Files.readString(file));
	}

	@Test
	void twoDownloadsGiveTwoFiles() throws IOException {
		server.route("/upd/ecv-karten-test.appinstaller", 200, APPINSTALLER);
		HttpUpdateSource source = source();
		assertNotEquals(source.downloadAppInstaller(), source.downloadAppInstaller());
	}

	@Test
	void htmlInsteadOfAppInstallerIsInvalid() throws IOException {
		server.route("/upd/ecv-karten-test.appinstaller", 200, "<!DOCTYPE html><html><body>WLAN-Anmeldung</body></html>");
		Path dir = temp.resolve("dl");
		assertThrows(InvalidUpdateDataException.class, () -> source(dir, Duration.ofSeconds(5)).downloadAppInstaller());
		assertEquals(0, count(dir));
	}

	@Test
	void bomBeforeXmlIsAccepted() throws IOException {
		server.route("/upd/ecv-karten-test.appinstaller", 200, "﻿  " + APPINSTALLER);
		assertTrue(Files.size(source().downloadAppInstaller()) > 0);
	}

	@Test
	void appInstallerUriUsesFsname() {
		assertEquals(server.base("/upd/ecv-karten-test.appinstaller"), source().appInstallerUri());
	}

	@Test
	void cleanupDeletesOnlyOldFiles() throws IOException {
		Path old = Files.writeString(temp.resolve("ecv-update-x-1.appinstaller"), "a");
		Path fresh = Files.writeString(temp.resolve("ecv-update-x-2.appinstaller"), "b");
		Path foreign = Files.writeString(temp.resolve("anderes.appinstaller"), "c");
		Files.setLastModifiedTime(old, FileTime.from(NOW.minus(Duration.ofDays(2))));
		Files.setLastModifiedTime(fresh, FileTime.from(NOW.minus(Duration.ofHours(1))));
		Files.setLastModifiedTime(foreign, FileTime.from(NOW.minus(Duration.ofDays(5))));
		HttpUpdateSource.cleanup(temp, NOW);
		assertFalse(Files.exists(old));
		assertTrue(Files.exists(fresh));
		assertTrue(Files.exists(foreign));
	}

	@Test
	void cleanupWithMissingDirDoesNothing() {
		HttpUpdateSource.cleanup(temp.resolve("fehlt"), NOW);
		source(temp.resolve("fehlt"), Duration.ofSeconds(1)).cleanup();
		assertFalse(Files.exists(temp.resolve("fehlt")));
	}
}
