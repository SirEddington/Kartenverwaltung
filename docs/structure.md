# Project structure

State: release 1.1.0.

## Directory tree

```
Kartenverwaltung/
├── .claude/
│   ├── CLAUDE.md                 Guidance for Claude Code: commands, where things are, local working folder
│   └── skills/spec/              Project skill /spec: writes a spec and has two independent reviewers check it
├── .github/
│   ├── CONTRIBUTING.md           Rules for working on the project (German): branches, workflow of a change, releases
│   ├── pull_request_template.md  Checklist of every pull request
│   ├── workflows/
│   │   ├── ci.yml                Every push and pull request: tests + Linux JAR (xvfb), release text check
│   │   └── release.yml           Manual release: tests, two fat JARs, release text, Conveyor publish
│   └── scripts/
│       └── release-notes.sh      Writes the GitHub release text from patchnotes.md (PatchNotesExport)
├── .mvn/wrapper/                 Maven wrapper (Maven 3.9.9)
├── mvnw, mvnw.cmd
├── pom.xml                       Dependencies, -Plinux profile, shade + javafx plugins
├── conveyor.conf                 Installer configuration (Windows amd64, Linux amd64), app.updates = manual
├── LICENSE                       GPL-3.0
├── docs/                         This documentation
│   ├── kartenverwaltung-map.html Interactive code map (standalone HTML)
│   ├── roadmap.md                Long-term roadmap (German): order of the planned versions and why
│   └── specs/                    Specs of changes (German); snapshots, not maintained after the merge
└── src/
    ├── main/
    │   ├── java/de/eltviller_carneval_verein/karten/
    │   │   ├── MainApp.java              JavaFX Application, startup, screen switching, footer
    │   │   ├── AppPaths.java             Data directory resolution
    │   │   ├── AppLogging.java           Rotating log file
    │   │   ├── GlobalErrorHandler.java   Uncaught exception dialog (SYS-001)
    │   │   ├── model/        (22 classes)
    │   │   ├── validation/   (26 classes)
    │   │   ├── repository/   (32 classes)
    │   │   ├── store/        (73 classes)
    │   │   ├── tracking/     (2 classes)
    │   │   ├── settings/     (3 classes)
    │   │   ├── update/       (40 classes)
    │   │   ├── util/         (4 classes)
    │   │   └── ui/           (33 classes in the root package)
    │   │       ├── accounting/ (6)  backup/ (6)   event/ (8)    hall/ (17)   input/ (17)
    │   │       └── menu/ (2)        sales/ (50)   settings/ (9) shared/ (30)
    │   └── resources/de/eltviller_carneval_verein/karten/
    │       ├── patchnotes.md     Patch notes of every version (KARV-125), newest first
    │       └── ui/
    │           ├── *View.fxml, *Panel.fxml   17 FXML files, one per screen/panel
    │           ├── style.css
    │           ├── fonts/        Yanone Kaffeesatz (OFL)
    │           └── images/       harlekin_logo.png (app icon), wappen.png
    └── test/
        ├── java/de/eltviller_carneval_verein/karten/   (same packages as main, 230 test classes)
        └── resources/
            ├── de/…/karten/update/   AppxManifest.xml, ecv-karten-fx132.appinstaller (update fixtures)
            └── format-samples/<version>/data.zip + README.md   Data of each release (1.0.1, 1.1.0)
```

Files that exist locally but are git-ignored: `target/` (build output, including the release text written by
`release-notes.sh`), Eclipse metadata (`.classpath`, `.project`, `.settings/`) and `.local/`. `.local/` is the one
place for everything that only lives on a developer's machine: `tmp/` (scratch files), `superpowers/` (old specs,
plans, handoffs, notes and task reports), `handtest/` (drivers for manual tests), `testrunner/` (a JUnit launcher
with its own classpath) and `deployment/conveyor.local.conf`.

## Packages and responsibilities

### Root package
Bootstrapping only. `MainApp` owns the primary stage and the global UI singletons (sync status, conflict list, locked
units, update state). `AppPaths.appDataDir()` is the single source for the data directory.

### `model`
Domain entities (`Event`, `Presentation`, `Booking`, `Hall`, `HallObject`, `SeatingPlan`, `TableRow`, `Table`,
`Seat`), enums (`PaymentStatus`, `SeatStatus`, `Shape`, `TextAlignH`, `TextAlignV`), labels (`Labeled`,
`TextSettings`), domain operations that must be applied the same way everywhere (`BookingSync`, `SaleDates`,
`BookingCancellation`) and the forward-compatibility helpers (`UnknownFields`, `TolerantEnum`, `Requires`).
**Not here:** validation rules (setters accept any value), persistence, JavaFX views.

