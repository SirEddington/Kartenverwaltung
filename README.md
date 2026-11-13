# Kartenverwaltung – Eltviller Carneval Verein

Ticket box office for the carnival sessions of the Eltviller Carneval Verein (ECV). Documentation state: **release
1.1.0**.

## 📚 Documentation
| | |
|---|---|
| [⚙️ Architecture](docs/architecture.md) | System design, components and flow |
| [📁 Structure](docs/structure.md) | Project organization and responsibilities |
| [🚀 Installation](docs/installation.md) | Requirements, running, packaging and releasing |
| [🧠 Technical decisions](docs/decisions.md) | Trade-offs and design justifications |
| [📖 Usage guide](docs/usage.md) | How to use the application, main flows, messages |
| [🧪 Testing](docs/testing.md) | How to run tests and testing strategy |

---

## Description

A JavaFX desktop application that runs the club's ticket sales from hall layout to cash reconciliation:

- **Halls and seating plans** – draw a hall (stage, bar, doors as shapes with text) and one or more seating plans
  made of table rows, tables and round seats. Overlaps and parts outside the hall are reported as warnings.
- **Events and presentations** – an event (e.g. a carnival season) has presentations (individual shows), each with a
  date, a hall, a seating plan and a default price.
- **Ticket sales** – sell, reserve, pay (cash, card, bank transfer), hand out and cancel seats, in a table or directly
  on the seating plan; a second "customer display" window shows free and taken seats to the buyer.
- **Accounting** – cash reconciliation per presentation (expected vs. counted cash) and statistics across events,
  exportable as CSV or PDF.
- **Multi-workstation operation** – several computers share one folder (e.g. synced by Nextcloud). Every change is an
  immutable version file; conflicts such as a double sale are detected and resolved without losing a sale.
- **Restore points** – every save is kept, so any event or hall (also a deleted one) can be restored.
- **Updates** – the app checks for new releases, updates only with the user's consent and can hand the installer to
  workstations without internet through the shared folder.

The problem it solves: a club box office with several cash desks, an unreliable network and volunteers as operators.
Selling must work offline, two desks must never silently lose a sale, and nothing may need a server.

The UI, the domain code, comments and messages are in German.

## What's new in 1.1

| Area | Change | Ticket |
|---|---|---|
| Seating plan | Seats are circles with a diameter, spread in equal sections along the table; new plans start with 50 × 320 cm tables and 45 cm seats; "Reihe füllen…" asks for table and seat sizes; sizes and labels can be set for whole rows | KARV-128 |
| Seating plan | Tables get labels like hall objects (text, size, alignment, rotation); a table keeps its place when its number changes, "Nummern neu vergeben" renumbers by place | KARV-128 |
| Hall editor | Layout checks: overlapping tables/objects and parts outside the hall as warnings (`HLL-003`, `HOB-005`, `TBL-007`, `SPL-004`, `SPL-005`) | KARV-60 |
| Sales view | Accessible seat display: the status no longer depends on color alone (ring, stripes, cross) and a legend explains it | KARV-26 |
| Display | The "Anzeige" menu is shared by sales view, customer display and hall editor and restored at start | KARV-76 |
| Updates | Update check against the latest GitHub release, update only after consent (`app.updates = manual`) | KARV-130, KARV-131 |
| Updates | Version entries and installer distribution through `<shared folder>/updates/` | KARV-132 |
| Patch notes | "Was ist neu" window after an update; the same text becomes the GitHub release text | KARV-125 |
| Data format | Forward-compatible format: unknown fields survive, unsafe content of newer versions is locked | KARV-133 – KARV-136 |
| Robustness | Files are forced to disk before rename (power-cut safety) | KARV-90 |
| Packaging | JavaFX native libraries extracted at build time (antivirus-safe start) | KARV-137 |
| Sync | Offline detection for Nextcloud and OneDrive folders (since 1.0.1) | KARV-127 |

Upgrade note: update **all** workstations of a shared folder to 1.1 at the same time, then open and save each hall
with a seating plan once in the hall editor (see
[installation.md](docs/installation.md#shared-folder-multi-workstation)).

## Quick start

```bash
git clone https://github.com/SirEddington/Kartenverwaltung.git
cd Kartenverwaltung
./mvnw javafx:run
```

On first start the application asks for the operator name ("Wer verkauft?") and runs in single-workstation mode with
its data under `%LOCALAPPDATA%\EltvillerCarnevalVerein\Kartenverwaltung`.

## Technologies used

| Category | Technology |
|---|---|
| Language / runtime | Java 21 |
| UI | JavaFX 21.0.2 (controls, FXML), ControlsFX 11.2.1 |
| Persistence | Jackson 2.17 (JSON, `jsr310` for `java.time`) on a custom file-based version store |
| Export | OpenPDF 1.3.43 (PDF), hand-written CSV |
| Build | Maven wrapper (Maven 3.9.9), maven-shade-plugin (per-platform fat JAR), javafx-maven-plugin |
| Tests | JUnit 5.10 |
| Packaging / distribution | Hydraulic Conveyor (Windows MSIX, Linux), GitHub Releases + GitHub Pages download site |
| CI | GitHub Actions (`ci.yml` on every push/PR, `release.yml` manual) |

## Quick installation

1. Install a JDK 21.
2. Clone the repository.
3. `./mvnw javafx:run` (on Linux: `./mvnw -Plinux javafx:run`).

End users install the packages from GitHub Releases. Details, packaging and releasing:
[docs/installation.md](docs/installation.md).

## Architecture (summary)

A layered single-process desktop application: FXML views and controllers (`ui`) work on a mutable domain model
(`model`), checked by validators (`validation`) and persisted through repositories (`repository`). The repositories
split events and halls into small *units* (event head, presentation, booking, hall) and write them to the *version
store* (`store`): append-only, immutable JSON version files plus claim files that decide, per unit, which version is
valid ("first to upload wins"). A background `SyncService` uploads to and polls a shared folder every 2 seconds, so
several workstations can sell at the same time. The `update` package checks for releases and handles installers in
the shared folder without JavaFX. See [docs/architecture.md](docs/architecture.md).

## Project structure

```
Kartenverwaltung/
├── pom.xml, mvnw, mvnw.cmd, .mvn/      Maven build (Windows natives by default, -Plinux for Linux)
├── conveyor.conf                        Installer/packaging configuration
├── .github/workflows/, .github/scripts/ CI (tests, release text) and release (Conveyor)
└── src/
    ├── main/java/de/eltviller_carneval_verein/karten/
    │   ├── MainApp.java                 Entry point, startup sequence, screen switching
    │   ├── model/                       Domain model (Event, Presentation, Booking, Hall, SeatingPlan, …)
    │   ├── validation/                  Plausibility and layout checks with coded messages
    │   ├── repository/                  Event/hall repositories, unit mapping, conflict policies
    │   ├── store/                       Version store, shared-folder sync, conflict resolution
    │   ├── tracking/                    Unsaved-change detection and state restore
    │   ├── settings/                    settings.json
    │   ├── update/                      Version, update check, update over the shared folder, patch notes
    │   ├── ui/                          JavaFX controllers (sales, accounting, event, hall, settings, shared, …)
    │   └── util/                        Money formatting, totals, angles
    ├── main/resources/…/                FXML views, CSS, fonts, images, patchnotes.md
    └── test/java/…                      JUnit tests, mirroring the main packages
```

See [docs/structure.md](docs/structure.md).

## License

GPL-3.0 (see [LICENSE](LICENSE)).
