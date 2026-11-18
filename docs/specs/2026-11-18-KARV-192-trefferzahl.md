# Trefferzahl neben dem Suchfeld (KARV-192)

Stand: Entscheidung vom 18.11.2026. Ein Spec ist eine Momentaufnahme und wird nach dem Merge nicht gepflegt; was
dauerhaft gilt, steht in `docs/decisions.md` und der übrigen Dokumentation.

## Anlass und Ziel

Rückmeldung aus dem Verkauf: Bei der Namenssuche möchte die Kassenkraft sehen, wie viele Ergebnisse es gibt, um zu
prüfen, ob ein Kunde die richtige Anzahl Karten bekommen hat. Heute filtert die Suche die Kartentabelle und blendet in
der Saalansicht die übrigen Sitze ab, eine Anzahl steht nirgends.

Ziel: Über dem Suchfeld der Verkaufsansicht steht immer „n Treffer“, wobei n die Zahl der gerade gezeigten Treffer
ist; ein Tooltip auf der Zahl schlüsselt sie nach Name und, bei „Alle Vorstellungen“, nach Vorstellung auf.

## Ziele, Nicht-Ziele, Anti-Ziele

**Ziele:**

- Die Zahl steht in Kartentabelle und Saalansicht, auch bei leerem Suchfeld.
- Sie zählt genau, was die Ansicht als Treffer zeigt (Entscheidung des Entwicklers, siehe „Betrachtete Wege“).
- Sie folgt dem Tippen, dem Wechsel von Event, Vorstellung und Ansicht, eigenen Änderungen und Änderungen anderer
  Rechner.
- Ein Tooltip nennt die Treffer je Name und, wenn „Alle Vorstellungen“ gewählt ist, je Vorstellung.

**Nicht-Ziele:**

- Keine Aufteilung der Zahl selbst nach Vorstellung, nur im Tooltip.
- Keine Änderung an der Suche (`BookingSearch`), an der Filterung der Tabelle oder am Abblenden im Saal.
- Keine Zahl in anderen Suchfeldern (Einstellungen, Event- und Hallenverwaltung) und nicht im Kundenfenster.
- Kein Zusammenspiel mit einer Gruppierung der Tabelle (KARV-193, eigenes Ticket).

**Anti-Ziele:**

- Die Zahl weicht von dem ab, was die Ansicht zeigt (z. B. nach einer Änderung eines anderen Rechners).
- Die Kopfzeile der Verkaufsansicht wird breiter, oder das Suchfeld schrumpft früher als heute.
- Der Tooltip wird bei leerem Suchfeld so lang, dass er den Bildschirm füllt.

## Randbedingungen

- Patch-Version 1.1.1: nichts am Datenformat, nichts an gespeicherten Daten. Die Änderung betrifft nur die
  Oberfläche.
- Schichtregeln aus `docs/architecture.md` („Layered architecture“): Alles bleibt in `ui.sales`.
- Logik außerhalb von JavaFX, damit sie ohne Toolkit testbar ist (`docs/testing.md`, „Logic out of JavaFX“).
- Texte deutsch, Doku englisch (`CONTRIBUTING.md`, „Sprache“).

## Ausgangslage

Geprüft am 18.11.2026 auf `origin/release/1.1.0` (825d66d).

- **Suchfeld:** `TicketShellView.fxml` Zeile 40–43: `VBox` mit `Label text="Suche"` (Stilklasse `field-caption`) über
  `TextField fx:id="searchField"` (`prefWidth 160`, `minWidth 90`). Event und Vorstellung haben dieselbe Form.
- **Weitergabe der Suche:** `TicketShellController.initialize` (Listener auf `searchField.textProperty()`) ruft
  `activeContentController.filter(searchText)`; nach jedem Ansichtswechsel ebenso (`showTicketTableView`,
  `showHallView`).
- **Gemeinsamer Zustand:** `SalesViewState` trägt Event, Vorstellung, sichtbaren Ausschnitt und Auswahl; Shell und
  Inhalte schreiben ihn, das Kundenfenster liest. `resetView()` leert beim Ansichtswechsel Auswahl und Ausschnitt.
  Die Shell erzeugt ihn (`state`), die Inhalte bekommen ihn über `SalesStateAware.setSalesState` vor `setEvent` und
  `setPresentation` (`TicketShellController.loadContentView`).