### `validation`
One `*Validator` and one `*Issue` enum per entity, plus `ValidationResult`, `ValidationIssue`, `Severity`,
`MessageTemplate`, `RangeCheck`, `DuplicateNames`, `EntityLabels` and `Footprint` (geometry of the layout checks).
New issue enums must be registered in `IssueConventionsTest`.
**Not here:** technical errors (those are `SYS-` messages in `store.StoreMessages` and `GlobalErrorHandler`).

### `repository`
`EventRepository`/`HallRepository` interfaces with `Store*Repository` implementations, unit mapping (`UnitMapper`,
`UnitBaseline`), merging foreign versions into the cache (`EventMerge`, `ForeignBacklog`, `ForeignBatch`,
`ForeignDecision`, `ForeignResult`, `Deferral`), conflict policies (`*Policy`, `ConflictPolicies`, `FieldMerge`,
`FieldGroups`, `PolicyRules`, `BookingTrail`, `BookingFate`), content rules for the store's gate (`ContentRules`),
restore planning (`RestorePlanner`, `RestorePlan`, `SavePoint`, `Restored`), `LockedUnitException`, listeners, and
`JsonMapperFactory` (the only `ObjectMapper` configuration).
**Not here:** file paths or file formats — that is the store's job.

### `store`
The domain-agnostic version store: file formats (`Version`, `Claim`, `Feed`, `StoreCodec`), file access (`FileLayer`,
`NioFileLayer`, `AtomicFileReplace`, `DurableWrite`), chain resolution and arbitration (`ChainResolver`,
`LocalArbiter`, `SharedArbiter`), content gate (`ContentGate`), sync (`SyncService`, `Uploader`,
`FeedReader`/`FeedWriter`, `FullSync`, `FullSyncRun`, `Reachability`, `CloudFolders`, `ServerProbe`), conflicts
(`ConflictResolver`, `ConflictLedger`, `ConflictNote`), healing (`DamageRepair`), machine identity and locking
(`MachineIdentity`, `ComputerName`, `Ownership`, `InstanceLock`), start index (`UnitIndex`), `connection.json` (`ConnectionMarker`),
user-facing technical messages (`StoreMessages`, SYS-005…SYS-013).
**Not here:** UI code or knowledge of events/halls; business conflict rules are injected via `ConflictPolicy`.

### `tracking`
`ChangeTracker` (snapshot comparison) and `StateRestorer` (replace an entity from a snapshot). JavaFX thread only.

### `settings`
`AppSettings` (`sharedDir`, `saveBookingsImmediately`, `checkCloudServer`, `recentOperators`, `display`,
`patchNotesSeen`), `DisplaySettings` (the "Anzeige" menu: rows, tables, seat numbers, hall objects, `seatPatterns`,
`selectionHint`, `selectionHintPlacement` and `summaryPlacement` as text, `selectionHintAvoidEdges`,
`ticketGrouping` as a list of column names, `ticketGroupOrder` as text and `ticketGroupingNested` (default `true`), `restoreAtStart`) and `SettingsStore` (`<AppData>/settings.json`, `saveOnly` for partial
saves, `existedAtLoad()`). Always read through `SettingsStore.getInstance().current()`.

