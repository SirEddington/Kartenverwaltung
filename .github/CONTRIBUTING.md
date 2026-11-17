# Mitarbeiten an der Kartenverwaltung

Diese Datei ist die eine Stelle für die Regeln, nach denen hier entwickelt wird. Sie gilt für Menschen und für
Claude Code gleichermaßen. Wie man das Projekt baut und startet, steht in
[docs/installation.md](../docs/installation.md).

„Maintainer" ist der Inhaber des Repositorys. Er gibt Specs frei und entscheidet über Patch-Versionen.

## Kurzfassung

1. Jede Version hat einen Branch `release/<version>`. Jede Änderung entsteht auf einem eigenen Arbeits-Branch davon
   und kommt per Pull Request zurück.
2. Vor einer Änderung an Verhalten, Datenformat oder Architektur steht ein Spec unter `docs/specs/`. Zwei
   unabhängige Prüfer lesen es, der Maintainer gibt es frei, erst dann wird gebaut.
3. Nach der Entwicklung: `/simplify`, dann `/code-review`, Tests grün, Dokumentation nachgezogen.
4. Pull Request mit ausgefüllter Vorlage, in den richtigen Branch und mit der richtigen Merge-Art.
5. `main` enthält nur veröffentlichte Versionen.

## Branches

| Branch | Zweck | Entsteht | Endet |
|---|---|---|---|
| `main` | enthält nur veröffentlichte Versionen | – | nie |
| `release/<version>` (dreiteilig, z. B. `release/1.2.0`) | sammelt alle Änderungen einer Version; nach der Veröffentlichung ist er die Wartungslinie dieser Version | von `main`, sobald die vorige Version veröffentlicht ist | wird gelöscht, sobald die **nächste** Version veröffentlicht ist |
| `<art>/KARV-<nr>-<kurzname>` | eine Änderung | vom Release-Branch, in den sie gehört | wird nach dem Merge automatisch gelöscht |

- **Arten:** `feature`, `bugfix`, `docs`, `test`, `chore`. Gibt es kein Ticket, entfällt `KARV-<nr>-`
  (`<art>/<kurzname>`).
- **Höchstens zwei Release-Branches gleichzeitig:** die Wartungslinie der veröffentlichten Version und der
  Release-Branch der Version in Arbeit.
- **Release-Branch in Arbeit** ist der `release/*` mit der höchsten Version im Hauptrepository. Zwischen einer
  Veröffentlichung und dem Anlegen des nächsten gibt es keinen; dann legt der Maintainer zuerst den nächsten an.
- **Nie direkt:** Jede Änderung kommt per Pull Request in ihren Release-Branch. Das gilt auch für den Versionssprung
  und für die Vorbereitung eines Releases.
- **Versionssprung:** Direkt nach dem Anlegen eines Release-Branches setzt ein eigener Pull Request
  (`chore/version-<version>`) die Version hoch. Welche Stellen dazugehören, steht in
  [docs/installation.md](../docs/installation.md), „Releasing", Schritt 1. Erst danach folgen die Änderungen der
  Version.
- **Namen von Werkzeugen:** Branches, die ein Werkzeug selbst benennt (Claude Code: `claude/…`, `worktree-…`), werden
  vor dem ersten Push nach dem Schema oben umbenannt.
- `gh-pages` gehört Conveyor (Download-Seite) und ist kein Teil dieses Modells.

## Merge-Arten

