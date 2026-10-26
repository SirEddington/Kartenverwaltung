package de.eltviller_carneval_verein.karten.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Test-Hilfe: eine {@link FileLayer}, die Aufrufe an eine andere Schicht weitergibt, jeden Aufruf aufzeichnet und auf
 * Wunsch Fehler auslöst. Damit prüfen Tests die Reihenfolge der Schritte und stellen einen Absturz zwischen zwei
 * Schritten nach (der Fehler tritt vor dem Aufruf auf, die Platte bleibt dann unverändert).
 * <p>
 * Die Operationen heißen wie die Methoden der Schnittstelle: {@code createNew}, {@code read}, {@code exists},
 * {@code listNames}, {@code isDirectory}, {@code replaceDamaged}, {@code rename}, {@code deleteOwnTemps}.
 * Threadsicher.
 */
public final class FaultyFileLayer implements FileLayer {

	private record Failure(Predicate<Path> path, String operation, IOException exception, boolean once) {
	}

	private final FileLayer delegate;
	private final List<String> calls = new ArrayList<>();
	private final List<Failure> failures = new ArrayList<>();

	public FaultyFileLayer(FileLayer delegate) {
		this.delegate = delegate;
	}

	/** Lässt den nächsten Aufruf der Operation (mit beliebigem Pfad) mit dieser Ausnahme scheitern. */
	public synchronized void failNext(String operation, IOException e) {
		failures.add(new Failure(p -> true, operation, e, true));
	}

	/** Lässt jeden Aufruf der Operation mit einem passenden Pfad mit dieser Ausnahme scheitern. */
	public synchronized void failWhen(Predicate<Path> path, String operation, IOException e) {
		failures.add(new Failure(path, operation, e, false));
	}

	/** Alle bisherigen Aufrufe als {@code "<Operation> <Pfad>"} in der Reihenfolge, auch die gescheiterten. */
	public synchronized List<String> calls() {
		return List.copyOf(calls);
	}

	/** Zeichnet den Aufruf auf und löst einen eingestellten Fehler aus. */
	private synchronized void enter(String operation, Path path) throws IOException {
		calls.add(operation + " " + path);
		for (Failure failure : failures) {
			if (failure.operation().equals(operation) && failure.path().test(path)) {
				if (failure.once()) {
					failures.remove(failure);
				}
				throw failure.exception();
			}
		}
	}

	/** Wie {@link #enter}, für Operationen, die keine {@link IOException} melden dürfen: der Fehler kommt verpackt. */
	private void enterUnchecked(String operation, Path path) {
		try {
			enter(operation, path);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@Override
	public boolean createNew(Path file, byte[] content) throws IOException {
		enter("createNew", file);
		return delegate.createNew(file, content);
	}

	@Override
	public Optional<byte[]> read(Path file) throws IOException {
		enter("read", file);
		return delegate.read(file);
	}

	@Override
	public boolean exists(Path file) {
		enterUnchecked("exists", file);
		return delegate.exists(file);
	}

	@Override
	public List<String> listNames(Path dir) throws IOException {
		enter("listNames", dir);
		return delegate.listNames(dir);
	}

	@Override
	public boolean isDirectory(Path path) {
		enterUnchecked("isDirectory", path);
		return delegate.isDirectory(path);
	}

	@Override
	public void replaceDamaged(Path file, byte[] good, String corruptName) throws IOException {
		enter("replaceDamaged", file);
		delegate.replaceDamaged(file, good, corruptName);
	}

	@Override
	public void rename(Path from, Path to) throws IOException {
		enter("rename", from);
		delegate.rename(from, to);
	}

	@Override
	public void deleteOwnTemps(Path dir, Duration olderThan) throws IOException {
		enter("deleteOwnTemps", dir);
		delegate.deleteOwnTemps(dir, olderThan);
	}
}
