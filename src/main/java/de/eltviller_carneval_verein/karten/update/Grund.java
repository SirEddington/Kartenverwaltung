package de.eltviller_carneval_verein.karten.update;

import java.io.IOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.cert.CertPathBuilderException;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import javax.net.ssl.SSLException;

/**
 * Warum eine Prüfung oder ein Update scheiterte (KARV-131). {@link #of} prüft die Regeln nacheinander, und jede Regel
 * die ganze Ursachenkette, bevor die nächste drankommt: Ein Zertifikatsfehler tief in einer {@code SSLHandshakeException}
 * bleibt so ein Zertifikatsfehler.
 */
public enum Grund {
	/** Kein Netz, Verbindung abgebrochen, vorübergehende Störung (still). */
	NETWORK,
	/** Zeitüberschreitung (still). */
	TIMEOUT,
	/** Zertifikat abgelehnt, etwa weil ein Virenschutz HTTPS aufbricht. */
	CERTIFICATE,
	/** Der Proxy verlangt eine Anmeldung oder sperrt. */
	PROXY,
	/** Die Quelle antwortete mit einem Fehler (nicht 5xx). */
	SERVER,
	/** Die Quelle lieferte keine gültigen Update-Daten. */
	INVALID,
	/** Alles andere. */
	UNKNOWN;

	private static final String TUNNEL = "Unable to tunnel through proxy";

	/** Der Grund zu einer Ausnahme der Prüfung bzw. des Updates. */
	public static Grund of(Throwable t) {
		List<Throwable> chain = chain(t);
		if (any(chain, e -> e instanceof CertificateException || e instanceof CertPathBuilderException
				|| e instanceof CertPathValidatorException)) {
			return CERTIFICATE;
		}
		if (any(chain, e -> e instanceof SocketTimeoutException)) {
			return TIMEOUT;
		}
		if (any(chain, e -> isTunnel(e) && (mentions(e, " 407") || mentions(e, " 403"))
				|| e instanceof HttpStatusException h && h.code() == 407)) {
			return PROXY;
		}
		if (any(chain, e -> e instanceof UnknownHostException || e instanceof SocketException || e instanceof SSLException
				|| isTunnel(e) || e instanceof HttpStatusException h && h.code() >= 500 && h.code() <= 599)) {
			return NETWORK;
		}
		if (any(chain, e -> e instanceof HttpStatusException)) {
			return SERVER;
		}
		if (any(chain, e -> e instanceof InvalidUpdateDataException)) {
			return INVALID;
		}
		return UNKNOWN;
	}

	private static boolean isTunnel(Throwable e) {
		return e instanceof IOException && e.getMessage() != null && e.getMessage().startsWith(TUNNEL);
	}

	private static boolean mentions(Throwable e, String text) {
		return e.getMessage() != null && e.getMessage().contains(text);
	}

	private static boolean any(List<Throwable> chain, Predicate<Throwable> test) {
		return chain.stream().anyMatch(test);
	}

	private static List<Throwable> chain(Throwable t) {
		List<Throwable> chain = new ArrayList<>();
		for (Throwable e = t; e != null && !chain.contains(e); e = e.getCause()) {
			chain.add(e);
		}
		return chain;
	}
}
