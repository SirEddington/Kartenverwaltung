package de.eltviller_carneval_verein.karten.update;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/** Eine Update-Quelle für Tests: liefert die eingestellte Info bzw. wirft die eingestellte Ausnahme. */
final class FakeUpdateSource implements UpdateSource {

	UpdateInfo info;
	Throwable failure;
	Path download = Path.of("ecv-update-test.appinstaller");
	Throwable downloadFailure;
	RuntimeException cleanupFailure;
	/** Läuft mitten in {@link #fetchInfo()}, z. B. um eine zweite Prüfung anzustoßen. */
	Runnable duringFetch = () -> {
	};
	final AtomicInteger fetches = new AtomicInteger();
	final AtomicInteger downloads = new AtomicInteger();
	final AtomicInteger cleanups = new AtomicInteger();

	FakeUpdateSource(UpdateInfo info) {
		this.info = info;
	}

	@Override
	public UpdateInfo fetchInfo() throws IOException {
		fetches.incrementAndGet();
		duringFetch.run();
		throwIfSet(failure);
		return info;
	}

	@Override
	public Path downloadAppInstaller() throws IOException {
		downloads.incrementAndGet();
		throwIfSet(downloadFailure);
		return download;
	}

	@Override
	public URI appInstallerUri() {
		return URI.create("http://localhost:8765/upd/ecv-karten.appinstaller");
	}

	@Override
	public void cleanup() {
		cleanups.incrementAndGet();
		if (cleanupFailure != null) {
			throw cleanupFailure;
		}
	}

	private static void throwIfSet(Throwable t) throws IOException {
		if (t instanceof IOException io) {
			throw io;
		}
		if (t instanceof RuntimeException re) {
			throw re;
		}
		if (t instanceof Error e) {
			throw e;
		}
	}
}
