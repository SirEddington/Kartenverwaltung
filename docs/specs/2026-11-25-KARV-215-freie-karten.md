# Anzahl freier Karten im Verkauf (KARV-215)

Stand: Entscheidung vom 25.11.2026. Ein Spec ist eine Momentaufnahme und wird nach dem Merge nicht gepflegt; was
dauerhaft gilt, steht in `docs/decisions.md` und der übrigen Dokumentation.

## Anlass und Ziel

Rückmeldung aus dem Verkauf (25.11.2026): Die Kassenkraft möchte jederzeit sehen, wie viele Karten noch frei sind,
um Kunden sofort Auskunft zu geben. Heute steht über dem Suchfeld „n Treffer“ (KARV-192); bei leerem Suchfeld zählt
das alle Zeilen, auch belegte. Eine Zahl der freien Karten gibt es nirgends: Die Statistik der Abrechnung zeigt die
Auslastung in Prozent je Event, die Event-Übersicht „bezahlt / reserviert / gesamt“.

Ziel: In der Verkaufsansicht steht über dem Auswahlfeld „Vorstellung“ dauerhaft „frei: n von m“ für die gewählte
Vorstellung, bei „Alle Vorstellungen“ als Summe mit einer Aufschlüsselung im Tooltip. Die Zahl hängt nicht von
Suche, Spaltenfilter oder Ansicht ab und folgt eigenen Änderungen, Änderungen anderer Rechner und Sperren.

## Ziele, Nicht-Ziele, Anti-Ziele

**Ziele:**

- Die Anzeige steht in Kartentabelle und Saalansicht, an derselben Stelle der gemeinsamen Kopfzeile.
- n zählt die Sitze, die sich jetzt verkaufen lassen; m zählt alle Sitze, die sich verkaufen lassen oder belegt
  sind. Die genaue Regel steht im Entwurf.
- Die Anzeige bleibt unverändert, wenn im Suchfeld ein Name steht oder ein Spaltenfilter gesetzt ist.
- Sie folgt dem Wechsel von Event und Vorstellung, eigenen Änderungen (auch solchen, die sofort gespeichert werden),
  Änderungen anderer Rechner an Buchungen und an der Bestuhlung und geänderten Sperren, ohne dass die Ansicht neu
  geladen wird.

**Nicht-Ziele:**

- Kein Suchwort „frei“ und keine Änderung an der Suche (`BookingSearch`).
- Kein Filter „nur freie“ für die Kartentabelle (eigenes Ticket).
- Keine Aufschlüsselung nach Kategorie.
- Keine Anzeige im Kundenfenster, in der Abrechnung oder in der Event-Übersicht.
- Keine Änderung an „n Treffer“.

**Anti-Ziele:**

- Die Zahl nennt einen Sitz frei, der sich nicht verkaufen lässt (gesperrt), oder zählt eine Buchung ohne Platz als
  Sitz.
- Die Zahl ändert sich beim Tippen im Suchfeld.
- Ein Feld der Kopfzeile (Event, Vorstellung, Suche) bekommt eine andere Mindest- oder Vorzugsbreite als heute.

## Randbedingungen

- Patch-Version auf der Wartungslinie `release/1.1.0`: nichts am Datenformat, nichts an gespeicherten Daten. Die
  Änderung betrifft nur die Oberfläche. Ob sie mit 1.1.2 oder einer späteren Patch-Version ausgeliefert wird,
  entscheidet der Maintainer beim Release.
- Schichtregeln aus `docs/architecture.md` („Layered architecture“): Alles bleibt in `ui.sales`.
- Logik außerhalb von JavaFX, damit sie ohne Toolkit testbar ist (`docs/testing.md`, „Logic out of JavaFX“).
- Texte der Oberfläche deutsch, beschreibende Doku englisch.

## Ausgangslage

Geprüft am 25.11.2026 auf `origin/release/1.1.0` (f6ee993). Pfade ohne Verzeichnis liegen unter
`src/main/java/de/eltviller_carneval_verein/karten/`.

