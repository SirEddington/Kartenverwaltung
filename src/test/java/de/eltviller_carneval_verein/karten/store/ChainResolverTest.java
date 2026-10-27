package de.eltviller_carneval_verein.karten.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.eltviller_carneval_verein.karten.store.UnitState.Status;

class ChainResolverTest {

	private static final Instant AT = Instant.parse("2026-10-26T10:00:00Z");
	private static final UnitKey UNIT = UnitKey.event("e1");

	private static final String ME = "aaaaaaaa";
	private static final String OTHER = "bbbbbbbb";

	/** Versions-IDs: je größer die Zahl, desto später die ID; der Rechner ist nur Teil des Namens. */
	private static VersionId id(int n, String machine) {
		return VersionId.create(n * 1000L, machine, 0);
	}

	private static VersionId mine(int n) {
		return id(n, ME);
	}

	private static VersionId theirs(int n) {
		return id(n, OTHER);
	}

	/** Baut die Dateien einer Einheit im Speicher auf. */
	private static final class Unit {

		private final Map<VersionId, VersionFile> versions = new TreeMap<>();
		private final Map<String, ClaimFile> claims = new TreeMap<>();

		Unit version(VersionId id, VersionId... parents) throws IOException {
			String machine = id.value().substring(12, 20);
			return version(machine, id, parents);
		}

		Unit version(String machine, VersionId id, VersionId... parents) throws IOException {
			Version version = new Version(1, id, List.of(parents), machine, "Kasse", "Anna", AT, "save-" + id, false,
					new ObjectMapper().createObjectNode().put("name", id.value()));
			versions.put(id, new VersionFile(id, StoreCodec.write(version), version, null));
			return this;
		}

		Unit deletedVersion(VersionId id, VersionId... parents) throws IOException {
			Version version = new Version(1, id, List.of(parents), OTHER, "Kasse", "Anna", AT, "save-" + id, true,
					new ObjectMapper().createObjectNode());
			versions.put(id, new VersionFile(id, StoreCodec.write(version), version, null));
			return this;
		}

		Unit unreadableVersion(VersionId id) {
			versions.put(id, new VersionFile(id, "kaputt".getBytes(StandardCharsets.UTF_8), null, null));
			return this;
		}

		Unit unknownFormatVersion(VersionId id) {
			versions.put(id, new VersionFile(id, "{\"format\":2}".getBytes(StandardCharsets.UTF_8), null, 2));
			return this;
		}

		/** Anspruchsdatei {@code name} für eine vorhandene Version, mit der Prüfsumme ihrer Bytes. */
		Unit claim(String name, VersionId target) throws IOException {
			return claim(name, target, StoreCodec.sha256(versions.get(target).bytes()));
		}

		Unit claim(String name, VersionId target, String sha256) throws IOException {
			Claim claim = new Claim(target, sha256, OTHER, AT);
			claims.put(name, new ClaimFile(name, StoreCodec.write(claim), claim));
			return this;
		}

		Unit unreadableClaim(String name) {
			claims.put(name, new ClaimFile(name, "kaputt".getBytes(StandardCharsets.UTF_8), null));
			return this;
		}

		UnitState resolve() {
			return ChainResolver.resolve(new UnitFiles(UNIT, versions, claims), ME);
		}
	}

	private static List<VersionId> ids(List<Version> versions) {
		return versions.stream().map(Version::id).toList();
	}

	@Test
	void emptyUnitHasNoHead() {
		UnitState state = new Unit().resolve();

		assertEquals(UNIT, state.unit());
		assertEquals(Status.OK, state.status());
		assertTrue(state.chain().isEmpty());
		assertNull(state.head());
		assertNull(state.current());
		assertTrue(state.pending().isEmpty());
		assertTrue(state.lost().isEmpty());
		assertNull(state.blockedAt());
		assertFalse(state.provisional());
	}