- **Kartentabelle:** `TicketTableController.initialize` setzt `filteredData` (`FilteredList` über `masterData`, Zeile
  190 f.) als Items und legt danach die Spaltenkopf-Filter von ControlsFX an (`TableFilter.forTableView(seatTable)
  .apply()`, Zeile 194). Diese ersetzen die Items der Tabelle durch eine eigene Liste
  (`SortedList(FilteredList(SortedList(filteredData)))`). Die Tabelle zeigt also, was nach Suche **und** Spaltenfilter
  übrig bleibt, in der Sortierung der Ansicht: `seatTable.getItems()`, nicht `filteredData`.
  `buildRows` liefert je gezeigter Vorstellung alle Buchungen aus `shownBookings`: jede Buchung der Vorstellung, also
  auch freie Sitze (nach dem Abgleich hat jeder Sitz eine Buchung, `HallPlanPane.drawPlan`) und Buchungen ohne Platz,
  ohne gesperrte Sitze (`LockedSeats.isLocked`). Bei „Alle Vorstellungen“ (`selectedPres == null`) sind es die Zeilen
  aller Vorstellungen des Events. `filter` setzt ein neues Prädikat mit `BookingSearch.matches`. Änderungen anderer
  Rechner laufen über `refreshAfterForeignChange` → `showRows`: Bei denselben Buchungen wird bei nicht leerer Suche
  erneut `filter` angewandt und neu gezeichnet, bei leerer Suche nur neu gezeichnet; bei anderen Buchungen
  `masterData.setAll`. Eigene Änderungen in einer Zelle zeichnen meist nur neu (`refreshAfterCommit`), das
  Prädikat wird dabei nicht neu geprüft; wird dabei eine Buchung ohne Platz leer, baut `removeCancelled` →
  `showRows` die Zeilen neu.
- **Saalansicht:** `HallOverviewController.renderHall` leert `seatNodes` und zeichnet über `HallPlanPane.render`; für
  jeden Sitz mit Buchung ruft `HallPlanPane.drawPlan` den Decorator, der in `styleSeatNode` die Buchung in
  `seatNodes` einträgt und Nicht-Treffer auf Deckkraft 0,25 setzt (`matchesQuery`). Ohne Vorstellung zeichnet
  `render` keine Sitze. Buchungen ohne Platz erscheinen im Saal nicht. Gesperrte Sitze werden grau gezeichnet und
  gehen ebenfalls durch `matchesQuery`. `renderHall` läuft nach Suche, Vorstellungs- und Eventwechsel, eigenen
  Änderungen (Popup), Änderungen anderer Rechner an Event oder Halle und Änderungen im Menü „Anzeige“.
- **Verlassene Ansichten laufen weiter:** `HallOverviewController.setSalesState` hängt `renderHall` über
  `labelOptions.follow(viewportPane, …)` und `LiveViews.follow(viewportPane, seatPatterns, …)` an die gemeinsamen
  Einstellungen des Menüs „Anzeige“ (Zeile 449–453). Das sind schwache Listener, die bis zur Garbage Collection
  bestehen und beim Wechsel der Ansicht nicht abgemeldet werden. Das Menü ist auch in der Tabelle bedienbar
  (`TicketShellController.initialize`). Eine verlassene Saalansicht kann also noch zeichnen, wenn die Tabelle aktiv
  ist; ebenso kann ein ausstehendes `Platform.runLater` der verlassenen Tabelle noch laufen.
- **Gesperrte Sitze** (`LockedSeats.isLocked`) sind nur freie, nicht belegte Buchungen mit Sitz (Zeile 62). Meist
  tragen sie keinen Namen; Reste einer alten Buchung (`Booking.hasLeftovers`) können aber Name oder Kommentar tragen.
- **Version:** `pom.xml` und `conveyor.conf` stehen auf 1.1.0, der oberste Abschnitt von `patchnotes.md` ist
  `## 1.1.0`. `PatchNotesBundledTest` verlangt, dass die oberste Version der Patchnotes zu pom und Conveyor passt.
  Den Versionssprung auf 1.1.1 (`chore/version-1.1.1`, `CONTRIBUTING.md` „Bugfixes und Patch-Versionen“, Schritt 1)
  gibt es noch nicht.
- **Vorbild für Zahlen:** `SelectionSummary.countText` (Kundenfenster) und „Keine Treffer“ in
  `SettingsController` (Zeile 138).
- **Doku:** `docs/usage.md` Zeile 97 beschreibt die Suche.

## Betrachtete Wege

**Was gezählt wird** (Entwickler, 18.11.2026):

