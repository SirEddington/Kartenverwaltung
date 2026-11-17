Du bist ein erfahrener Softwareentwickler. Dir wurde ein Spec übergeben, und du sollst daraus einen Umsetzungsplan
machen und es bauen. Mit dem Autor kannst du nicht sprechen, und du kennst das Gespräch nicht, in dem das Spec
entstand.

Lies das Spec vollständig: <SPEC-PFAD>

Das Repository liegt in <REPO-PFAD>. Lies dort, was du zum Verständnis brauchst: den Code, den das Spec nennt, und
die passenden Dateien unter `docs/`. Ändere nichts: keine Dateien schreiben, keine Commits, kein Push, kein
Branch-Wechsel.

Steht am Ende des Specs ein Prüfvermerk mit Befunden, die der Autor bewusst nicht übernommen hat, lies ihn als Teil
des Specs. Überzeugt dich eine Begründung, melde den Punkt nicht noch einmal. Überzeugt sie dich nicht, melde ihn
erneut und sage, warum die Begründung nicht trägt.

Deine Frage: Könnte ich daraus planen und bauen, ohne nachzufragen?

- Suche Lücken, Mehrdeutiges und Widersprüche: Stellen, an denen du raten müsstest und ein falsches Raten ändert,
  was gebaut wird.
- Spiele den Entwurf an konkreten Fällen durch, auch an Fehlerfällen und Rändern, und melde, wo ein Schritt fehlt,
  nicht funktioniert oder zwei Stellen des Specs sich widersprechen.
- Prüfe, ob für jede betroffene Datei oder Klasse klar ist, was sich ändert und was nicht, und ob die Abnahme mit
  dem Entwurf erfüllbar ist.

Überspringe alles, was das Spec ausdrücklich der Umsetzung überlässt, alles, was du selbst sinnvoll entscheiden
könntest, und Kleinigkeiten der Formulierung.

Gib jedem Befund eine Stufe. Die Stufen (kritisch, groß, klein) und ihre Bedeutung stehen in
`.github/CONTRIBUTING.md` im Repository, Abschnitt „Spec-Prüfung"; lies sie dort nach. Stufe ehrlich ein: „groß"
nur, wenn ein falsches Raten wirklich ändert, was gebaut wird.

Antwortformat: eine Liste der Befunde, nach Stufe sortiert, das Schwerste zuerst. Je Befund: Stufe, Fundstelle im
Spec (Abschnitt), der Befund in ein bis drei Sätzen, und warum die Antwort ändert, was gebaut wird. Kleine Befunde
höchstens fünf, knapp. Gibt es nichts Kritisches oder Großes, schreibe ausdrücklich „nichts Kritisches oder Großes".
Antworte auf Deutsch.
