# KARV-140: Buchungen auf andere Plätze verschieben

Stand: Entscheidung vom 21.11.2026. Ein Spec ist eine Momentaufnahme und wird nach dem Merge nicht gepflegt; was
dauerhaft gilt, steht in `docs/decisions.md` und der übrigen Dokumentation.

## Anlass und Ziel

Will ein Kunde einen anderen Platz, gibt es heute keinen Weg dafür. Die Kasse storniert und verkauft neu, oder sie
überschreibt die Daten zweier Buchungen von Hand, um sie zu tauschen. Dabei gehen Reservierungs- und Bezahldatum
verloren oder werden falsch, und die Kennung der Buchung bleibt am alten Sitz: Ändert eine zweite Kasse dieselbe
Buchung zur gleichen Zeit, landet ihre Änderung beim falschen Kunden.

Ziel: Im Bearbeitungsmodus lassen sich eine oder mehrere Buchungen innerhalb ihrer Vorstellung auf andere Plätze
verschieben. Ist ein Zielplatz belegt, tauschen die Buchungen die Plätze. Alle Daten und die Kennung der Buchung
wandern mit.

## Ziele, Nicht-Ziele, Anti-Ziele

**Ziele:**

- Eine Buchung auf einen freien Platz derselben Vorstellung verschieben.
- Zwei belegte Plätze tauschen.
- Mehrere Buchungen in einem Vorgang verschieben (eine Gruppe an einen anderen Tisch).
- Das gilt für reservierte, bezahlte und abgeholte Buchungen. Bei abgeholten fragt das Programm, ob die Markierung
  „Abgeholt“ bleibt.
- Der Vorgang beginnt in der Saalansicht oder in der Kartentabelle.
- Ein Vorgang ist ein Speichervorgang. Andere Rechner am gemeinsamen Ordner sehen einen Umzug, kein Storno mit
  Neuverkauf.

**Nicht-Ziele:**

- Verschieben in eine andere Vorstellung (KARV-200).
- Preisänderung, Nachzahlung oder Erstattung beim Verschieben (KARV-199, KARV-200, später KARV-66). Innerhalb einer
  Vorstellung hängt der Preis heute nicht am Platz, siehe Ausgangslage.
- Buchungen ohne Platz: Dafür bleibt „Platz zuweisen…“ unverändert.
- Ziehen mit der Maus (Drag and Drop).
- Ein neues Feld im Datenformat.
- Eine Anzeige der Zielwahl im Kundenfenster.

**Anti-Ziele:**

- Eine Buchung verliert beim Verschieben Daten oder ihre Kennung.
- Nach dem Vorgang zeigen zwei Buchungen auf denselben Sitz, oder ein Sitz der Bestuhlung hat keine Buchung.
- Ein Vorgang wird nur zum Teil ausgeführt.
- Ein Tausch geschieht, ohne dass die Kasse ihn gesehen und bestätigt hat, etwa weil eine andere Kasse den Zielplatz
  verkauft hat, während der Dialog offen war.
- Ein gesperrter Sitz (KARV-111) wird Ziel.

## Randbedingungen

- **Datenformat:** Es kommt kein Feld hinzu, `StoreCodec.FORMAT` bleibt. Ein Rechner mit 1.1 liest die Versionen
  eines Umzugs wie die, die der Konflikt-Dialog schon heute schreibt (Ausgangslage 3). Ein Rechner mit 1.1 kann
  selbst keinen Tausch auslösen, aber einen Konflikt mit einem Tausch auflösen; dafür gelten dort die Regeln von 1.1
  (Entwurf, Abschnitt 8).
- **Ein Vorgang, ein Speichervorgang:** Der Speicher erkennt einen Umzug nur innerhalb eines Speichervorgangs
  (Ausgangslage 15). Zwischen dem ersten und dem letzten Zug eines Vorgangs darf deshalb nicht gespeichert werden.
- **Reihenfolge:** KARV-140 wird erst gemergt, wenn der Fehler aus Ausgangslage 18 in `release/1.2.0` behoben ist
  (KARV-201 auf der Linie 1.1, KARV-202 bringt ihn nach 1.2; Entwurf, Abschnitt 8). Spec, Planung und Bau können
  vorher beginnen.
- **Schichten** (`docs/structure.md`, „Layer rules“): Die Regeln des Verschiebens stehen ohne JavaFX im Paket
  `model`. Texte und der Zustand der Zielwahl stehen in Klassen ohne JavaFX im Paket `ui.sales`, mit einer dünnen
  JavaFX-Hülle daneben, wie bei `PlaceAssignment` und `AssignPlaceDialog`.
- **Nur im Bearbeitungsmodus**, wie Verkaufen, Stornieren und „Platz zuweisen…“.
- **Sprache:** Code, Meldungen und dieses Spec deutsch; `docs/usage.md`, `docs/architecture.md` und
  `docs/decisions.md` englisch.

## Ausgangslage

Geprüft am 21.11.2026 auf `release/1.2.0`, Commit `3d44279` (enthält KARV-108). Pfade ohne Präfix liegen unter
`src/main/java/de/eltviller_carneval_verein/karten/`.

1. **Jeder Sitz hat eine Buchung.** `BookingSync.reconcile` legt für jeden Sitz ohne Buchung eine leere an
   (`model/BookingSync.java:272-282`). Eine Buchung trägt `seatId` und eine Momentaufnahme des Platzes;
   `Booking.takeSnapshot(Table, Seat)` setzt beides (`model/Booking.java:401`).
2. **`BookingSync.assign`** (`model/BookingSync.java:137-154`) setzt eine Buchung auf einen Sitz. Trägt der Sitz eine
   belegende Buchung (`Booking.isTaken()`: reserviert oder bezahlt), lehnt es ab. Sonst entfernt es die bisherige
   Buchung des Sitzes samt Resten und ruft `takeSnapshot`. Den Status der umziehenden Buchung prüft es nicht. Hatte
   die Buchung schon einen Sitz, legt `assign` dort keinen Platzhalter an.
3. **Ein Umzug von Sitz zu Sitz kommt schon vor:** `ConflictActions.assignAndSave` (`ui/ConflictActions.java:349-374`)
   ruft `assign` und legt am verlassenen Sitz mit `presentation.addBooking().takeSnapshot(...)` einen neuen
   Platzhalter an. Von Sitz zu Sitz führt dorthin „Meinen Wert übernehmen“ der Gruppe „Platz“ (`:519-526`); „Platz
   zuweisen…“ im Konflikt-Dialog gilt nur für Buchungen ohne Platz (`bookingToAssign`, `:299-306`).
4. **Frei und belegt:** `Booking.isTaken()` ist wahr für reservierte und bezahlte Buchungen
   (`model/Booking.java:342`). Eine freie Buchung kann Reste tragen (`hasLeftovers()`, `model/Booking.java:471`),
   etwa einen alten Kommentar; sie belegen den Sitz nicht (KARV-121).
5. **Kennung:** `Booking.getId()` erzeugt die Kennung beim ersten Lesen und behält sie
   (`model/Booking.java:123-128`). `takeSnapshot` ändert sie nicht.
6. **Preis:** `Booking.getEffectivePrice()` ist der Einzelpreis der Buchung, sonst der Standardpreis der Vorstellung
   (`model/Booking.java:287-292`). Der Platz geht in den Preis nicht ein. `takeSnapshot` kopiert die Kategorie des
   Tischs in die Momentaufnahme; sie ist nur Beschriftung (KARV-66 ändert das später).