	@Test
	void linearChainEndsAtLastClaim() throws IOException {
		UnitState state = new Unit().version(mine(1)).version(theirs(2), mine(1)).version(mine(3), theirs(2))
				.claim("root", mine(1)).claim(mine(1).value(), theirs(2)).claim(theirs(2).value(), mine(3)).resolve();

		assertEquals(Status.OK, state.status());
		assertEquals(List.of(mine(1), theirs(2), mine(3)), ids(state.chain()));
		assertEquals(mine(3), state.head().id());
		assertEquals(mine(3), state.current().id());
		assertTrue(state.pending().isEmpty());
		assertTrue(state.lost().isEmpty());
		assertFalse(state.provisional());
	}

	@Test
	void ownUnclaimedWithEmptyChainIsPending() throws IOException {
		UnitState state = new Unit().version(mine(1)).resolve();

		assertEquals(Status.OK, state.status());
		assertTrue(state.chain().isEmpty());
		assertEquals(List.of(mine(1)), ids(state.pending()));
		assertTrue(state.lost().isEmpty());
		assertNull(state.head());
		assertEquals(mine(1), state.current().id());
		assertTrue(state.provisional());
	}

	@Test
	void ownUnclaimedOnHeadIsPending() throws IOException {
		UnitState state = new Unit().version(theirs(1)).version(mine(2), theirs(1)).claim("root", theirs(1)).resolve();

		assertEquals(List.of(theirs(1)), ids(state.chain()));
		assertEquals(List.of(mine(2)), ids(state.pending()));
		assertTrue(state.lost().isEmpty());
		assertEquals(theirs(1), state.head().id());
		assertEquals(mine(2), state.current().id());
		assertTrue(state.provisional());
	}

	@Test
	void pendingChainOfTwo() throws IOException {
		UnitState state = new Unit().version(theirs(1)).version(mine(2), theirs(1)).version(mine(3), mine(2))
				.claim("root", theirs(1)).resolve();

		assertEquals(List.of(mine(2), mine(3)), ids(state.pending()));
		assertTrue(state.lost().isEmpty());
		assertEquals(theirs(1), state.head().id());
		assertEquals(mine(3), state.current().id());
	}

	@Test
	void ownVersionOnOldBaseIsLost() throws IOException {
		UnitState state = new Unit().version(theirs(1)).version(mine(2)).claim("root", theirs(1)).resolve();

		assertEquals(Status.OK, state.status());
		assertEquals(List.of(theirs(1)), ids(state.chain()));
		assertTrue(state.pending().isEmpty());
		assertEquals(List.of(List.of(mine(2))), state.lost());
		assertEquals(theirs(1), state.current().id());
		assertFalse(state.provisional());
	}

	@Test
	void ownVersionOnNonHeadChainMemberIsLost() throws IOException {
		UnitState state = new Unit().version(theirs(1)).version(theirs(2), theirs(1)).version(mine(3), theirs(1))
				.claim("root", theirs(1)).claim(theirs(1).value(), theirs(2)).resolve();

		assertEquals(List.of(theirs(1), theirs(2)), ids(state.chain()));
		assertTrue(state.pending().isEmpty());
		assertEquals(List.of(List.of(mine(3))), state.lost());
	}

	@Test
	void lostCarriesDescendants() throws IOException {
		UnitState state = new Unit().version(mine(1)).version(mine(2), mine(1)).version(theirs(3))
				.claim("root", theirs(3)).resolve();

		assertEquals(List.of(theirs(3)), ids(state.chain()));
		assertTrue(state.pending().isEmpty());
		assertEquals(List.of(List.of(mine(1), mine(2))), state.lost());
	}

	@Test
	void mergedLoserIsNotReportedAgain() throws IOException {
		// v1 und v2 (auf v1) verlieren gegen w; m fuehrt nur v1 zusammen, v2 bleibt verloren.
		UnitState state = new Unit().version(mine(1)).version(mine(2), mine(1)).version(theirs(3))
				.version(mine(4), theirs(3), mine(1)).claim("root", theirs(3)).claim(theirs(3).value(), mine(4))
				.resolve();

		assertEquals(List.of(theirs(3), mine(4)), ids(state.chain()));
		assertEquals(mine(4), state.head().id());
		assertTrue(state.pending().isEmpty());
		assertEquals(List.of(List.of(mine(2))), state.lost());
	}

