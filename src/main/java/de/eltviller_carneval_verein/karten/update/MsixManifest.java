package de.eltviller_carneval_verein.karten.update;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Die Identität einer {@code .msix} aus ihrem {@code AppxManifest.xml} (KARV-132). Eine {@code .msix} ist eine ZIP-Datei;
 * gelesen wird über {@link ZipFile}, das das zentrale Verzeichnis liest und abgeschnittene Dateien erkennt. Die Datei
 * kann untergeschoben sein: Größen sind begrenzt, das XML wird sicher gelesen.
 *
 * @param version aus der Paketversion {@code a.b.c.d} umgerechnet
 */
public record MsixManifest(String name, String publisher, AppVersion version, String architecture) {

	/** Größte {@code .msix}, die gelesen wird. */
	public static final long MAX_MSIX_BYTES = 200L * 1024 * 1024;
	/** Größtes entpacktes {@code AppxManifest.xml}. */
	static final int MAX_MANIFEST_BYTES = 1024 * 1024;
	/** Größte entpackte Gesamtgröße beim vollständigen Prüfen (echte Pakete: rund 150 MB). */
	static final long MAX_UNPACKED_BYTES = 1024L * 1024 * 1024;

	private static final String FOUNDATION = "http://schemas.microsoft.com/appx/manifest/foundation/windows10";

	/** Die Paketfamilie {@code Name_PublisherId}. */
	public String packageFamilyName() {
		return PackageFamily.of(name, publisher);
	}

	/**
	 * Liest die Identität.
	 *
	 * @throws InvalidUpdateDataException bei zu großer Datei, fehlendem oder zu großem Manifest, ungültigem XML oder
	 *                                    fehlender bzw. ungültiger {@code Identity}
	 * @throws IOException                wenn es keine lesbare ZIP-Datei ist
	 */
	public static MsixManifest read(Path msix) throws IOException {
		checkSize(msix);
		byte[] xml;
		try (ZipFile zip = new ZipFile(msix.toFile())) {
			ZipEntry entry = zip.getEntry("AppxManifest.xml");
			if (entry == null) {
				throw new InvalidUpdateDataException("Kein AppxManifest.xml in " + msix.getFileName());
			}
			try (InputStream in = zip.getInputStream(entry)) {
				xml = in.readNBytes(MAX_MANIFEST_BYTES + 1);
			}
		}
		if (xml.length > MAX_MANIFEST_BYTES) {
			throw new InvalidUpdateDataException("AppxManifest.xml größer als " + MAX_MANIFEST_BYTES + " Bytes");
		}
		NodeList ids = SecureXml.parse(xml).getElementsByTagNameNS(FOUNDATION, "Identity");
		if (ids.getLength() != 1) {
			throw new InvalidUpdateDataException("Keine eindeutige Identity im Manifest");
		}
		Element id = (Element) ids.item(0);
		String name = id.getAttribute("Name").strip();
		String publisher = id.getAttribute("Publisher").strip();
		String version = id.getAttribute("Version").strip();
		String arch = id.getAttribute("ProcessorArchitecture").strip();
		if (name.isEmpty() || publisher.isEmpty()) {
			throw new InvalidUpdateDataException("Identity ohne Name oder Publisher");
		}
		AppVersion v = AppVersion.fromPackageVersion(version)
				.orElseThrow(() -> new InvalidUpdateDataException("Ungültige Paketversion im Manifest: " + version));
		return new MsixManifest(name, publisher, v, arch);
	}

	/**
	 * Liest jeden Eintrag vollständig, rechnet seine Prüfsumme CRC32 nach (das tut {@link ZipFile} nicht selbst) und verlangt
	 * {@code AppxSignature.p7x} und {@code AppxBlockMap.xml}. Erkennt abgeschnittene Dateien und Bitfehler; ob die
	 * Signatur gültig ist, prüft erst Windows.
	 *
	 * @throws IOException bei einem Fehler im ZIP oder einer falschen Prüfsumme
	 */
	public static void verifyComplete(Path msix) throws IOException {
		verifyComplete(msix, MAX_UNPACKED_BYTES);
	}

	/** Wie {@link #verifyComplete(Path)}, mit eigener Obergrenze für die entpackte Gesamtgröße (gegen Zip-Bomben). */
	static void verifyComplete(Path msix, long maxUnpacked) throws IOException {
		checkSize(msix);
		long unpacked = 0;
		try (ZipFile zip = new ZipFile(msix.toFile())) {
			if (zip.getEntry("AppxSignature.p7x") == null || zip.getEntry("AppxBlockMap.xml") == null) {
				throw new InvalidUpdateDataException("Signatur oder BlockMap fehlt in " + msix.getFileName());
			}
			byte[] buf = new byte[1 << 16];
			Enumeration<? extends ZipEntry> entries = zip.entries();
			while (entries.hasMoreElements()) {
				ZipEntry e = entries.nextElement();
				CRC32 crc = new CRC32();
				try (InputStream in = zip.getInputStream(e)) {
					for (int n; (n = in.read(buf)) >= 0;) {
						crc.update(buf, 0, n);
						unpacked += n;
						if (unpacked > maxUnpacked) {
							throw new InvalidUpdateDataException(
									"Entpackt größer als " + maxUnpacked + " Bytes: " + msix.getFileName());
						}
					}
				}
				// ZipFile prüft die CRC32 selbst nicht (anders als ZipInputStream): nachrechnen
				if (e.getCrc() != -1 && crc.getValue() != e.getCrc()) {
					throw new ZipException("Falsche Prüfsumme in " + e.getName() + " (" + msix.getFileName() + ")");
				}
			}
		}
	}

	private static void checkSize(Path msix) throws IOException {
		if (Files.size(msix) > MAX_MSIX_BYTES) {
			throw new InvalidUpdateDataException("Datei größer als 200 MB: " + msix.getFileName());
		}
	}
}
