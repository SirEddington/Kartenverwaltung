package de.eltviller_carneval_verein.karten.update;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.cert.CertificateException;

import javax.net.ssl.SSLHandshakeException;

import org.junit.jupiter.api.Test;

class GrundTest {

	/** Wie sun.security.validator.ValidatorException: eine Unterklasse von CertificateException. */
	private static final class FakeValidatorException extends CertificateException {
		private static final long serialVersionUID = 1L;

		FakeValidatorException(String msg) {
			super(msg);
		}
	}

	private static <T extends Throwable> T causedBy(T outer, Throwable cause) {
		outer.initCause(cause);
		return outer;
	}

	private static IOException tunnel(String status) {
		return new IOException("Unable to tunnel through proxy. Proxy returns \"" + status + "\"");
	}

	@Test
	void certificateDeepInChain() {
		Throwable t = causedBy(new IOException("wrapper"),
				causedBy(new SSLHandshakeException("PKIX path building failed"), new CertificateException("untrusted")));
		assertEquals(Grund.CERTIFICATE, Grund.of(t));
	}

	@Test
	void validatorExceptionIsCertificate() {
		assertEquals(Grund.CERTIFICATE,
				Grund.of(causedBy(new SSLHandshakeException("x"), new FakeValidatorException("PKIX"))));
	}

	@Test
	void certificateWinsOverTimeoutLaterInChain() {
		Throwable t = causedBy(new SocketTimeoutException("x"), new CertificateException("y"));
		assertEquals(Grund.CERTIFICATE, Grund.of(t));
	}

	@Test
	void handshakeWithoutCertificateCauseIsNetwork() {
		assertEquals(Grund.NETWORK, Grund.of(new SSLHandshakeException("Remote host terminated the handshake")));
	}

	@Test
	void socketTimeoutIsTimeout() {
		assertEquals(Grund.TIMEOUT, Grund.of(new SocketTimeoutException("Read timed out")));
		assertEquals(Grund.TIMEOUT, Grund.of(causedBy(new IOException("x"), new SocketTimeoutException("Connect timed out"))));
	}

	@Test
	void tunnel407IsProxy() {
		assertEquals(Grund.PROXY, Grund.of(tunnel("HTTP/1.1 407 Proxy Authentication Required")));
	}

	@Test
	void tunnel403IsProxy() {
		assertEquals(Grund.PROXY, Grund.of(tunnel("HTTP/1.1 403 Forbidden")));
	}

	@Test
	void http407IsProxy() {
		assertEquals(Grund.PROXY, Grund.of(new HttpStatusException(407)));
	}

	@Test
	void tunnel503IsNetwork() {
		assertEquals(Grund.NETWORK, Grund.of(tunnel("HTTP/1.1 503 Service Unavailable")));
	}

	@Test
	void unknownHostConnectNoRouteAreNetwork() {
		assertEquals(Grund.NETWORK, Grund.of(new UnknownHostException("github.com")));
		assertEquals(Grund.NETWORK, Grund.of(new ConnectException("Connection refused")));
		assertEquals(Grund.NETWORK, Grund.of(new NoRouteToHostException("x")));
		assertEquals(Grund.NETWORK, Grund.of(new java.net.SocketException("Connection reset")));
	}

	@Test
	void http5xxIsNetwork() {
		assertEquals(Grund.NETWORK, Grund.of(new HttpStatusException(502)));
		assertEquals(Grund.NETWORK, Grund.of(new HttpStatusException(503)));
	}

	@Test
	void http404IsServer() {
		assertEquals(Grund.SERVER, Grund.of(new HttpStatusException(404)));
	}

	@Test
	void invalidDataIsInvalid() {
		assertEquals(Grund.INVALID, Grund.of(new InvalidUpdateDataException("kaputt")));
	}

	@Test
	void otherIsUnknown() {
		assertEquals(Grund.UNKNOWN, Grund.of(new IllegalStateException("x")));
		assertEquals(Grund.UNKNOWN, Grund.of(new IOException("irgendwas")));
	}
}
