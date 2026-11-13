# Architecture

State: release 1.1.0.

## Overview

Kartenverwaltung is a single-process JavaFX desktop application. There is no server and no database: each
workstation keeps a complete local copy of all data as JSON files, and workstations exchange data through a plain
shared folder (local disk, network share or a folder synced by a client such as Nextcloud).

```
┌──────────────────────────────────────────────────────────────────────┐
│ ui (FXML + controllers)                                              │
│  sales · accounting · event · hall · settings · backup · shared      │
│  ChangeTrigger → ChangeTracker   LeaveGuard   EventSaver/HallSaver   │
│  DisplayPreferences (app-wide "Anzeige" menu)                        │
└──────┬────────────────────────────────────────▲──────────────┬───────┘
       │ edits model, saves                     │ foreign      │ update state,
       │                                        │ changes      │ folder offers
┌──────▼────────────────────────┐   ┌───────────┴──────────┐   ┌▼──────────────────┐
│ model  +  validation          │   │ repository           │   │ update (no JavaFX)│
│ Event, Presentation, Booking, │◄──┤ Store*Repository     │   │ UpdateChecker     │
│ Hall, SeatingPlan, Seat, …    │   │ UnitMapper, Baseline │   │ FolderWatch       │
│ UnknownFields, TolerantEnum   │   │ EventMerge, Policies │   │ PatchNotes        │
│ *Validator, Footprint         │   │ ContentRules         │   └────────┬──────────┘
└───────────────────────────────┘   └───────────┬──────────┘            │ SharedFolderAccess
                                                │ units (JSON payload)  │
┌───────────────────────────────────────────────▼───────────────────────▼──┐
│ store  (no scene graph; JavaFX properties only)                          │
│ SyncService ── tick every 2 s on a background thread                     │
│   VersionStore (local <AppData>/data)  ◄─ FullSync / FeedReader ─►       │
│   VersionStore (shared <folder>/data)     Uploader / FeedWriter          │
│   ChainResolver · SharedArbiter/LocalArbiter · ConflictResolver          │
│   ContentGate · UnitIndex · DamageRepair · Reachability                  │
│   FileLayer (NioFileLayer, DurableWrite) — the only disk access          │
└──────────────────────────────────────────────────────────────────────────┘
```

## Components

### `MainApp` – entry point and screen switching

`main()` installs file logging (`AppLogging`, rotating `<AppData>/logs/kartenverwaltung-N.log`) and the
`GlobalErrorHandler` (SYS-001 dialog for any uncaught exception), then launches JavaFX. `start()`:

1. Loads fonts and `SettingsStore` (`settings.json`) and installs the app-wide `DisplayPreferences` from it, so that
   "Anzeige beim Start wiederherstellen" refers to the state at start.
2. Creates the `SyncService` singleton. This takes the instance lock (`instance.lock`) and reads the machine
   identity (`machine.json`); if another instance is running, the app shows an error and stops (SYS-010).
3. Installs the conflict policies (`ConflictPolicies.install`) *before* the store starts, because startup already
   resolves lost changes.
4. Shows a loading view immediately and runs `SharedFolderStart.run` in a background `Task`. It compares the
   `sharedDir` setting with the store's "connected" marker (`connection.json`) and decides whether to start offline,
   continue, or connect to a new folder.
5. Loads halls first, then events (`loadRepositories`): event loading needs the hall lookup to drop bookings that do
   not fit the presentation's seating plan. If a first connection is still running, loading waits for it, so nobody
   works on a half-synced data set.
6. Asks for the operator (`OperatorDialog`), shows load warnings, then the main menu. `onLoaded` decides via
   `PatchNotesOnStart` whether the "Was ist neu" window appears, starts the `UpdateChecker` and the `FolderWatch`.

Screen changes go through `MainApp.show*View()` methods; each view is an FXML file with its controller. Every content
view is wrapped with a footer that shows sync status, open conflicts, locked entries and the update hint.

### `model` – domain objects

Plain mutable Java objects serialized by Jackson:

| Class | Content |
|---|---|
| `Event` | name, description, `archived`, list of `Presentation` |
| `Presentation` | date, time, `hallId`, `seatingPlanId`, default `price` (cents), `actualCashAmount`, bookings |
| `Booking` | `seatId` plus a snapshot of the seat (row label, table/seat number, category, wheelchair), price override, `PaymentStatus` (`NONE`, `CASH`, `CARD`, `TRANSFER`), `paidAmount`, collected flag, reservation/payment/collection dates, customer name, e-mail, comment, `unassigned` |
| `Hall` | dimensions, `HallObject`s (shapes with text), `SeatingPlan`s |
| `SeatingPlan` | default table/seat sizes (50 × 320 cm, seat 45 cm), `TableRow`s (position, angle, gaps, aisle), `Table`s with text settings, each with `Seat`s |
| `Seat` | number, position, `width` = diameter (`radius()`, fallback 10 cm), category, wheelchair flag |

Money is always `int` cents. Rules that every UI path must apply the same way live in the model as static helpers:

- `BookingSync` reconciles a presentation's bookings with its seating plan. It never drops a booking with data and
  never assigns one seat to two bookings; bookings whose seat disappeared become "unassigned" (*ohne Platz*).
- `SaleDates.change` sets reservation/payment/collection dates and the paid amount when a booking's state changes.
  The setters stay dumb because Jackson calls them while loading.
- `BookingCancellation` turns a seat booking into an empty placeholder with a new id.

**Labels.** `HallObject` and `Table` implement `Labeled`: five text fields (auto, size, horizontal/vertical alignment,
rotation) stored individually, bundled as the value type `TextSettings` for drawing and editing (never stored).

**Forward compatibility (1.1).** Every stored class keeps fields it does not know in `UnknownFields`
(`@JsonAnySetter`/`@JsonAnyGetter`, original JSON nodes in reading order), so they survive load, edit, save and copy.
`TolerantEnum` reads an unknown `Shape`/`TextAlignH`/`TextAlignV` as a fallback (`RECTANGLE`, `CENTER`) and writes the
original text back until someone changes the value. `Requires` evaluates the reserved top-level `requires` field of a
unit. Removed field names are listed per class in `@JsonIgnoreProperties`. Rules: [decisions.md](decisions.md#forward-compatible-data-format).

### `validation` – plausibility and layout checks

One validator per entity (`EventValidator`, `PresentationValidator`, `HallValidator`, `HallObjectValidator`,
`SeatingPlanValidator`, `TableRowValidator`, `TableValidator`, `SeatValidator`, `BookingValidator`). Validators never
throw; they return a `ValidationResult` of `ValidationIssue`s. Each issue comes from an enum implementing `Issue`: a
stable code (`EVT-`, `PRS-`, `HLL-`, `HOB-`, `SPL-`, `ROW-`, `TBL-`, `SEA-`), a `Severity`, and a message template with
SAP-style placeholders `&1`–`&9` (`MessageTemplate`). Errors block saving; warnings are shown after saving.

**Layout checks (KARV-60).** `Footprint` turns a table (plate plus seat circles) or a hall object into rotated
rectangles and circles without JavaFX, with the same rotation formula as the seat layout. The validators report as
warnings: hall objects partly overlapping (`HLL-003`), objects or tables beyond the hall edge (`HOB-005`, `TBL-007`),
tables overlapping each other (`SPL-004`) or partly overlapping a hall object (`SPL-005`). Tolerance is 1 cm; a part
lying fully inside another does not count, and tables without a valid row take no part (`TBL-005` reports them).

### `repository` – from model to units

`StoreEventRepository` and `StoreHallRepository` hold the in-memory cache of all events and halls and are the only
bridge between model and store.

- **Unit mapping** (`UnitMapper`): an event is split into an *event head* (without presentations), one
  *presentation* unit each (without bookings) and one *booking* unit per seat (or `_<bookingId>` for unassigned
  bookings). A hall is a single unit. Empty bookings are never written; `BookingSync` recreates them on load.
  `UnitMapper.readUnit` is the only way a payload enters the model; it leaves out locked content
  (`ArchitectureGuardTest`).
- **Delta writes** (`UnitBaseline`): on save only the units that differ from the loaded state are appended. "Locally
  changed" compares with the round trip of the baseline, so fields of other program versions never count as a change.
- **Foreign changes** (`EventMerge`, `ForeignBacklog`, `ForeignBatch`, `ForeignDecision`): versions written by other
  workstations are merged into the existing model objects so that open screens keep their object identity. Units the
  user is editing are *deferred* (shown in the footer); bookings whose seat is not in the model yet are *held*.
- **Conflict policies** (`EventHeadPolicy`, `PresentationPolicy`, `BookingPolicy`, `HallPolicy`): per unit type, a
  three-way merge (`FieldMerge`, per `FieldGroups`) of *base*, *mine* and *theirs*. `BookingPolicy` additionally
  tracks booking moves and cancellations (`BookingTrail`, `BookingFate`) and turns a double sale into "mine moves to
  *unassigned*" instead of losing a sale.
- **Content rules** (`ContentRules`): what the store's `ContentGate` treats as too new in content (`requires`, an
  unknown `paymentStatus`).
