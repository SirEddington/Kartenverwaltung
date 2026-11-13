# Testing

State: release 1.1.0.

## Running the tests

Framework: JUnit 5 (`junit-jupiter` 5.10.2) via maven-surefire-plugin 3.2.5.

Windows (default JavaFX classifier `win`):

```bash
./mvnw test
```

Linux, headless (the command CI runs; `package` runs the same tests and leaves the Linux JAR for the next step):

```bash
xvfb-run --auto-servernum ./mvnw --batch-mode -Plinux package
```

Without `-Plinux` on Linux, or without a display, tests that start the JavaFX toolkit fail with "No toolkit found".

A single class or method:

```bash
./mvnw test -Dtest=VersionStoreTest
```

```bash
./mvnw test -Dtest=SyncServiceTest#methodName
```

**CI** (`.github/workflows/ci.yml`) runs on every push and pull request on `ubuntu-latest` with Temurin 21: the full
suite as above, then `.github/scripts/release-notes.sh`, which builds the GitHub release text from `patchnotes.md`
exactly like the release does, so a broken patch notes section fails on the branch and not at release time. The
release workflow runs the tests again before building the JARs.

## What is covered

About 3,500 test methods (`@Test`, plus 21 parameterized tests) in 230 test classes, organized in the same packages as
the production code.

| Area | Examples | Kind |
|---|---|---|
| `model` | `BookingTest`, `SeatingPlanTest`, `SaleDatesTest`, `BookingSyncTest`, `BookingCancellationTest`, `TolerantEnumTest`, `UnknownFieldsTest`, `UnknownFieldsCopyTest` | Unit |
| `validation` | one test per validator, `FootprintTest` (rotated rectangles, circles, tolerance), `EntityLabelsTest`, `MessageTemplateTest`, `IssueConventionsTest` (codes, prefixes, placeholders of every issue enum) | Unit |
| `util` | `MoneyFormatTest`, `BookingTotalsTest` | Unit |
| `store` | `VersionStoreTest`, `ChainResolverTest`, `UploaderTest`, `FeedReaderTest`/`FeedWriterTest`, `FullSyncTest`, `DamageRepairTest`, `UnitIndexTest`, `InstanceLockTest`, `DurableWriteTest`, `CloudFoldersTest`, `SyncServiceTest` | Unit / integration on temp dirs |
| `store` scenarios | `CloudScenarioTest` (several services behind a simulated Nextcloud), `ConnectFlowStoreTest` | Integration |
| `repository` | `StoreEventRepositoryTest`, `StoreHallRepository*Test`, `UnitMapperTest`, `EventMergeTest`, `FieldMergeTest`, `BookingPolicyTest`, `BookingTrailTest`, `RestorePlannerTest` | Unit / integration |
| `repository` scenarios | `ConflictScenarioTest` (conflicts end to end across two or three machines; also a simulated newer-version machine) | Integration |
| data format guards | `FormatGuardTest`, `JsonKeysTest`, `ArchitectureGuardTest`, `FormatSamplesTest`, `StoreCodecTest` | Unit / integration |
| `tracking` | `ChangeTrackerTest`, `StateRestorerTest` | Unit |
| `update` | `AppVersionTest`, `InstallationTest`, `UpdateInfoTest`, `GrundTest`, `UpdateStateTest`, `UpdateCheckerTest` (fake scheduler), `HttpUpdateSourceTest` (small `ServerSocket` server; the module does not read `jdk.httpserver`), `FolderUpdatesTest`, `FolderDecisionTest`, `FolderDownloaderTest`, `FolderWatchTest`, `StationRegistryTest`, `MsixManifestTest`, `PatchNotesTest`, `PatchNotesMarkdownTest`, `PatchNotesOnStartTest`, `PatchNotesExportTest`, `PatchNotesBundledTest` | Unit |
| `ui.hall` | `SeatingPlanLayoutTest` (equal sections, distance by diameter), `PlanTextTest`, `PlanLabelOptionsTest`, `TableRowBulkEditTest`, `LayoutChecksTest` (arranged plans report nothing) | Unit |
| `ui.sales` | `SeatLookTest` (four states distinguishable in grayscale and under simulated red-green deficiency), `TableLabelTest`, `CustomerDisplayTextTest`, `SeatBulkEdit`, `BookingSearch`, `PlaceAssignment`, `MoneyConsistencyTest`, … | Unit |
| other `ui` logic | savers, `LeaveGuard`, `LiveUpdates`, `DisplayPreferencesTest`, `shared` (`ConnectFlowTest`, `SharedFolderStartTest`, `SyncStatusTest`, …), `backup`, `input` | Unit |
| `ui` end to end | `PlanEditsEndToEndTest` (edit seating plan, save, reload, delete plan — no booking with data lost, no cent changed) | Integration |

