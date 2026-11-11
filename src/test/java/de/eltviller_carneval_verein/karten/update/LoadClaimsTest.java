package de.eltviller_carneval_verein.karten.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.eltviller_carneval_verein.karten.update.StationRegistryTest.MutableClock;

class LoadClaimsTest {

	private static final Instant NOW = Instant.parse("2026-11-10T13:00:00Z");
	private static final String QUAD = "1.1.0.2";

	@TempDir
	Path updates;

	private MutableClock clock;
	private LoadClaims claims;

	@BeforeEach
	void setUp() {
		clock = new MutableClock(NOW);
		claims = new LoadClaims(clock);
	}

	private Path claim(String machine, Instant angelegt, Instant fertig) throws IOException {
		return Files.write(updates.resolve(UpdatesDir.claimName(QUAD, machine)),
				LoadClaims.json(machine, "Kasse", angelegt, fertig));
	}

	private LoadClaims.Claim only() {
		List<LoadClaims.Claim> all = claims.read(updates, QUAD);
		assertEquals(1, all.size());
		return all.get(0);
	}

	@Test
	void readsNameAndTimes() throws IOException {
		Path p = claim("bbbb", NOW, null);
		Files.writeString(updates.resolve(UpdatesDir.claimName("1.1.0.3", "cccc")), "{}");
		LoadClaims.Claim c = only();
		assertEquals(p, c.file());
		assertEquals("bbbb", c.machineId());
		assertEquals(Optional.of(NOW), c.angelegt());
		assertFalse(c.done());
	}

	@Test
	void freshWithinOneHour() throws IOException {
		claim("bbbb", NOW, null);
		assertTrue(claims.isFresh(only()));
		clock.now = NOW.plus(Duration.ofMinutes(59));
		assertTrue(claims.isFresh(only()));
	}

	@Test
	void staleAfterOneHourSeen() throws IOException {
		claim("bbbb", NOW.plus(Duration.ofMinutes(30)), null);
		only();
		clock.now = NOW.plus(Duration.ofMinutes(61));
		assertFalse(claims.isFresh(only()));
	}

	@Test
	void staleWhenTimeOlderThanOneHour() throws IOException {
		claim("bbbb", NOW.minus(Duration.ofMinutes(61)), null);
		assertFalse(claims.isFresh(only()));
	}

	@Test
	void noTimeIsNotFresh() throws IOException {
		Files.writeString(updates.resolve(UpdatesDir.claimName(QUAD, "bbbb")), "{\"format\":1}");
		assertFalse(claims.isFresh(only()));
	}

	@Test
	void futureTimeStaleAfterOneHourSeen() throws IOException {
		claim("bbbb", NOW.plus(Duration.ofDays(3)), null);
		assertTrue(claims.isFresh(only()));
		clock.now = NOW.plus(Duration.ofMinutes(61));
		assertFalse(claims.isFresh(only()));
	}

	@Test
	void doneFreshThreeHours() throws IOException {
		claim("bbbb", NOW, NOW);
		assertTrue(only().done());
		clock.now = NOW.plus(Duration.ofMinutes(179));
		assertTrue(claims.isFresh(only()));
		clock.now = NOW.plus(Duration.ofMinutes(181));
		assertFalse(claims.isFresh(only()));
	}

	@Test
	void observationRestartsOnChange() throws IOException {
		claim("bbbb", NOW, null);
		only();
		clock.now = NOW.plus(Duration.ofMinutes(50));
		claim("bbbb", NOW, clock.now);
		Files.setLastModifiedTime(updates.resolve(UpdatesDir.claimName(QUAD, "bbbb")),
				java.nio.file.attribute.FileTime.from(clock.now));
		only();
		clock.now = NOW.plus(Duration.ofMinutes(50 + 170));
		assertTrue(claims.isFresh(only()), "seit der Änderung erst 170 min beobachtet");
	}

	@Test
	void expiredAfterOneDay() throws IOException {
		claim("bbbb", NOW.minus(Duration.ofHours(25)), null);
		assertTrue(claims.isExpired(only()));
		Files.writeString(updates.resolve(UpdatesDir.claimName(QUAD, "bbbb")), "{}");
		assertFalse(claims.isExpired(only()), "ohne Zeit erst nach einem Tag Beobachtung");
		clock.now = NOW.plus(Duration.ofHours(25));
		assertTrue(claims.isExpired(only()));
	}

	@Test
	void oversizedClaimHasNoTime() throws IOException {
		Files.writeString(updates.resolve(UpdatesDir.claimName(QUAD, "bbbb")), "{\"x\":\"" + "y".repeat(70_000) + "\"}");
		assertEquals(Optional.empty(), only().angelegt());
	}
}
