# Project structure

## Directory tree

```
Kartenverwaltung/
├── .github/workflows/
│   ├── ci.yml                    Tests on every push and pull request (Linux runner, xvfb)
│   └── release.yml               Manual release: tests, two fat JARs, Conveyor publish
├── .mvn/wrapper/                 Maven wrapper (Maven 3.9.9)
├── mvnw, mvnw.cmd
├── pom.xml                       Dependencies, -Plinux profile, shade + javafx plugins
├── conveyor.conf                 Installer configuration (Windows amd64, Linux amd64)
├── LICENSE                       GPL-3.0
└── src/
    ├── main/
    │   ├── java/de/eltviller_carneval_verein/karten/
    │   │   ├── MainApp.java              JavaFX Application, startup, screen switching, footer
    │   │   ├── AppPaths.java             Data directory resolution
    │   │   ├── AppLogging.java           Rotating log file
    │   │   ├── GlobalErrorHandler.java   Uncaught exception dialog (SYS-001)
    │   │   ├── model/
    │   │   ├── validation/
    │   │   ├── repository/
    │   │   ├── store/
    │   │   ├── tracking/
    │   │   ├── settings/
    │   │   ├── update/
    │   │   ├── util/
    │   │   └── ui/
    │   │       ├── accounting/   backup/   event/   hall/   input/
    │   │       ├── menu/         sales/    settings/  shared/
    │   │       └── *.java        cross-cutting UI helpers
    │   └── resources/de/eltviller_carneval_verein/karten/ui/
    │       ├── *View.fxml, *Panel.fxml   one FXML per screen/panel
    │       ├── style.css
    │       ├── fonts/            Yanone Kaffeesatz (OFL)
    │       └── images/           harlekin_logo.png (app icon), wappen.png
    └── test/java/de/eltviller_carneval_verein/karten/
        └── (same packages as main)
```

Files that exist locally but are git-ignored: `target/`, `bin/`, Eclipse metadata (`.classpath`, `.project`,
`.settings/`), `dependency-reduced-pom.xml`, `deployment/` (old local release script and
`conveyor.local.conf`), `docs/superpowers/` (working specs and plans), `events_data/`, `CLAUDE.md`, `*.log`.

## Packages and responsibilities

### Root package
Bootstrapping only. `MainApp` owns the primary stage and the global UI singletons (sync status, conflict list,
locked units). `AppPaths.appDataDir()` is the single source for the data directory.

### `model`
Domain entities (`Event`, `Presentation`, `Booking`, `Hall`, `HallObject`, `SeatingPlan`, `TableRow`, `Table`,
`Seat`), enums (`PaymentStatus`, `SeatStatus`, `Shape`, `TextAlign*`) and domain operations that must be applied
the same way everywhere (`BookingSync`, `SaleDates`, `BookingCancellation`).
**Not here:** validation rules (setters accept any value), persistence, JavaFX views.

### `validation`
One `*Validator` and one `*Issue` enum per entity, plus `ValidationResult`, `Severity`, `MessageTemplate`,
`RangeCheck`, `DuplicateNames`, `EntityLabels`. New issue enums must be registered in `IssueConventionsTest`.
**Not here:** technical errors (those are `SYS-` messages in `store.StoreMessages` and `GlobalErrorHandler`).

### `repository`
`EventRepository`/`HallRepository` interfaces with `Store*Repository` implementations, unit mapping
(`UnitMapper`, `UnitBaseline`), merging foreign versions into the cache (`EventMerge`, `ForeignBacklog`,
`ForeignBatch`, `ForeignDecision`), conflict policies (`*Policy`, `FieldMerge`, `FieldGroups`, `PolicyRules`,
`BookingTrail`, `BookingFate`), restore planning, and `JsonMapperFactory` (the only `ObjectMapper` configuration).
**Not here:** file paths or file formats — that is the store's job.