7. **Abgeholt:** `collected` und `collectionDate` sind Felder der Buchung. Die Tabelle ändert `collected` über
   `SaleDates.change` (`ui/sales/TicketTableController.java:726`). Nimmt eine Änderung „abgeholt“ zurück, lässt
   `SaleDates.change` das Abholdatum stehen (`model/SaleDates.java:12-16`).
8. **Platz zuweisen in der Tabelle:** `TicketTableController.assignPlace` (`ui/sales/TicketTableController.java:557-587`)
   gilt nur für eine Buchung ohne Platz, wählt den Sitz mit `AssignPlaceDialog.choose` (Liste der freien Sitze) und
   ruft danach `edits.pin(booking)`, `rebuild()` und `treeTable.selectAndReveal(booking)`. Das Kontextmenü der
   Tabelle hat die Einträge Verkaufen, „Platz zuweisen…“ und Stornieren
   (`ui/sales/TicketTableController.java:258-266`, `472-494`).
9. **Saalansicht:** Das Kontextmenü eines Sitzes hat Verkaufen und Stornieren
   (`ui/sales/HallOverviewController.java`, `showSelectionMenu`, `:301-323`). Ein Linksklick wählt im
   Bearbeitungsmodus den Sitz und öffnet seine Karte (`styleSeatNode`, `:424` ff.), Strg-Klick und ein aufgezogener
   Rahmen wählen mehrere (`:196-292`). Ein Klick ins Leere hebt die Auswahl auf (`handleMouseReleased`), und
   `filter` nimmt Buchungen aus der Auswahl, die die Suche ausblendet (`:567` ff.). Gewählte Sitze bekommen über
   `HallPlanPane.SELECTED_KEY` ein Halo; `HallPlanPane.Decorator.seatMark` legt ein Zeichen in den Sitz (heute das
   Kreuz gesperrter Sitze, `:390-395`). Seit KARV-108 fordert `renderHall()` das Zeichnen nur an (`:353-355`);
   gezeichnet wird gebündelt in `drawHall()` (`:357-367`), bis dahin zeigen Plan und `seatNodes` den alten Stand.
10. **Stornieren als Vorbild für eine Aktion auf der Auswahl:** `CancelAction` (`ui/sales/CancelAction.java`)
    beschriftet den Menüeintrag, fragt nach, ändert das Modell und ruft bei „Buchungen sofort speichern“
    `BookingAutoSave.flushActive()`. `flush()` vergleicht und speichert das Event in einem Zug
    (`ui/sales/BookingAutoSave.java:109-126`).
11. **Gesperrte Sitze:** `LockedSeats.isLocked(Booking)` (`ui/sales/LockedSeats.java:59-78`) meldet einen Sitz,
    dessen Buchungs-Einheit in der Liste der gesperrten Einträge steht und dessen Buchung im Modell frei wirkt.
    `PlaceAssignment.freeSeats` und `isFree` nehmen diese Sitze aus (`ui/sales/PlaceAssignment.java:57-83`). Für eine
    belegende Buchung ist `isLocked` immer falsch (`:62`), auch wenn ihre Einheit gesperrt ist und das Modell nur
    einen älteren Stand zeigt. Ändert oder löscht ein Speichervorgang eine gesperrte Einheit, lehnt
    `UnitMapper.changes` den ganzen Speichervorgang mit einer `LockedUnitException` ab
    (`repository/UnitMapper.java:283-288`, `requireWritable`); `BookingAutoSave` meldet das in der Fußzeile, und das
    Event bleibt ungespeichert (`ui/sales/BookingAutoSave.java:145-158`). Stornieren hat heute dieselbe Falle.
12. **Reihenfolge der Plätze:** `Booking.PLACE_ORDER` (`model/Booking.java:35`): Reihenbuchstabe, Tischnummer,
    Sitznummer. `PlaceAssignment.freeSeats` sortiert Sitze genauso.
13. **Speicher:** Buchungs-Einheiten liegen unter `bookings/<seatId>` (`docs/decisions.md`, „Small units instead of
    whole documents“). Leere Buchungen werden nie geschrieben (`repository/UnitMapper.java:45-47`, `:146-148`); ein
    freier Sitz hat also keine Einheit, außer seine Buchung trägt Reste. Zeigen zwei Buchungen mit Daten auf
    denselben Sitz, lehnt das Speichern ab (`:150-154`).
14. **Umzugsvermerke:** `UnitMapper.withMoves` (`repository/UnitMapper.java:513-567`) erkennt je Speichervorgang, dass
    eine Kennung in einer Einheit wegfällt und in einer anderen Einheit derselben Vorstellung auftaucht, und setzt
    `movedTo` an der Quelle und `movedFrom` am Ziel (`store/VersionMarks.java`). Bei einem Tausch trägt jede der
    beiden Einheiten beide Vermerke; `UnitMapperTest.swapMarksBothDirections` belegt das. Eine Kette und ein Ring
    über drei Sitze sind dort nicht getestet.
15. **Ein Speichervorgang:** `BookingAutoSave.flush()` und „Speichern“ schreiben das ganze Event in einem Zug
    (`EventSaver`, `StoreEventRepository`, `UnitBaseline.save`). `withMoves` paart nur innerhalb eines
    Speichervorgangs: Ein Umzug, der über zwei Speichervorgänge verteilt ankommt, wäre ein Storno und ein
    Neuverkauf.
16. **Konfliktregeln für Umzüge (KARV-93)** stehen in `repository/BookingTrail`, `BookingFate` und `BookingPolicy`.
    `BookingTrail.displacedOnly` (`repository/BookingTrail.java:389-418`) nennt „Tausch, Ersetzen“ ausdrücklich.
    Belegt ist ein Tausch gegen eine gleichzeitige Feldänderung der anderen Kasse
    (`BookingPolicyTest.swapAgainstEditKeepsBothBookingsOnce`, für beide Gewinner). Nicht durch Tests belegt sind ein
    Tausch gegen ein gleichzeitiges Storno, gegen einen weiteren Umzug derselben Buchung und gegen einen Neuverkauf
    des Zielsitzes, und kein Test führt einen Tausch über zwei Rechner Ende zu Ende (`ConflictScenarioTest` kennt nur
    `move` auf einen freien Sitz, `:1683-1689`). `BookingPolicy` schreibt je Einheit nur einen Vermerk
    (`repository/BookingPolicy.java:192-198`).
17. **Fremde Änderungen und Objekte:** Verschiebt die eigene Kasse eine Buchung, behält das `Booking`-Objekt seine
    Kennung und bekommt einen neuen Sitz. Arbeitet `EventMerge` eine fremde Änderung ein, kopiert es den fremden Stand
    in das Objekt, das am Sitz liegt (`repository/EventMerge.java:436-449`, `current.copyFrom(state)`): Dasselbe
    Objekt kann danach eine andere Buchung tragen. Auswahl und Merkliste der Ansichten arbeiten mit der
    Objektidentität (`TicketEdits`, `TicketSelection`).
