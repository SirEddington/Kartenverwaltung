# Technical decisions

State: release 1.1.0.

Decisions added or changed in 1.1:

| Decision | Ticket |
|---|---|
| [Offline detection for cloud folders](#offline-detection-for-cloud-folders-karv-127) | KARV-127 |
| [Layout checks only warn](#layout-checks-only-warn) | KARV-60 |
| [Round seats in equal sections, tables keep their place](#round-seats-in-equal-sections-tables-keep-their-place-karv-128) | KARV-128 |
| [Durable writes before rename](#durable-writes-before-rename-karv-90) | KARV-90 |
| [Forward-compatible data format](#forward-compatible-data-format) | KARV-133 – KARV-136 |
| [JavaFX native libraries extracted at build time](#javafx-native-libraries-extracted-at-build-time) | KARV-137 |
| [No public code-signing certificate (for now)](#no-public-code-signing-certificate-for-now) | KARV-137 |
| [Updates only with the user's consent](#updates-only-with-the-users-consent-appupdates--manual) | KARV-130 |
| [Update check and update from the app](#update-check-and-update-from-the-app-karv-131) | KARV-131 |
| [Patch notes](#patch-notes-karv-125) | KARV-125 |
| [Update over the shared folder](#update-over-the-shared-folder-karv-132) | KARV-132 |
| [Accessible seat display](#accessible-seat-display-karv-26) | KARV-26 |
| [One "Anzeige" choice for all plan views, restored at start](#one-anzeige-choice-for-all-plan-views-restored-at-start-karv-76) | KARV-76 |

Decisions added or changed in 1.2:

| Decision | Ticket |
|---|---|
| [Ticket table as tree table](#ticket-table-as-tree-table-karv-193) | KARV-193 |
| [Cash registers as units](#cash-registers-as-units-karv-144) | KARV-144 |
| [Forward-compatible data format](#forward-compatible-data-format): first known `requires` feature, new kind of unit | KARV-144 |

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

## Offline detection for cloud folders (KARV-127)

**Decision:** A shared folder that belongs to Nextcloud (found through `nextcloud.cfg`: `%APPDATA%\Nextcloud` on
Windows; on Linux `$XDG_CONFIG_HOME/Nextcloud` or `~/.config/Nextcloud` and the Flatpak's config folder, KARV-179)
or OneDrive (the `OneDrive*` environment variables) only counts as reachable if its server also answers a TCP connect
(`CloudFolders`, `ServerProbe`: no proxy, two attempts, 5 s in total). Network drives and plain local folders are checked as before.
Every tick runs the full check. "Verbindung zum Cloud-Server prüfen" (on by default) switches the server check off.

**Why:** A sync folder lies on the local disk and was therefore always "reachable". Without internet, new sales
counted as uploaded although the client could not transfer them. With the server check the workstation shows
`OFFLINE`, and its sales stay provisional until the folder is really reachable again.

**Trade-off:** A firewall that blocks the direct TCP connect while the client itself syncs makes the workstation look
offline; the setting is the escape hatch for that case.

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

## Layout checks only warn

**Decision:** Overlaps between tables and hall objects, and parts lying outside the hall, are warnings, never errors.
The tolerance is 1 cm (`Footprint.TOLERANCE`). A table counts together with its seats. Objects or tables that lie
fully inside another one do not count as overlapping, and seats are not checked against their own table.

**Why:** Some overlaps are intended (a door on the wall, a stage with a table on it), and while rows or objects are
being moved into place the intermediate state must not block saving.

**Detail:** The geometry (`Footprint`) lives in `validation` without JavaFX and uses the seat radius from the model
(`Seat.radius()`), so the checks run in plain unit tests and never depend on `ui.hall`. Tables without a valid row take
no part in the layout checks; `TBL-005` already reports them as an error.

## Round seats in equal sections, tables keep their place (KARV-128)

**Decision:**
- A seat is a circle; `Seat.width` is its diameter, `height` is gone (a burned name, see below). Seats without a width
  use a fallback radius of 10 cm.
- `SeatingPlanLayout` divides a table into equal sections per seat pair and places the seat center radius + 5 cm beside
  the table edge. New seating plans start with 50 × 320 cm tables and 45 cm seats; "Reihe füllen…" makes its sizes the
  plan's defaults.
- Tables carry labels with the same five text fields as hall objects (`Labeled`, `TextSettings`); one computation
  (`PlanText`) draws both.
- Tables stay in creation order; the table number is only a label. "Nummern neu vergeben" renumbers by place.

**Why:** The only size of a chair that matters for the plan is how much room it takes. With equal sections the spacing
stays the same where two tables meet. A table number that decided the order made a table move when its number was
changed (or duplicated for a moment while editing).

**Consequences:** 1.1 places automatically positioned seats differently from 1.0.x. A hall therefore has to be saved
once in 1.1, and a 1.0.x workstation that saves it puts the seats back and drops the labels
([installation.md](installation.md#shared-folder-multi-workstation)). "Locally changed" in `StoreHallRepository`
compares with the round trip of the baseline, so fields written by other program versions never count as a change.

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

## Durable writes before rename (KARV-90)

**Decision:** Every temporary file that is later renamed (or hard-linked) into place is written with
`FileChannel.force(true)` first (`store.DurableWrite`). This covers version and claim files (local and shared),
`index/unclean`, healed files (`NioFileLayer.createNew`, `replaceDamaged`), the index files, the "connected" marker,
acknowledged conflicts and `machine.json` (`AtomicFileReplace.write`), the first `machine.json`, `sync-state.json` and
the settings file. The "cleanly closed" marker `index/clean` is written in place and forced as well.

**Why:** Without it, a power cut seconds after a save can leave a file under its final name with missing or zeroed
content: the rename is already in the file system journal while the data is still in the write cache. The startup
index cannot see this (same name; size and mtime may even match). A zeroed claim file makes the next sale on that unit
count as lost; a zeroed `machine.json` would give the workstation a new id; a zeroed settings file drops the shared
folder. A failing `force` fails the write like any other I/O error; there is no silent fallback.

**Cost:** One wait for the disk per file, about 2 ms on NVMe. A saved booking writes two files (version and claim).
Measured on 12.11.2026 (Ryzen 7 5800X, NVMe, median of 3 runs; shared folder on the local disk):

| Case | Before | After |
|---|---|---|
| Single mode: save one booking | 0.057 s | 0.044 s |
| Single mode: save an event with 3,000 changed bookings | 20.6 s | 32.8 s |
| Single mode: start with index | 5.5 s | 4.2 s |
| Shared mode: save one booking (background upload) | 0.050 s (2.1 s) | 0.051 s (2.0 s) |
| Shared mode: save 3,000 changed bookings (background upload) | 11.5 s (27.9 s) | 19.0 s (51.0 s) |

A sale is not affected. Large batches (restoring a big event, the first upload after connecting a full workstation)
take noticeably longer. This was accepted for 1.1.0: a zeroed claim after a power cut is worse than a rare
operation that takes longer. Speeding up large batches is a follow-up.

**Not covered:**
- Forcing the directory after the rename (only possible on Linux/macOS). Without it a rename may be lost, which leaves
  the older, readable state, not a damaged one.
- Making deletes and renames without new content durable (`rename`, setting aside the damaged file, deleting the
  marker): a lost delete or rename also leaves an older, readable state.
- The update files of KARV-132 (`update.SharedFileReplace`, `FolderDownloader`); they are rewritten or downloaded
  again.
- A real network drive (SMB) was not measured. There every `force` goes to the server and costs noticeably more.

## Forward-compatible data format

**Decision:** Versions 1.1 and later keep what they do not understand. Unknown fields survive load, edit, save, copy
and merge (`UnknownFields`, an any-setter/any-getter on every model class); unknown values of tolerant enums
(`Shape`, `TextAlignH`, `TextAlignV`) load with a fallback and keep the original on save; content that must not be
processed without understanding it (an unknown `paymentStatus`, a `requires` entry) locks the unit like a version of
a newer program (SYS-009). `StoreCodec.FORMAT` stays `1`; the envelope of the version files does not change.

**Why:** Workstations share one folder and are not updated at the same moment. An older version that silently drops
fields, or shows a paid booking as unpaid, damages what the newer version wrote. Only the older version can protect
the data, so the protection had to be in 1.1. The guard tests (`FormatGuardTest`, `JsonKeysTest`,
`FormatSamplesTest`, `ArchitectureGuardTest`) point to this section when they fail: read the rules before you extend
a list.

### Allowed without a new `StoreCodec.FORMAT`

- Add fields, if older versions may ignore them.
- Add values to tolerant enums, if the fallback in older versions is acceptable. A tolerant enum is display only: it
  loads with the fallback (`RECTANGLE`, `CENTER`), but the original value stays in the file until someone changes it
  on purpose: only setting a value different from the current (fallback) one drops the original; setting the
  fallback value itself (e.g. `RECTANGLE` for an unknown shape) keeps it.
- Add values to strict enums, or put a feature name into `requires`, if older versions must not touch a unit. They
  lock it then, with all consequences described below. Both are deliberate decisions.
- Remove a field, if older versions can live with a missing or outdated value. The new version keeps it as an
  unknown field but no longer maintains it; older versions see the default for objects of the new version and an
  outdated value for objects the new version edited. If it should disappear from the files, put it on the ignore
  list (the name is then burned, see below).
- Add a new kind of unit in a folder that older versions do not scan (`events/<id>/cashRegisters/<id>`, KARV-144),
  without changing an existing unit. Older versions skip the folder (`UnitKey.parse`, `VersionStore.eventUnits`), so
  the new units stay out of sight; to keep them from working on a stale picture of the event, a `requires` feature on
  the head (below) locks the event for them as soon as such a unit exists. Without that lock the new kind of unit
  would not be allowed.

### Rules for new fields

- A new field is absent or `null` while it has its default value (`FieldMerge` treats absent and `null` as equal,
  `false` and `""` are not).
- It needs no group-wise merging with existing fields, and it is no identifier that has to stay unique when copying.
- Occupancy or customer data never live only in new fields.
- **No derived fields** (e.g. `amountDue` from price and discount) and **no per-copy state** (e.g. "accounting
  finished" on a presentation). An older version changes the source field and carries the derived one along
  unchanged, i.e. outdated; and it copies per-copy state into new objects. A new meaning for money or accounting that
  older versions cannot represent (a discount they cannot apply) needs `requires`.
- **Strict only through `requires`.** A new field or enum that a later version treats strictly in its own gate rule
  is just an unknown field for older versions: they keep it and lock nothing.
- **Never remove enum values**, neither from strict nor from tolerant enums. The gate cannot tell "unknown because
  newer" from "unknown because removed": a removed payment status would lock every old booking with that value for
  good, with the wrong message (SYS-009); a removed tolerant value would show old data with the fallback. Mark a
  value as deprecated instead (not selectable in the UI, still readable). `FormatGuardTest` keeps a fixed list of
  values per stored enum and fails when one is missing.
- **A field that replaces an old one** (e.g. `priceCents` for `price`) is not enough as an extra field: an older
  version changes `price` and carries the new field back unchanged, so the newer version would read the old amount
  and lose the change. While older versions write the old field, **the old field wins**: the new version writes both,
  notices when they do not match on reading, and derives the new one from the old one. If that is impossible, change
  the format or use `requires` on the unit. `requires` may also sit on the head above the unit if the head decides
  whether an older version may touch the unit at all: an older version saves nothing below a head it considers too
  new, so the old field cannot be written there either (cash registers: the head of the event, the old fields on the
  presentation; see "Cash registers as units").
- **A format change is needed** to rename a field, change the type or meaning of a field or value, or restructure
  existing units. A new kind of unit beside the existing ones is no restructuring (see above).

### Burned names and `requires`

Removed fields are never reused. The lists are per class and live in the `@JsonIgnoreProperties` annotations (and in
`JsonKeysTest`):

| Class | Burned names |
|---|---|
| `Presentation` | `tables`, `defaultTableWidth`, `defaultTableHeight`, `defaultSeatWidth`, `defaultSeatHeight`, `hallWidth`, `hallHeight`, `tableRows`, `hallObjects` |
| `SeatingPlan` | `defaultSeatHeight` |
| `Seat` | `height`, `price`, `paymentStatus`, `collected`, `lastName`, `firstName`, `email`, `comment` |

`requires` is reserved. No model class has a property of that name, and the unit classes never write it from a freshly
built model. A version that uses it **derives it when writing**, removes it from the collected fields when reading,
**never keeps it from the model and never copies it into another unit**, not even on JSON level (resolver,
`FieldMerge`/`transfer`). Otherwise a unit would never unlock and copies would be locked for no reason. Inside the same
unit the payload stays as it is, so raw comparisons do not write a version because of a removed `"requires": []`.
`requires` works at the top of a unit only; inside a nested object (a hall object in a hall) it is only kept. To make
part of a hall strict, set `requires` on the hall.

**What the derivation looks at.** The first rule was to derive it from the state of the model and never to keep it.
That works if the model holds everything the feature stands for. It does not for `cashRegisters` (KARV-144): a
workstation right after the update knows the head but not yet the cash registers, and deriving from its model would
remove the lock. So the feature on the head follows the **units in the version store** (`HeadRequires`), and a stored
feature **stays until a save deletes the last cash register** of the event. The cost, accepted: a head can keep the
feature although every cash register is deleted (or there never was one), and the event then stays locked for 1.1.

The gate (`ContentGate`) calls a version too new if `requires` is present, not `null`, and not an array of texts the
program knows (`Requires.KNOWN`: `cashRegisters` since 1.2; absent, `null` and `[]` mean "nothing required"), or if a
booking has a `paymentStatus` that is not `null` and not the name of a `PaymentStatus` (a number counts as unknown).
`rulesId()` is computed from these inputs, so a new `PaymentStatus` value or a new known feature changes it, and the
startup index then resolves the units again. `EventHeadPolicy` keeps `requires` out of the group-wise comparison and
sets the feature in the result if either side carries it, without a conflict note. Comparisons for "changed locally"
ignore `requires` on the head, so a head never counts as changed by the user because of it.

### Boundary to 1.0.x

1.0.x drops unknown fields on save, so the protection works among workstations running 1.1 or later (checked
against tag `1.0.1`):

- A new value of a strict enum (`paymentStatus`) makes the booking unreadable on 1.0.x, but not locked (its chain is
  `OK`): the seat looks free and can be sold twice.
- `HallObject` has no `ignoreUnknown` in 1.0.x, and `shape`/alignment are strict there: a new field on a hall object
  or a new shape or alignment value makes the whole hall unreadable on 1.0.x, and its presentations freeze.
- 1.0.x does not know `requires` and drops it on save. Since 1.2 uses it (`cashRegisters`), no workstation at the same
  folder may run 1.0.x any more.
- A hall saved by 1.1 counts as changed on 1.0.x, and its next hall save there drops the keys only 1.1 knows (text
  and alignment on tables).
- If a downgrade keeps the index folder of 1.1, 1.0.1 warns "Unbekanntes Format des Index: 5" and rebuilds the index
  (`INDEX_FORMAT` is 5 since 1.1).

So new values of strict enums, `requires`, and new fields or values on hall objects may only be used once no
workstation runs 1.0.x. The release notes of the version that introduces them say so.

### Permanent locks

If no newer workstation exists any more (single use, or after a downgrade from 1.2), a locked unit stays locked until
someone installs a newer version again. SYS-009 then says "newer program" even if the cause is a hand-edited file.
Meanwhile the unit is fixed in place: the presentation or event above it can be neither deleted nor switched to
another hall or plan.

Consequences to keep in mind:

- A booking without a seat does not find its seat by itself after the unlock.
- An own change that waits for a lock is invisible until then; do not enter the customer again.
- Resetting a unit that became damaged after a head that was too new in content leads from SYS-008 to SYS-009: the
  last readable state is the one the newer version wrote.

### Release steps

For every release, create the sample for the new version from the final release state, not during development
(otherwise the sample does not match the shipped format):

1. Run `FormatSampleGenerator` (`src/test/java/.../store/`) with `main(<target folder>)`. It writes
   `<target folder>/data.zip`.
2. Put `data.zip` plus a `README.md` (how it was created, contents) into
   `src/test/resources/format-samples/<version>/` and commit. An archive keeps the paths short; the plain files
   exceed the Windows path limit.
3. `FormatSamplesTest` picks up every folder automatically: every version must load all samples, nothing counts as
   changed, and saving without a change writes nothing. Samples are immutable; the tests copy them first.

The generator also writes the cash registers of 1.2: a presentation whose counted amount sits only in the old fields,
one cash register of each kind, and the head with `requires`. The sample for 1.2 is created at release time, as above.

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

The native libraries inside the fat JAR are unpacked by Conveyor at build time, see
[JavaFX native libraries extracted at build time](#javafx-native-libraries-extracted-at-build-time).

## JavaFX native libraries extracted at build time

**Decision:** `conveyor.conf` sets `app.jvm.extract-native-libraries = true`. The fat JAR stays as it is.

- At build time Conveyor takes the native libraries out of the JAR and puts them, flat, into the library folder of
  the bundled Java runtime: `bin\` on Windows, `lib/runtime/lib` on Linux.
- The shade plugin leaves the Microsoft runtime files of `javafx-graphics` out of the Windows JAR (`msvcp140*.dll`,
  `vcruntime140*.dll`, `ucrtbase.dll`, `api-ms-win-*.dll`). The Windows JAR keeps the 7 JavaFX DLLs.

**Why:**

- **The old behaviour:** Without the switch, JavaFX unpacked its DLLs on every start into
  `%USERPROFILE%\.openjfx\cache\21.0.2+5\amd64`, a cache shared by every JavaFX 21.0.2 program of the user.
- **The incident in KARV-130:** Norton's behaviour-based protection quarantined two test launchers.
  - While cleaning up, it deleted `glass.dll`, `prism_d3d.dll`, `javafx_font.dll` and `prism_sw.dll` from that cache.
  - It blocked their paths until Windows restarted (`AccessDeniedException`).
  - Until then no JavaFX program started on the machine, the real Kartenverwaltung included.
  - It is not known whether the club laptops run Norton.
- **A second mechanism is suspected, not proven:**
  - JavaFX compares the cached file with the resource by MD5 and deletes and rewrites it when they differ.
  - The installed app ships DLLs signed by Conveyor, while `javafx:run` and the tests use unsigned ones, both in
    the same cache folder.
  - Switching between the two rewrites the files. If another process has the DLL loaded, the delete fails, and so
    does the start.

**How JavaFX finds the libraries:** `NativeLibLoader` (JavaFX 21.0.2) tries, in this order:

1. next to the JAR: `<jar dir>\..\bin` on Windows, `<jar dir>` on Linux
2. the resource in the JAR, unpacked into the cache
3. `java.library.path`
4. `System.loadLibrary`

On Windows step 1 now finds them in `<install>\bin`. On Linux step 3 finds them, through the
`java.library.path` the Conveyor launcher sets (`lib/app/../runtime/lib/`). Both depend on undocumented behaviour
of JavaFX and Conveyor. After every update of either, check with `-Djavafx.verbose=true` where the libraries are
loaded from.

**Linux package dependencies:**

- With the `.so` files loose in the package, Conveyor derives the Debian `Depends` from them too: GTK3, GLib, ATK,
  Cairo, Pango, GL and friends.
- It resolves the package names against Ubuntu 24.04, where several carry a `t64` suffix (`libgtk-3-0t64`,
  `libglib2.0-0t64`, `libatk1.0-0t64`, `libasound2t64`). These names do not exist on Ubuntu 22.04 and Debian 12.
- `conveyor.conf` removes these entries and adds alternatives with the old names, so the `.deb` installs on all
  three.
- `libasound2t64` was already in 1.0.x, so Debian 12 could not install the package before either.
- This was measured with `apt-get install --simulate`.

**Why the Microsoft runtime files are filtered:**

- JavaFX 21.0.2 ships `msvcp140*`/`vcruntime140*` 14.36, the bundled JDK (Corretto 21) ships 14.44.
- Conveyor copies extracted libraries into `bin\`, replacing existing files without a warning (the "conflicting
  native libraries" check only compares JARs with each other). Unfiltered, the whole JVM and the launcher would
  silently run on the older runtime.
- With the filter, the runtime in the package is unchanged from before: the same JDK files.
- The three files the JDK does not ship (`msvcp140_1`, `msvcp140_2`, `api-ms-win-core-console-l1-2-0`) are no longer
  preloaded by JavaFX. No JavaFX DLL imports them, and JavaFX ignores the failure. With `javafx.verbose` this shows
  as "failed to load …" lines, which are not errors.

**Rejected alternatives:**

- **The app as a JPMS module:** jlink rejects modules that depend on an automatic module, and the app depends on
  OpenPDF 1.3.43.
- **JavaFX as jmods in the runtime:** a larger change with no gain for the goal.
- **A private cache via `javafx.cachedir`:** the app would still unpack DLLs at run time.
- **Individual JARs from Maven instead of the fat JAR:** JavaFX and ControlsFX would be jlinked as named modules,
  and the app JAR would depend on an automatic module.

**Not affected:** Development and tests (`javafx:run`, Maven tests) still load JavaFX from the Maven JARs and use the
cache in the user profile. An old `.openjfx` folder on a club laptop is harmless and no longer used; it may be
deleted.

**What this does not achieve:**

- Norton can still quarantine a file inside the package.
- The cache used to heal itself: a missing file was unpacked again on the next start. That fallback is gone. If
  `bin\glass.dll` is missing, the app does not start until the file is restored.
- Other programs are no longer affected.

**Recovery if a release does not start on a workstation:**

1. **Restore the file from the Norton quarantine.**
2. **Withdraw the broken release** if it is the latest one: mark it as a pre-release on GitHub. "Latest" then points
   to the previous release again, and no further workstation updates to it.
3. **Install a fixed version over the broken one:**
   - Set `app.jvm.extract-native-libraries = false` in `conveyor.conf`. With the filtered JAR, this was verified to
     start in the KARV-137 spike; the filter can stay.
   - Build it with a higher version than the broken one; MSIX accepts only a higher version as an update.
   - On the workstation run `Add-AppxPackage -Path <msix> -ForceApplicationShutdown`, from a USB stick or the shared
     folder. Data and update source are kept.
4. **Never run `Remove-AppxPackage` on a workstation** without first backing up
   `%LOCALAPPDATA%\Packages\EcvKarten_0cs1gtds5d9x0\LocalCache\Local\EltvillerCarnevalVerein\Kartenverwaltung`. It
   is deleted with the package.
5. **Downgrade only in an emergency** (`Add-AppxPackage -ForceUpdateFromAnyVersion`):
   - 1.0.x drops the table labels of 1.1.
   - 1.0.x checks for updates in the background and updates itself back to the latest release, so withdraw the
     broken release first (step 2).
6. **Pilot:**
   - Do not release shortly before a sale.
   - Workstations on 1.0.x update themselves from the latest release. Keep the other workstations switched off until
     the pilot is done, or withdraw the release at once if it fails (step 2).
   - On the pilot workstation, after the update, open the app and check the loaded modules:
     `(Get-Process -Name "Kartenverwaltung - Eltviller Carneval Verein").Modules`. Every `glass.dll`, `prism_*.dll`
     and `javafx_*.dll` must come from `(Get-AppxPackage EcvKarten).InstallLocation\bin`, and none from `.openjfx` or
     `Temp`.

## Conveyor for installers, GitHub for hosting

**Decision:** Hydraulic Conveyor builds signed Windows MSIX and Linux packages; releases go to GitHub Releases and
the download page to the `gh-pages` branch (`make copied-site`).

**Why:** Updates and installers without maintaining a server, and 40–90 MB binaries never enter the history of
`main`. When updates are applied is decided by the app, see "Updates only with the user's consent" below.

**Notes from `conveyor.conf`:** the "update escape hatch" (`updatecheck.exe`) is disabled because Norton quarantines
it; required JDK modules (`java.scripting`, `java.xml`, `jdk.unsupported`, …) are listed explicitly because jdeps
does not detect the reflective use by `FXMLLoader`; `extract-native-libraries` is on, see
[JavaFX native libraries extracted at build time](#javafx-native-libraries-extracted-at-build-time).

## No public code-signing certificate (for now)

**Decision:** The Windows packages stay signed with Conveyor's self-signed certificate
(`CN=Kartenverwaltung - Eltviller Carneval Verein`). No public code-signing certificate is bought for now (KARV-137,
part 2, November 2026).

**Why:**

- **Norton:** The detections seen in KARV-130 ("IDP.Generic", behaviour-based protection) are a behaviour heuristic.
  They demonstrably also hit software signed with public certificates. A real signature would likely raise the
  reputation, but would not have prevented what we saw. Avoiding suspicious behaviour helps more: no executables
  started from `%TEMP%`, no native libraries unpacked at run time (KARV-137).
- **What a public certificate would bring:**
  - installation without an administrator importing the certificate
  - a verified publisher name in Windows instead of "unknown"
  - acceptance by Smart App Control on Windows 11
  - SmartScreen still warns for the first weeks; EV certificates no longer skip that either.
- **Options and approximate prices (2026):**
  - Azure Trusted Signing: about 120 $/year and supported by Conveyor, but only for organisations in the EU
    (individuals only in the US and Canada). This would be a certificate for the club.
  - A certificate in a private person's name (SSL.com or Sectigo with a cloud key): about 230–310 $/year. The name
    and town would be public in every signed file.
  - Certum's open-source certificate (49 €): only for free software, and not supported by Conveyor on Linux CI.
  - SignPath Foundation: free, but the publisher would be "SignPath Foundation", and Conveyor has no integration.
- **The switch has a one-off cost on every workstation:**
  - A new certificate changes the MSIX publisher and therefore the package family (`EcvKarten_0cs1gtds5d9x0`).
    Installed versions then get no further updates.
  - Conveyor's automatic path for this (the update escape hatch) is disabled, see above.
  - Every workstation would have to install the new package. Its local data lives in the old package's virtualized
    `LocalCache` and would have to be imported into the new one. The old `LocalCache` is readable from the new
    package (tested), so an import at first start is feasible.

**Instead:**

- If Norton flags a release, report it at `https://submissions.norton.com/` (detection name, alert ID, file).
  Norton promises updated definitions within 48 hours.
- Optionally submit each Windows release to Microsoft as a software developer:
  `https://www.microsoft.com/wdsi/filesubmission`.

**Revisit when:**

- Norton or Smart App Control blocks the app on a club workstation again.
- The club wants a verified publisher.

The preferred route is then Azure Trusted Signing for the club (check the identity validation requirements first).
Plan the switch together with a release that touches every workstation anyway, and include the data import. Once
switched, keep the certificate subject unchanged across renewals.

## Updates only with the user's consent (`app.updates = manual`)

**Decision:** Windows no longer checks for updates in the background. An update runs only when a user agrees to it
in the app (KARV-131) or when it is installed from the shared folder
([KARV-132](#update-over-the-shared-folder-karv-132)).

**Why:**
- Workstations sharing a folder must run the same version (see [installation.md](installation.md)). An update must
  never arrive unnoticed in the middle of a sale.
- The default mode (`background`, `<AutomaticBackgroundTask />` in the `.appinstaller`) did not deliver in practice.
  A workstation linked to the GitHub update source was still on 0.9.5 two days after 1.0.0 was released.
- `aggressive` (check on every start) is not an option: it is not supported on Linux, and its start-up check gives
  no say in when an update happens.

**Verified in a test build (KARV-130, 06.11.2026, Windows 11 with Norton):**
- In `manual` mode the `.appinstaller` contains no `UpdateSettings`. The package still keeps its link to the update
  source.
- The packaged app gets `app.version`, `app.revision` and `app.repositoryUrl` as system properties.
- With `dev.hydraulic.conveyor:conveyor-control`, `getInstance()` returns Conveyor's implementation.
  - `canTriggerUpdateCheckUI()` returns `AVAILABLE`.
  - `getCurrentVersionFromRepository()` reads the newest version. Without a connection it throws
    `UpdateCheckException`.
  - `triggerUpdateCheckUI()` closes the app and installs the update. It does **not** restart the app, and the
    helper (`updater.exe`) stays behind as a hidden process.
- `Add-AppxPackage -Path <newer .msix> -ForceApplicationShutdown` updates a running app from a local folder,
  with no update source and no internet. A helper then restarts the app. The data and the link to the update
  source are kept.
- Norton's behaviour-based protection ("IDP.Generic") quarantined the launchers
  (`bin\Kartenverwaltung UPDATE-TEST.exe`) of two of the three test versions:

  | Version | What the app did | Norton |
  |---|---|---|
  | 1.1.0.1 | `triggerUpdateCheckUI()`, which starts `updater.exe` (a copy of `updatecheck.exe`) from `%TEMP%` | quarantined |
  | 1.1.0.2 | updated with `Add-AppxPackage` by a hidden PowerShell helper and started by it; HTTP to localhost only | not flagged |
  | 1.1.0.3 | HTTPS to GitHub through the Windows trust store (`jdk.crypto.mscapi`, `Windows-ROOT`) on its first start | quarantined |

  - Norton reports the launchers as unknown ("file version unknown", "no community data"). The self-signed
    certificate gives them no reputation, so any unusual behaviour is enough to trigger the behaviour-based
    protection.
  - Norton also deleted the JavaFX native libraries in the shared cache; see
    [JavaFX native libraries extracted at build time](#javafx-native-libraries-extracted-at-build-time).
  - Conveyor ships `updatecheck.exe` in the package even with `use-update-escape-hatch = false`.
- Norton's Web/Mail Shield intercepts TLS. Java's own trust store rejects its root certificate (PKIX), as Conveyor,
  Maven and a plain JDK test showed. The Windows trust store accepts it, but that requires `jdk.crypto.mscapi`,
  which the bundled runtime does not contain (it also lacks `java.net.http`). In the packaged app this path is
  what triggered Norton (1.1.0.3).

- Every Norton test can damage the machine it runs on. Since KARV-137 the installed app no longer uses the shared
  JavaFX cache, but development and tests (`javafx:run`, Maven tests) still do: after a Norton hit they may not
  start until Windows has been restarted.

**Consequence:** `manual` must not ship without KARV-131. Otherwise there is no way to update apart from a new
install.

## Update check and update from the app (KARV-131)

**Decision:**
- **Check (W3):** the app reads `metadata.properties` of the update source (`app.repositoryUrl` with a trailing `/`)
  with `HttpURLConnection` and Java's default trust store: 10 s after start, then 4 h after the end of the previous
  check, and on "Jetzt prüfen". No helper process, no Windows certificate store, never a pop-up.
- **Update (W2):** on a click and after a confirmation, the app saves or asks about unsaved changes (like closing the
  window), checks again, downloads the `.appinstaller` file freshly into `java.io.tmpdir` (the package's own
  `LocalCache\Local\Temp`, passed explicitly) and opens it with `Desktop.open`; then it exits. The App Installer
  updates and restarts the app.
- **Fallback:** "Update im Browser laden" opens the `.appinstaller` address with `Desktop.browse` and exits too,
  because the App Installer would otherwise close the running app without asking.
- **Package identity:** the button only appears inside an MSIX package (`PACKAGE_LOCALAPPDATA` points into
  `\Packages\<family>`) whose family (from `app.windows.userModelID`) matches `windows.package-family-name` of the
  source. Otherwise "Neuinstallation nötig" (e.g. after a certificate change) or, on Linux and the Windows ZIP, a
  hint only.

**Why:**
- Probe KARV-131 (07.11.2026, Windows 11 with Norton, no Norton alert):
  - **W1**, a PowerShell helper (`CheckUpdateAvailabilityAsync`, `Add-AppxPackage -ForceApplicationShutdown`): works,
    but takes about 90 s while the old app keeps running; it starts a program and the script is fragile. Rejected.
  - **W2**, `.appinstaller` via the browser: fast and clean; the link to the update source is kept.
  - **W3**, Java HTTPS from the **installed** app to GitHub: HTTP 200. Norton does not intercept the packaged app on
    this machine; the plain JDK `java.exe` still gets PKIX. HTTPS with the default trust store is therefore used for
    the check only, and a failing check is shown, not hidden.
  - **W4**, `Desktop.open` of a self-downloaded file from inside the package: works. The file lands in the package's
    `LocalCache\Local\Temp` (`java.io.tmpdir`, already present), the App Installer opens directly without the
    "How do you want to open this file?" prompt, shows the new version, updates and, with "Starten wenn bereit",
    starts it. `PACKAGE_LOCALAPPDATA` is `…\Packages\<family>\LocalCache\Local`.
- The `.appinstaller` of a release points to a versioned MSIX under `latest`; an old file returns 404 once a newer
  release is "latest". The file is therefore loaded only on the click.
- `Desktop.browse` starts the browser as a child process of the package: it runs as its own instance with its own
  profile, and its data lands in the package folder (seen with Opera in W2). Acceptable for a fallback.
- **Known gap:** an antivirus that only cuts the connection (instead of presenting its own certificate) looks like
  "no network" and stays silent; a workstation without internet is normal at a box office.

## Patch notes (KARV-125)

**Decision:**
- One Markdown file in the app, `patchnotes.md`, is the only source. The app shows it offline; the GitHub release
  text is generated from it by the release workflow, before Conveyor publishes.
- After an update the first start shows "Was ist neu" once (not after a fresh installation); *Info und Updates*
  shows it at any time. Before an update the app only links to the GitHub release page; it never loads the text.
- Fixed sections "Neu", "Verbessert", "Behoben", "Hinter den Kulissen" in plain language for the box office. Claude
  drafts the final text from the tickets of the version shortly before the pull request from the release branch to
  `main`; the maintainer approves it. Until then the section holds a provisional text, because the guard test requires
  a valid section for the version on every build.
- The file format is strict and checked by a test against `pom.xml` and `conveyor.conf`: placeholders, empty
  sections, formatting and anything GitHub would turn into a mention, a reference or an emoji (`@name`, `#12`,
  `:word:`) are errors, so no release can go out with unfinished or misleading notes.

**Why:**
- A file inside the app works without internet and explains each installed version by itself. Rejected: a JSON file
  (harder to write and review) and the GitHub release as the source (needs internet, against "only a link").
- Generating the release text before Conveyor means a broken file stops the release before installers are public.

## Update over the shared folder (KARV-132)

**Decision:**
- **Version alignment:** every workstation with a version writes `<shared folder>/updates/kassen/<machine id>.json`
  (version, revision, `fsname`, name, platform, last start). A newer entry with the same `fsname` gives the footer
  warning "„Kasse Y“ nutzt … – bitte aktualisieren". Entries do not expire: a retired newer workstation still left data
  in the newer format.
- **Installer in the folder:** a Windows package workstation whose last successful check knows a version V ≥ its own
  (also right after it updated itself) downloads the `.msix` of V into `updates/` in the background, unless a valid
  installer ≥ V is there. Coordination: claim file `laden-<a.b.c.d>-<machine id>.json`, a random wait of 2 to 5
  minutes, then the smallest machine id wins; a fresh "done" claim (3 h) stops everyone else. The download goes to a
  `~karv-….tmp` file (Nextcloud does not upload those), is checked (length, manifest, ZIP with CRC32) and placed with
  `Files.move` without options (only under a free name), followed by a byte-identical description `<name>.msix.json`
  (size, SHA-256, package family, package version).
- **Update from the folder:** on every Windows package workstation, the newest valid installer of its own package family
  above its version is offered (footer, *Info und Updates*, once at start). The app checks the file again, opens it with
  `Desktop.open` and exits; the App Installer updates and restarts the app. A candidate newer than the update source
  after a younger check is treated as withdrawn and not offered.
- **Placement:** `updates/` sits next to `data/`. The version store only works below `data/` (the shared folder itself
  is only its anchor and is never listed), so `updates/` causes no sync, conflict or SYS error (test
  `updatesFolderBesideDataIsIgnoredByTheStore`). `updates/` and `kassen/` are created only if the shared folder exists.
- **First replaced file in the shared folder:** a workstation's own entry is replaced in place (temporary file, atomic
  move). Everywhere else in the shared folder files are only created; this is safe because exactly one workstation
  writes each entry, so the sync client cannot produce a conflict.

**Why / verified (probe W5, 10.11.2026, Windows 11 with Norton, no Norton alert):**
- `Desktop.open` on an `.msix` in a Nextcloud-synced folder from inside the package: the App Installer opened
  directly (no "How do you want to open this file?"), offered "aktualisieren?" with the new version and the folder as
  source, updated and restarted the app with "Starten wenn bereit". The package kept its update source
  (`AppInstallerUri` unchanged), and the normal update from the app (KARV-131) worked afterwards; the App Installer then
  downloaded only changed blocks.
- A rejected or cancelled installation does **not** restart the app; the user starts it again by hand.
- `java.util.zip.ZipFile` does not verify CRC32 itself; the app recomputes it for every entry.

**Consequences:**
- Reading entries and updating from the folder only need the folder to be present locally; a Nextcloud workstation
  without internet (offline for the version store) can still update. Only the download needs the cloud server.
- If the winning workstation shuts down before Nextcloud uploaded the `.msix`, the others wait up to 3 hours. The
  winner's own "done" claim survives a restart for those 3 hours, too.
- After a withdrawal (another workstation downloads, folder not reachable, source changed during the wait) the next
  attempt waits for the next regular tick (10 minutes) or a newer update check; otherwise tick and download thread
  would trigger each other without pause. A changed source also triggers a new update check.
- A withdrawn version in the folder blocks the older source version: no workstation places it next to it (otherwise
  candidate choice, download and "delete older" cleanup would have to change together and the cleanup would delete it
  again). The withdrawn file is deleted by hand ([usage.md](usage.md#updates)). Likewise only the current GitHub version
  should be placed by hand.
- Files in `updates/` may be planted by anyone with write access: names follow strict patterns, `.json` files are at
  most 64 KB, at most 200 entries are read, `.msix` at most 200 MB, the manifest at most 1 MB, XML without DOCTYPE.
  Protection against a foreign package rests on Windows' signature check and the private signing key.
- Workstations on 1.0.x do not write entries; the release-notes hint for the boundary to 1.0.x stays.

## Accessible seat display (KARV-26)

**Decision:** With "Barrierefreie Sitzdarstellung" (menu "Anzeige", on by default) the seat status no longer depends on
color alone: free is white with a green ring, reserved yellow with dark stripes, sold solid red, blocked gray with a
white cross. A legend below the plan explains it. The customer display only distinguishes free (ring) and taken (red).
Switched off, the plain colors of earlier versions apply. The choice is stored as `display.seatPatterns`.

**Why:** Red and green are the hardest pair for the most common color vision deficiency, and free versus sold is the
distinction that matters at the box office. The look has to survive grayscale and simulated red-green deficiency;
`SeatLookTest` checks exactly that.

**How:** Ring and stripes are gradients with hard stops (`SeatLook`), i.e. plain fills: they stay sharp when zooming
and need no extra node per seat. Only the cross is a separate node, laid between seat and number, and it lets clicks
through to the seat. The cross is white because a dark one swallowed the black seat number.

## One "Anzeige" choice for all plan views, restored at start (KARV-76)

**Decision:** Sales view, customer display and hall editor share one app-wide `DisplayPreferences` (rows, tables, seat
numbers, hall objects, seat patterns, selection hint at the pointer (KARV-194) and its placement above or below, with or without avoiding edges (KARV-197), purchase summary placement, and since KARV-193 the grouping of the ticket table with its group order and the option "Gruppen verschachteln"; the last two apply to the ticket table only, not to the plan views). It is installed in `MainApp` at start and written to
`settings.json` (`display`) on every change as a partial save. With "Anzeige beim Start wiederherstellen" (on by
default) the next start uses the stored choice; off, every start shows everything with the placement "Automatisch".

**Why:** Hiding rows in the editor but still seeing them in the sales view (or the other way round) was confusing, and
operators had to set up the menu again after every start. Installing at start makes "beim Start" mean the state at
program start, not the state when sales or editor are opened for the first time.

**Details:**
- Views follow the preferences through weak listeners (`PlanLabelOptions.follow`), so closed editors and shells are
  not kept alive by the app-wide instance.
- The placement is stored as text (`settings` does not know the UI) and read through `TolerantEnum`: an unknown value
  from a newer version means "Automatisch" and is written back unchanged until someone picks another one.
- A failing write is logged as a warning and never reaches the global error dialog: losing a menu tick is not worth
  interrupting a sale.

## Ticket table as tree table (KARV-193)

**Decision:** The ticket table is a `TreeTableView<TicketRow>` and can be grouped by one or more columns, as levels in
the order they were ticked. A group header shows column, value and number of bookings. The grouping and the group
order (by value or by size) are kept in `settings.json` as part of the "Anzeige" choices (KARV-76). Column filters stay,
rebuilt by hand (`ColumnFilterMenu`). The selection is a set of bookings, kept in the controller. The other tables are not grouped;
the event editor keeps ControlsFX `TableFilter`.

**Why:** Groups are a hierarchy, and `TreeTableView` is the component for that. Two cheaper ways were rejected:
group headers as rows of a flat `TableView` would make every column, cell, selection, sort and the ControlsFX filter
know header rows, and would be built against the internal lists of `TableFilter`; only sorting with a count column
gives no collapsing and no free levels. Because of its size and the risk at the most used table of the box office
the ticket went from 1.1.1 to 1.2.

**Details:**
- **Own column filter instead of ControlsFX:** ControlsFX 11.2.1 offers a filter only for `TableView`. The rebuilt
  filter keeps the operation (right-click on the heading, search, values with ticks, "Anwenden"). `TicketFilter` stores
  the deselected keys per column, so that a value that appears through another workstation's change is visible. The
  value list merges texts by the group key (trimmed, case-insensitive), so that filter and groups agree.
  ControlsFX stays a dependency for `EventEditController` and `CheckComboBox`.
- **Group order separate from column sorting:** A click on a heading sorts only the bookings within the lowest group
  (`sortPolicy` of the table is its own); the order of the groups is only `GroupOrder.VALUE` or `SIZE`. Sorting the
  groups by their empty cells, as the default sort policy would, makes no sense. The sort policy always returns `true`
  and starts the rebuild later, because it runs inside an atomic selection change of JavaFX.
- **Selection as a set of bookings:** JavaFX cannot select rows inside closed groups and drops the selection when a
  group closes, depending on the main selection. So the controller keeps the bookings by identity
  (`TicketSelection`), the selection model shows only the visible booking rows, and a group row is never selected
  there but shown as selected when all its bookings are. A selected closed group therefore counts as all its bookings
  for selling, cancelling, customer display and hall view; a closed group is never only partly selected (it opens
  instead). Changes JavaFX makes while expanding, collapsing and rebuilding do not change the set.
- **Rebuild only after editing:** Every rebuild (own change, foreign change, search, filter, grouping, sort) waits
  until no cell is edited, so no row moves under the typing hand (`TicketEdits`, as `refreshAfterForeignChange` did
  before). A booking the operator changed moves to its new place and stays visible despite search and filter until
  these change or another presentation is shown. Before, a changed booking stayed where it was, because the lists of
  ControlsFX do not re-sort without a list change; with groups, headers and counts would be wrong.
- **No rebuild during a mouse operation (hand test, 20.11.2026):** The first press of a double click commits the edit
  and asks for a rebuild; if it ran before the second click, another booking or a group header could sit under the
  pointer (the header then collapsed on the double click). So a rebuild also waits for the end of a mouse operation
  (`MouseGesture`): from the press in a row until 500 ms after the release, the usual Windows double-click time as a
  fixed value. The same wait holds back removing cancelled bookings without a seat. Cancelling, the storno from
  `NameRemovalDialog`, "Platz zuweisen…" and `loadBookings` still rebuild at once. Because the system double-click
  time can be longer, `TicketTreeTable` also consumes a press or release with click count 2 or more on another row
  (booking or group path) than the first click (a release after a drag is let through). It sits on the parent node of the table, because filters on the
  table itself kept running after a consume. The listeners and scene filters of the controller exist only while the
  table is shown, so a controller left behind reports no selection to `SalesViewState`.
- **Grouping order as chips, "Gruppen verschachteln", expand/collapse all (hand test, 20.11.2026):** The order of
  levels used to follow the order of ticking, so changing it meant unticking and ticking again; the chips of
  `GroupingBar` can be dragged (ordinary mouse events, not system drag and drop, so tests need no toolkit gestures).
  With "Gruppen verschachteln" off, several columns form one level with one group per combination (path of length 1,
  so closing, selection rules and structure comparison are unchanged); with one column the setting is ignored, so
  switching it does not forget closed groups. "Alle zuklappen" closes inner groups too and keeps the selection of
  fully selected top-level groups as one step.
- **Input helpers once for both table kinds:** `TableInput` works against `TableAccess`
  (`TableViewAccess`, `TreeTableViewAccess`), the parsing text cells share `ParsingEditing`. Group rows are never
  editable, and Tab skips them.
- **Local only:** The grouping is not part of the shared folder. An older version (1.1.x) ignores the new fields and
  loses them when it writes `settings.json`; that is acceptable, only the remembered grouping is lost.

## Free seats in the sales view (KARV-215)

**Decision:** Above the "Vorstellung" box the sales view shows "frei: n von m". n counts the seats that can be sold
now, m the free and taken seats. A locked seat (`LockedSeats.isLocked`) and a booking without a place count in
neither; a booking with leftovers but no last name is free, as in the hall view. With "Alle Vorstellungen" both are
sums, and the tooltip lists every presentation.

**Why:** The box office has to tell a customer at once how many tickets are left. "n Treffer" cannot answer that: it
counts what the view shows, taken seats included, and changes with the search.

**How:** The shell counts over the bookings in the model (`FreeSeats`), not over what a view shows, so search, column
filters and the choice between table and hall view do not change the number. After the reconciliation with the seating
plan every seat has exactly one booking, which makes the bookings the seats.

- m is deliberately smaller than "gesamt" in the event overview and the statistics (`BookingTotals`), which include
  bookings without a place and locked seats.
- The number is recalculated on a change of event or presentation, on own changes (reported by the `ChangeTracker`),
  on a save of the shown event, on foreign changes to events and halls, and when locks change. The save is a trigger
  of its own because a change saved before the `ChangeTrigger` has compared never reaches the tracker's listeners
  (Ctrl+S out of an open table cell).
- No field of the header changes its width. With long numbers the caption "Vorstellung" is shortened, never the number.

## Moving bookings keeps the booking id (KARV-140)

**Decision:** When the box office moves bookings to other seats of the same presentation, each booking keeps its id and
all its data; only `seatId` and the seat snapshot change (`BookingMove`, no JavaFX). A move is a plan first (`plan`),
which the user confirms, and then runs only if recomputing it gives the same plan (`apply`). One operation is one
save. No price changes, and nothing outside one presentation.

**Why:** The store tells a move from a sale by the booking id. Copying the data to a new booking would look like a
cancellation plus a new sale, and the conflict rules would no longer follow the booking: a swap against a change by
another workstation could lose one of the two. With the id kept, the rules that already handle the move out of the
conflict dialog apply. Prices stay as they were because the booking is not sold again.

**Detail:**
- **Pairing:** Sources and targets are sorted by the same order of the seating plan (row, table, seat, then seat id; a
  source counts with its old seat), and the i-th source gets the i-th target. A seat may be source and target at once.
- **Chains and counter moves:** The moves form chains from a seat that nobody takes over (start) to a target that is
  not the seat of a source (end). If the end holds a booking, it moves to the start (counter move; with one source this
  is a swap). Otherwise the booking at the end is removed with its leftovers, as in `BookingSync.assign`, and the start
  gets an empty placeholder. Both sides are paired by the same order, so a chain never closes into a ring.
- **Blocked seats:** `LockedSeats.lockedUnitSeatIds` names every seat whose unit is on the list of locked entries, also
  when the model still shows an older booking there. A plan that touches such a seat is refused, otherwise the store
  would reject the whole save and the moved state would stay unsaved.
- **Stale views:** The view keeps id and seat of each source as chosen. If the plan, the seating plan or the view
  (event, presentation) changed during the confirmation, nothing is moved and the footer says so.
- **Collected tick:** The confirmation asks whether "Abgeholt" stays; removing it also clears the collection date.

## Cash registers as units (KARV-144)

**Decision:** A cash register is a unit of its own below the event, `events/<eventId>/cashRegisters/<id>`
(`UnitType.CASH_REGISTER`, `model/CashRegister`, the list `Event.cashRegisters`). Three kinds, told apart by the id and
not stored: the register of a presentation (id = id of the presentation), the shared register (`_event`) and free
registers (`_` + UUID). The default is one register per presentation. A register holds name, "applies to", the counted
amount in cents (`actualCashAmount`) and the comment on the difference. All rules live in `model/CashRegisters`
(static, no JavaFX).

**Why:** The counted amount used to be two fields on the presentation, so the club was tied to exactly one register per
presentation, and the further work of 1.2 (payment carries the register, cash count per register, accounting close) had
nothing to hang on. A list in the head of the event would be merged per field: two workstations at different registers
would conflict over the whole list. A unit below the event is merged by the existing field groups (`name`, `scope`,
`cash`), and one workstation never touches another one's register.

**How:**
- **The register of a presentation is only imagined until it has data.** Nothing is created on load, so loading an
  event from 1.1 writes nothing and the event does not count as changed. The effective state of a presentation's
  register is the register object if the event has one, else the old fields of the presentation
  (`CashRegisters.effective`); the same numbers show before and after the update. Changing amount, comment or name first
  fetches the register for editing (`forEdit`): it creates the object from the old fields and clears them. No unit is
  *created* for a presentation register without data of its own; once saved, a unit is rewritten like any other, so an
  amount set back to 0 is a field change, not a deletion. A unit is deleted only when its object leaves the list: a free
  or the shared register by hand, a presentation register by the rule below.
- **A presentation that goes away** (by hand or by a restore) leaves its register behind as an orphan if the effective
  state has an amount or a comment: the register is fetched for editing and named after its default name. A register
  without amount and comment is removed.
- **A second amount.** If the register object exists but the old fields still hold an amount or comment (a 1.1
  workstation wrote after the first save, or a save broke off), the register wins. `KAS-005` and the cash
  reconciliation name the second amount until someone changes amount or comment or discards it; only that clears the old
  fields.
- **Lock for 1.1 through `requires` on the head**, feature `cashRegisters` (`Requires.KNOWN`, see "Forward-compatible
  data format"). `HeadRequires` derives it from the version store: it is set when a save creates or changes a register
  unit, or the store already knows a living one; it is removed only by a save that itself deletes a register unit and
  leaves none alive. A save writes the head along, even if nothing else changed on it. Order within one save
  (`HeadRequires`): created or changed register units stand before every presentation and before the deletions that
  revive a head; a living head that keeps or gains the feature stands before the registers, a head that loses it after
  the deletions of the registers. A break-off then leaves at most a head with the feature and no register, a second
  amount in the old fields, or a head that keeps the feature; 1.1 never opens an event with emptied old fields. Why not stay open for 1.1: payments without a register would fall into the register of
  the presentation, there is no rule "old field wins" for a shared register, and 1.1 would show stale amounts.
- **"Registers not yet reconciled".** On a workstation right after the update the head carries the feature but the
  registers have not arrived (1.1 skipped their list entries). If a shared folder is connected, no full sync has
  finished since the start (`SyncService.fullSyncCompletedSinceStart`, `SyncListener.fullSyncCompleted`) and the store
  knows no register unit of the event, the registers of that event cannot be edited, nor can a presentation be deleted or
  the event restored, and the overall balance shows "–". The state ends with the first register unit or the first
  finished full sync; there is none in single mode.
- **Conflicts** (`CashRegisterPolicy`): "equal" and "unreadable" as for the presentation, then "delete wins" with one
  exception: if the other side counted (group `cash` changed, or without a base an amount or comment), the side that
  did not delete wins. Only an empty register is ever deleted, so a count of another workstation is not lost.
  If "mine" is the deletion, "theirs" wins with the note `UNMERGED`; no new kind of note.
- **Restore keeps registers.** A restore resets presentations and bookings; registers stay as they are
  (`CashRestore`). The target state takes the current state of every readable register unit, also those that did not
  exist at the chosen time or sit below a deleted head. The old fields of a presentation that is still alive get their
  current value; for a presentation the restore brings back they are cleared if its register lives, else they take the
  value of the last non-deleted state of the unit. Presentations the restore removes follow the rule above. The only
  register units a restore writes are those two cases. Locked, unreadable or waiting register units do not stop a
  restore. Their chains do not count for the restore points, so a save that wrote only registers is not listed. The
  confirmation says that registers stay and lists the amounts counted since then (`CountedCash`).
- **Sales are never blocked by a locked register**: a locked register unit stops editing it, and saving the event with
  an own change to it fails, but a booking saves as before.
- **Not copied:** "Use as template" copies no registers; the copy starts with one register per presentation.
- **Checks `KAS-001` to `KAS-005`** (`CashRegisterIssue`, `CashRegisterValidator`; name comparison in `EventValidator`).
  `PRS-005` stays reserved and is no longer emitted. Duplicate register names only warn, as duplicate presentation
  names do: they arise without anyone's doing, and an error would block every save, also in the sales view.
- **No amount for shared and free registers yet.** Only the register of a presentation can be counted, in the cash
  reconciliation. The cash count per register (KARV-146) needs the target per register, hence payments with a register
  (KARV-145). An orphan register with an amount cannot be emptied or deleted before that; it stays in the list and in
  the overall balance.

**Consequence:** An event with a saved register no longer opens on 1.1, and no workstation at the same folder may run
1.0.x (it drops `requires`). The patch notes of 1.2.0 say so.

## OpenPDF 1.3.x

The PDF export uses OpenPDF 1.3.43 on purpose: from 2.x on, ICU4J (~15 MB) is a mandatory dependency, and the
reports need no complex text layout.

## Development workflow

**Decision:** How the project is developed (branch model, how branches are merged, specs and their review, bug
fixes, releases) is defined in one place, [CONTRIBUTING.md](../.github/CONTRIBUTING.md) (in German), for people and
for Claude Code alike. This entry only records why the rules are what they are.

**Why:**
- `main` only holds published versions, so its tip is always the latest published version.
- Changes are squashed into the release branch: one commit per change keeps the history of a version readable, and
  `git log --first-parent` lists its changes.
- Release 1.1.0 was squashed into `main`. `main` then did not know the commits of the release branch, and a fix on
  that branch could no longer be merged without conflicts. With a merge commit the release branch stays an ancestor
  of `main`: a fix is made once, on the oldest affected line, and merged forward.
- Keeping the release branch until the next version gives fixes for the published version a fixed place.
- The version has three parts. What separates the second from the third part is not the size of a change but whether
  all workstations of a shared folder have to upgrade together. There is no fourth part for fixes: Conveyor builds
  the package version `a.b.c.d` from the version and the revision, and `AppVersion` reads it back that way;
  `PatchNotesGuard` requires a three-part version that is equal in `pom.xml`, `conveyor.conf` and the patch notes;
  and with one developer and a few releases a year, separating small features from fixes helps nobody.
- Specs used to live on one machine only. In the repository a second developer can read and review them, and a pull
  request can link to its spec. A spec is a snapshot and is not maintained after the merge; what lasts goes into
  this file.
- Two reviewers who do not know the conversation find what the author no longer sees: one asks whether it can be
  built without having to ask, the other checks the claims against the code.
- The rules live in the repository, not in plugins on one developer's machine: whoever clones it has them, and
  whoever opens it with Claude Code has the skill `/spec`.