- **Kopfzeile:** `src/main/resources/de/eltviller_carneval_verein/karten/ui/TicketShellView.fxml`, Zeile 35–38: eine
  `VBox` mit `Label text="Vorstellung"` (Stilklasse `field-caption`) über `ComboBox fx:id="presComboBox"`
  (`prefWidth 160`, `minWidth 100`). Die Suche daneben (Zeile 40–49) hat seit KARV-192 statt des einfachen Labels eine
  `HBox` (`minWidth 0`, `prefWidth 0`) mit dem Label „Suche“ (`minWidth 0`), einem wachsenden `Region` (`minWidth 4`)
  und dem Label `lblSearchHits` (`minWidth -Infinity`). `field-caption` hat die Schriftgröße 11 px (`style.css`,
  Zeile 56–59).
- **Zustand der Verkaufsansicht:** `ui/sales/SalesViewState` trägt `eventProperty()` und `presentationProperty()`;
  eine Vorstellung `null` bedeutet „Alle Vorstellungen“. Die Shell (`ui/sales/TicketShellController`) schreibt beide.
- **Eventwechsel:** `TicketShellController.applyEventSelection` (Zeile 246) setzt zuerst `state.eventProperty()` und
  `state.presentationProperty()` (auf `null`) und ruft erst danach `BookingSyncService.prepare(selectedEvent)` (Zeile
  251). `prepare` vergleicht ein schon verfolgtes Event zuerst (`tracker.check`, `ui/BookingSyncService.java` Zeile
  61–64; das kann die Listener des Trackers rufen), gleicht dann je Vorstellung die Buchungen mit der Bestuhlung ab
  (`BookingSync.sync`, Zeile 75) und macht den Stand danach zum gespeicherten (`tracker.markSaved`, Zeile 84–86,
  nur wenn das Event vorher sauber war; dabei rufen die Listener nicht). Ein Listener auf `eventProperty()`,
  `presentationProperty()` oder am Tracker kann beim Eventwechsel also vor dem Abgleich laufen.
- **Jeder Sitz hat eine Buchung:** Nach `BookingSync.sync` hat jeder Sitz der Bestuhlung genau eine Buchung, freie
  Sitze eine leere (`model/BookingSync.java`, Klassenbeschreibung, Schritte 1 bis 4). Buchungen mit Daten, die keinen
  Sitz finden, bleiben als „ohne Platz“ erhalten (`Booking.isUnassigned()`, `model/Booking.java` Zeile 276). Hat die
  Vorstellung keine `seatingPlanId`, gibt es nur Buchungen ohne Platz. Ist die `seatingPlanId` gesetzt, die
  Bestuhlung aber nicht mehr in der Halle (`planMissing`, `BookingSync.sync`), bleiben die Buchungen unverändert,
  also mit Sitz; die Saalansicht zeichnet dann keine Sitze (`SalesPlan.of` liefert `null`), die Kartentabelle zeigt
  die Buchungen weiter. `Presentation.getBookings()` kann `null` sein (`TicketTableController.shownBookings`, Zeile
  579–581, fängt das ab).
- **Belegt:** `Booking.isTaken()` (Zeile 342) ist wahr, wenn `getStatus()` nicht `FREE` ist. `getStatus()` (Zeile 327)
  liefert `SOLD` bei bezahlter Buchung, `RESERVED` bei nicht leerem Nachnamen (`isReserved()`, Zeile 317), sonst
  `FREE`. `SeatStatus.BLOCKED` liefert `getStatus()` nie.
- **Gesperrt:** `ui/sales/LockedSeats.isLocked(Booking)` (Zeile 59) ist wahr für eine Buchung mit Sitz, die nicht
  belegt und nicht ohne Platz ist und deren Buchungs-Einheit in der Liste der gesperrten Einträge steht. Die
  Saalansicht zeichnet solche Sitze als „Gesperrt“ (`HallOverviewController`, Zeile 364), die Kartentabelle lässt sie
  weg (`TicketTableController.shownBookings`, Zeile 583). Ändern sich die Sperren, ruft `LockedSeats.changed()`
  (Zeile 108) die Listener; `LockedSeats.whileShown(Runnable)` (Zeile 99) liefert eine `LiveViews.Registration` dafür.
