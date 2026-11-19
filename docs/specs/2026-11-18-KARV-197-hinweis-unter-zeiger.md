# KARV-197: Hinweis zur Auswahl wahlweise unter dem Mauszeiger

Stand: Entscheidung vom 18.11.2026. Ein Spec ist eine Momentaufnahme und wird nach dem Merge nicht gepflegt; was
dauerhaft gilt, steht in `docs/decisions.md` und der übrigen Dokumentation.

## Anlass und Ziel

KARV-194 (in 1.1.1, noch nicht veröffentlicht) zeigt in der Verkaufsansicht neben dem Mauszeiger „n Karten gewählt ·
Betrag". Der Hinweis steht fest rechts über dem Zeiger, weil rechts darunter der Tooltip eines Sitzes erscheint. Der
Wunsch aus dem Ticket: im Menü „Anzeige" wählen können, ob der Hinweis oben (wie heute) oder unten steht.

Ziel: Der Haken „Auswahl am Mauszeiger" im Menü „Anzeige" wird zu einem gleichnamigen Untermenü mit *Aus*, *Über dem
Zeiger* und *Unter dem Zeiger*; die Wahl wird gespeichert wie die übrigen Einträge des Menüs. Der Tooltip der Sitze in
der Saalansicht der Verkaufsansicht entfällt, damit er den Hinweis unter dem Zeiger nicht verdeckt.

Nachtrag (Entwickler, 18.11.2026, nach der ersten Umsetzung): Im selben Untermenü kommt der Haken *Am Rand ausweichen*
dazu (Standard an). Ist er aus, springt der Hinweis am Rand nicht auf die andere Seite des Zeigers, sondern behält
seine Seite und wird nur so weit verschoben, dass er im Inhaltsbereich bleibt (Abschnitt „Nachtrag: Am Rand
ausweichen").

## Ziele, Nicht-Ziele, Anti-Ziele

**Ziele:**

- Untermenü „Auswahl am Mauszeiger" mit drei Einträgen, von denen genau einer gewählt ist.
- „Über dem Zeiger" ist der Standard und verhält sich wie heute, bis auf den oberen Rand (siehe Entwurf, „Platz").
- „Unter dem Zeiger" stellt den Hinweis rechts unter den Zeiger, mit Ausweichen an den Rändern.
- Die Wahl gilt sofort und, mit „Anzeige beim Start wiederherstellen", auch beim nächsten Start.
- Haken „Am Rand ausweichen" im Untermenü, Standard an, gespeichert und wiederhergestellt wie die Lage.
- Der Sitz-Tooltip „Tisch A4, Sitz 3 (Verkauft)" der Saalansicht entfällt.

**Nicht-Ziele:**

- Keine weiteren Lagen (links, mittig, frei wählbarer Abstand).
- Hallen-Editor, Kundenfenster und Abrechnung bleiben unverändert.
- Kein Ersatz für die Angaben des entfallenden Tooltips: Wer Tische und Sitznummern ausgeblendet hat, sieht den Platz
  beim Überfahren nicht mehr, sondern erst im Sitz-Dialog. Der Status steht dann nur noch in Farbe und, mit
  „Barrierefreie Sitzdarstellung", im Muster; mit ausgeschalteter barrierefreier Darstellung allein in der Farbe. Vom
  Entwickler so entschieden (18.11.2026).
- Text, Sichtbarkeitsregeln und Lebensdauer des Hinweises aus KARV-194 bleiben, wie sie sind.

**Anti-Ziele:**

- Der Hinweis verdeckt die Spitze des Mauszeigers (der Punkt, auf den ein Klick wirkt). Ausnahme mit Absicht: Bei
  ausgeschaltetem „Am Rand ausweichen" in der Ecke des Bereichs (siehe Nachtrag); Klicks gehen weiter durch, weil der
  Hinweis mausdurchlässig ist.
- „Aus" und eine Lage widersprechen sich, z. B. ist nach dem Wiedereinschalten eine andere Lage gewählt als vor dem
  Ausschalten, ohne dass jemand sie geändert hat.
- Eine `settings.json` aus 1.1.0 oder aus einer neueren Version mit unbekannter Lage macht die Einstellungen unlesbar
  oder schaltet den Hinweis ab.

## Randbedingungen

- Patch-Version 1.1.1 auf der Wartungslinie `release/1.1.0`. Die neue Wahl speichert ein Feld in `settings.json`.
  Wie bei KARV-194 gilt die Auslegung von `CONTRIBUTING.md`, „Versionen": Die Regel gegen neue Felder in Patch-Versionen
  begründet sich mit dem gemeinsamen Umstieg aller Rechner eines Ordners; `settings.json` ist lokal je Rechner
  (`<AppData>/settings.json`, `docs/structure.md:102`) und verlangt keinen gemeinsamen Umstieg. Der Maintainer
  bestätigt das mit der Freigabe.