	@Test
	void mergeOfTheLastLoserLeavesNothingLost() throws IOException {
		UnitState state = new Unit().version(mine(1)).version(mine(2), mine(1)).version(theirs(3))
				.version(mine(4), theirs(3), mine(2)).claim("root", theirs(3)).claim(theirs(3).value(), mine(4))
				.resolve();

		assertEquals(mine(4), state.head().id());
		assertTrue(state.pending().isEmpty());
		assertTrue(state.lost().isEmpty());
	}

	@Test
	void damagedClaimedOwnVersionIsNeitherPendingNorLost() throws IOException {
		UnitState state = new Unit().version(theirs(1)).version(mine(2), theirs(1)).claim("root", theirs(1))
				.claim(theirs(1).value(), mine(2), "0".repeat(64)).resolve();

		assertEquals(Status.LOCKED_DAMAGED, state.status());
		assertEquals(mine(2), state.blockedAt());
		assertEquals(List.of(theirs(1)), ids(state.chain()));
		assertTrue(state.pending().isEmpty());
		assertTrue(state.lost().isEmpty());
		assertEquals(theirs(1), state.current().id());
		assertFalse(state.provisional());
	}

	@Test
	void skippedClaimedOwnVersionIsNotLostWithItsDescendant() throws IOException {
		// d (eigen, beschaedigt) hat seinen Anspruch bekommen und r folgt ihm; e baut unbeansprucht auf d.
		UnitState state = new Unit().version(theirs(1)).version(mine(2), theirs(1)).version(theirs(3), mine(2))
				.version(mine(4), mine(2)).claim("root", theirs(1)).claim(theirs(1).value(), mine(2), "0".repeat(64))
				.claim(mine(2).value(), theirs(3)).resolve();

		assertEquals(Status.OK, state.status());
		assertEquals(List.of(theirs(1), theirs(3)), ids(state.chain()));
		assertEquals(theirs(3), state.head().id());
		assertTrue(state.pending().isEmpty());
		assertEquals(List.of(List.of(mine(4))), state.lost());
	}

	@Test
	void unclaimedMergeIsPendingAndHidesItsLoser() throws IOException {
		UnitState state = new Unit().version(mine(1)).version(theirs(2)).version(mine(3), theirs(2), mine(1))
				.claim("root", theirs(2)).resolve();

		assertEquals(List.of(theirs(2)), ids(state.chain()));
		assertEquals(List.of(mine(3)), ids(state.pending()));
		assertTrue(state.lost().isEmpty());
	}

	@Test
	void ownVersionNamedOnlyByUnclaimedForeignVersionStaysPending() throws IOException {
		// w (fremd) ist beansprucht; v (eigen) baut auf w, f (fremd) baut auf v, aber nur v und f sind unbeansprucht.
		// Der Absturz kam zwischen Liste und Anspruch: f nennt v, hat aber keinen Platz bekommen.
		UnitState state = new Unit().version(theirs(1)).version(mine(2), theirs(1)).version(theirs(3), mine(2))
				.claim("root", theirs(1)).resolve();

		assertEquals(List.of(theirs(1)), ids(state.chain()));
		assertEquals(theirs(1), state.head().id());
		assertEquals(List.of(mine(2)), ids(state.pending()));
		assertTrue(state.lost().isEmpty());
	}

	@Test
	void ownVersionNamedByClaimedVersionIsNoTip() throws IOException {
		UnitState state = new Unit().version(theirs(1)).version(mine(2), theirs(1)).version(theirs(3), mine(2))
				.claim("root", theirs(1)).claim(theirs(1).value(), mine(2)).claim(mine(2).value(), theirs(3)).resolve();

		assertEquals(Status.OK, state.status());
		assertEquals(List.of(theirs(1), mine(2), theirs(3)), ids(state.chain()));
		assertTrue(state.pending().isEmpty());
		assertTrue(state.lost().isEmpty());
	}

	@Test
	void deletedVersionIsNormalHead() throws IOException {
		UnitState state = new Unit().version(mine(1)).deletedVersion(theirs(2), mine(1)).claim("root", mine(1))
				.claim(mine(1).value(), theirs(2)).resolve();

		assertEquals(Status.OK, state.status());
		assertEquals(List.of(mine(1), theirs(2)), ids(state.chain()));
		assertTrue(state.head().deleted());
		assertTrue(state.pending().isEmpty());
	}