There are no UI tests that click through screens. A few tests start the JavaFX toolkit (`Platform.startup`) for
controls such as table cells, column widths and paints; this is why CI needs `xvfb-run`.

## Strategy

- **Logic out of JavaFX.** Classes like `ConnectFlow`, `ConflictActions`, `LockedSeats`, `SyncHint`, `SyncStatus` and
  the whole `update` package contain the decisions and are tested directly; the JavaFX classes next to them only
  render.
- **Deterministic store tests.** `TestSyncServices` builds a `SyncService` without threads or tick: UI and background
  work run on the caller's thread, and `TestClock` controls time. Tests call `tick()` explicitly.
- **Fault injection.** `FaultyFileLayer` wraps the real `FileLayer`, records every call and fails a chosen operation
  either before it runs or after it ran (simulating a lost response). This tests crash safety between any two steps,
  e.g. the upload order version → feed → claim. `ForceProbe` checks that every write path forces its data before the
  rename (KARV-90).
- **Sync-client simulation.** `SimulatedCloud` models a Nextcloud server plus one client folder per machine. The test
  decides when each client syncs, so arrival order is exact; it reproduces conflict copies, ignored `~*.tmp` files and
  non-propagated deletions.
- **Real files.** Store and repository tests work on JUnit temp directories with the production `NioFileLayer`.
- **Update fixtures.** `FakeSharedFolder`, `TestMsix` and the resources `AppxManifest.xml` and
  `ecv-karten-fx132.appinstaller` stand in for a shared folder and real Conveyor output.
- **Data format guards** (forward-compatible format, rules in
  [decisions.md](decisions.md#forward-compatible-data-format)). They fail on a format change that needs a decision:
  - `FormatGuardTest` finds every stored class and enum through the app's mapper: a new class, field or enum value, or
    a removed enum value, must follow the rules (tolerant or strict enum, `rulesId` matches the strict enum).
  - `JsonKeysTest` pins the exact keys every stored class writes and that no hidden, discarded or reserved name is
    written.
  - `ArchitectureGuardTest` scans `src/main/java`: a payload is read into the model only through
    `UnitMapper.readUnit`, which leaves out locked content.
  - `StoreCodecTest.envelopeKeysAreUnchanged` pins the keys of a version file's envelope (`StoreCodec.FORMAT` stays 1).
  - `FormatSamplesTest` loads every sample under `src/test/resources/format-samples/<version>/data.zip` (one per
    release, created with `FormatSampleGenerator`, see [installation.md](installation.md#releasing)): nothing counts as
    changed, and saving without a change writes nothing. It also builds the folder of a "1.2 machine" with
    `FutureWriter` and checks that extra fields survive and exactly the unsafe content is locked.
  - `ConflictScenarioTest` runs the same newer-version machine (`FutureWriter`, its own service with a permissive
    gate) against a 1.1 machine over the shared folder: extra fields survive edits, an unknown payment status locks the
    seat until a known value arrives, and `requires` on a presentation locks it.
- **Patch notes guard.** `PatchNotesBundledTest` reads the bundled `patchnotes.md` (the parser `PatchNotes` rejects
  placeholders, mentions, issue references and emoji shortcodes) and, through `PatchNotesGuard`, requires that its top
  version equals the version in `pom.xml` and `app.version` in `conveyor.conf`, as text and three-part (`a.b.c`). No
  release can be built without matching patch notes.
- **Test doubles in the test tree:** `FakeConnection` (connection control for UI flows), `TestRepositories`,
  `TrailFixture`, `VersionStoreFixtures`, `ForeignVersions`, `FutureWriter` (raw writes of a newer program version),
  `TestBookings`, `LogCapture` (assert log output), `SteppedClock`, `ForceProbe`, `FakeSharedFolder`.

## Conventions

- Test classes are package-private and live in the same package as the class under test, so they can use
  package-private API.
- A new `Issue` enum must be added to `IssueConventionsTest`.
- Comments and Javadoc are in German like the production code; method names are mixed (older tests German with
  underscores, e.g. `hasData_ist_wahr_bei_Nachname`, newer ones English camelCase, e.g.
  `singleModeAppendIsImmediatelyValid`).

No coverage tool (e.g. JaCoCo) is configured.