- Abwärts: Fehlt das neue Feld (Datei aus 1.1.0 oder aus einem Testbuild von 1.1.1), gilt „Über dem Zeiger".
  Vorwärts: 1.1.0 ignoriert das Feld (`@JsonIgnoreProperties(ignoreUnknown = true)`); schreibt 1.1.0 danach, fällt
  es weg und gilt wieder „Über dem Zeiger". Das ist hinnehmbar.
- Der Sitz-Tooltip steckt schon in der veröffentlichten 1.1.0. Die Patch-Version entfernt also veröffentlichtes
  Verhalten; `CONTRIBUTING.md` nennt für Patch-Versionen nur „Fehlerbehebungen und kleine Ergänzungen". Der Maintainer
  hat das Entfernen entschieden (18.11.2026) und bestätigt es mit der Freigabe; die Patchnotes nennen es.
- Das vorhandene Feld `selectionHint` (an/aus) behält Name, Typ und Bedeutung (Weg A, vom Entwickler gewählt).
- Arbeits-Branch `feature/KARV-197-hinweis-unter-zeiger` von `origin/release/1.1.0`, Pull Request dorthin (Squash).
- Texte deutsch, Beschreibung unter `docs/` englisch. Nur JavaFX-Thread; Windows und Linux gleich.

## Ausgangslage

Geprüft am 18.11.2026 auf `origin/release/1.1.0` (Commit `624cce2`).

- **Menü „Anzeige".** `TicketShellController.initialize` hängt nach den Beschriftungen
  `PlanLabelsMenu.item("Barrierefreie Sitzdarstellung", …)` und `PlanLabelsMenu.item("Auswahl am Mauszeiger",
  state.selectionHintProperty())` an (`ui/sales/TicketShellController.java:119-120`), danach
  `SummaryPlacementMenu.append(menuDisplay, state.summaryPlacementProperty())` (`:121`) und erzeugt den
  `SelectionHint` (`:123`). `PlanLabelsMenu.item` liefert ein `CheckMenuItem` (`ui/hall/PlanLabelsMenu.java:22`).
- **Vorbild Untermenü.** `SummaryPlacementMenu.append` (`ui/sales/SummaryPlacementMenu.java:22-44`) baut ein `Menu`
  mit je einem `RadioMenuItem` in einer `ToggleGroup`, setzt die Property bei Auswahl und folgt Änderungen von außen
  über einen schwach angemeldeten Listener, den das Untermenü in seinen `getProperties()` hält.
- **Hinweis.** `SelectionHint` (`ui/sales/SelectionHint.java`) bekommt im Konstruktor `contentArea`, die Auswahl und
  den Schalter als `ObservableBooleanValue` (`:75`) und folgt dem Schalter schwach über `LiveViews.follow(area,
  enabled, this::update)` (`:95`; Signatur `follow(Node, ObservableValue<?>, Runnable)`, `ui/LiveViews.java:66`).
  `place(pointerX, pointerY, width, height, areaWidth, areaHeight)` (`:129-140`) rechnet die linke obere Ecke: rechts
  über dem Zeiger (linke Kante 16 px rechts, `GAP_RIGHT`; untere Kante 8 px darüber, `GAP_ABOVE`); ragt er rechts
  hinaus, links (rechte Kante 8 px links, `GAP_LEFT`); ragt er oben hinaus, **links** unter dem Zeiger (obere Kante
  20 px darunter, `GAP_BELOW`), ausdrücklich wegen des Sitz-Tooltips (`:125-127`); danach auf den Bereich begrenzt
  (`clamp`, `:143-145`). `blocks` (`:152-154`) lässt Tooltips nicht als blockierendes Popup gelten, mit der Begründung
  „sonst verschwände der Hinweis beim Überfahren der Sitze".
- **Sitz-Tooltip.** Einziger Tooltip in der Saalansicht der Verkaufsansicht:
  `Tooltip.install(seatNode, new Tooltip(booking.getPlaceLabel() + " (" + status.getDisplayName() + ")"))` in
  `HallOverviewController.styleSeatNode` (`ui/sales/HallOverviewController.java:388`). Das Javadoc bei `:422` nennt
  „Farbe, Tooltip etc." als Folge des Neuzeichnens. In der Kartentabelle gibt es keinen Tooltip (Suche nach `Tooltip`
  in `TicketTableController` und den FXML-Dateien ohne Treffer). Weitere Tooltips der Verkaufsansicht liegen außerhalb
  von `contentArea` (Trefferzahl, `TicketShellController.java:224-226`) oder in Popups, die ohnehin blockieren
  (`SeatDetailPopup.java:173`, `:260`).