| Pull Request | Merge-Art | Grund |
|---|---|---|
| Arbeits-Branch → `release/<version>` | Squash | ein Commit je Änderung |
| `release/<version>` → `main` | Merge-Commit | `main` kennt danach alle Commits der Version, und die Wartungslinie bleibt ein Vorfahr von `main` |
| `chore/main-nach-<version>` → `release/<version>` (Vorwärts-Merge, siehe [Bugfixes](#bugfixes-und-patch-versionen)) | Merge-Commit | ein Squash würde die Abstammung verlieren und beim nächsten Release Konflikte erzeugen |

Nach `main` lässt GitHub nur den Merge-Commit zu. In einen Release-Branch sind Squash und Merge-Commit möglich, und
GitHub unterscheidet dabei nicht nach dem Quell-Branch; die Merge-Art wählt dort, wer mergt. Deshalb steht die Frage
in der Checkliste jedes Pull Requests.

## Ablauf einer Änderung

Spec schreiben und prüfen lassen, Freigabe abwarten, entwickeln, vereinfachen, reviewen, Pull Request.

### Spec

- **Wann:** für jede Änderung, die Verhalten, Datenformat oder Architektur verändert (Features, größere Umbauten).
  Nicht nötig für Bugfixes, die dokumentiertes Verhalten wiederherstellen, und für reine Doku-, Test- und
  Aufräumarbeit. Im Zweifel ein Spec schreiben. Wer keines schreibt, begründet das im Pull Request in einem Satz.
- **Wo:** `docs/specs/JJJJ-MM-TT-KARV-<nr>-<thema>.md` (ohne Ticket: `JJJJ-MM-TT-<thema>.md`), als Commit auf dem
  Arbeits-Branch. Das Spec ist Teil des Pull Requests.
- **Aufbau:** nach der Vorlage [.claude/skills/spec/vorlage.md](../.claude/skills/spec/vorlage.md).
- **Öffentlich:** Das Repository ist öffentlich. Ein Spec enthält keine privaten Daten und keine Zugangsdaten.
- **Momentaufnahme:** Nach dem Merge wird ein Spec nicht mehr gepflegt. Was dauerhaft gilt, kommt in
  [docs/decisions.md](../docs/decisions.md) und die übrige Dokumentation.

### Spec-Prüfung

Zwei voneinander unabhängige Prüfer lesen das Spec. Sie kennen das Gespräch nicht, in dem es entstand, und sehen die
Befunde des anderen nicht.

- **Prüfer 1, Umsetzer:** Könnte ich daraus planen und bauen, ohne nachzufragen? Sucht Lücken, Mehrdeutiges und
  Widersprüche im Spec.
- **Prüfer 2, Gegenprüfer:** Stimmt, was das Spec über den Bestand behauptet, und passt der Entwurf zu Code,
  Schichtregeln, `docs/decisions.md` und Datenformat? Sucht, was der Entwurf bricht oder übersieht.

Jeder Befund trägt eine Stufe:

| Stufe | Bedeutung |
|---|---|
| kritisch | So gebaut entsteht falsches Verhalten oder Datenverlust, eine harte Randbedingung wird verletzt, oder es lässt sich so nicht bauen. |
| groß | Der Umsetzer müsste raten, und die Antwort ändert, was gebaut wird; oder eine wesentliche Lücke oder ein Widerspruch. |
| klein | Formulierung, Geschmack, alles, was der Umsetzer selbst sinnvoll entscheiden kann. |

- **Runden:** Kritische und große Befunde werden im Spec behoben. Danach prüft ein **neues** Paar das geänderte Spec.
- **Ende der Prüfung:** Es zählt die Einstufung der Prüfer, nicht die des Autors. Die Prüfung ist beendet, wenn in
  einer Runde keiner der beiden Prüfer einen kritischen oder großen Befund meldet. Jede Runde mit einem solchen
  Befund zieht eine weitere nach sich, auch wenn der Autor den Befund für unbegründet hält.
- **Nicht übernommene Befunde:** Was der Autor nicht übernimmt oder herabstuft, hält er mit Begründung im
  Prüfvermerk fest. Die Prüfer der nächsten Runde lesen den Vermerk als Teil des Specs und können der Begründung
  folgen oder den Befund erneut melden.
- **Abbruch:** Melden die Prüfer nach drei Runden weiter Kritisches oder Großes, endet die Prüfung, und der
  Maintainer entscheidet über die strittigen Befunde. Meist ist das Spec dann zu groß und gehört geteilt.
- **Prüfvermerk:** Er steht am Ende des Specs und nennt je Runde Datum und Ergebnis, die bewusst nicht übernommenen
  Befunde mit Begründung und was nicht belegt werden konnte.
- **Ohne Claude Code** gilt dieselbe Regel als Ergebnis. Wer prüft (Menschen oder ein anderes Werkzeug), wählt der
  Entwickler; die Prüfer dürfen das Gespräch nicht kennen und einander nicht lesen.

### Freigabe

Gebaut wird erst, wenn das Spec geprüft und vom Maintainer freigegeben ist.

- Ist der Entwickler selbst der Maintainer, genügt seine Freigabe im Gespräch.
- Sonst öffnet der Entwickler einen Pull Request als Entwurf, der nur das Spec enthält, auf den Release-Branch in
  Arbeit (ohne Schreibrecht aus einem Fork). Der Maintainer gibt mit einem zustimmenden Review frei. Die Entwicklung
  läuft danach auf demselben Branch und im selben Pull Request weiter.

### Nach der Entwicklung

In dieser Reihenfolge:

1. `/simplify` vereinfacht den geänderten Code. Zuerst, damit sich der Code nach dem Review nicht noch einmal
   ändert.
2. `/code-review` sucht Fehler im Diff. Befunde beheben oder im Pull Request begründen, warum nicht.
3. Alle Tests grün (`./mvnw test`), betroffene Dokumentation unter `docs/` nachgezogen. Ein Eintrag in den
   Patchnotes gehört dazu, wenn die Änderung das ausgelieferte Programm betrifft (ein vorläufiger Text genügt bis
   zum Release); reine Doku-, Test- und Aufräumarbeit braucht keinen.
4. Pull Request mit ausgefüllter Vorlage.

Ändert ein Pull Request keinen Code (nur Dokumentation oder Specs), entfallen die Schritte 1 und 2. Für Pull
Requests, die nur den Ablauf tragen (Versionssprung, Release-Vorbereitung, Release nach `main`, Vorwärts-Merge),
gilt von der Checkliste nur der letzte Punkt, dazu grüne Tests.

## Bugfixes und Patch-Versionen

Ein Fehler wird einmal behoben, auf der ältesten betroffenen Linie, die noch gepflegt wird, und von dort nach vorn
gemergt.

- **Wer entscheidet:** Steckt ein Fehler auch in der veröffentlichten Version, entscheidet der Maintainer, ob er eine
  Patch-Version wert ist. Maßstab: Der Fehler stört den Betrieb an der Kasse (Verkauf, Abrechnung, Daten) und kann
  nicht bis zur nächsten Version warten. Sonst wird er nur in der Version in Arbeit behoben.
- **Fehler nur in der Version in Arbeit, oder ohne Patch-Version:** `bugfix/…` vom Release-Branch in Arbeit, Pull
  Request dorthin.
- **Fehler in der veröffentlichten Version, mit Patch-Version:**
  1. Einmal je Patch-Version: Pull Request `chore/version-<patch-version>` in die Wartungslinie (Versionssprung wie
     unter [Branches](#branches)). Weitere Fixes derselben Patch-Version ergänzen nur deren Patchnotes.
  2. `bugfix/…` von der Wartungslinie, Pull Request dorthin.
  3. Die Patch-Version veröffentlichen wie ein Release, Schritte 1 bis 3 unter [Release](#release): Vorbereitung
     `chore/release-<patch-version>` in die Wartungslinie, Pull Request Wartungslinie → `main`, Release bauen.
     Schritt 4 dort entfällt, die Wartungslinie bleibt bestehen.
  4. Vorwärts-Merge: Branch `chore/main-nach-<version in Arbeit>` vom Release-Branch in Arbeit, `main` hineinmergen,
     Pull Request in den Release-Branch in Arbeit. Konflikte bei der Version und in den Patchnotes sind dabei der
     Normalfall, sobald die Version in Arbeit hochgesetzt ist. Auflösung: Die Version in Arbeit bleibt; in den
     Patchnotes bleiben beide Abschnitte, der der Version in Arbeit steht oben.
- Der Wartungs-Branch behält seinen Namen, auch wenn er eine Patch-Version trägt (`release/1.1.0` liefert 1.1.1).
- Ein Fehler wird nicht von Hand ein zweites Mal behoben. Cherry-Pick nur, wenn ein Vorwärts-Merge nicht möglich
  ist, und dann mit Hinweis im Pull Request.

## Release

Diese Datei sagt, welche Branches wann gemergt, angelegt und gelöscht werden. Wie ein Release technisch gebaut wird
(Version, Patchnotes, Formatmuster, Workflow, Fehlerfälle), steht in
[docs/installation.md](../docs/installation.md), „Releasing".

1. **Vorbereitung** per Pull Request `chore/release-<version>` in den Release-Branch (endgültige Patchnotes,
   Formatmuster). Vor einer neuen Version außerdem zwei Prüfungen:
   - Die Wartungslinie der vorigen Version ist vollständig in `main` enthalten
     (`git log origin/main..origin/release/<alt>` ist leer). Liegt dort ein unveröffentlichter Fix, kommt er per
     Cherry-Pick in einem Bugfix-Pull-Request in den Release-Branch in Arbeit, bevor dieser nach `main` geht.
   - Kein Vorwärts-Merge steht aus (`git log origin/release/<version>..origin/main` ist leer).
2. **Pull Request** `release/<version>` → `main`.
3. **Release bauen** (Workflow „Release build" auf `main`).
4. **Nach der Veröffentlichung einer neuen Version** (nicht einer Patch-Version): den Release-Branch der **vorigen**
   Version löschen, `release/<nächste Version>` von `main` anlegen, Versionssprung. Zum Löschen das Ruleset
   `release-nicht-loeschen` kurz auf „Disabled" stellen, den Branch löschen und das Ruleset wieder auf „Active"
   stellen (siehe [Einstellungen am Repository](#einstellungen-am-repository)).

## Commits und Pull Requests

- Commit-Betreff `KARV-<nr>: …`, wenn es ein Ticket gibt.
- Titel eines Pull Requests. Er wird beim Mergen zum Betreff des Commits, bei Squash wie bei Merge-Commit:

  | Pull Request | Titel |
  |---|---|
  | Änderung mit Ticket | `KARV-<nr>: <Titel> (v<Version>)` |
  | Änderung ohne Ticket | `<Titel> (v<Version>)` |
  | Release nach `main` | `Release <version>` |
  | Vorwärts-Merge | `main nach release/<version>`, nach einer Patch-Version mit dem Zusatz `(<patch-version>)` |

- Beschreibung nach der Vorlage [pull_request_template.md](pull_request_template.md).

## Sprache

Code, Kommentare, Meldungen, Commits, Pull Requests, Tickets und Specs (`docs/specs/`) sind deutsch. Die
beschreibende Dokumentation (`README.md`, `docs/*.md`) ist englisch.

## Arbeiten mit Claude Code

Wer das Repository mit Claude Code öffnet, hat alles Nötige, ohne ein Plugin zu installieren:

| Befehl | Wofür |
|---|---|
| `/spec <Ticket oder Thema>` | schreibt das Spec und lässt es von zwei unabhängigen Prüfern prüfen (Projekt-Skill unter `.claude/skills/spec/`) |
| `/simplify` | vereinfacht den geänderten Code (in Claude Code enthalten) |
| `/code-review` | sucht Fehler im Diff (in Claude Code enthalten) |

Die Anleitung für Claude steht in [.claude/CLAUDE.md](../.claude/CLAUDE.md); sie bindet diese Datei ein.

## Einstellungen am Repository

Stand 17.11.2026. Die Einstellungen schützen vor Versehen; die Regeln oben ersetzen sie nicht. Wer sie ändert, zieht
diesen Abschnitt nach.

**Rulesets** (Settings → Rules → Rulesets), alle aktiv und ohne Ausnahmen, auch nicht für Admins:

| Ruleset | Ziel | Regeln |
|---|---|---|
| `mainMerge` | `main` | kein Löschen, kein Force-Push, Änderungen nur per Pull Request, nur Merge-Commit, Check `test` muss grün sein |
| `release-branches` | `release/*` | kein Force-Push, Änderungen nur per Pull Request, Squash oder Merge-Commit, Check `test` muss grün sein (nicht beim Anlegen eines Branches) |
| `release-nicht-loeschen` | `release/*` | kein Löschen |

- Die Löschsperre steht in einem eigenen Ruleset, damit sie sich einzeln abschalten lässt, wenn ein alter
  Release-Branch gelöscht wird. Die übrigen Regeln bleiben dabei in Kraft.
- `test` ist der Job der CI (`.github/workflows/ci.yml`). Wer den Job umbenennt, passt beide Rulesets an, sonst lässt
  sich nichts mehr mergen.
- Auf Arbeits-Branches liegt kein Ruleset: Dort sind Push, Rebase und Löschen frei.

**Pull Requests** (Settings → General → Pull Requests):

- „Automatically delete head branches" ist an: Ein gemergter Arbeits-Branch wird gelöscht. Release-Branches bleiben
  wegen der Löschsperre stehen.
- Der Commit trägt den Titel des Pull Requests: bei „Allow merge commits" die Vorgabe „Pull request title", bei
  „Allow squash merging" die Vorgabe „Pull request title and commit details".

**Was die Einstellungen nicht verhindern:** GitHub unterscheidet nicht nach dem Quell-Branch. Ein Vorwärts-Merge lässt
sich squashen, ein Arbeits-Branch mit Merge-Commit in einen Release-Branch oder per Pull Request direkt nach `main`
mergen. Dafür gibt es den letzten Punkt der Checkliste.

**Erprobt am 17.11.2026:** Ein neuer Release-Branch lässt sich trotz der Regeln von `main` anlegen. Die Löschsperre
hält gegen das Löschen von Hand und gegen das automatische Löschen nach einem Merge.