	@Test
	void foreignUnclaimedVersionIsIgnored() throws IOException {
		UnitState state = new Unit().version(theirs(1)).version(theirs(2)).claim("root", theirs(1)).resolve();

		assertEquals(List.of(theirs(1)), ids(state.chain()));
		assertTrue(state.pending().isEmpty());
		assertTrue(state.lost().isEmpty());
	}

	@Test
	void missingClaimedVersionIsWaiting() throws IOException {
		Unit unit = new Unit().version(theirs(1)).version(theirs(2), theirs(1)).claim("root", theirs(1))
				.claim(theirs(1).value(), theirs(2), "abc");
		unit.versions.remove(theirs(2));

		UnitState state = unit.resolve();

		assertEquals(Status.WAITING, state.status());
		assertEquals(List.of(theirs(1)), ids(state.chain()));
		assertEquals(theirs(2), state.blockedAt());
		assertTrue(state.pending().isEmpty());
	}

	@Test
	void waitingSuspendsLost() throws IOException {
		Unit unit = new Unit().version(theirs(1)).version(mine(2)).claim("root", theirs(1)).claim(theirs(1).value(),
				theirs(3), "abc");

		UnitState state = unit.resolve();

		assertEquals(Status.WAITING, state.status());
		assertEquals(theirs(3), state.blockedAt());
		assertEquals(List.of(theirs(1)), ids(state.chain()));
		assertEquals(List.of(mine(2)), ids(state.pending()));
		assertTrue(state.lost().isEmpty());
		assertTrue(state.provisional());
	}

	@Test
	void checksumMismatchLocksDamaged() throws IOException {
		UnitState state = new Unit().version(theirs(1)).version(theirs(2), theirs(1)).claim("root", theirs(1))
				.claim(theirs(1).value(), theirs(2), "0".repeat(64)).resolve();

		assertEquals(Status.LOCKED_DAMAGED, state.status());
		assertEquals(List.of(theirs(1)), ids(state.chain()));
		assertEquals(theirs(2), state.blockedAt());
	}

	@Test
	void unreadableVersionLocksDamaged() throws IOException {
		Unit unit = new Unit().version(theirs(1)).unreadableVersion(theirs(2)).claim("root", theirs(1));
		unit.claim(theirs(1).value(), theirs(2));

		UnitState state = unit.resolve();

		assertEquals(Status.LOCKED_DAMAGED, state.status());
		assertEquals(List.of(theirs(1)), ids(state.chain()));
		assertEquals(theirs(2), state.blockedAt());
	}

	@Test
	void damagedVersionWithFollowerClaimIsSkipped() throws IOException {
		// Nach dem Zuruecksetzen (Heilung Stufe 2): d ist beschaedigt, r folgt ihm.
		Unit unit = new Unit().version(theirs(1)).unreadableVersion(theirs(2)).version(mine(3), theirs(2))
				.claim("root", theirs(1));
		unit.claim(theirs(1).value(), theirs(2));
		unit.claim(theirs(2).value(), mine(3));

		UnitState state = unit.resolve();

		assertEquals(Status.OK, state.status());
		assertEquals(List.of(theirs(1), mine(3)), ids(state.chain()));
		assertNull(state.blockedAt());
		assertTrue(state.pending().isEmpty());
		assertTrue(state.lost().isEmpty());
		// Die übersprungene beschädigte Version steht nicht in der Kette, hat aber ihren Platz bekommen
		assertEquals(Set.of(theirs(1), theirs(2), mine(3)), state.claimed());
	}

	@Test
	void checksumMismatchWithFollowerClaimIsSkipped() throws IOException {
		Unit unit = new Unit().version(theirs(1)).version(theirs(2), theirs(1)).version(mine(3), theirs(2))
				.claim("root", theirs(1)).claim(theirs(1).value(), theirs(2), "0".repeat(64))
				.claim(theirs(2).value(), mine(3));

		UnitState state = unit.resolve();

		assertEquals(Status.OK, state.status());
		assertEquals(List.of(theirs(1), mine(3)), ids(state.chain()));
	}

