# Architecture

## Overview

Kartenverwaltung is a single-process JavaFX desktop application. There is no server and no database: each
workstation keeps a complete local copy of all data as JSON files, and workstations exchange data through a plain
shared folder (local disk, network share or a folder synced by a client such as Nextcloud).

```
┌──────────────────────────────────────────────────────────────────────┐
│ ui (FXML + controllers)                                              │
│  sales · accounting · event · hall · settings · backup · shared      │
│  ChangeTrigger → ChangeTracker   LeaveGuard   EventSaver/HallSaver   │
└───────────────┬──────────────────────────────────────▲───────────────┘
                │ edits model, saves                   │ foreign changes (LiveUpdates)
┌───────────────▼───────────────┐   ┌──────────────────┴───────────────┐
│ model  +  validation          │   │ repository                       │
│ Event, Presentation, Booking, │◄──┤ StoreEventRepository             │
│ Hall, SeatingPlan, …          │   │ StoreHallRepository              │
│ BookingSync, SaleDates        │   │ UnitMapper, UnitBaseline,        │
│ *Validator → ValidationResult │   │ EventMerge, ConflictPolicies     │
└───────────────────────────────┘   └──────────────────┬───────────────┘
                                                       │ units (JSON payload)
┌──────────────────────────────────────────────────────▼───────────────┐
│ store  (no scene graph; JavaFX properties only)                      │
│ SyncService ── tick every 2 s on a background thread                 │
│   VersionStore (local <AppData>/data)  ◄─ FullSync / FeedReader ─►   │
│   VersionStore (shared <folder>/data)     Uploader / FeedWriter      │
│   ChainResolver · SharedArbiter/LocalArbiter · ConflictResolver      │
│   UnitIndex (start index) · DamageRepair · Reachability              │
│   FileLayer (NioFileLayer) — the only disk access                    │
└──────────────────────────────────────────────────────────────────────┘
```

## Components

### `MainApp` – entry point and screen switching

`main()` installs file logging (`AppLogging`, rotating `<AppData>/logs/kartenverwaltung-N.log`) and the
`GlobalErrorHandler` (SYS-001 dialog for any uncaught exception), then launches JavaFX. `start()`:

1. Loads fonts and `SettingsStore` (`settings.json`).
2. Creates the `SyncService` singleton. This takes the instance lock (`instance.lock`) and reads the machine
   identity (`machine.json`); if another instance is running, the app shows an error and stops.
3. Installs the conflict policies (`ConflictPolicies.install`) *before* the store starts, because startup already
   resolves lost changes.
4. Shows a loading view immediately and runs `SharedFolderStart.run` in a background `Task`. It compares the
   `sharedDir` setting with the store's "connected" marker (`connection.json`) and decides whether to start
   offline, continue, or connect to a new folder.
5. Loads halls first, then events (`loadRepositories`): event loading needs the hall lookup to drop bookings that
   do not fit the presentation's seating plan. If a first connection is still running, loading waits for it, so
   nobody works on a half-synced data set.
6. Asks for the operator (`OperatorDialog`), shows load warnings, then the main menu.

Screen changes go through `MainApp.show*View()` methods; each view is an FXML file with its controller. Every
content view is wrapped with a footer that shows sync status, open conflicts and locked entries.

### `model` – domain objects

Plain mutable Java objects serialized by Jackson:

| Class | Content |
|---|---|
| `Event` | name, description, `archived`, list of `Presentation` |
| `Presentation` | date, time, `hallId`, `seatingPlanId`, default `price` (cents), `actualCashAmount`, bookings |
| `Booking` | `seatId` plus a snapshot of the seat (row label, table/seat number, category, wheelchair), price override, `PaymentStatus` (`NONE`, `CASH`, `CARD`, `TRANSFER`), `paidAmount`, collected flag, reservation/payment/collection dates, customer name, e-mail, comment, `unassigned` |
| `Hall` | dimensions, `HallObject`s (shapes with text), `SeatingPlan`s |
| `SeatingPlan` | `TableRow`s (position, angle, gaps, aisle), `Table`s, each with `Seat`s |

Money is always `int` cents. Some rules live in the model as static helpers so every UI path uses them:

- `BookingSync` reconciles a presentation's bookings with its seating plan. It never drops a booking with data and
  never assigns one seat to two bookings; bookings whose seat disappeared become "unassigned" (*ohne Platz*).
- `SaleDates.change` sets reservation/payment/collection dates and the paid amount when a booking's state changes.
  The setters stay dumb because Jackson calls them while loading.
- `BookingCancellation` turns a seat booking into an empty placeholder with a new id.

### `validation` – plausibility checks

One validator per entity (`EventValidator`, `PresentationValidator`, `HallValidator`, `SeatingPlanValidator`,
`TableValidator`, `SeatValidator`, `BookingValidator`, …). Validators never throw; they return a
`ValidationResult` of `ValidationIssue`s. Each issue comes from an enum implementing `Issue`: a stable code
(`EVT-`, `PRS-`, `HLL-`, `HOB-`, `SPL-`, `ROW-`, `TBL-`, `SEA-` …), a `Severity`, and a message template with
SAP-style placeholders `&1`–`&9` (`MessageTemplate`). Errors block saving; warnings are shown after saving.

