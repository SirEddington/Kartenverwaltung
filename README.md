# Kartenverwaltung – Eltviller Carneval Verein

## 📚 Documentation
| | |
|---|---|
| [⚙️ Architecture](docs/architecture.md) | System design, components and flow |
| [📁 Structure](docs/structure.md) | Project organization and responsibilities |
| [🚀 Installation](docs/installation.md) | Requirements and steps to run the project |
| [🧠 Technical decisions](docs/decisions.md) | Trade-offs and design justifications |
| [📖 Usage guide](docs/usage.md) | How to use the application, main flows |
| [🧪 Testing](docs/testing.md) | How to run tests and testing strategy |

---

## Description

A JavaFX desktop application for the ticket box office of the Eltviller Carneval Verein (ECV). It manages the
club's carnival sessions from hall layout to cash reconciliation:

- **Halls and seating plans** – draw a hall (stage, bar, doors as shapes) and one or more seating plans made of
  table rows, tables and seats.
- **Events and presentations** – an event (e.g. a carnival season) has presentations (individual shows), each with a
  date, a hall, a seating plan and a default price.
- **Ticket sales** – sell, reserve, pay (cash, card, bank transfer), hand out and cancel seats, either in a table
  view or directly on the seating plan; a second "customer display" window shows free/taken seats to the buyer.
- **Accounting** – cash reconciliation per presentation (expected vs. counted cash) and statistics across events,
  exportable as CSV or PDF.
- **Multi-workstation operation** – several computers share one folder (e.g. synced by Nextcloud). Every change is
  an immutable version file; conflicts such as a double sale are detected and resolved deterministically.
- **Restore points** – every save is kept, so any event or hall (also deleted ones) can be restored to an earlier
  state.

The UI, code identifiers in the domain layer, comments and messages are in German.

## Quick start

```bash
git clone https://github.com/SirEddington/Kartenverwaltung.git
cd Kartenverwaltung
./mvnw javafx:run
```

On first start the application asks for the operator name ("Wer verkauft?") and runs in single-workstation mode
with its data under `%LOCALAPPDATA%\EltvillerCarnevalVerein\Kartenverwaltung`.

## Technologies used

| Category | Technology |
|---|---|
| Language / runtime | Java 21 |
| UI | JavaFX 21.0.2 (controls, FXML), ControlsFX 11.2.1 |
| Persistence | Jackson 2.17 (JSON, `jsr310` for `java.time`) on a custom file-based version store |
| Export | OpenPDF 1.3.43 (PDF), hand-written CSV |
| Build | Maven (wrapper, Maven 3.9.9), maven-shade-plugin (per-platform fat JAR), javafx-maven-plugin |
| Tests | JUnit 5.10 |
| Packaging / distribution | Hydraulic Conveyor (Windows MSIX, Linux), GitHub Releases + GitHub Pages download site |
| CI | GitHub Actions (`ci.yml` on every push/PR, `release.yml` manual) |

## Quick installation

1. Install a JDK 21.
2. Clone the repository.
3. `./mvnw javafx:run` (on Linux: `./mvnw -Plinux javafx:run`).

Details, packaging and release: [docs/installation.md](docs/installation.md).

## Architecture (summary)

A layered single-process desktop application: FXML views and controllers (`ui`) work on a mutable domain model
(`model`), checked by validators (`validation`) and persisted through repositories (`repository`). The repositories
split events and halls into small *units* (event head, presentation, booking, hall) and write them to the *version
store* (`store`): append-only, immutable JSON version files plus claim files that decide, per unit, which version
is valid ("first to upload wins"). A background `SyncService` uploads to and polls a shared folder every 2 seconds,
so several workstations can sell at the same time. See [docs/architecture.md](docs/architecture.md).

## Project structure

```
Kartenverwaltung/
├── pom.xml, mvnw, mvnw.cmd, .mvn/      Maven build (Windows natives by default, -Plinux for Linux)
├── conveyor.conf                        Installer/packaging configuration
├── .github/workflows/                   CI (tests) and release (Conveyor)
└── src/
    ├── main/java/de/eltviller_carneval_verein/karten/
    │   ├── MainApp.java                 Entry point, startup sequence, screen switching
    │   ├── model/                       Domain model (Event, Presentation, Booking, Hall, SeatingPlan, …)
    │   ├── validation/                  Plausibility checks with coded messages
    │   ├── repository/                  Event/hall repositories, unit mapping, conflict policies
    │   ├── store/                       Version store, shared-folder sync, conflict resolution
    │   ├── tracking/                    Unsaved-change detection and state restore
    │   ├── settings/                    settings.json
    │   ├── ui/                          JavaFX controllers (sales, accounting, event, hall, settings, shared, …)
    │   └── util/                        Money formatting, totals, angles
    ├── main/resources/…/ui/             FXML views, CSS, fonts, images
    └── test/java/…                      JUnit tests, mirroring the main packages
```

See [docs/structure.md](docs/structure.md).

## License

GPL-3.0 (see [LICENSE](LICENSE)).
