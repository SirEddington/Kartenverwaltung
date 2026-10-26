package de.eltviller_carneval_verein.karten.store;

import java.io.IOException;

/**
 * Im gemeinsamen Ordner liegt unter dem Namen einer Versionsdatei eine Datei mit anderem Inhalt als der, der
 * hochgeladen werden sollte. Nichts wurde überschrieben.
 */
public class ContentMismatchException extends IOException {

	private static final long serialVersionUID = 1L;

	private final String relativePath;

	/**
	 * @param relativePath Pfad der Versionsdatei relativ zum Datenordner, mit {@code /} getrennt
	 */
	public ContentMismatchException(String relativePath) {
		super("Im gemeinsamen Ordner liegt " + relativePath + " mit anderem Inhalt als lokal. Es wurde nichts überschrieben.");
		this.relativePath = relativePath;
	}

	/** Pfad der Versionsdatei relativ zum Datenordner, mit {@code /} getrennt. */
	public String relativePath() {
		return relativePath;
	}
}
