# Entwicklungsablauf: Branch-Modell, Regeln und Werkzeuge

Stand: Entscheidung vom 17.11.2026. Ein Spec ist eine Momentaufnahme und wird nach dem Merge nicht gepflegt; was
dauerhaft gilt, steht in `.github/CONTRIBUTING.md` und `docs/decisions.md`.

## Anlass und Ziel

Bisher arbeitet ein Entwickler allein an der Kartenverwaltung, zusammen mit Claude Code. Wie gearbeitet wird, steht
verstreut: ein Satz in `docs/installation.md`, ein Abschnitt in `.claude/CLAUDE.md`, der Rest in lokalen Notizen und
in Plugins, die nur auf einem Rechner installiert sind. Es gibt noch keine weiteren Entwickler. Der Maintainer will
vorbereitet sein, falls jemand mitentwickeln möchte.

Ziel: Eine Stelle im Repository beschreibt verbindlich, wie hier gearbeitet wird. Wer das Repository klont, findet
dort Branch-Modell, Bugfix-Weg und die Schritte vor und nach der Entwicklung, und wer es mit Claude Code öffnet, hat
die nötigen Werkzeuge, ohne etwas zu installieren.

## Ziele, Nicht-Ziele, Anti-Ziele

**Ziele**

- Die Regeln stehen genau einmal, in `.github/CONTRIBUTING.md`. Menschen und Claude lesen dieselbe Datei.
- Vor einer Entwicklung entsteht ein Spec, das zwei unabhängige Prüfer gelesen haben, bis keiner mehr einen
  kritischen oder großen Punkt findet.
- Nach einer Entwicklung laufen `/simplify` und `/code-review`.
- Specs liegen im Repository und sind im Pull Request nachprüfbar.
- Einfache Regeln auf GitHub schützen vor Versehen (falscher Merge, gelöschter Release-Branch, roter Build).

**Nicht-Ziele**

- Keine automatische Prüfung der PR-Checkliste in der CI.
- Keine Pflicht-Reviews durch eine zweite Person (es gibt keine).
- Keine versionierte `.claude/settings.json` und keine Pflicht, ein Plugin zu installieren.
- Die 34 alten Specs unter `.local/superpowers/specs/` wandern nicht ins Repository. Sie sind für den lokalen
  Gebrauch geschrieben und nicht auf Privates durchgesehen.
- Keine Änderung an `ci.yml` oder `release.yml`, keine Issue-Vorlagen, keine Git-Hooks, kein CODEOWNERS.

**Anti-Ziele** (wäre ein Fehlschlag, auch wenn es technisch funktioniert)

- Dieselbe Regel steht an zwei Stellen und läuft auseinander.
- Eine Regel blockiert den einzigen Entwickler im Alltag.
- In der Projektwurzel entstehen neue Einträge.
- Ein Spec im öffentlichen Repository enthält private Daten (Namen von Kunden, E-Mail-Adressen, Zugangsdaten).

## Randbedingungen

- Das Repository `SirEddington/Kartenverwaltung` ist öffentlich; einziger Mitwirkender ist der Maintainer.
- Die Projektwurzel bleibt, wie sie nach dem Aufräumen ist. Neue Dateien liegen unter `.github/`, `.claude/` und
  `docs/`.
- Sprache: Code, Kommentare, Commits, Pull Requests und Tickets sind deutsch, die beschreibende Dokumentation unter
  `docs/*.md` und die `README.md` sind englisch.
- Einstellungen am GitHub-Repository ändert der Maintainer selbst, oder Claude auf sein ausdrückliches Ja hin. Sie
  sind nicht Teil des Pull Requests.
- Push und Pull Request durch Claude nur auf ausdrückliche Anweisung.
- Lokale Dateien liegen weiter nur unter `.local/` (siehe `.claude/CLAUDE.md`).
- Die Umsetzung landet im Branch `chore/projektverzeichnis-aufraeumen` und geht mit dem Aufräumen in einem Pull
  Request auf `release/1.2.0`.

## Ausgangslage (geprüft am 17.11.2026)

