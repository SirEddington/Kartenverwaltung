Du prüfst ein Spec gegen die Wirklichkeit. Du kennst das Gespräch nicht, in dem es entstand, und sollst dich nicht
auf die Behauptungen des Specs verlassen, sondern sie nachprüfen.

Das Spec (vollständig lesen): <SPEC-PFAD>

Das Repository liegt in <REPO-PFAD>. Du darfst lesen und lesende Befehle ausführen (zum Beispiel `git log`,
`git diff`, `git merge-tree`, Suchen im Code, `gh` nur lesend). Ändere nichts: keine Dateien schreiben, keine
Commits, kein Push, kein Branch-Wechsel, keine Einstellungen.

Steht am Ende des Specs ein Prüfvermerk mit Befunden, die der Autor bewusst nicht übernommen hat, lies ihn als Teil
des Specs. Überzeugt dich eine Begründung, melde den Punkt nicht noch einmal. Überzeugt sie dich nicht, melde ihn
erneut und sage, warum die Begründung nicht trägt. Was das Spec selbst als nicht belegt führt und wofür es eine
Probe samt Rückfall nennt, ist kein Befund, solange Probe und Rückfall tragen.

Deine Fragen:

1. Stimmt, was das Spec über den Bestand behauptet (Abschnitt „Ausgangslage" und jede Stelle, die Klassen, Dateien,
   Zeilen oder Verhalten des bestehenden Codes nennt)? Prüfe nach und nenne Abweichungen.
2. Passt der Entwurf zum Bestand: zu den Schichtregeln (`docs/structure.md`), zur Architektur
   (`docs/architecture.md`), zu den Entscheidungen (`docs/decisions.md`) und zum Datenformat samt
   Abwärtskompatibilität? Widerspricht er einer davon, ohne es zu sagen?
3. Funktioniert der Entwurf technisch so, wie beschrieben? Denke ihn durch, wo möglich mit Befehlen oder am Code:
   Was bricht er, welche Aufrufer, Tests oder Dateien sind betroffen, die das Spec nicht nennt?
4. Was übersieht der Entwurf sonst: Fehlerfälle, Mehrplatzbetrieb über den gemeinsamen Ordner, Offline-Betrieb,
   alte Daten, Linux und Windows?

Gib jedem Befund eine Stufe. Die Stufen (kritisch, groß, klein) und ihre Bedeutung stehen in
`.github/CONTRIBUTING.md` im Repository, Abschnitt „Spec-Prüfung"; lies sie dort nach. Wo du etwas nicht sicher
weißt, sage das und stufe nicht höher als „groß" ein.

Antwortformat: eine Liste der Befunde, nach Stufe sortiert, das Schwerste zuerst. Je Befund: Stufe, Fundstelle im
Spec (Abschnitt), der Befund in ein bis drei Sätzen, und der Beleg (Befehl und Ergebnis, Datei und Zeile, oder
„Überlegung, nicht belegt"). Kleine Befunde höchstens fünf, knapp. Nenne am Ende in wenigen Zeilen, was du nicht
prüfen konntest. Gibt es nichts Kritisches oder Großes, schreibe ausdrücklich „nichts Kritisches oder Großes".
Antworte auf Deutsch und fasse dich kurz: keine Liste dessen, was stimmt.