- **Speicherung.** `DisplaySettings` (`settings/DisplaySettings.java`) hat `selectionHint` (Standard `true`, `:25`)
  und `summaryPlacement` als Text (Standard `"AUTO"`, `:17`, `:26`); die Klasse ignoriert unbekannte Felder (`:13`).
  `DisplayPreferences` (`ui/DisplayPreferences.java`) hält `selectionHint` als `BooleanProperty` (`:39`) und die
  Platzierung mit einem `TolerantEnum` (`:43-44`), das einen unbekannten Namen als Rückfallwert liest und unverändert
  zurückschreibt (`model/TolerantEnum.java:47-72`); übernimmt beim Start nur mit `restoreAtStart` (`:54-62`), hängt
  danach Listener auf `write()` an (`:64-67`) und schreibt in `write()` alle Felder (`:112-133`).
  `SalesViewState.selectionHintProperty()` reicht durch (`ui/sales/SalesViewState.java:80-82`).
- **Texte.** `DisplaySection.RESTORE_HINT` nennt „Auswahl am Mauszeiger" (`ui/settings/DisplaySection.java:13-16`).
  Patchnotes 1.1.1, „Neu", erster Punkt: „… lässt sich das unter „Auswahl am Mauszeiger" abschalten"
  (`src/main/resources/de/eltviller_carneval_verein/karten/patchnotes.md:6-8`).
- **Tests.** `SelectionHintTest` prüft `place` (`:82-125`), darunter `movesLeftBelowThePointerAtTheTopEdge` und
  `topRightCornerUsesLeftBelow`, die das Ausweichen nach links unten festschreiben. `SelectionHintPopupTest` prüft
  `blocks` mit einem Tooltip „Tisch 1, Sitz 1 (Frei)". `DisplayPreferencesTest` und `DisplaySettingsTest` prüfen
  `selectionHint`. `HallOverviewController` hat keine Tests.