### `repository` – from model to units

`StoreEventRepository` and `StoreHallRepository` hold the in-memory cache of all events and halls and are the only
bridge between model and store.

- **Unit mapping** (`UnitMapper`): an event is split into an *event head* (without presentations), one
  *presentation* unit each (without bookings) and one *booking* unit per seat (or `_<bookingId>` for unassigned
  bookings). A hall is a single unit. Empty bookings are never written; `BookingSync` recreates them on load.
- **Delta writes** (`UnitBaseline`): on save only the units that differ from the loaded state are appended.
- **Foreign changes** (`EventMerge`, `ForeignBacklog`, `ForeignDecision`): versions written by other workstations
  are merged into the existing model objects so that open screens keep their object identity. Units the user is
  editing are *deferred* (shown in the footer); bookings whose seat is not in the model yet are *held* silently.
- **Conflict policies** (`EventHeadPolicy`, `PresentationPolicy`, `BookingPolicy`, `HallPolicy`): per unit type, a
  three-way merge (`FieldMerge`, per `FieldGroups`) of *base*, *mine* and *theirs*. `BookingPolicy` additionally
  tracks booking moves and cancellations (`BookingTrail`, `BookingFate`) and turns a double sale into "mine moves
  to *unassigned*" instead of losing a sale.
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
  `payload`, optional conflict note and `seen` (last feed number read per machine). Version ids
  (`VersionId`) are `<millis base36>-<machine prefix>-<counter>`, fixed width, so string order is time order.