1. *Genau die gezeigten Treffer* — gewählt. Tabelle: Zeilen, die die Tabelle zeigt (nach Suche und Spaltenfilter);
   Saal: nicht abgeblendete Sitze. Bei
   leerem Suchfeld also alle Sitze einschließlich freier. Tabelle und Saal können sich für dieselbe Vorstellung
   unterscheiden, unter anderem bei Buchungen ohne Platz, gesperrten Sitzen, Spaltenfiltern, mehreren Buchungen auf
   einem Sitz (der Saal zeigt nur die erste, `HallPlanPane.bookingsBySeat`) und Buchungen auf Sitzen außerhalb des
   Plans.
2. *Nur belegte Karten* (reserviert oder bezahlt) — nicht gewählt. Für die Kontrolle „richtige Anzahl“ gleichwertig,
   weil freie Sitze ohne Namen bei einer Namenssuche ohnehin nicht passen; weicht aber von dem ab, was man sieht.

**Wo die Zahl steht** (Entwickler): rechts in der Beschriftungszeile über dem Suchfeld, nicht rechts neben dem Feld,
damit die Kopfzeile nicht breiter wird.

**Wie die Zahl zur Shell kommt** (Entwickler, auf Empfehlung):

- A: *Trefferliste im `SalesViewState`* — gewählt. Tabelle und Saal schreiben die gezeigten Treffer hinein, die Shell
  zeigt Zahl und Tooltip. Folgt dem bestehenden Muster (Auswahl, Ausschnitt), braucht keine neue Schnittstelle.
- B: `ContentController` liefert eine beobachtbare Trefferliste — ändert ein allgemeines Interface für ein Merkmal nur
  des Verkaufs.
- C: Die Shell fragt nach `filter()` nach — Änderungen anderer Rechner kämen nicht an; verworfen.

## Entwurf

### `SalesViewState`

Neu:

- `ObservableList<Booking> searchHits()` — nur lesend gedacht (nach außen unveränderbar): die Buchungen, die die
  aktive Ansicht gerade als Treffer zeigt, in der Reihenfolge der Ansicht.
- `setActiveView(Object view)` — die Shell meldet den Inhalts-Controller, der gerade zu sehen ist.
- `showSearchHits(Object view, List<Booking> hits)` — ersetzt die Liste, aber nur, wenn `view` die aktive Ansicht
  ist; sonst geschieht nichts. Damit kann eine verlassene Ansicht (siehe Ausgangslage) die Zahl nicht mehr
  verfälschen, gleich wie lange sie noch lebt.
- `resetView()` leert zusätzlich `searchHits` (die aktive Ansicht bleibt).

`TicketShellController.loadContentView` ruft `state.setActiveView(controller)` direkt nach dem Laden, vor
`resetView` und `setSalesState`.

### `SearchHits` (neu, `ui.sales`, ohne JavaFX)

Statische Hilfen, aus denen die Shell Text und Tooltip baut:

- `countText(int n)`: „n Treffer“ (auch „0 Treffer“ und „1 Treffer“).
- `tooltipText(List<Booking> hits, List<Presentation> presentations, boolean byPresentation)`:
  - leer → „Keine Treffer“.
  - Abschnitt „Nach Name:“, je Name eine Zeile „<Name>: <Anzahl>“. Name: Nachname und Vorname, getrimmt, mit „, “
    verbunden, leere Teile entfallen („Müller, Hans“, „Müller“, „Hans“); sind beide leer, „(ohne Namen)“ (dazu
    gehören freie Sitze und meist auch gesperrte). Namen gelten als gleich, wenn sie nach dem Trimmen ohne Groß- und
    Kleinschreibung übereinstimmen (`Locale.GERMAN`); angezeigt wird die Schreibweise des ersten Treffers in der
    Reihenfolge der Liste. Reihenfolge: alphabetisch nach `Collator` für Deutsch,
    „(ohne Namen)“ zuletzt. Höchstens 15 Namenszeilen; darüber hinaus folgt „… und k weitere“ (k = Zahl der nicht
    gezeigten Namen, „(ohne Namen)“ eingeschlossen, wenn es abgeschnitten wurde).
  - Nur wenn `byPresentation`: Leerzeile, Abschnitt „Nach Vorstellung:“, je Vorstellung aus `presentations` mit
    mindestens einem Treffer „<Name>: <Anzahl>“ in der Reihenfolge von `presentations`; Name über
    `EntityLabels.nameOrPlaceholder`. Zugeordnet wird über `Booking.getParent()`.

### Shell (`TicketShellView.fxml`, `TicketShellController`)

