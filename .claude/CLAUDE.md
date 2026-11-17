# CLAUDE.md

Anleitung für Claude Code in diesem Repository.

Kartenverwaltung des Eltviller Carneval Vereins: JavaFX-21-Desktopanwendung (Java 21, Maven) zum Verwalten von Events,
Vorstellungen, Hallen und Bestuhlungen sowie zum Kartenverkauf und zur Abrechnung, auch mit mehreren Kassen über einen
gemeinsamen Ordner. Code, Kommentare, Meldungen sowie Jira und Confluence sind deutsch; die Dokumentation unter `docs/`
ist englisch.

## Befehle

```bash
./mvnw -o --batch-mode test-compile                  # kompilieren (-o = offline)
./mvnw test                                          # alle Tests
./mvnw test -Dtest=VersionStoreTest#methodName       # einzelner Test
./mvnw javafx:run                                    # App starten (Hauptklasse MainApp)
./mvnw -o --batch-mode package -DskipTests           # Fat-JAR für Windows nach target/
./mvnw -o --batch-mode package -Plinux -DskipTests   # Fat-JAR für Linux
bash .github/scripts/release-notes.sh                # Release-Text nach target/ (braucht das Linux-JAR und Java 21 im Pfad)
```

Es gibt keinen Linter. Die CI (`.github/workflows/ci.yml`) baut bei jedem Push mit `-Plinux` unter `xvfb-run`, führt
alle Tests aus und erzeugt den Release-Text.

Neue Version: `<version>` in `pom.xml`, `app.version` in `conveyor.conf` und ein Abschnitt oben in
`src/main/resources/de/eltviller_carneval_verein/karten/patchnotes.md`. Der ganze Ablauf steht in
`docs/installation.md`, Abschnitt „Releasing".

## Wo was steht

Die Architektur wird hier nicht wiederholt. Vor einer Änderung die passende Datei lesen:

- `docs/architecture.md`: Komponenten, Datenfluss, Muster
- `docs/decisions.md`: Entscheidungen mit Begründung; was dauerhaft gilt, wird dort ergänzt
- `docs/structure.md`: Verzeichnisbaum, Pakete, Schichtregeln
- `docs/testing.md`: Tests ausführen, Abdeckung, Konventionen
- `docs/installation.md`: Entwicklungsumgebung, gemeinsamer Ordner, Release
- `docs/usage.md`: Bedienung aus Sicht der Kasse

## Lokale Ablage: `.local/`

`.local/` ist nicht versioniert und der einzige Ort für Dateien, die nur auf diesem Rechner liegen. Nichts davon wird
committet, und außerhalb von `.local/` und `target/` entstehen keine lokalen Hilfsdateien.

| Ordner | Inhalt |
|---|---|
| `.local/tmp/` | Scratch-Programme, Zwischenergebnisse, Logs; jederzeit löschbar |
| `.local/superpowers/specs/` | Specs und Designs |
| `.local/superpowers/plans/` | Umsetzungspläne |
| `.local/superpowers/handoffs/` | Übergaben zwischen Sitzungen |
| `.local/superpowers/sdd/` | Arbeitsstände je Plan (Ledger, Briefe, Review-Pakete) |
| `.local/superpowers/notizen/` | Handtest-Protokolle, PR-Texte, Entwürfe für Confluence |
| `.local/superpowers/reports/` | Berichte einzelner Aufgaben |
| `.local/handtest/` | Treiber für Handtests per Fernsteuerung |
| `.local/testrunner/` | JUnit-Launcher mit eigenem Classpath (README dort) |
| `.local/deployment/` | `conveyor.local.conf` für lokale Conveyor-Läufe |

Das gilt ausdrücklich anstelle der Standardpfade der Superpowers-Skills: Specs und Pläne gehören nicht nach
`docs/superpowers/`, Arbeitsstände nicht nach `.superpowers/`. Skripte der Skills, die diese Pfade fest anlegen, werden
nicht benutzt.

## Arbeitsweise

- Pull Requests einer Version gehen auf `release/<version>`, nur die fertige Version geht nach `main`.
- Commit-Betreff `KARV-xx: …`, wenn es ein Ticket gibt. Push und Pull Request nur auf ausdrückliche Anweisung.
- Im Arbeitsverzeichnis haben die meisten Dateien CRLF: bestehende Dateien mit dem Edit-Werkzeug ändern, nicht mit
  `sed -i`. Shell-Skripte (`*.sh`) bleiben LF.
- Es wird parallel in Eclipse gearbeitet: vor einem Commit `git status` und `git diff` ansehen.