- **Eigene Änderungen:** `ui/ChangeTrigger` vergleicht nach 300 ms Ruhe (`DEBOUNCE_MILLIS`, Zeile 31) alle verfolgten
  Events mit ihrem letzten Stand; weicht eines ab, ruft der `ChangeTracker` seine Listener
  (`tracking/ChangeTracker.Listener.onChanged(entity, before, after)`, Zeile 73–80; `addListener` Zeile 402,
  `removeListener` Zeile 406). Vorbild: `ui/sales/CustomerDisplay` meldet sich so an (Zeile 365) und zeichnet nur bei
  Änderungen am gezeigten Event neu (`onDataChanged`, Zeile 390).
- **Speichern meldet der Tracker nicht:** Jedes Speichern eines Events endet in `StoreEventRepository.write` mit
  `listeners.notifySaved(event)` (`repository/StoreEventRepository.java` Zeile 266). Daran hängt
  `ChangeTracker.repositoryUpdater()` (`MainApp.java` Zeile 575), das mit `markSaved` den gespeicherten Stand zum
  letzten bekannten macht, ohne die Listener des Trackers zu rufen. Wird eine Änderung gespeichert, bevor der
  `ChangeTrigger` verglichen hat, erfährt ein Listener am Tracker also nie von ihr. So etwa bei Strg+S oder
  „Speichern“ (`TicketShellController.handleSave`, Zeile 420), während eine Tabellenzelle bearbeitet wird: Die Zelle
  übernimmt den Wert, und im selben Ereignis wird gespeichert. `RepositoryListener.saved(T)`
  (`repository/RepositoryListener.java` Zeile 18) steht jedem Listener am Repository offen.
- **Wiederherstellen:** Nach „Nicht speichern“ sind Event und Vorstellungen neue Objekte;
  `TicketShellController.rebindToRestoredData` (Zeile 366) wählt sie in den Auswahlfeldern neu, was über den Listener
  der Event-Auswahl in `applyEventSelection` endet.
- **Änderungen anderer Rechner an Events:** Die Shell hat dafür `foreignChanges` (Zeile 93), einen gebündelten
  Listener am Event-Repository über alle Einheiten (`LiveViews.onFxThread(LiveViews.ALL_UNITS, …)`), der heute nur
  `selectionSync.sync()` ruft. Angemeldet ist er über `LiveViews.whileShown(contentArea, …)` (Zeile 155), solange die
  Shell zu sehen ist; dort hängt auch eine zweite Registrierung für `autoSave` (Zeile 156).
- **Änderungen anderer Rechner an einer Halle:** `ui/LiveUpdates.apply` (Zeile 250–254) arbeitet erst die Hallen
  ein und gleicht dann in `prepareEventsOfChangedHalls` (Zeile 289) die verfolgten Events mit der neuen Bestuhlung
  ab, über `BookingSyncService.prepare`. Der Abgleich selbst meldet weder dem Tracker noch dem Event-Repository. Die
  Shell hört nicht am Hallen-Repository. Saalansicht und Kundenfenster tun es mit einem gebündelten Listener
  (`HallOverviewController.foreignHallChanges`, Zeile 101, über `LiveViews.onFxThread`); er läuft über
  `Platform.runLater` und damit nach dem Abgleich. `StoreHallRepository` hat `addListener` und `removeListener`
  (`repository/StoreHallRepository.java` Zeile 333 und 338).
- **Andere Zählungen:** `util/BookingTotals.of` (Zeile 23) zählt als „gesamt“ jede Buchung, auch Buchungen ohne
  Platz und gesperrte Sitze. Daraus rechnen die Event-Übersicht und die Statistik
  (`ui/accounting/StatisticsController.computeStats`, Zeile 116).
- **„n Treffer“ als Vorbild für Text und Tooltip:** `TicketShellController.bindSearchHits` (Zeile 225) setzt Text und
  Tooltip von `lblSearchHits` und baut den Tooltip beim Anzeigen (`setOnShowing`) neu. Die Texte kommen aus
  `ui/sales/SearchHits` (ohne JavaFX), die Namen der Vorstellungen aus `EntityLabels.nameOrPlaceholder`.
- **Fenster:** Das Hauptfenster ist mindestens 1250 px breit (`MainApp.java`, `MIN_WIDTH`, Zeile 193 und 210).
- **Doku:** `docs/usage.md` Zeile 103–107 beschreibt Suche und Trefferzahl. `docs/structure.md` Zeile 39 nennt
  `sales/ (33)`; tatsächlich liegen 36 Klassen in `ui/sales`. Auf `release/1.2.0` steht dort `sales/ (50)`.
  `docs/testing.md` Zeile 56 zählt die Tests von `ui.sales` auf.
