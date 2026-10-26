package de.eltviller_carneval_verein.karten.store;

import java.io.IOException;

/**
 * Eine Datei des Versionsspeichers hat ein höheres Format, als diese Programmversion kennt. Sie stammt von einem
 * neueren Programm und ist nicht beschädigt.
 */
public class UnknownFormatException extends IOException {

	private static final long serialVersionUID = 1L;

	private final int format;

	/**
	 * @param format das unbekannte Format der Datei
	 */
	public UnknownFormatException(int format) {
		super("Unbekanntes Dateiformat: " + format);
		this.format = format;
	}

	/** Das unbekannte Format der Datei. */
	public int format() {
		return format;
	}
}