18. **Fehler im Bestand: ersetzter Sitz, zwei neue Buchungen.** Am 21.11.2026 mit einem Wegwerf-Test in
    `ConflictScenarioTest` über zwei Rechner ausgeführt (nicht im Repository; jeweils beide Reihenfolgen des
    Hochladens):
    - Beide Rechner stornieren die Buchung eines Sitzes und verkaufen ihn neu, je in einem Speichervorgang: Die
      Buchung des später hochladenden Rechners ist danach auf beiden Rechnern weg, ohne Einheit „Ohne Platz“ und ohne
      Vermerk.
    - Beide Rechner verkaufen einen freien Sitz, dessen Buchung Reste trägt (alter Kommentar): dasselbe Ergebnis.
    - Kontrolle mit einem ganz freien Sitz: Die Doppelverkaufsregel greift, der Verlierer steht unter „Ohne Platz“
      mit Vermerk.
    - Tausch von Hand nachgebaut gegen Storno und Neuverkauf des Zielsitzes: Lädt der tauschende Rechner zuerst hoch,
      ist der Neuverkauf weg. Lädt der andere zuerst hoch, bleibt alles erhalten, die getauschte Buchung steht unter
      „Ohne Platz“, ihr Vermerk fehlt.

    In allen Läufen zeigen beide Rechner am Ende denselben Stand, und keine Buchung lebt doppelt. Ursache nach
    Lektüre, nicht per Debugger verfolgt: Trägt die Basis des Sitzes eine Kennung, läuft die Auflösung über
    `BookingPolicy.moves`; `newcomer` (`repository/BookingPolicy.java:300-308`) übernimmt nur eine der beiden neuen
    Buchungen, und die Doppelverkaufsregel wird nicht mehr erreicht. Der Test
    `BookingPolicyTest.bothReplacedWithDifferentBookingsIsADoubleSale` baut seine Eingabe ohne diese Spur.

## Betrachtete Wege

**Schnitt des Vorhabens** (entschieden vom Maintainer am 21.11.2026):

- *Drei Schritte (gewählt):* KARV-140 verschiebt und tauscht innerhalb einer Vorstellung, ohne Geld und ohne neues
  Datenformat. KARV-199 bringt mehrere Zahlungen je Buchung. KARV-200 bringt den Wechsel in eine andere Vorstellung
  mit Ausgleich der Preisdifferenz. So ist der Tausch früh da, und nichts kollidiert mit der Arbeit an der
  Abrechnung.
- *Alles in einem Ticket:* ein Spec für Verschieben, Zahlungsmodell und Vorstellungswechsel. Verworfen: zu groß, und
  es änderte Buchung, Zahlungslogik und Speicher zur selben Zeit wie die Abrechnung.
- *Warten, bis das Zahlungsmodell steht:* kein Zwischenstand, aber der Tausch käme Wochen später. Verworfen.

**Wie eine Buchung den Platz wechselt:**

- *Die Buchung zieht mit ihrer Kennung um (gewählt):* Der Speicher erkennt den Umzug, die Konfliktregeln folgen der
  Buchung.
- *Die Daten werden auf die Buchung des Zielsitzes kopiert,* so wie die Kasse es heute von Hand macht. Verworfen: Die
  Kennung bliebe am Sitz, und die Änderung einer zweiten Kasse träfe den falschen Kunden.

**Wie die Kasse das Ziel wählt:**

- *In der Saalansicht den Zielplatz anklicken, in der Tabelle aus einer Liste wählen (gewählt):* Im Plan sieht die
  Kasse, wer wo sitzt; die Liste folgt dem Muster von „Platz zuweisen…“.
- *Nur die Liste, in beiden Ansichten:* weniger Arbeit, aber beim Tauschen fehlt der Überblick. Verworfen.
- *Ziehen mit der Maus:* liegt nahe, kollidiert aber mit dem Auswahlrahmen (linke Taste) und dem Verschieben des
  Plans (rechte Taste). Verworfen.

**Mehrere Buchungen** (entschieden vom Maintainer am 21.11.2026): ein Vorgang für mehrere Buchungen statt einer je
Vorgang, weil oft ganze Gruppen zusammen sitzen. Die Zuordnung von Buchungen zu Zielplätzen folgt der Reihenfolge der
Plätze, nicht der Reihenfolge der Klicks: Ein Rahmen und eine Liste haben keine Klickreihenfolge, und der
Bestätigungsdialog zeigt die Paare vor dem Ausführen.

## Entwurf

### 1. Begriffe

- **Quelle:** eine Buchung, die verschoben werden soll. Sie gehört zur Vorstellung, hat einen Sitz der Bestuhlung
  (nicht „Ohne Platz“) und belegt ihn (`isTaken()`).
- **Ziel:** ein Sitz der Bestuhlung derselben Vorstellung.
- **Gegenzug:** der Umzug einer belegenden Buchung, die auf einem Ziel sitzt und selbst keine Quelle ist. Sie
  bekommt einen frei werdenden Platz.
- **Zug:** ein Umzug einer Buchung von einem Sitz auf einen anderen, Quelle oder Gegenzug.

### 2. Regeln im Modell: `model/BookingMove`

Eine neue Klasse ohne JavaFX mit nur statischen Methoden, neben `BookingSync`. Sie plant einen Vorgang und führt ihn
aus.

**Eingaben:** die Vorstellung, ihre Bestuhlung, die Quellen (Buchungen), die Ziele (Sitze) und die Kennungen der
gesperrten Sitze. Die Ansicht hält zu jeder Quelle fest, welche Kennung und welchen Sitz sie hatte, als die Kasse sie
wählte, und gibt das mit: Nach einer fremden Änderung kann dasselbe Objekt eine andere Buchung tragen (Ausgangslage
17). Ziele hält die Ansicht über die Kennung des Sitzes, nicht über das `Seat`-Objekt: Eine ersetzte Halle hat neue
Objekte mit denselben Kennungen.

**Gesperrte Sitze.** Gesperrt im Sinn dieses Abschnitts ist jeder Sitz, dessen Buchungs-Einheit die Liste der
gesperrten Einträge führt, gleich ob seine Buchung im Modell frei wirkt oder einen älteren belegten Stand zeigt
(Ausgangslage 11). `LockedSeats` bekommt dafür eine Abfrage ohne JavaFX, die für eine Vorstellung diese Sitze nennt;
`LockedSeats.isLocked` bleibt, wie es ist. Ein Vorgang darf keinen gesperrten Sitz berühren, weder als Sitz einer
Quelle noch als Ziel. Sonst lehnte der Speicher den ganzen Speichervorgang ab, und das Event bliebe mit dem
verschobenen Stand ungespeichert.

**Ablehnung.** Der Plan wird abgelehnt, und nichts ändert sich, wenn eine der Bedingungen zutrifft. Die Gründe sind
unterscheidbar (etwa ein Enum), die Texte stehen in Abschnitt 7. Treffen mehrere zu, gilt der erste in der
Reihenfolge der Tabelle. „Anzahl“ ist aus der Oberfläche nicht erreichbar; die Ansichten behandeln ihn wie „Auswahl
veraltet“.