- **Patchnotes:** `src/main/resources/de/eltviller_carneval_verein/karten/patchnotes.md` beginnt mit `## 1.1.2` und
  hat dort nur den Abschnitt `### Behoben`. `pom.xml` und `conveyor.conf` stehen auf 1.1.2.

## Betrachtete Wege

**Was m ist** (Entwickler, 25.11.2026):

1. *Ohne gesperrte Sitze* — gewählt. n und m zählen nur Sitze, die sich verkaufen lassen oder belegt sind; ein
   gesperrter Sitz fehlt in beiden.
2. *Alle Sitze der Vorstellung* — nicht gewählt. m bliebe die Größe des Saals, ein gesperrter Sitz senkte nur n.

**Wo die Anzeige steht** (Entwickler):

1. *Über dem Auswahlfeld „Vorstellung“* — gewählt. Kostet keine Breite und steht bei dem, worauf sich die Zahl
   bezieht.
2. Fußzeile neben „Anzeige“ — weiter weg vom Blick, neben den Statusmeldungen.
3. Neben „n Treffer“ — dort ist es eng, und die Zahl hängt gerade nicht von der Suche ab.

**Wie die Zahl entsteht** (Entwickler, auf Empfehlung):

- A: *Die Shell zählt selbst über das Modell* — gewählt. Unabhängig von Suche, Spaltenfilter und Ansicht; Tabelle
  und Saalansicht bleiben unberührt.
- B: Tabelle und Saalansicht melden die Zahl wie die Treffer — die Tabelle zeichnet bei eigenen Änderungen nur neu,
  es bräuchte zusätzliche Meldestellen in zwei Controllern, und beide könnten verschieden zählen.
- C: Aus den Treffern ableiten — die Zahl änderte sich mit der Suche; verworfen.

**Ein Suchwort „frei“** (Vorschlag aus dem Verkauf) — verworfen: Es kollidiert in einer Namenssuche mit dem
Nachnamen Frei und mit Kommentaren.

## Entwurf

### Zählregel

Gezählt wird je Vorstellung über `presentation.getBookings()` (`null` wie leer). Jede Buchung fällt in genau eine
Gruppe, geprüft in dieser Reihenfolge:

| Gruppe | Bedingung | zählt in |
|---|---|---|
| ohne Platz | `booking.isUnassigned()` | nichts |
| gesperrt | `LockedSeats.isLocked(booking)` | „gesperrt“ |
| belegt | `booking.isTaken()` | m |
| frei | sonst | n und m |

n ist also die Zahl der freien, m die der freien und belegten Sitze. Eine Buchung mit Resten (Vorname oder
Kommentar ohne Nachnamen, nicht bezahlt) ist frei, wie in der Saalansicht.

„Gesperrt“ meint hier nur `LockedSeats.isLocked`, also was die Saalansicht als gesperrt zeichnet. Andere Gründe,
aus denen sich eine Buchung nicht speichern lässt, berücksichtigt die Anzeige nicht; solche Sitze zählen wie in der
Saalansicht als frei.

Gezählt wird, was im Modell steht, ohne Blick auf die Bestuhlung. Fehlt die Bestuhlung einer Vorstellung
(`planMissing`), zählen ihre Buchungen mit Sitz deshalb weiter, wie die Kartentabelle sie weiter zeigt.

m weicht bewusst von „gesamt“ in Event-Übersicht und Statistik ab (`BookingTotals`): Dort zählen Buchungen ohne
Platz und gesperrte Sitze mit. `BookingTotals` bleibt unverändert.

Welche Vorstellungen zählen: die gewählte; bei „Alle Vorstellungen“ alle aus `event.getPresentations()` (`null` wie
leer), n, m und „gesperrt“ jeweils als Summe.

### `FreeSeats` (neu, `ui.sales`, ohne JavaFX)

Statische Hilfen, aus denen die Shell Text und Tooltip baut:

- ein Ergebnis mit den drei Zahlen frei, gesamt (m) und gesperrt, für eine Vorstellung und als Summe über mehrere,
- `labelText`: „frei: n von m“, auch „frei: 0 von 0“,
- `tooltipText` für die gezählten Vorstellungen in der Reihenfolge von `event.getPresentations()`:
  - je Vorstellung eine Zeile „<Name>: n von m“, Name über `EntityLabels.nameOrPlaceholder`; das gilt auch, wenn nur
    eine Vorstellung gewählt ist (dann eine Zeile),
  - ist die Summe der gesperrten Sitze größer als 0, danach eine Leerzeile und „Gesperrt, nicht mitgezählt: k“,
  - ohne Vorstellung (Event ohne Vorstellungen) „Keine Vorstellung“.

### Shell (`TicketShellView.fxml`, `TicketShellController`)

- Die Beschriftung über `presComboBox` wird eine Zeile wie über dem Suchfeld: eine `HBox` (`minWidth 0`,
  `prefWidth 0`) mit dem Label „Vorstellung“ (`minWidth 0`), einem wachsenden `Region` (`minWidth 4`) und einem neuen
  `Label fx:id="lblFreeSeats"` (Stilklasse `field-caption`, `minWidth -Infinity`). Bei Platzmangel wird
  „Vorstellung“ gekürzt, nicht die Zahl. `prefWidth` und `minWidth` von `presComboBox` bleiben, wie sie sind; auch
  „Event“ und „Suche“ ändern sich nicht. Dass „Vorstellung“ bei langen Zahlen (vierstellige Summen bei „Alle
  Vorstellungen“) schon in der Vorzugsbreite gekürzt wird, ist hingenommen: Das Auswahlfeld darunter nennt die
  Vorstellung ohnehin. Zeigt der Handtest, dass die Zahl bei der kleinsten Fensterbreite über die Zeile hinausragt
  und die Beschriftung „Suche“ überdeckt (Abnahme 9), ändert der Umsetzer keine Breiten, sondern legt den Fall dem
  Maintainer vor.
- Ohne gewähltes Event ist der Text von `lblFreeSeats` leer. Mit Event zeigt es `labelText` und trägt einen Tooltip
  mit `tooltipText`.
- Eine Methode der Shell berechnet beides aus `state.eventProperty()` und `state.presentationProperty()` neu. Sie
  läuft:
  1. am Ende von `applyEventSelection`, also nach `BookingSyncService.prepare` (nicht nur über einen Listener auf
     `eventProperty()`, siehe Ausgangslage); das deckt auch das Wiederherstellen nach „Nicht speichern“,
  2. bei jeder Änderung von `state.presentationProperty()`,
  3. wenn der `ChangeTracker` der Events eine Änderung am gezeigten Event meldet (eigene Änderungen),
  4. wenn das Event-Repository `saved` für das gezeigte Event meldet (eigene Änderungen, die gespeichert wurden,
     bevor der Tracker sie verglichen hat, siehe Ausgangslage),
  5. in `foreignChanges` nach `selectionSync.sync()` (Änderungen anderer Rechner an Events),
  6. bei einer Änderung anderer Rechner an einer Halle: ein neuer gebündelter Listener am `StoreHallRepository`
     über `LiveViews.onFxThread`, für jede Halle (`LiveViews.ALL_UNITS`),
  7. wenn sich die Sperren ändern (`LockedSeats`),
  8. beim Anzeigen des Tooltips (`setOnShowing`).
- Die Listener aus 3, 4, 6 und 7 werden wie `foreignChanges` über `LiveViews.whileShown(contentArea, …)` an- und
  abgemeldet, damit weder Tracker noch Repositories noch `LockedSeats` eine verlassene Shell festhalten.
- „Das gezeigte Event“ in 3 und 4 wird mit `equals` verglichen, wie in `CustomerDisplay.onDataChanged`.
- Beim Eventwechsel können 2 und 3 vor dem Abgleich laufen (siehe Ausgangslage). Das ist unschädlich, solange 1
  danach im selben Aufruf rechnet; wer Auslöser bündelt, hält diese Reihenfolge.
- Die Berechnung läuft immer auf dem FX-Thread.
- Nach einer eigenen Änderung folgt die Zahl mit dem Vergleich des `ChangeTrigger`, also etwa 0,3 s nach der letzten
  Eingabe, oder mit dem Speichern, je nachdem, was zuerst kommt. Das genügt.

