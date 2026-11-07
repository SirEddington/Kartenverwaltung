package de.eltviller_carneval_verein.karten.update;

import java.io.IOException;

/** Die Update-Quelle antwortete mit einem anderen HTTP-Status als 200 (KARV-131). */
public class HttpStatusException extends IOException {

	private static final long serialVersionUID = 1L;

	private final int code;

	public HttpStatusException(int code) {
		super("HTTP " + code);
		this.code = code;
	}

	/** Der HTTP-Status. */
	public int code() {
		return code;
	}
}
