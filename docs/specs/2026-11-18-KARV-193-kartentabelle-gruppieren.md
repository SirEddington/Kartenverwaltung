# Kartentabelle nach Spalten gruppieren (KARV-193)

Stand: Entscheidung vom 18.11.2026. Ein Spec ist eine Momentaufnahme und wird nach dem Merge nicht gepflegt; was
dauerhaft gilt, steht in `docs/decisions.md` und der übrigen Dokumentation.

## Anlass und Ziel

Rückmeldung aus dem Verkauf: „Filtermöglichkeit, wer x viele Karten bestellt hat (vereinfacht tauschen)“. Heute
zeigt die Kartentabelle eine flache Liste von Buchungen. Wer alle Kunden mit vier Karten sucht, etwa um Plätze zu
tauschen (KARV-140), muss die Tabelle nach Namen sortieren und von Hand zählen.

**Ziel:** Die Kassenkraft gruppiert die Kartentabelle nach frei gewählten Spalten in frei gewählter Reihenfolge. Jede
Gruppe zeigt ihre Größe, lässt sich nach Größe ordnen, auf- und zuklappen und mit einem Klick ganz auswählen. Dafür
wird die Kartentabelle eine `TreeTableView`; Bedienung, Bearbeiten und Spaltenfilter bleiben wie heute.

## Ziele, Nicht-Ziele, Anti-Ziele

**Ziele:**

- Gruppieren nach einer oder mehreren Spalten der Kartentabelle, in der Reihenfolge der Auswahl, als Ebenen.
- Jeder Gruppenkopf nennt Spalte, Wert und Zahl der Buchungen darunter.
- Gruppen ordnen nach Wert oder nach Größe.
- Gruppen auf- und zuklappen. Ein Klick auf den Gruppenkopf wählt die Gruppe, und eine gewählte Gruppe zählt für
  Verkauf, Stornieren, Kundenfenster und Saalansicht als alle ihre Buchungen.
- Die Gruppierung gilt beim nächsten Start wieder, wenn „Anzeige beim Start wiederherstellen“ an ist (KARV-76).
- Die Spaltenfilter der Kartentabelle bleiben in gleicher Bedienung erhalten (eigener Nachbau, weil ControlsFX für
  `TreeTableView` keinen hat).
- Ohne Gruppierung verhält sich die Kartentabelle wie heute, mit zwei gewollten Änderungen (Abschnitt 11):
  - Eine geänderte Buchung (eigene oder fremde Änderung) zieht nach dem Ende der Bearbeitung an ihre neue Stelle in
    der Sortierung, und Spaltenfilter gelten nach einer fremden Änderung neu. Heute bleibt sie stehen, weil die Listen
    von ControlsFX ohne Listenänderung nicht neu sortieren und filtern (`TableFilter.java` Zeilen 72–78, ohne
    Extractor). Mit Gruppen ginge das nicht: Köpfe und Anzahlen wären sonst falsch.
  - Eine selbst geänderte Buchung bleibt trotz Suche und Filter sichtbar.

**Nicht-Ziele:**

- Gruppieren in anderen Tabellen (Event-Verwaltung, Konfliktdialog) oder in der Saalansicht.
- Gruppieren nach Bestellung (kommt mit KARV-185).
- Summen je Gruppe außer der Anzahl (etwa Summe der Preise).
- Merken, welche Gruppen auf- oder zugeklappt waren, über das Neuladen der Kartentabelle hinaus.
- Die übrigen Tabellen mit Spaltenfilter (`EventEditController`) behalten ControlsFX und werden nicht umgebaut.

**Anti-Ziele:**

- Die Excel-artige Bedienung (`TableInput`: Klick, Tab, Tippen, Ziehen) oder die Fehlerbehandlung der Textzellen
  (`ParsingTextFieldTableCell`) verhält sich in der Kartentabelle anders als heute oder in der Event-Verwaltung.
- Eine Eingabe landet in der falschen Buchung, weil sich die Zeilen unter einer laufenden Bearbeitung verschieben.
- Eine fremde Änderung reißt eine laufende Bearbeitung weg (heute verhindert über `EditingUnits` und
  `refreshAfterForeignChange`).
- Ein Verkauf oder ein Storno trifft Buchungen, die die Kassenkraft nicht gemeint hat: Wer eine zugeklappte Gruppe
  wählt, muss die Zahl der betroffenen Buchungen sehen, bevor etwas geschieht.
- Die Logik der Eingabehilfen liegt am Ende zweimal im Code, einmal für `TableView` und einmal für `TreeTableView`.

## Randbedingungen

- **Version:** Ziel ist 1.2.0 (Fix-Version `v1.2`), Arbeits-Branch `feature/KARV-193-kartentabelle-gruppieren` von
  `release/1.2.0`. Am Datenformat des gemeinsamen Ordners ändert sich nichts.
- **Einstellungen:** Die Gruppierung steht nur in der lokalen `settings.json` dieses Rechners. Eine ältere Version
  ignoriert die neuen Felder (`DisplaySettings` ist `@JsonIgnoreProperties(ignoreUnknown = true)`).
- **Schichten:** Die Entscheidungen (Gruppenschlüssel, Reihenfolge, Filter, Zählen) liegen ohne JavaFX in `ui.sales`
  und werden direkt getestet (`docs/testing.md`, „Logic out of JavaFX“). `settings` kennt die Oberfläche nicht:
  Spalten stehen dort als Text.
- **JavaFX 21.0.2, ControlsFX 11.2.1** (`pom.xml`); keine neue Abhängigkeit.
- **Sprache:** Oberfläche, Meldungen und Code-Kommentare deutsch.
- **KARV-196** (Auswahl in sortierter Tabelle) ist auf der Wartungslinie behoben und kommt per Vorwärts-Merge nach
  `release/1.2.0`. Dieses Spec ersetzt `restoreSelection` und `select(TableView…)`. Den Fall aus dessen Test
  (sortierte oder gefilterte Tabelle, Rückkehr aus der Saalansicht wählt die richtigen Buchungen) schreibt der Umbau als
  Test für die Baumtabelle neu; der alte Test mit ControlsFX entfällt mit dem Code. Konflikte löst der Umbau zugunsten
  von Abschnitt 6. Ist der Vorwärts-Merge beim Beginn der Umsetzung noch nicht da, wird er abgewartet oder der
  Arbeits-Branch danach auf `release/1.2.0` gebracht.