Tabelle, Saalansicht, `SalesViewState` und Kundenfenster ändern sich nicht.

### Doku und Patchnotes

- `docs/usage.md`, nach dem Absatz „Search“: zwei Sätze zur Anzeige, zur Zählregel (gesperrte Sitze und Buchungen
  ohne Platz zählen nicht) und zum Tooltip.
- `docs/decisions.md`: ein kurzer Abschnitt „Free seats in the sales view (KARV-215)“ mit der Zählregel und dem
  Grund, warum die Shell selbst zählt.
- `docs/structure.md` Zeile 39: die Zahl bei `sales/` auf den tatsächlichen Stand nach der Änderung setzen (37).
  Beim Vorwärts-Merge nach `release/1.2.0` gibt es an dieser Zeile einen Konflikt; richtig ist dort der Stand von
  1.2.0 plus eins.
- `docs/testing.md` Zeile 56: `FreeSeatsTest` ergänzen.
- `patchnotes.md`: unter `## 1.1.2` ein Abschnitt `### Neu` vor `### Behoben` mit einem vorläufigen Eintrag in
  einfacher Sprache.

### Tests

- `FreeSeatsTest` (ohne JavaFX):
  - freie, reservierte und bezahlte Sitze: n und m,
  - Buchung ohne Platz zählt nicht, auch nicht mit Namen,
  - gesperrter Sitz (über `LockedSeats.install` mit einer gesperrten Einheit, wie in `LockedSeatsTest`) zählt weder
    in n noch in m, aber in „gesperrt“,
  - Buchung mit Resten ohne Nachnamen ist frei,
  - `getBookings()` und `getPresentations()` `null`,
  - Summe über mehrere Vorstellungen,
  - `labelText` für 0 von 0 und für übliche Werte,
  - `tooltipText`: eine und mehrere Vorstellungen in der Reihenfolge des Events, Zeile „Gesperrt“ nur bei k > 0,
    „Keine Vorstellung“.
- Die Anbindung in der Shell ist JavaFX-Code und wird im Handtest geprüft (siehe Abnahme).

## Der Umsetzung überlassen

- Namen und Signaturen in `FreeSeats`.
- Ob die Shell für Punkt 2 einen Listener oder die vorhandenen Stellen nutzt, an denen sie die Vorstellung setzt.
- Ob mehrere Auslöser im selben Takt zu einer Berechnung zusammengefasst werden.
- Ob Auslöser 4 ein eigener Listener am Event-Repository ist oder mit `foreignChanges` in einem Listener liegt.
- Ob das Label ohne Event keinen Tooltip trägt oder einen mit leerem Text.
- Die Wortwahl in Doku und vorläufigem Patchnotes-Eintrag.

## Abnahme

1. In Kartentabelle und Saalansicht steht rechts über dem Auswahlfeld „Vorstellung“ „frei: n von m“. Ohne gewähltes
   Event steht dort nichts.
2. Für eine gewählte Vorstellung mit vorhandener Bestuhlung und ohne gesperrte Sitze ist m die Zahl der Sitze ihrer
   Bestuhlung und n die Zahl der Sitze, die die Saalansicht als „Frei“ zeichnet.
3. Bei „Alle Vorstellungen“ zeigt die Anzeige die Summen; der Tooltip nennt je Vorstellung eine Zeile
   „<Name>: n von m“ in der Reihenfolge des Auswahlfelds.
4. Tippen im Suchfeld, ein Spaltenfilter und der Wechsel zwischen Tabelle und Saalansicht ändern die Anzeige nicht.
5. Eine Reservierung, ein Verkauf oder ein Storno am eigenen Rechner ändert n innerhalb einer Sekunde, ohne dass die
   Ansicht gewechselt wird. Das gilt auch, wenn in der Kartentabelle ein Nachname in eine Zelle getippt und ohne
   Verlassen der Zelle mit Strg+S gespeichert wird, mit „Buchungen sofort speichern“ auf Ja und auf Nein.
6. Eine Reservierung, die ein anderer Rechner gespeichert hat, senkt n ohne Neuladen. Ein Sitz, den ein anderer
   Rechner der Bestuhlung hinzufügt, erhöht n und m ohne Neuladen.