| Grund | Bedingung |
|---|---|
| keine Bestuhlung | die Bestuhlung ist `null` |
| Auswahl veraltet | eine Quelle steht nicht mehr in `presentation.getBookings()`, ist „Ohne Platz“, belegt ihren Sitz nicht mehr, ihr Sitz gehört nicht zur Bestuhlung, sie trägt nicht mehr die Kennung und den Sitz, mit denen sie gewählt wurde (Ausgangslage 17), oder ein Ziel ist kein Sitz der Bestuhlung mehr |
| Anzahl | die Zahl der Ziele ist nicht die Zahl der Quellen, eine Quelle oder ein Ziel kommt doppelt vor, oder es gibt keine Quelle |
| Platz gesperrt | der Sitz einer Quelle oder ein Ziel ist gesperrt (die Buchung eines Gegenzugs sitzt auf einem Ziel und ist damit erfasst); das Ergebnis nennt den ersten solchen Sitz in der Reihenfolge der Plätze |
| nichts zu tun | nach der Zuordnung sitzt jede Quelle schon auf ihrem Ziel |

**Zuordnung.** Quellen und Ziele werden nach derselben Ordnung sortiert, und zwar nach dem Sitz der Bestuhlung, nicht
nach der Momentaufnahme der Buchung: Reihenbuchstabe, Tischnummer, Sitznummer wie in `PlaceAssignment.freeSeats`,
bei Gleichstand die Kennung des Sitzes. Eine Quelle zählt dabei mit ihrem bisherigen Sitz. Die i-te Quelle bekommt
das i-te Ziel. Paare, bei denen die Quelle schon auf dem Ziel sitzt, sind kein Zug und fallen aus dem Plan.

**Ketten und Gegenzüge.** Der Sitz einer Quelle darf selbst Ziel einer anderen Quelle sein (eine Gruppe rückt um
einen Sitz weiter). Die Züge der Quellen bilden deshalb Ketten: Eine Kette beginnt an einem Sitz, den eine Quelle
verlässt und den keine Quelle bekommt (Kettenanfang), und endet an einem Ziel, das nicht Sitz einer Quelle ist
(Kettenende). Weil Quellen und Ziele nach derselben Ordnung gepaart werden, läuft jede Kette in eine Richtung und
schließt sich nie von selbst zum Kreis; jede Kette hat genau einen Anfang und ein Ende. Für jede Kette gilt:

- Trägt das Kettenende eine belegende Buchung, zieht sie auf den Kettenanfang (Gegenzug). Ohne Überschneidung von
  Quellen und Zielen ist das der Sitz der Quelle, die ihren Platz bekommt: ein Tausch.
- Sonst wird die Buchung des Kettenendes entfernt, samt Resten (wie `BookingSync.assign`, KARV-121), und der
  Kettenanfang bekommt einen neuen leeren Platzhalter mit der Momentaufnahme seines Sitzes (wie
  `ConflictActions.assignAndSave`). Der Dialog nennt die Reste nicht; auch ein Verkauf verwirft sie ohne Rückfrage.

Beispiele mit den Sitzen 1 bis 4 eines Tischs:

| Quellen auf | Ziele | Ergebnis |
|---|---|---|
| 1 (A) | 3 (frei) | A 1→3; Sitz 1 bekommt einen Platzhalter |
| 1 (A) | 3 (C) | A 1→3, C 3→1 (Tausch) |
| 1 (A), 2 (B) | 2, 3 (frei) | A 1→2, B 2→3; Sitz 1 bekommt einen Platzhalter |
| 1 (A), 2 (B) | 2, 3 (C) | A 1→2, B 2→3, C 3→1 (Gegenzug auf den Kettenanfang) |
| 1 (A), 2 (B) | 3 (frei), 4 (D) | A 1→3, B 2→4, D 4→2; Sitz 1 bekommt einen Platzhalter |
| 1 (A), 2 (B) | 1, 2 | abgelehnt: nichts zu tun |

**Ergebnis des Plans:** die Züge (Buchung, bisheriger Sitz, neuer Sitz, ob Gegenzug), erst die Quellen in der
Reihenfolge ihrer bisherigen Sitze, dann die Gegenzüge in der Reihenfolge ihrer bisherigen Sitze (dieselbe Ordnung
wie bei der Zuordnung).

**Ausführen.** Vor dem Ausführen wird der Plan aus denselben Quellen und Zielen neu berechnet. Weicht er vom
gezeigten ab (ein Ablehnungsgrund, oder nicht dieselben Züge: je Zug Kennung der Buchung, bisheriger Sitz, neuer Sitz
und ob Gegenzug; dazu je Kettenende ohne Gegenzug, ob dort eine Buchung mit Daten liegt), ändert sich nichts, und das
Ergebnis sagt das (Grund „Auswahl veraltet“). Ändern sich nur Daten einer beteiligten Buchung (etwa ihr Name durch
eine andere Kasse), ist der Plan derselbe: Verschoben wird die Buchung, nicht ihr Inhalt. Sonst, in einem Zug auf dem
FX-Thread und ohne Zwischenstand, der gespeichert oder gezeichnet werden könnte:

1. Jede Buchung eines Zugs bekommt mit `takeSnapshot` den neuen Sitz. `unassigned` bleibt `false`.
2. An jedem Kettenende ohne Gegenzug werden alle Buchungen entfernt, die bisher auf diesen Sitz zeigten.
3. Jeder Kettenanfang ohne Gegenzug bekommt einen neuen leeren Platzhalter.

Danach zeigt auf jeden beteiligten Sitz genau eine Buchung. Kennung, Name, Zahlung, Einzelpreis, gezahlter Betrag,
Daten, Kommentar und unbekannte Felder der umgezogenen Buchungen sind unverändert. `BookingSync.assign` bleibt, wie
es ist.

### 3. Abholmarkierung

Ist mindestens eine Buchung eines Zugs (Quelle oder Gegenzug) abgeholt, fragt der Bestätigungsdialog, ob die
Markierung bleibt (Abschnitt 6). Die Antwort gilt für alle abgeholten Buchungen des Vorgangs.

- **Behalten** (Vorgabe): `collected` und `collectionDate` bleiben.
- **Entfernen:** Nach dem Ausführen setzt `SaleDates.change` bei jeder abgeholten Buchung des Vorgangs `collected`
  auf `false` und im selben Schritt `collectionDate` auf `null`. Ohne das zweite bliebe das Datum stehen, und die
  Prüfung meldete ein Abholdatum ohne Abholung.

### 4. Saalansicht: Zielwahl im Plan

**Menü.** Das Kontextmenü eines Sitzes bekommt zwischen Verkaufen und Stornieren den Eintrag „Buchung
verschieben…“, bei mehreren belegenden Buchungen in der Auswahl „Ausgewählte Buchungen verschieben… (n)“. Es zählen
nur die belegenden Buchungen der Auswahl; freie Sitze in der Auswahl werden übergangen, wie Stornieren Buchungen ohne
Daten übergeht. Der Eintrag ist gesperrt ohne Bearbeitungsmodus und wenn keine Buchung der Auswahl ihren Sitz belegt.

**Zielwahl.** Der Eintrag prüft zuerst, ob der Sitz einer Quelle gesperrt ist (Abschnitt 2); dann beginnt keine
Zielwahl, und die Fußzeile nennt den Platz (Abschnitt 7). Sonst schaltet er die Saalansicht in die Zielwahl für die n
Quellen. Solange sie läuft:

- Über dem Plan steht eine Leiste: bei einer Quelle „Ziel wählen für Anna Müller (Tisch A3, Sitz 2): Platz
  anklicken.“, bei mehreren „Ziele wählen für 4 Buchungen: 2 von 4 Plätzen gewählt.“ Rechts stehen die Knöpfe
  „Verschieben…“ (nur aktiv, wenn n Ziele gewählt sind) und „Abbrechen“.
