package de.eltviller_carneval_verein.karten.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Test-Hilfe: eine {@link FileLayer}, die Aufrufe an eine andere Schicht weitergibt, jeden Aufruf aufzeichnet und auf
 * Wunsch Fehler auslöst. Damit prüfen Tests die Reihenfolge der Schritte und stellen einen Absturz zwischen zwei
 * Schritten nach. Meist tritt der Fehler vor dem Aufruf auf, die Platte bleibt dann unverändert ({@link #failNext},
 * {@link #failWhen}). Mit {@link #failAfter} und {@link #failAfterWhen} wird der Aufruf erst ausgeführt und danach der
 * Fehler gemeldet: der Zustand nach einer verlorenen Antwort, bei der die Datei schon angelegt ist.
 * <p>
 * Die Operationen heißen wie die Methoden der Schnittstelle: {@code createNew}, {@code read}, {@code exists},
 * {@code listNames}, {@code listTree}, {@code isDirectory}, {@code replaceDamaged}, {@code rename}, {@code deleteOwnTemps}.
 * Threadsicher.
 */
public final class FaultyFileLayer implements FileLayer {

	private record Failure(Predicate<Path> path, String operation, IOException exception, boolean once, boolean after) {
	}

	private final FileLayer delegate;
	private final List<String> calls = new ArrayList<>();
	private final List<Failure> failures = new ArrayList<>();

	public FaultyFileLayer(FileLayer delegate) {
		this.delegate = delegate;
	}

	/** Lässt den nächsten Aufruf der Operation (mit beliebigem Pfad) mit dieser Ausnahme scheitern. */
	public synchronized void failNext(String operation, IOException e) {
		failures.add(new Failure(p -> true, operation, e, true, false));
	}

	/** Lässt jeden Aufruf der Operation mit einem passenden Pfad mit dieser Ausnahme scheitern. */
	public synchronized void failWhen(Predicate<Path> path, String operation, IOException e) {
		failures.add(new Failure(path, operation, e, false, false));
	}

	/**
	 * Führt den nächsten Aufruf der Operation (mit beliebigem Pfad) aus und meldet danach diese Ausnahme: Die Wirkung
	 * ist eingetreten, der Aufrufer erfährt es nicht.
	 */
	public synchronized void failAfter(String operation, IOException e) {
		failures.add(new Failure(p -> true, operation, e, true, true));
	}

	/** Wie {@link #failAfter}, für jeden Aufruf der Operation mit einem passenden Pfad. */
	public synchronized void failAfterWhen(Predicate<Path> path, String operation, IOException e) {
		failures.add(new Failure(path, operation, e, false, true));
	}

	/** Alle bisherigen Aufrufe als {@code "<Operation> <Pfad>"} in der Reihenfolge, auch die gescheiterten. */
	public synchronized List<String> calls() {
		return List.copyOf(calls);
	}

	/**
	 * Zeichnet den Aufruf auf. Ein eingestellter Fehler "vor dem Aufruf" wird ausgelöst; ein Fehler "nach dem Aufruf" wird
	 * zurückgegeben, damit der Aufrufer ihn nach der Ausführung wirft.
	 */
	private synchronized IOException enter(String operation, Path path) throws IOException {
		calls.add(operation + " " + path);
		for (Failure failure : failures) {
			if (failure.operation().equals(operation) && failure.path().test(path)) {
				if (failure.once()) {
					failures.remove(failure);
				}
				if (failure.after()) {
					return failure.exception();
				}
				throw failure.exception();
			}
		}
		return null;
	}

	private static void throwLate(IOException late) throws IOException {
		if (late != null) {
			throw late;
		}
	}

	/** Wie {@link #enter}, für Operationen, die keine {@link IOException} melden dürfen: der Fehler kommt verpackt. */
	private IOException enterUnchecked(String operation, Path path) {
		try {
			return enter(operation, path);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static void throwLateUnchecked(IOException late) {
		if (late != null) {
			throw new UncheckedIOException(late);
		}
	}

	@Override
	public boolean createNew(Path file, byte[] content) throws IOException {
		IOException late = enter("createNew", file);
		boolean result = delegate.createNew(file, content);
		throwLate(late);
		return result;
	}

	@Override
	public Optional<byte[]> read(Path file) throws IOException {
		IOException late = enter("read", file);
		Optional<byte[]> result = delegate.read(file);
		throwLate(late);
		return result;
	}

	@Override
	public boolean exists(Path file) {
		IOException late = enterUnchecked("exists", file);
		boolean result = delegate.exists(file);
		throwLateUnchecked(late);
		return result;
	}

	@Override
	public List<String> listNames(Path dir) throws IOException {
		IOException late = enter("listNames", dir);
		List<String> result = delegate.listNames(dir);
		throwLate(late);
		return result;
	}

	@Override
	public Map<String, List<String>> listTree(Path root) throws IOException {
		IOException late = enter("listTree", root);
		Map<String, List<String>> result = delegate.listTree(root);
		throwLate(late);
		return result;
	}

	@Override
	public boolean isDirectory(Path path) {
		IOException late = enterUnchecked("isDirectory", path);
		boolean result = delegate.isDirectory(path);
		throwLateUnchecked(late);
		return result;
	}

	@Override
	public void replaceDamaged(Path file, byte[] good, String corruptName) throws IOException {
		IOException late = enter("replaceDamaged", file);
		delegate.replaceDamaged(file, good, corruptName);
		throwLate(late);
	}

	@Override
	public void rename(Path from, Path to) throws IOException {
		IOException late = enter("rename", from);
		delegate.rename(from, to);
		throwLate(late);
	}

	@Override
	public void deleteOwnTemps(Path dir, Duration olderThan) throws IOException {
		IOException late = enter("deleteOwnTemps", dir);
		delegate.deleteOwnTemps(dir, olderThan);
		throwLate(late);
	}
}
