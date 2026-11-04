# Technical decisions

## Desktop app with local data, no server

**Decision:** A JavaFX desktop application; every workstation has the full data set on its own disk.

**Why:** Selling must not depend on the network. When the shared folder is unreachable the app keeps working
(`SyncState.OFFLINE`: new versions are provisional and are uploaded once the folder is back), and in single mode
it needs nothing besides its own data directory.

**Trade-off:** No central authority; conflicts must be detected and resolved by the clients themselves (see below).

## A shared folder instead of a server or database

**Decision:** Workstations exchange data only through a shared folder (`<folder>/data`): a network share or a
folder synced by a client such as Nextcloud.

**Why:** No server to host, secure or update. The target setup is a Nextcloud-synced folder; the design is tested
against a simulated sync client (`SimulatedCloud`) that renames losing writes to `(conflicted copy …)` files,
uploads only when the test says so, ignores `~*.tmp` files and never propagates deletions.

**Consequences:** The store may only rely on "create file if absent" and atomic rename. Locks across machines are
not available, so there is no locking; arbitration is done by who creates a claim file first.

## Append-only versions and claim files

**Decision:** Every save creates new immutable version files; nothing is ever overwritten or deleted
(`FileLayer.createNew`). The valid state of a unit is the chain obtained by following claim files from
`claims/root.json` (`ChainResolver`). Rule: *first to upload wins* (`SharedArbiter`).

**Why:**
- Sync clients handle new files well and concurrent edits of the same file badly. Immutable files never conflict
  at the file level; a sync-client conflict copy can only affect a claim, and late defeats are detected by
  re-reading own claims for 15 minutes.
- Nothing is lost: a losing version stays on disk and is resolved into a merge version, and every past state is a
  restore point (`ui.backup`).
- Claims carry the SHA‑256 of the version file, so any copy can be verified and damaged files healed from another
  workstation (`DamageRepair`), keeping the damaged file as `.corrupt-<time>`.

**Upload order** `version file → feed entry → claim` (`Uploader`) guarantees that a claim never points to a version
that other workstations cannot find.

**Discarded alternative:** One JSON file per event that is overwritten on save — simple, but a sync client would
silently drop one of two concurrent sales.

## Small units instead of whole documents

**Decision:** Events are split into head, presentations and one unit per booking/seat (`UnitMapper`); a hall is one
unit. Only changed units are written (`UnitBaseline`).

**Why:** Two workstations selling different seats of the same show touch different units and never conflict. Only
selling the *same* seat is a real conflict (`DOUBLE_SALE`). A hall is edited rarely and by one person, so it stays
a single unit.

**Detail:** Booking units are keyed by seat id (`bookings/<seatId>`), unassigned ones by `_<bookingId>`; ids may
not contain `_`, so the prefix is unambiguous (`UnitKey`).

## Business conflict rules outside the store

**Decision:** The store resolves lost versions generically (merge version with parents `[theirs, mine]`) but takes
the content from a `ConflictPolicy` per unit type, installed by `repository.ConflictPolicies`.

**Why:** The store stays testable without the domain, and the domain rules (three-way merge per field group;
payment and price coupled; a cancelled seat sold again; a double sale moving "mine" to *unassigned* instead of
dropping it) live where the model is known. Policies are pure functions on JSON copies, so they can run on the
store's background thread.

**Without a policy** the default is "theirs stays" with note `UNMERGED`, which is safe but visible as a conflict.

## Snapshot-based change tracking

**Decision:** `ChangeTracker` compares serialized JSON (and its SHA‑256) with the saved state instead of making the
model observable or instrumenting setters. Comparison is triggered by a debounced filter on all window events
(`ChangeTrigger`).

**Why:** No change to every setter and every UI binding; an undone edit is automatically "clean" again. Using the
same `ObjectMapper` as the repositories (`JsonMapperFactory`) makes "changed" mean exactly "would write a different
file". The stored bytes double as the source for "Nicht speichern" (`StateRestorer`).

**Trade-off:** Serializing on every comparison costs CPU; the debounce keeps it out of the typing path.

## Validators return results, never throw

**Decision:** Validation lives in separate validator classes returning `ValidationResult`; model setters accept any
value. Messages are coded enums with SAP-style `&1…&9` templates.

**Why:** Jackson must be able to load any stored state, even one that a newer rule would reject. The UI decides
what to do with errors (block save) and warnings (show after save). Stable codes make user reports traceable;
`IssueConventionsTest` enforces code and placeholder conventions.

## Money in integer cents

All prices and amounts are `int`/`Integer` cents; formatting is centralized in `util.MoneyFormat` and
`GermanDecimalStringConverter`. Avoids rounding errors in totals and cash reconciliation (`MoneyConsistencyTest`).

## Startup index as a cache only

**Decision:** `UnitIndex` stores the resolved state per unit with a fingerprint of file names, sizes and modification
times; only changed units are re-resolved at startup.

**Why:** Every booking is its own unit, so a season has many unit folders; reading and resolving all of them on
each start would dominate startup time. Because the index is
never authoritative, a missing or corrupt index only costs time. Size and mtime are part of the fingerprint so that
a file replaced under the same name (healing, external tools) is noticed.

## One instance per machine, random machine id

`InstanceLock` prevents two processes on one machine, because they would generate colliding version ids and write
the same feed. The machine id in `machine.json` is random; a copied data directory is detected when two machines
write the same feed number (`DuplicateMachineException`, SYS-011), and a new id is assigned while `Ownership` keeps
the previous ids up to the time of reassignment.

## Platform-specific fat JARs

**Decision:** The JavaFX dependencies have a fixed classifier (`win` by default, `linux` with `-Plinux`) and all
transitive `org.openjfx` artifacts are excluded; the shade plugin produces one JAR per platform.

**Why:** The JavaFX POMs activate OS profiles of the *build* machine. On the Linux CI runner a Windows build would
otherwise contain both Windows and Linux natives, and whichever service registration was merged last decided the
toolkit at runtime ("No toolkit found" on Windows).

## Conveyor for installers, GitHub for hosting

**Decision:** Hydraulic Conveyor builds signed Windows MSIX and Linux packages; releases go to GitHub Releases and
the download page to the `gh-pages` branch (`make copied-site`).

**Why:** Auto-updates and installers without maintaining a server, and 40–90 MB binaries never enter the history of
`main`.

**Notes from `conveyor.conf`:** the "update escape hatch" (`updatecheck.exe`) is disabled because Norton quarantines
it; required JDK modules (`java.scripting`, `java.xml`, `jdk.unsupported`, …) are listed explicitly because jdeps
does not detect the reflective use by `FXMLLoader`.

## OpenPDF 1.3.x

The PDF export uses OpenPDF 1.3.43 on purpose: from 2.x on, ICU4J (~15 MB) is a mandatory dependency, and the
reports need no complex text layout.