- Die Quellen tragen das Halo der Auswahl. Gewählte Ziele sind anders markiert, und zwar nicht nur über eine Farbe
  (KARV-26): mit einem Zeichen im Sitz über `Decorator.seatMark` oder einer vergleichbaren Form.
- Ein Linksklick auf einen Sitz wählt ihn als Ziel oder wählt ihn wieder ab. Er öffnet keine Karte und ändert die
  Auswahl nicht. Auch der Sitz einer Quelle kann Ziel sein. Sitze, die der Suchfilter abblendet, sind als Ziel
  wählbar: Die Suche galt der Buchung, nicht dem Ziel.
- Ein gesperrter Sitz (Abschnitt 2) ist kein Ziel; der Klick wählt nichts und zeigt die Meldung aus Abschnitt 7.
  Das gilt auch für einen Sitz, dessen belegende Buchung auf einer gesperrten Einheit liegt.
- Sind schon n Ziele gewählt, wählt ein Klick auf einen weiteren Sitz nichts; die Fußzeile sagt, dass erst ein Ziel
  abzuwählen ist.
- Mit dem n-ten Ziel wird der Plan berechnet. Lehnt er ab („nichts zu tun“, „Platz gesperrt“ nach einer inzwischen
  geänderten Sperrliste), zeigt die Fußzeile den Grund, und die Zielwahl bleibt mit ihren Zielen bestehen, damit die
  Kasse eines abwählen kann. Bei „Auswahl veraltet“ endet sie (siehe unten). Sonst öffnet sich
  der Bestätigungsdialog. „Abbrechen“ dort führt in die Zielwahl zurück, die gewählten Ziele bleiben. Der Knopf
  „Verschieben…“ der Leiste tut dasselbe wie das n-te Ziel.
- Es gibt keinen Auswahlrahmen und kein Kontextmenü. Zoomen und Verschieben des Plans gehen weiter.
- Die Auswahl der Ansicht (und damit Kundenfenster und Hinweis am Mauszeiger) bleibt, wie sie beim Start der
  Zielwahl war: Weder ein Klick ins Leere noch eine geänderte Suche nimmt in der Zielwahl etwas aus der Auswahl. Die
  Quellen hält die Zielwahl selbst und unabhängig von der Suche. Das Kundenfenster zeigt von der Zielwahl nichts.

**Ende der Zielwahl:**

- nach einem ausgeführten Vorgang: Die Quellen sind danach die Auswahl, an ihren neuen Plätzen; blendet die Suche
  eine davon aus, gehört sie wie sonst nicht zur Auswahl;
- mit „Abbrechen“ in der Leiste oder mit Esc: Die Auswahl ist danach die vom Start, ohne das, was die Suche
  inzwischen ausblendet. Der Plan bekommt heute nie den Tastaturfokus; Esc wird deshalb an der Szene abgefangen,
  solange die Zielwahl läuft und kein Dialog offen ist;
- wenn der Bearbeitungsmodus endet, Event oder Vorstellung wechseln oder die Saalansicht verlassen wird;
- bei jedem „Auswahl veraltet“ (Abschnitt 2), gleich ob eine Quelle oder ein Ziel der Anlass ist: geprüft für die
  Quellen nach jeder fremden Änderung, die die Ansicht neu zeichnen lässt, und für den ganzen Plan beim n-ten Ziel
  und beim Ausführen. Die Zielwahl endet mit der Meldung aus Abschnitt 7, die Auswahl ist danach leer.

Ein gewähltes Ziel, das nach einer fremden Änderung gesperrt ist oder nicht mehr zur Bestuhlung gehört, fällt beim
Neuzeichnen aus den Zielen; die Zielwahl läuft weiter.

Der Zustand der Zielwahl (Quellen, gewählte Ziele, Text der Leiste, ob vollständig) steht in einer Klasse ohne
JavaFX, damit er ohne Oberfläche testbar ist.

### 5. Kartentabelle: Zielwahl aus einer Liste

**Menü.** Das Kontextmenü bekommt nach „Platz zuweisen…“ denselben Eintrag wie die Saalansicht, mit derselben
Beschriftung und Sperre. Es zählen die belegenden Buchungen der Auswahl, die einen Platz haben.

**Ablauf.**

1. Eine laufende Bearbeitung einer Zelle endet zuerst (`seatTable.edit(-1, null)`), ein offener Verkaufsdialog
   schließt (`closeDialog()`), wie bei `assignPlace`.
2. Gehören die Quellen zu mehr als einer Vorstellung (Ansicht „Alle Vorstellungen“), endet der Vorgang mit der
   Meldung aus Abschnitt 7. Ebenso ohne Bestuhlung und wenn der Sitz einer Quelle gesperrt ist.
3. Ein Dialog zeigt alle Sitze der Bestuhlung, die nicht gesperrt sind (Abschnitt 2), sortiert nach dem Platz. Jede
   Zeile nennt den Platz und was dort ist: „frei“, den Kunden der belegenden Buchung (`EntityLabels.customer`, ohne
   Namen „Buchung ohne Namen“) oder bei einem Sitz einer Quelle zusätzlich „(wird verschoben)“. Ein Klick auf eine
   Zeile wählt sie oder wählt sie ab, ohne Strg. Über der Liste steht, für wen gewählt wird (wie die Leiste in
   Abschnitt 4), darunter „k von n Plätzen gewählt“. „Weiter“ ist nur mit genau n gewählten Plätzen aktiv,
   „Abbrechen“ beendet den Vorgang. Der Dialog zeigt den Stand beim Öffnen; ob er noch stimmt, prüft der Plan.
4. „Weiter“ berechnet den Plan. Lehnt er mit „nichts zu tun“ oder „Platz gesperrt“ ab, zeigt der Dialog den Grund in
   einer Zeile über den Knöpfen (die Fußzeile läge hinter dem modalen Dialog), und die Liste bleibt mit ihrer Wahl
   offen. Bei „Auswahl veraltet“ schließt die Liste, und der Vorgang endet mit der Meldung in der Fußzeile. Sonst
   öffnet sich der Bestätigungsdialog; „Abbrechen“ dort führt zur Liste zurück, die Wahl bleibt.
5. Nach dem Ausführen: Alle umgezogenen Buchungen (auch die der Gegenzüge) gelten als selbst geändert
   (`edits.pin`), die Tabelle wird neu aufgebaut, und die Quellen sind die Auswahl. Meldet das Ausführen „Auswahl
   veraltet“, endet der Vorgang mit der Meldung, und die Tabelle wird neu aufgebaut.

`AssignPlaceDialog` und `assignPlace` bleiben unverändert.

### 6. Bestätigungsdialog

Ein gemeinsamer Dialog für beide Ansichten, modal über dem Hauptfenster.

- Titel „Buchung verschieben“, bei mehr als einem Zug „Buchungen verschieben“.
- Je Zug eine Zeile in der Reihenfolge des Plans: `<Kunde>: <bisheriger Platz> → <neuer Platz>`, bei einem Gegenzug
  mit dem Zusatz „(Tausch)“. Kunde ist `EntityLabels.customer` (Vor- und Nachname, „Anna Müller“), ohne Namen
  „Buchung ohne Namen“; die Plätze heißen wie `EntityLabels.placeOf` („Tisch A3, Sitz 2“). Bei vielen Zeilen ist die
  Liste scrollbar.