7. Ein gesperrter Sitz zählt weder in n noch in m; der Tooltip nennt dann „Gesperrt, nicht mitgezählt: k“. Nach dem
   Entsperren stimmen n und m wieder, ohne dass die Ansicht gewechselt wird.
8. Eine Buchung ohne Platz ändert weder n noch m.
9. Die Felder der Kopfzeile sind bei derselben Fensterbreite so breit wie vorher. Bei der kleinsten Fensterbreite
   (1250 px) und einer vierstelligen Summe („Alle Vorstellungen“) darf „Vorstellung“ gekürzt sein; die Zahl ist
   vollständig lesbar und überdeckt die Beschriftung „Suche“ nicht (Handtest unter Windows).
10. Das Kundenfenster zeigt die Anzeige nicht.
11. `./mvnw test` ist grün, mit `FreeSeatsTest`.

## Prüfvermerk

**Runde 1 (25.11.2026):** Umsetzer 0 kritisch, 0 groß, 4 klein; Gegenprüfer 0 kritisch, 1 groß, 5 klein.

Behoben:

- Groß (Gegenprüfer; vom Umsetzer als klein gemeldet): Eine eigene Änderung, die gespeichert wird, bevor der
  `ChangeTrigger` verglichen hat, erreicht die Listener des Trackers nie. Neuer Auslöser 4 (`saved` des
  Event-Repositorys), Ausgangslage und Abnahme 5 ergänzt.
- Klein übernommen:
  - Änderung der Bestuhlung durch einen anderen Rechner (beide): neuer Auslöser 6 am Hallen-Repository, Abnahme 6.
  - Vorstellung mit fehlender Bestuhlung (beide): Ausgangslage berichtigt, Zählregel sagt, dass gezählt wird, was im
    Modell steht; Abnahme 2 eingeschränkt.
  - Breite der Kopfzeile (beide): Die Regel, `prefWidth` zu erhöhen, ist gestrichen, weil sie „Event“ und „Suche“
    früher schrumpfen ließe. Kein Feld ändert seine Breite; „Vorstellung“ wird bei langen Zahlen gekürzt. Abnahme 9
    misst mit einer vierstelligen Summe.
  - Anlass (Gegenprüfer): Die Statistik zeigt keine Zahl freier Karten; berichtigt, Abweichung von `BookingTotals`
    in der Zählregel genannt.
  - Wiederherstellen (Gegenprüfer): läuft über Auslöser 1, nicht über den Tracker; berichtigt.
  - Zeilennummer `CustomerDisplay.onDataChanged` (390).

Nicht übernommen: keine.

**Runde 2 (25.11.2026):** Umsetzer 0 kritisch, 0 groß, 4 klein; Gegenprüfer 0 kritisch, 0 groß, 4 klein. Prüfung
beendet.

Übernommen (klein): Vorgehen, falls die Zahl bei kleinster Fensterbreite nicht passt (Maintainer entscheidet, keine
Breitenänderung); Abnahme 9 mit „darf gekürzt sein“ und der Mindestbreite des Fensters; Reihenfolge der Auslöser
beim Eventwechsel und `tracker.check` in `prepare`; Vergleich des gezeigten Events mit `equals`; Tooltip ohne Event
der Umsetzung überlassen; „gesperrt“ meint nur `LockedSeats.isLocked`; Konflikt in `docs/structure.md` beim
Vorwärts-Merge.

Nicht übernommen: keine.

Nicht belegt:

- Die Breiten in der Kopfzeile: ob „Vorstellung“ und die Zahl bei 160 px nebeneinander passen und ob die Zahl bei
  der Mindestbreite des Feldes (100 px) in die Zeile passt. Beide Prüfer haben nur gerechnet; das prüft der
  Handtest.
- Linux: Dort greift eine Ersatzschrift mit anderen Breiten. Ein Handtest unter Linux ist nicht vorgesehen.
- Dass eine Tabellenzelle bei Strg+S ihren Wert übernimmt, bevor gespeichert wird, ist nur am Code gelesen; Abnahme
  5 prüft es im Handtest.
- Ob eine Vorstellung, deren Kopf ein Rechner mit neuerer Programmversion gespeichert hat, im Modell bleibt und
  ihre Sitze dann als frei zählen.
