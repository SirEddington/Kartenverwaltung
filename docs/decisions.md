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

## Layout checks only warn

**Decision:** Overlaps between tables and hall objects, and parts lying outside the hall, are warnings, never errors.
The tolerance is 1 cm (`Footprint.TOLERANCE`). A table counts together with its seats. Objects or tables that lie
fully inside another one do not count as overlapping, and seats are not checked against their own table.

**Why:** Some overlaps are intended (a door on the wall, a stage with a table on it), and while rows or objects are
being moved into place the intermediate state must not block saving.

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
  the format or use `requires` on the unit.
- **A format change is needed** to rename a field, change the type or meaning of a field or value, or restructure
  units.

### Burned names and `requires`

Removed fields are never reused. The lists are per class and live in the `@JsonIgnoreProperties` annotations (and in
`JsonKeysTest`):

| Class | Burned names |
|---|---|
| `Presentation` | `tables`, `defaultTableWidth`, `defaultTableHeight`, `defaultSeatWidth`, `defaultSeatHeight`, `hallWidth`, `hallHeight`, `tableRows`, `hallObjects` |
| `SeatingPlan` | `defaultSeatHeight` |
| `Seat` | `height`, `price`, `paymentStatus`, `collected`, `lastName`, `firstName`, `email`, `comment` |

`requires` is reserved. No model class has a property of that name, and the unit classes never write it from a freshly
built model. A version that uses it **derives it when writing** from the state of the model ("this presentation
carries the new lock flag"), removes it from the collected fields when reading, **never keeps it and never copies it
into another unit**, not even on JSON level (resolver, `FieldMerge`/`transfer`). Otherwise a unit would never unlock
and copies would be locked for no reason. Inside the same unit the payload stays as it is, so raw comparisons do not
write a version because of a removed `"requires": []`. `requires` works at the top of a unit only; inside a nested
object (a hall object in a hall) it is only kept. To make part of a hall strict, set `requires` on the hall.

The gate (`ContentGate`) calls a version too new if `requires` is present, not `null`, and not an array of texts the
program knows (1.1 knows none; absent, `null` and `[]` mean "nothing required"), or if a booking has a `paymentStatus`
that is not `null` and not the name of a `PaymentStatus` (a number counts as unknown). `rulesId()` is computed from
these inputs, so a new `PaymentStatus` value changes it, and the startup index then resolves the units again.

### Boundary to 1.0.x

1.0.x drops unknown fields on save, so the protection works among workstations running 1.1 or later (checked
against tag `1.0.1`):

- A new value of a strict enum (`paymentStatus`) makes the booking unreadable on 1.0.x, but not locked (its chain is
  `OK`): the seat looks free and can be sold twice.
- `HallObject` has no `ignoreUnknown` in 1.0.x, and `shape`/alignment are strict there: a new field on a hall object
  or a new shape or alignment value makes the whole hall unreadable on 1.0.x, and its presentations freeze.
- 1.0.x does not know `requires` and drops it on save.
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

## OpenPDF 1.3.x

The PDF export uses OpenPDF 1.3.43 on purpose: from 2.x on, ICU4J (~15 MB) is a mandatory dependency, and the
reports need no complex text layout.