- Die Beschriftung über dem Suchfeld wird eine Zeile (`HBox`): links „Suche“ wie bisher, rechts ein neues
  `Label fx:id="lblSearchHits"` mit Stilklasse `field-caption`, dazwischen ein wachsender Abstand. Die Zeile darf
  weder Mindest- noch Vorzugsbreite der `VBox` erhöhen: Die `HBox` bekommt `minWidth="0"` und `prefWidth="0"` (die
  `VBox` zieht sie auf die Breite des Suchfelds), das Label `minWidth="0"`; ist kein Platz, kürzt es mit „…“.
  Bei Platzmangel wird „Suche“ gekürzt, nicht die Zahl (`minWidth="-Infinity"` auf der Zahl). Nachtrag aus dem
  Code-Review: Ursprünglich stand hier das Umgekehrte; dann wäre bei schmalem Fenster die Zahl unlesbar geworden.
- Text: `SearchHits.countText(state.searchHits().size())`, aktualisiert bei jeder Änderung der Liste; beim Start
  „0 Treffer“.
- Tooltip auf dem Label mit dem Starttext „Keine Treffer“. Sein Text wird bei jeder Änderung der Liste und zusätzlich
  bei jedem Anzeigen (`setOnShowing`) aus dem aktuellen Stand neu gebaut, damit eigene Änderungen an Namen ohne
  Neuaufbau der Liste darin stimmen. `byPresentation` ist wahr, wenn
  `state.presentationProperty().get() == null`; `presentations` sind die Vorstellungen des gewählten Events (leer ohne
  Event oder wenn `getPresentations()` `null` ist).

### Kartentabelle (`TicketTableController.setSalesState`)

Ein Listener auf `seatTable.getItems()` (die Liste, die die Tabelle nach Anlegen der Spaltenfilter zeigt) meldet bei
jeder Änderung die Buchungen der Zeilen, in der Reihenfolge der Tabelle, mit `state.showSearchHits(this, …)`; direkt
beim Setzen des Zustands einmal den aktuellen Stand. Ersetzt jemand die Items später (`itemsProperty`), hängt der
Listener an die neue Liste. Damit folgt die Zahl Suche, Spaltenfilter, Sortierung, Event- und Vorstellungswechsel,
Storno und Änderungen anderer Rechner, wie die Zeilen selbst. Bleibt eine Zeile nach einer eigenen Änderung stehen,
obwohl sie nicht mehr passt (heutiges Verhalten, Prädikat wird nicht neu geprüft), zählt sie weiter: Gezählt wird, was
zu sehen ist.

### Saalansicht (`HallOverviewController`)

Am Ende von `renderHall` werden die Buchungen aus `seatNodes`, die `matchesQuery` erfüllen, mit
`state.showSearchHits(this, …)` gemeldet (ohne gesetzten Zustand nichts). Eine verlassene Saalansicht, die über das
Menü „Anzeige“ noch zeichnet, wird dabei vom Zustand ignoriert. Reihenfolge: wie `TableLabel.BOOKING_ORDER`. Gesperrte Sitze zählen,
wenn sie zur Suche passen (sie sind nicht abgeblendet). Ohne Vorstellung ist die Liste leer („0 Treffer“).

### Doku und Patchnotes

- `docs/usage.md`, Absatz „Search“: ein Satz zur Trefferzahl und zum Tooltip.
- `docs/structure.md`: Zahl der Klassen in `ui/sales` (`sales/ (31)`) um eins erhöhen.
- `docs/testing.md`: `SearchHitsTest` in der Tabelle der Tests von `ui.sales` ergänzen.
- `src/main/resources/de/eltviller_carneval_verein/karten/patchnotes.md`: vorläufiger Eintrag unter `## 1.1.1`,
  „Neu“.
- **Voraussetzung:** Den Abschnitt `## 1.1.1` und den Versionssprung von pom und Conveyor legt der eigene Pull
  Request `chore/version-1.1.1` in `release/1.1.0` an, nicht diese Änderung. Ist er bei der Umsetzung noch nicht
  gemergt, entsteht der Code trotzdem; der Eintrag in den Patchnotes und der Pull Request von KARV-192 folgen erst,
  nachdem der Branch auf den Stand mit dem Versionssprung gebracht ist. Bis dahin bleibt `patchnotes.md` unberührt,
  damit `PatchNotesBundledTest` grün bleibt.

### Tests

- `SearchHitsTest`: `countText` für 0, 1, viele; Tooltip leer, Namen mit und ohne Vorname, ohne Namen, Sortierung
  mit Umlauten, Grenze 15 und „… und k weitere“, Abschnitt Vorstellungen nur mit `byPresentation`, Vorstellungen ohne
  Treffer fehlen.