- **Restore** (`RestorePlanner`): computes save points from the valid chains of an event's or hall's units; the
  restore itself is a normal save of the target state.

### `store` – the version store and sync

The store knows nothing about events or halls; it versions opaque JSON payloads per `UnitKey`
(`EVENT`, `PRESENTATION`, `BOOKING`, `HALL`).

**On-disk model** (identical locally under `<AppData>/data` and in `<shared folder>/data`):

```
data/
├── events/<eventId>/
│   ├── <VersionId>.json                     event head versions
│   ├── claims/root.json, claims/<VersionId>.json
│   └── presentations/<presentationId>/
│       ├── <VersionId>.json, claims/…
│       └── bookings/<seatId>/ | bookings/_<bookingId>/
├── halls/<hallId>/<VersionId>.json, claims/…
└── feeds/<machineId>/<n>.json               (shared folder) change lists per workstation
```

- A **version** (`Version`) holds `id`, `parents`, machine id/name, operator, timestamp, `saveId`, `deleted`,
  `payload`, optional conflict note and `seen` (last feed number read per machine). Version ids (`VersionId`) are
  `<millis base36>-<machine prefix>-<counter>`, fixed width, so string order is time order. The envelope format
  (`StoreCodec.FORMAT`) is `1`.
- A **claim** (`Claim`) records which version took the slot after a predecessor, including the SHA‑256 of the version
  file. `claims/root.json` names the first version. Following claims from the root yields the **valid chain**
  (`ChainResolver`). Own versions outside the chain are either *pending* (built on the chain's end) or *lost* (built
  on an older state).
- Files are only ever **created** (`FileLayer.createNew`, "only if absent"); data files are never overwritten or
  deleted. Temporary files are named `~karv-….tmp` and are invisible to listings. Since 1.1 every temporary file is
  forced to disk before it is renamed or linked into place (`DurableWrite`, KARV-90).

**Single mode vs. shared mode.** Without a shared folder, `LocalArbiter` claims every version immediately. With a
shared folder, a version is provisional until uploaded: per save, `Uploader` writes *version files → feed entry →
claim files*, in that order, and `SharedArbiter` creates the claim in the shared folder ("first to upload wins").

**The tick** (`SyncService.tick`, every 2 s on a background thread):

1. Check reachability (`Reachability`, with timeout, because a hanging network drive blocks for ~30 s). A folder of a
   cloud client (`CloudFolders`: Nextcloud from `nextcloud.cfg`, OneDrive from the `OneDrive*` environment variables)
   also needs its server to answer a TCP connect (`ServerProbe`), unless "Verbindung zum Cloud-Server prüfen" is off.
2. Run a slice of the full sync (`FullSync`/`FullSyncRun`, budget 2 s per tick; a full run starts every 10 min, at
   startup, after coming back online and on request).
3. Continue upload jobs.
4. Discover new workstations and read their feeds (`FeedReader`).
5. Re-check own claims of the last 15 minutes in the shared folder (a sync client may report a defeat late).
6. Heal damaged files from verified copies (`DamageRepair`); the damaged file stays as `<name>.corrupt-<time>`.
7. Resolve lost versions (`ConflictResolver`) with the installed `ConflictPolicy`: a merge version with parents
   `[theirs, mine]` is written; the result is tracked in `ConflictLedger` for the conflict dialog.

`SyncState` reports `AUS` (single mode), `AKTUELL`, `AUSSTEHEND`, `OFFLINE`, `FEHLER` or `ENTSCHEIDUNG` (waiting for
the user to choose how to connect). `SyncListener` callbacks are delivered on the JavaFX thread.

**Connecting** (`SyncService.connect`, UI in `ui.shared.ConnectFlow`) supports three `ConnectMode`s: `MERGE` (take
local data along), `SET_ASIDE` (rename local `data` to `data-beiseite-<timestamp>` and start from the shared data)
and `ASK` (decide on first run; pause if both sides have data).

**Startup index** (`UnitIndex`, `<AppData>/index/`, `INDEX_FORMAT` 5): per unit the last resolved state and a
fingerprint over file names, sizes and modification times. Only units whose fingerprint changed are re-resolved. The
index is a cache, never an arbiter: losing it costs time, not correctness.

