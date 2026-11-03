package de.eltviller_carneval_verein.karten.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import de.eltviller_carneval_verein.karten.store.ResolutionBase.Kind;

/** Die abgesenkte Basis bei Auflösung gegen Auflösung (KARV-98, Spec Abschnitt 1). */
class ResolutionBaseTest {

	private static final String MACHINE = "abcdefgh0123";
	private static final UnitKey UNIT = UnitKey.unassignedBooking("e", "p", "m");

	private final Map<VersionId, VersionFile> files = new LinkedHashMap<>();
	private int counter;

	// Bauteile -->

	private Version user(Version... parents) {
		return version(false, parents);
	}

	private Version resolution(Version... parents) {
		return version(true, parents);
	}

	private Version version(boolean resolution, Version... parents) {
		counter++;
		Version version = new Version(StoreCodec.FORMAT, VersionId.create(1_800_000_000_000L + counter, MACHINE, counter),
				Arrays.stream(parents).map(Version::id).toList(), MACHINE, "Kasse", null, Instant.EPOCH, "s" + counter,
				false, JsonNodeFactory.instance.objectNode(), null, Map.of(),
				resolution ? VersionMarks.RESOLUTION : VersionMarks.NONE);
		files.put(version.id(), new VersionFile(version.id(), new byte[0], version, null));
		return version;
	}

	private ResolutionBase.Result lower(Version mine, Version theirs) {
		return ResolutionBase.lower(new UnitFiles(UNIT, files, Map.of(), Map.of(), Map.of()), mine, theirs);
	}

	// <-- Bauteile

	/**
	 * Bs Wiederbelebung (auf Bs Zuweisen) gegen A's Zusammenführung (Bs Zuweisen und A's Änderung): Die Basis ist Mia vor
	 * beiden Änderungen, nicht Bs Zuweisen (strenge Lesart).
	 */
	@Test
	void lowersToTheCommonAncestorOfBothUserFrontiers() {
		Version m0 = user();
		Version bd = user(m0);
		Version br = resolution(bd);
		Version ae = user(m0);
		Version am = resolution(bd, ae);

		ResolutionBase.Result result = lower(br, am);

		assertEquals(Kind.LOWERED, result.kind());
		assertEquals(m0.id(), result.base());
	}

	@Test
	void takesTheNearestOfSeveralCommonAncestors() {
		Version m0 = user();
		Version m1 = user(m0);
		Version bd = user(m1);
		Version br = resolution(bd);
		Version ae = user(m1);
		Version am = resolution(bd, ae);

		assertEquals(m1.id(), lower(br, am).base());
	}

	@Test
	void keepsOnACrossMergeWithTwoNearestAncestors() {
		Version x = user();
		Version y = user();
		Version bd = user(x, y);
		Version br = resolution(bd);
		Version ae = user(x, y);
		Version am = resolution(bd, ae);

		ResolutionBase.Result result = lower(br, am);

		assertEquals(Kind.KEEP, result.kind());
		assertNull(result.base());
	}

	/** Das Parken in einer neuen Einheit ist eine Auflösung ohne Vorgänger: leere Grenze. */
	@Test
	void keepsForAResolutionWithoutParents() {
		Version m0 = user();
		Version ae = user(m0);
		Version am = resolution(m0, ae);

		assertEquals(Kind.KEEP, lower(resolution(), am).kind());
	}

	@Test
	void reportsAMissingFrontierVersion() {
		Version m0 = user();
		Version bd = user(m0);
		Version br = resolution(bd);
		Version ae = user(m0);
		Version am = resolution(bd, ae);
		files.remove(ae.id());

		assertEquals(Kind.MISSING, lower(br, am).kind());
	}

	/** Eine Version aus einer neueren App lässt sich nicht lesen: sofort die heutige Basis, kein Warten. */
	@Test
	void keepsForAnUnreadableFrontierVersion() {
		Version m0 = user();
		Version bd = user(m0);
		Version br = resolution(bd);
		Version ae = user(m0);
		Version am = resolution(bd, ae);
		files.put(ae.id(), new VersionFile(ae.id(), new byte[0], null, 99));

		assertEquals(Kind.KEEP, lower(br, am).kind());
	}
}
