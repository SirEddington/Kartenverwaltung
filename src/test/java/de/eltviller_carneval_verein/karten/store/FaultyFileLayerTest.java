package de.eltviller_carneval_verein.karten.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FaultyFileLayerTest {

	@TempDir
	Path dir;

	@Test
	void failNextFailsOnlyTheNextCallOfThatOperation() throws IOException {
		FaultyFileLayer layer = new FaultyFileLayer(new NioFileLayer());
		Path file = dir.resolve("a.json");
		IOException failure = new IOException("Platte voll");
		layer.failNext("createNew", failure);

		assertTrue(layer.exists(dir));
		IOException thrown = assertThrows(IOException.class, () -> layer.createNew(file, new byte[] { 1 }));
		assertSame(failure, thrown);
		assertFalse(layer.exists(file));

		assertTrue(layer.createNew(file, new byte[] { 2 }));
		assertArrayEquals(new byte[] { 2 }, layer.read(file).orElseThrow());
	}

	@Test
	void failWhenFailsMatchingPathsEveryTime() throws IOException {
		FaultyFileLayer layer = new FaultyFileLayer(new NioFileLayer());
		Path bad = dir.resolve("bad.json");
		Path good = dir.resolve("good.json");
		IOException failure = new IOException("Zugriff verweigert");
		layer.failWhen(p -> p.getFileName().toString().startsWith("bad"), "read", failure);

		assertTrue(layer.createNew(bad, "x".getBytes(StandardCharsets.UTF_8)));
		assertTrue(layer.createNew(good, "y".getBytes(StandardCharsets.UTF_8)));

		assertSame(failure, assertThrows(IOException.class, () -> layer.read(bad)));
		assertSame(failure, assertThrows(IOException.class, () -> layer.read(bad)));
		assertTrue(layer.read(good).isPresent());
		assertTrue(layer.exists(bad));
	}

	@Test
	void failAfterPerformsTheCallAndThenFails() throws IOException {
		FaultyFileLayer layer = new FaultyFileLayer(new NioFileLayer());
		Path file = dir.resolve("a.json");
		Path other = dir.resolve("b.json");
		IOException failure = new IOException("Antwort verloren");
		layer.failAfter("createNew", failure);

		assertSame(failure, assertThrows(IOException.class, () -> layer.createNew(file, new byte[] { 1 })));

		assertArrayEquals(new byte[] { 1 }, layer.read(file).orElseThrow());
		assertFalse(layer.createNew(file, new byte[] { 2 }), "die Datei ist angelegt worden");
		// nur der nächste Aufruf scheitert
		assertTrue(layer.createNew(other, new byte[] { 3 }));
		assertEquals("createNew " + file, layer.calls().get(0));
	}

	@Test
	void failAfterWhenFailsMatchingPathsEveryTimeAfterTheCall() throws IOException {
		FaultyFileLayer layer = new FaultyFileLayer(new NioFileLayer());
		Path bad = dir.resolve("bad.json");
		Path good = dir.resolve("good.json");
		IOException failure = new IOException("Antwort verloren");
		layer.failAfterWhen(p -> p.getFileName().toString().startsWith("bad"), "createNew", failure);

		assertTrue(layer.createNew(good, new byte[] { 1 }));
		assertSame(failure, assertThrows(IOException.class, () -> layer.createNew(bad, new byte[] { 2 })));
		assertSame(failure, assertThrows(IOException.class, () -> layer.createNew(bad, new byte[] { 3 })));

		assertArrayEquals(new byte[] { 2 }, layer.read(bad).orElseThrow());
	}

	@Test
	void callsAreRecordedInOrderIncludingFailedOnes() throws IOException {
		FaultyFileLayer layer = new FaultyFileLayer(new NioFileLayer());
		Path file = dir.resolve("a.json");
		layer.failNext("read", new IOException("weg"));

		layer.createNew(file, new byte[0]);
		assertThrows(IOException.class, () -> layer.read(file));
		layer.exists(file);
		layer.isDirectory(dir);
		layer.listNames(dir);
		layer.listTree(dir);

		assertEquals(List.of(
				"createNew " + file,
				"read " + file,
				"exists " + file,
				"isDirectory " + dir,
				"listNames " + dir,
				"listTree " + dir), layer.calls());
	}
}