**Locked units.** A unit whose only version is unreadable (SYS-008) or was written by a newer program version
(SYS-009) is locked. SYS-009 also covers content this version cannot process safely: a `requires` entry or an unknown
payment status (`ContentGate`, rules in `repository.ContentRules`); the version is read but marked as too new in
content, and `ChainResolver` steps over it. The UI lists locked units under "gesperrte Einträge", shows affected seats
as blocked (`ui.sales.LockedSeats`), and offers a reset to the last readable state for damaged ones. A newer
workstation that writes a readable state unlocks the unit.

### `tracking` – unsaved changes

`ChangeTracker` (one each for events, halls and settings) detects unsaved changes by **comparing JSON snapshots**
(same mapper as the repositories, `JsonMapperFactory`) with the last loaded/saved state, instead of instrumenting
setters. `ui.ChangeTrigger` runs the comparison after a short debounce on any mouse/key/action event in any window.
`StateRestorer` replaces an entity in the cache from a snapshot ("Nicht speichern"); `LeaveGuard` asks save / don't
save / cancel when leaving a screen.

### `settings` – `settings.json`

`AppSettings` holds `sharedDir`, `saveBookingsImmediately`, `checkCloudServer`, `recentOperators`, `display`
(`DisplaySettings`: rows, tables, seat numbers, hall objects, `seatPatterns`, `summaryPlacement`, `restoreAtStart`)
and `patchNotesSeen`. `SettingsStore` reads and writes `<AppData>/settings.json`; `existedAtLoad()` tells a fresh
installation from an update. `DisplaySettings` stores the summary placement as text and ignores unknown fields, so a
value from a newer version never makes the file unreadable.

### `update` – version, updates and patch notes

The package uses neither JavaFX nor `ui` nor `store`.

- **Update check (KARV-131).** `UpdateChecker` reads `metadata.properties` of the update source (`app.repositoryUrl`)
  over HTTPS with Java's default trust store, 10 s after start and then every 4 hours, and publishes an
  `UpdateState`. The footer hint (`ui.shared.UpdateIndicator`) and *Einstellungen → Info und Updates*
  (`ui.settings.InfoSection`) follow it. `ui.shared.UpdateFlow` runs the update: confirmation, `confirmClose` like
  closing the window, a fresh check, download of the `.appinstaller` file and `Desktop.open`; then the app exits and
  the Windows App Installer takes over.
- **Shared folder (KARV-132).** `FolderWatch` ticks 30 s after start, after connecting and every 10 minutes:
  `StationRegistry` writes the own entry and reads the others (warning about a newer workstation); on the Windows
  package `FolderUpdates` finds and checks installers (`MsixManifest`, description with SHA-256) and cleans up,
  `FolderDecision` weighs the candidate against `UpdateChecker.lastSuccess()`, and `FolderDownloader` loads the
  current version in the background (claim files `LoadClaims`, `HttpGet`, `AppInstallerFile`). `FolderState` feeds the
  footer (`ui.shared.UpdateHint`), *Info und Updates* and the prompt at start (`ui.shared.StartPrompt`). The package
  sees the shared folder only through `SharedFolderAccess`, which `MainApp` connects to `SyncService`.
- **Patch notes (KARV-125).** One source, `src/main/resources/.../patchnotes.md`. `PatchNotes` reads and checks it
  (fixed sections, plain text, no placeholders); `PatchNotesOnStart` decides whether `ui.shared.PatchNotesWindow`
  appears and remembers the version in `settings.json`. The release workflow prints the section of the new version
  with `PatchNotesExport` and sets it as the GitHub release text; the app links to that page
  (`Installation.releasePage`) but never downloads it.

```
<shared folder>/
├── data/                                    version store (above)
└── updates/
    ├── kassen/<machineId>.json              version entry per workstation
    ├── <name>.msix, <name>.msix.json        installer and its description (only the newest stays)
    ├── laden-<a.b.c.d>-<machineId>.json     claim while a workstation downloads
    └── ~karv-<machineId>-<UUID>.tmp         download in progress
```

### `ui` – screens

