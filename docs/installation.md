# Installation

## End users

Installers are published as GitHub Releases and on the download page that Conveyor maintains in the `gh-pages`
branch. Supported platforms: **Windows x64** (per-user MSIX install, optional desktop shortcut, updates from the
latest GitHub Release only after the user agrees to them in the app: a hint in the footer and *Einstellungen → Info
und Updates*, see [usage.md](usage.md#updates) and
[decisions.md](decisions.md#updates-only-with-the-users-consent-appupdates--manual)) and **Linux x64 (glibc)**.
macOS is intentionally not built (it would require Apple signing and notarization). The installer bundles its own
Java runtime (Amazon Corretto 21) and the JavaFX native libraries; no native libraries are unpacked at run time.

## Development setup

### Prerequisites

| Requirement | Version | Notes |
|---|---|---|
| JDK | 21 | `maven.compiler.release` is 21. CI uses Temurin, packaging bundles Corretto. |
| Maven | none needed | Use the wrapper (`mvnw` / `mvnw.cmd`, Maven 3.9.9). |
| Display | – | JavaFX needs a display; on headless Linux run tests under `xvfb-run`. |
| Conveyor | – | Only for building installers locally; releases run in GitHub Actions. |

No database, server or environment variables are required.

### 1. Clone

```bash
git clone https://github.com/SirEddington/Kartenverwaltung.git
cd Kartenverwaltung
```

### 2. Build

The JavaFX dependencies are pinned to one platform classifier. The default is Windows (`win`); on Linux, add the
`linux` profile to every Maven command.

Windows:

```bash
./mvnw clean package
```

Linux:

```bash
./mvnw -Plinux clean package
```

This runs the tests and produces a per-platform fat JAR: `target/Kartenverwaltung-1.1.0-win.jar` or
`target/Kartenverwaltung-1.1.0-linux.jar`. The two can coexist in `target/` (the release workflow builds both
without `clean` in between). These JARs are the inputs for Conveyor.

### 3. Run

```bash
./mvnw javafx:run
```

On Linux:

```bash
./mvnw -Plinux javafx:run
```

The `javafx-maven-plugin` starts `de.eltviller_carneval_verein.karten.MainApp` with
`--add-opens javafx.controls/javafx.scene.control.skin=org.controlsfx.controls`, which ControlsFX requires. Use
the same JVM option when running `MainApp` from an IDE.

### 4. Data directory

All data, settings and logs live in one directory, resolved by `AppPaths.appDataDir()`:

| Condition | Directory |
|---|---|
| System property `karten.appDataDir` set | that directory |
| `LOCALAPPDATA` set (Windows) | `%LOCALAPPDATA%\EltvillerCarnevalVerein\Kartenverwaltung` |
| otherwise | `~/.kartenverwaltung` |

Contents:

```
data/             local version store (all units)
index/            start index (cache, safe to lose)
logs/             kartenverwaltung-0.log … -4.log (5 × 1 MB, rotating)
settings.json     application settings (shared folder, operators, auto-save)
machine.json      random machine id and display name of this workstation
connection.json   "connected" marker for the shared folder (only when connected)
instance.lock     prevents a second instance on the same machine
```

To run a second instance on the same computer (e.g. to test multi-workstation sync), give each instance its own
data directory with the JVM option `-Dkarten.appDataDir=<dir>`; otherwise the instance lock refuses the start.
Never copy a data directory to another computer: both would share the machine id (the app detects this, SYS-011,
and assigns a new id, but it is avoidable).

### 5. Verify

- The window "ECV Kartenverwaltung" opens with "Bestand wird geladen …", then asks "Wer verkauft?".
- After entering a name, the main menu shows *Abrechnung*, *Kartenverkauf*, *Verwaltung*, *Einstellungen*.
- The data directory now contains `machine.json`, `instance.lock` and `logs/kartenverwaltung-0.log`.

## Shared folder (multi-workstation)

No installation step is required: in *Einstellungen → Gemeinsamer Ordner* choose a folder that all workstations
can reach (a network share or a folder synced by a client such as Nextcloud) and click *Verbinden*. See
[usage.md](usage.md#working-with-several-workstations).

**Update all workstations together.** Workstations sharing a folder should run the same version; each one shows the
update hint in its footer (or use *Einstellungen → Info und Updates → "Jetzt prüfen"*). Version 1.1 adds
table labels to the seating plan; a workstation still on 1.0.x drops them (back to "Text automatisch") and puts
automatically placed seats back in their old positions when it saves a hall. Update every workstation to 1.1 at the
same time and restart it. Then open each hall with a seating plan once in the hall editor and save it: 1.1 places
seats differently, and until a hall is saved with the new layout, a workstation that opened it in the editor holds
back changes to that hall from other workstations until it is restarted.

## Releasing

1. Bump the version in `conveyor.conf` (`app.version`) **and** in `pom.xml` (`<version>`). The JAR names in
   `conveyor.conf` are derived from `app.version`, so both must match. In the same commit add a section for the new
   version at the top of `src/main/resources/de/eltviller_carneval_verein/karten/patchnotes.md` with at least one
   real entry; `PatchNotesBundledTest` fails otherwise (it also requires a three-part version `a.b.c`).
2. **Before the pull request from `release/<version>` to `main` (patch notes):** until then the section of the new
   version only holds a provisional text, so that the tests stay green on the release branch. Shortly before the pull
   request, write the final text from the tickets of the fixVersion in Jira (`git log <previous tag>..HEAD` helps) and
   have the maintainer approve it. Plain language for the box
   office; sections "Neu", "Verbessert", "Behoben", "Hinter den Kulissen"; no placeholders, no `@name`, `#12` or
   `:word:` (the parser rejects them, see [decisions.md](decisions.md#patch-notes-karv-125)).
3. Create the format sample of the new version from this final release state: run `FormatSampleGenerator`
   (`src/test/java/.../store/`) with the target folder `src/test/resources/format-samples/<version>`. It writes
   `data.zip` there; add a `README.md` next to it (how it was created, contents) and commit both. `FormatSamplesTest`
   then checks that every later version loads this sample unchanged, so a missed sample means no guard for the next
   version. Details: [decisions.md](decisions.md#release-steps).
4. Run the **Release build** workflow (`.github/workflows/release.yml`, manual `workflow_dispatch`). It:
   1. runs the tests on Linux under `xvfb-run` (`-Plinux`),
   2. builds the Windows JAR, then the Linux JAR (`-DskipTests`),
   3. writes the release text from `patchnotes.md` (`.github/scripts/release-notes.sh` with `PatchNotesExport`) and
      keeps it as the artifact `release-notes`; a missing or broken section stops the run here, before anything is
      published,
   4. runs `conveyor make copied-site`, which uploads installers as GitHub Release assets and updates the download
      page in `gh-pages` (the release is public at once, not a draft),
   5. in the separate job `release-notes`, replaces Conveyor's text of the release with the patch notes
      (`gh release edit`; the download link stays).

   If something fails:
   - job `release-notes` red: "Re-run failed jobs" is safe.
   - Conveyor red and no release on GitHub yet (`gh release view <version>` finds nothing): running the workflow
     again is safe.
   - Conveyor red but the release already exists (e.g. the push to `gh-pages` failed): job `release-notes` does not
     run. Set the text by hand with `gh release edit <version> --repo SirEddington/Kartenverwaltung --notes-file
     release-notes.md` using the file from the artifact; look at the state of the release before running Conveyor
     again.

   After the release: open the release page on GitHub and check the text. A workstation updated from the previous
   version shows the window "Was ist neu in Version X". From the first release after 1.1.0 on, also check on a
   workstation with the previous version that *Info und Updates* and the update confirmation show the link "Was ist
   neu in Version X?" and that it opens `…/releases/tag/X` (on Linux too, if there is a Linux workstation).
5. Required repository secrets: `CONVEYOR_SIGNING_KEY`, `CONVEYOR_BILLING_EMAIL`. `GITHUB_TOKEN` is provided by
   Actions.
6. Optionally submit the Windows installer to Microsoft as a software developer
   (`https://www.microsoft.com/wdsi/filesubmission`). If Norton flags a release on a workstation, report it at
   `https://submissions.norton.com/`. See [decisions.md](decisions.md#no-public-code-signing-certificate-for-now).
7. Running Windows workstations show the update hint within about 4 hours (or at the next start). To withdraw a
   release, mark it as a pre-release: the previous release becomes "latest" again and the hint disappears.
   Workstations showing "Update-Prüfung nicht möglich" are updated with *"Update im Browser laden"*. See
   [decisions.md](decisions.md#update-check-and-update-from-the-app-karv-131).

`conveyor.conf` includes `deployment/conveyor.local.conf`, which is git-ignored and only used for local builds.
The `deployment/build.bat` script is the old local release path and is superseded by the workflow.