### `update`
Without JavaFX.
- **Update check (KARV-131):** `AppVersion`, `Installation` (version, update source, platform, package family from
  Conveyor's system properties, `releasePage`), `Plattform`, `UpdateInfo` (`metadata.properties`), `UpdateSource`/
  `HttpUpdateSource`, `Grund`, `UpdateState`, `UpdateSuccess`, `UpdateChecker` (timer, `lastSuccess`), `Scheduler`/
  `ExecutorScheduler`, `DesktopOps`/`AwtDesktopOps`.
- **Update over the shared folder (KARV-132):** `UpdatesDir` (folder `updates/` and name rules), `StationEntry`/
  `StationRegistry` (version entries), `MsixManifest`, `PackageFamily`, `AppInstallerFile`, `SecureXml`, `HttpGet`,
  `FolderUpdates` (candidate, check, cleanup), `FolderOffer`, `FolderDownloader` and `LoadClaims` (background download
  with claim files), `FolderDecision`, `FolderState`, `FolderWatch` (timer), `SharedFolderAccess`,
  `SharedFileReplace`, `OwnFiles`, `UpdateFiles`, `FileKey`.
- **Patch notes (KARV-125):** `PatchNotes` (reads and checks `patchnotes.md`), `PatchNotesOnStart` (what to show
  after an update), `PatchNotesExport` (command line, prints the GitHub release text).

### `util`
Small pure helpers: `MoneyFormat`, `BookingTotals`, `NumberText`, `Angles`.

### `ui`
| Subpackage | Content |
|---|---|
| `menu` | Main menu (Abrechnung, Kartenverkauf, Verwaltung, Einstellungen) and management menu |
| `sales` | Ticket sales shell, ticket table, hall overview (`HallPlanPane`, `HallViewport`), customer display, `SeatLook` (accessible seat display), bulk edit, search, cancel, assign place, purchase summary placement. The ticket table (KARV-193) is split into JavaFX-free decisions and the tree table: `TicketColumn` (columns as values: heading, group key, text, order), `TicketFilter` (column filters), `TicketGrouping` with `TicketNode` and `GroupOrder` (groups and counts), `TicketSelection` (selection as a set of bookings) and `TicketRow` (row type: booking or group); and the JavaFX side: `TicketTreeTable` (the `TreeTableView`) with `TicketTreeBuilder` (the `TreeItem`s), `TicketTreeRow`, `TicketCells`, `TicketEdits` (when to rebuild, which bookings stay visible), `MouseGesture` (a mouse operation, which holds rebuilds back; no JavaFX), `ColumnFilterMenu`, `GroupingMenu` and `GroupingBar` (the draggable chips of the grouping order) |
| `accounting` | Accounting shell, cash reconciliation, statistics, `Exportable` (CSV/PDF), locked bookings notice |
| `event` | Event overview, create/edit, presentations, templates (copies), seating plan switch, locked entry guard |
| `hall` | Hall management and editor, hall plan and seating plan drawing (`SeatingPlanDrawing`, `PlanNodeFactory`), seat layout (`SeatingPlanLayout`), labels (`PlanText`, `PlanLabels`, `PlanLabelOptions`), row fill dialog, bulk edits of rows (`TableRowBulkEdit`) |
| `settings` | Searchable settings screen with the sections "Gemeinsamer Ordner", "Anzeige" and "Info und Updates"; `PatchNoteActions` |
| `shared` | Shared-folder start/connect flow, sync indicator and info window, operator dialog, locked units, data reload, update flow, footer hints, start prompt, `PatchNotesWindow` ("Was ist neu"), `DialogConfirmation` |
| `backup` | Restore dialog and catalogs for events and halls |
| `input` | Parsing text fields, spinners, date/time, rotation and number inputs with inline feedback (`INP-` messages). The spreadsheet-like table handling `TableInput` works against `TableAccess` with two implementations, `TableViewAccess` and `TreeTableViewAccess`, and shares its mouse and scroll helpers with the ticket table in `TableGestures`; the parsing text cells share their logic in `ParsingEditing` (`ParsingTextFieldTableCell` and `ParsingTextFieldTreeTableCell`, `ParsingCell` marks the cells `TableInput` edits itself) |
| *(root)* | Savers, `LeaveGuard`, `ChangeTrigger`, `LiveUpdates`, `LiveValidator`, conflict dialog/list/indicator, status footer, `DisplayPreferences` (the app-wide "Anzeige" choices) |

Controllers are referenced by `fx:controller` in the FXML files under `src/main/resources/.../ui/`.

## Layer rules

- `store` does not know the model. Inside the app it uses only `AppPaths`, `repository.JsonMapperFactory` (shared JSON
  mapper) and `validation.MessageTemplate` (message placeholders). From JavaFX it uses only observable read-only
  properties (`syncState()`, `connectionComplete()`, …) and `Platform` as the default UI executor; no scene graph.
- `repository` depends on `model` and `store`; `ui` depends on everything below it.
- `validation` uses no JavaFX: `Footprint` computes the layout checks in plain geometry, and the seat radius comes from
  the model (`Seat.radius()`), not from `ui.hall`.
- `settings` does not know the UI: the summary placement is stored as text and turned into an enum in
  `ui.DisplayPreferences`.
- `update` uses neither JavaFX nor `ui` nor `store`; inside the app it uses only `repository.JsonMapperFactory` (JSON as
  `JsonNode` trees). It sees the shared folder through `SharedFolderAccess`, which `MainApp` connects to
  `SyncService`. `ui.shared.UpdateFlow`, `UpdateIndicator`, `UpdateHint`, `StartPrompt`, `UpdateTexts` and
  `ui.settings.InfoSection` build on it.
- A payload enters the model only through `UnitMapper.readUnit` (`ArchitectureGuardTest`).
- Logic that has to be tested without a running JavaFX toolkit is kept in plain classes (e.g. `ConnectFlow`,
  `SyncStatus`, `LockedSeats`, `PlaceAssignment`) with a thin JavaFX wrapper next to it.
- Tests mirror the main package of the class under test; shared test doubles live next to the tests that use them
  (`store/FaultyFileLayer`, `store/SimulatedCloud`, `store/TestSyncServices`, `ui/shared/FakeConnection`,
  `repository/TestRepositories`).
