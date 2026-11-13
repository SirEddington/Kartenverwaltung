package de.eltviller_carneval_verein.karten.update;

/** Die Patchnotes-Datei verletzt eine ihrer Regeln (KARV-125); nennt Zeile und Grund. */
public final class InvalidPatchNotesException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final int line;

	/**
	 * @param line   die Zeile (ab 1), 0 für die ganze Datei
	 * @param reason der Grund auf Deutsch
	 */
	public InvalidPatchNotesException(int line, String reason) {
		super(line > 0 ? "patchnotes.md, Zeile " + line + ": " + reason : "patchnotes.md: " + reason);
		this.line = line;
	}

	/** Die Zeile (ab 1), 0 für die ganze Datei. */
	public int line() {
		return line;
	}
}
