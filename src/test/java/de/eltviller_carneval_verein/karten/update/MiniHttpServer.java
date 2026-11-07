package de.eltviller_carneval_verein.karten.update;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Ein winziger HTTP-Server für Tests der Update-Quelle, nur mit {@code java.base} (das Modul liest {@code jdk.httpserver}
 * nicht). Antwortet je Pfad mit fest eingestelltem Status, Kopfzeilen und Inhalt; merkt sich die Kopfzeilen jeder Anfrage.
 */
final class MiniHttpServer implements AutoCloseable {

	record Route(int status, Map<String, String> headers, byte[] body, long delayMs) {
	}

	private final ServerSocket server;
	private final Map<String, Route> routes = new ConcurrentHashMap<>();
	private final Map<String, List<Map<String, String>>> requests = new ConcurrentHashMap<>();

	MiniHttpServer() throws IOException {
		server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
		Thread t = new Thread(this::loop, "mini-http");
		t.setDaemon(true);
		t.start();
	}

	URI base(String path) {
		return URI.create("http://127.0.0.1:" + server.getLocalPort() + path);
	}

	MiniHttpServer route(String path, int status, String body) {
		return route(path, new Route(status, Map.of(), body.getBytes(StandardCharsets.UTF_8), 0));
	}

	MiniHttpServer route(String path, Route route) {
		routes.put(path, route);
		return this;
	}

	MiniHttpServer redirect(String path, String target) {
		return route(path, new Route(302, Map.of("Location", base(target).toString()), new byte[0], 0));
	}

	List<Map<String, String>> requests(String path) {
		return requests.getOrDefault(path, List.of());
	}

	private void loop() {
		while (!server.isClosed()) {
			try {
				Socket s = server.accept();
				Thread t = new Thread(() -> handle(s), "mini-http-conn");
				t.setDaemon(true);
				t.start();
			} catch (IOException e) {
				return;
			}
		}
	}

	private void handle(Socket s) {
		try (s) {
			BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
			String requestLine = in.readLine();
			if (requestLine == null) {
				return;
			}
			String path = requestLine.split(" ")[1];
			Map<String, String> headers = new LinkedHashMap<>();
			for (String line = in.readLine(); line != null && !line.isEmpty(); line = in.readLine()) {
				int colon = line.indexOf(':');
				if (colon > 0) {
					headers.put(line.substring(0, colon).trim().toLowerCase(), line.substring(colon + 1).trim());
				}
			}
			requests.computeIfAbsent(path, p -> new CopyOnWriteArrayList<>()).add(headers);
			Route route = routes.getOrDefault(path, new Route(404, Map.of(), new byte[0], 0));
			if (route.delayMs() > 0) {
				Thread.sleep(route.delayMs());
			}
			StringBuilder head = new StringBuilder("HTTP/1.1 " + route.status() + " X\r\n");
			route.headers().forEach((k, v) -> head.append(k).append(": ").append(v).append("\r\n"));
			head.append("Content-Length: ").append(route.body().length).append("\r\nConnection: close\r\n\r\n");
			OutputStream out = s.getOutputStream();
			out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
			out.write(route.body());
			out.flush();
		} catch (IOException | InterruptedException e) {
			// Verbindung vom Client geschlossen (z. B. Zeitlimit im Test)
		}
	}

	@Override
	public void close() throws IOException {
		server.close();
	}
}
