package de.eltviller_carneval_verein.karten.update;

import java.io.IOException;

/** Die Update-Quelle lieferte Daten, die keine gültigen Update-Daten sind (KARV-131, Grund {@code INVALID}). */
public class InvalidUpdateDataException extends IOException {

	private static final long serialVersionUID = 1L;

	public InvalidUpdateDataException(String message) {
		super(message);
	}

	public InvalidUpdateDataException(String message, Throwable cause) {
		super(message, cause);
	}
}
