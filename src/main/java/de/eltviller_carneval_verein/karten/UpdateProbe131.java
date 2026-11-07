package de.eltviller_carneval_verein.karten;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;

/**
 * Nur Spike KARV-131, wird nicht gemergt: probiert einen Update-Weg aus, gesteuert ueber die Systemeigenschaft
 * {@code karten.probe} (W1, W2 oder W3). Ausgeloest wird die Probe 20 s nach dem Start, wenn im Datenordner die Datei
 * {@code probe-go} liegt; sie wird dabei geloescht. Protokoll: {@code <Datenordner>/probe.txt}.
 */
final class UpdateProbe131 {

	private UpdateProbe131() {
	}

	static void runInBackground() {
		Thread t = new Thread(UpdateProbe131::run, "update-probe-131");
		t.setDaemon(true);
		t.start();
	}

	private static void run() {
		File dir = AppPaths.appDataDir();
		File log = new File(dir, "probe.txt");
		try {
			Thread.sleep(20_000);
			dir.mkdirs();
			log("Start " + System.getProperty("app.version") + " rev " + System.getProperty("app.revision") + ", Weg "
					+ System.getProperty("karten.probe"), log);
			File go = new File(dir, "probe-go");
			String goText = go.isFile() ? Files.readString(go.toPath(), StandardCharsets.UTF_8).trim() : "";
			if (!go.isFile() || !go.delete()) {
				log("kein probe-go, nichts zu tun", log);
				return;
			}
			switch (System.getProperty("karten.probe", "")) {
			case "W4" -> w4(goText, log);
			case "W1" -> w1(dir, log);
			case "W2" -> w2(log);
			case "W3" -> w3(log);
			default -> log("unbekannter Weg", log);
			}
		} catch (Throwable e) {
			log("FEHLER " + e, log);
		}
	}

	/** W1: PowerShell-Helfer prueft per Windows, installiert per Add-AppxPackage und startet die App neu. */
	private static void w1(File dir, File log) throws Exception {
		File script = new File(dir, "probe-w1.ps1");
		try (InputStream in = UpdateProbe131.class.getResourceAsStream("probe-w1.ps1")) {
			Files.copy(in, script.toPath(), StandardCopyOption.REPLACE_EXISTING);
		}
		ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
				"-WindowStyle", "Hidden", "-File", script.getAbsolutePath(), "-Pattern",
				System.getProperty("karten.probe.pkg"), "-Log", new File(dir, "probe-w1.log").getAbsolutePath());
		pb.redirectErrorStream(true).redirectOutput(new File(dir, "probe-w1.out"));
		Process p = pb.start();
		log("W1: Helfer gestartet, PID " + p.pid(), log);
	}

	/** W2: oeffnet die .appinstaller-Datei der Update-Quelle im Standardbrowser; den Rest macht der App Installer. */
	private static void w2(File log) throws Exception {
		URI uri = URI.create(System.getProperty("app.repositoryUrl") + System.getProperty("app.fsname") + ".appinstaller");
		if (!java.awt.Desktop.isDesktopSupported()
				|| !java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
			log("W2: Desktop.browse nicht unterstuetzt", log);
			return;
		}
		java.awt.Desktop.getDesktop().browse(uri);
		log("W2: geoeffnet " + uri, log);
	}

	/**
	 * W4 (Entwurf KARV-131): laedt die .appinstaller-Datei selbst in den Temp-Ordner des Pakets (oder mit "downloads" in
	 * probe-go in den Download-Ordner), oeffnet sie per Desktop.open und beendet die App wie der Entwurf.
	 */
	private static void w4(String goText, File log) throws Exception {
		String pkgLocal = System.getenv("PACKAGE_LOCALAPPDATA");
		log("W4: PACKAGE_LOCALAPPDATA=" + pkgLocal + ", java.io.tmpdir=" + System.getProperty("java.io.tmpdir")
				+ ", userModelID=" + System.getProperty("app.windows.userModelID") + ", probe-go=" + goText, log);
		if (pkgLocal == null || pkgLocal.isBlank()) {
			log("W4: ABBRUCH, PACKAGE_LOCALAPPDATA leer", log);
			return;
		}
		java.nio.file.Path target = "downloads".equalsIgnoreCase(goText)
				? java.nio.file.Path.of(System.getProperty("user.home"), "Downloads")
				: java.nio.file.Path.of(System.getProperty("java.io.tmpdir"));
		log("W4: Ziel " + target + ", vorher vorhanden: " + Files.isDirectory(target), log);
		Files.createDirectories(target);
		String fsname = System.getProperty("app.fsname");
		URI uri = URI.create(System.getProperty("app.repositoryUrl") + fsname + ".appinstaller");
		HttpURLConnection c = (HttpURLConnection) uri.toURL().openConnection();
		c.setConnectTimeout(10_000);
		c.setReadTimeout(10_000);
		int code = c.getResponseCode();
		if (code != 200) {
			log("W4: HTTP " + code + " fuer " + uri, log);
			return;
		}
		byte[] body;
		try (InputStream in = c.getInputStream()) {
			body = in.readAllBytes();
		}
		java.nio.file.Path file = Files.createTempFile(target, "ecv-update-" + fsname + "-", ".appinstaller");
		Files.write(file, body);
		log("W4: geladen " + body.length + " Bytes nach " + file, log);
		java.awt.Desktop.getDesktop().open(file.toFile());
		log("W4: Desktop.open ohne Ausnahme zurueck, beende die App", log);
		javafx.application.Platform.exit();
	}

	/** W3: Java liest die neueste Version selbst, per HTTPS (Standard-Truststore) und per HTTP von der Testquelle. */
	private static void w3(File log) {
		log("W3 HTTPS GitHub: " + fetch("https://github.com/SirEddington/Kartenverwaltung/releases/latest/download/metadata.properties"), log);
		log("W3 HTTP Testquelle: " + fetch(System.getProperty("app.repositoryUrl") + "metadata.properties"), log);
	}

	private static String fetch(String url) {
		try {
			HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
			c.setConnectTimeout(10_000);
			c.setReadTimeout(10_000);
			int code = c.getResponseCode();
			if (code != 200) {
				return "HTTP " + code;
			}
			try (InputStream in = c.getInputStream()) {
				return "HTTP 200, " + new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
						.filter(l -> l.startsWith("app.version=") || l.startsWith("app.revision="))
						.reduce((a, b) -> a + " " + b).orElse("?");
			}
		} catch (Exception e) {
			return "FEHLER " + e;
		}
	}

	private static void log(String text, File log) {
		try {
			Files.writeString(log.toPath(), LocalTime.now().withNano(0) + " " + text + System.lineSeparator(),
					StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (Exception e) {
			System.err.println("probe: " + e);
		}
	}
}