	@Test
	void unreadableClaimLocksDamagedWithoutBlockedVersion() throws IOException {
		UnitState state = new Unit().version(theirs(1)).claim("root", theirs(1)).unreadableClaim(theirs(1).value())
				.resolve();

		assertEquals(Status.LOCKED_DAMAGED, state.status());
		assertEquals(List.of(theirs(1)), ids(state.chain()));
		assertNull(state.blockedAt());
	}

	@Test
	void unreadableRootClaimLocksDamaged() {
		UnitState state = new Unit().unreadableClaim("root").resolve();

		assertEquals(Status.LOCKED_DAMAGED, state.status());
		assertTrue(state.chain().isEmpty());
		assertNull(state.blockedAt());
	}

	@Test
	void claimCycleLocksDamaged() throws IOException {
		Unit unit = new Unit().version(theirs(1)).version(theirs(2), theirs(1)).claim("root", theirs(1))
				.claim(theirs(1).value(), theirs(2)).claim(theirs(2).value(), theirs(1));

		UnitState state = unit.resolve();

		assertEquals(Status.LOCKED_DAMAGED, state.status());
		assertEquals(List.of(theirs(1), theirs(2)), ids(state.chain()));
		assertEquals(theirs(1), state.blockedAt());
	}

	@Test
	void unknownFormatLocksFormat() throws IOException {
		Unit unit = new Unit().version(theirs(1)).unknownFormatVersion(theirs(2)).claim("root", theirs(1));
		unit.claim(theirs(1).value(), theirs(2), "abc");

		UnitState state = unit.resolve();

		assertEquals(Status.LOCKED_FORMAT, state.status());
		assertEquals(List.of(theirs(1)), ids(state.chain()));
		assertEquals(theirs(2), state.blockedAt());
	}

	@Test
	void unknownFormatEndsChainEvenWithFollowerClaim() throws IOException {
		Unit unit = new Unit().version(theirs(1)).unknownFormatVersion(theirs(2)).version(mine(3), theirs(2))
				.claim("root", theirs(1));
		unit.claim(theirs(1).value(), theirs(2), "abc");
		unit.claim(theirs(2).value(), mine(3));

		UnitState state = unit.resolve();

		assertEquals(Status.LOCKED_FORMAT, state.status());
		assertEquals(List.of(theirs(1)), ids(state.chain()));
		assertEquals(theirs(2), state.blockedAt());
	}

	@Test
	void pendingOrderFollowsParentsNotIds() throws IOException {
		// v1 hat die groessere ID als sein Nachfolger v2.
		UnitState state = new Unit().version(theirs(1)).version(mine(9), theirs(1)).version(mine(5), mine(9))
				.claim("root", theirs(1)).resolve();

		assertEquals(List.of(mine(9), mine(5)), ids(state.pending()));
		assertEquals(mine(5), state.current().id());
	}

	@Test
	void lostOrderFollowsParentsNotIds() throws IOException {
		UnitState state = new Unit().version(mine(9)).version(mine(5), mine(9)).version(theirs(1))
				.claim("root", theirs(1)).resolve();

		assertEquals(List.of(List.of(mine(9), mine(5))), state.lost());
	}

	@Test
	void lostAndPendingStretchesAreSeparate() throws IOException {
		UnitState state = new Unit().version(theirs(1)).version(mine(2)).version(mine(3), theirs(1))
				.claim("root", theirs(1)).resolve();

		assertEquals(List.of(mine(3)), ids(state.pending()));
		assertEquals(List.of(List.of(mine(2))), state.lost());
	}

	@Test
	void severalLostStretchesAreOrderedByOldestId() throws IOException {
		UnitState state = new Unit().version(mine(30)).version(mine(10)).version(mine(11), mine(10))
				.version(theirs(1)).claim("root", theirs(1)).resolve();

		assertEquals(List.of(List.of(mine(10), mine(11)), List.of(mine(30))), state.lost());
	}

	@Test
	void sharedPrefixOfTwoTipsIsReportedOnce() throws IOException {
		UnitState state = new Unit().version(theirs(1)).version(mine(2), theirs(1)).version(mine(3), mine(2))
				.version(mine(4), mine(2)).claim("root", theirs(1)).resolve();

		assertEquals(List.of(mine(2), mine(3), mine(4)), ids(state.pending()));
	}

