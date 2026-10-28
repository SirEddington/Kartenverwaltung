package de.eltviller_carneval_verein.karten;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppPathsTest {

	private static final String PROPERTY = "karten.appDataDir";

	@Test
	void appDataDirFollowsSystemProperty(@TempDir Path folder) {
		String before = System.getProperty(PROPERTY);
		try {
			System.setProperty(PROPERTY, folder.toString());

			assertEquals(folder.toFile(), AppPaths.appDataDir());
		} finally {
			restore(before);
		}
	}

	@Test
	void blankPropertyFallsBackToLocalAppData() {
		String before = System.getProperty(PROPERTY);
		try {
			System.clearProperty(PROPERTY);
			File withoutProperty = AppPaths.appDataDir();

			System.setProperty(PROPERTY, "   ");
			assertEquals(withoutProperty, AppPaths.appDataDir());

			System.setProperty(PROPERTY, "");
			assertEquals(withoutProperty, AppPaths.appDataDir());
		} finally {
			restore(before);
		}
	}

	private static void restore(String before) {
		if (before == null) {
			System.clearProperty(PROPERTY);
		} else {
			System.setProperty(PROPERTY, before);
		}
	}
}
