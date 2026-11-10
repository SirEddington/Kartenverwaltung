package de.eltviller_carneval_verein.karten.update;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Die Paketfamilie eines MSIX-Pakets aus Name und Publisher, wie Windows sie bildet (KARV-132):
 * {@code <Name>_<PublisherId>}. Die PublisherId sind die ersten 8 Bytes von SHA-256 über den Publisher in UTF-16LE, mit
 * einem angehängten 0-Bit (65 Bit) in 13 Zeichen Base32 mit dem Alphabet {@code 0123456789abcdefghjkmnpqrstvwxyz}.
 */
public final class PackageFamily {

	private static final String ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz";

	private PackageFamily() {
	}

	/** Die Paketfamilie, z. B. {@code EcvKartenFx132_0cs1gtds5d9x0}. */
	public static String of(String name, String publisher) {
		return name + "_" + publisherId(publisher);
	}

	static String publisherId(String publisher) {
		byte[] hash;
		try {
			hash = MessageDigest.getInstance("SHA-256").digest(publisher.getBytes(StandardCharsets.UTF_16LE));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 fehlt", e);
		}
		long bits = 0;
		for (int i = 0; i < 8; i++) {
			bits = bits << 8 | hash[i] & 0xFF;
		}
		StringBuilder id = new StringBuilder(13);
		for (int i = 0; i < 12; i++) {
			id.append(ALPHABET.charAt((int) (bits >>> 59 - 5 * i) & 0x1F));
		}
		// letzte Gruppe: die untersten 4 Bit und das angehängte 0-Bit
		id.append(ALPHABET.charAt((int) (bits & 0xF) << 1));
		return id.toString();
	}
}
