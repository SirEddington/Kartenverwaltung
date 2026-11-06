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
in the app (KARV-131) or when it is installed from the shared folder (KARV-132).

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
  - **Collateral damage:** while cleaning up, Norton also deleted the JavaFX native libraries in the shared cache
    `%USERPROFILE%\.openjfx\cache\21.0.2+5\amd64` (`glass.dll`, `prism_d3d.dll`, `javafx_font.dll`, `prism_sw.dll`).
    They were not in quarantine and could not be recreated until Windows restarted (`AccessDeniedException`).
    Until then no JavaFX 21.0.2 program started on the machine: not the real app, not `javafx:run`, not the UI
    tests. The fat JAR extracts these libraries into the user profile on every start, so the real app shares the
    cache with every other JavaFX program.
  - Conveyor ships `updatecheck.exe` in the package even with `use-update-escape-hatch = false`.
- Norton's Web/Mail Shield intercepts TLS. Java's own trust store rejects its root certificate (PKIX), as Conveyor,
  Maven and a plain JDK test showed. The Windows trust store accepts it, but that requires `jdk.crypto.mscapi`,
  which the bundled runtime does not contain (it also lacks `java.net.http`). In the packaged app this path is
  what triggered Norton (1.1.0.3).

**Consequence for the update path (KARV-131, KARV-132):**
- The app itself should not start executables, use HTTPS or read the certificate store.
- Do not use `triggerUpdateCheckUI()`.
- Windows should check for and download updates. Candidates are `Package.CheckUpdateAvailabilityAsync()` and
  `Add-AppxPackage -AppInstallerFile`, using the update source the package is already linked to. A local `.msix`
  from the shared folder is installed with `Add-AppxPackage -Path`. Whether Norton accepts the app starting such a
  helper is **not verified**: in 1.1.0.2 the helper was started from outside the app.
- Package JavaFX with Conveyor so that its native libraries live inside the package rather than in the shared
  user cache. Norton could then no longer break other JavaFX programs.
- The lasting fix is a code-signing certificate with reputation. Without one, every new launcher is unknown to
  Norton.
- Every Norton test can damage the machine it runs on. Run them only on a machine where JavaFX may break for a
  while, and restart Windows afterwards.

**Consequence:** `manual` must not ship without KARV-131. Otherwise there is no way to update apart from a new
install.

## OpenPDF 1.3.x

The PDF export uses OpenPDF 1.3.43 on purpose: from 2.x on, ICU4J (~15 MB) is a mandatory dependency, and the
reports need no complex text layout.
