package de.eltviller_carneval_verein.karten.store;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
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
 * "Nur wenn nicht vorhanden" beruht auf {@link Files#move} ohne Optionen, das ein vorhandenes Ziel nicht ersetzt. Nicht
 * {@code ATOMIC_MOVE}: Unter Windows ersetzt das ein vorhandenes Ziel stillschweigend.
 */
public final class NioFileLayer implements FileLayer {

	/** Legt einen einzelnen Ordner an, dessen Elternordner vorhanden sein muss; die Naht für Tests. */
	@FunctionalInterface
	interface DirectoryCreator {
		void create(Path dir) throws IOException;
	}

	private final DirectoryCreator directories;

	public NioFileLayer() {
		this(Files::createDirectory);
	}

	/** Nur für Tests: Ordner einer Netzfreigabe nachstellen, die sich anders verhält als das lokale Dateisystem. */
	NioFileLayer(DirectoryCreator directories) {
		this.directories = directories;
	}

	@Override
	public boolean createNew(Path file, byte[] content, Path anchor, String tempTag) throws IOException {
		Path dir;
		if (anchor == null) {
			dir = parentOf(file);
			Files.createDirectories(dir);
		} else {
			dir = createLevelsBelow(anchor, file);
		}
		Path temp = writeTemp(dir, content, tempTag);
		try {
			Files.move(temp, file);
			return true;
		} catch (FileAlreadyExistsException e) {
			deleteQuietly(temp, null);
			return false;
		} catch (IOException | RuntimeException e) {
			deleteQuietly(temp, e);
			throw e;
		}
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
		return list(dir, false);
	}

	@Override
	public List<String> listNamesWithTemps(Path dir) throws IOException {
		return list(dir, true);
	}

	private static List<String> list(Path dir, boolean withTemps) throws IOException {
		List<String> names = new ArrayList<>();
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
			for (Path entry : stream) {
				String name = entry.getFileName().toString();
				if (withTemps || !name.startsWith(TEMP_PREFIX)) {
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

	@Override
	public List<DirEntry> listEntries(Path dir) throws IOException {
		return listWithKinds(dir, false);
	}

	@Override
	public List<DirEntry> listEntriesWithTemps(Path dir) throws IOException {
		return listWithKinds(dir, true);
	}

	/**
	 * Ein Durchlauf des Ordners, der zu jedem Eintrag auch sagt, ob er ein Ordner ist. Die Attribute stammen aus der
	 * Aufzählung selbst: {@link Files#walkFileTree} mit Tiefe 1 nutzt die Attribute, die das Betriebssystem (unter Windows)
	 * beim Aufzählen liefert, und fragt nicht je Eintrag noch einmal das Dateisystem; {@code Files.readAttributes} je Eintrag
	 * täte das (gemessen: so schnell wie {@code isDirectory} je Eintrag). Ein Verweis zählt als Ordner, wenn sein Ziel einer
	 * ist, wie bei {@link Files#isDirectory}. Ein Eintrag, der während des Durchlaufs verschwindet, fehlt in der Liste.
	 */
	private static List<DirEntry> listWithKinds(Path dir, boolean withTemps) throws IOException {
		List<DirEntry> entries = new ArrayList<>();
		Files.walkFileTree(dir, EnumSet.noneOf(FileVisitOption.class), 1, new MissingTolerantVisitor() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				if (file.equals(dir)) {
					throw new NotDirectoryException(dir.toString());
				}
				String name = file.getFileName().toString();
				if (withTemps || !name.startsWith(TEMP_PREFIX)) {
					boolean directory = attrs.isSymbolicLink() ? Files.isDirectory(file) : attrs.isDirectory();
					entries.add(new DirEntry(name, directory));
				}
				return FileVisitResult.CONTINUE;
			}
		});
		entries.sort(Comparator.comparing(DirEntry::name));
		return entries;
	}

	@Override
	public Map<String, List<String>> listTree(Path root) throws IOException {
		Map<String, List<String>> tree = new TreeMap<>();
		Files.walkFileTree(root, new MissingTolerantVisitor() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
				tree.put(keyOf(root, dir), new ArrayList<>());
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
				if (attrs.isRegularFile()) {
					tree.get(keyOf(root, file.getParent())).add(file.getFileName().toString());
				}
				return FileVisitResult.CONTINUE;
			}
		});
		tree.values().forEach(Collections::sort);
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
					if (name.startsWith(TEMP_PREFIX) && name.endsWith(TEMP_SUFFIX)
							&& attrs.lastModifiedTime().toInstant().isBefore(limit)) {
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
			if (!name.startsWith(TEMP_PREFIX) || !name.endsWith(TEMP_SUFFIX) || name.indexOf('/') >= 0
					|| name.indexOf('\\') >= 0) {
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
		Path dir = parentOf(file).normalize();
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

	/** Schreibt den Inhalt in eine neue temporäre Datei im Ordner; bei einem Fehler bleibt keine zurück. */
	private static Path writeTemp(Path dir, byte[] content, String tempTag) throws IOException {
		String tag = tempTag == null || tempTag.isEmpty() ? "" : tempTag + "-";
		Path temp = dir.resolve(TEMP_PREFIX + tag + UUID.randomUUID() + TEMP_SUFFIX);
		try {
			Files.write(temp, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
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
