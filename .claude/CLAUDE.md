# CLAUDE.md

Anleitung für Claude Code in diesem Repository.

Kartenverwaltung des Eltviller Carneval Vereins: JavaFX-21-Desktopanwendung (Java 21, Maven) zum Verwalten von Events,
Vorstellungen, Hallen und Bestuhlungen sowie zum Kartenverkauf und zur Abrechnung, auch mit mehreren Kassen über einen
gemeinsamen Ordner.

## Regeln

Branch-Modell, Merge-Arten, der Ablauf einer Änderung (Spec, Prüfung, Freigabe, danach `/simplify` und
`/code-review`), Bugfixes, Release, Commits und Sprache stehen in `.github/CONTRIBUTING.md`. Die Datei ist hier
eingebunden und gilt vollständig; diese Anleitung wiederholt sie nicht.

@../.github/CONTRIBUTING.md

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

Neue Version: Was dafür zu ändern ist und wie ein Release gebaut wird, steht in `docs/installation.md`, Abschnitt
„Releasing".

## Wo was steht

Die Architektur wird hier nicht wiederholt. Vor einer Änderung die passende Datei lesen:

- `docs/architecture.md`: Komponenten, Datenfluss, Muster
- `docs/decisions.md`: Entscheidungen mit Begründung; was dauerhaft gilt, wird dort ergänzt
- `docs/structure.md`: Verzeichnisbaum, Pakete, Schichtregeln
- `docs/testing.md`: Tests ausführen, Abdeckung, Konventionen
- `docs/installation.md`: Entwicklungsumgebung, gemeinsamer Ordner, Release
- `docs/usage.md`: Bedienung aus Sicht der Kasse
- `docs/roadmap.md`: Reihenfolge der geplanten Versionen und ihre Begründung
- `docs/specs/`: Specs einzelner Änderungen, Momentaufnahmen

## Lokale Ablage: `.local/`

`.local/` ist nicht versioniert und der einzige Ort für Dateien, die nur auf diesem Rechner liegen. Nichts davon wird
committet, und außerhalb von `.local/` und `target/` entstehen keine lokalen Hilfsdateien.

| Ordner | Inhalt |
|---|---|
| `.local/tmp/` | Scratch-Programme, Zwischenergebnisse, Logs; jederzeit löschbar |
| `.local/superpowers/specs/` | alte Specs und Designs (bis 16.11.2026); neue liegen versioniert in `docs/specs/` |
| `.local/superpowers/plans/` | Umsetzungspläne |
| `.local/superpowers/handoffs/` | Übergaben zwischen Sitzungen |
| `.local/superpowers/sdd/` | Arbeitsstände je Plan (Ledger, Briefe, Review-Pakete) |
| `.local/superpowers/notizen/` | Handtest-Protokolle, PR-Texte, Entwürfe für Confluence |
| `.local/superpowers/reports/` | Berichte einzelner Aufgaben |
| `.local/handtest/` | Treiber für Handtests per Fernsteuerung |
| `.local/testrunner/` | JUnit-Launcher mit eigenem Classpath (README dort) |
| `.local/deployment/` | `conveyor.local.conf` für lokale Conveyor-Läufe |

Das gilt ausdrücklich anstelle der Standardpfade der Superpowers-Skills: Specs gehören nach `docs/specs/`, Pläne und
Arbeitsstände nach `.local/superpowers/`, nichts nach `docs/superpowers/` oder `.superpowers/`. Skripte der Skills, die
diese Pfade fest anlegen, werden nicht benutzt.

Wer das Superpowers-Plugin nutzt: Das Gespräch führt dessen Brainstorming. Ablageort, Aufbau, Prüfung und Freigabe
des Specs richten sich nach der `CONTRIBUTING.md`, ausgeführt mit dem Skill `spec` (zwei unabhängige Prüfer statt des
einen Prüfers des Plugins).

## Arbeitsweise

- Push und Pull Request nur auf ausdrückliche Anweisung.
- Beim Anlegen eines Pull Requests die Vorlage `.github/pull_request_template.md` lesen und ausfüllen;
  `gh pr create --body` übernimmt sie nicht von selbst.
- Im Arbeitsverzeichnis haben die meisten Dateien CRLF: bestehende Dateien mit dem Edit-Werkzeug ändern, nicht mit
  `sed -i`. Shell-Skripte (`*.sh`) bleiben LF.
- Es wird parallel in Eclipse gearbeitet: vor einem Commit `git status` und `git diff` ansehen.