- **Doku.** `docs/usage.md:91-95` und `:116-117` (Haken „Auswahl am Mauszeiger"), `docs/architecture.md:211`,
  `:271`, `:275-279` (Lage „left below it at the top edge (seat tooltips appear right below)"),
  `docs/decisions.md:700`, `docs/structure.md:102`.

## Betrachtete Wege

Menü (vom Entwickler gewählt, 18.11.2026): ein Untermenü mit drei Einträgen statt Haken plus eigenem Untermenü für die
Lage. Eine Stelle für eine Sache, gebaut wie „Kaufzusammenfassung".

Sitz-Tooltip (vom Entwickler gewählt, 18.11.2026): entfernen. Verworfen wurden „behalten und unten heißt links unter
dem Zeiger" (kleinster Eingriff, aber ungewohnte Lage) und „Platzangabe in den Hinweis übernehmen" (eigenes neues
Verhalten, zu groß für diese Patch-Version).

Speicherung:

- **A: zweites Feld** (gewählt vom Entwickler am 18.11.2026). `selectionHint` bleibt an/aus, dazu kommt das Textfeld
  `selectionHintPlacement` (`ABOVE`/`BELOW`), tolerant gelesen wie `summaryPlacement`. Das vorhandene Feld behält seine
  Bedeutung, und die Lage bleibt erhalten, während der Hinweis aus ist.
- **B: ein Feld mit drei Werten.** `selectionHint` wird Text (`OFF`/`ABOVE`/`BELOW`). Ein Feld weniger, aber der Typ
  ändert sich, und `true`/`false` aus Testbuilds müssten eigens abgefangen werden. Die Lage ginge beim Ausschalten
  verloren.

## Entwurf

### Neue Aufzählung `ui.sales.SelectionHintPlacement`

`ABOVE("Über dem Zeiger")`, `BELOW("Unter dem Zeiger")`, mit `label()` wie `SummaryPlacement`. In dieser Reihenfolge.

### `DisplaySettings`

- Neues Feld `selectionHintPlacement` als Text, Konstante `DEFAULT_SELECTION_HINT_PLACEMENT = "ABOVE"`, Getter und
  Setter; der Setter macht aus `null` den Standard (wie `setSummaryPlacement`). Fehlt das Feld in der Datei, gilt der
  Standard. Javadoc der Klasse nennt das Feld.
- `selectionHint` bleibt unverändert.

### `DisplayPreferences`

- Neue `ObjectProperty<SelectionHintPlacement> selectionHintPlacement`, Standard `ABOVE`, mit einem eigenen
  `TolerantEnum` (Rückfallwert `ABOVE`) wie die Platzierung der Kaufzusammenfassung: ein unbekannter Name gilt als
  `ABOVE` und wird unverändert zurückgeschrieben, bis jemand eine andere Lage als `ABOVE` wählt (genau das Verhalten
  von `TolerantEnum.set`).
- Beim Start mit `restoreAtStart` übernommen, ohne gilt `ABOVE`; danach Listener auf `write()`; `write()` schreibt
  das Feld mit, `null` als `ABOVE`.
- Getter `selectionHintPlacementProperty()`.

### `SalesViewState`

- `selectionHintPlacementProperty()` reicht durch, wie `selectionHintProperty()`.

### Menü: neue Klasse `ui.sales.SelectionHintMenu`

Baut das Untermenü „Auswahl am Mauszeiger" mit drei `RadioMenuItem`s in einer `ToggleGroup`, in dieser Reihenfolge:
„Aus", „Über dem Zeiger", „Unter dem Zeiger" (Texte aus `SelectionHintPlacement.label()`).

- Gewählt ist „Aus", wenn `selectionHint` aus ist, sonst der Eintrag der Lage.
- Wählt jemand „Aus": `selectionHint` = `false`, die Lage bleibt.
- Wählt jemand eine Lage: Lage setzen und `selectionHint` = `true`.
- Ändert sich eine der beiden Properties von außen, folgt die Auswahl im Menü; beide Listener schwach angemeldet und
  vom Untermenü gehalten, wie in `SummaryPlacementMenu`.
- Gegen Rückkopplung: Während das Menü selbst die Properties setzt, tun seine beiden Folge-Listener nichts (eine
  Sperre); danach steht die Auswahl ohnehin richtig. Sonst wählte z. B. beim Wechsel von „Aus" auf „Unter" der
  Listener der Lage kurz wieder „Aus", weil `selectionHint` noch `false` ist. (Umgesetzt nach `/simplify` ohne Sperre:
  Das Menü folgt einem aus beiden Properties abgeleiteten Wert, der sich beim Wechsel erst mit dem Schalter ändert.)
- Zwei Schreibvorgänge beim Wechsel von „Aus" auf eine andere Lage als die gespeicherte sind in Ordnung.

`TicketShellController.initialize`: Statt `PlanLabelsMenu.item("Auswahl am Mauszeiger", …)` steht nach
„Barrierefreie Sitzdarstellung" das neue Untermenü. Die übrige Reihenfolge des Menüs bleibt (danach Trenner und
„Kaufzusammenfassung").

### `SelectionHint`

- Der Konstruktor bekommt zusätzlich die Lage als `ObservableValue<SelectionHintPlacement>` und folgt ihr schwach über
  `LiveViews.follow(area, placement, this::update)` wie dem Schalter. `TicketShellController` übergibt
  `state.selectionHintPlacementProperty()`.
- `update()` gibt die aktuelle Lage an `place` weiter; `null` gilt als `ABOVE`.
- `blocks` bleibt unverändert: Tooltips blockieren weiterhin nicht. Nach dem Wegfall des Sitz-Tooltips trifft das in
  `contentArea` keinen Tooltip mehr, schadet aber nicht, und ein Tooltip außerhalb (Trefferzahl), der beim Hineinfahren
  noch kurz offen ist, blendet den Hinweis nicht aus. Javadoc und Kommentar sprechen von Tooltips allgemein statt von
  denen der Sitze.

### Platz

`place` bekommt die Lage als zusätzlichen Parameter und rechnet so (Koordinaten von `contentArea`, Abstände wie heute:
`GAP_RIGHT` 16, `GAP_LEFT` 8, `GAP_ABOVE` 8, `GAP_BELOW` 20):

1. **Waagerecht:** linke Kante `GAP_RIGHT` rechts vom Zeiger; ragte der Hinweis damit rechts aus dem Bereich
   (`x + width > areaWidth`), rechte Kante `GAP_LEFT` links vom Zeiger. Für beide Lagen gleich.
2. **Senkrecht, „Über dem Zeiger":** untere Kante `GAP_ABOVE` über dem Zeiger; ragte er damit oben hinaus (`y < 0`),
   obere Kante `GAP_BELOW` unter dem Zeiger.
3. **Senkrecht, „Unter dem Zeiger":** obere Kante `GAP_BELOW` unter dem Zeiger; ragte er damit unten hinaus
   (`y + height > areaHeight`), untere Kante `GAP_ABOVE` über dem Zeiger.
4. Danach wie heute auf den Bereich begrenzen (`clamp`): nie links oder oben außerhalb; ist der Bereich kleiner als
   der Hinweis, beginnt er am linken bzw. oberen Rand.

Waagerechte und senkrechte Wahl sind unabhängig. **Verhaltensänderung gegenüber KARV-194:** Bei „Über dem Zeiger" am
oberen Rand steht der Hinweis künftig **rechts** unter dem Zeiger (bisher immer links darunter, wegen des Tooltips),
links nur, wenn er rechts nicht passt. 1.1.1 ist nicht veröffentlicht; die Patchnotes nennen das nicht eigens.

Beispiele (Hinweis 120 × 24, Bereich 1000 × 800):

| Zeiger | Lage | linke obere Ecke |
|---|---|---|
| (200, 300) | über | (216, 268) |
| (200, 300) | unter | (216, 320) |
| (950, 300) | unter | (822, 320) |
| (200, 10) | über | (216, 30) |
| (200, 790) | unter | (216, 758) |
| (990, 790) | unter | (862, 758) |

### Sitz-Tooltip entfernen

- `HallOverviewController.styleSeatNode`: Die Zeile `Tooltip.install(seatNode, …)` entfällt, der Import von `Tooltip`
  ebenso, falls er danach ungenutzt ist. Das Javadoc bei `:422` nennt den Tooltip nicht mehr.
- Weitere Stellen, die den Sitz-Tooltip nennen, werden angepasst: `HallPlanPane.java:63` (Javadoc), `style.css:955-956`
  (Kommentar zur Stilklasse `selection-hint`), das Klassen-Javadoc von `SelectionHint` (`:30-31`) und das Javadoc von
  `place` (`:124-127`) mit der neuen Regel, das Klassen-Javadoc von `SelectionHintPopupTest` (`:21`) und der Kommentar
  in `SelectionHintTest.java:98`.
- Der in KARV-194 offene Linux-Handtest „Tooltip über Sitzen" ist damit hinfällig; der Pull Request sagt das.
- Weil `styleSeatNode` bei jedem Neuzeichnen für neue Knoten läuft, braucht es kein `Tooltip.uninstall`.
- Hallen-Editor und Kundenfenster sind nicht betroffen (Nicht-Ziel).

### Texte

- `DisplaySection.RESTORE_HINT`: „Auswahl am Mauszeiger" bleibt als Name in der Aufzählung; kein neuer Text nötig.
- Patchnotes 1.1.1, „Neu", erster Punkt (vorläufig): statt „lässt sich das unter „Auswahl am Mauszeiger" abschalten"
  sinngemäß: „Im Menü „Anzeige“ unter „Auswahl am Mauszeiger“ lässt sich wählen, ob der Hinweis über oder unter dem
  Mauszeiger steht, oder ihn abschalten." Dazu ein Satz, dass der Tooltip mit Platz und Status über den Sitzen der
  Saalansicht entfällt. Endgültiger Text erst vor dem Pull Request nach `main`.

### Verhalten in Fällen

| Fall | Ergebnis |
|---|---|
| Neue Installation oder `settings.json` ohne `selectionHintPlacement` | „Über dem Zeiger" gewählt, Hinweis wie heute |
| `selectionHint` = `false` in der Datei | „Aus" gewählt; die gespeicherte Lage bleibt in der Datei |
| „Unter dem Zeiger" gewählt | Das Menü „Anzeige" liegt in der Fußleiste außerhalb des Inhaltsbereichs: Der Hinweis erscheint rechts unter dem Zeiger, sobald der Zeiger wieder über dem Inhaltsbereich ist (und das Menü zu) |
| „Aus", danach „Unter dem Zeiger" | Hinweis an, unter dem Zeiger |
| „Unter", dann „Aus", Neustart mit Wiederherstellen | nach dem Start „Aus" gewählt; in der Datei steht weiter `BELOW`, bis jemand eine Lage wählt |
| Ohne „Anzeige beim Start wiederherstellen" | jeder Start: an, „Über dem Zeiger" |
| Unbekannte Lage aus neuerer Version (z. B. `"LEFT"`) | gilt als „Über dem Zeiger"; wird zurückgeschrieben, bis jemand „Unter dem Zeiger" wählt |
| Zeiger über einem Sitz der Saalansicht | kein Tooltip mehr; Hinweis an der gewählten Lage |
| Zeiger am unteren Rand, „Unter" | Hinweis über dem Zeiger |
| Zeiger am oberen Rand, „Über" | Hinweis unter dem Zeiger, rechts davon (links nur am rechten Rand) |

### Tests

- `SelectionHintTest`: `place` für beide Lagen mit den Beispielen der Tabelle oben; die bisherigen Fälle für
  „Über dem Zeiger" bleiben, soweit die neue Regel sie nicht ändert. Angepasst werden
  `movesLeftBelowThePointerAtTheTopEdge` und `topRightCornerUsesLeftBelow` (rechts unter, links unter nur am rechten
  Rand) sowie `neverLeavesTheAreaAtTheBottom` (erwartet künftig (216, 16) statt (72, 16)); Begrenzung bei zu kleinem
  Bereich für beide Lagen.
- `SelectionHintPopupTest`: Kommentar/Text des Tooltips allgemein; Ergebnis unverändert.
- `DisplaySettingsTest`: fehlendes Feld ergibt `"ABOVE"`; `null` im Setter ergibt `"ABOVE"`; Hin- und Rückweg über
  JSON.
- `DisplayPreferencesTest`: Übernahme mit und ohne `restoreAtStart`; eine Änderung der Lage schreibt das Feld und die
  übrige Auswahl; unbekannte Lage gilt als `ABOVE` und wird zurückgeschrieben, bis eine andere gewählt wird.
- `SelectionHintMenu`: Zuordnung Properties ↔ gewählter Eintrag in beiden Richtungen (Menü setzt Properties; Änderung
  der Properties wählt den Eintrag), sofern sich `RadioMenuItem` ohne laufendes Fenster testen lässt (Toolkit wie in
  `SelectionHintPopupTest`); sonst Handtest.
- Handtest unter Windows: Saalansicht und Tabelle, alle drei Einträge, die vier Ränder bei „Unter"; kein Sitz-Tooltip
  mehr; nach Neustart mit Wiederherstellen ist die Wahl erhalten. Dabei bei „Unter" ansehen, ob der Pfeil des
  Mauszeigers den Anfang des Texts verdeckt (normale Zeigergröße). Mit vergrößertem Mauszeiger (Barrierefreiheit,
  HiDPI) kann er das, weil die Abstände fest sind; das wird hingenommen, dafür gibt es „Über dem Zeiger".

### Nachtrag: Am Rand ausweichen

Vom Entwickler gewünscht am 18.11.2026, nach der ersten Umsetzung. Gewählt: „Seite halten, am Rand stoppen" (statt
„wirklich über den Rand ragen", bei dem die Fußleiste den Hinweis verdeckt, der Fensterrand ihn abschneidet und er
oben über der Kopfleiste läge); Standard an.

