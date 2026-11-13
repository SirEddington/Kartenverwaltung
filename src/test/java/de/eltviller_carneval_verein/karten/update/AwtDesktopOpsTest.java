package de.eltviller_carneval_verein.karten.update;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AwtDesktopOpsTest {

	@TempDir
	Path temp;

	@Test
	void missingFileBecomesIOException() {
		// Desktop.open wirft hier IllegalArgumentException (oder UnsupportedOperationException ohne Desktop)
		assertThrows(IOException.class, () -> new AwtDesktopOps().open(temp.resolve("fehlt.appinstaller")));
	}
}
