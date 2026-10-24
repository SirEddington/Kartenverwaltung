package de.eltviller_carneval_verein.karten.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.eltviller_carneval_verein.karten.settings.AppSettings;

class SettingsValidatorTest {

	@TempDir
	Path root;

	private Path defaultRoot() {
		return root.resolve("app");
	}

	private ValidationResult validate(String mirrorDir) {
		AppSettings settings = new AppSettings();
		settings.setMirrorDir(mirrorDir);
		return SettingsValidator.validate(settings, defaultRoot());
	}

	private static void assertNested(ValidationResult result) {
		assertEquals(1, result.getIssues().size(), "Genau eine Meldung erwartet: " + result.getIssues());
		assertEquals("SET-001", result.getIssues().get(0).code());
	}

	@Test
	void blankMirrorDirIsValid() {
		assertTrue(validate(null).isEmpty());
		assertTrue(validate("").isEmpty());
		assertTrue(validate("   ").isEmpty());
	}

	@Test
	void mirrorDirEqualToDefaultIsRejected() {
		assertNested(validate(defaultRoot().toString()));
	}

	@Test
	void mirrorDirInsideDefaultIsRejected() {
		assertNested(validate(defaultRoot().resolve("sub").toString()));
	}

	@Test
	void mirrorDirContainingDefaultIsRejected() {
		assertNested(validate(root.toString()));
	}

	@Test
	void unrelatedMirrorDirIsValid() {
		assertTrue(validate(root.resolve("other").toString()).isEmpty());
	}

	@Test
	void dotDotAndTrailingSeparatorCountAsSamePath() {
		assertNested(validate(defaultRoot() + File.separator + ".." + File.separator + "app" + File.separator));
	}

	@Test
	void caseOnlyDifferenceCountsAsSamePathOnWindows() {
		assumeTrue(System.getProperty("os.name").startsWith("Windows"));

		assertNested(validate(defaultRoot().toString().toUpperCase()));
	}

	@Test
	void messageNamesThePath() {
		String input = defaultRoot().toString();

		assertTrue(validate(input).getIssues().get(0).message().contains(input));
	}
}
