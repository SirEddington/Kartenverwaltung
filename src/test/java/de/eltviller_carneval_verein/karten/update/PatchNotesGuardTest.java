package de.eltviller_carneval_verein.karten.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class PatchNotesGuardTest {

	@Test
	void allEqual() {
		assertEquals(List.of(), PatchNotesGuard.mismatches("1.1.0", "1.1.0", "1.1.0"));
	}

	@Test
	void notesDiffer() {
		List<String> m = PatchNotesGuard.mismatches("1.0.9", "1.1.0", "1.1.0");
		assertEquals(1, m.size(), m.toString());
		assertTrue(m.get(0).contains("patchnotes.md"), m.toString());
	}

	@Test
	void pomDiffers() {
		// verglichen wird gegen die pom: Datei und conveyor.conf weichen beide von ihr ab
		List<String> m = PatchNotesGuard.mismatches("1.1.0", "1.2.0", "1.1.0");
		assertEquals(2, m.size(), m.toString());
		assertTrue(m.stream().allMatch(s -> s.contains("pom.xml")), m.toString());
	}

	@Test
	void conveyorDiffers() {
		List<String> m = PatchNotesGuard.mismatches("1.1.0", "1.1.0", "1.2.0");
		assertEquals(1, m.size(), m.toString());
		assertTrue(m.get(0).contains("conveyor.conf"), m.toString());
	}

	@Test
	void sameVersionWrittenDifferentlyIsAMismatch() {
		assertEquals(1, PatchNotesGuard.mismatches("1.1", "1.1.0", "1.1.0").size());
	}

	@Test
	void versionMustHaveThreeParts() {
		for (String v : List.of("1.1", "1.1.0.1")) {
			List<String> m = PatchNotesGuard.mismatches(v, v, v);
			assertEquals(1, m.size(), m.toString());
			assertTrue(m.get(0).contains("dreiteilig"), m.toString());
		}
	}
}
