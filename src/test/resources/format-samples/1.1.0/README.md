# Beispieldaten von 1.1.0

`data.zip` enthält einen echten lokalen Bestand (`data/…`, 22 Versions- und Anspruchsdateien), geschrieben vom Code
des Release-Stands von 1.1.0 (Branch `release/1.1.0`, Commit `5fbb07b1d3cc2bef0f8efa2c8f98ad18a66a89f1`) über dessen
`SyncService` im Einzelbetrieb und die Repositories. Die Bytes sind genau die, die 1.1.0 schrieb: nicht von Hand ändern
(die Prüfsummen in den Ansprüchen stimmten sonst nicht mehr). `FormatSamplesTest` entpackt das Archiv in ein
temporäres Verzeichnis, bevor er lädt oder speichert.

Ein Archiv statt der einzelnen Dateien, weil deren Pfade (Event, Vorstellung, Buchung, Ansprüche, je mit UUID) für Git
unter Windows ohne `core.longpaths` zu lang sind.

Im Archiv steht nur `data/`. Index, Marke „sauber beendet“ und `machine.json` fehlen: Der Start baut den Index neu auf,
und die Versionen stammen damit von einem anderen Rechner (wie bei einem kopierten Datenordner).

## So entstanden (13.11.2026)

```powershell
git worktree add ..\Kartenverwaltung-rel origin/release/1.1.0
cd ..\Kartenverwaltung-rel
.\mvnw.cmd -o -q test-compile
.\mvnw.cmd -o -q dependency:build-classpath "-Dmdep.outputFile=cp.txt"
& "$env:JAVA_HOME\bin\java.exe" -cp "target\classes;target\test-classes;$(Get-Content cp.txt)" `
    de.eltviller_carneval_verein.karten.store.FormatSampleGenerator "src\test\resources\format-samples\1.1.0"
```

`FormatSampleGenerator` liegt in `src/test/java/.../store/` und schreibt das Beispiel in einen temporären Datenordner
(nie in den echten der App); dessen Unterordner `data` kommt unverändert als `data.zip` in den Zielordner.

## Inhalt des Archivs

Derselbe Inhalt wie im Beispiel von 1.0.1, damit `FormatSamplesTest` beide gleich prüft:

- Halle „Stadthalle“ (`halls/ee0e82f1-…`), zwei Versionen:
  - „Bühne“: `RECTANGLE`, `LEFT`/`TOP`; „Theke“: `CIRCLE`, `RIGHT`/`BOTTOM`, Beschreibung „Getränke“;
  - Bestuhlung „Reihenbestuhlung“ mit Reihe A, Tisch 1 und 2 mit je drei Sitzen.
- Event „Prunksitzung“ (`events/27c03cc4-…`, Kopf) mit der Vorstellung „Freitag“.
- Buchungen (je eine Einheit):
  - A/1/1 Müller `NONE`, A/1/2 Meier `CASH`, A/1/3 Schmitz `CARD`, A/2/1 Becker `TRANSFER`;
  - A/2/4 Schulz `CASH`, **ohne Platz**: Tisch 2 bekam einen vierten Sitz, Schulz wurde darauf gebucht und gespeichert,
    dann wurde der Sitz im Plan gelöscht und die Halle gespeichert; das nächste Speichern verschob die Buchung in die
    Einheit `bookings/_48ecde64-…`, die alte Sitz-Einheit ist gelöscht.
  - Die beiden freien Sitze A/2/2 und A/2/3 haben keine Datei (leere Platzhalter werden nicht geschrieben).

Anders als 1.0.1 schreibt 1.1.0 an Tischen Text und Ausrichtung und an Sitzen keine Höhe mehr.
