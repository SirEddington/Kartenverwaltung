# KARV-194: Anzahl und Preis der gewählten Karten am Mauszeiger

Stand: Entscheidung vom 18.11.2026. Ein Spec ist eine Momentaufnahme und wird nach dem Merge nicht gepflegt; was
dauerhaft gilt, steht in `docs/decisions.md` und der übrigen Dokumentation.

## Anlass und Ziel

Rückmeldung aus dem Verkauf: „Anzahl der Karten bei Markierung anzeigen (vereinfacht Verkauf bzw. Vergeben der
richtigen Anzahl an Karten)". Heute steht die Anzahl erst im Kontextmenü („Ausgewählte Sitze verkaufen… (6)") und im
Kundenfenster; das Kontextmenü kommt erst nach dem Auswählen, das Kundenfenster steht auf einem anderen Bildschirm.

Ziel: In der Verkaufsansicht (Saalansicht und Kartentabelle) steht neben dem Mauszeiger ein kleiner Hinweis „n Karten
gewählt · Betrag", sobald mindestens eine Karte gewählt ist. Er lässt sich im Menü „Anzeige" abschalten.

## Ziele, Nicht-Ziele, Anti-Ziele

**Ziele:**

- Hinweis am Mauszeiger mit Anzahl und Gesamtpreis der Auswahl, in Saalansicht und Kartentabelle, ab einer Karte.
- Er folgt dem Mauszeiger und aktualisiert sich, sobald sich die Auswahl ändert.
- Schalter „Auswahl am Mauszeiger" im Menü „Anzeige", gespeichert wie die übrigen Einträge dort (KARV-76).

**Nicht-Ziele:**

