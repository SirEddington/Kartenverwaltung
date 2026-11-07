package de.eltviller_carneval_verein.karten.update;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Die Update-Quelle über HTTP(S) mit dem Standard-Zertifikatsspeicher von Java (Weg W3, KARV-131): kein Hilfsprozess,
 * kein Windows-Zertifikatsspeicher. Weiterleitungen werden gefolgt (GitHub leitet {@code latest/download} zweimal
 * weiter). Die {@code .appinstaller}-Datei landet in {@code dir}; in der App ist das ausdrücklich
 * {@code Path.of(System.getProperty("java.io.tmpdir"))}, denn der Conveyor-Launcher biegt den Temp-Ordner erst nach
 * dem Start auf den Paketordner um, und JDK-Aufrufe ohne Ordner nähmen den alten, virtualisierten Wert.
 */
public final class HttpUpdateSource implements UpdateSource {

	private static final Logger LOG = Logger.getLogger(HttpUpdateSource.class.getName());
	private static final String PREFIX = "ecv-update-";
	private static final String SUFFIX = ".appinstaller";

	private final Installation inst;
	private final Duration timeout;
	private final int maxBytes;
	private final Path dir;
	private final Clock clock;

	/**
	 * @param timeout  Zeitlimit für Verbinden und Lesen, je Weiterleitung
	 * @param maxBytes höchste Größe einer Antwort
	 * @param dir      Ordner für die geladenen {@code .appinstaller}-Dateien
	 * @param clock    nur für {@link #cleanup()}
	 */
	public HttpUpdateSource(Installation inst, Duration timeout, int maxBytes, Path dir, Clock clock) {
		this.inst = inst;
		this.timeout = timeout;
		this.maxBytes = maxBytes;
		this.dir = dir;
		this.clock = clock;
	}

	@Override
	public UpdateInfo fetchInfo() throws IOException {
		HttpURLConnection c = open(inst.metadataUri());
		try (InputStream in = c.getInputStream()) {
			return UpdateInfo.parse(in, maxBytes);
		} finally {
			c.disconnect();
		}
	}

	@Override
	public Path downloadAppInstaller() throws IOException {
		byte[] body;
		HttpURLConnection c = open(inst.appInstallerUri());
		try (InputStream in = c.getInputStream()) {
			body = in.readNBytes(maxBytes + 1);
		} finally {
			c.disconnect();
		}
		if (body.length > maxBytes) {
			throw new InvalidUpdateDataException("Update-Datei größer als " + maxBytes + " Bytes");
		}
		String text = new String(body, StandardCharsets.UTF_8).replace("﻿", "").strip();
		if (!text.startsWith("<?xml") || !text.contains("<AppInstaller")) {
			throw new InvalidUpdateDataException("Keine .appinstaller-Datei: " + inst.appInstallerUri());
		}
		Files.createDirectories(dir);
		Path file = Files.createTempFile(dir, PREFIX + inst.fsname() + "-", SUFFIX);
		Files.write(file, body);
		return file;
	}

	@Override
	public URI appInstallerUri() {
		return inst.appInstallerUri();
	}

	@Override
	public void cleanup() {
		cleanup(dir, clock.instant());
	}

	/** Löscht {@code ecv-update-*.appinstaller} in {@code dir}, die älter als einen Tag sind; fehlt der Ordner, nichts. */
	public static void cleanup(Path dir, Instant now) {
		if (!Files.isDirectory(dir)) {
			return;
		}
		Instant limit = now.minus(Duration.ofDays(1));
		try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, PREFIX + "*" + SUFFIX)) {
			for (Path file : files) {
				try {
					if (Files.getLastModifiedTime(file).toInstant().isBefore(limit)) {
						Files.delete(file);
					}
				} catch (IOException e) {
					LOG.log(Level.WARNING, "Alte Update-Datei ließ sich nicht löschen: " + file, e);
				}
			}
		} catch (IOException | RuntimeException e) {
			LOG.log(Level.WARNING, "Alte Update-Dateien ließen sich nicht aufräumen: " + dir, e);
		}
	}

	private HttpURLConnection open(URI uri) throws IOException {
		HttpURLConnection c = (HttpURLConnection) uri.toURL().openConnection();
		c.setInstanceFollowRedirects(true);
		c.setConnectTimeout((int) timeout.toMillis());
		c.setReadTimeout((int) timeout.toMillis());
		c.setRequestProperty("Cache-Control", "no-cache");
		int code = c.getResponseCode();
		if (code != HttpURLConnection.HTTP_OK) {
			c.disconnect();
			LOG.warning("Update-Quelle antwortete mit HTTP " + code + ": " + uri);
			throw new HttpStatusException(code);
		}
		return c;
	}
}