Grouped by area: `menu` (main and management menu), `sales` (ticket table, hall overview, customer display),
`accounting` (cash reconciliation, statistics, CSV/PDF export), `event`, `hall` (hall plan and seating plan editor),
`settings`, `backup` (restore dialog), `shared` (shared-folder connection, sync indicator and info window, operator
dialog, locked units, update flow, patch notes), `input` (parsing text fields and converters with inline error
feedback). Cross-cutting helpers sit directly in `ui`: `EventSaver`/`HallSaver`/`SettingsSaver`, `LiveUpdates` (routes
foreign changes to the repositories and keeps the change tracker consistent), `BookingAutoSave` (immediate booking
saves), `LiveValidator`, `DisplayPreferences`.

Notable 1.1 pieces:

- **Seat layout** (`ui.hall.SeatingPlanLayout`): the table length is divided into equal sections per seat pair, so the
  spacing stays the same where two tables meet; a seat's center lies its radius + 5 cm beside the table edge.
  Automatically placed seats are recomputed from the table; manually placed ones keep their position.
- **Labels** (`ui.hall.PlanText`): one computation for hall objects and tables. A table label is computed in the
  unrotated table (rotation from the table axis), turns with the table and never stands upside down.
- **Seat look** (`ui.sales.SeatLook`): fill per `SeatStatus`. With the accessible display (KARV-26) free is a white
  seat with a green ring, reserved yellow with dark stripes, sold solid red, blocked gray with a white cross; rings and
  stripes are gradients with hard stops, only the cross is a separate node that lets clicks through.
- **Display preferences** (`ui.DisplayPreferences`, KARV-76): one app-wide instance of the "Anzeige" choices
  (`PlanLabelOptions`, seat patterns, summary placement), shared by sales view, customer display and hall editor.
  Views follow it through weak listeners (`PlanLabelOptions.follow`), and every change is written to
  `settings.json` as a partial save.

## Data flow

**Saving a sale (shared mode):**

1. A controller changes a `Booking` through `SaleDates.change`.
2. `ChangeTrigger` → `ChangeTracker` marks the event dirty; with "Buchungen sofort speichern" on, `BookingAutoSave`
   schedules `EventSaver.save`.
3. `EventSaver` validates the event, then `StoreEventRepository.save` diffs against `UnitBaseline` and calls
   `SyncService.append` for each changed unit (one `saveId` per save).
4. The version file is written locally (forced to disk); the state becomes `AUSSTEHEND`.
5. The next tick uploads it (version → feed → claim). If another workstation claimed the slot first, the version is
   lost, `ConflictResolver` writes a merge, and `ConflictLedger` reports e.g. `DOUBLE_SALE`.

**Receiving a foreign change:**

1. `FeedReader` reads another workstation's feed and copies new versions into the local store.
2. `SyncListener` reports changed units on the FX thread; `LiveUpdates` passes them to the hall repository first (and
   re-syncs affected bookings with the new seating plan via `BookingSyncService`), then to the event repository.
3. `EventMerge` updates the cached objects in place; the change tracker is rebased so the foreign change never counts
   as the user's own unsaved change.

**Updating from the shared folder (Windows):**

1. A workstation with internet sees a newer release (`UpdateChecker`), claims the download (`laden-…json`) and writes
   the `.msix` plus its description into `updates/`.
2. The sync client distributes the files; `FolderWatch` on the other workstations checks the installer completely
   (`FolderUpdates`) and offers it in the footer and once at start.
3. `UpdateFlow.updateFromFolder` saves like closing the window, opens the file with the App Installer and exits.

## Patterns

- **Layered architecture** with a strict direction: `ui` → `repository`/`validation`/`model` → `store`, and `ui` →
  `update`. The store uses JavaFX only for observable properties and takes its UI executor and background thread as
  constructor arguments; UI logic classes such as `ConnectFlow`, `LockedSeats` and `SyncStatus` need no scene graph.
  Both run in plain unit tests on the caller's thread (`TestSyncServices`).
- **Repository** pattern with an in-memory cache (singletons via `getInstance()`).
- **Event sourcing–like append-only store**: immutable versions with parents form a DAG per unit; claims select the
  valid chain.
- **Strategy**: `ConflictPolicy` per unit type, injected into the store from the repository layer.
- **Ports for I/O**: `FileLayer` is an interface so tests can inject faults (`FaultyFileLayer`) and simulate a sync
  client (`SimulatedCloud`); `update` has its own ports (`UpdateSource`, `DesktopOps`, `Scheduler`,
  `SharedFolderAccess`).
- **Snapshot-based dirty tracking** instead of observable model properties.
- **Tolerant reader** for the data format: unknown fields and enum values are kept, not rejected.

For the reasoning behind these choices see [decisions.md](decisions.md).
