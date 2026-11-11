package de.eltviller_carneval_verein.karten.ui.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.update.AppVersion;
import de.eltviller_carneval_verein.karten.update.FolderOffer;

class StartPromptTickerTest {

	private static final Instant T = Instant.parse("2026-11-11T19:45:00Z");

	private final List<Runnable> later = new ArrayList<>();
	private final List<String> asked = new ArrayList<>();
	private Optional<FolderOffer> offer = Optional.of(offer());
	private boolean firstCheckDone = true;
	private final StartPrompt prompt = new StartPrompt(Clock.fixed(T, ZoneOffset.UTC));

	private static FolderOffer offer() {
		return new FolderOffer() {
			@Override
			public Path file() {
				return Path.of("a.msix");
			}

			@Override
			public AppVersion version() {
				return new AppVersion("1.1.0", 2);
			}

			@Override
			public Instant firstSeen() {
				return T;
			}

			@Override
			public boolean stillValid() {
				return true;
			}
		};
	}

	private StartPromptTicker ticker() {
		return new StartPromptTicker(prompt, new StartPromptTicker.Conditions() {
			@Override
			public Optional<FolderOffer> offer() {
				return offer;
			}

			@Override
			public boolean firstCheckDone() {
				return firstCheckDone;
			}

			@Override
			public boolean noBlockingWindow() {
				return true;
			}

			@Override
			public boolean flowIdle() {
				return true;
			}
		}, o -> asked.add(o.version().display()), later::add);
	}

	/** Handtest KARV-132: Ein modaler Dialog ist im Animationsschritt einer Timeline verboten. */
	@Test
	void askRunsLaterNotInsideTheTick() {
		prompt.menuShown();
		StartPromptTicker t = ticker();
		assertTrue(t.tick(), "fertig");
		assertEquals(List.of(), asked, "nicht im Takt selbst");
		assertEquals(1, later.size());
		later.get(0).run();
		assertEquals(List.of("1.1.0 (Rev. 2)"), asked);
	}

	@Test
	void waitsUntilDue() {
		prompt.menuShown();
		firstCheckDone = false;
		StartPromptTicker t = ticker();
		assertFalse(t.tick());
		assertTrue(later.isEmpty());
		firstCheckDone = true;
		assertTrue(t.tick());
		assertEquals(1, later.size());
	}

	@Test
	void finishedWhenExpired() {
		prompt.menuShown();
		prompt.asked();
		assertTrue(ticker().tick());
		assertTrue(later.isEmpty());
	}

	@Test
	void offerVanishingBeforeLaterRunDoesNotAsk() {
		prompt.menuShown();
		StartPromptTicker t = ticker();
		t.tick();
		asked.clear();
		offer = Optional.empty();
		later.get(0).run();
		assertEquals(List.of("1.1.0 (Rev. 2)"), asked, "das beim Takt gültige Angebot wird gefragt");
	}
}
