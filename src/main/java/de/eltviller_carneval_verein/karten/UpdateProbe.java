package de.eltviller_carneval_verein.karten;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;

import dev.hydraulic.conveyor.control.SoftwareUpdateController;

/**
 * Nur Spike KARV-130, wird nicht gemergt: Schreibt beim Start auf, was Conveyor der App zum Thema Updates mitgibt, nach
 * {@code <Datenordner>/update-probe.txt}. Liegt dort die Datei {@code update-trigger}, wird sie gelöscht und danach
 * {@link SoftwareUpdateController#triggerUpdateCheckUI()} aufgerufen.
 */
final class UpdateProbe {

	private UpdateProbe() {
	}

	static void runInBackground() {
		Thread t = new Thread(UpdateProbe::run, "update-probe");
		t.setDaemon(true);
		t.start();
	}

	private static void run() {
		File dir = AppPaths.appDataDir();
		dir.mkdirs();
		File out = new File(dir, "update-probe.txt");
		StringBuilder sb = new StringBuilder();
		sb.append("=== ").append(LocalDateTime.now()).append('\n');
		for (String key : new String[] { "app.version", "app.revision", "app.repositoryUrl", "app.dir", "karten.appDataDir",
				"java.home", "jdk.module.path", "java.class.path" }) {
			sb.append(key).append(" = ").append(System.getProperty(key)).append('\n');
		}
		sb.append("module = ").append(UpdateProbe.class.getModule().getName()).append('\n');
		SoftwareUpdateController controller = null;
		try {
			controller = SoftwareUpdateController.getInstance();
			sb.append("controller = ").append(controller == null ? "null" : controller.getClass().getName()).append('\n');
			if (controller != null) {
				sb.append("currentVersion = ").append(describe(controller.getCurrentVersion())).append('\n');
				sb.append("canTriggerUpdateCheckUI = ").append(controller.canTriggerUpdateCheckUI()).append('\n');
				try {
					sb.append("versionFromRepository = ").append(describe(controller.getCurrentVersionFromRepository()))
							.append('\n');
				} catch (Exception e) {
					sb.append("versionFromRepository FEHLER = ").append(e).append('\n');
				}
			}
		} catch (Throwable e) {
			sb.append("controller FEHLER = ").append(e).append('\n');
		}
		sb.append("javax.net.ssl.trustStoreType = ").append(System.getProperty("javax.net.ssl.trustStoreType")).append('\n');
		sb.append("https Standard = ").append(httpsCheck(null)).append('\n');
		try {
			java.security.KeyStore ks = java.security.KeyStore.getInstance("Windows-ROOT");
			ks.load(null, null);
			javax.net.ssl.TrustManagerFactory tmf = javax.net.ssl.TrustManagerFactory
					.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
			tmf.init(ks);
			javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLS");
			ctx.init(null, tmf.getTrustManagers(), null);
			sb.append("https Windows-ROOT = ").append(httpsCheck(ctx.getSocketFactory())).append('\n');
		} catch (Exception e) {
			sb.append("Windows-ROOT FEHLER = ").append(e).append('\n');
		}
		File trigger = new File(dir, "update-trigger");
		boolean doTrigger = controller != null && trigger.isFile() && trigger.delete();
		sb.append("trigger = ").append(doTrigger).append('\n');
		write(out, sb.toString());
		if (doTrigger) {
			try {
				controller.triggerUpdateCheckUI();
				write(out, "triggerUpdateCheckUI zurückgekehrt " + LocalDateTime.now() + '\n');
			} catch (Throwable e) {
				write(out, "triggerUpdateCheckUI FEHLER = " + e + '\n');
			}
		}
	}

	/** HTTPS-Abruf der metadata.properties der echten neuesten Release, mit Standard- oder eigener Socket-Fabrik. */
	private static String httpsCheck(javax.net.ssl.SSLSocketFactory factory) {
		try {
			javax.net.ssl.HttpsURLConnection c = (javax.net.ssl.HttpsURLConnection) java.net.URI
					.create("https://github.com/SirEddington/Kartenverwaltung/releases/latest/download/metadata.properties")
					.toURL().openConnection();
			if (factory != null) {
				c.setSSLSocketFactory(factory);
			}
			c.setConnectTimeout(10_000);
			c.setReadTimeout(10_000);
			int code = c.getResponseCode();
			String version = "?";
			if (code == 200) {
				try (java.io.InputStream in = c.getInputStream()) {
					version = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
							.filter(l -> l.startsWith("app.version=")).findFirst().orElse("?");
				}
			}
			return "HTTP " + code + ", " + version;
		} catch (Exception e) {
			return "FEHLER " + e;
		}
	}

	private static String describe(SoftwareUpdateController.Version v) {
		return v == null ? "null" : v.getVersion() + " rev " + v.getRevision();
	}

	private static void write(File out, String text) {
		try {
			Files.writeString(out.toPath(), text, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
					StandardOpenOption.APPEND);
		} catch (IOException e) {
			System.err.println("update-probe: " + e);
		}
	}
}