	@Test
	void ownVersionOnUnreadableParentIsLost() throws IOException {
		UnitState state = new Unit().version(theirs(1)).unreadableVersion(theirs(2)).version(mine(3), theirs(2))
				.claim("root", theirs(1)).resolve();

		assertTrue(state.pending().isEmpty());
		assertEquals(List.of(List.of(mine(3))), state.lost());
	}

	@Test
	void ownVersionOnMissingParentIsLost() throws IOException {
		UnitState state = new Unit().version(theirs(1)).version(mine(3), theirs(2)).claim("root", theirs(1)).resolve();

		assertTrue(state.pending().isEmpty());
		assertEquals(List.of(List.of(mine(3))), state.lost());
	}

	@Test
	void lockedUsesTruncatedChainForPendingAndLost() throws IOException {
		Unit unit = new Unit().version(theirs(1)).unreadableVersion(theirs(2)).version(mine(3), theirs(1))
				.version(mine(4), theirs(2)).claim("root", theirs(1));
		unit.claim(theirs(1).value(), theirs(2));

		UnitState state = unit.resolve();

		assertEquals(Status.LOCKED_DAMAGED, state.status());
		// mine(4) baut auf der beschädigten Version auf, an der die Kette endet: ihr Platz ist frei (wie beim Zurücksetzen)
		assertEquals(List.of(mine(3), mine(4)), ids(state.pending()));
		assertTrue(state.lost().isEmpty());
	}

	@Test
	void resetOnTheDamagedEndIsPending() throws IOException {
		// mine(3) ist das Zurücksetzen: Inhalt von theirs(1), erster Vorgänger die beschädigte theirs(2)
		Unit unit = new Unit().version(theirs(1)).unreadableVersion(theirs(2)).version(mine(3), theirs(2))
				.claim("root", theirs(1));
		unit.claim(theirs(1).value(), theirs(2));

		UnitState state = unit.resolve();

		assertEquals(Status.LOCKED_DAMAGED, state.status());
		assertEquals(theirs(2), state.blockedAt());
		assertEquals(List.of(mine(3)), ids(state.pending()));
		assertTrue(state.lost().isEmpty());
		assertEquals(mine(3), state.current().id());
	}

	@Test
	void ownVersionOnAnOlderLinkStaysLostWhileLocked() throws IOException {
		Unit unit = new Unit().version(theirs(1)).version(theirs(2), theirs(1)).unreadableVersion(theirs(3))
				.version(mine(4), theirs(1)).claim("root", theirs(1));
		unit.claim(theirs(1).value(), theirs(2));
		unit.claim(theirs(2).value(), theirs(3));

		UnitState state = unit.resolve();

		assertEquals(Status.LOCKED_DAMAGED, state.status());
		assertEquals(theirs(3), state.blockedAt());
		assertTrue(state.pending().isEmpty());
		assertEquals(List.of(List.of(mine(4))), state.lost());
	}

	@Test
	void stateListsAreUnmodifiable() throws IOException {
		UnitState state = new Unit().version(theirs(1)).version(mine(2)).version(mine(3), theirs(1))
				.claim("root", theirs(1)).resolve();

		assertThrows(UnsupportedOperationException.class, () -> state.chain().add(state.chain().get(0)));
		assertThrows(UnsupportedOperationException.class, () -> state.pending().clear());
		assertThrows(UnsupportedOperationException.class, () -> state.lost().clear());
		assertThrows(UnsupportedOperationException.class, () -> state.lost().get(0).clear());
	}

	@Test
	void constructorCopiesGivenLists() {
		List<Version> chain = new ArrayList<>();
		List<List<VersionId>> lost = new ArrayList<>();
		Set<VersionId> claimed = new HashSet<>();
		UnitState state = new UnitState(UNIT, Status.OK, chain, List.of(), lost, null, claimed);

		lost.add(List.of(mine(1)));
		claimed.add(mine(1));

		assertTrue(state.lost().isEmpty());
		assertTrue(state.claimed().isEmpty());
		assertThrows(UnsupportedOperationException.class, () -> state.claimed().add(mine(2)));
	}
}