- Hallen-Editor und Abrechnung bekommen keinen Hinweis.
- Keine Vorschau der Anzahl während des Aufziehens eines Auswahlrahmens (siehe Entwurf, „Auswahlrahmen").
- Der Code des Kundenfensters ändert sich nicht. Sein Inhalt ändert sich nur in dem einen Fall unter „Saalansicht im
  Anzeigemodus", weil es dieselbe Auswahl liest.
- Keine Änderung daran, wann es eine Auswahl gibt, außer der einen Lücke unter „Saalansicht im Anzeigemodus" im
  Entwurf: Die Saalansicht kennt eine Auswahl nur im Bearbeitungsmodus.

**Anti-Ziele:**

- Der Hinweis fängt Klicks, Rechtsklicks, Mausrad oder das Aufziehen ab oder verändert, welcher Sitz bzw. welche
  Zeile getroffen wird.
- Der Hinweis steht über oder neben einem offenen Sitz-Dialog, Kontextmenü oder modalen Dialog.
- Die Zahl weicht von der im Kontextmenü ab.
- Eine geschlossene Verkaufsansicht wird über einen Listener am Leben gehalten.

## Randbedingungen

- 1.1.1 ist eine Patch-Version. `CONTRIBUTING.md`, „Versionen", sagt wörtlich, sie „ändert nichts an gespeicherten
  Daten", und eine kleine Funktion, die ein neues Feld speichert, gehöre deshalb in die zweite Stelle. Der neue
  Schalter speichert ein Feld in `settings.json`. **Auslegung:** Die Regel begründet sich mit dem gemeinsamen Umstieg
  aller Rechner eines Ordners („deshalb"); `settings.json` ist eine lokale Einstellung je Rechner und verlangt keinen
  gemeinsamen Umstieg. Der Maintainer bestätigt diese Auslegung mit der Freigabe des Specs.
- Abwärts: Eine `settings.json` ohne das neue Feld gilt als „an". Vorwärts: 1.1.0 liest eine Datei mit dem neuen
  Feld, weil `DisplaySettings` unbekannte Felder ignoriert (`@JsonIgnoreProperties(ignoreUnknown = true)`); schreibt
  1.1.0 danach, fällt das Feld weg und gilt wieder als „an". Das ist hinnehmbar.
- Arbeits-Branch `feature/KARV-194-auswahl-am-mauszeiger` von `release/1.1.0` (Wartungslinie). Der Versionssprung
  auf 1.1.1 (`chore/version-1.1.1`) ist ein eigener Pull Request und nicht Teil dieser Änderung.
- Texte deutsch, Beschreibung unter `docs/` englisch (`CONTRIBUTING.md`, „Sprache").
- Nur JavaFX-Thread; Windows und Linux gleich (keine plattformabhängigen Fenster).

## Ausgangslage

Geprüft am 18.11.2026 auf `origin/release/1.1.0` (Commit `825d66d`).

- **Gemeinsame Auswahl.** `SalesViewState.selectedBookings()` ist ein `ObservableSet<Booking>`
  (`ui/sales/SalesViewState.java:28`, `:53`). Die Saalansicht arbeitet direkt auf diesem Set
  (`HallOverviewController.setSalesState`, `:448`: `selectedBookings = state.selectedBookings()`); die Tabelle
  spiegelt ihre gewählten Zeilen hinein (`TicketTableController.setSalesState`, `:704-707`, über
  `state.replaceSelection`, auch ohne Bearbeitungsmodus).
- **Auswahl in der Saalansicht** gibt es nur im Bearbeitungsmodus: Rahmen nur mit `editMode`
  (`HallOverviewController.java:174`), Strg-Klick `:388`, Einzelklick wählt den Sitz und öffnet seine Karte
  (`:393-399`); nach dem Schließen der Karte bleibt der Sitz gewählt. Die Auswahl des Rahmens entsteht erst beim
  Loslassen (`finishRubberBand`, `:240-255`). `applyEditMode` leert die Auswahl („Die Mehrfachauswahl gibt es nur im
  Bearbeitungsmodus", `:436-440`). **Lücke:** `restoreSelection` (`:460-468`) prüft den Modus nicht. Die Shell ruft
  beim Wechsel der Ansicht erst `setEditMode` (`TicketShellController.java:287`) und merkt die Auswahl vor (`:288`);
  `restoreSelection` folgt danach über `restorePendingSelection()` (`:256-262`) aus `showTicketTableView` bzw.
  `showHallView` (`:240`, `:249`). Das Javadoc von `showSelectionMenu` (`HallOverviewController.java:271-272`, aus
  KARV-59) rechnet mit einer solchen Auswahl im Anzeigemodus. Wer im Anzeigemodus Tabellenzeilen wählt und zur Saalansicht wechselt, hat
  dort eine Auswahl mit Halo, die sich im Anzeigemodus nicht aufheben lässt (Klick ins Leere nur mit `editMode`,
  `:174`, `:192`).
- **Zahl im Kontextmenü.** Saalansicht: `SeatSelectionEditor.menuText(selectedBookings.size())` auf einem gewählten
  Sitz (`HallOverviewController.java:287`). Tabelle: Zahl der gewählten Zeilen (`TicketTableController.java:236`).
- **Kundenfenster.** `SelectionSummary.of(state.selectedBookings(), presentation)` zählt nur Buchungen der gezeigten
  Vorstellung, ohne gezeigte Vorstellung („Alle Vorstellungen") ist es leer; Preis ist die Summe von
  `Booking.getEffectivePrice()` (`ui/sales/SelectionSummary.java:31-38`; `CustomerDisplay.java:283-288`).
  `SelectionSummary.countText(n)` liefert „1 Karte" bzw. „n Karten" (`:41-46`). `Booking.getEffectivePrice()` gibt
  Cent als `int` (`model/Booking.java:287`), `MoneyFormat.formatCents(long)` formatiert (`util/MoneyFormat.java:15`).
- **Shell.** `TicketShellController` hält einen `SalesViewState` (`:57`) und ersetzt beim Wechsel der Ansicht den
  Inhalt von `contentArea` (ein `StackPane`, `TicketShellView.fxml:65`) mit
  `contentArea.getChildren().setAll(view)` (`TicketShellController.java:274`); davor schließt
  `PopupWindows.hideOwnedBy` die Popups des Fensters (`:273`). Listener, die nur gelten sollen, solange die Shell zu
  sehen ist, hängen über `LiveViews.whileShown(contentArea, …)` (`:144-145`).
- **Menü „Anzeige".** `TicketShellController.initialize` füllt `menuDisplay` mit den Beschriftungen,
  „Barrierefreie Sitzdarstellung" (`PlanLabelsMenu.item(text, BooleanProperty)`) und der Platzierung der
  Kaufzusammenfassung (`:111-114`). Der Hallen-Editor hat ein eigenes Menü ohne diese beiden Einträge. `initialize`
  zeigt am Ende die Tabelle (`showTicketTableView()`, `:196`), lädt also schon dort die erste Ansicht.
- **Schwache Listener.** Die Properties des Menüs gehören zur app-weiten `DisplayPreferences.instance()`; Ansichten
  folgen ihnen schwach über `LiveViews.follow(node, property, action)` (`ui/LiveViews.java:58-70`, genutzt in
  `HallOverviewController.java:453`), damit die Instanz geschlossene Ansichten nicht festhält (`docs/decisions.md`,
  KARV-76, „Details").
- **Speicherung des Menüs.** `DisplaySettings` (`settings/DisplaySettings.java`) trägt die Felder in
  `settings.json`; `DisplayPreferences` (`ui/DisplayPreferences.java:50-65`) übernimmt sie beim Start nur mit
  `restoreAtStart`, hängt danach Listener an und schreibt jede Änderung per `saveOnly` (`:104-123`).
  `SalesViewState` reicht die Properties durch (`:61-81`). Der Erklärtext in den Einstellungen zählt die Einträge
  auf (`ui/settings/DisplaySection.java:13-16`). Tests: `settings/DisplaySettingsTest`, `ui/DisplayPreferencesTest`.
- **Popups.** Sitz-Dialog (`SeatDetailPopup`, ein `javafx.stage.Popup`, gezeigt mit dem Hauptfenster als Besitzer,
  `HallOverviewController.java:413-415`), Kontextmenüs und Tooltips der Sitze (`:375`) sind `PopupWindow`s des
  Hauptfensters (`ui/PopupWindows.java:9-10`). Modale Dialoge (Storno-Rückfrage, Platz zuweisen) sind eigene Stages.

## Betrachtete Wege

- **A: ein Hinweis in der Shell** (gewählt vom Entwickler am 18.11.2026). Ein mausdurchlässiges Label über
  `contentArea`, das die gemeinsame Auswahl liest und dem Mauszeiger folgt. Eine Stelle für beide Ansichten; die
  Ansichten ändern sich nicht. Offene Dialoge erkennt er allgemein über die Fenster statt über die Ansichten.
- **B: je Ansicht ein eigener Hinweis**, wie heute der Auswahlrahmen der Saalansicht. Jede Ansicht kennt ihren
  Dialog direkt, dafür steht Anzeige, Text und Platzierung doppelt da, und die Tabelle braucht eine zusätzliche
  Hülle, weil eine `TableView` keine Kinder aufnimmt.
- **C: eigenes Popup-Fenster** wie ein Tooltip. Liegt über allem, auch über Dialogen, muss in jedem Fall von Hand
  versteckt werden und kann je nach System Mausereignisse abfangen. Verworfen.

Ebenfalls vom Entwickler entschieden (18.11.2026): ab 1 Karte, auch über der Kartentabelle, mit Gesamtpreis,
abschaltbar im Menü „Anzeige".

## Entwurf

### Neue Klasse `ui.sales.SelectionHint`

Besitzt das Label und alle Regeln. Die Shell erzeugt eine Instanz je Shell.

**Text** (reine Funktion, ohne JavaFX testbar), z. B. `static String text(Collection<Booking> selected)`:

    SelectionSummary.countText(n) + " gewählt · " + MoneyFormat.formatCents(summe)

- `n` = Zahl der Buchungen in `selected`, `summe` = Summe von `getEffectivePrice()` über alle (als `long`).
- Beispiele: „1 Karte gewählt · 12,00 €", „5 Karten gewählt · 60,00 €"; ein Preis von 0 steht als „0,00 €" da.
- Gezählt werden alle gewählten Buchungen, auch die anderer Vorstellungen (Tabelle mit „Alle Vorstellungen"). So
  stimmt die Zahl immer mit dem Kontextmenü überein. Mit einer gewählten Vorstellung stimmt sie auch mit dem
  Kundenfenster überein; bei „Alle Vorstellungen" zeigt das Kundenfenster wie heute nichts.

**Sichtbar** genau dann, wenn alle Bedingungen gelten (als reine Funktion testbar):

1. Schalter „Auswahl am Mauszeiger" an.
2. Die Auswahl (`state.selectedBookings()`) ist nicht leer.
3. Der Mauszeiger ist über `contentArea`: Die zuletzt bei `MOUSE_MOVED` oder `MOUSE_DRAGGED` gemerkte Position liegt
   innerhalb der Grenzen von `contentArea` (`getLayoutBounds().contains` der lokalen Position), und seitdem kam kein
   `MOUSE_EXITED` von `contentArea`. Die Prüfung der Grenzen ist nötig, weil bei gedrückter Taste (Rahmen,
   Verschieben) `MOUSE_DRAGGED` weiter an das Ziel in `contentArea` geht, auch wenn der Zeiger schon draußen ist. Vor
   der ersten Mausbewegung gilt „nicht drüber".
4. Das Hauptfenster (das Fenster der Szene von `contentArea`) hat den Fokus. Ein modaler Dialog oder ein Klick ins
   Kundenfenster nimmt ihn und blendet den Hinweis damit aus.
5. Kein **blockierendes Popup** ist offen: ein sichtbares `PopupWindow`, das kein `Tooltip` ist und dessen
   Besitzerkette (`getOwnerWindow()` bis zum ersten Nicht-Popup) beim Hauptfenster endet. Das deckt Sitz-Dialog,
   Kontextmenüs, Auswahllisten und Datumsauswahl in Zellen oder im Dialog ab. Tooltips der Sitze blockieren nicht,
   sonst flackerte der Hinweis beim Überfahren der Sitze.

Neu ausgewertet wird bei: Änderung der Auswahl (Listener am `ObservableSet`), Mausbewegung bzw. Verlassen, Änderung
von `focusedProperty` des Hauptfensters, Änderung von `Window.getWindows()` (dort stehen nur sichtbare Fenster),
Änderung des Schalters, und einmal beim Anmelden über `whileShown` (Shell wird sichtbar). Bei jeder Auswertung, in der der Hinweis sichtbar ist oder wird, wird der Text neu berechnet,
also auch bei jeder Mausbewegung. So folgt der Betrag spätestens mit der nächsten Mausbewegung, wenn ein anderer
Rechner einen Preis ändert, ohne dass sich die Auswahl ändert.

**Platz:** Das Label ist `managed = false` und `mouseTransparent = true`, damit `StackPane` es nicht anordnet und es
keine Ereignisse bekommt. Weil `StackPane` nicht gemanagte Kinder nicht auslegt, ruft der Hinweis nach jeder
Textänderung `applyCss()` und `autosize()` auf, bevor er die Position aus seiner Breite und Höhe berechnet.

Die Ecke steht **rechts über** dem Mauszeiger: linke Kante 16 px rechts vom Zeiger, untere Kante 8 px über dem Zeiger
(Koordinaten von `contentArea`, aus `sceneToLocal` der Mausposition). Rechts über, weil die Tooltips der Sitze rechts
**unter** dem Zeiger erscheinen und den Hinweis sonst verdecken. Ragte er rechts aus `contentArea`, steht er links vom
Zeiger (rechte Kante 8 px links davon); ragte er oben hinaus, steht er **links** unter dem Zeiger (rechte Kante 8 px
links davon, obere Kante 20 px darunter), damit er auch dort nicht unter dem Tooltip liegt.
Nie links oder oben außerhalb von `contentArea` (auf 0 begrenzen). Die Spitze des Mauszeigers bleibt damit frei; ein
Sitz unter der Spitze ist nicht verdeckt.

**Aussehen:** eigene Stilklasse `selection-hint` in `ui/style.css`: heller Hintergrund, dünner Rahmen, kleiner
Innenabstand, Schrift wie ein Tooltip; lesbar über weißem Plan und über Tabellenzeilen.

**Lebensdauer:** Nichts App-Weites darf die Shell festhalten (Anti-Ziel).

- Die Listener an `Window.getWindows()` (statische Liste) und an `focusedProperty` des Hauptfensters hängen nur,
  solange die Shell zu sehen ist (`LiveViews.whileShown(contentArea, …)`). Das Hauptfenster ist das Fenster der Szene
  von `contentArea` beim Anmelden.
- Dem Schalter (`state.selectionHintProperty()`, eine Property der app-weiten `DisplayPreferences`) folgt der Hinweis
  schwach über `LiveViews.follow(contentArea, property, …)`, wie die Saalansicht `seatPatterns`.
- Der Listener an `state.selectedBookings()` und die Ereignisfilter an `contentArea` gehören zur Shell selbst und
  dürfen stark sein.

### `TicketShellController`

- Erzeugt in `initialize` den `SelectionHint` mit `contentArea`, `state.selectedBookings()` und
  `state.selectionHintProperty()`, **bevor** `showTicketTableView()` die erste Ansicht lädt, denn `loadContentView`
  braucht `hint.node()`.
- `loadContentView`: `contentArea.getChildren().setAll(view, hint.node())`, damit der Hinweis über jeder Ansicht
  liegt (Reihenfolge: Ansicht zuerst).
- Menü „Anzeige": nach „Barrierefreie Sitzdarstellung" der Eintrag
  `PlanLabelsMenu.item("Auswahl am Mauszeiger", state.selectionHintProperty())`.

### `SalesViewState`

- `selectionHintProperty()` reicht `display.selectionHintProperty()` durch, wie `seatPatternsProperty()`.

### `DisplayPreferences` und `DisplaySettings`

- `DisplaySettings`: Feld `selectionHint` (Standard `true`) mit Getter/Setter; fehlt es in der Datei, ist es an.
- `DisplayPreferences`: `BooleanProperty selectionHint` (Standard `true`), beim Start mit `restoreAtStart`
  übernommen, danach mit Listener auf `write()`; `write()` schreibt das Feld mit. Ohne `restoreAtStart` gilt beim
  Start „an".
- `DisplaySection.RESTORE_HINT`: die Aufzählung nennt „Auswahl am Mauszeiger" mit.

### Saalansicht im Anzeigemodus

`HallOverviewController.restoreSelection` übernimmt die Auswahl nur im Bearbeitungsmodus; im Anzeigemodus leert es
sie und zeichnet neu, wie `applyEditMode`. Damit gilt die schon dokumentierte Regel „Die Mehrfachauswahl gibt es nur
im Bearbeitungsmodus" auch nach dem Wechsel aus der Tabelle, und der Hinweis steht im Anzeigemodus der Saalansicht
nie. Die Reihenfolge der Shell (erst `setEditMode`, dann `restoreSelection`) bleibt; `editMode` ist beim Aufruf also
schon gesetzt. Wechselt man danach zurück zur Tabelle, ist die Auswahl leer, wie heute nach einem Moduswechsel in der
Saalansicht (die Tabelle selbst behält ihre Auswahl beim Moduswechsel, daran ändert sich nichts).

Folgen: Das Kundenfenster zeigt in diesem Fall keine Auswahl mehr (heute zeigt es die aus der Tabelle übernommene).
Das Javadoc von `showSelectionMenu` wird angepasst: Im Anzeigemodus gibt es keine Auswahl, der Eintrag nennt deshalb
immer einen Sitz. Die Patchnotes nennen die Änderung.

### Auswahlrahmen

Während des Aufziehens zeigt der Hinweis die bisherige Auswahl; die neue Zahl erscheint beim Loslassen, wenn
`finishRubberBand` die Auswahl setzt. Eine Vorschau während des Ziehens bräuchte die Trefferberechnung bei jeder
Mausbewegung und gehört nicht in diese Patch-Version.

### Verhalten in Fällen

| Fall | Hinweis |
|---|---|
| Saalansicht, Anzeigemodus (auch nach Wechsel aus der Tabelle mit gewählten Zeilen) | keine Auswahl, also nie zu sehen; das Kundenfenster zeigt dann ebenfalls keine Auswahl |
| Saalansicht, Bearbeitungsmodus, Einzelklick auf Sitz | Sitz-Dialog offen: aus; nach dem Schließen „1 Karte gewählt · …" am Zeiger |
| Strg-Klick, Rahmen | Zahl ändert sich beim Klick bzw. beim Loslassen |
| Klick ins Leere | Auswahl leer: aus |
| Rechtsklick auf Sitz | Kontextmenü offen: aus; danach wieder an, solange die Auswahl besteht |
| Tabelle, jede Zeilenauswahl (auch im Anzeigemodus) | an, sobald eine Zeile gewählt ist und der Zeiger über dem Inhaltsbereich ist |
| Tabelle, Zelle im Bearbeiten mit Auswahlliste/Datumsauswahl | deren Popup offen: aus |
| Suchfilter blendet gewählte Sitze/Zeilen aus | sie fallen heute schon aus der Auswahl; Zahl folgt |
| Fremde Änderung entfernt gewählte Buchung | Saalansicht nimmt sie aus der Auswahl (`dropGoneBookingsFromSelection`); Zahl folgt |
| Wechsel Tabelle ↔ Saalansicht | `resetView` leert, `restoreSelection` setzt neu; Zahl folgt |
| Storno-Rückfrage, Platz zuweisen (modal) | Hauptfenster ohne Fokus: aus |
| Zeiger über Kopfzeile, Fußzeile oder außerhalb des Fensters, auch beim Ziehen mit gedrückter Taste | aus |
| Tooltip eines Sitzes erscheint | Hinweis bleibt (rechts über dem Zeiger, Tooltip rechts darunter) |
| Schalter aus | nie zu sehen |

### Tests

- `SelectionHintTest` (ohne JavaFX-Toolkit): Text für 1 und mehrere Karten, Summe über verschiedene Preise und über
  zwei Vorstellungen, Preis 0; Sichtbarkeit für jede der fünf Bedingungen einzeln.
- Die Regel „blockierendes Popup" als Funktion über Fenster testbar machen, soweit es ohne laufendes Toolkit geht;
  sonst Handtest.
- `DisplaySettingsTest`: fehlendes Feld ergibt `true`; Hin- und Rückweg über JSON.
- `DisplayPreferencesTest`: Übernahme mit und ohne `restoreAtStart`; eine Änderung schreibt `selectionHint`.
- `HallOverviewController` hat keine Tests (Stand 18.11.2026); die Änderung an `restoreSelection` prüft der
  Handtest.
- Platzierung als reine Funktion (Zeiger, Größe des Hinweises, Größe von `contentArea` → Ecke): Normalfall rechts
  über, rechter Rand, oberer Rand, beide.

### Dokumentation

- `docs/usage.md`: Hinweis am Mauszeiger in Saalansicht und Tabelle, Schalter im Menü „Anzeige".
- `docs/decisions.md`, KARV-76: Aufzählung der Einträge um den Schalter ergänzen.
- `docs/structure.md` (Felder von `DisplaySettings`) und `docs/architecture.md` (Beschreibung der
  `DisplayPreferences`), wo sie die Felder aufzählen.
- Patchnotes: vorläufiger Eintrag für 1.1.1.

## Der Umsetzung überlassen

- Genaue Signaturen von `SelectionHint` (z. B. ob die Sichtbarkeit als Record oder als Funktion mit Parametern
  geprüft wird).
- Farben und Schriftgröße der Stilklasse im Rahmen von „wie ein Tooltip, gut lesbar".
- Ob die Position bei jeder Mausbewegung oder nur bei sichtbarem Hinweis berechnet wird.

## Abnahme

- In der Saalansicht im Bearbeitungsmodus steht am Mauszeiger „n Karten gewählt · Betrag", sobald mindestens ein
  Sitz gewählt ist, und verschwindet bei leerer Auswahl.
- In der Kartentabelle ebenso, sobald mindestens eine Zeile gewählt ist.
- Zahl und Betrag stimmen mit dem Kontextmenü (Zahl) und, bei gewählter Vorstellung, mit dem Kundenfenster (Zahl und
  Gesamtpreis) überein.
- Klicks, Strg-Klicks, Rechtsklicks, Mausrad, Aufziehen und Verschieben mit der rechten Taste wirken wie ohne den
  Hinweis.
- Bei offenem Sitz-Dialog, Kontextmenü, Auswahlliste oder modalem Dialog, mit dem Zeiger außerhalb des
  Inhaltsbereichs und bei Fenster ohne Fokus ist er nicht zu sehen; Tooltips der Sitze blenden ihn nicht aus.
- Am rechten und oberen Rand bleibt er vollständig sichtbar; Tooltips der Sitze verdecken ihn nicht.
- Im Anzeigemodus der Saalansicht ist keine Auswahl gezeichnet, auch nicht nach dem Wechsel aus der Tabelle mit
  gewählten Zeilen.
- „Auswahl am Mauszeiger" im Menü „Anzeige" schaltet ihn sofort aus und an; mit „Anzeige beim Start
  wiederherstellen" gilt die Wahl beim nächsten Start wieder, ohne gilt „an".
- Eine `settings.json` ohne das Feld ergibt „an".
- `./mvnw test` grün; die Tests oben sind vorhanden.
- Handtest unter Windows: Saalansicht und Tabelle, beide Modi, die Fälle der Tabelle oben; dabei ausdrücklich, dass
  der Hinweis nach dem Schließen des Sitz-Dialogs ohne weiteren Klick, spätestens bei der nächsten Mausbewegung über
  dem Inhaltsbereich, wieder erscheint (Fokus des Hauptfensters).
- Handtest unter Linux für Tooltip über Sitzen und Sitz-Dialog (Fokus), sobald ein Linux-Rechner verfügbar ist; bis
  dahin im Pull Request als offen vermerkt.
- Nach dem Schließen der Verkaufsansicht hält keine Instanz von `SelectionHint` oder `TicketShellController` über
  `DisplayPreferences` oder `Window.getWindows()` fest (am Code der Anmeldungen nachvollziehbar).

## Prüfvermerk

### Runde 1, 18.11.2026

- Umsetzer: 0 kritisch, 1 groß, 4 klein. Gegenprüfer: 0 kritisch, 1 groß, 5 klein.
- Behoben (groß): Die Saalansicht hatte nach dem Wechsel aus der Tabelle auch im Anzeigemodus eine Auswahl
  (`restoreSelection` ohne Modusprüfung). Neu: Abschnitt „Saalansicht im Anzeigemodus". Der Schalter wird nur
  schwach beobachtet (`LiveViews.follow`), sonst hielte `DisplayPreferences` jede Shell fest; „Lebensdauer" neu
  gefasst.
- Eingearbeitet (klein): Größe des nicht gemanagten Labels (`applyCss`/`autosize`); Grenzprüfung beim Ziehen mit
  gedrückter Taste; Text bei jeder Auswertung neu (Preisänderung von anderem Rechner); Erzeugung vor
  `showTicketTableView()`; Platz rechts über statt rechts unter dem Zeiger wegen der Tooltips der Sitze; Handtest zum
  Fokus nach dem Sitz-Dialog; Patch-Regel ausdrücklich als Auslegung gekennzeichnet; den Satz zum Fensterwechsel
  gestrichen (das Hauptfenster ist das beim Anmelden, ein Wechsel kommt in der App nicht vor).
- Nicht belegt: Fokus- und `MOUSE_EXITED`-Verhalten von JavaFX bei Popups unter Windows und Linux; ob sich
  `PopupWindow`/`Tooltip` ohne laufendes Toolkit für einen Test erzeugen lassen. Beides deckt der Handtest ab.

### Runde 2, 18.11.2026

- Umsetzer: 0 kritisch, 0 groß, 4 klein. Gegenprüfer: 0 kritisch, 0 groß, 5 klein. Damit ist die Prüfung beendet.
- Eingearbeitet (klein): am oberen Rand links unter statt rechts unter dem Zeiger (Tooltip); Handtest „spätestens
  bei der nächsten Mausbewegung"; Folge für das Kundenfenster und das Javadoc von `showSelectionMenu` (KARV-59) im
  Abschnitt „Saalansicht im Anzeigemodus" und im Nicht-Ziel; Auswertung beim Anmelden; Zeilenangaben zur Reihenfolge
  in der Shell berichtigt; „wie heute nach einem Moduswechsel" auf die Saalansicht eingeschränkt; Linux-Handtest
  aufgenommen.
- Nicht belegt: wie oben; außerdem, ob eine fremde Preisänderung dieselben `Booking`-Objekte ändert oder ersetzt. In
  beiden Fällen rechnet der Hinweis bei der nächsten Mausbewegung mit der aktuellen Auswahl.
