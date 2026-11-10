package de.eltviller_carneval_verein.karten.update;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Baut kleine {@code .msix}-ähnliche ZIP-Dateien für Tests (KARV-132). */
final class TestMsix {

	/** Inhalt des gespeicherten (nicht komprimierten) Nutzdaten-Eintrags; in der Datei auffindbar. */
	static final byte[] PAYLOAD = "NUTZDATEN-DER-KARTENVERWALTUNG-0123456789".repeat(20).getBytes(StandardCharsets.US_ASCII);
	static final String PAYLOAD_ENTRY = "app/Kartenverwaltung.jar";

	private TestMsix() {
	}

	/** Das Manifest der Probe-Datei (Revision 3). */
	static String probeManifest() {
		try (InputStream in = TestMsix.class.getResourceAsStream("AppxManifest.xml")) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Das Probe-Manifest mit anderer Paketversion. */
	static String manifest(String packageVersion) {
		return probeManifest().replace("Version=\"1.1.0.3\"", "Version=\"" + packageVersion + "\"");
	}

	/** Das Probe-Manifest mit anderem Paketnamen (andere Familie). */
	static String manifestWithName(String name, String packageVersion) {
		return manifest(packageVersion).replace("Name=\"EcvKartenFx132\"", "Name=\"" + name + "\"");
	}

	/** Eine vollständige Datei: Manifest, Nutzdaten (gespeichert), BlockMap, Content-Types, Signatur. */
	static Path standard(Path target, String packageVersion) throws IOException {
		return write(target, manifest(packageVersion), standardExtras());
	}

	static Map<String, byte[]> standardExtras() {
		Map<String, byte[]> extra = new LinkedHashMap<>();
		extra.put(PAYLOAD_ENTRY, PAYLOAD);
		extra.put("AppxBlockMap.xml", "<BlockMap/>".getBytes(StandardCharsets.UTF_8));
		extra.put("[Content_Types].xml", "<Types/>".getBytes(StandardCharsets.UTF_8));
		extra.put("AppxSignature.p7x", new byte[] { 'P', 'K', 'C', 'X', 1, 2, 3 });
		return extra;
	}

	/**
	 * Schreibt eine ZIP-Datei: {@code AppxManifest.xml} komprimiert (fehlt bei {@code manifestXml == null}), die
	 * weiteren Einträge in Reihenfolge; {@link #PAYLOAD_ENTRY} wird gespeichert, damit ein Test darin ein Byte ändern kann.
	 */
	static Path write(Path target, String manifestXml, Map<String, byte[]> extraEntries) throws IOException {
		Files.createDirectories(target.toAbsolutePath().getParent());
		try (OutputStream out = Files.newOutputStream(target); ZipOutputStream zip = new ZipOutputStream(out)) {
			if (manifestXml != null) {
				zip.putNextEntry(new ZipEntry("AppxManifest.xml"));
				zip.write(manifestXml.getBytes(StandardCharsets.UTF_8));
				zip.closeEntry();
			}
			for (Map.Entry<String, byte[]> e : extraEntries.entrySet()) {
				ZipEntry entry = new ZipEntry(e.getKey());
				if (e.getKey().equals(PAYLOAD_ENTRY)) {
					CRC32 crc = new CRC32();
					crc.update(e.getValue());
					entry.setMethod(ZipEntry.STORED);
					entry.setSize(e.getValue().length);
					entry.setCompressedSize(e.getValue().length);
					entry.setCrc(crc.getValue());
				}
				zip.putNextEntry(entry);
				zip.write(e.getValue());
				zip.closeEntry();
			}
		}
		return target;
	}

	/** Ändert ein Byte mitten in den gespeicherten Nutzdaten (Größe bleibt gleich). */
	static void flipPayloadByte(Path file) throws IOException {
		byte[] all = Files.readAllBytes(file);
		int at = indexOf(all, PAYLOAD);
		if (at < 0) {
			throw new IllegalStateException("Nutzdaten nicht gefunden");
		}
		all[at + PAYLOAD.length / 2] ^= 0x01;
		Files.write(file, all);
	}

	private static int indexOf(byte[] haystack, byte[] needle) {
		outer: for (int i = 0; i <= haystack.length - needle.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (haystack[i + j] != needle[j]) {
					continue outer;
				}
			}
			return i;
		}
		return -1;
	}
}
