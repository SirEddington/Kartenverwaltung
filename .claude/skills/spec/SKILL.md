---
name: spec
description: Schreibt für die Kartenverwaltung ein Spec unter docs/specs/ und lässt es von zwei unabhängigen Prüfern prüfen. Verwenden vor jeder Änderung an Verhalten, Datenformat oder Architektur, bevor Code entsteht, und wenn ein vorhandenes Spec geprüft werden soll.
argument-hint: <Ticket oder Thema>
---

# Spec schreiben und prüfen lassen

Dieser Skill führt die Regeln aus `.github/CONTRIBUTING.md` aus, Abschnitte „Spec", „Spec-Prüfung" und „Freigabe".
Dort steht, was gilt; hier steht, in welcher Reihenfolge du es tust. Lies diese Abschnitte, bevor du beginnst.

Kein Code, bevor das Spec geprüft und freigegeben ist.

## Ablauf

1. **Klären.** Finde mit dem Entwickler heraus, was gebaut werden soll und warum. Frage, bis du das Ziel in zwei
   Sätzen wiedergeben kannst und er zustimmt.
2. **Bestand lesen.** Lies den betroffenen Code und die passenden Dateien unter `docs/` (`architecture.md`,
   `decisions.md`, `structure.md`). Was du über den Bestand behauptest, musst du gesehen haben.
3. **Wege vorstellen.** Zeige zwei oder drei Wege mit ihren Folgen und sage, welchen du empfiehlst und warum. Der
   Entwickler wählt.
4. **Arbeits-Branch.** Stelle sicher, dass du auf einem Arbeits-Branch stehst (Namen und Basis: `CONTRIBUTING.md`,
   „Branches").
   - Auf `main` oder einem Release-Branch: Namen und Basis vorschlagen, nach dem Ja des Entwicklers anlegen. Basis
     ist der Release-Branch in Arbeit. Hole dafür den Stand des Hauptrepositorys; bei einem Fork ist das nicht
     `origin`, und ein Fork enthält zunächst nur `main`.
   - Auf einem Arbeits-Branch, der zu etwas anderem gehört, oder auf einem Branch außerhalb des Schemas
     (`claude/…`, `worktree-…`): nachfragen.
5. **Schreiben.** Lege das Spec nach [vorlage.md](vorlage.md) unter `docs/specs/` an (Dateiname: `CONTRIBUTING.md`,
   „Spec"). Keine privaten Daten, keine Zugangsdaten: Das Repository ist öffentlich.
6. **Prüfen lassen.** Starte zwei Subagents in derselben Nachricht, damit sie gleichzeitig und unabhängig laufen:
   - einen mit dem Text aus [pruefauftrag-umsetzer.md](pruefauftrag-umsetzer.md),
   - einen mit dem Text aus [pruefauftrag-gegenpruefer.md](pruefauftrag-gegenpruefer.md).

   Ersetze in beiden `<SPEC-PFAD>` und `<REPO-PFAD>`. Gib ihnen sonst nichts mit: keine Zusammenfassung des
   Gesprächs, keine eigene Einschätzung, nicht die Befunde des anderen.
7. **Einarbeiten.** Behebe kritische und große Befunde im Spec. Was du nicht übernimmst oder herabstufst, kommt mit
   Begründung in den Prüfvermerk. Kleine Befunde arbeitest du ein, wenn sie das Spec besser machen.
8. **Wiederholen** mit einem neuen Paar. Wann die Prüfung endet und wann sie abbricht, steht in der
   `CONTRIBUTING.md`, „Spec-Prüfung"; halte dich genau daran.
9. **Abschließen.** Schreibe den Prüfvermerk, committe das Spec auf dem Arbeits-Branch und zeige es dem Entwickler.
   Nenne ihm den Weg der Freigabe (`CONTRIBUTING.md`, „Freigabe"). Pushe nicht von dir aus.
10. **Warten.** Vor der Freigabe wird nichts gebaut.

## Einstieg später im Ablauf

- Ziel, Grund und Weg sind im Gespräch schon geklärt (etwa durch das Brainstorming des Superpowers-Plugins): Beginne
  bei Schritt 4.
- Es gibt schon ein Spec, das nur geprüft werden soll: Beginne bei Schritt 4 und überspringe Schritt 5.

## Was du dem Entwickler am Ende sagst

- wo das Spec liegt,
- wie viele Runden es gab und was die letzte ergab,
- welche Befunde du bewusst nicht übernommen hast,
- welche Entscheidungen du selbst getroffen hast und er prüfen sollte,
- was nicht belegt werden konnte.
