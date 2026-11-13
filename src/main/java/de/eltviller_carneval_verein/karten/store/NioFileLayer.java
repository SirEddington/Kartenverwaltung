package de.eltviller_carneval_verein.karten.store;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * {@link FileLayer} auf Basis von {@code java.nio.file}.
 * <p>
 * "Nur wenn nicht vorhanden" beruht unter Windows auf {@link Files#move} ohne Optionen, das ein vorhandenes Ziel nicht
 * ersetzt, unter Linux und macOS auf einem harten Link (siehe {@code publishNew}). Nicht {@code ATOMIC_MOVE}: Unter
 * Windows ersetzt das ein vorhandenes Ziel stillschweigend.
 * <p>
 * Jede temporäre Datei ist auf der Platte, bevor sie ihren richtigen Namen bekommt ({@link DurableWrite}, KARV-90).
 */
public final class NioFileLayer implements FileLayer {

	/** Ob das Dateisystem POSIX ist (Linux, macOS): Dort ist {@link Files#move} ohne Optionen nicht atomar. */
	private static final boolean POSIX = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

	/** Legt einen einzelnen Ordner an, dessen Elternordner vorhanden sein muss; die Naht für Tests. */
	@FunctionalInterface
	interface DirectoryCreator {
		void create(Path dir) throws IOException;
	}

	private final DirectoryCreator directories;

	/** Bringt jede temporäre Datei auf die Platte, bevor sie ihren Namen bekommt (KARV-90); die Naht für Tests. */
	private final DurableWrite.Forcer forcer;

	public NioFileLayer() {
		this(Files::createDirectory);
	}

	/** Nur für Tests: Ordner einer Netzfreigabe nachstellen, die sich anders verhält als das lokale Dateisystem. */
	NioFileLayer(DirectoryCreator directories) {
		this(directories, DurableWrite.FORCE);
	}

	/** Nur für Tests: zusätzlich beobachten, wann eine temporäre Datei auf die Platte geschrieben wird. */
	NioFileLayer(DirectoryCreator directories, DurableWrite.Forcer forcer) {
		this.directories = directories;
		this.forcer = forcer;
	}

	@Override
	public boolean createNew(Path file, byte[] content, Path anchor, String tempTag) throws IOException {
		// Ziel absolut und normalisiert wie die Ordner: Unter Linux scheitert sonst ein Pfad mit "x/..", dessen "x" fehlt
		Path target = file.toAbsolutePath().normalize();
		Path dir;
		if (anchor == null) {
			dir = parentOf(target);
			Files.createDirectories(dir);
		} else {
			dir = createLevelsBelow(anchor, file);
		}
		Path temp = writeTemp(dir, content, tempTag);
		try {
			publishNew(temp, target);
			return true;
		} catch (FileAlreadyExistsException e) {
			deleteQuietly(temp, null);
			return false;
		} catch (IOException | RuntimeException e) {
			deleteQuietly(temp, e);
			throw e;
		}
	}

	/**
	 * Macht die fertige temporäre Datei unter {@code target} sichtbar, nur wenn dort noch nichts liegt.
	 * <p>
	 * Unter Windows ist {@link Files#move} ohne Optionen atomar und scheitert an einem vorhandenen Ziel. Unter Linux und
	 * macOS prüft es dagegen erst und benennt dann um: Zwei gleichzeitige Aufrufe gewinnen dort beide, der zweite ersetzt
	 * den ersten. Dort legt ein harter Link das Ziel atomar an ({@code link(2)} scheitert an einem vorhandenen Ziel), danach
	 * fällt die temporäre Datei weg. Kann das Dateisystem keine harten Links (manche Netzfreigaben), bleibt es beim
	 * Umbenennen.
	 *
	 * @throws FileAlreadyExistsException wenn {@code target} schon vorhanden ist; die temporäre Datei räumt der Aufrufer weg
	 */
	private static void publishNew(Path temp, Path target) throws IOException {
		if (POSIX) {
			try {
				Files.createLink(target, temp);
				deleteQuietly(temp, null);
				return;
			} catch (FileAlreadyExistsException e) {
				throw e;
			} catch (UnsupportedOperationException | FileSystemException e) {
				// keine harten Links auf diesem Dateisystem: wie unter Windows umbenennen
			}
		}
		Files.move(temp, target);
	}

	@Override
	public void replace(Path file, byte[] content, String tempTag) throws IOException {
		AtomicFileReplace.write(file, content, tempTag, forcer);
	}

	@Override
	public Optional<byte[]> read(Path file) throws IOException {
		try {
			return Optional.of(Files.readAllBytes(file));
		} catch (NoSuchFileException e) {
			return Optional.empty();
		}
	}

	@Override
	public boolean exists(Path file) {
		return Files.exists(file);
	}

	@Override
	public List<String> listNames(Path dir) throws IOException {
		List<String> names = new ArrayList<>();
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
			for (Path entry : stream) {
				String name = entry.getFileName().toString();
				if (!name.startsWith(TEMP_PREFIX)) {
					names.add(name);
				}
			}
		} catch (NoSuchFileException e) {
			return Collections.emptyList();
		} catch (DirectoryIteratorException e) {
			// Ein Ein-/Ausgabefehler beim Durchlaufen (etwa weggebrochene Netzfreigabe) bleibt eine IOException
			throw e.getCause();
		}
		Collections.sort(names);
		return names;
	}

	/**
	 * Ein Durchlauf des Ordners, der zu jedem Eintrag auch sagt, ob er ein Ordner ist; {@link #listEntries} ist die
	 * Standardfassung ohne die {@code ~karv-*}-Einträge. Die Attribute stammen aus der
	 * Aufzählung selbst: {@link Files#walkFileTree} mit Tiefe 1 nutzt die Attribute, die das Betriebssystem (unter Windows)
	 * beim Aufzählen liefert, und fragt nicht je Eintrag noch einmal das Dateisystem; {@code Files.readAttributes} je Eintrag
	 * täte das (gemessen: so schnell wie {@code isDirectory} je Eintrag). Größe und Änderungszeit stammen aus denselben
	 * Attributen. Ein Verweis zählt als Ordner, wenn sein Ziel einer ist, wie bei {@link Files#isDirectory}. Ein Eintrag,
	 * der während des Durchlaufs verschwindet, fehlt in der Liste.
	 */
	@Override
	public List<DirEntry> listEntriesWithTemps(Path dir) throws IOException {
		List<DirEntry> entries = new ArrayList<>();
		Files.walkFileTree(dir, EnumSet.noneOf(FileVisitOption.class), 1, new MissingTolerantVisitor() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				if (file.equals(dir)) {
					throw new NotDirectoryException(dir.toString());
				}
				boolean directory = attrs.isSymbolicLink() ? Files.isDirectory(file) : attrs.isDirectory();
				entries.add(entryOf(file.getFileName().toString(), directory, attrs));
				return FileVisitResult.CONTINUE;
			}
		});
		entries.sort(Comparator.comparing(DirEntry::name));
		return entries;
	}

	/** Der Eintrag mit Größe und Änderungszeit aus den Attributen, die das Aufzählen liefert. */
	private static DirEntry entryOf(String name, boolean directory, BasicFileAttributes attrs) {
		return new DirEntry(name, directory, attrs.size(), attrs.lastModifiedTime().toMillis());
	}

	@Override
	public Map<String, List<DirEntry>> listTree(Path root) throws IOException {
		Map<String, List<DirEntry>> tree = new TreeMap<>();
		Files.walkFileTree(root, new MissingTolerantVisitor() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
				tree.put(keyOf(root, dir), new ArrayList<>());
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
				if (attrs.isRegularFile()) {
					tree.get(keyOf(root, file.getParent())).add(entryOf(file.getFileName().toString(), false, attrs));
				}
				return FileVisitResult.CONTINUE;
			}
		});
		tree.values().forEach(files -> files.sort(Comparator.comparing(DirEntry::name)));
		return tree;
	}

	/** Der Pfad des Ordners relativ zur Wurzel, getrennt durch {@code /}; für die Wurzel selbst {@code ""}. */
	private static String keyOf(Path root, Path dir) {
		return root.relativize(dir).toString().replace(root.getFileSystem().getSeparator(), "/");
	}

	@Override
	public boolean isDirectory(Path path) {
		return Files.isDirectory(path);
	}

	@Override
	public void replaceDamaged(Path file, byte[] good, String corruptName, String tempTag) throws IOException {
		Path dir = parentOf(file);
		Path corrupt = dir.resolve(corruptName);
		Path temp = writeTemp(dir, good, tempTag);
		try {
			Files.move(file, corrupt);
		} catch (IOException | RuntimeException e) {
			deleteQuietly(temp, e);
			throw e;
		}
		try {
			Files.move(temp, file);
		} catch (IOException | RuntimeException e) {
			try {
				Files.move(corrupt, file);
			} catch (IOException | RuntimeException restoreFailure) {
				e.addSuppressed(restoreFailure);
			}
			deleteQuietly(temp, e);
			throw e;
		}
	}

	@Override
	public void rename(Path from, Path to) throws IOException {
		Files.move(from, to);
	}

	@Override
	public void deleteOwnTemps(Path dir, Duration olderThan) throws IOException {
		Instant limit = Instant.now().minus(olderThan);
		try {
			Files.walkFileTree(dir, new MissingTolerantVisitor() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					String name = file.getFileName().toString();
					if (FileLayer.isTempName(name) && attrs.lastModifiedTime().toInstant().isBefore(limit)) {
						Files.deleteIfExists(file);
					}
					return FileVisitResult.CONTINUE;
				}
			});
		} catch (NoSuchFileException e) {
			// Ordner fehlt: nichts aufzuräumen
		}
	}

	@Override
	public void deleteOldTemps(Path dir, Collection<String> names, Duration olderThan) throws IOException {
		Instant limit = Instant.now().minus(olderThan);
		for (String name : names) {
			if (!FileLayer.isTempName(name) || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
				continue;
			}
			Path file = dir.resolve(name);
			try {
				BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
				if (attrs.isRegularFile() && attrs.lastModifiedTime().toInstant().isBefore(limit)) {
					Files.deleteIfExists(file);
				}
			} catch (NoSuchFileException e) {
				// die Datei ist schon weg (oder der Ordner fehlt): nichts zu löschen
			}
		}
	}

	/** Ein Durchlauf, der Ordner und Dateien, die fehlen oder währenddessen verschwinden, übergeht; jeden anderen Fehler wirft er. */
	private abstract static class MissingTolerantVisitor extends SimpleFileVisitor<Path> {
		@Override
		public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
			if (exc instanceof NoSuchFileException) {
				return FileVisitResult.CONTINUE;
			}
			throw exc;
		}
	}

	/** Der Ordner der Datei; bei einem Namen ohne Ordner der aktuelle Ordner. */
	private static Path parentOf(Path file) {
		Path parent = file.toAbsolutePath().getParent();
		return parent != null ? parent : file.toAbsolutePath();
	}

	/**
	 * Legt die fehlenden Ordner zwischen {@code anchor} und dem Ordner der Datei einzeln an, nie den Anker selbst. Ist der
	 * Ordner der Datei schon da, geschieht nichts weiter. Vorhandene Ebenen werden übersprungen, nicht noch einmal
	 * angelegt (auf Netzfreigaben scheitert das ohne das Recht, dort Ordner anzulegen). Fehlt der Anker oder eine Ebene
	 * darüber, scheitert das Anlegen mit {@link NoSuchFileException}, ohne etwas anzulegen. Scheitert das Anlegen sonst,
	 * aber die Ebene ist danach da (ein anderer Rechner war schneller), ist das kein Fehler.
	 *
	 * @return der Ordner der Datei
	 * @throws IllegalArgumentException wenn die Datei nicht unter dem Anker liegt
	 */
	private Path createLevelsBelow(Path anchor, Path file) throws IOException {
		Path root = anchor.toAbsolutePath().normalize();
		// beide Seiten absolut und normalisiert: Ein relativer Pfad zum gemeinsamen Ordner (oder einer mit "." und "..") liegt
		// sonst scheinbar nicht unter dem Anker
		Path dir = parentOf(file.toAbsolutePath().normalize());
		if (!dir.startsWith(root)) {
			throw new IllegalArgumentException("Die Datei liegt nicht unter dem Anker: " + file + " (Anker " + anchor + ")");
		}
		if (Files.isDirectory(dir)) {
			return dir; // alle Ebenen sind da (und damit der Anker); es gibt nichts anzulegen und keine Zugriffe darüber hinaus
		}
		Path level = root;
		for (Path part : root.relativize(dir)) {
			if (part.toString().isEmpty()) {
				break; // die Datei liegt unmittelbar im Anker
			}
			level = level.resolve(part);
			if (Files.isDirectory(level)) {
				continue; // Ein vorhandener Ordner wird nie erneut angelegt: Auf Netzfreigaben scheitert das ohne Recht dazu
			}
			try {
				directories.create(level);
			} catch (NoSuchFileException e) {
				throw e; // der Anker oder eine Ebene darüber fehlt
			} catch (IOException e) {
				if (!Files.isDirectory(level)) {
					throw e;
				}
				// ein anderer Rechner hat die Ebene gerade angelegt
			}
		}
		return dir;
	}

	/**
	 * Schreibt den Inhalt in eine neue temporäre Datei im Ordner und wartet, bis er auf der Platte ist: Erst danach darf
	 * die Datei ihren richtigen Namen bekommen, sonst kann sie nach einem Stromausfall darunter leer stehen (KARV-90). Bei
	 * einem Fehler bleibt keine temporäre Datei zurück.
	 */
	private Path writeTemp(Path dir, byte[] content, String tempTag) throws IOException {
		String tag = tempTag == null || tempTag.isEmpty() ? "" : tempTag + "-";
		Path temp = dir.resolve(TEMP_PREFIX + tag + UUID.randomUUID() + TEMP_SUFFIX);
		try {
			DurableWrite.writeNew(temp, content, forcer);
		} catch (IOException | RuntimeException e) {
			deleteQuietly(temp, e);
			throw e;
		}
		return temp;
	}

	/** Löscht die eigene temporäre Datei, soweit möglich. Ein Fehler wird nur beim ursprünglichen Fehler vermerkt. */
	private static void deleteQuietly(Path temp, Throwable original) {
		try {
			Files.deleteIfExists(temp);
		} catch (IOException | RuntimeException e) {
			if (original != null) {
				original.addSuppressed(e);
			}
		}
	}
}
