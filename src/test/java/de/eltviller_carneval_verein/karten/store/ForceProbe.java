package de.eltviller_carneval_verein.karten.store;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Test-Hilfe (KARV-90): ein {@link DurableWrite.Forcer}, der bei jedem Erzwingen festhält, was in diesem Moment am Ziel
 * steht, und dann wirklich erzwingt. So lässt sich prüfen, dass eine temporäre Datei auf der Platte ist, bevor sie den
 * Namen des Ziels bekommt.
 */
final class ForceProbe implements DurableWrite.Forcer {

	/** Steht für ein Ziel, das es beim Erzwingen noch nicht gab. */
	static final String MISSING = "<fehlt>";

	/** Je Erzwingen der Inhalt des Ziels (UTF-8) oder {@link #MISSING}. */
	final List<String> targetAtForce = new ArrayList<>();

	private final Path target;

	ForceProbe(Path target) {
		this.target = target;
	}

	@Override
	public void force(FileChannel channel) throws IOException {
		targetAtForce.add(Files.exists(target) ? Files.readString(target) : MISSING);
		channel.force(true);
	}

	/** Ein Erzwingen, das immer mit {@code failure} scheitert. */
	static DurableWrite.Forcer failing(IOException failure) {
		return channel -> {
			throw failure;
		};
	}
}