**Stand vor dem Nachtrag** (Arbeitskopie des Branches, 18.11.2026): `SelectionHint.place(placement, pointerX,
pointerY, width, height, areaWidth, areaHeight)` weicht immer aus (Schritte 1 bis 3 unter „Platz") und begrenzt
danach (`clamp`). `SelectionHintMenu.create(enabled, placement)` baut die drei Radio-Einträge über `RadioMenus` und
folgt einem `ObjectBinding` aus beiden Properties. `DisplayPreferences` hält `selectionHint` und
`selectionHintPlacement`, `DisplaySettings` die Felder `selectionHint` und `selectionHintPlacement`.
`PlanLabelsMenu.item(text, BooleanProperty)` (`ui/hall/PlanLabelsMenu.java:22-26`) liefert einen Haken, der per
`bindBidirectional` an die Property gebunden ist; JavaFX hält beide Seiten einer solchen Bindung nur schwach.

**Verhalten.** Neuer Parameter `avoidEdges` für `place`:

- an (Standard): wie unter „Platz", Schritte 1 bis 4.
- aus: Schritte 1 bis 3 entfallen. Die Ecke ist immer linke Kante `GAP_RIGHT` rechts vom Zeiger und, je nach Lage,
  untere Kante `GAP_ABOVE` über bzw. obere Kante `GAP_BELOW` unter dem Zeiger; danach Schritt 4 (`clamp`). Am rechten
  Rand rückt der Hinweis damit nach links bis an den Rand, am oberen bzw. unteren Rand nach unten bzw. oben bis an den
  Rand, er wechselt aber nie die Seite. In der rechten Ecke auf seiner Seite (bei „Über" rechts oben, bei „Unter"
  rechts unten; ein Feld etwa so breit wie der Hinweis und so hoch wie Hinweis plus Abstand) überdeckt er so die
  Spitze des Zeigers (Ausnahme zum Anti-Ziel, vom Entwickler hingenommen).

Beispiele mit ausgeschaltetem Ausweichen (Hinweis 120 × 24, Bereich 1000 × 800):

| Zeiger | Lage | linke obere Ecke |
|---|---|---|
| (200, 300) | über | (216, 268) |
| (950, 300) | über | (880, 268) |
| (200, 10) | über | (216, 0) |
| (200, 790) | unter | (216, 776) |
| (990, 790) | unter | (880, 776) |

**Speicherung.** `DisplaySettings`: neues Feld `selectionHintAvoidEdges` (`boolean`, Standard `true`); fehlt es,
gilt `true`. `DisplayPreferences`: `BooleanProperty selectionHintAvoidEdges` (Standard `true`), beim Start mit
`restoreAtStart` übernommen, ohne gilt `true`; Listener auf `write()`, `write()` schreibt das Feld mit.
`SalesViewState.selectionHintAvoidEdgesProperty()` reicht durch. Randbedingungen zu Patch-Version und
Abwärts-/Vorwärtsrichtung wie für `selectionHintPlacement` (lokale Datei; fehlt das Feld, gilt an; 1.1.0 ignoriert es).

**Menü.** Im Untermenü „Auswahl am Mauszeiger" nach den drei Radio-Einträgen ein `SeparatorMenuItem` und der Haken
„Am Rand ausweichen" (`PlanLabelsMenu.item("Am Rand ausweichen", …)` oder gleichwertig mit `bindBidirectional`). Er
bleibt auch bei „Aus" bedienbar und ändert dann nur die gespeicherte Einstellung; an Schalter und Lage ändert er nichts.

**Hinweis.** `SelectionHint` bekommt `avoidEdges` als `ObservableBooleanValue`, folgt ihm schwach über
`LiveViews.follow` wie Schalter und Lage und gibt den Wert an `place` weiter. `TicketShellController` übergibt
`state.selectionHintAvoidEdgesProperty()` an `SelectionHint` und an das Untermenü.

**Texte und Doku.** `DisplaySection.RESTORE_HINT` bleibt (der Haken gehört zu „Auswahl am Mauszeiger").
`docs/usage.md` nennt den Haken; `docs/architecture.md`, `docs/structure.md` und `docs/decisions.md` nennen das Feld
bzw. die Einstellung neben der Lage. Wo `docs/usage.md` („near an edge it moves to the other side") und
`docs/architecture.md` (Ausweichen am rechten, oberen und unteren Rand) das Ausweichen als feste Regel beschreiben,
wird es an den Haken geknüpft, mit dem Verhalten ohne ihn. Patchnotes (vorläufig): ein Halbsatz, dass sich das Ausweichen am Rand
abschalten lässt.

**Fälle.**

| Fall | Ergebnis |
|---|---|
| `settings.json` ohne `selectionHintAvoidEdges` | Haken an, Verhalten wie unter „Platz" |
| Haken aus, Zeiger am rechten Rand, „Über" | Hinweis über dem Zeiger, rechte Kante am Rand des Bereichs, rückt über den Zeiger |
| Haken aus, Zeiger am unteren Rand, „Unter" | Hinweis bleibt unter dem Zeiger, untere Kante am Rand des Bereichs |
| Haken aus, „Aus" gewählt | kein Hinweis; der Haken wird gespeichert und gilt beim Wiedereinschalten |
| Ohne „Anzeige beim Start wiederherstellen" | jeder Start: Haken an |

**Tests.** `SelectionHintTest`: `place` mit ausgeschaltetem Ausweichen für die Beispiele oben; die bisherigen Fälle mit
eingeschaltetem. `DisplaySettingsTest`: fehlendes Feld ergibt `true`, Hin- und Rückweg. `DisplayPreferencesTest`:
Übernahme mit und ohne `restoreAtStart`, eine Änderung schreibt das Feld. `SelectionHintMenuTest`: Haken vorhanden,
an die Property gebunden (beide Richtungen), ändert Radio-Auswahl nicht; die vorhandenen Tests und ihre Helfer
(`offersOffAboveAndBelowInThisOrder`, `chosen`, `entry`) rechnen mit Trenner und Haken statt nur mit Radio-Einträgen,
und `withMenu` ruft `create` mit der neuen Property auf. Handtest: Haken aus, rechter, unterer und
oberer Rand, Ecke; nach Neustart erhalten.

**Abnahme.** Das Untermenü hat nach einem Trenner den Haken „Am Rand ausweichen", standardmäßig an. Ist er aus,
wechselt der Hinweis am Rand nie von über nach unter dem Zeiger oder umgekehrt, springt nicht auf die linke Seite und
bleibt vollständig im Inhaltsbereich (nah am Rand rückt er dabei bis neben oder auf den Zeiger); ist er an, verhält er
sich wie unter „Platz". Die Wahl gilt sofort und mit „Anzeige beim Start wiederherstellen" auch nach dem Neustart;
eine `settings.json` ohne das Feld ergibt „an".

### Dokumentation

- `docs/usage.md`: Untermenü statt Haken (`:94`, `:117`), Lage über oder unter dem Zeiger.
- `docs/architecture.md:275-279`: Lage nach Wahl, Ausweichen an den Rändern, kein Verweis mehr auf Sitz-Tooltips;
  `:211` und `:271` um das neue Feld bzw. die Lage ergänzen.
- `docs/decisions.md:700` und `docs/structure.md:102`: Aufzählung der Felder bzw. Einträge um die Lage ergänzen.
- Patchnotes wie unter „Texte".

## Der Umsetzung überlassen

- Ob `SelectionHintMenu` eine eigene Klasse ist oder `SummaryPlacementMenu` zu einem gemeinsamen Baustein für
  Untermenüs mit Auswahl verallgemeinert wird.
- Genaue Signatur von `place` (z. B. Lage als `boolean below` oder als Aufzählung).
- Ob der Wechsel von „Aus" auf eine Lage einmal oder zweimal schreibt.

## Abnahme

- Das Menü „Anzeige" der Verkaufsansicht hat statt des Hakens das Untermenü „Auswahl am Mauszeiger" mit „Aus",
  „Über dem Zeiger", „Unter dem Zeiger"; genau einer dieser drei Einträge ist gewählt (dazu der Haken aus dem
  Nachtrag).
- „Aus" blendet den Hinweis aus, „Über" und „Unter" zeigen ihn an der gewählten Lage, sobald der Zeiger nach dem
  Schließen des Menüs wieder über dem Inhaltsbereich ist; ein Neustart oder Ansichtswechsel ist nicht nötig.
- Bei „Unter dem Zeiger" steht der Hinweis rechts unter dem Zeiger; mit eingeschaltetem „Am Rand ausweichen" am
  rechten Rand links davon, am unteren Rand darüber; er bleibt in allen vier Ecken vollständig im Inhaltsbereich und
  verdeckt die Spitze des Zeigers nicht.
- Bei „Über dem Zeiger" steht er wie bisher rechts darüber; mit eingeschaltetem „Am Rand ausweichen" am oberen Rand
  rechts darunter (links nur am rechten Rand).
- Über den Sitzen der Saalansicht der Verkaufsansicht erscheint kein Tooltip mehr; im Hallen-Editor ändert sich nichts.
- Mit „Anzeige beim Start wiederherstellen" gelten An/Aus und Lage beim nächsten Start wieder; ohne gilt „Über dem
  Zeiger". Nach „Aus" und Wiedereinschalten über „Über dem Zeiger" oder „Unter dem Zeiger" gilt die gewählte Lage.
- Eine `settings.json` ohne `selectionHintPlacement` ergibt „Über dem Zeiger"; eine unbekannte Lage ebenso und bleibt
  in der Datei, bis eine andere gewählt wird.
- `./mvnw test` grün; die Tests oben sind vorhanden.
- Handtest unter Windows wie unter „Tests"; Linux wie bei KARV-194 im Pull Request als offen vermerkt.

## Prüfvermerk

### Runde 1, 18.11.2026

- Umsetzer: 0 kritisch, 0 groß, 4 klein. Gegenprüfer: 0 kritisch, 0 groß, 5 klein. Damit ist die Prüfung beendet.
- Eingearbeitet (klein): auch `neverLeavesTheAreaAtTheBottom` ändert sich; weitere Stellen, die den Sitz-Tooltip
  nennen (CSS, Javadoc von `SelectionHint`, `place`, `HallPlanPane`, Tests), und der hinfällige Linux-Handtest;
  Sperre gegen Rückkopplung im Menü; der Hinweis erscheint nach dem Menü erst, wenn der Zeiger wieder über dem
  Inhaltsbereich ist (Fälle und Abnahme); Anti-Ziel auf die Zeigerspitze beschränkt (der Sitz darunter ist je nach
  Zoom größer als die Abstände, wie schon bei KARV-194); Pfad der Patchnotes; „auch ihre Tooltips" gestrichen
  (Hallen-Editor und Kundenfenster haben keine); Wegfall der Statusangabe und Entfernen von veröffentlichtem Verhalten
  in einer Patch-Version als Randbedingung zur Freigabe; vergrößerter Mauszeiger bei „Unter" in den Handtest,
  hingenommen.
- Nicht belegt: wie `RadioMenuItem`s in einer `ToggleGroup` ohne Fenster im Test reagieren; die tatsächliche Größe des
  Mauszeigers gegenüber den Abständen; Verhalten unter Linux. Das deckt der Handtest ab bzw. bleibt im Pull Request
  offen.

### Nachtrag „Am Rand ausweichen"

Runde 2, 18.11.2026 (nur der Nachtrag ist neu; der Rest war geprüft, freigegeben und auf dem Branch umgesetzt):

- Umsetzer: 0 kritisch, 0 groß, 3 klein. Gegenprüfer: 0 kritisch, 0 groß, 4 klein. Damit ist die Prüfung beendet.
- Eingearbeitet (klein): Abnahme des Nachtrags genauer (nie über/unter getauscht, kein Sprung nach links, rückt nah
  am Rand bis neben oder auf den Zeiger); Haupt-Abnahme auf eingeschaltetes Ausweichen bzw. die drei Radio-Einträge
  eingeschränkt; Anpassung der vorhandenen Menü-Tests und ihrer Helfer genannt; Doku knüpft das Ausweichen an den
  Haken; die Ecke, in der die Zeigerspitze verdeckt wird, genau benannt.
- Nicht belegt: ob ein Klick auf den Haken das Menü schließt und wie Radio-Einträge und Haken im selben Untermenü
  aussehen; Verhalten unter Linux. Das deckt der Handtest ab bzw. bleibt im Pull Request offen.
