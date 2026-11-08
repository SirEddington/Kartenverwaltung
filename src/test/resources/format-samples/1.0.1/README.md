# Beispieldaten von 1.0.1

`data.zip` enthält einen echten lokalen Bestand (`data/…`, 22 Versions- und Anspruchsdateien), geschrieben vom Code
des Tags `1.0.1` (Commit `909ed13ab428146c0087b2cc2cfe385aa98f7942`) über dessen `SyncService` im Einzelbetrieb und
die Repositories. Die Bytes sind genau die, die 1.0.1 schrieb: nicht von Hand ändern (die Prüfsummen in den Ansprüchen
stimmten sonst nicht mehr). `FormatSamplesTest` entpackt das Archiv in ein temporäres Verzeichnis, bevor er lädt oder
speichert.

Ein Archiv statt der einzelnen Dateien, weil deren Pfade (Event, Vorstellung, Buchung, Ansprüche, je mit UUID) bis zu
223 Zeichen lang sind und Git unter Windows sie ohne `core.longpaths` nicht anlegen kann.

Im Archiv steht nur `data/`. Index, Marke „sauber beendet“ und `machine.json` fehlen: Der Start baut den Index neu auf,
und die Versionen stammen damit von einem anderen Rechner (wie bei einem kopierten Datenordner).

## So entstanden (08.11.2026)

```powershell
git worktree add "$env:TEMP\wt-101" 1.0.1      # Wegwerf-Worktree außerhalb des Repos
cd "$env:TEMP\wt-101"
.\mvnw.cmd -o -q test-compile
.\mvnw.cmd -o -q dependency:build-classpath "-Dmdep.outputFile=cp.txt"
& "$env:JAVA_HOME\bin\java.exe" -cp "target\classes;target\test-classes;$(Get-Content cp.txt)" `
    de.eltviller_carneval_verein.karten.store.Sample101Generator "$env:TEMP\sample-101-gen"
# danach den Worktree entfernt und im Repo $env:TEMP\sample-101-gen\data mit
# FormatSampleGenerator.zipData(<sample-101-gen>, format-samples\1.0.1\data.zip) gepackt
# (entpackt mit diff -r gegen das Original geprüft: gleich)
```

`Sample101Generator` war eine Wegwerf-Klasse in `src/test/java` des Worktrees (nicht im Repo). Sie tat dasselbe wie
`FormatSampleGenerator.fill` in diesem Repo (nur die Beschreibungen lauten hier „Beispiel aus 1.0.1“), mit der API von
1.0.1 (`BookingSync.sync(p, plan)` ohne gesperrte Sitze): `TestSyncServices.singleMode`, `startup(null)`,
`TestRepositories.halls/events`, Speichern über `saveHall`/`saveEvent`, zuletzt `closeIndex()`.

## Inhalt des Archivs

- Halle „Stadthalle“ (`halls/4964821e-…`), zwei Versionen:
  - „Bühne“: `RECTANGLE`, `LEFT`/`TOP`; „Theke“: `CIRCLE`, `RIGHT`/`BOTTOM`, Beschreibung „Getränke“;
  - Bestuhlung „Reihenbestuhlung“ mit Reihe A, Tisch 1 und 2 mit je drei Sitzen.
- Event „Prunksitzung“ (`events/94e4a825-…`, Kopf) mit der Vorstellung „Freitag“ (05.02.2027, 19:11, 25,00 €).
- Buchungen (je eine Einheit):
  - A/1/1 Müller `NONE`, A/1/2 Meier `CASH`, A/1/3 Schmitz `CARD`, A/2/1 Becker `TRANSFER`;
  - A/2/4 Schulz `CASH`, **ohne Platz**, über den echten Weg: Tisch 2 bekam einen vierten Sitz, Schulz wurde darauf
    gebucht und gespeichert, dann wurde der Sitz im Plan gelöscht und die Halle gespeichert; der Abgleich beim Öffnen
    (`BookingSync.sync` unter `deriving`) machte Schulz zur Buchung ohne Platz, das nächste Speichern verschob sie in
    die Einheit `bookings/_06770d0a-…` (`movedFrom`), die alte Sitz-Einheit ist gelöscht (`movedTo`). Reihe/Tisch/Nummer
    A/2/4 passen zu keinem Sitz, der Abgleich ordnet sie also beim Laden nicht zu.
  - Die beiden freien Sitze A/2/2 und A/2/3 haben keine Datei (leere Platzhalter schreibt 1.0.1 nicht).

## Rückwärts-Prüfung (08.11.2026)

Im Wegwerf-Worktree von `1.0.1` lud ein kleiner Lader (Wegwerf-Klasse `SampleLoader101`) jeden Ordner im Einzelbetrieb
wie die App: `loadHalls`, `loadEvents`, `BookingSyncService.prepare`, dann Ladewarnungen, Sperren, Rückmeldungen des
Dienstes (`error`, `locked`, `skipped`), Warnungen im Log von `SyncService` und den Repositories, Zustände aller
Einheiten, „lokal verändert“ und Speichern ohne Änderung.

| Ordner | Ergebnis in 1.0.1 |
|---|---|
| 1.1-Beispiel aus `FormatSampleGenerator` (Branch-Stand `906d12e` + Task 18), nur `data/` | lädt ohne Fehler und Warnungen, 9 Einheiten `OK`, Inhalt vollständig |
| dieses Beispiel, mit 1.1 geöffnet, Kommentar von Meier geändert und gespeichert, nur `data/` | lädt ohne Fehler und Warnungen, 9 Einheiten `OK`, Kommentar sichtbar; nichts verändert, Speichern schreibt nichts |
| zur Kontrolle: dieses Beispiel unverändert | ohne Fehler und Warnungen |

Beobachtungen beim 1.1-Beispiel, ohne Fehler oder Warnung: 1.0.1 hält die Halle für verändert (`isCurrent` falsch),
und Speichern ohne Änderung schreibt eine neue Hallenversion. Grund: Die Halle von 1.1 hat Schlüssel, die 1.0.1 nicht
kennt (Text und Ausrichtung an Tischen) und keine Sitzhöhe mehr; 1.0.1 übergeht sie beim Lesen und schreibt sie beim
nächsten Speichern der Halle nicht mit. Das ist das erwartete Verhalten einer älteren Version.

Mit dem ganzen Datenordner von 1.1 (samt Index) warnt 1.0.1 beim Start „Unbekanntes Format des Index: 5“ und baut den
Index neu auf; danach wie oben.
