package de.eltviller_carneval_verein.karten.store;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.UnknownHostException;
import java.time.Duration;

/** Fragt, ob ein Server antwortet (KARV-127). Ohne JavaFX. */
@FunctionalInterface
public interface ServerProbe {

	/** Ob {@code host} auf {@code port} antwortet; wirft nie. */
	boolean answers(String host, int port);

	/**
	 * Ein TCP-Verbindungsaufbau ohne Proxy, nacheinander zu jeder Adresse des Namens (IPv4 und IPv6), je Adresse höchstens
	 * {@code connectTimeout}. Die Auflösung des Namens hat kein eigenes Zeitlimit; das setzt {@link Reachability}. Ein
	 * TCP-Aufbau beweist, dass der Weg zum Server frei ist, nicht, dass der Dienst dahinter läuft; eine
	 * Anmeldeseite im WLAN kann ihn vortäuschen.
	 */
	static ServerProbe tcp(Duration connectTimeout) {
		int millis = Math.toIntExact(connectTimeout.toMillis());
		return (host, port) -> {
			InetAddress[] addresses;
			try {
				addresses = InetAddress.getAllByName(host);
			} catch (UnknownHostException | SecurityException e) {
				return false;
			}
			for (InetAddress address : addresses) {
				if (Thread.currentThread().isInterrupted()) {
					return false;
				}
				try (Socket socket = new Socket(Proxy.NO_PROXY)) {
					socket.connect(new InetSocketAddress(address, port), millis);
					return true;
				} catch (IOException | RuntimeException e) {
					// die nächste Adresse
				}
			}
			return false;
		};
	}
}
