package de.eltviller_carneval_verein.karten.update;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.update.PatchNotes.Release;
import de.eltviller_carneval_verein.karten.update.PatchNotesOnStart.Decision;

class PatchNotesOnStartTest {

	private static final PatchNotes NOTES = PatchNotes.parse(String.join("\n", "## 1.1.0", "### Neu", "- A", "## 1.0.9",
			"### Neu", "- B"));
	private static final Optional<AppVersion> CURRENT = AppVersion.parse("1.1.0", null);

	private final List<String> calls = new ArrayList<>();

	private static Decision decide(Optional<AppVersion> current, String seen, boolean existed) {
		return PatchNotesOnStart.decide(current, seen, existed, Optional.of(NOTES));
	}

	private static List<String> shown(Decision d) {
		return d.show().stream().map(r -> r.version().version()).toList();
	}

	@Test
	void development() {
		Decision d = decide(Optional.empty(), "1.0.9", true);
		assertEquals(List.of(), shown(d));
		assertEquals(Optional.empty(), d.remember());
	}

	@Test
	void sameVersionAlsoWithOtherRevision() {
		Decision d = decide(AppVersion.parse("1.1.0", "3"), "1.1.0", true);
		assertEquals(List.of(), shown(d));
		assertEquals(Optional.empty(), d.remember());
	}

	@Test
	void freshInstallation() {
		Decision d = decide(CURRENT, null, false);
		assertEquals(List.of(), shown(d));
		assertEquals(Optional.of("1.1.0"), d.remember());
	}

	@Test
	void updateFrom10x() {
		for (String seen : new String[] { null, "" }) {
			Decision d = decide(CURRENT, seen, true);
			assertEquals(List.of("1.1.0", "1.0.9"), shown(d));
			assertEquals(Optional.of("1.1.0"), d.remember());
		}
	}

	@Test
	void unreadableSeenCountsAsUpdate() {
		Decision d = decide(CURRENT, "kaputt", false);
		assertEquals(List.of("1.1.0", "1.0.9"), shown(d));
		assertEquals(Optional.of("1.1.0"), d.remember());
	}

	@Test
	void olderSeen() {
		Decision d = decide(AppVersion.parse("1.1.0", "2"), "1.0.9", true);
		assertEquals(List.of("1.1.0"), shown(d));
		assertEquals(Optional.of("1.1.0"), d.remember());
	}

	@Test
	void downgrade() {
		Decision d = decide(CURRENT, "1.2.0", true);
		assertEquals(List.of(), shown(d));
		assertEquals(Optional.of("1.1.0"), d.remember());
	}

	@Test
	void invalidFileShowsNothingButRemembers() {
		Decision d = PatchNotesOnStart.decide(CURRENT, "1.0.9", true, Optional.empty());
		assertEquals(List.of(), shown(d));
		assertEquals(Optional.of("1.1.0"), d.remember());
	}

	@Test
	void invalidFileAndSameVersionRemembersNothing() {
		Decision d = PatchNotesOnStart.decide(CURRENT, "1.1.0", true, Optional.empty());
		assertEquals(List.of(), shown(d));
		assertEquals(Optional.empty(), d.remember());
	}

	private void run(java.util.function.Supplier<PatchNotes> read, java.util.function.Consumer<List<Release>> show,
			java.util.function.Consumer<String> remember) {
		PatchNotesOnStart.run(CURRENT, "1.0.9", true, read, show, remember);
	}

	@Test
	void runShowsAndRemembers() {
		run(() -> NOTES, releases -> calls.add("show " + releases.size()), v -> calls.add("remember " + v));
		assertEquals(List.of("show 1", "remember 1.1.0"), calls);
	}

	@Test
	void runWithFailingReadStillRemembers() {
		run(() -> {
			throw new InvalidPatchNotesException(0, "Datei fehlt");
		}, releases -> calls.add("show"), v -> calls.add("remember " + v));
		assertEquals(List.of("remember 1.1.0"), calls);
	}

	@Test
	void runWithFailingShowStillRemembers() {
		run(() -> NOTES, releases -> {
			throw new IllegalStateException("kein Fenster");
		}, v -> calls.add("remember " + v));
		assertEquals(List.of("remember 1.1.0"), calls);
	}

	@Test
	void runWithFailingRememberDoesNotThrow() {
		assertDoesNotThrow(() -> run(() -> NOTES, releases -> calls.add("show"), v -> {
			throw new IllegalStateException("nicht schreibbar");
		}));
		assertEquals(List.of("show"), calls);
	}

	@Test
	void runDoesNotShowAnEmptyList() {
		PatchNotesOnStart.run(CURRENT, "1.1.0", true, () -> NOTES, releases -> calls.add("show"),
				v -> calls.add("remember " + v));
		assertEquals(List.of(), calls);
	}
}