### `store`
The domain-agnostic version store: file formats (`Version`, `Claim`, `Feed`, `StoreCodec`), file access
(`FileLayer`, `NioFileLayer`, `AtomicFileReplace`), chain resolution and arbitration (`ChainResolver`,
`LocalArbiter`, `SharedArbiter`), sync (`SyncService`, `Uploader`, `FeedReader`/`FeedWriter`, `FullSync`,
`FullSyncRun`), conflicts (`ConflictResolver`, `ConflictLedger`, `ConflictNote`), healing (`DamageRepair`),
machine identity and locking (`MachineIdentity`, `Ownership`, `InstanceLock`), start index (`UnitIndex`),
`connection.json` (`ConnectionMarker`), user-facing technical messages (`StoreMessages`, SYS-005…SYS-013).
**Not here:** UI code or knowledge of events/halls; business conflict rules are injected via `ConflictPolicy`.

### `tracking`
`ChangeTracker` (snapshot comparison) and `StateRestorer` (replace an entity from a snapshot). JavaFX-thread only.

### `settings`
`AppSettings` (fields with defaults: `sharedDir`, `saveBookingsImmediately`, `checkCloudServer`, `recentOperators`,
`display` → `DisplaySettings`, the "Anzeige" menu and whether it is restored at start) and
`SettingsStore` (`<AppData>/settings.json`). Always read through `SettingsStore.getInstance().current()`.

### `update`
Update check without JavaFX (KARV-131): `AppVersion`, `Installation` (version, update source, platform, package
family from Conveyor's system properties), `UpdateInfo` (`metadata.properties`), `HttpUpdateSource`, `Grund`,
`UpdateState`, `UpdateChecker` (timer) and `DesktopOps`.

### `util`
Small pure helpers: `MoneyFormat`, `BookingTotals`, `NumberText`, `Angles`.

### `ui`
| Subpackage | Content |
|---|---|
| `menu` | Main menu (Abrechnung, Kartenverkauf, Verwaltung, Einstellungen) and management menu |
| `sales` | Ticket sales shell, ticket table, hall overview, customer display window, bulk edit, search, cancel, assign place |
| `accounting` | Accounting shell, cash reconciliation, statistics, `Exportable` (CSV/PDF) |
| `event` | Event overview, create/edit, presentations, seating plan switch |
| `hall` | Hall management, hall editor, hall plan and seating plan drawing/layout, row fill, bulk edits; `DisplayPreferences` (the app-wide "Anzeige" menu shared with sales) |
| `settings` | Searchable settings screen, "Gemeinsamer Ordner", "Anzeige" and "Info und Updates" sections |
| `shared` | Shared-folder start/connect flow, sync indicator and info window, operator dialog, locked units, data reload, update flow, texts and footer hint |
| `backup` | Restore dialog and catalogs for events and halls |
| `input` | Parsing text fields, spinners, date/time and number inputs with inline feedback |
| *(root)* | Savers, `LeaveGuard`, `ChangeTrigger`, `LiveUpdates`, `LiveValidator`, conflict dialog/list/indicator, status footer |

Controllers are referenced by `fx:controller` in the FXML files under `src/main/resources/.../ui/`.

## Layer rules

- `store` does not know the model. Inside the app it uses only `AppPaths`, `repository.JsonMapperFactory` (shared
  JSON mapper) and `validation.MessageTemplate` (message placeholders). From JavaFX it uses only observable
  read-only properties (`syncState()`, `connectionComplete()`, …) and `Platform` as the default UI executor; no
  scene graph.
- `repository` depends on `model` and `store`; `ui` depends on everything below it.
- `update` (version, update source, update check; KARV-131) uses neither JavaFX nor `ui`; `ui.shared.UpdateFlow`,
  `UpdateIndicator`, `UpdateTexts` and `ui.settings.InfoSection` build on it.
- Logic that has to be tested without a running JavaFX toolkit is kept in plain classes (e.g. `ConnectFlow`,
  `SyncStatus`, `LockedSeats`, `PlaceAssignment`) with a thin JavaFX wrapper next to it.
- Tests mirror the main package of the class under test; shared test doubles live next to the tests that use them
  (`store/FaultyFileLayer`, `store/SimulatedCloud`, `store/TestSyncServices`, `ui/shared/FakeConnection`,
  `repository/TestRepositories`).