- `SearchHitsTest` zusätzlich: „Müller“ und „müller“ ergeben eine Zeile; leere `presentations` ohne Abschnitt.
- `SalesViewStateTest`: `showSearchHits` der aktiven Ansicht ersetzt die Liste, einer anderen Ansicht ändert nichts;
  `resetView` leert `searchHits`.
- Die Anbindung in Tabelle und Saal ist JavaFX-Code und wird im Handtest geprüft (siehe Abnahme).

## Der Umsetzung überlassen

- Name und genaue Signatur der Hilfsklasse und ihrer Methoden.
- Ob die Shell die Zahl über eine Bindung oder einen Listener setzt.
- Ob die Tabelle die Liste bei jedem Ereignis neu setzt oder Änderungen zusammenfasst, solange die Zahl nach jedem
  abgeschlossenen Schritt stimmt.

## Abnahme

1. In Kartentabelle und Saalansicht steht rechts über dem Suchfeld „n Treffer“, auch bei leerem Suchfeld und ohne
   gewähltes Event („0 Treffer“).
2. Tabelle: n ist die Zahl der Tabellenzeilen, auch mit gesetztem Spaltenfilter. Saal: n ist die Zahl der nicht
   abgeblendeten Sitze.
3. Die Zahl ändert sich beim Tippen, beim Setzen eines Spaltenfilters, beim Wechsel von Event, Vorstellung und
   Ansicht, nach einem Storno und nach einer Änderung, die ein anderer Rechner gespeichert hat, sofern sich damit
   ändert, was zu sehen ist (bei leerem Suchfeld bleibt nach einem Storno mit Platz der freie Sitz stehen; im
   Handtest daher mit Namenssuche prüfen).
4. Nach Saal → Tabelle ändert ein Umschalten im Menü „Anzeige“ die Zahl der Tabelle nicht.
5. Der Tooltip nennt die Treffer je Name; bei „Alle Vorstellungen“ zusätzlich je Vorstellung. Er hat höchstens 15
   Namenszeilen.
6. Die Kopfzeile ist bei derselben Fensterbreite nicht breiter als vorher; das Suchfeld behält seine Mindestbreite.
7. Das Kundenfenster zeigt keine Trefferzahl.
8. `./mvnw test` ist grün, mit den Tests aus „Tests“.

## Prüfvermerk

**Runde 1 (18.11.2026):** Umsetzer 1 kritisch, 0 groß, 2 klein; Gegenprüfer 0 kritisch, 3 groß, 3 klein.

Behoben:

- Verlassene Ansicht schreibt weiter in die Trefferliste (beide Prüfer): `setActiveView` und
  `showSearchHits(view, …)` im `SalesViewState`, Ausgangslage ergänzt, Abnahme 4 neu.
- Spaltenfilter von ControlsFX (Gegenprüfer): gezählt wird `seatTable.getItems()`, Ausgangslage und Abnahme 2/3
  ergänzt.
- Patchnotes ohne Versionssprung (beide): Voraussetzung `chore/version-1.1.1` festgehalten.
- Klein übernommen: Vorzugsbreite der Beschriftungszeile, gesperrte Sitze im Tooltip, Starttext und Neubau des
  Tooltips, Groß- und Kleinschreibung bei Namen, `getPresentations()` `null`, Beschreibung von `showRows`.

Nicht übernommen: keine.

**Runde 2 (18.11.2026):** Umsetzer 0 kritisch, 0 groß, 3 klein; Gegenprüfer 0 kritisch, 0 groß, 5 klein. Prüfung
beendet.

Übernommen (klein): Abnahme 3 zum Storno bei leerem Suchfeld, „Suche“ wird nicht gekürzt, gesperrte Sitze können Reste
mit Namen tragen, weitere Unterschiede zwischen Tabelle und Saal, `removeCancelled` bei eigenen Änderungen, Pfad der
Patchnotes, `docs/structure.md` und `docs/testing.md`.

Nicht übernommen:

- Keine Grenze für die Zeilen „Nach Vorstellung“: Ein Event hat wenige Vorstellungen.
- `TicketTableController.restoreSelection` sucht Indizes in `filteredData`, wählt aber in `seatTable.getItems()` aus
  (alter Fehler bei Sortierung oder Spaltenfilter): außerhalb dieses Specs, eigenes Ticket.

Nicht belegt: wie breit die Beschriftungszeile mit `field-caption` wird und ob ein Tooltip mit in `setOnShowing`
gesetztem Text seine Größe richtig berechnet; beides prüft der Handtest.
