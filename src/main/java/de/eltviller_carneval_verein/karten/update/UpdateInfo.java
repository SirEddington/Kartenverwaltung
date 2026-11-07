package de.eltviller_carneval_verein.karten.update;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Was die {@code metadata.properties} der Update-Quelle über die neueste Version sagt (KARV-131).
 *
 * @param packageFamilyName {@code windows.package-family-name}, {@code null}, wenn die Quelle keine nennt
 * @param familyChanged     {@code internal.windows.package-family-name-changed}
 */
public record UpdateInfo(AppVersion version, String packageFamilyName, boolean familyChanged) {

	/**
	 * Liest höchstens {@code maxBytes} Bytes als Properties.
	 *
	 * @throws InvalidUpdateDataException bei fehlendem oder ungültigem {@code app.version} bzw. {@code app.revision},
	 *                                    zu großen Daten oder kaputten Escapes
	 */
	public static UpdateInfo parse(InputStream in, int maxBytes) throws IOException {
		byte[] data = in.readNBytes(maxBytes + 1);
		if (data.length > maxBytes) {
			throw new InvalidUpdateDataException("Update-Daten größer als " + maxBytes + " Bytes");
		}
		Properties props = new Properties();
		try {
			props.load(new ByteArrayInputStream(data));
		} catch (IllegalArgumentException e) {
			throw new InvalidUpdateDataException("Update-Daten nicht lesbar: " + e.getMessage(), e);
		}
		AppVersion version = AppVersion.parse(props.getProperty("app.version"), props.getProperty("app.revision"))
				.orElseThrow(() -> new InvalidUpdateDataException("app.version bzw. app.revision fehlt oder ist ungültig: "
						+ props.getProperty("app.version") + " / " + props.getProperty("app.revision")));
		return new UpdateInfo(version, props.getProperty("windows.package-family-name"),
				Boolean.parseBoolean(props.getProperty("internal.windows.package-family-name-changed")));
	}
}
