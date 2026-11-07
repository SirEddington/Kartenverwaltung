package de.eltviller_carneval_verein.karten.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class AppVersionTest {

	private static AppVersion v(String version, String revision) {
		return AppVersion.parse(version, revision).orElseThrow();
	}

	@Test
	void parseAcceptsOneToFourFields() {
		assertTrue(AppVersion.parse("1", null).isPresent());
		assertTrue(AppVersion.parse("1.1.0", null).isPresent());
		assertTrue(AppVersion.parse("1.1.0.3", null).isPresent());
		assertEquals(Optional.empty(), AppVersion.parse("1.1.0.3.4", null));
		assertEquals(Optional.empty(), AppVersion.parse("", null));
		assertEquals(Optional.empty(), AppVersion.parse("1.a", null));
		assertEquals(Optional.empty(), AppVersion.parse("1..0", null));
		assertEquals(Optional.empty(), AppVersion.parse(".1", null));
		assertEquals(Optional.empty(), AppVersion.parse(null, null));
	}

	@Test
	void revisionNullOrEmptyIsZero() {
		assertEquals(0, v("1.1.0", null).revision());
		assertEquals(0, v("1.1.0", "").revision());
		assertEquals(0, v("1.1.0", "0").revision());
		assertEquals(2, v("1.1.0", "2").revision());
	}

	@Test
	void invalidRevisionGivesEmpty() {
		assertEquals(Optional.empty(), AppVersion.parse("1.1.0", "-1"));
		assertEquals(Optional.empty(), AppVersion.parse("1.1.0", "x"));
	}

	@Test
	void overflowGivesEmpty() {
		assertEquals(Optional.empty(), AppVersion.parse("99999999999", null));
		assertEquals(Optional.empty(), AppVersion.parse("1.0", "99999999999"));
	}

	@Test
	void compareFieldByFieldThenRevision() {
		assertEquals(0, v("1.1", null).compareTo(v("1.1.0", null)));
		assertTrue(v("1.1.0", null).compareTo(v("1.1.1", null)) < 0);
		assertTrue(v("1.10.0", null).compareTo(v("1.9.0", null)) > 0);
		assertTrue(v("1.1.0", "1").compareTo(v("1.1.0", "0")) > 0);
		assertTrue(v("1.1.1", "0").compareTo(v("1.1.0", "5")) > 0);
	}

	@Test
	void displayShowsRevisionOnlyAboveZero() {
		assertEquals("1.1.0", v("1.1.0", "0").display());
		assertEquals("1.1.0 (Rev. 1)", v("1.1.0", "1").display());
	}

	@Test
	void fromReadsAppProperties() {
		Map<String, String> props = Map.of("app.version", "1.1.0", "app.revision", "2");
		assertEquals(Optional.of(v("1.1.0", "2")), AppVersion.from(props::get));
		assertEquals(Optional.empty(), AppVersion.from(Map.<String, String>of()::get));
	}
}