- **KARV-192 und KARV-194** sind für 1.1.1 auf der Wartungslinie geplant und kommen per Vorwärts-Merge nach
  `release/1.2.0`. Stand 18.11.2026: KARV-194 ist gemergt (Pull Request #52, Commit `8fa442d` auf `release/1.1.0`);
  KARV-192 liegt erst als lokaler Branch `feature/KARV-192-trefferzahl` ohne Pull Request vor. Beide berühren die
  umgebauten Stellen:
  - KARV-192 meldet auf seinem Branch die gezeigten Treffer der Kartentabelle aus `seatTable.getItems()` und hört dafür
    auf `itemsProperty()` (`reportSearchHits`, an `setSalesState` gehängt), an `SalesViewState.showSearchHits(this, …)`.
    Das gibt es in der Baumtabelle nicht. Abschnitt 11 legt fest, was künftig gemeldet wird; gilt beim Umbau eine
    geänderte Signatur, wird sie übernommen.
  - KARV-194 erweitert `DisplaySettings`, `DisplayPreferences`, `DisplaySection.RESTORE_HINT` und `style.css` und
    nutzt `state.selectedBookings()`. Bei den Konflikten bleiben beide Ergänzungen erhalten; der Hinweis am Mauszeiger
    passt zur Auswahl als Menge, denn er liest `SalesViewState`.
  - Dieselbe Regel wie bei KARV-196: Die Umsetzung beginnt auf einem `release/1.2.0`, der diese Änderungen schon
    enthält, oder holt sie vor dem Umbau der betroffenen Stellen herein.

## Ausgangslage

Geprüft am 18.11.2026 auf `release/1.2.0` (Commit `55af126`). Der Quellcode unter `src` ist dort gleich dem auf
`main` (`git diff --stat main origin/release/1.2.0 -- src` ist leer).

**Kartentabelle** (`ui/sales/TicketTableController.java`, `TicketTableView.fxml`):

- Eine `TableView<BookingDTO>` mit 16 Spalten (FXML Zeilen 27–43): Vorstellung, Tisch, Kategorie, Sitz, Nachname,
  Vorname, Reserviert, Reserviert am, Preis (€), Bezahlt (€), Zahlungsstatus, Bezahlt am, Abgeholt, Abgeholt am,
  Rollstuhl, Kommentar.
- Zeilen: `buildRows()` (Zeilen 557–572) baut je Buchung der gewählten Vorstellung oder aller Vorstellungen ein
  `BookingDTO`; `shownBookings` (579–585) lässt gesperrte Sitze weg und sortiert nach `TableLabel.BOOKING_ORDER`
  (ohne Platz zuerst, dann nach Platz).
- Suche: `filter(query)` (753–759) setzt das Prädikat einer `FilteredList` über `BookingSearch.matches`.
- Spaltenfilter: `TableFilter.forTableView(seatTable).apply()` (194). Der Konstruktor von ControlsFX `TableFilter`
  (Quellen 11.2.1) legt `new SortedList<>(new FilteredList<>(new SortedList<>(backingList)))` um die Zeilen, bindet
  den Comparator an die Tabelle und setzt diese Liste als Items. Den Filter öffnet ein Rechtsklick auf den
  Spaltenkopf; er zeigt ein Suchfeld, eine Liste der Werte mit Häkchen und die Knöpfe „Apply“, „None“, „All“, „Reset
  All“ (`FilterPanel`, englische Texte, ControlsFX bringt keine deutschen mit).
- ControlsFX 11.2.1 hat keinen Filter für `TreeTableView`. Die Filterklassen im Paket `org.controlsfx.control.table`
  (`TableFilter`, `ColumnFilter`, `FilterPanel`, `FilterValue`) gibt es nur für `TableView`; die übrigen Klassen dort
  (`TableRowExpanderColumn`, `DupeCounter`, `TableViewUtils`, `model.*`) filtern nicht.
- Mehrfachauswahl (199), `TableInput.install` (201), `ColumnMinWidths.install` (195–196),
  `EditingUnits.getInstance().watch(seatTable, BookingDTO::getBooking)` (251).
- Kontextmenü je Zeile (218–245): „n Karten verkaufen“ (`SeatSelectionEditor.menuText`), „Platz zuweisen…“ (nur
  auf einer Buchung ohne Platz), „Stornieren“ (`CancelAction.prepare`). Auf einer nicht gewählten Zeile wählt der
  Rechtsklick zuerst nur diese.
- Die Checkbox „Abgeholt“ holt die Buchung über den Zeilenindex aus `seatTable.getItems()` (485–494).
- Auswahl an die Shell: `setSalesState` (704–708) meldet `selectedBookings()` an `SalesViewState.replaceSelection`.
- `restoreSelection` (712–723) sucht die Indizes in `filteredData`, die Tabelle zeigt aber die Liste von ControlsFX.
  Ist nach einer Spalte sortiert oder gefiltert, stimmen die Indizes nicht überein. Nur gelesen, nicht
  ausprobiert; mit dem Umbau entfällt die Stelle.
- Fremde Änderungen: `showRows()` (634–652) zeichnet nur neu, wenn dieselben Buchungen in derselben Reihenfolge
  kommen, sonst setzt es die Zeilen neu und stellt die Auswahl wieder her. Solange eine Zelle bearbeitet wird,
  wartet es (`refreshAfterForeignChange`, 592–599).

**Shell** (`ui/sales/TicketShellController.java`): Beim Wechsel zwischen Tabelle und Saalansicht lädt
`loadContentView` das FXML neu (Zeilen 235–248, 264–287); der Controller der Kartentabelle entsteht also bei jedem
Wechsel neu. Das Suchfeld liegt in der Kopfzeile der Shell (`TicketShellView.fxml`), nicht in der Kartentabelle.

**Eingabehilfen**, alle nur für `TableView`:

- `ui/input/TableInput.java`: `install(TableView<S>)`, `moveFocus`, `moveEditing`, `DragSelection`; arbeitet mit
  `TableRow`, `TableCell`, `TablePosition`, `.table-row-cell`.
- `ui/input/ParsingTextFieldTableCell.java` erbt von `TextFieldTableCell` und nutzt `TableView`, `TablePosition`,
  `CellEditEvent`.
- `ui/EditingUnits.java`: `watch(TableView<S>, Function<S, ?>)` (Zeile 110).
- `ui/ColumnMinWidths.java`: `install(TableView<?>, Map<TableColumn<?, ?>, Double>)` (Zeile 42), nutzt
  `TableView.ResizeFeatures`.
- Weitere Nutzer: `EventEditController` (`TableInput.install` für drei Tabellen, Zeilen 258–260;
  `ParsingTextFieldTableCell` in vielen Spalten; `TableFilter` Zeilen 462, 463, 495, 599).

**Auswahl in `TreeTableView`** (JavaFX 21.0.2, Quellen `TreeTableView.java`):

- `select(TreeItem)` wählt nur sichtbare Zeilen. Für ein Element in einer zugeklappten Gruppe setzt es nur
  `selectedItem` ohne Index (Zeilen 3060–3086).
- Beim Zuklappen fallen gewählte Kinder aus der Auswahl. Der Elternknoten wird nur gewählt, wenn die Hauptauswahl
  (`selectedIndex`) in den Kindern lag; sonst sind sie ersatzlos weg (Zeilen 2695–2752). Auf dieses Verhalten baut der
  Entwurf deshalb nicht.
- `sort()` ruft die `sortPolicy` innerhalb von `selectionModel.startAtomic()` auf und setzt die Sortierung zurück, wenn
  sie `false` liefert (Zeilen 1980–2050).
- `CheckBoxTreeTableCell` zeigt die Checkbox in jeder nicht leeren Zeile und gibt der Rückruffunktion nur den Index
  (`CheckBoxTreeTableCell.java`, Zeilen 358–386, 402–405).

**Weitere Stellen in der Kartentabelle**, die `TableView`-Typen prüfen: `freezeSelectionWhileDialogOpen` und
`isRowArea` (`TicketTableController.java`, Zeilen 367–393) erkennen Zeilen über `instanceof TableRow` bzw.
`TableCell`. Ein eigener Commit zeichnet heute nur neu (`refreshAfterCommit`, 537–539).

**Einstellungen:** `DisplaySettings` (Abschnitt `display` in `settings.json`) und `ui/DisplayPreferences.java`:
Übernahme beim Start nur bei `restoreAtStart` (Zeilen 53–60), jede Änderung sofort per `SettingsStore.saveOnly`
(104–123), unbekannte Werte aus neueren Versionen über `TolerantEnum`.

**Bestehende Baumtabelle:** `AbstractOverviewController` (Übersichten) nutzt schon `TreeTableView<Object>`.
`style.css` gestaltet `.tree-table-view` und `.tree-table-row-cell` in Abschnitt 6 (ab Zeile 412) nur zum Teil wie
Tabellen. Zeilenhöhe 30 px (Zeile 508), Zellabstand (502), Höhe der Auswahlliste (526) und zentrierte Checkbox (620)
gelten nur für `.table-view`.

**Andere Arbeit:** KARV-196 behebt den Fehler in `restoreSelection` auf der Wartungslinie (Pull Request #50 nach
`release/1.1.0`, gemergt am 18.11.2026, Commit `6928e63` auf dem Branch). Es führt `static select(TableView<BookingDTO>,
…)` und einen Test mit echtem ControlsFX-`TableFilter` ein. Nach `release/1.2.0` kommt es mit dem nächsten
Vorwärts-Merge.

**Tests der Eingabehilfen:** Für `TableInput` gibt es keinen Test (`src/test` enthält keinen `TableInput*Test`).
Getestet sind `ColumnMinWidthsTest`, `EditingUnitsTest`, `ParsingTextFieldTableCellTest`.
`ParsingTextFieldTableCell.forTableColumn` hat schon heute eine Fassung mit Bedingung je Zeile (Zeilen 109–112,
genutzt für „Bezahlt“).

**Wurzel der Baumtabelle:** Das Setzen einer neuen Wurzel leert die Sortierung (`TreeTableView.java`,
`root.invalidated()`, Zeilen 1062–1080, darin 1076 `getSortOrder().clear()`).

**Kundenfenster:** zählt nur Buchungen der gezeigten Vorstellung, bei „Alle Vorstellungen“ keine
(`SelectionSummary.of`, Zeilen 31–37).

**Andere Baumtabellen:** Event-Übersicht und Hallenverwaltung sind `TreeTableView` (`EventOverviewView.fxml` und
`HallManagementView.fxml`, je Zeile 32).

## Betrachtete Wege

| Weg | Kosten | Nutzen | Folgen |
|---|---|---|---|
| **A: Kartentabelle wird `TreeTableView`** | etwa 5 Tage, dazu etwa 1 Tag für den eigenen Spaltenfilter | Gruppen sind echte Knoten; Aufklappen, Einrückung und Auswahl bringt JavaFX mit | Eingabehilfen müssen auch `TreeTableView` können; Spaltenfilter selbst bauen |
| B: Gruppenköpfe als Zeilen in der `TableView` | etwa 3 Tage | Eingabehilfen bleiben unverändert | Jede Spalte, Zelle, Auswahl, Sortierung und der ControlsFX-Filter müssen Kopfzeilen kennen; gegen die Listen von `TableFilter` gebaut, brüchig |
| C: Nur sortieren, Spalte „Anzahl“, Trennlinien | etwa 1 Tag | „Kunden mit 4 Karten“ über den Filter der Spalte Anzahl | kein Auf- und Zuklappen, kein Gruppenkopf, keine freie Gruppierung als Ebenen |

**Gewählt: A**, vom Maintainer am 18.11.2026. Grund: Gruppen sind eine Hierarchie, und `TreeTableView` ist das
Bauteil dafür; B täuscht sie vor und macht jede Stelle der Tabelle davon abhängig. Wegen der Größe und des Risikos
an der meistgenutzten Tabelle der Kasse geht das Ticket von `v1.1.1` nach `v1.2`. Der Spaltenfilter wird nachgebaut,
statt wegzufallen.

## Entwurf

### Übersicht

```
DisplayPreferences ──(Gruppierung, Gruppenordnung)──┐
                                                    ▼
Buchungen ─► Suche ─► Spaltenfilter ─► TicketGrouping ─► TreeItem-Baum ─► TreeTableView
(buildRows)  (BookingSearch) (TicketFilter)   (Gruppen, Zählen,          (TicketTreeBuilder)
                                               Reihenfolge)
```

Alles links von `TreeItem-Baum` ist ohne JavaFX und direkt getestet.

### 1. Spalten als Werte: `TicketColumn` (neu, `ui.sales`, ohne JavaFX)

Ein Enum mit einem Eintrag je Spalte der Kartentabelle in FXML-Reihenfolge: `PRESENTATION`, `TABLE`, `CATEGORY`,
`SEAT`, `LAST_NAME`, `FIRST_NAME`, `RESERVED`, `RESERVED_ON`, `PRICE`, `PAID_AMOUNT`, `PAYMENT_STATUS`, `PAID_ON`,
`COLLECTED`, `COLLECTED_ON`, `WHEELCHAIR`, `COMMENT`. Jeder Eintrag kennt:

- die Überschrift (wie im FXML),
- den Gruppenschlüssel einer Buchung (`BookingDTO` → Schlüssel),
- den Text eines Schlüssels für Gruppenkopf und Spaltenfilter,
- die Ordnung der Schlüssel.

| Spalte | Schlüssel | Text | Ordnung |
|---|---|---|---|
| Vorstellung | die ID der Vorstellung (`getId()`); ein neu zusammengesetztes Objekt (`EventMerge`) behält so Pfad und Filter | `EntityLabels.nameOrPlaceholder(name)` | Reihenfolge der Vorstellungen im Event |
| Tisch | `TableLabel.of(booking)`; alle ohne Platz bilden eine Gruppe | `TableLabel.toString()` („Ohne Platz“, „A3“) | `TableLabel.compareTo` |
| Kategorie, Nachname, Vorname, Kommentar | Text ohne Rand, Groß- und Kleinschreibung gleich (`Locale.GERMAN`) | der Text der ersten Buchung der Gruppe in der Grundordnung | nach Text, ohne Groß- und Kleinschreibung |
| Sitz | Sitznummer wie in der Spalte; 0 gilt als „(leer)“ | Zahl | numerisch |
| Preis, Bezahlt | Cent (`getEffectivePrice`, `getPaidCents`) | „12,00 €“ wie `MoneyFormat` | numerisch |
| Zahlungsstatus | `PaymentStatus` | `getDisplayName()` | Reihenfolge des Enums |
| Reserviert, Abgeholt, Rollstuhl | boolean | „Ja“ / „Nein“ | Ja vor Nein |
| Reserviert am, Bezahlt am, Abgeholt am | `LocalDate` | `dd.MM.yyyy` | nach Datum |

- Ein fehlender Wert (`null`, leerer Text, kein gezahlter Betrag) ist ein eigener Schlüssel „(leer)“ und steht in
  jeder Ordnung zuletzt.
- Die **Grundordnung** der Buchungen ist die heutige: Reihenfolge der Vorstellungen, darin `TableLabel.BOOKING_ORDER`
  (wie `buildRows`).
- Der Name eines Eintrags (`LAST_NAME` …) ist zugleich sein Text in `settings.json` und bleibt stabil.
- Die Ordnung der Vorstellung hängt vom Event ab; ihr Comparator bekommt die Vorstellungen des Events (bzw. die
  Grundordnung) als Kontext. Wie das in die Signatur kommt, entscheidet die Umsetzung.

### 2. Spaltenfilter: `TicketFilter` (neu, `ui.sales`, ohne JavaFX)

- Je Spalte eine Menge **abgewählter** Schlüssel. Eine Buchung besteht den Filter, wenn keiner ihrer Schlüssel in der
  Menge ihrer Spalte abgewählt ist. Abgewählte statt gewählter Schlüssel: Ein Wert, der durch eine fremde Änderung
  neu entsteht, ist sichtbar.
- Die **Werteliste** einer Spalte enthält die Schlüssel aller Buchungen, die die Suche und die Filter der **anderen**
  Spalten bestehen, mit Text und Anzahl, in der Ordnung der Spalte. Ein abgewählter Schlüssel, der in der Liste nicht
  mehr vorkommt, bleibt abgewählt.
- Die Filter gelten, solange die Kartentabelle offen ist; beim Neuladen des FXML (Wechsel zur Saalansicht und
  zurück) beginnen sie leer, wie heute bei ControlsFX. Sie werden nicht gespeichert.

### 3. Gruppieren: `TicketGrouping` (neu, `ui.sales`, ohne JavaFX)

Eingabe: die Buchungen nach Suche und Filter in Grundordnung, die Gruppierung (geordnete Liste von `TicketColumn`,
ohne Doppelte), die Gruppenordnung (`VALUE` oder `SIZE`) und die Sortierung der Tabelle als `Comparator<BookingDTO>`
oder keiner (siehe 5). Den Comparator baut der Controller aus den Spalten; `TicketGrouping` kennt JavaFX nicht.
Ausgabe: ein Baum aus Gruppen und Buchungen.

- Ebene *i* gruppiert die Buchungen einer Gruppe der Ebene *i−1* nach dem Schlüssel der *i*-ten Spalte. Leere Gruppen
  gibt es nicht.
- Die **Anzahl** einer Gruppe ist die Zahl der Buchungen darunter, über alle Ebenen.
- **Gruppenordnung `VALUE`:** Geschwister nach der Ordnung der Spalte aufsteigend. **`SIZE`:** Geschwister nach Anzahl
  absteigend, bei gleicher Anzahl wie `VALUE`. Die Gruppenordnung gilt für alle Ebenen.
- Ohne Gruppierung ist der Baum flach: die Buchungen direkt unter der Wurzel.
- Eine Gruppe hat einen **Pfad** aus den Schlüsseln von der obersten Ebene bis zu ihr; er bleibt über einen Neuaufbau
  gleich, solange dieselben Schlüssel vorkommen.

### 4. Die Tabelle: `TreeTableView<TicketRow>`

- `TicketRow` (neu): sealed interface mit `BookingRow(BookingDTO)` und `GroupRow(TicketColumn column, Object key,
  String text, int count, List<Object> path)`.
- `TicketTableView.fxml`: `TreeTableView` statt `TableView`, `TreeTableColumn` mit denselben `fx:id`, Überschriften und
  Breiten, `showRoot="false"`, Spaltenregel `CONSTRAINED_RESIZE_POLICY`.
- **Spalten** liefern für `BookingRow` dieselben Werte wie heute, für `GroupRow` nichts. Keine Zelle einer Gruppenzeile
  zeigt ein Bedienelement (Checkbox, Auswahlliste, Textfeld), keine lässt sich bearbeiten. Die Checkbox-Spalten und die
  Auswahlliste brauchen dafür eigene Unterklassen der Baumzellen von JavaFX (Ausgangslage).
- `style.css` bekommt für die Kartentabelle dieselben Regeln wie für Tabellen (Zeilenhöhe, Zellabstand, Auswahlliste,
  Checkbox, rote Markierung `.input-error` wie Zeilen 963–964), damit Abnahme 1 auch im Aussehen gilt. Die Regeln hängen
  an einer eigenen Style-Klasse der Kartentabelle (etwa `ticket-table`), nicht allgemein an `.tree-table-view`: Die
  Event-Übersicht und die Hallenverwaltung bleiben unverändert.
- **Gruppenkopf:** Die Zeile zeigt „<Überschrift>: <Text> (<Anzahl>)“, etwa „Nachname: Müller (4)“, in Fettschrift,
  über die ganze Breite der Zeile und nicht auf eine Spalte beschnitten, eingerückt nach Ebene, mit dem Pfeil zum
  Auf- und Zuklappen.
- **Eigene Spalte für Pfeil und Einrückung** (`colTree`, neu): schmal, ohne Überschrift, ganz links und nicht
  verschiebbar (`setReorderable(false)`; JavaFX bestimmt die Stelle des Pfeils aus der Position der `treeColumn`,
  `TableRowSkinBase` Zeilen 272–274), nicht sortierbar,
  ohne Filter, nicht bearbeitbar, nicht im Menü „Gruppieren“ und kein Eintrag in `TicketColumn`. Sie ist die
  `treeColumn` der Tabelle und nur bei aktiver Gruppierung sichtbar, breit genug für Pfeil und Einrückung der tiefsten
  Ebene. Grund: JavaFX merkt sich die größte Pfeilbreite je `treeColumn` in einer statischen Tabelle, die nie kleiner
  wird, und rückt damit jede Zelle der Baumspalte ein, auch ohne Pfeil (`TableRowSkinBase.maxDisclosureWidthMap`, Zeile
  96, gefüllt 282–293; `TreeTableCellSkin` 159–182). Ohne gesetzte `treeColumn` ist der Schlüssel `null` und wird mit
  den Übersichten (`AbstractOverviewController`) geteilt. Ist eine eigene `treeColumn` gesetzt, rückt JavaFX keine
  andere Spalte ein (`TreeTableCellSkin` Zeile 161). Ist `colTree` ausgeblendet, sind die Datenspalten also nie
  eingerückt, auch nicht nach „Gruppierung aufheben“.
- **Ohne Gruppierung** gibt es keine Pfeile und keine Einrückung. Die Tabelle sieht aus wie heute.
- **Aufgeklappt:** Der Controller merkt sich die Pfade der **zugeklappten** Gruppen; alle anderen sind aufgeklappt.
  Neue Gruppen sind also aufgeklappt, und eine Gruppe, die durch Suche oder Filter kurz verschwindet, kommt zugeklappt
  zurück, wenn sie es war. Die Menge lebt im Controller und endet mit ihm; „Gruppierung aufheben“ und eine andere
  Gruppierung leeren sie.
- Der Text eines Gruppenkopfs wird bei jedem Neuaufbau aus den aktuellen Buchungen der Gruppe neu bestimmt.
- Buchungen ohne Platz bleiben kursiv (heute Zeile 224). Die Leiste „Ohne Platz“ bleibt, ihr Zusatz „(oben in der
  Tabelle)“ steht nur, solange die Tabelle weder gruppiert noch sortiert ist.

### 5. Sortieren

- Ein Klick auf einen Spaltenkopf sortiert wie heute (auf-, absteigend, aus; mit Umschalt mehrere Spalten). Er ordnet
  nur die Buchungen innerhalb ihrer Gruppe der untersten Ebene, ohne Gruppierung also alle Buchungen. Die Ordnung der
  Gruppen bestimmt allein die Gruppenordnung.
- Verglichen wird wie heute: die Zellwerte der Spalte mit dem Comparator der Spalte (Standard
  `TreeTableColumn.DEFAULT_COMPARATOR`, wie bisher `TableColumn.DEFAULT_COMPARATOR`), nicht die Ordnung aus
  `TicketColumn`. Der Controller baut daraus den `Comparator<BookingDTO>` für `TicketGrouping` (Zellwert je Buchung
  über die Wert-Fabrik der Spalte, Richtung und Reihenfolge aus `getSortOrder()`). Bei Gleichstand gilt die
  Grundordnung.
- Ohne Sortierung gilt die Grundordnung.
- Umsetzung über eine eigene `sortPolicy` der `TreeTableView`. Die Standard-Sortierung verglich sonst Gruppenzeilen über
  ihre leeren Zellwerte. Die Policy liefert immer `true` und baut den Baum nicht selbst um, denn sie läuft innerhalb
  von `startAtomic()` (Ausgangslage). Sie stößt den Neuaufbau nach 11 an (`Platform.runLater`), der wie jeder
  Neuaufbau wartet, solange eine Zelle bearbeitet wird.

### 6. Auswahl

Die Auswahl ist eine Menge von **Buchungen**, nicht von Zeilen. Sie liegt im Controller (nach Identität, wie
`cancelCandidates`); das Auswahlmodell der `TreeTableView` zeigt sie nur an. Grund: Zeilen in zugeklappten Gruppen
kann JavaFX nicht wählen, und beim Zuklappen geht seine Auswahl je nach Hauptauswahl verloren (Ausgangslage).

**Regel der Anzeige.** Nach jeder Änderung an Auswahl oder Baum gilt:

1. Eine Buchungszeile ist markiert genau dann, wenn ihre Buchung gewählt ist. Markiert heißt hier: im Auswahlmodell
   der `TreeTableView` gewählt.
2. Eine Gruppenzeile gilt als markiert genau dann, wenn alle Buchungen darunter gewählt sind. Sie wird dafür **nie** im
   Auswahlmodell gewählt, sondern nur über eine Pseudoklasse (etwa `:group-selected`) im Aussehen einer gewählten
   Zeile gezeigt. Das Auswahlmodell enthält also nur Buchungszeilen. So gilt weiter, worauf `TableInput` baut: Ist eine
   einzige Buchung gewählt, ist genau ein Index gewählt (Zeilen 84 und 143), auch wenn sie allein in ihrer Gruppe
   steht.
3. Eine zugeklappte Gruppe enthält nie nur einen Teil gewählter Buchungen. Tritt das ein, wird sie aufgeklappt (beim
   Neuaufbau, bei `restoreSelection`, `deselect`, `assignPlace`) oder ihr gewählter Teil fällt heraus (beim Zuklappen,
   siehe unten).

So ist gewählt, was markiert aussieht, und jede gewählte Buchung ist sichtbar oder steckt in einer ganz markierten
Gruppe.

**Bedienung.** Klicks und Tasten wirken zunächst wie heute auf Zeilen und damit auf das Auswahlmodell. Danach wird die
Änderung in die Menge übertragen, und die Anzeige wird angeglichen. Maßgeblich ist, welche Zeile die Bedienung meint
(die geklickte Zeile bzw. die Zeile, auf die der Fokus geht), nicht, was JavaFX im Auswahlmodell daraus macht:

| Bedienung | Meint eine Buchungszeile | Meint eine Gruppenzeile |
|---|---|---|
| Klick (auch auf eine schon markierte Zeile), Rechtsklick auf eine nicht markierte Zeile | Menge = diese Buchung | Menge = alle Buchungen der Gruppe |
| Rechtsklick auf eine markierte Zeile | Menge bleibt (wie heute) | Menge bleibt |
| Strg-Klick | Buchung hinein bzw. heraus | ganz markiert: alle heraus, sonst: alle hinein |
| Pfeil auf/ab, Pos1, Ende, Bild auf/ab ohne Umschalt | Menge = diese Buchung | Menge = alle Buchungen der Gruppe |
| Tab mit `clearAndSelect` (`TableInput.moveFocus`); Tab überspringt Gruppenzeilen (Abschnitt 9) | Menge = diese Buchung | kommt nicht vor |
| Leertaste (heute: fügt die Zeile im Fokus hinzu, ohne die Auswahl zu leeren, und startet im Bearbeitungsmodus das Bearbeiten der Zelle im Fokus; `TableViewBehaviorBase` Zeilen 213, 919–943) | Buchung hinein, Bearbeiten wie heute | alle Buchungen der Gruppe hinein, kein Bearbeiten |
| Strg+Leertaste | wie Strg-Klick | wie Strg-Klick |
| Strg mit Pfeil, Pos1, Ende, Bild | nur der Fokus wandert, die Menge bleibt | ebenso |
| Doppelklick | wie heute (startet das Bearbeiten) | klappt auf bzw. zu (Regel zum Zuklappen); die Menge ist schon durch den ersten Klick gesetzt |
| Umschalt-Klick, Umschalt mit Pfeil, Pos1, Ende, Bild; Ziehen | Bereich, siehe unten | Bereich, siehe unten |
| Strg+A | alle Buchungen der Tabelle, auch in zugeklappten Gruppen | ebenso |
| Klick auf den Pfeil | keine Änderung außer der Regel zum Zuklappen | ebenso |
| Links, Rechts | wie heute in der `TableView`: Fokus auf die Zelle links bzw. rechts, Menge = diese Buchung (`selectLeftCell`/`selectRightCell` → `clearAndSelect(row, column)`) | Rechts: Gruppe im Fokus aufklappen; Links: zuklappen (Regel zum Zuklappen), steht sie schon zu, Fokus auf die Gruppenzeile darüber; Menge sonst unverändert |
| Plus, Minus, Stern | keine Wirkung (wie heute) | Plus: aufklappen; Minus: zuklappen (Regel zum Zuklappen); Stern: samt Untergruppen aufklappen; Menge sonst unverändert |

- **Diese Tasten** behandelt die Kartentabelle selbst nach der Zeile im **Fokus** und verbraucht das Ereignis. In der
  `TreeTableView` belegt eine vorrangige InputMap Links und Rechts mit Auf- und Zuklappen (`TreeTableViewBehavior`
  Zeilen 82–90) und richtet sich nach der gewählten Zeile im Auswahlmodell (`TreeViewBehavior.expandRow` und
  `collapseRow`, Zeilen 643–709). Ohne eigene Behandlung ginge in Buchungszeilen das Wechseln der Spalte verloren, mit
  dem heute Tippen, Enter und F2 die Zelle wählen (`TableInput.editableCellInFocus` liest `getFocusedCell()`), und auf
  einem Gruppenkopf täten die Tasten nichts, weil Gruppenzeilen nie im Auswahlmodell stehen. Ohne Gruppierung verhalten
  sich die Tasten damit genau wie heute.
- Die Regel zum Zuklappen gilt für jeden Auslöser, auch für die Tastatur.
- **Gewollte Abweichung:** Ist eine zugeklappte Gruppe gewählt und dazu genau eine sichtbare Buchung, steht im
  Auswahlmodell genau ein Index. `TableInput` behandelt das wie eine Einzelauswahl: Klick oder Tippen bearbeiten die
  sichtbare Buchung. Das bleibt so; es betrifft nur diese eine Zelle, nicht die Gruppe. Ebenso gewollt: Nach einem
  Klick auf den Kopf einer aufgeklappten Gruppe mit genau einer Buchung startet der nächste Klick auf eine Zelle dieser
  Buchung das Bearbeiten, wie nach einem Klick auf die Buchung selbst.

- **Bereiche** übertragen nur die Buchungszeilen zwischen Anker und Ziel (Menge = diese Buchungen, mit Strg dazu).
  Gruppenzeilen im Bereich zählen nicht als Gruppe, zugeklappte Gruppen im Bereich tragen nichts bei. Ob eine
  Gruppenzeile danach markiert ist, folgt allein aus Regel 2. So gilt wie heute: Gewählt ist, was zwischen den
  Endpunkten zu sehen ist.
- **Anker und Fokus** stehen nach dem Angleichen auf der Zeile, die die Bedienung meinte, auch wenn das eine
  Gruppenzeile ist, die nicht im Auswahlmodell steht. Ein folgender Umschalt-Klick rechnet seinen Bereich von dort.
- **Angleichen** heißt: Das Auswahlmodell wird auf die sichtbaren Buchungszeilen der Menge gesetzt, die Pseudoklasse
  der Gruppenzeilen nach Regel 2 gesetzt und Regel 3 angewandt. Wird etwa in einer ganz gewählten, aufgeklappten
  Gruppe eine Buchung mit Strg abgewählt, verliert die Gruppenzeile ihre Markierung.
- **Keine Rückkopplung:** Übertragen werden nur Änderungen, die eine Bedienung auslöst. Änderungen des Auswahlmodells
  durch das Angleichen, durch JavaFX beim Zu- und Aufklappen (das ändert die Auswahl schon in
  `TreeItem.expanded.invalidated()`, bevor ein Listener davon erfährt) und durch das Ersetzen der Kinder beim
  Neuaufbau ändern die Menge nicht. Das Verfahren dafür (etwa eine Sperre während dieser Schritte, mit der Menge als
  Stand davor) wählt die Umsetzung.
- `setSalesState` meldet die **Menge** an `SalesViewState`, nicht das Auswahlmodell, und nur ihren Stand nach einem
  abgeschlossenen Schritt. Zwischenstände erreichen das Kundenfenster nicht.

**Zuklappen** einer Gruppe: Waren alle Buchungen darunter gewählt, bleibt die Auswahl gleich, und die Gruppenzeile ist
markiert. Waren nur einige gewählt, fallen diese aus der Auswahl. Aufklappen ändert die Auswahl nie; die Buchungszeilen
einer markierten Gruppe erscheinen markiert. Das Verhalten von JavaFX beim Zuklappen wird danach überschrieben, nicht
übernommen.

**Neuaufbau** des Baums (Abschnitt 11): Die Menge bleibt, wie sie vor dem Neuaufbau war. Eine Buchung, die nicht mehr
in der Tabelle steht (Filter, Suche, storniert, gesperrt), fällt heraus. Eine Buchung, die neu in eine Gruppe kommt,
etwa durch eine fremde Änderung, ist nicht gewählt. Eine gewählte Gruppe wächst also nie von selbst. War sie
zugeklappt und ganz gewählt, ist sie danach nur noch teilweise gewählt, wird nach Regel 3 aufgeklappt und zeigt, was
gewählt ist.

**Weitere Stellen:**

- `selectedBookings()` liefert die Menge in Reihenfolge der Tabelle. `SalesViewState.replaceSelection`, das
  Kontextmenü („n Karten verkaufen“, „Stornieren“) und die Leiste „Ohne Platz“ (Knopf aktiv bei genau einer gewählten
  Buchung ohne Platz) nutzen sie.
- **`restoreSelection(bookings)`** (Rückkehr aus der Saalansicht): setzt die Menge auf die Buchungen, die in der
  Tabelle stehen, und gleicht die Anzeige nach der Regel an.
- **`deselect(bookings)`** (etwa „Nur freie Sitze“ im Verkaufsdialog, `SeatSelectionEditor`): nimmt die Buchungen aus
  der Menge und gleicht die Anzeige an. Eine bisher ganz gewählte, zugeklappte Gruppe wird dabei aufgeklappt, und ihre
  übrigen Buchungen bleiben einzeln markiert. Das Kundenfenster zählt die abgewählten nicht mehr mit.
- **`assignPlace`** baut nach dem Zuweisen neu auf wie bei einer eigenen Änderung (nicht über `loadBookings` mit
  Leeren der Menge), setzt die Menge auf die zugewiesene Buchung (wie heute `clearAndSelect`), behält sie als selbst
  geänderte Buchung sichtbar (Abschnitt 11), klappt ihre Gruppe nach Regel 3 auf und scrollt zu ihrer Zeile.
- Die Commit-Handler der Spalten erhalten die Buchung aus `getRowValue().getValue()` (ein `TreeItem<TicketRow>`); eine
  Gruppenzeile kann nicht bearbeitet werden, ein Commit auf ihr kommt nicht vor.
- **Rechtsklick auf eine Gruppenzeile:** wie in der Tabelle oben. „Platz zuweisen…“ ist auf einer Gruppenzeile nicht
  sichtbar. Das Menü öffnet auch auf einer Gruppenzeile, die nicht im Auswahlmodell steht; „markiert“ meint hier
  Regel 2, nicht `row.isSelected()`.
- `freezeSelectionWhileDialogOpen` und `isRowArea` erkennen Zeilen und Zellen der Baumtabelle (`TreeTableRow`,
  `TreeTableCell`); bei offenem Verkaufsdialog ändert sich die Auswahl weiterhin nicht, auch nicht durch Auf- und
  Zuklappen. Den Pfeil sperrt der Dialog dafür mit.

### 7. Bedienung zum Gruppieren

Eine neue Leiste über der Tabelle in `TicketTableView.fxml`, oberhalb der Leiste „Ohne Platz“:

- `MenuButton` „Gruppieren“ mit einem Eintrag zum Anhaken je Spalte in Spaltenreihenfolge. Anhaken hängt die Spalte als
  unterste Ebene an, Abhaken nimmt sie heraus; die übrigen behalten ihre Reihenfolge. Das Menü bleibt nach einem Klick
  offen. Dafür reichen `CheckMenuItem` und `RadioMenuItem` nicht, sie schließen das Menü
  (`ContextMenuContent.java` Zeilen 1414–1424); nötig sind `CustomMenuItem` mit `hideOnClick=false` und einer
  `CheckBox` bzw. einem `RadioButton`.
- Darunter im selben Menü ein Trenner, „Gruppen nach Wert ordnen“ und „Gruppen nach Größe ordnen“ zur Wahl, und
  „Gruppierung aufheben“ (schließt das Menü).
- Neben dem Knopf steht die aktive Gruppierung als Text, „Gruppiert nach: Nachname › Vorname“; ohne Gruppierung steht
  dort nichts.
- Gruppieren geht auch im Anzeigemodus (ohne „Bearbeiten“); es ändert keine Daten.

### 8. Spaltenfilter in der Bedienung

Nachbau der Bedienung von ControlsFX, in Deutsch:

- Rechtsklick auf einen Spaltenkopf öffnet den Filter der Spalte (`TreeTableColumn.setContextMenu` mit einem
  `CustomMenuItem`): Suchfeld „Suchen…“ (filtert die Werteliste nach Text, ohne Groß- und Kleinschreibung), Werteliste
  mit Häkchen, Text und Anzahl, Knöpfe „Anwenden“, „Keine“, „Alle“, „Alle Filter zurücksetzen“.
- „Keine“ und „Alle“ setzen die Häkchen der gerade sichtbaren Werte; erst „Anwenden“ ändert die Tabelle. Schließen
  ohne „Anwenden“ (Esc, Klick daneben) verwirft die geänderten Häkchen. „Alle Filter zurücksetzen“ leert die Filter aller Spalten sofort.
- Eine Spalte mit aktivem Filter zeigt im Kopf ein Filtersymbol (`graphic`), wie heute bei ControlsFX.
- Gruppenanzahlen und die Auswahl richten sich nach dem gefilterten Stand. Die Zahl in der Leiste „Ohne Platz“ zählt
  wie heute alle Buchungen ohne Platz der gezeigten Vorstellungen, unabhängig von Suche und Filter; nur ihr Knopf folgt
  der Auswahl.
- Bewusster Unterschied zu ControlsFX: Die Werteliste fasst Texte nach den Schlüsseln aus Abschnitt 1 zusammen (ohne
  Rand, ohne Groß- und Kleinschreibung), ControlsFX listete die genauen Zellwerte getrennt. So passen Filter und Gruppen
  zusammen.

### 9. Eingabehilfen für beide Tabellenarten

Die Logik bleibt je einmal im Code:

- **`TableInput`:** Die Logik (Klick zum Bearbeiten, Tippen, Enter, Tab, Ziehen mit Mitscrollen) arbeitet gegen eine
  kleine Schnittstelle in `ui.input` mit zwei Umsetzungen für `TableView` und `TreeTableView`: Zeile und Spalte der
  bearbeiteten und der fokussierten Zelle, Auswahl nach Index, sichtbare Spalten, Bearbeiten starten und beenden, Zelle
  zu Zeile und Spalte, Zeile und Zelle unter einem Ereignisziel. `install(TableView)` bleibt, `install(TreeTableView)`
  kommt dazu.
- **Tab** überspringt Gruppenzeilen: Die nächste bearbeitbare Zelle liegt in der nächsten sichtbaren Buchungszeile;
  zugeklappte Gruppen werden übersprungen, nicht aufgeklappt. Eine Zeile
  zählt als bearbeitbar, wenn eine Bedingung der Zeile sie zulässt. Die Kartentabelle setzt sie für `GroupRow` auf
  nein.
- **`ParsingTextFieldTableCell`:** Die Logik (Merker für unlesbare Eingabe, Ersatzwert, Übernahme beim Beenden durch die
  Tabelle, Tab, Esc, Fokusverlust) zieht in eine gemeinsame Klasse; `ParsingTextFieldTableCell` (erbt weiter von
  `TextFieldTableCell`) und eine neue `ParsingTextFieldTreeTableCell` (erbt von `TextFieldTreeTableCell`) nutzen sie.
  Beide bieten dieselben Fabriken (`forTableColumn` auch mit Bedingung je Zeile, wie heute; `forDateColumn`,
  `forTextColumn`). In der Baumtabelle gilt in jeder Fabrik zusätzlich: Gruppenzeilen sind nie bearbeitbar.
- **`EditingUnits.watch`** bekommt eine Variante für `TreeTableView`; `modelOf` liefert für `GroupRow` `null` (keine
  Einheit in Bearbeitung).
- **`ColumnMinWidths.install`** bekommt eine Variante für `TreeTableView` mit gleicher Wirkung.
- Auswahlliste (Zahlungsstatus) und Checkboxen (Abgeholt; nur lesbar Reserviert, Rollstuhl) nutzen Unterklassen der
  JavaFX-Zellen für Baumtabellen (`ComboBoxTreeTableCell`, `CheckBoxTreeTableCell`), die in Gruppenzeilen nichts zeigen
  (Abschnitt 4). Sie erhalten die Buchung über das `TreeItem` der Zeile (`getTreeItem(index)`), nicht über einen
  Listenindex.
- Die Event-Verwaltung nutzt weiter die `TableView`-Varianten und verhält sich unverändert.

### 10. Speichern der Gruppierung

- `DisplaySettings` bekommt `ticketGrouping` (Liste von Texten, Namen von `TicketColumn`, Standard leer) und
  `ticketGroupOrder` (Text, Standard `"VALUE"`).
- `DisplayPreferences` hält beides als Properties (Liste von `TicketColumn`, Gruppenordnung), übernimmt es beim Start
  nur bei `restoreAtStart`, wie die übrigen Felder, und schreibt jede Änderung per `saveOnly`.
- Unbekannte Spaltennamen (aus einer neueren Version) werden beim Lesen übersprungen; ein unbekannter Wert der
  Gruppenordnung gilt als `VALUE` und wird wie bei der Platzierung unverändert zurückgeschrieben, bis jemand eine
  andere Ordnung wählt (`TolerantEnum`). Beim nächsten Schreiben entfallen unbekannte Spaltennamen. Ebenso verliert
  eine ältere Version (1.1.x), die `settings.json` schreibt, die beiden neuen Felder ganz, denn sie liest sie nicht
  ein. Beides ist hinnehmbar: Die Datei ist lokal, und verloren geht nur die gemerkte Gruppierung.
- Der Hinweis zum Schalter in den Einstellungen (`ui/settings/DisplaySection.RESTORE_HINT`) nennt die Gruppierung der
  Kartentabelle mit.
- Die Kartentabelle liest die Properties beim Laden und folgt ihren Änderungen über schwache Listener, wie es
  `docs/decisions.md` für Ansichten von `DisplayPreferences` verlangt (KARV-76). Ihr Controller entsteht bei jedem
  Wechsel neu, starke Listener hielten alte Controller samt Baum am Leben. Damit überlebt die Gruppierung den Wechsel
  zur Saalansicht und zurück.

### 11. Ablauf beim Neuaufbau

`showRows()` und `loadBookings()` bauen den Baum über `TicketTreeBuilder` (neu, JavaFX) aus dem Ergebnis von
`TicketGrouping`. Die Regel von heute bleibt: Kommen dieselben Buchungen in derselben Struktur (gleiche Pfade, gleiche
Buchungen je Gruppe, gleiche Reihenfolge, gleiche Kopftexte), genügt `refresh()`. Sonst werden die **Kinder der
Wurzel ersetzt** (`root.getChildren().setAll(…)`), und die Anzeige der Auswahl wird nach 6 angeglichen. Die Wurzel
selbst wird nur einmal beim Laden gesetzt und danach nie ausgetauscht, denn eine neue Wurzel leert die Sortierung
(Ausgangslage). Die Sperre gegen Rückkopplung (6) deckt das Ersetzen der Kinder ab.

**Treffer (KARV-192):** Nach jedem Neuaufbau und jedem bloßen Neuzeichnen meldet die Kartentabelle an
`SalesViewState.showSearchHits` alle Buchungen des Baums in Reihenfolge der Tabelle. Das sind die Buchungen nach
Suche und Spaltenfilter, die selbst geänderten Ausnahmen (unten) eingeschlossen, auch die in zugeklappten Gruppen.
Das entspricht der Regel von KARV-192 „gezählt wird, was die Ansicht als Treffer zeigt“: Eine zugeklappte Gruppe zeigt
ihre Buchungen in ihrer Anzahl. `reportSearchHits` über `getItems()` entfällt.

**Auswahl leeren:** Wie heute leeren zwei Stellen die Menge ausdrücklich: jeder Aufruf von `setEvent` oder
`setPresentation` (das Leeren hängt an diesen beiden, nicht an `loadBookings`; `assignPlace` leert nicht, siehe 6) und
ein ausgeführtes Storno (`cancelSelection`, heute Zeile 278). Das gilt auch,
wenn die Shell ein neu zusammengesetztes Event oder dieselbe Vorstellung neu setzt (`TicketShellController` Zeilen
134–140, 220–231; Kommentar `TicketTableController` Zeile 105), wie heute, wo `loadBookings` alle Zeilen ersetzt.
Diese Aufrufe beenden auch die Ausnahme für selbst geänderte Buchungen (unten). Jeder andere Neuaufbau behält die
Menge nach Abschnitt 6.

**Fokus nach dem Neuaufbau:** Fokus und Anker folgen der Buchung bzw. Gruppenzeile (über ihren Pfad), auf der sie
vorher standen, nicht dem alten Index. Gibt es sie nicht mehr, steht der Fokus auf keiner Zeile. Nach einem Klick auf
einen Spaltenkopf setzt JavaFX den Fokus nach der `sortPolicy` selbst auf die Hauptauswahl (`TreeTableView.java`
Zeilen 2081–2085), also vor dem Neuaufbau; „vorher“ meint dann diese Zeile.

**Anlässe:** fremde Änderung, Suche, Filter, Gruppierung, Gruppenordnung, Sortierung und **jede eigene Änderung** an
einer Buchung aus der Kartentabelle: jeder Commit einer Zelle (auch Vorname, Kommentar, Bezahlt), die Checkbox
„Abgeholt“, der Sitz-Dialog, „Platz zuweisen…“ und das Storno. Abgeleitete Werte (etwa „Reserviert“ und „Reserviert
am“ nach einem Nachnamen) sind damit eingeschlossen, denn der Neuaufbau rechnet alle Schlüssel aus den Buchungen neu.
Ob sich etwas verschoben hat, entscheidet der Strukturvergleich unten: Ist die Struktur gleich, wird nur neu
gezeichnet.

**Wann:** Jeder Neuaufbau wartet, bis keine Zelle mehr bearbeitet wird, wie heute bei fremden Änderungen
(`refreshAfterForeignChange`, `foreignChangePending`). Eine Tab-Folge durch mehrere Zellen ordnet die Tabelle also erst
nach ihrem Ende neu (Enter, Esc, Klick daneben, Fokus weg). Bis dahin stehen Köpfe und Anzahlen auf dem alten Stand,
und keine Zeile verschiebt sich unter der Bearbeitung.

**Eigene Änderung gegen Suche und Filter:** Eine Buchung, die die Kassenkraft selbst geändert hat, bleibt sichtbar,
auch wenn sie danach die Suche oder einen Spaltenfilter nicht mehr besteht. Sie zählt dann überall als passend
(Gruppen, Anzahlen, Wertelisten). Die Ausnahme endet, sobald die Kassenkraft den Suchtext oder die Menge der
abgewählten Filterwerte ändert („Anwenden“ ohne geänderte Häkchen zählt nicht) oder `setEvent`/`setPresentation` läuft;
fremde Änderungen beenden sie nicht. In ihre neue Gruppe und an ihre neue Sortierstelle zieht die
Buchung trotzdem um. Das ist eine bewusste Änderung: Heute verschwindet eine solche Buchung bei der nächsten fremden
Änderung, denn `showRows()` wendet die Suche dann neu an (Zeilen 639–642). Mitten im Verkauf soll eine gerade
bearbeitete Buchung aber nicht wegen eines anderen Rechners verschwinden. Stornierte Buchungen ohne Platz verschwinden
wie heute sofort (`removeCancelled`).

### 12. Tests

Ohne JavaFX:

- `TicketColumnTest`: Schlüssel, Text und Ordnung je Spalte, auch „(leer)“ zuletzt, Groß- und Kleinschreibung,
  „Ohne Platz“ als eine Gruppe, Vorstellungen in Event-Reihenfolge.
- `TicketFilterTest`: abgewählte Schlüssel, Werteliste mit Anzahl unter den anderen Filtern, ein neuer Wert ist
  sichtbar, Zurücksetzen.
- `TicketGroupingTest`: eine und zwei Ebenen, Anzahl über Ebenen, `VALUE` und `SIZE` mit Gleichstand, Sortierung nur in
  der untersten Ebene, flacher Baum ohne Gruppierung, stabile Pfade, Reihenfolge Suche → Filter → Gruppen.
- `DisplaySettingsTest`, `DisplayPreferencesTest`: neue Felder, Standard, `restoreAtStart`, unbekannte Werte.
- Die Regeln der Auswahl aus Abschnitt 6 (Menge übertragen, Anzeige angleichen, Zuklappen, Neuaufbau, `deselect`)
  liegen in einer Klasse ohne JavaFX und werden dort geprüft: Gruppe wählen und abwählen, Strg-Abwahl in ganz gewählter
  Gruppe, Zuklappen ganz und teilweise gewählt, eine Gruppe wächst durch fremde Buchung nicht, `deselect` aus einer
  zugeklappten Gruppe.

Mit JavaFX-Toolkit (wie die vorhandenen Zellen-Tests). Kein Test lädt heute das FXML oder den Controller, und der
Controller zieht beim Erzeugen `StoreEventRepository.getInstance()` und künftig `DisplayPreferences.instance()`. Deshalb
liegt die JavaFX-Logik der Baumtabelle (Baum setzen, Auswahl angleichen, Tasten, Fokus) in einer eigenen Klasse neben
dem Controller (etwa `TicketTreeTable`), die eine `TreeTableView` und ihre Eingaben übergeben bekommt und ohne Singletons
auskommt. Die Tests bauen eine `TreeTableView` selbst, wie der Test von KARV-196 eine `TableView`:

- **Zuerst, vor dem Umbau:** `TableInputTest` hält das heutige Verhalten an einer `TableView` fest: ein Klick auf eine
  Zelle der einzigen gewählten Zeile startet das Bearbeiten, bei mehreren gewählten nicht; Tippen ersetzt den Inhalt;
  Enter; Tab und Umschalt+Tab samt Sprung über das Zeilenende; Tab aus dem Bearbeiten bearbeitet die nächste Zelle;
  Ziehen wählt einen Bereich, mit Strg bleibt die bisherige Auswahl. Nach dem Umbau laufen dieselben Fälle mit
  denselben Erwartungen gegen `TableView` und gegen eine flache `TreeTableView`; nur die Erzeugung der Tabelle wird dafür
  parametrisiert.
- `ParsingTextFieldTreeTableCellTest` mit denselben Fällen wie `ParsingTextFieldTableCellTest`; die gemeinsame Logik
  wird für beide Zellarten geprüft.
- `TableInput` für `TreeTableView`: Tab überspringt eine Gruppenzeile; Tab am Ende einer Gruppe springt in die erste
  Buchung der nächsten.
- Kartentabelle: `selectedBookings()` mit gewählter zugeklappter Gruppe; Zuklappen mit Hauptauswahl außerhalb der
  Gruppe (das JavaFX-Verhalten wird überschrieben); `restoreSelection` klappt teilweise gewählte Gruppen auf; Checkbox
  „Abgeholt“ ändert die richtige Buchung in gruppierter, sortierter Tabelle; ein eigener neuer Nachname zieht die
  Buchung erst nach dem Ende der Bearbeitung um und bleibt trotz Suche sichtbar; die Sortierung überlebt einen
  Neuaufbau; eine einzelne Buchung allein in ihrer Gruppe lässt sich nach Klick durch Tippen bearbeiten; Pfeil ab auf
  eine Gruppenzeile wählt die Gruppe; Links und Rechts wechseln in einer Buchungszeile die Spalte (danach bearbeitet
  Tippen die neue Zelle) und klappen auf einem Gruppenkopf zu bzw. auf; Umschalt-Klick nach Klick auf einen Gruppenkopf rechnet den Bereich vom Kopf; der
  Fall aus dem Test von KARV-196 (Randbedingungen).

Die bestehenden Tests der Event-Verwaltung und von `ParsingTextFieldTableCell` bleiben grün und unverändert.

### 13. Dokumentation

- `docs/usage.md`, Abschnitt „Selling tickets“: Gruppieren, Gruppenordnung, Gruppenkopf, Spaltenfilter.
- `docs/architecture.md`, `ui`: Kartentabelle als `TreeTableView`, Eingabehilfen für beide Tabellenarten.
- `docs/decisions.md`: neuer Eintrag „Ticket table as tree table“ (Grund für A, eigener Spaltenfilter statt
  ControlsFX, Gruppenordnung getrennt von der Spaltensortierung, Auswahl als Menge von Buchungen). Der Eintrag zu
  KARV-76 („Anzeige“) nennt die Gruppierung als weiteren Inhalt von `DisplayPreferences`.
- `docs/structure.md`: Feldliste von `DisplaySettings` (heute Zeile 110) und die neuen Klassen in `ui.sales` und
  `ui.input`.
- Patchnotes 1.2.0: vorläufiger Eintrag.

## Der Umsetzung überlassen

- Namen und genaue Signaturen der neuen Klassen und der Schnittstelle in `ui.input`.
- Wie der Gruppenkopf über die ganze Zeile reicht (eigene `TreeTableRow` mit überlagertem Label, eigenes Skin oder
  Ähnliches), solange die Vorgaben in 4 erfüllt sind. Lässt sich das nicht sauber bauen, wird es vor dem Weiterbauen
  mit dem Maintainer geklärt, nicht still durch einen Text in einer Spalte ersetzt.
- Aussehen des Filtersymbols, der Leiste „Gruppieren“ und der Gruppenköpfe im Rahmen von `style.css`.
- Ob die gemeinsame Logik von `ParsingTextFieldTableCell` als Hilfsklasse oder anders geteilt wird, solange sie nur
  einmal im Code steht.
- Reihenfolge der Arbeitsschritte, mit einer Vorgabe: `TableInputTest` (Abschnitt 12) entsteht und ist grün, bevor
  `TableInput` umgebaut wird. Empfohlen danach: die Eingabehilfen für beide Tabellenarten, mit diesem und den
  bestehenden Tests als Sicherung; dann die Kartentabelle flach als `TreeTableView`, die sich wie heute verhält; dann Filter,
  Gruppierung und Speichern.

## Abnahme

1. Ohne Gruppierung und ohne Filter zeigt die Kartentabelle dieselben Zeilen in derselben Reihenfolge wie vorher, ohne
   Pfeile oder Einrückung. Bearbeiten, Tab, Tippen, Ziehen, Kontextmenü, Leiste „Ohne Platz“, Suche und Sortierung
   verhalten sich wie vorher, mit den gewollten Änderungen aus den Zielen: Eine geänderte Buchung zieht in einer
   sortierten Tabelle nach dem Ende der Bearbeitung an ihre neue Stelle, Spaltenfilter gelten nach einer fremden
   Änderung neu, und eine selbst geänderte Buchung bleibt trotz Suche und Filter sichtbar (Abschnitt 11).
2. Gruppieren nach „Nachname“, dann „Vorname“ zeigt zwei Ebenen. Jeder Kopf hat die Form „Nachname: Müller (6)“ bzw.
   „Vorname: Hans (4)“, und die Zahl stimmt mit den Buchungen darunter überein.
3. Mit „Gruppen nach Größe ordnen“ stehen die Gruppen mit den meisten Buchungen oben, bei Gleichstand nach Wert.
4. Ein Klick auf einen Gruppenkopf wählt die Gruppe. Das Kontextmenü zeigt „n Karten verkaufen“ mit n gleich der
   Anzahl der Gruppe, auch wenn sie zugeklappt ist. Bei einer gewählten Vorstellung (nicht „Alle Vorstellungen“, dort
   zählt das Kundenfenster wie heute nichts) zeigt das Kundenfenster dieselbe Zahl.
5. Verkaufen und Stornieren über eine gewählte Gruppe betreffen genau ihre Buchungen.
6. Auf- und Zuklappen bleibt erhalten, wenn ein anderer Rechner eine Buchung ändert oder die Suche sich ändert. Die
   einzige Ausnahme ist Regel 3 in Abschnitt 6: Eine zugeklappte Gruppe, die nur noch teilweise gewählt ist, wird
   aufgeklappt.
7. Eine laufende Bearbeitung in einer gruppierten Tabelle wird weder von einer fremden noch von einer eigenen Änderung
   unterbrochen. Die Eingabe landet in der Buchung, in der sie begann. Ändert sie die Gruppe (etwa ein neuer Nachname),
   zieht die Buchung erst nach dem Ende der Bearbeitung in ihre neue Gruppe; Köpfe und Anzahlen stimmen danach.
8. Eine gewählte, zugeklappte Gruppe wächst nicht, wenn ein anderer Rechner eine Buchung in sie hineinbringt: Die
   Zahl im Kontextmenü bleibt die der zuvor gewählten Buchungen, und die Gruppe klappt auf.
9. Zuklappen einer teilweise gewählten Gruppe nimmt deren gewählte Buchungen aus der Auswahl, unabhängig davon, welche
   Zeile zuletzt angeklickt wurde. Zuklappen einer ganz gewählten Gruppe lässt die Auswahl gleich.
10. „Nur freie Sitze“ im Verkaufsdialog über eine gewählte Gruppe nimmt die belegten Buchungen aus der Auswahl; das
   Kundenfenster (bei gewählter Vorstellung) zählt sie nicht mehr.
11. Ist eine einzelne Buchung gewählt, die allein in ihrer Gruppe steht, startet Tippen, Enter oder ein zweiter Klick
   das Bearbeiten wie in der ungruppierten Tabelle.
12. Nach einem Neuaufbau (fremde Änderung, Suche, eigene Änderung) ist die Tabelle weiter nach den gewählten Spalten
   sortiert.
13. Tab aus der letzten bearbeitbaren Zelle einer Gruppe springt in die erste Buchung der nächsten Gruppe; eine
   Gruppenzeile bekommt keinen Fokus zum Bearbeiten.
14. Der Spaltenfilter öffnet per Rechtsklick auf den Spaltenkopf, in Deutsch. Er filtert Buchungen, die Gruppen zählen
   danach, und eine gefilterte Spalte zeigt das Symbol.
15. Die Gruppierung und die Gruppenordnung gelten nach dem Wechsel zur Saalansicht und zurück und, mit „Anzeige beim
    Start wiederherstellen“, nach einem Neustart. Ohne den Schalter beginnt die Tabelle ungruppiert.
16. Die Event-Verwaltung verhält sich unverändert (Bearbeiten, Tab, ControlsFX-Filter).
17. `./mvnw test` ist grün, die neuen Tests aus Entwurf, Abschnitt 12, sind vorhanden.
18. Am gemeinsamen Ordner ändert sich nichts: `FormatGuardTest`, `JsonKeysTest` und `FormatSamplesTest` laufen
    unverändert durch.

## Prüfvermerk

**Runde 1, 18.11.2026.** Umsetzer: 0 kritisch, 3 groß, 4 klein. Gegenprüfer: 0 kritisch, 4 groß, 5 klein.

Behoben:

- Auswahl (beide Prüfer, groß): Neuaufbau über `restoreSelection` klappte gewählte Gruppen auf; eine gewählte Gruppe
  konnte durch fremde Buchungen still wachsen; das beschriebene Zuklappen-Verhalten von JavaFX stimmte nicht (der
  Elternknoten übernimmt nur bei Hauptauswahl in den Kindern); `deselect` aus einer gewählten Gruppe war offen. Neu:
  Abschnitt 6 führt die Auswahl als Menge von Buchungen mit fester Regel der Anzeige und eigenem Verhalten beim
  Zuklappen; Ausgangslage berichtigt; Abnahmen 8–10 neu.
- Eigene Änderung gegen Gruppe, Filter und Sortierung (beide Prüfer, groß): Abschnitt 11 nennt eigene Änderungen als
  Anlass, Neuaufbau erst nach dem Ende der Bearbeitung, selbst geänderte Buchungen bleiben trotz Suche und Filter
  sichtbar; Abnahme 7 angepasst.
- Klein, übernommen: Vergleich der Spaltensortierung wie heute (5); `sortPolicy` innerhalb `startAtomic` (5);
  Checkbox-Zellen brauchen Unterklassen (4); fehlende CSS-Regeln für Baumtabellen (4); Zusatz „(oben in der Tabelle)“
  (4); Kopftext beim Neuaufbau neu und Teil des Strukturvergleichs (4, 11); Tab über zugeklappte Gruppen (9); schwache
  Listener nach KARV-76 (10); `docs/structure.md` und Eintrag KARV-76 (13); `freezeSelectionWhileDialogOpen` und
  `isRowArea` (6); Abstimmung mit KARV-196 (Randbedingungen).

Nicht übernommen: nichts.

**Runde 2, 18.11.2026.** Umsetzer: 0 kritisch, 3 groß, 3 klein. Gegenprüfer: 0 kritisch, 2 groß, 5 klein.

Behoben:

- Einrückung ohne Gruppierung (beide Prüfer, groß): JavaFX merkt sich die Pfeilbreite je `treeColumn` statisch, ohne
  `treeColumn` geteilt mit den Übersichten. Neu: eigene Spalte `colTree` als `treeColumn`, nur bei Gruppierung sichtbar
  (Abschnitt 4); der Schutz der übrigen Spalten ist in `TreeTableCellSkin` Zeile 161 nachgelesen.
- Anlässe für den Neuaufbau (beide Prüfer, groß): Aufzählung und Grundsatz widersprachen sich. Neu: jede eigene Änderung
  ist Anlass, der Strukturvergleich entscheidet (Abschnitt 11).
- Bereiche über Gruppenköpfe (Umsetzer groß, Gegenprüfer klein): Neu: Eine Gruppe als Ganzes nur bei Klick,
  Strg-Klick oder Rechtsklick auf ihren Kopf; Bereiche übertragen nur Buchungszeilen; Strg+A wählt alle (Abschnitt 6).
- Klein, übernommen: keine Rückkopplung beim Zuklappen und bei `setRoot`, `setSalesState` meldet die Menge (6); Klick
  auf den Pfeil (6); `assignPlace` und `getRowValue()` (6); Lebensdauer der Ausnahme für eigene Änderungen und
  berichtigter Vergleich mit heute (11); `CustomMenuItem` statt `CheckMenuItem` (7); Sitz 0 als „(leer)“ (1);
  `DisplaySection.RESTORE_HINT` (10).

Nicht übernommen: nichts.

**Runde 3, 18.11.2026.** Umsetzer: 1 kritisch, 1 groß, 4 klein. Gegenprüfer: 1 kritisch, 1 groß, 5 klein. Damit endete
die Prüfung nach `CONTRIBUTING.md` („Abbruch“). Der Maintainer hat am 18.11.2026 entschieden, die Befunde einarbeiten
und die Runden fortsetzen zu lassen, bis keine kritischen oder großen Befunde mehr kommen; das Spec wird nicht geteilt.
Eingearbeitet nach Runde 3:

- Kritisch (Umsetzer): Regel 2 markierte den Kopf einer Gruppe mit nur einer Buchung im Auswahlmodell mit, `TableInput`
  hätte die Zeile dann nicht mehr bearbeiten lassen. Neu: Gruppenzeilen stehen nie im Auswahlmodell, ihre Markierung
  ist eine Pseudoklasse (6, Regel 2); neue Abnahme 11.
- Kritisch (Gegenprüfer): `setRoot` leert die Sortierung. Neu: Neuaufbau ersetzt nur die Kinder der Wurzel (11,
  Ausgangslage); neue Abnahme 12.
- Groß (Umsetzer): Tastatur ohne Regel. Neu: Tabelle der Bedienungen samt Tastatur, Anker und Fokus (6).
- Groß (Gegenprüfer): `TableInput` ohne Test. Neu: `TableInputTest` am heutigen Verhalten vor dem Umbau, als Vorgabe der
  Reihenfolge (12, „Der Umsetzung überlassen“).
- Alle kleinen Befunde der Runde: Abnahme 1 nennt die Ausnahme der Suche; `Comparator<BookingDTO>` (3, 5); Menge der
  zugeklappten Pfade (4); KARV-196 (Ausgangslage, Randbedingungen); CSS über eine eigene Style-Klasse (4);
  Kundenfenster in Abnahme 4 und 10; Vorstellung über die ID (1); Fabriken mit Bedingung je Zeile (9).

**Runde 4, 18.11.2026.** Umsetzer: 0 kritisch, 1 groß, 4 klein. Gegenprüfer: 0 kritisch, 1 groß, 3 klein.

Behoben:

- Wann die Menge geleert wird (Umsetzer, groß): Wechsel von Vorstellung oder Event und Storno leeren sie wie heute
  (11).
- KARV-192 und KARV-194 auf der Wartungslinie (Gegenprüfer, groß): Randbedingungen nennen beide; Abschnitt 11 legt
  fest, welche Treffer die Baumtabelle an `showSearchHits` meldet.
- Klein, übernommen: Klick auf eine schon markierte Zeile (6); eine sichtbare Buchung plus zugeklappte Gruppe als
  gewollte Abweichung in `TableInput` (6); Wortlaut zum parametrisierten `TableInputTest` (12); Ordnung der
  Vorstellung mit Event als Kontext (1); Fokus und Anker nach dem Neuaufbau (11); Zuklappen über die Tastatur (6);
  Inhalt des ControlsFX-Pakets (Ausgangslage).

Nicht übernommen: nichts.

**Runde 5, 18.11.2026.** Umsetzer: 0 kritisch, 1 groß, 4 klein. Gegenprüfer: 0 kritisch, 1 groß, 4 klein.

Behoben:

- Tasten zum Auf- und Zuklappen (Umsetzer, groß): JavaFX richtet sie nach dem Auswahlmodell, in dem Gruppenzeilen nie
  stehen. Neu: Die Kartentabelle behandelt Rechts, Plus, Links, Minus und Stern selbst nach der Fokuszeile (6).
- Neu sortieren und filtern nach einer Änderung auch ohne Gruppierung (Gegenprüfer groß, Umsetzer klein): als gewollte
  Änderung in Zielen und Abnahme 1 festgehalten, mit Grund.
- Klein, übernommen: Tab trifft keine Gruppenzeile (6); Zahl und Knopf der Leiste „Ohne Platz“ (8); Klick nach einem
  Gruppenkopf mit einer Buchung (6); Stand von KARV-192 und KARV-194 berichtigt (Randbedingungen); Tests über eine
  eigene Klasse neben dem Controller ohne Singletons (12); ältere Version verliert die neuen Felder beim Schreiben (10);
  Werteliste des Filters nach Schlüsseln als bewusster Unterschied zu ControlsFX (8); neu gesetztes Event oder
  Vorstellung leert Auswahl und Ausnahme wie heute (11).

Nicht übernommen: nichts.

**Runde 6, 18.11.2026.** Umsetzer: 0 kritisch, 0 groß, 4 klein. Gegenprüfer: 0 kritisch, 1 groß, 4 klein.

Behoben:

- Links und Rechts (Gegenprüfer, groß): Sie wechseln heute die Spalte, in der Tippen und Enter bearbeiten; Runde 5 hatte
  das auf Buchungszeilen weggenommen. Neu: Auf Buchungszeilen gilt die heutige Bedeutung, nur auf Gruppenköpfen klappen
  sie; Plus, Minus, Stern nur auf Gruppenköpfen (6); Test dazu (12).
- Klein, übernommen: Leertaste wie heute (hinzufügen, Bearbeiten), Strg+Leertaste, Strg+Pfeil, Doppelklick auf einen
  Gruppenkopf (6); `assignPlace` leert die Menge nicht und das Leeren hängt an `setEvent`/`setPresentation` (6, 11);
  Filter ohne „Anwenden“ schließen verwirft (8); „Anwenden“ ohne Änderung beendet die Ausnahme nicht (11); Fokus nach
  der `sortPolicy` (11); `colTree` nicht verschiebbar (4).

Nicht übernommen: nichts.

Nicht belegt: ob der Gruppenkopf über die ganze Zeile in `TreeTableRowSkin` (JavaFX 21.0.2) sauber baubar ist; dafür
regelt „Der Umsetzung überlassen“ das Vorgehen. Die Lösung mit `colTree` ist aus den Quellen abgeleitet, nicht
ausprobiert. Ebenso nicht ausprobiert: wie stark das Angleichen der Auswahl den Anker für Umschalt-Klick und den Fokus
für `TableInput` stört.
