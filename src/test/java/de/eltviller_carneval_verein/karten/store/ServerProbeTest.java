package de.eltviller_carneval_verein.karten.store;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;

import org.junit.jupiter.api.Test;

class ServerProbeTest {

	private static final ServerProbe TCP = ServerProbe.tcp(Duration.ofSeconds(2));

	@Test
	void listeningServerAnswers() throws IOException {
		try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			assertTrue(TCP.answers("localhost", server.getLocalPort()));
		}
	}

	@Test
	void closedPortDoesNotAnswer() throws IOException {
		int port;
		try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			port = server.getLocalPort();
		}

		assertFalse(TCP.answers("localhost", port));
	}

	@Test
	void unknownHostDoesNotAnswer() {
		assertFalse(TCP.answers("kartenverwaltung-gibt-es-nicht.invalid", 443));
	}
}