- Nur wenn eine Buchung eines Zugs abgeholt ist: die Zeile „k der Buchungen sind abgeholt.“ (bei k = 1 „1 der
  Buchungen ist abgeholt.“, bei einem einzigen Zug „Die Buchung ist abgeholt.“) und darunter ein Häkchen „Markierung
  „Abgeholt“ behalten“, gesetzt.
- Knöpfe „Verschieben“ (Stil `button-primary`) und „Abbrechen“ (Stil `button-default`).

Beispiel:

```
Buchungen verschieben

  Anna Müller:    Tisch A3, Sitz 2  →  Tisch B1, Sitz 4
  Peter Schmidt:  Tisch B1, Sitz 4  →  Tisch A3, Sitz 2  (Tausch)

  1 der Buchungen ist abgeholt.
  [x] Markierung „Abgeholt“ behalten

                              [ Verschieben ]  [ Abbrechen ]
```

Die Texte der Zeilen entstehen in einer Klasse ohne JavaFX.

### 7. Ausführen, Speichern, Meldungen

Nach „Verschieben“ führt die Ansicht den Plan aus (Abschnitt 2), setzt danach gegebenenfalls die Abholmarkierung
zurück (Abschnitt 3), zeigt die Erfolgsmeldung und ruft dann bei „Buchungen sofort speichern“
`BookingAutoSave.flushActive()`, wie `CancelAction`. In dieser Reihenfolge ersetzt die Meldung des Speichers die
Erfolgsmeldung: Ein „Nicht gespeichert: …“ bleibt sichtbar. Mit der Einstellung sieht die Kasse deshalb „Gespeichert“
und die Buchungen am neuen Platz, nicht den Erfolgstext; der erscheint nur ohne die Einstellung. Dann bleibt die
Änderung ungespeichert wie jede andere, bis die Kasse speichert. In beiden Fällen kommt der ganze Vorgang in einem
Speichervorgang an.

Scheitert das Speichern (Prüfung nicht bestanden, oder eine Einheit wurde zwischen Plan und Speichern gesperrt),
bleibt der verschobene Stand ungespeichert im Modell, wie nach jeder anderen Änderung; der Vorgang wird nicht
zurückgenommen. Die Sperrprüfung aus Abschnitt 2 macht den zweiten Fall selten, schließt ihn aber nicht aus.

Meldungen in der Fußzeile (`StatusMessage`). Die Erfolgsmeldung richtet sich nach den Zügen des Plans, nicht nach
der Zahl der gewählten Quellen:

| Anlass | Art | Text |
|---|---|---|
| genau ein Zug | Erfolg | „Buchung von Tisch A3, Sitz 2 auf Tisch B1, Sitz 4 verschoben“ |
| genau zwei Züge, einer davon ein Gegenzug | Erfolg | „Buchungen auf Tisch A3, Sitz 2 und Tisch B1, Sitz 4 getauscht“ |
| sonst | Erfolg | „q Buchungen verschoben“, mit Gegenzügen „q Buchungen verschoben, g weitere im Tausch“; q zählt die Züge der Quellen, g die Gegenzüge |
| keine Bestuhlung | Fehler | `PlaceAssignment.noPlanText(presentation)` |
| mehrere Vorstellungen (Tabelle) | Fehler | „Verschieben geht nur innerhalb einer Vorstellung. Wähle Buchungen einer Vorstellung.“ |
| Platz gesperrt | Fehler | „<Platz> ist gesperrt (siehe „Gesperrte Einträge“) und lässt sich nicht verschieben oder belegen.“ |
| schon n Ziele gewählt | Hinweis | „Es sind schon n Plätze gewählt. Wähle zuerst einen ab.“ |
| Auswahl veraltet | Fehler | „Die Plätze haben sich inzwischen geändert. Nichts wurde verschoben, bitte neu wählen.“ |
| nichts zu tun | Hinweis | „Die Buchungen sitzen schon auf diesen Plätzen.“ |

### 8. Speicher und Konflikte

Dieses Ticket ändert weder den Speicher noch die Konfliktregeln. Der Umzug auf einen freien Platz ist derselbe Fall
wie der Umzug aus dem Konflikt-Dialog (Ausgangslage 3) und über zwei Rechner getestet. Neu im Betrieb ist, dass
eine Einheit im selben Speichervorgang Quelle und Ziel zugleich ist (Tausch, Kette, Ring). Dafür sind die Vermerke
vorgesehen (Ausgangslage 14 und 16), aber nur zum Teil getestet. Dieses Ticket schließt die Lücke mit Tests und legt
fest, was dabei gelten muss.

**Fehler im Bestand, eigene Behebung.** Ersetzen zwei Rechner auf derselben Basis die Buchung eines Sitzes je durch
eine andere neue Buchung, geht die Buchung des Rechners verloren, der später hochlädt: Sie steht weder auf einem Sitz
noch unter „Ohne Platz“, und es gibt keinen Vermerk (Ausgangslage 18). Das hängt nicht am Verschieben und betrifft die
veröffentlichte Version. Der Fehler wird deshalb nicht hier behoben, sondern nach der Entscheidung des Maintainers
vom 21.11.2026 als Bug KARV-201 auf der Wartungslinie (Patch-Version 1.1.2); KARV-202 bringt die Behebung per
Vorwärts-Merge nach `release/1.2.0` (`.github/CONTRIBUTING.md`, „Bugfixes und Patch-Versionen“).

**Abhängigkeit.** Das Verschieben trifft dieselbe Lücke auf zwei Wegen: Tauscht ein Rechner zwei Buchungen, während
ein anderer eine davon storniert und ihren Sitz neu verkauft, geht der Neuverkauf verloren, wenn der tauschende
Rechner zuerst hochlädt. Und verschiebt ein Rechner eine Buchung auf einen freien Sitz mit Resten, während ein
anderer diesen Sitz verkauft, läuft die Auflösung nach Lektüre über denselben Weg. KARV-140 wird deshalb erst in
`release/1.2.0` gemergt, wenn KARV-202 dort angekommen ist. Die Tests für diese Fälle (unten) gehören zu KARV-140;
sie sind vor dem Fix rot (das prüft der Umsetzer, damit sie nicht aus dem falschen Grund grün sind) und danach grün.

**Muss gelten** (harte Bedingung, für jeden Test unten und für beide Gewinner eines Konflikts):

- Nach dem Abgleich zeigen beide Rechner denselben Stand.
- Keine Buchung mit Daten geht verloren, außer eine Kasse hat sie storniert, und keine lebt doppelt.
- Eine Buchung, die ihren Platz in einem Konflikt verliert, steht danach unter „Ohne Platz“ (die bestehende Regel
  für den Doppelverkauf), nicht auf einem Sitz mit einer zweiten Buchung.

Verletzt der Bestand nach dem Fix des oben genannten Fehlers in einem der Tests unten noch eine dieser Bedingungen,
ist das ein weiterer Fehler der Konfliktregeln. Der Umsetzer meldet ihn dem Maintainer mit dem roten Test; der
entscheidet, ob er in diesem Ticket oder in einem eigenen behoben wird. Gemergt wird KARV-140 erst, wenn alle Tests
unten grün sind.

