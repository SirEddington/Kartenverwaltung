package de.eltviller_carneval_verein.karten.update;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import de.eltviller_carneval_verein.karten.update.FolderDecision.Result;

class FolderDecisionTest {

	private static final Instant SEEN = Instant.parse("2026-11-10T13:00:00Z");
	private static final String FAMILY = "EcvKartenFx132_0cs1gtds5d9x0";
	private static final Optional<Installation> INST = Installation.from(Map.of("app.version", "1.1.0", "app.revision",
			"0", "app.fsname", "ecv-karten-fx132", "app.repositoryUrl", "http://localhost/src/", "os.name", "Windows 11",
			"app.windows.userModelID", FAMILY + "!App")::get,
			Map.of("PACKAGE_LOCALAPPDATA", "C:\\Users\\x\\AppData\\Local\\Packages\\" + FAMILY + "\\LocalCache\\Local")::get);

	private static Optional<FolderOffer> offer(String version, int revision) {
		AppVersion v = new AppVersion(version, revision);
		return Optional.of(new FolderOffer() {
			@Override
			public Path file() {
				return Path.of("x.msix");
			}

			@Override
			public AppVersion version() {
				return v;
			}

			@Override
			public Instant firstSeen() {
				return SEEN;
			}

			@Override
			public boolean stillValid() {
				return true;
			}
		});
	}

	private static Optional<UpdateSuccess> success(String version, Instant at) {
		return success(version, at, FAMILY);
	}

	private static Optional<UpdateSuccess> success(String version, Instant at, String family) {
		return Optional.of(new UpdateSuccess(new UpdateInfo(new AppVersion(version, 0), family, false), at));
	}

	private static final UpdateState OK = new UpdateState.UpToDate(SEEN);
	private static final UpdateState FAILED = new UpdateState.CheckFailed(Grund.NETWORK, SEEN.plusSeconds(30), Optional.empty());

	private static Result decide(Optional<FolderOffer> c, Optional<UpdateSuccess> s, UpdateState state,
			Optional<Instant> recheck, Instant now) {
		return FolderDecision.decide(c, s, state, INST, recheck, now);
	}

	@Test
	void noCandidateGivesNone() {
		assertEquals(Result.NONE, decide(Optional.empty(), success("1.1.1", SEEN), OK, Optional.empty(), SEEN));
	}

	@Test
	void equalGivesFolder() {
		assertEquals(Result.FOLDER, decide(offer("1.1.1", 0), success("1.1.1", SEEN.minusSeconds(60)), OK, Optional.empty(), SEEN));
	}

	@Test
	void noSuccessGivesFolder() {
		assertEquals(Result.FOLDER, decide(offer("1.1.1", 0), Optional.empty(), FAILED, Optional.empty(), SEEN));
	}

	@Test
	void offlineWithFileOffersFolder() {
		assertEquals(Result.FOLDER,
				decide(offer("1.1.1", 0), Optional.empty(), new UpdateState.NotChecked(), Optional.empty(), SEEN));
	}

	@Test
	void higherWithYoungerSuccessIsWithdrawn() {
		assertEquals(Result.WITHDRAWN,
				decide(offer("1.1.2", 0), success("1.1.1", SEEN.plusSeconds(60)), OK, Optional.empty(), SEEN.plusSeconds(60)));
	}

	@Test
	void higherWithOlderSuccessIsUnclear() {
		assertEquals(Result.UNCLEAR,
				decide(offer("1.1.2", 0), success("1.1.1", SEEN.minusSeconds(60)), OK, Optional.empty(), SEEN));
	}

	@Test
	void unclearWhileRecheckRuns() {
		Instant t = SEEN.plusSeconds(10);
		assertEquals(Result.UNCLEAR, decide(offer("1.1.2", 0), success("1.1.1", SEEN.minusSeconds(60)),
				new UpdateState.Checking(OK), Optional.of(t), t.plusSeconds(60)));
	}

	@Test
	void unclearAfterNewSuccessIsDecidedAgain() {
		Instant t = SEEN.plusSeconds(10);
		assertEquals(Result.FOLDER,
				decide(offer("1.1.2", 0), success("1.1.2", t.plusSeconds(5)), OK, Optional.of(t), t.plusSeconds(5)));
		assertEquals(Result.WITHDRAWN,
				decide(offer("1.1.2", 0), success("1.1.1", t.plusSeconds(5)), OK, Optional.of(t), t.plusSeconds(5)));
	}

	@Test
	void unclearAfterCheckFailedGivesFolder() {
		Instant t = SEEN.plusSeconds(10);
		UpdateState failedAfter = new UpdateState.CheckFailed(Grund.NETWORK, t.plusSeconds(10), Optional.empty());
		assertEquals(Result.FOLDER,
				decide(offer("1.1.2", 0), success("1.1.1", SEEN.minusSeconds(60)), failedAfter, Optional.of(t), t.plusSeconds(10)));
	}

	@Test
	void oldCheckFailedBeforeRecheckDoesNotCount() {
		Instant t = SEEN.plusSeconds(60);
		UpdateState failedBefore = new UpdateState.CheckFailed(Grund.NETWORK, SEEN, Optional.empty());
		assertEquals(Result.UNCLEAR,
				decide(offer("1.1.2", 0), success("1.1.1", SEEN.minusSeconds(60)), failedBefore, Optional.of(t), t.plusSeconds(5)));
	}

	@Test
	void unclearAfterFiveMinutesGivesFolder() {
		Instant t = SEEN.plusSeconds(10);
		assertEquals(Result.FOLDER, decide(offer("1.1.2", 0), success("1.1.1", SEEN.minusSeconds(60)), OK, Optional.of(t),
				t.plus(Duration.ofMinutes(5))));
	}

	@Test
	void lowerWithUsableGithubGivesGithub() {
		assertEquals(Result.GITHUB, decide(offer("1.1.1", 0), success("1.1.2", SEEN), OK, Optional.empty(), SEEN));
	}

	@Test
	void lowerWhileCheckFailedGivesFolder() {
		assertEquals(Result.FOLDER, decide(offer("1.1.1", 0), success("1.1.2", SEEN), FAILED, Optional.empty(), SEEN));
		assertEquals(Result.FOLDER,
				decide(offer("1.1.1", 0), success("1.1.2", SEEN), new UpdateState.Checking(FAILED), Optional.empty(), SEEN));
	}

	@Test
	void lowerWithOtherFamilyGivesFolder() {
		assertEquals(Result.FOLDER,
				decide(offer("1.1.1", 0), success("1.1.2", SEEN, "Andere_x"), OK, Optional.empty(), SEEN));
	}

	@Test
	void lowerWithoutInstallationGivesFolder() {
		assertEquals(Result.FOLDER, FolderDecision.decide(offer("1.1.1", 0), success("1.1.2", SEEN), OK, Optional.empty(),
				Optional.empty(), SEEN));
	}
}
