package de.eltviller_carneval_verein.karten.update;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Eine GET-Verbindung zur Update-Quelle mit dem Standard-Zertifikatsspeicher von Java (KARV-131/132): Weiterleitungen
 * werden gefolgt (bei gleichem Protokoll auch über Hostgrenzen, wie GitHub von {@code latest/download} auf seinen
 * Speicher), kein Zwischenspeicher, alles außer HTTP 200 ist ein Fehler.
 */
final class HttpGet {

	private static final Logger LOG = Logger.getLogger(HttpGet.class.getName());

	private HttpGet() {
	}

	/**
	 * @param connect Zeitlimit für den Verbindungsaufbau, je Weiterleitung
	 * @param read    Zeitlimit je Lesen eines Blocks
	 * @throws HttpStatusException bei einem anderen Status als 200 (die Verbindung ist dann schon getrennt)
	 */
	static HttpURLConnection open(URI uri, Duration connect, Duration read) throws IOException {
		HttpURLConnection c = (HttpURLConnection) uri.toURL().openConnection();
		c.setInstanceFollowRedirects(true);
		c.setConnectTimeout((int) connect.toMillis());
		c.setReadTimeout((int) read.toMillis());
		c.setRequestProperty("Cache-Control", "no-cache");
		int code = c.getResponseCode();
		if (code != HttpURLConnection.HTTP_OK) {
			c.disconnect();
			LOG.warning("Update-Quelle antwortete mit HTTP " + code + ": " + uri);
			throw new HttpStatusException(code);
		}
		return c;
	}

	/**
	 * Holt eine kleine Antwort ganz.
	 *
	 * @param max höchste Größe
	 * @throws InvalidUpdateDataException wenn die Antwort größer ist
	 */
	static byte[] fetch(URI uri, Duration connect, Duration read, int max) throws IOException {
		HttpURLConnection c = open(uri, connect, read);
		Optional<byte[]> body;
		try (InputStream in = c.getInputStream()) {
			body = UpdateFiles.readAtMost(in, max);
		} finally {
			c.disconnect();
		}
		return body.orElseThrow(() -> new InvalidUpdateDataException("Antwort größer als " + max + " Bytes: " + uri));
	}
}