Ein Rechner ohne den Fix (1.1.x vor der Behebung) löst einen solchen Konflikt weiter mit Verlust auf, gleich wer
getauscht hat. Das gilt schon heute ohne KARV-140. Eine Bitte, alle Kassen zu aktualisieren, kommt dafür nicht in die
Patchnotes; darauf weist die Fußzeile hin, sobald eine Kasse eine neuere Version nutzt.

**Darf offen bleiben:** ob in jedem dieser Fälle ein Vermerk im Konflikt-Dialog erscheint und ob eine Buchung auf
einem frei gewordenen Sitz landen könnte statt unter „Ohne Platz“. Belegt ist ein Fall: Beim Tausch gegen Storno und
Neuverkauf des Zielsitzes landet die getauschte Buchung unter „Ohne Platz“, wenn der andere Rechner zuerst hochlädt,
aber ihr Vermerk „Doppelverkauf“ fehlt, weil `BookingPolicy` je Einheit nur einen Vermerk schreibt (Ausgangslage 16).
Die Tests halten das beobachtete Ergebnis fest; was dabei fehlt, steht im Pull Request und in einem Folgeticket. Die
Buchung bleibt in jedem Fall über die Leiste „Ohne Platz“ der Tabelle sichtbar.

**Tests:**

- `UnitMapperTest`: Vermerke einer Kette (A 1→2, B 2→3 auf einen freien Sitz) und eines Rings über drei Sitze (A
  1→2, B 2→3, C 3→1, die Kette mit Gegenzug aus Abschnitt 2).
- `BookingPolicyTest` (innere Klasse `World`), jeweils für beide Gewinner: Tausch gegen Storno einer der beiden
  Buchungen; Tausch gegen einen weiteren Umzug einer der beiden Buchungen auf einen dritten Sitz; Tausch gegen Storno
  und Neuverkauf des Zielsitzes; eine Kette gegen eine Feldänderung an einer ihrer Buchungen; zwei überlappende
  Täusche (ein Rechner tauscht A und B, der andere B und C).
- `ConflictScenarioTest`, über zwei Rechner mit `BookingMove` statt des Helfers `move`, wo es einen Konflikt gibt für
  beide Reihenfolgen des Hochladens: ein Tausch und eine Kette, die der zweite Rechner ohne eigene Änderung
  übernimmt (kein Vermerk, beide Stände gleich); ein Tausch gegen eine Feldänderung des zweiten Rechners (beide
  Buchungen einmal, die Änderung bleibt); ein Tausch gegen ein Storno des zweiten Rechners; ein Tausch gegen Storno
  und Neuverkauf des Zielsitzes; ein Umzug auf einen freien Sitz mit Resten gegen den Verkauf dieses Sitzes. Die
  letzten beiden hängen an KARV-201.

### 9. Tests

- `model/BookingMoveTest`: jede Zeile der Beispieltabelle aus Abschnitt 2; jeder Ablehnungsgrund, auch jede
  Bedingung von „Auswahl veraltet“ und „Platz gesperrt“ für Quelle und Ziel (frei wirkend und belegt); Reste am freien Ziel
  verschwinden; Kennung, Zahlung, Einzelpreis, Daten und unbekannte Felder bleiben; die Momentaufnahme (auch Kategorie
  und Rollstuhl) kommt vom neuen Sitz; nach dem Ausführen zeigt auf jeden Sitz der Bestuhlung genau eine Buchung, und
  ein zweiter `BookingSync.sync` ändert nichts; ein Plan, der beim Ausführen nicht mehr stimmt (Ziel inzwischen
  belegt, Ziel inzwischen mit Resten, Quelle trägt eine andere Kennung), ändert nichts.
- Abholmarkierung: behalten und entfernen, auch beim Gegenzug; das Abholdatum ist nach dem Entfernen leer.
- Texte und Sperre des Menüeintrags ohne Oberfläche, nach dem Muster von `CancelActionTest`: eine, mehrere, keine
  belegende Buchung, Anzeigemodus. Dazu Zeilen des Bestätigungsdialogs, Leiste der Zielwahl, Meldungen.
- Zustand der Zielwahl ohne Oberfläche: Ziel wählen und abwählen, n erreicht, weiterer Klick bei n, gesperrtes Ziel,
  Quelle wird ungültig, Ziel fällt weg.
- `LockedSeatsTest`: die neue Abfrage nennt auch den Sitz einer belegenden Buchung auf gesperrter Einheit.
- Speicher: siehe Abschnitt 8.

Die Controller selbst (`HallOverviewController`, `TicketTableController`) haben heute keine Tests, die ein Menü
aufbauen; dieses Ticket führt keine ein. Was dort neu ist, wird im Handtest geprüft (Abnahme 1 bis 8).

### 10. Dokumentation und Patchnotes

- `docs/usage.md`, „Selling tickets“: ein Absatz zum Verschieben und Tauschen (beide Ansichten, mehrere auf einmal,
  Rückfrage bei abgeholten Buchungen).
- `docs/decisions.md`: ein Eintrag, dass eine Buchung beim Platzwechsel ihre Kennung behält, mit der Regel für
  Ketten und Gegenzüge.
- `docs/architecture.md` und `docs/structure.md`: `BookingMove` dort nennen, wo `BookingSync` genannt ist.
- `patchnotes.md`, Abschnitt 1.2.0, „Neu“ (vorläufiger Text): „Buchungen lassen sich im Bearbeitungsmodus auf andere
  Plätze derselben Vorstellung verschieben oder mit anderen tauschen, auch mehrere auf einmal: Rechtsklick auf die
  Buchung, dann „Verschieben…“.“

## Der Umsetzung überlassen

- Namen und Signaturen der neuen Klassen und Methoden, außer `model/BookingMove`.
- Aussehen der Leiste und der Markierung der Ziele im Rahmen von Abschnitt 4.
- Bau der Liste in Abschnitt 5 (etwa Häkchen je Zeile) und ob sie Teile von `AssignPlaceDialog` wiederverwendet.
- Ob die Texte in einer oder mehreren Klassen stehen.
- Wo genau die Saalansicht das Verlassen der Ansicht bemerkt.

## Abnahme

1. In der Saalansicht lässt sich im Bearbeitungsmodus eine reservierte, eine bezahlte und eine abgeholte Buchung
   auf einen freien Platz verschieben. Danach ist der alte Platz frei, und die Buchung trägt am neuen Platz dieselbe
   Kennung, denselben Namen, dieselbe Zahlung und dieselben Daten.
2. Wählt die Kasse als Ziel einen belegten Platz, zeigt der Bestätigungsdialog beide Züge, den zweiten mit
   „(Tausch)“, und nach „Verschieben“ haben die Buchungen die Plätze getauscht.
3. Vier Buchungen lassen sich in einem Vorgang auf vier Plätze verschieben; die Zuordnung folgt der Reihenfolge der
   Plätze und steht vor dem Ausführen im Dialog.
4. Dasselbe geht aus der Kartentabelle über die Liste der Plätze.
5. Ist eine Buchung des Vorgangs abgeholt, fragt der Dialog nach der Markierung. Ohne Häkchen ist die Buchung danach
   nicht mehr abgeholt und hat kein Abholdatum.
6. Im Anzeigemodus ist der Eintrag gesperrt. Ein gesperrter Sitz lässt sich nicht als Ziel wählen, und eine Buchung
   auf einer gesperrten Einheit lässt sich nicht verschieben; die Fußzeile nennt den Platz.