- `main` und `release/1.2.0` zeigen auf `afdf7b8` („Release 1.1.0 (#44)"). `release/1.1.0` liegt noch auf GitHub.
- Release 1.1.0 kam per Squash nach `main`. `release/1.1.0` hat deshalb 15 Commits, die `main` nicht kennt, bei
  identischem Inhalt.
- Ruleset `onlyMerge` auf dem Standard-Branch: kein Löschen, kein Force-Push, Pull Request nötig, erlaubte Merge-Art
  nur `squash`. Es hat eine Ausnahme für die Rolle „Repository admin" im Modus `always`: Für den Maintainer, den
  einzigen Admin, sperrt es also nichts.
- Repository-Einstellungen: alle drei Merge-Arten erlaubt, `delete_branch_on_merge` aus. Titel eines Squash-Commits:
  `COMMIT_OR_PR_TITLE` (bei einem Pull Request mit nur einem Commit dessen Betreff, nicht der PR-Titel). Titel eines
  Merge-Commits: `MERGE_MESSAGE` („Merge pull request #n from …").
- Die CI (`.github/workflows/ci.yml`) läuft bei jedem Push und Pull Request; ihr einziger Job heißt `test`.
- Bisherige Pull Requests: Branch `feature/KARV-<nr>-<kurzname>` (auch `docs/…`; `test/…` und `hotfix/…` gingen vor
  den Release-Branches direkt nach `main`), Titel meist `KARV-<nr>: <Titel> (v<Version>)`, per Squash in den
  Release-Branch. 4 der 15 Commits auf `release/1.1.0` kamen ohne Pull Request direkt auf den Branch, darunter die
  Release-Vorbereitung.
- Im Repository gibt es keine `CONTRIBUTING.md`, keine PR-Vorlage, keinen Skill und keine versionierten
  Claude-Einstellungen außer `.claude/CLAUDE.md`.

Aus der Dokumentation von Claude Code (code.claude.com/docs, Seiten memory, skills, commands):

- `.claude/CLAUDE.md` ist neben `./CLAUDE.md` ein dokumentierter Ort für die Projektanleitung.
- Eine Anleitung bindet andere Dateien mit `@pfad/zur/datei` ein. Relative Pfade gelten ab der einbindenden Datei,
  nicht ab dem Arbeitsverzeichnis. In Code-Spannen und Code-Blöcken wird nicht eingebunden. Für Dateien innerhalb
  des Projekts erscheint kein Freigabe-Dialog.
- Ein Projekt-Skill liegt unter `.claude/skills/<name>/SKILL.md`, wird mit dem Repository geteilt und mit `/<name>`
  aufgerufen; der Name ergibt sich aus dem Ordner.
- `/simplify` und `/code-review` sind mitgelieferte Skills und brauchen kein Plugin. `/simplify` sucht keine Fehler,
  `/code-review` sucht Fehler im Diff.
- Nicht dokumentiert ist, ob die Anweisungen eines Skills Subagents starten dürfen. In der Praxis tun Skills das
  (auch die des Superpowers-Plugins); der Probelauf unten belegt es für diesen Skill. Die Option `context: fork`
  scheidet aus, weil der Skill dann das Gespräch mit dem Entwickler nicht führen könnte.

## Betrachtete Wege

| Weg | Inhalt | Bewertung |
|---|---|---|
| A: nur Dokumentation | `CONTRIBUTING.md` und PR-Vorlage | Schnell, blockiert nie. Claude kennt die Regeln nur auf Zuruf, die Spec-Prüfung muss jeder selbst richtig anstoßen. |
| B: Dokumentation plus Werkzeug | A, dazu bindet `.claude/CLAUDE.md` die `CONTRIBUTING.md` ein, und ein Projekt-Skill führt die Spec-Prüfung aus | Regeln an einer Stelle für Menschen und Claude. Verbindlich nur durch Absprache und Checkliste. |
| C: zusätzlich Regeln auf GitHub | B, dazu Rulesets, automatisches Löschen, CI als Pflicht, optional eine automatische Prüfung der Checkliste | Schützt vor Versehen. Jede harte Regel gilt auch für den Maintainer. |

**Gewählt (Maintainer, 17.11.2026): B, ergänzt um drei Punkte aus C:** ein Ruleset für `release/**`, das automatische
Löschen gemergter Branches und die CI als Pflicht. Die automatische Prüfung der Checkliste entfällt, weil sie sich
erst lohnt, wenn wirklich jemand mitentwickelt.

## Die Regeln

Dieser Abschnitt ist der Inhalt, den `.github/CONTRIBUTING.md` in eigenen Worten wiedergibt.

### Branches

| Branch | Zweck | Entsteht | Endet |
|---|---|---|---|
| `main` | enthält nur veröffentlichte Versionen | – | nie |
| `release/<version>` (dreiteilig, z. B. `release/1.2.0`) | sammelt alle Änderungen einer Version; nach der Veröffentlichung Wartungslinie dieser Version | von `main`, sobald die vorige Version veröffentlicht ist | wird gelöscht, sobald die **nächste** Version veröffentlicht ist |
| `<art>/KARV-<nr>-<kurzname>` | eine Änderung | vom Release-Branch, in den sie gehört | automatisch nach dem Merge |

- Arten: `feature`, `bugfix`, `docs`, `test`, `chore`. Gibt es kein Ticket, entfällt `KARV-<nr>-`
  (`<art>/<kurzname>`); das gilt für jede Art.
- Branches, die ein Werkzeug selbst benennt (Claude Code: `claude/…`, `worktree-…`), werden vor dem ersten Push nach
  diesem Schema umbenannt.
- Es gibt damit höchstens zwei Release-Branches gleichzeitig: den der veröffentlichten Version (Wartungslinie) und
  den der Version in Arbeit. **Release-Branch in Arbeit** ist der `release/*` mit der höchsten Version im
  Hauptrepository. Zwischen einer Veröffentlichung und dem Anlegen des nächsten gibt es keinen; dann legt der
  Maintainer zuerst den nächsten an.
- `gh-pages` gehört Conveyor (Download-Seite) und ist kein Teil dieses Modells.
- Jede Änderung kommt per Pull Request in ihren Release-Branch, nie direkt. Das gilt auch für die Vorbereitung eines
  Releases (endgültige Patchnotes, Formatmuster): Branch `chore/release-<version>`.
- **Versionssprung:** Direkt nach dem Anlegen eines Release-Branches setzt ein eigener Pull Request
  (`chore/version-<version>`) die Version hoch. Welche Stellen dazugehören, steht nur in `docs/installation.md`,
  „Releasing", Schritt 1; die `CONTRIBUTING.md` zählt sie nicht auf. Erst danach folgen die Änderungen der Version.

### Merge-Arten

| Pull Request | Merge-Art | Grund |
|---|---|---|
| Arbeits-Branch → `release/<version>` | Squash | ein Commit je Änderung, Betreff ist der PR-Titel |
| `release/<version>` → `main` | Merge-Commit | `main` kennt danach alle Commits der Version; die Wartungslinie bleibt ein Vorfahr von `main` |
| `chore/main-nach-<version>` → `release/<version in Arbeit>` (Vorwärts-Merge, siehe „Bugfixes") | Merge-Commit | ein Squash würde die Abstammung verlieren und beim nächsten Release Konflikte erzeugen |

### Bugfixes

Ein Fehler wird einmal behoben, auf der ältesten betroffenen Linie, die noch gepflegt wird, und von dort nach vorn
gemergt.

- **Wer entscheidet:** Steckt ein Fehler auch in der veröffentlichten Version, entscheidet der Maintainer, ob er eine
  Patch-Version wert ist. Maßstab: Der Fehler stört den Betrieb an der Kasse (Verkauf, Abrechnung, Daten) und kann
  nicht bis zur nächsten Version warten. Sonst wird er nur in der Version in Arbeit behoben.
- **Fehler nur in der Version in Arbeit, oder ohne Patch-Version:** `bugfix/…` vom Release-Branch in Arbeit, Pull
  Request dorthin.
- **Fehler in der veröffentlichten Version, mit Patch-Version:**
  1. Einmal je Patch-Version: Pull Request `chore/version-<patch-version>` in die Wartungslinie (Versionssprung wie
     oben). Weitere Fixes derselben Patch-Version ergänzen nur deren Patchnotes.
  2. `bugfix/…` von der Wartungslinie, Pull Request dorthin (Squash).
  3. Die Patch-Version veröffentlichen wie ein Release, Schritte 1 bis 3 unter „Release" (Vorbereitung
     `chore/release-<patch-version>`, Pull Request Wartungslinie → `main` mit Merge-Commit, Release bauen). Schritt 4
     dort entfällt, die Wartungslinie bleibt bestehen. Weil die Wartungslinie ein Vorfahr von `main` ist, hat der
     Pull Request keine Konflikte.
  4. Vorwärts-Merge: Branch `chore/main-nach-<version in Arbeit>` vom Release-Branch in Arbeit, `main` hineinmergen,
     Pull Request in den Release-Branch in Arbeit mit Merge-Commit. Konflikte in `pom.xml`, `conveyor.conf` und
     `patchnotes.md` sind dabei der Normalfall, sobald die Version in Arbeit hochgesetzt ist. Auflösung: Die Version
     in Arbeit bleibt; in `patchnotes.md` bleiben beide Abschnitte, der der Version in Arbeit steht oben.
- Der Wartungs-Branch behält seinen Namen, auch wenn er eine Patch-Version trägt (`release/1.1.0` liefert 1.1.1).
- Ein Fehler wird nicht von Hand ein zweites Mal behoben. Cherry-Pick nur, wenn ein Vorwärts-Merge nicht möglich
  ist, und dann mit Hinweis im Pull Request.

### Release

Die Zuständigkeit ist geteilt, damit nichts doppelt steht: Die `CONTRIBUTING.md` sagt, **welche Branches wann
gemergt, angelegt und gelöscht werden**. `docs/installation.md`, „Releasing", sagt, **wie ein Release technisch
gebaut wird** (Version, Patchnotes, Formatmuster, Workflow, Fehlerfälle) und verweist für die Branch-Schritte auf die
`CONTRIBUTING.md`.

Branch-Schritte eines Releases:

1. Vorbereitung per Pull Request `chore/release-<version>` in den Release-Branch (endgültige Patchnotes,
   Formatmuster). Vor einer neuen Version außerdem prüfen, dass die Wartungslinie der vorigen Version vollständig in
   `main` enthalten ist (`git log origin/main..origin/release/<alt>` ist leer). Liegt dort ein unveröffentlichter
   Fix, kommt er per Cherry-Pick in einem Bugfix-Pull-Request in den Release-Branch in Arbeit, bevor dieser nach
   `main` geht (der Weg über `main` ist ihm ohne eigene Patch-Version versperrt). Ebenso prüfen, dass kein
   Vorwärts-Merge aussteht (`git log origin/release/<version>..origin/main` ist leer).
2. Pull Request `release/<version>` → `main`, Merge-Commit.
3. Release bauen (Workflow „Release build" auf `main`).
4. Nach der Veröffentlichung einer neuen Version (nicht einer Patch-Version): den Release-Branch der **vorigen**
   Version löschen, `release/<nächste Version>` von `main` anlegen, Versionssprung wie oben. Zum Löschen wird das
   Ruleset `release-nicht-loeschen` kurz abgeschaltet (siehe „Einstellungen auf GitHub", Nr. 3); die
   `CONTRIBUTING.md` nennt diesen Handgriff an dieser Stelle.

### Vor der Entwicklung: Spec

- **Wann:** für jede Änderung, die Verhalten, Datenformat oder Architektur verändert (Features, größere Umbauten).
  Nicht nötig für Bugfixes, die dokumentiertes Verhalten wiederherstellen, und für reine Doku-, Test- und
  Aufräumarbeit. Im Zweifel ein Spec schreiben. Wer keines schreibt, begründet das im Pull Request in einem Satz.
- **Wo:** `docs/specs/JJJJ-MM-TT-KARV-<nr>-<thema>.md` (ohne Ticket: `JJJJ-MM-TT-<thema>.md`), deutsch, als Commit
  auf dem Arbeits-Branch. Das Spec ist Teil des Pull Requests.
- **Aufbau:** Stand-Zeile; Anlass und Ziel; Ziele, Nicht-Ziele, Anti-Ziele; Randbedingungen; Ausgangslage (was das
  Spec über den Bestand voraussetzt); betrachtete Wege und Entscheidung; Entwurf; was der Umsetzung überlassen
  bleibt; Abnahme; Prüfvermerk. Maßgeblich ist die Vorlage des Skills (`.claude/skills/spec/vorlage.md`); die
  `CONTRIBUTING.md` verweist darauf und zählt die Abschnitte nicht selbst auf.
- **Öffentlich:** Das Repository ist öffentlich. Ein Spec enthält keine privaten Daten und keine Zugangsdaten.
- **Momentaufnahme:** Nach dem Merge wird ein Spec nicht mehr gepflegt. Was dauerhaft gilt, kommt in
  `docs/decisions.md` und die übrige Dokumentation.
- **Prüfung:** Zwei voneinander unabhängige Prüfer lesen das Spec, ohne das Gespräch zu kennen, in dem es entstand,
  und ohne die Befunde des anderen zu sehen.
  - Prüfer 1, **Umsetzer:** Könnte ich daraus planen und bauen, ohne nachzufragen? Sucht Lücken, Mehrdeutiges und
    Widersprüche im Spec.
  - Prüfer 2, **Gegenprüfer:** Stimmt, was das Spec über den Bestand behauptet, und passt der Entwurf zu Code,
    Schichtregeln, `docs/decisions.md` und Datenformat? Sucht, was der Entwurf bricht oder übersieht.
  - Jeder Befund trägt eine Stufe:

    | Stufe | Bedeutung |
    |---|---|
    | kritisch | So gebaut entsteht falsches Verhalten oder Datenverlust, eine harte Randbedingung wird verletzt, oder es lässt sich so nicht bauen. |
    | groß | Der Umsetzer müsste raten, und die Antwort ändert, was gebaut wird; oder eine wesentliche Lücke oder ein Widerspruch. |
    | klein | Formulierung, Geschmack, alles, was der Umsetzer selbst sinnvoll entscheiden kann. |

  - Kritische und große Befunde werden im Spec behoben. Danach prüft ein **neues** Paar das geänderte Spec.
  - **Ende der Prüfung:** Es zählt die Einstufung der Prüfer, nicht die des Autors. Die Prüfung ist beendet, wenn in
    einer Runde keiner der beiden Prüfer einen kritischen oder großen Befund meldet. Jede Runde mit einem solchen
    Befund zieht eine weitere nach sich, auch wenn der Autor den Befund für unbegründet hält.
  - Einen Befund, den der Autor nicht übernimmt oder herabstuft, hält er mit Begründung im Prüfvermerk fest. Die
    Prüfer der nächsten Runde lesen den Vermerk als Teil des Specs und können der Begründung folgen oder den Befund
    erneut melden. Der Autor kann die Prüfung also nicht durch Herabstufen beenden.
  - Nach drei Runden mit weiter gemeldeten kritischen oder großen Befunden: abbrechen. Der Maintainer entscheidet
    über die strittigen Befunde. Meist ist das Spec dann zu groß und gehört geteilt.
  - Der **Prüfvermerk** am Ende des Specs nennt Datum, Zahl der Runden, das Ergebnis der letzten Runde und die
    bewusst nicht übernommenen Befunde mit Begründung.
- Ohne Claude Code gilt dieselbe Regel als Ergebnis: zwei unabhängige Prüfer, bis nichts Kritisches oder Großes
  offen ist. Wer prüft (Menschen oder ein anderes Werkzeug), wählt der Entwickler; die Prüfer dürfen das Gespräch
  nicht kennen und einander nicht lesen.
- **Freigabe:** Gebaut wird erst, wenn das Spec geprüft und vom Maintainer freigegeben ist.
  - Ist der Entwickler selbst der Maintainer, genügt seine Freigabe im Gespräch.
  - Sonst öffnet der Entwickler einen Pull Request als Entwurf, der nur das Spec enthält, auf den Release-Branch in
    Arbeit (ohne Schreibrecht aus einem Fork). Der Maintainer gibt mit einem zustimmenden Review frei. Die
    Entwicklung läuft danach auf demselben Branch und im selben Pull Request weiter.

### Nach der Entwicklung

In dieser Reihenfolge:

1. `/simplify` (vereinfacht den geänderten Code). Zuerst, damit sich der Code nach dem Review nicht noch einmal
   ändert.
2. `/code-review` (sucht Fehler im Diff). Befunde beheben oder im Pull Request begründen, warum nicht.
3. Alle Tests grün (`./mvnw test`), betroffene Dokumentation unter `docs/` nachgezogen. Ein Eintrag in
   `patchnotes.md` gehört dazu, wenn die Änderung das ausgelieferte Programm betrifft (vorläufiger Text genügt bis
   zum Release, siehe `docs/installation.md`); reine Doku-, Test- und Aufräumarbeit braucht keinen.
4. Pull Request mit ausgefüllter Vorlage.

Ändert ein Pull Request keinen Code (nur Dokumentation oder Specs), entfallen die Schritte 1 und 2; die Checkliste
nennt das. Für Pull Requests, die nur den Ablauf tragen (Versionssprung, Release-Vorbereitung, Release nach `main`,
Vorwärts-Merge), gilt von der Checkliste nur der letzte Punkt (Ziel-Branch und Merge-Art), dazu grüne Tests.

### Commits und Pull Requests

- Commit-Betreff `KARV-<nr>: …`, wenn es ein Ticket gibt. Deutsch.
- PR-Titel, er wird beim Squash zum Commit-Betreff:

  | Pull Request | Titel |
  |---|---|
  | Änderung mit Ticket | `KARV-<nr>: <Titel> (v<Version>)` |
  | Änderung ohne Ticket | `<Titel> (v<Version>)` |
  | Release nach `main` | `Release <version>` |
  | Vorwärts-Merge | `main nach release/<version>`, nach einer Patch-Version mit dem Zusatz `(<patch-version>)` |

- PR-Beschreibung nach der Vorlage.

### Sprache

Code, Kommentare, Meldungen, Commits, Pull Requests, Tickets und Specs (`docs/specs/`) sind deutsch. Die
beschreibende Dokumentation (`README.md`, `docs/*.md`) ist englisch. Die `.claude/CLAUDE.md` verweist darauf, statt
die Regel selbst zu nennen.

## Übergang und Reihenfolge

Dieser Abschnitt gilt nur für die Einführung. Nichts davon kommt in die `CONTRIBUTING.md`.

**Reihenfolge.** Die `CONTRIBUTING.md` beschreibt nur, was wirklich gilt. Deshalb:

1. Die Dateien dieses Pull Requests entstehen im Branch, die `CONTRIBUTING.md` zunächst ohne den Abschnitt
   „Einstellungen am Repository".
2. Die Einstellungen auf GitHub werden gesetzt und die Proben gemacht (siehe „Einstellungen auf GitHub").
3. Der einmalige Merge aus „Altlast" geschieht, danach der Vorwärts-Merge von `main` nach `release/1.2.0`.
4. Erst dann wird der Abschnitt „Einstellungen am Repository" geschrieben, als Ist-Zustand mit dem Ergebnis der
   Proben und den Entscheidungen des Maintainers (Ausnahme auf `main`, Pflicht-Check auf `main`). Fällt eine Probe
   anders aus als erwartet, werden die betroffenen Stellen der `CONTRIBUTING.md` angepasst (z. B. „Delete branch" von
   Hand statt automatisch).
5. Danach wird der Pull Request gemergt.

Die Schritte 2 und 3 löst der Maintainer aus oder gibt sie ausdrücklich frei. Will er den Pull Request früher
mergen, bleibt der Abschnitt „Einstellungen am Repository" weg und kommt mit einem Folge-Pull-Request; eine
`CONTRIBUTING.md`, die Schutzregeln behauptet, die es nicht gibt, ist ausgeschlossen. In diesem Fall werden auch die
Stellen, die eine Einstellung voraussetzen, neutral gefasst und mit dem Folge-Pull-Request nachgezogen: das
automatische Löschen gemergter Branches, der PR-Titel als Betreff des Squash-Commits und der Handgriff mit
`release-nicht-loeschen`.

**Ab wann die Regeln gelten.**

- GitHub nimmt PR-Vorlage und Contributing-Hinweis vom Standard-Branch. Beide wirken auf der Website deshalb erst,
  wenn Version 1.2.0 auf `main` liegt. Das wird hingenommen: `main` enthält nur veröffentlichte Versionen, und eine
  Ausnahme dafür wäre die erste Abweichung von den eigenen Regeln.
- Bis dahin gelten die Regeln für alles, was von `release/1.2.0` abzweigt; dort liegen `CONTRIBUTING.md`, Vorlage
  und Skill im Arbeitsverzeichnis. Claude füllt die Vorlage aus der Datei aus.
- `release/1.1.0` enthält die Dateien nicht. Für einen Fehler dort gelten die Regeln dem Sinn nach, nachzulesen auf
  `release/1.2.0`.

## Entwurf: Dateien im Pull Request

### `.github/CONTRIBUTING.md` (neu, deutsch)

Die eine Stelle für die Regeln oben. Gliederung: Kurzfassung (der ganze Ablauf in wenigen Zeilen); Branches und
Merge-Arten; Ablauf einer Änderung (Spec, Entwicklung, `/simplify`, `/code-review`, Pull Request); Bugfixes und
Patch-Versionen; Release (Verweis auf `docs/installation.md`); Commits und Pull Requests; Arbeiten mit Claude Code
(die drei Befehle `/spec`, `/simplify`, `/code-review`, Hinweis auf `.claude/CLAUDE.md`); Einstellungen am Repository
(welche Rulesets es gibt und wozu, damit ein späterer Maintainer sie versteht).

Deutsch, weil alle Arbeitsartefakte deutsch sind (Commits, Pull Requests, Tickets, Specs) und wer mitentwickelt,
ohnehin deutschen Code liest.

### `.github/pull_request_template.md` (neu, deutsch)

```
## Was und warum

KARV-xx: …

## Checkliste

- [ ] Spec unter `docs/specs/…` geschrieben und von zwei unabhängigen Prüfern geprüft – oder kein Spec nötig, weil: …
- [ ] `/simplify` gelaufen – oder entfällt, weil kein Code geändert ist
- [ ] `/code-review` gelaufen, Befunde behoben oder unten begründet – oder entfällt, weil kein Code geändert ist
- [ ] Tests grün, Dokumentation nachgezogen, Patchnotes ergänzt (wenn das ausgelieferte Programm betroffen ist)
- [ ] Richtiger Ziel-Branch und richtige Merge-Art (siehe CONTRIBUTING)
```

GitHub füllt damit die Beschreibung neuer Pull Requests vor. `gh pr create --body …` übergeht die Vorlage; deshalb
steht in `.claude/CLAUDE.md`, dass Claude die Vorlage liest und ausfüllt.

### `.claude/skills/spec/` (neu, deutsch)

Ein Projekt-Skill, Aufruf `/spec <Ticket oder Thema>`. Er liegt im Repository und steht damit jedem zur Verfügung,
der es mit Claude Code öffnet.

| Datei | Inhalt |
|---|---|
| `SKILL.md` | Ablauf (unten), Frontmatter mit `name: spec` und einer Beschreibung, wann der Skill gilt |
| `vorlage.md` | Gerüst eines Specs mit den Abschnitten aus „Aufbau" |
| `pruefauftrag-umsetzer.md` | Auftrag für Prüfer 1 |
| `pruefauftrag-gegenpruefer.md` | Auftrag für Prüfer 2 |

Ablauf, den `SKILL.md` vorgibt:

1. Mit dem Entwickler klären, was gebaut werden soll und warum. Kein Code.
2. Den betroffenen Code und die passenden Dateien unter `docs/` lesen.
3. Die Wege vorstellen, der Entwickler wählt.
4. Sicherstellen, dass ein Arbeits-Branch besteht. Steht der Entwickler auf einem Release-Branch oder auf `main`,
   schlägt der Skill Namen und Basis vor und legt den Branch nach dem Ja des Entwicklers an. Basis ist der
   Release-Branch in Arbeit, wie ihn die `CONTRIBUTING.md` bestimmt; der Skill holt dafür den Stand des
   Hauptrepositorys (bei einem Fork ist das nicht `origin`, und ein Fork enthält zunächst nur `main`). Steht der Entwickler auf
   einem Arbeits-Branch, der zu etwas anderem gehört, oder auf einem Branch, dessen Name nicht ins Schema passt
   (`claude/…`, `worktree-…`), fragt der Skill nach.
5. Das Spec nach `vorlage.md` unter `docs/specs/` schreiben.
6. Zwei Subagents gleichzeitig starten, je einen mit einem der beiden Prüfaufträge. Jeder bekommt nur den Pfad des
   Specs und Zugriff auf das Repository, keine Zusammenfassung des Gesprächs.
7. Kritische und große Befunde beheben; was nicht übernommen oder herabgestuft wird, mit Begründung in den
   Prüfvermerk.
8. Mit einem neuen Paar wiederholen. Ende und Abbruch nach der Regel „Ende der Prüfung" in der `CONTRIBUTING.md`.
9. Prüfvermerk schreiben, das Spec auf dem Arbeits-Branch committen und dem Entwickler zeigen. Für die Freigabe gilt
   die Regel „Freigabe"; der Skill nennt dem Entwickler den passenden Weg und pusht nicht von sich aus.
10. Vor der Freigabe wird nichts gebaut.

Sind Ziel, Grund und Weg im Gespräch schon geklärt (etwa durch das Brainstorming des Superpowers-Plugins), beginnt
der Skill bei Schritt 4. Das steht so in `SKILL.md`.

Damit nichts doppelt steht: Stufen, Ende der Prüfung und Freigabe stehen nur in der `CONTRIBUTING.md`. `SKILL.md`
verweist darauf, und die Prüfaufträge weisen den Prüfer an, die Stufen dort im Abschnitt zur Spec-Prüfung
nachzulesen. Der Aufbau eines Specs steht nur in `vorlage.md`; die `CONTRIBUTING.md` verweist auf die Vorlage.

Die Prüfaufträge verlangen als Antwort eine Liste von Befunden mit Stufe, Fundstelle im Spec und Begründung, oder
den Satz „nichts Kritisches oder Großes".

### `.claude/CLAUDE.md` (ändern)

- Bindet `.github/CONTRIBUTING.md` ein, statt Regeln zu wiederholen: eine eigene Zeile `@../.github/CONTRIBUTING.md`
  (Pfad ab `.claude/`), außerhalb von Code-Blöcken.
- Der Abschnitt „Arbeitsweise" behält nur, was allein Claude betrifft: Push und Pull Request nur auf ausdrückliche
  Anweisung; Zeilenenden und Edit-Werkzeug; parallele Arbeit in Eclipse; PR-Vorlage lesen und ausfüllen. Branch- und
  Commit-Regeln entfallen dort, sie stehen in der `CONTRIBUTING.md`.
- Der Absatz „Neue Version: …" (heute Zeile 25–27) zählt die Stellen der Version nicht mehr auf, sondern verweist
  auf `docs/installation.md`, „Releasing".
- Der Satz zur Sprache (heute Zeile 7–8) wird zu einem Verweis auf die Sprachregel der `CONTRIBUTING.md`.
- Tabelle „Lokale Ablage": Die Zeile `.local/superpowers/specs/` heißt künftig „alte Specs (bis 16.11.2026); neue
  liegen in `docs/specs/`". Der Satz zu den Standardpfaden der Superpowers-Skills (heute Zeile 58–60) lautet dann:
  Specs gehören nach `docs/specs/`, Pläne und Arbeitsstände nach `.local/superpowers/`, nichts nach
  `docs/superpowers/` oder `.superpowers/`. Der Satz, dass Skripte der Skills mit festen Pfaden nicht benutzt werden,
  bleibt.
- Neu: Wer das Superpowers-Plugin nutzt, führt das Gespräch mit dessen Brainstorming; Ablageort, Aufbau, Prüfung und
  Freigabe des Specs richten sich nach der `CONTRIBUTING.md`, ausgeführt mit dem Skill `spec` (zwei Prüfer statt des
  einen Prüfers des Plugins).

### `.gitignore` (ändern)

Der Kommentar über `.local/` nennt „Specs" nicht mehr als lokale Ablage.

### Dokumentation (ändern, englisch)

- `README.md`: Zeile in der Tabelle „Documentation" mit Link auf `.github/CONTRIBUTING.md` und dem Hinweis, dass die
  Datei deutsch ist.
- `docs/installation.md`, „Releasing": Der erste Satz („Pull requests of a version go to …") wird durch einen Verweis
  auf die `CONTRIBUTING.md` ersetzt, die sagt, welche Branches wann gemergt, angelegt und gelöscht werden. Die
  technischen Schritte bleiben. Branch-Regeln werden dort nicht ausformuliert; Schritt 2 nennt nur noch den Zeitpunkt
  („before the pull request to `main`") und verweist für den Pull Request selbst auf die `CONTRIBUTING.md`. Der
  Verweis am Anfang sagt in einem Halbsatz, dass auch die Schritte 1 bis 3 über Pull Requests laufen; wie, steht in
  der `CONTRIBUTING.md`. Der Befehl in Schritt 2 wird zu `git log --first-parent <previous tag>..HEAD`, weil mit
  Merge-Commits sonst Commits älterer Versionen als neu erscheinen. Nebenbei berichtigt: der Satz „For 1.1.0 this
  sample does not exist yet (only `1.0.1`)" in Schritt 3 (das Muster für 1.1.0 liegt im Repository).
- `docs/structure.md`, Zeile mit `format-samples`: „(1.0.1 so far)" wird berichtigt, aus demselben Grund.
- `conveyor.conf`, Kommentar in Zeile 1–9: bleibt unverändert. Er nennt technische Schritte direkt neben dem Wert,
  verweist schon auf `docs/installation.md`, und seine Aussagen bleiben richtig.
- `docs/structure.md`: Im Baum `.claude/skills/spec/`, `.github/CONTRIBUTING.md`, `.github/pull_request_template.md`
  und `docs/specs/`; im Absatz über ignorierte Dateien entfällt „working specs".
- `docs/decisions.md`: ein neuer Abschnitt „Development workflow" am Ende der Datei mit den Gründen: Release-Branch
  je Version und Wartungslinie bis zur nächsten Version; Merge-Commit nach `main`, Squash in den Release-Branch;
  Specs im Repository mit zwei unabhängigen Prüfern; Regeln in der `CONTRIBUTING.md`, nicht in Plugins. Er nennt
  Gründe und verweist für die Regeln selbst auf die `CONTRIBUTING.md`. Die Stand-Zeile und die Tabelle „added or
  changed in 1.1" bleiben; sie werden mit der Dokumentation zu 1.2.0 aktualisiert.
- Die Dateien unter `docs/` und die `README.md` wurden mit `/makeDoc` erzeugt. Ein neuer Lauf kann von Hand
  ergänzte Stellen überschreiben; nach einem solchen Lauf sind die hier ergänzten Stellen zu prüfen.

### `docs/specs/` (neu)

Enthält als erstes dieses Spec. Das Design des Aufräumens vom 16.11.2026 bleibt lokal; es entstand vor dieser Regel.

## Entwurf: Einstellungen auf GitHub

Nicht Teil des Pull Requests. Wer sie setzt, steht in den Randbedingungen.

1. **Ruleset `onlyMerge` (`main`):** erlaubte Merge-Art von `squash` auf `merge` umstellen. Das übernimmt der
   Maintainer. Empfohlen zusätzlich: Status-Check `test` als Pflicht, und die Ausnahme für „Repository admin"
   entfernen. Solange sie besteht, schützt das Ruleset den Maintainer (und Claude, der mit seinen Zugangsdaten
   arbeitet) nicht vor einem versehentlichen direkten Push nach `main`. Ob sie entfällt, entscheidet der Maintainer.
2. **Neues Ruleset `release-branches`**, Ziel `refs/heads/release/**`, ohne Ausnahmen:
   - Pull Request nötig (0 Freigaben), erlaubte Merge-Arten `squash` und `merge`
   - Force-Push gesperrt
   - Status-Check `test` als Pflicht (ohne „Branch muss aktuell sein"), mit der Option, den Check beim Anlegen eines
     Branches nicht zu verlangen (`do_not_enforce_on_create`), damit sich `release/<nächste Version>` anlegen lässt
3. **Neues Ruleset `release-nicht-loeschen`**, Ziel `refs/heads/release/**`: Löschen gesperrt, **ohne Ausnahmen**.
   Getrennt von Nr. 2, damit es sich einzeln abschalten lässt: Um den Release-Branch der vorigen Version zu löschen,
   stellt der Maintainer dieses eine Ruleset kurz auf „Disabled", löscht den Branch und stellt es wieder auf
   „Active". Eine Ausnahme für den Admin scheidet aus: Nutzer berichten (GitHub Community, Discussion 152926), dass
   das automatische Löschen einen so geschützten Branch entfernt, wenn der Mergende eine Ausnahme hat.
4. **„Automatically delete head branches"** einschalten (Settings → General → Pull Requests).
5. **Titel der Commits** (Settings → General → Pull Requests): bei „Allow squash merging" die Vorgabe „Pull request
   title" (`squash_merge_commit_title: PR_TITLE`), bei „Allow merge commits" ebenfalls „Pull request title"
   (`merge_commit_title: PR_TITLE`). Damit heißt ein Squash-Commit immer wie der Pull Request und der Merge-Commit
   eines Releases „Release <version>". Wer das über die API setzt, setzt `merge_commit_message` mit (`PR_BODY` oder
   `BLANK`); die Oberfläche tut das von selbst.

Die Rulesets unterscheiden nicht nach Quell-Branch. Sie verhindern deshalb nicht, dass ein Vorwärts-Merge gesquasht,
ein Arbeits-Branch mit Merge-Commit gemergt oder ein Arbeits-Branch direkt nach `main` gemergt wird. Dafür bleibt
die Checkliste der PR-Vorlage („richtiger Ziel-Branch und richtige Merge-Art"). Die `CONTRIBUTING.md` sagt das im
Abschnitt „Einstellungen am Repository".

Reihenfolge: Nr. 1, 2, 3 und 5; dann die Probe mit `release/0.0.0` (unten); dann Nr. 4; dann der einmalige Merge aus
„Altlast". Dieser Merge ist zugleich die Probe für das automatische Löschen: Auf `release/1.1.0` zeigt kein offener
Pull Request, ein versehentlich gelöschter Branch lässt sich dort gefahrlos über „Restore branch" zurückholen.

Nicht belegt und beim Einrichten zu prüfen:

- Ob Nr. 3 das automatische Löschen aus Nr. 4 für Release-Branches verhindert. Die GitHub-Doku sagt nur allgemein,
  dass Rulesets das automatische Löschen verhindern können. Prüfung mit dem Altlast-Merge (oben). Wird der Branch
  trotzdem gelöscht: wiederherstellen und Nr. 4 ausschalten; gemergte Arbeits-Branches löscht dann der Knopf
  „Delete branch" am Pull Request.
- Ob sich mit Nr. 2 ein neuer Branch `release/<nächste Version>` von `main` anlegen lässt (Pflicht-Check und
  PR-Pflicht beim Anlegen). Prüfung: direkt nach dem Einrichten einen Probe-Branch `release/0.0.0` von `main`
  anlegen; danach Nr. 3 kurz abschalten und ihn wieder löschen. Lässt er sich nicht anlegen: Nr. 2 zum Anlegen eines
  Release-Branches kurz abschalten, und diesen Schritt in der `CONTRIBUTING.md` unter „Einstellungen am Repository"
  festhalten.

## Altlast `release/1.1.0`

Weil 1.1.0 per Squash nach `main` kam, ist `release/1.1.0` kein Vorfahr von `main`. Der gemeinsame Vorfahr ist
`dc79071`, dort steht in `pom.xml` noch 1.0.1. Ein Pull Request `release/1.1.0` → `main` mit einer Patch-Version
hätte deshalb sicher Konflikte in `pom.xml`, `conveyor.conf` und `patchnotes.md`: Der Bugfix-Weg oben funktioniert
für die heutige Wartungslinie nicht. Außerdem verschwänden beim späteren Löschen von `release/1.1.0` die
Einzel-Commits der Version aus der Branch-Historie (in den geschlossenen Pull Requests bleiben sie sichtbar).
`src/test/resources/format-samples/1.1.0/README.md` verweist zudem auf den Commit `5fbb07b`, der heute von `main`
aus nicht erreichbar ist.

**Einmalige Voraussetzung**, nicht Teil dieses Pull Requests: Sobald `onlyMerge` Merge-Commits erlaubt, wird
`release/1.1.0` → `main` mit Merge-Commit gemergt. Der Inhalt beider Branches ist identisch, der Merge ändert keine
Datei; `main` kennt danach die 15 Commits, und die Wartungslinie ist ein Vorfahr von `main`. Danach `main` per
Vorwärts-Merge nach `release/1.2.0` (über `main`, nicht `release/1.1.0` direkt: das gäbe Konflikte). Der Pull
Request `release/1.1.0` → `main` zeigt auf GitHub rund 320 geänderte Dateien, weil GitHub gegen den gemeinsamen
Vorfahren vergleicht; der Vorwärts-Merge zeigt 0 geänderte Dateien bei 17 Commits. Beide PR-Texte sagen, dass der
Merge keine Datei ändert. Den Merge nach `main` löst der Maintainer aus oder gibt ihn ausdrücklich frei.

Der Tag `1.1.0` liegt auf dem Squash-Commit und bleibt auch danach kein Vorfahr von `release/1.1.0`. Für die
Patchnotes einer Version 1.1.1 zählen deshalb die Commits auf `release/1.1.0` seit `825d66d`, nicht
`git log 1.1.0..HEAD`.

Auf GitHub liegt außerdem der Branch `claude/quirky-brahmagupta-uesnyl` (Roadmap, kein Pull Request). Er entstand vor
diesen Regeln und wird hier nicht angefasst; bevor aus ihm ein Pull Request wird, bekommt er einen Namen nach dem
Schema.
Bis dahin gilt für einen Fehler in 1.1.0: erst diese Voraussetzung schaffen, dann der Bugfix-Weg.

## Der Umsetzung überlassen

- Wortlaut der `CONTRIBUTING.md`, der Prüfaufträge, der Vorlage und des Eintrags in `docs/decisions.md`.
- Der Nachweis, dass die Einbindung greift: In einer neuen Sitzung im Repository kennt Claude eine Regel, die nur in
  der `CONTRIBUTING.md` steht. Die Import-Zeile ist aus der dokumentierten Regel abgeleitet, nicht aus einem
  dokumentierten Beispiel.
- Lokale Notizen (Memory) des Maintainers anpassen; sie liegen außerhalb des Repositorys.

Nicht Teil dieses Pull Requests, aber Folge der Regeln: der Versionssprung auf 1.2.0 (`chore/version-1.2.0`), der auf
`release/1.2.0` noch aussteht. Dieser Pull Request ändert kein ausgeliefertes Programm und braucht ihn nicht.

## Abnahme

- `.github/CONTRIBUTING.md` enthält jede Regel aus „Die Regeln", mit zwei Verweisen statt eigener Aufzählung: der
  Aufbau eines Specs (Vorlage des Skills) und die Stellen des Versionssprungs (`docs/installation.md`).
  `.claude/CLAUDE.md`, `docs/installation.md` und die Dateien des Skills formulieren keine Regel aus, sondern
  verweisen. Ein Verweis darf sagen, worum es geht und wo eine Datei liegt (etwa die Tabelle der Ablageorte in der
  `CLAUDE.md`); er legt nichts fest, was nicht in der `CONTRIBUTING.md` steht. Die beiden Prüfaufträge führen die
  Prüferrollen aus und sind keine zweite Regelquelle.
- Der Abschnitt „Einstellungen am Repository" der `CONTRIBUTING.md` beschreibt den Stand, der beim Merge wirklich
  gilt, oder fehlt (siehe „Übergang und Reihenfolge").
- Die PR-Vorlage liegt unter `.github/pull_request_template.md`. Dass GitHub sie anzeigt, lässt sich erst prüfen,
  wenn sie auf `main` liegt (siehe „Ab wann die Regeln gelten"); das ist keine Bedingung für diesen Pull Request.
- Probelauf des Skills in einer **neuen Claude-Code-Sitzung** im Arbeitsverzeichnis des Branches, nicht in einem
  Subagent (der kann womöglich keine weiteren Subagents starten und hat keinen Entwickler als Gegenüber): `/spec`
  mit einer kleinen erfundenen Änderung. Bestanden, wenn der Skill gefunden wird, das Spec unter `docs/specs/` nach
  der Vorlage entsteht und zwei Prüfer mit den beiden Aufträgen starten. Der Probelauf endet vor Schritt 9 des
  Skills; das Probe-Spec wird gelöscht und nicht committet.
- In derselben neuen Sitzung nennt Claude auf Nachfrage eine Regel, die nur in der `CONTRIBUTING.md` steht (Beleg
  für die Einbindung). Dabei mit `/memory` nachsehen, welche Anleitungen geladen sind: Eine Sitzung in einem
  Worktree unter `.claude/worktrees/` kann zusätzlich die Anleitung des Hauptverzeichnisses laden, also die eines
  anderen Branch-Stands.
- In der Projektwurzel ist kein Eintrag dazugekommen.
- `./mvnw test` ist so grün wie vorher (es ändert sich kein Produktcode).

## Prüfvermerk

**Runde 1 (17.11.2026):** Umsetzer sieben große Befunde, Gegenprüfer sieben große Befunde, nichts Kritisches. Alle
sind im Spec behoben, mit zwei bewussten Abweichungen:

- **Regeln und Vorlage vorab nach `main` bringen** (Umsetzer, als Möglichkeit genannt): nicht übernommen. Die
  Verzögerung bis 1.2.0 wird hingenommen, siehe „Ab wann die Regeln gelten". Ein Sonderweg nach `main` widerspräche
  der Regel, dass `main` nur veröffentlichte Versionen enthält.
- **Kommentar in `conveyor.conf` als vierte Nennung der Versionsstellen** (Gegenprüfer, Teil eines großen Befunds):
  nicht geändert. Der Kommentar steht direkt neben dem Wert, den er erklärt, verweist auf `docs/installation.md` und
  bleibt richtig. Die drei anderen Nennungen sind auf eine zurückgeführt (`docs/installation.md`).

**Runde 2 (17.11.2026):** Umsetzer ein großer Befund (Reihenfolge von Einstellungen, Altlast und Pull Request;
behoben mit dem Abschnitt „Übergang und Reihenfolge"), Gegenprüfer nichts Kritisches oder Großes. Die kleinen
Befunde beider Prüfer sind eingearbeitet. Bewusst nicht geändert: `docs/decisions.md`, Abschnitt „Patch notes",
nennt den Zeitpunkt der endgültigen Patchnotes ebenfalls; die Stelle begründet eine Entscheidung und widerspricht
den Regeln nicht.

**Runde 3 (17.11.2026):** Umsetzer und Gegenprüfer melden beide nichts Kritisches oder Großes. Die Prüfung ist damit
nach drei Runden beendet. Die kleinen Befunde der Runde sind eingearbeitet (Arten ohne Ticket, Prüfungen vor dem
Release, Sprachregel in der `CONTRIBUTING.md`, früher Merge, Bestimmung des Release-Branches in Arbeit, Angaben zur
Altlast).

Nicht belegt bleiben zwei Punkte zum Verhalten von GitHub; für beide nennt „Einstellungen auf GitHub" eine Probe und
einen Rückfall.