- A **claim** (`Claim`) records which version took the slot after a predecessor, including the SHA‑256 of the
  version file. `claims/root.json` names the first version. Following claims from the root yields the **valid
  chain** (`ChainResolver`). Own versions outside the chain are either *pending* (built on the chain's end) or
  *lost* (built on an older state).
- Files are only ever **created** (`FileLayer.createNew`, "only if absent"); data files are never overwritten or
  deleted. Temporary files are named `~karv-….tmp` and are invisible to listings.

**Single mode vs. shared mode.** Without a shared folder, `LocalArbiter` claims every version immediately. With a
shared folder, a version is provisional until uploaded: per save, `Uploader` writes *version files → feed entry →
claim files*, in that order, and `SharedArbiter` creates the claim in the shared folder ("first to upload wins").

**The tick** (`SyncService.tick`, every 2 s on a background thread):

1. Check reachability (`Reachability`, with timeout, because a hanging network drive blocks for ~30 s). A folder
   of a cloud client (`CloudFolders`: Nextcloud from `nextcloud.cfg`, OneDrive from the `OneDrive*` environment
   variables) also needs its server to answer a TCP connect (`ServerProbe`). Every tick runs this full check.
2. Run a slice of the full sync (`FullSync`/`FullSyncRun`, budget 2 s per tick; a full run starts every 10 min,
   at startup, after coming back online and on request).
3. Continue upload jobs.
4. Discover new workstations and read their feeds (`FeedReader`).
5. Re-check own claims of the last 15 minutes in the shared folder (a sync client may report a defeat late).
6. Heal damaged files from verified copies (`DamageRepair`); the damaged file stays as `<name>.corrupt-<time>`.
7. Resolve lost versions (`ConflictResolver`) with the installed `ConflictPolicy`: a merge version with parents
   `[theirs, mine]` is written; the result is tracked in `ConflictLedger` for the conflict dialog.

`SyncState` reports `AUS` (single mode), `AKTUELL`, `AUSSTEHEND`, `OFFLINE`, `FEHLER` or `ENTSCHEIDUNG` (waiting
for the user to choose how to connect). `SyncListener` callbacks are delivered on the JavaFX thread.

**Connecting** (`SyncService.connect`, UI in `ui.shared.ConnectFlow`) supports three `ConnectMode`s: `MERGE`
(take local data along), `SET_ASIDE` (rename local `data` to `data-beiseite-<timestamp>` and start from the shared
data) and `ASK` (decide on first run; pause if both sides have data).

**Startup index** (`UnitIndex`, `<AppData>/index/`): per unit the last resolved state and a fingerprint over file
names, sizes and modification times. Only units whose fingerprint changed are re-resolved. The index is a cache,
never an arbiter: losing it costs time, not correctness.

**Locked units.** A unit whose only version is unreadable (SYS-008) or was written by a newer program version
(SYS-009) is locked. SYS-009 also covers content this version cannot process safely: a `requires` entry or an unknown
payment status (`ContentGate`, rules in `repository.ContentRules`); the version is read but marked as too new in
content, and `ChainResolver` steps over it. The UI lists locked units under "gesperrte Einträge", shows affected
seats as blocked (`ui.sales.LockedSeats`), and offers a reset to the last readable state for damaged ones. A newer
workstation that writes a readable state unlocks the unit. The rules for changing the data format are in
[decisions.md](decisions.md#forward-compatible-data-format).

### `tracking` – unsaved changes

`ChangeTracker` (one each for events, halls and settings) detects unsaved changes by **comparing JSON snapshots**
(same mapper as the repositories, `JsonMapperFactory`) with the last loaded/saved state, instead of instrumenting
setters. `ui.ChangeTrigger` runs the comparison after a short debounce on any mouse/key/action event in any window.
`StateRestorer` replaces an entity in the cache from a snapshot ("Nicht speichern"); `LeaveGuard` asks
save / don't save / cancel when leaving a screen.

### `update` – version and updates

`UpdateChecker` reads `metadata.properties` of the update source (`app.repositoryUrl`) over HTTPS with Java's default
trust store, 10 s after start and then every 4 hours, and publishes an `UpdateState`. The footer hint
(`ui.shared.UpdateIndicator`) and *Einstellungen → Info und Updates* (`ui.settings.InfoSection`) follow it.
`ui.shared.UpdateFlow` runs the update: confirmation, `confirmClose` like closing the window, a fresh check, download
of the `.appinstaller` file and `Desktop.open`, then the app exits and the Windows App Installer takes over. See
[decisions.md](decisions.md#update-check-and-update-from-the-app-karv-131).

**Shared folder (KARV-132).** `FolderWatch` ticks 30 s after start, after connecting and every 10 minutes:
`StationRegistry` writes the own entry and reads the others (warning about a newer workstation); on the Windows package
`FolderUpdates` finds and checks installers (`MsixManifest`, description with SHA-256) and cleans up, `FolderDecision`
weighs the candidate against `UpdateChecker.lastSuccess()`, and `FolderDownloader` loads the current version in the
background (claim files, `HttpGet`, `AppInstallerFile`). `FolderState` feeds the footer (`ui.shared.UpdateHint`),
*Info und Updates* and the prompt at start (`ui.shared.StartPrompt`); `UpdateFlow.updateFromFolder` opens the file.
The package only sees the shared folder through `SharedFolderAccess`, which `MainApp` connects to `SyncService`.

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
`accounting` (cash reconciliation, statistics, CSV/PDF export), `event`, `hall` (hall plan and seating plan
editor), `settings`, `backup` (restore dialog), `shared` (shared-folder connection, sync indicator and info window,
operator dialog, locked units), `input` (parsing text fields and converters with inline error feedback).
Cross-cutting helpers sit directly in `ui`: `EventSaver`/`HallSaver`/`SettingsSaver`, `LiveUpdates` (routes foreign
changes to the repositories and keeps the change tracker consistent), `BookingAutoSave` (immediate booking saves).

## Data flow

**Saving a sale (shared mode):**

1. A controller changes a `Booking` through `SaleDates.change`.
2. `ChangeTrigger` → `ChangeTracker` marks the event dirty; with "Buchungen sofort speichern" on,
   `BookingAutoSave` schedules `EventSaver.save`.
3. `EventSaver` validates the event, then `StoreEventRepository.save` diffs against `UnitBaseline` and calls
   `SyncService.append` for each changed unit (one `saveId` per save).
4. The version file is written locally; the state becomes `AUSSTEHEND`.
5. The next tick uploads it (version → feed → claim). If another workstation claimed the slot first, the version
   is lost, `ConflictResolver` writes a merge, and `ConflictLedger` reports e.g. `DOUBLE_SALE`.

**Receiving a foreign change:**

1. `FeedReader` reads another workstation's feed and copies new versions into the local store.
2. `SyncListener` reports changed units on the FX thread; `LiveUpdates` passes them to the hall repository first
   (and re-syncs affected bookings with the new seating plan via `BookingSyncService`), then to the event
   repository.
3. `EventMerge` updates the cached objects in place; the change tracker is rebased so the foreign change never
   counts as the user's own unsaved change.

## Patterns

- **Layered architecture** with a strict direction: `ui` → `repository`/`validation`/`model` → `store`. The store
  uses JavaFX only for observable properties and takes its UI executor and background thread as constructor
  arguments; UI logic classes such as `ConnectFlow`, `LockedSeats` and `SyncStatus` need no scene graph. Both run in
  plain unit tests on the caller's thread (`TestSyncServices`).
- **Repository** pattern with an in-memory cache (singletons via `getInstance()`).
- **Event sourcing–like append-only store**: immutable versions with parents form a DAG per unit; claims select
  the valid chain.
- **Strategy**: `ConflictPolicy` per unit type, injected into the store from the repository layer.
- **Ports for I/O**: `FileLayer` is an interface so tests can inject faults (`FaultyFileLayer`) and simulate a
  sync client (`SimulatedCloud`).
- **Snapshot-based dirty tracking** instead of observable model properties.

For the reasoning behind these choices see [decisions.md](decisions.md).