7. Wird ein Zielplatz verkauft, während der Bestätigungsdialog offen ist, wird nichts verschoben, und die Fußzeile
   sagt das.
8. Mit „Buchungen sofort speichern“ ist der Vorgang nach „Verschieben“ gespeichert, in einem Speichervorgang.
9. Ein zweiter Rechner am gemeinsamen Ordner zeigt nach dem Abgleich die Buchungen an den neuen Plätzen, ohne
   Konfliktmeldung, wenn er nichts an ihnen geändert hat.
10. Die Tests aus Abschnitt 8 sind grün. Die Tests in `BookingPolicyTest` und `ConflictScenarioTest` prüfen in jedem
    Fall die drei Bedingungen unter „Muss gelten“; die in `UnitMapperTest` prüfen die Vermerke.
11. `./mvnw test` ist grün; die neuen Tests aus Abschnitt 9 sind dabei.
12. Der Pull Request nennt, in welchen Konfliktfällen ein Vermerk fehlt oder eine Buchung unter „Ohne Platz“ landet,
    obwohl ein Sitz frei wäre, und verweist auf das Folgeticket dafür.

## Prüfvermerk

**Runde 1, 21.11.2026.** Umsetzer: 0 kritisch, 0 groß, 5 klein. Gegenprüfer: 0 kritisch, 2 groß, 5 klein.

Behoben:

- *Groß (Gegenprüfer):* Im Konflikt „Tausch gegen Storno und Neuverkauf des Zielsitzes“ geht nach Lektüre eine
  Buchung verloren, und der Weg ist nicht tauschspezifisch. Der Autor hat das mit einem Wegwerf-Test über zwei Rechner
  ausgeführt und bestätigt (Ausgangslage 18). Folge im Spec: eigener Bugfix außerhalb dieses Tickets, KARV-140 wird
  erst danach gemergt (Randbedingungen, Abschnitt 8).
- *Groß (Gegenprüfer):* Eine Quelle oder ein Gegenzug auf einer gesperrten Einheit mit älterem belegten Stand wurde
  nicht abgelehnt und hätte das Speichern des Events scheitern lassen. Jetzt lehnt der Plan jeden Vorgang ab, der
  einen Sitz mit gesperrter Einheit berührt (Abschnitt 2, „Gesperrte Sitze“); ein gescheitertes Speichern aus anderem
  Grund ist ausdrücklich hingenommen (Abschnitt 7).
- *Klein:* Stand auf `3d44279` mit KARV-108 und neue Fundstellen der Saalansicht; ein Kreis aus Quellenzügen ist mit
  der Zuordnung nach Platzreihenfolge unmöglich und gestrichen; Zustand nach Ablehnung, geänderter Suche und Klick
  ins Leere festgelegt; Ziele über die Sitzkennung, „Ziel kein Sitz der Bestuhlung mehr“ als „Auswahl veraltet“;
  Reste am Ziel gehen in den Planvergleich ein; Kunde heißt „Anna Müller“ (`EntityLabels.customer`); Reihenfolge von
  Erfolgsmeldung und Speichern; Erfolgsmeldungen nach Zügen; Tests der Controller gestrichen, weil es kein Muster
  gibt, dafür Handtest.

Nicht übernommen: nichts.

**Runde 2, 21.11.2026** (neues Paar, auf dem geänderten Spec). Umsetzer: 0 kritisch, 0 groß, 5 klein. Gegenprüfer:
0 kritisch, 0 groß, 5 klein. Die Prüfung ist damit beendet.

Die kleinen Befunde sind eingearbeitet: jedes „Auswahl veraltet“ beendet die Zielwahl; Vorrang der Ablehnungsgründe
und Umgang mit „Anzahl“; Quellen und Ziele werden nach derselben Ordnung über den Sitz sortiert, mit der Sitzkennung
als letztem Kriterium; der Grund einer Ablehnung steht im Listendialog selbst; Esc wird an der Szene abgefangen, weil
der Plan keinen Fokus bekommt; die Erfolgstexte sind mit „Buchungen sofort speichern“ nicht zu sehen; der Umzug von
Sitz zu Sitz im Bestand heißt „Meinen Wert übernehmen“, nicht „Platz zuweisen…“; drei weitere Konflikttests (Tausch
gegen Storno und Neuverkauf über zwei Rechner, Umzug auf einen Sitz mit Resten gegen dessen Verkauf, zwei
überlappende Täusche). Nach der Runde kamen außerdem die Ticketnummern KARV-201 und KARV-202 hinzu.

Nicht übernommen: nichts.

Nicht belegt:

- Die Ursache des Fehlers aus Ausgangslage 18 ist gelesen, nicht per Debugger verfolgt. Der Wegwerf-Test lief nur auf
  `release/1.2.0`; auf der Linie 1.1 sind die Pakete `repository` und `store` dieselben.
- Ob die Konfliktregeln die übrigen Fälle aus Abschnitt 8 (Tausch gegen Storno, Tausch gegen weiteren Umzug, Kette
  gegen Feldänderung, überlappende Täusche) schon heute erfüllen, ist nicht ausgeführt; das zeigen die Tests der
  Umsetzung. Dass der Umzug auf einen Sitz mit Resten gegen dessen Verkauf eine Buchung verliert, ist gelesen, nicht
  ausgeführt.
- Ob die Liste der gesperrten Einträge in jedem Moment genau das enthält, was das Speichern ablehnt (wartende
  Einheiten), ist nicht geprüft; Abschnitt 7 nimmt den Rest hin.
- Das Verhalten eines modalen Dialogs während einer fremden Änderung und das Zeichnen der Zielmarke sind nicht
  ausprobiert.

## Nachtrag vom 26.11.2026

Freigegeben vom Maintainer am 26.11.2026 im Gespräch. Seit der Prüfung hat sich der Bestand geändert; der Text oben
ist bis auf einen Satz zu den Patchnotes (Abschnitt 8) unverändert geblieben.

- **Die Abhängigkeit ist erfüllt.** KARV-201 ist behoben und mit 1.1.2 veröffentlicht; der Vorwärts-Merge
  (`b155b57`, Pull Request 70) hat ihn nach `release/1.2.0` gebracht, zusammen mit KARV-205 und KARV-209 an denselben
  Konfliktregeln. Der Arbeits-Branch steht auf diesem Stand.
- **Ausgangslage 16 und 18 beschreiben den Stand vom 21.11.2026.** `BookingPolicy` und `BookingTrail` sind seitdem
  geändert, die Zeilenangaben dort stimmen nicht mehr. `ConflictScenarioTest` enthält jetzt
  `bothCancelAndResellIsADoubleSale`, `doubleSaleOnASeatWithLeftoversKeepsBothBookings` und
  `swapAgainstCancelAndResellKeepsBothBookingsOnce` (der Tausch dort von Hand gebaut). Die Tests aus Abschnitt 8
  kommen daneben und nutzen `BookingMove`; die Forderung „vor dem Fix rot“ entfällt für die Fälle, die diese Tests
  schon abdecken.
- **Weiter offen:** `BookingPolicy` schreibt je Einheit nur einen Vermerk (heute `repository/BookingPolicy.java:213`).
- **Neu im Verkauf:** KARV-215 zeigt die Zahl der freien Karten (`ui/sales/FreeSeats`). Ein Vorgang dieses Specs
  ändert die Zahl der freien Plätze nicht.
