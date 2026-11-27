# Kassen am Event anlegen und verwalten (KARV-144)

Stand: Entscheidung vom 26.11.2026, vom Maintainer freigegeben am 27.11.2026. Ein Spec ist eine Momentaufnahme und
wird nach dem Merge nicht gepflegt; was dauerhaft gilt, steht in `docs/decisions.md` und der übrigen Dokumentation.

Das Spec deckt die Story KARV-144 mit ihren Sub-Tasks KARV-156 (Modell und Speicherung), KARV-157 (Übernahme des
Bestands), KARV-158 (Oberfläche) und KARV-159 (Prüfungen) ab und entscheidet KARV-153 (Mischbetrieb mit v1.1).

## Anlass und Ziel

Heute ist die Kasse kein eigenes Objekt: Der gezählte Bar-Betrag („Ist-Betrag") und der Kommentar zur Differenz sind
zwei Felder der Vorstellung. Der Verein ist damit an genau eine Kasse je Vorstellung gebunden; eine Vorverkaufskasse
für die ganze Kampagne lässt sich nicht abbilden, und die weiteren Arbeiten der Version 1.2 (Zahlung trägt die Kasse,
Kassensturz je Kasse, Kassenbewegungen, Abschluss) haben nichts, woran sie hängen können.

Ziel: Die Kasse wird ein eigenes Objekt des Events mit Namen und „gilt für". Vorgabe bleibt eine Kasse je
Vorstellung; stattdessen lässt sich eine gemeinsame Kasse für das Event wählen, weitere Kassen legt man frei an. Wer
weiter eine Kasse je Vorstellung führt, sieht dieselben Zahlen wie vorher.

## Ziele, Nicht-Ziele, Anti-Ziele

**Ziele:**

- Kassen lassen sich in der Event-Verwaltung anlegen, umbenennen, zuordnen und löschen.
- Ein Bestand aus v1.1 zeigt nach dem Update im Kassenabgleich dieselben Soll-, Ist- und Differenzwerte, ohne dass
  beim Laden etwas geschrieben wird.
- Jede Kasse ist eine eigene Einheit im Versionsspeicher: Zwei Rechner ändern verschiedene Kassen, ohne sich zu
  stören; an derselben Kasse gelten die Konfliktregeln je Feldgruppe.
- Sobald ein Event eine gespeicherte Kasse hat, sperrt es sich für Rechner mit v1.1 (SYS-009). Ausgenommen sind
  die Wege unter „Bekannte Grenzen" bei „Sperre für v1.1"; der wichtigste ist das Wiederherstellen eines gelöschten
  Events an einem Rechner mit v1.1.
- Das Wiederherstellen eines Events lässt die Kassen, wie sie sind.

**Nicht-Ziele:**

- Kein Ist-Betrag für die gemeinsame Kasse und für freie Kassen in der Oberfläche. Den Kassensturz je Kasse bringt
  KARV-146; bis dahin lässt sich nur die Kasse einer Vorstellung zählen, im bestehenden Kassenabgleich.
- Keine Kasse an der Zahlung (KARV-145) und keine Auswertung „Soll je Kasse".
- Keine Sperre des Wiederherstellens hinter einem abgeschlossenen Kassensturz. Der Grundsatz dafür steht unter
  „Für die Folgetickets vermerkt"; entschieden wird in KARV-148.
- Kein eigenes Wiederherstellen einzelner Kassen.
- Kein Weg, eine verwaiste Kasse mit Ist-Betrag zu leeren oder zu löschen. Ihr Betrag wird erst mit dem Kassensturz
  je Kasse (KARV-146) änderbar; bis dahin bleibt sie in der Liste und in der Gesamtbilanz stehen.
- Kein Kennzeichen in den Versionen, dass ein Speichervorgang ein Wiederherstellen war (eigenes Ticket, siehe
  „Für die Folgetickets vermerkt").
- Kassen werden mit „Als Vorlage verwenden" nicht kopiert.

**Anti-Ziele:**

- Ein gezählter Betrag oder ein Kommentar verschwindet, ohne dass ihn jemand von Hand geändert hat: durch
  Wiederherstellen, durch das Löschen einer Vorstellung, durch die Übernahme aus v1.1 oder durch das Löschen einer
  Kasse auf einem anderen Rechner.
- Ein Rechner mit v1.1 zeigt im Kassenabgleich einen veralteten Ist-Betrag als gültig an.
- Das Laden eines Bestands aus v1.1 schreibt Versionen oder lässt ein Event als geändert gelten.
- Eine Regel für Kassen steht im Event-Kopf und geht beim Wiederherstellen mit dem Kopf zurück.

## Randbedingungen

- Version 1.2.0 auf `release/1.2.0`. Ein Release der zweiten Stelle darf das Datenformat erweitern und verlangen,
  dass alle Rechner eines gemeinsamen Ordners zusammen umsteigen (`.github/CONTRIBUTING.md`, „Versionen").
- `docs/decisions.md`, „Forward-compatible data format": `StoreCodec.FORMAT` bleibt 1. Strenge gegenüber älteren
  Versionen entsteht nur über `requires`; `requires` wird nie ins Modell gelesen und nie in eine andere Einheit
  kopiert. Keine abgeleiteten Felder. Der Entwurf weicht in drei Punkten bewusst von den Regeln dort ab; sie stehen
  unter „Abweichungen von `docs/decisions.md`" und werden mit der Umsetzung dort nachgezogen.
- Schichtregeln aus `docs/structure.md`, „Layer rules": `store` kennt das Modell nicht; ein Inhalt kommt nur über
  `UnitMapper.readUnit` ins Modell; Logik, die ohne JavaFX testbar sein muss, liegt in schlichten Klassen.
- Geld in ganzen Cent (`docs/decisions.md`, „Money in integer cents").
- Code, Meldungen und dieses Spec deutsch; die Doku unter `docs/` (außer `specs/` und `roadmap.md`) englisch.

## Ausgangslage

Geprüft am 26.11.2026 auf `release/1.2.0` (b155b57). Pfade ohne Verzeichnis liegen unter
`src/main/java/de/eltviller_carneval_verein/karten/`.

**Kasse heute**

- `model/Presentation.java` trägt `actualCashAmount` (Cent) und `cashDifferenceComment` (Zeile 48–49); `copyFrom`
  übernimmt beide. `repository/FieldGroups.java` Zeile 31 führt sie als Gruppe `cash` der Vorstellung.
- `ui/accounting/CashReconciliationController.java`: Der Ist-Betrag und der Kommentar werden direkt an der gewählten
  Vorstellung gesetzt (Zeile 118–132). `refresh` (Zeile 170–228) summiert je Zahlungsart das Soll aus
  `BookingTotals` und den Ist-Betrag über die gezeigten Vorstellungen; die Differenz ist Ist minus Bar-Soll (Zeile
  230–235). Ohne gewählte Vorstellung („Gesamtbilanz") sind Ist-Betrag und Kommentar gesperrt (Zeile 274–278).
  Gespeichert wird das ganze Event über `EventSaver.save` (Zeile 399–407).
- `validation/PresentationValidator.java` Zeile 49–50 und 132–135 prüft den Ist-Betrag auf 0 bis 100 000 000 Cent
  und meldet `PRS-005` (`validation/PresentationIssue.java` Zeile 20–21, Vorlage `RangeCheck.TEMPLATE`). `PRS-001`
  ist dort das Muster für einen Code, der nicht mehr ausgegeben wird und reserviert bleibt (Zeile 9–13).
- `ui/event/EventTemplates.java` Zeile 41–54 und 63–73: Kopien von Vorstellung und Event übernehmen weder
  Ist-Betrag noch Kommentar; unbekannte Felder werden mitkopiert.

**Einheiten im Versionsspeicher**

- `store/UnitType.java`: `EVENT`, `PRESENTATION`, `BOOKING`, `HALL`; `hasChildren()` gilt für Event und Vorstellung.
- `store/UnitKey.java`: Record mit fünf Komponenten (Zeile 31), Kennungen nach `[A-Za-z0-9-]{1,64}` (Zeile 33). Der
  Ordner einer Buchung ohne Sitz heißt `_<bookingId>`; der Unterstrich ist als Kennung nicht erlaubt und
  kennzeichnet ihn eindeutig (Zeile 40–41, 86–88). `parent`, `relativePath` und `parse` schalten über die vier
  Arten (Zeile 133–139, 154–162, 169–196); `parse` liefert für jeden anderen Pfad `Optional.empty()`.
- Zwei Wege finden Einheiten: Der Start-Index nimmt jeden Pfad, den `parse` erkennt (`store/UnitIndex.java` Zeile
  333) und gruppiert alles außer Hallen nach Event (`groupOf`, Zeile 1187–1189). `store/VersionStore.java` zählt in
  `eventUnits` dagegen feste Ordner auf: Kopf, je Vorstellung sie selbst und ihre Buchungen (Zeile 288–297, Ordner
  `presentations` und `bookings`, Zeile 40–42). Darauf bauen Wiederherstellen
  (`repository/StoreEventRepository.java` Zeile 283 und 415–421) und der Neuaufbau des Index aus dem lokalen
  Bestand (`store/SyncService.java` Zeile 2221, über `allUnits`). Der Vollabgleich bildet seine Abschnitte selbst
  aus beiden Beständen (`store/FullSyncRun.java` Zeile 136–157, über `FullSync.childrenOfBoth` mit dem Ordner
  `presentations`): je Event ein Abschnitt für den Kopf, dann Abschnitte je Vorstellung.
- `repository/UnitMapper.java`: `decompose` (Zeile 123–159) bildet aus dem JSON des Events den Kopf (ohne
  `presentations`), je Vorstellung eine Einheit (ohne `bookings`) und je Buchung mit Daten eine Einheit; leere
  Buchungen werden nie geschrieben (Klassenbeschreibung, Zeile 44–47). `assembleEvents` (Zeile 673–765) setzt in
  drei Durchläufen nach Art zusammen; Einheiten unter einem gelöschten Kopf sind „hidden" und werden still
  übergangen. `comparable`, `readUnit` und `modelClass` schalten über die Art (Zeile 945–1004).
- `UnitMapper.changes` (Zeile 262–280): Eine Einheit, die im Vergleichsstand lebt, angezeigt war und im Objekt
  fehlt, wird als gelöscht geschrieben, wenn ihre Eltern-Einheit im Objekt steht. `repository/UnitBaseline.java`
  Zeile 251–281: Bringt ein Speichern einen gelöschten Kopf zurück, gelten alle lesbaren Einheiten darunter als
  angezeigt (`isBelow`: unter einem Kopf alles außer dem Kopf selbst) und werden gelöscht, wenn sie im Objekt
  fehlen.
- Konfliktregeln: `repository/PolicyRules.mergeFields` (Zeile 168–176) wendet „Gleich", „unlesbar" und „Löschen
  gewinnt" an und führt sonst je Feldgruppe zusammen; `EventHeadPolicy` und `PresentationPolicy` rufen es mit ihren
  Gruppen auf. Ein Feld ohne Gruppe bildet eine eigene (`repository/FieldMerge.java` Zeile 134–150), also auch ein
  `requires` im Inhalt.
- Fremde Stände: `repository/EventMerge.apply` schaltet über die Art (Zeile 322–331). `ui/LiveViews.touches` zählt
  eine Einheit für eine gezeigte Vorstellung nur, wenn sie der Kopf ist oder deren `presentationId` trägt.
  `ui/ConflictLabels.of` baut die Bezeichnung aus Event, Vorstellung und Buchung; findet es für eine Einheit, die
  nicht Halle oder Kopf ist, keine Vorstellung im Modell, ruft es `UnitKey.presentation(eventId, presentationId)`
  auf (Zeile 78–80), was ohne `presentationId` eine Ausnahme wirft.
- Konfliktvermerke: `store/ConflictNote.Kind` kennt `DOUBLE_SALE`, `FIELDS`, `DELETE_WINS` und `UNMERGED`.
  `PolicyRules.unmerged` (Zeile 66–70) lässt „Deren" gelten und vermerkt für diesen Rechner, dass „Meine" weicht.

**Vorwärtskompatibilität**

- `model/Requires.java`: `KNOWN` ist leer (Zeile 18). `repository/ContentRules.java` sperrt eine Einheit, deren
  `requires` ein unbekanntes Merkmal nennt (Zeile 44–49); `rulesId()` enthält `KNOWN` (Zeile 68–71). Keine Stelle im
  Hauptcode schreibt heute `requires`. `model/UnknownFields.putUnitField` (Zeile 33–38) verwirft beim Lesen ein
  `requires`, das das Gate durchlässt.
- Verhalten von v1.1 gegenüber einem gesperrten Kopf: `UnitMapper.requireWritableAncestors` (Zeile 329–336) lehnt
  das Speichern jeder Einheit unter einem inhaltlich zu neuen Kopf ab; ein solcher Kopf fehlt im Modell
  (`UnitMapper.read`, Zeile 848–853), und `StoreEventRepository.requireCurrent` (Zeile 242–253) meldet SYS-009. Die
  Methode `requireWritableAncestors` steht in den Tags 1.1.0, 1.1.1 und 1.1.2 (`git grep` am 26.11.2026).
- v1.1 und ein neuer Ordner: v1.1 hat denselben `UnitKey.parse` und dieselbe Aufzählung in `eventUnits`; es übergeht
  einen Ordner `cashRegisters`. Ein Eintrag in der Änderungsliste eines anderen Rechners mit einem Pfad, den `parse`
  nicht erkennt, setzt dort `fullSyncNeeded` (`store/FeedReader.java` Zeile 307–311).

**Wiederherstellen**

- `StoreEventRepository.savePoints` (Zeile 282–284) bildet die Speicherstände aus den Ketten aller Einheiten von
  `eventUnits`. `targetOf` (Zeile 361–366) nimmt je Einheit das letzte Glied bis zum Zeitpunkt
  (`repository/RestorePlanner.targetsAt`, Zeile 83–96; eine Einheit ohne solches Glied fehlt), prüft mit
  `requireRestorable` jede Einheit des Events (Zeile 116–137) und setzt das Ziel-Event zusammen. `commitRestore`
  (Zeile 329–340) passt es mit `adjust` an und speichert es wie jedes Event; was im Ziel fehlt, wird nach den Regeln
  oben gelöscht.
- `ui/backup/EventBackupCatalog.java`: `adjustForRestore` (Zeile 36–50) entfernt Hallen, die es nicht mehr gibt, und
  liefert Hinweise; `restoreWarning` (Zeile 52–55) liefert den Satz der Rückfrage, den
  `ui/backup/BackupRestoreDialog.confirmText` (Zeile 88–91) einsetzt.

**Oberfläche und Änderungsverfolgung**

- `ui/event/EventEditController.java` und `EventEditView.fxml`: Event- und Vorstellungstabelle nebeneinander (FXML
  Zeile 27–58), darunter die Felder der Vorstellung und die Tisch- und Sitztabelle. „Speichern" speichert alle
  geänderten Events über `EventSaver.save` (Controller Zeile 1137–1155). Eine Vorstellung wird über
  `LockedEntryGuard.deletePresentation` und `Event.deletePresentation` gelöscht (Controller Zeile 1017–1030,
  `model/Event.java` Zeile 166).
- Ungespeicherte Änderungen erkennt der `ChangeTracker` am JSON des ganzen Events (`docs/architecture.md`,
  „tracking"); `ui/EventSaver.trySave` (Zeile 50–57) prüft mit `EventValidator.validate` und speichert bei Fehlern
  nicht; auch der Kartenverkauf speichert über `EventSaver.save`. `validation/EventValidator.run` (Zeile 24–29)
  prüft Name, eindeutige Vorstellungsnamen und die Vorstellungen. Doppelte Vorstellungsnamen sind nur eine Warnung
  (`validation/EventIssue.java`, `EVT-001`).
- `ui/EditingUnits` führt die Objekte, die gerade bearbeitet werden, nach ihrer Identität.
- `ui/BookingSyncService.prepare` gleicht beim Öffnen eines Events die Buchungen mit der Bestuhlung ab, in
  `StoreEventRepository.deriving` (Zeile 1175–1182), und macht den Stand danach zum gespeicherten, wenn das Event
  vorher sauber war (Zeile 84–86).
- Schutztests: `FormatGuardTest` führt die gespeicherten Klassen in `NINE` (Zeile 48–49), `JsonKeysTest` die
  JSON-Schlüssel je Klasse (Zeile 34–48, für `Event`: `id`, `name`, `description`, `archived`, `presentations`),
  `IssueConventionsTest.allEnums` die Meldungs-Enums (Zeile 21–25).

## Betrachtete Wege

**Mischbetrieb mit v1.1 (KARV-153)**

- *v1.1 darf weiter verkaufen:* Zahlungen ohne Kasse fielen in die Kasse der Vorstellung. Für eine gemeinsame Kasse
  gibt es keine Regel „altes Feld gewinnt", und der Kassenabgleich von v1.1 zeigte veraltete Ist-Beträge.
- *Gemeinsamer Umstieg, erzwungen über `requires`:* gewählt vom Maintainer am 26.11.2026. Das Format der Kasse
  bleibt frei von Rücksichten, und KARV-199 braucht die Sperre nach den Formatregeln ohnehin.

**Speicherort (KARV-156)**

- *Liste im Event-Kopf:* wenig Aufwand, aber der Kopf führt je Feld zusammen; zwei Rechner an verschiedenen Kassen
  erzeugten einen Konflikt über die ganze Liste, und jede Kassenbewegung (KARV-147) schriebe eine Version des Kopfs.
- *Eigene Einheit neben Events und Hallen:* nur sinnvoll, wenn eine Kasse für mehrere Events gilt. Kampagne ist
  Event, den Fall gibt es nicht; Löschen, Kopieren und Verweise bräuchten eigene Regeln.
- *Eigene Einheit unter dem Event:* gewählt vom Maintainer am 26.11.2026.

**Wiederherstellen**

- *Kassen gehen mit zurück* (heutiges Verhalten des Ist-Betrags): Eine Zählung, deren Geld längst bei der Bank
  liegt, verschwände still; jeder Stand von vor 1.2 löschte alle Kassen, weil sie darin fehlen.
- *Kassen bleiben stehen:* gewählt vom Maintainer am 26.11.2026, nach vier unabhängigen Gutachten (Kassenprüfung,
  Bedienung, Technik, Fälle) und deren Diskussion. Bleibt der Ist-Betrag stehen, zeigt die Differenz die Verkäufe,
  die das Wiederherstellen entfernt hat.

**Entscheidungen des Autors** (im Gespräch nicht einzeln besprochen; der Maintainer prüft sie mit der Freigabe)

- *Kasse einer Vorstellung nur gedacht statt beim Laden angelegt:* Eine angelegte Kasse je Vorstellung hieße
  Schreiben beim Laden oder ein abgeleiteter Zustand, der beim nächsten Speichern mitgeht. Gedacht heißt: Es gibt
  die Einheit erst, wenn die Kasse eigene Daten trägt.
- *Sperre für v1.1 ab der ersten gespeicherten Kasse statt ab dem ersten Start oder ab jedem Speichern:* So folgt
  `requires` aus dem, was im Event gespeichert ist, und ein Event ohne gespeicherte Kasse enthält nichts, was v1.1
  falsch behandeln könnte.
- *Schalter „gemeinsame Kasse" ergibt sich aus den Kassen:* Es gibt ihn genau dann, wenn die gemeinsame Kasse
  besteht. Ein Feld im Kopf ginge beim Wiederherstellen mit zurück.
- *Kein Ist-Betrag für gemeinsame und freie Kassen vor KARV-146:* Der heutige Kassenabgleich rechnet je Vorstellung;
  ein Kassensturz je Kasse braucht das Soll je Kasse und damit KARV-145.
- *Keine Prüfung „Vorstellung ohne Kasse"* (in KARV-159 vorgesehen): Nach diesem Entwurf hat jede Vorstellung ihre
  Kasse oder die gemeinsame; der Fall kann nicht eintreten.

## Entwurf

### Begriffe

Eine **Kasse** gehört zu genau einem Event. Ihre Art ergibt sich aus ihrer Kennung und wird nicht gespeichert:

| Art | Kennung (`id`, zugleich Ordnername) | gilt für |
|---|---|---|
| Vorstellungskasse | die Kennung der Vorstellung | diese Vorstellung |
| gemeinsame Kasse | `_event` | alle Vorstellungen |
| freie Kasse | `_` + zufällige UUID | alle Vorstellungen oder eine Auswahl |

Der Unterstrich trennt die Namensräume wie beim Ordner einer Buchung ohne Sitz. Eine Vorstellungskasse ist
**verwaist**, wenn das Event im Modell keine Vorstellung mit ihrer Kennung enthält. Das ist auch so, wenn die
Vorstellung nur gesperrt oder unlesbar ist; die Oberfläche unterscheidet die beiden Fälle in der Spalte „gilt für"
(siehe dort).

### Abweichungen von `docs/decisions.md`

Der Entwurf weicht in drei Punkten von „Forward-compatible data format" ab. Jeder wird dort mit der Umsetzung
nachgetragen.

- **Neue Art von Einheit ohne Formatwechsel.** Dort verlangt „restructure units" einen Formatwechsel. Hier wird
  keine vorhandene Einheit umgebaut: Es kommt eine Art in einem Ordner hinzu, den ältere Versionen übergehen, und
  ein `requires` am Kopf hält sie aus dem Event heraus, sobald es eine solche Einheit gibt.
- **`requires` wird nicht allein aus dem Modell abgeleitet und bleibt stehen.** Dort heißt es „derives it when
  writing from the state of the model" und „never keeps it". Hier richtet sich das Merkmal am Kopf nach den
  Kassen-Einheiten im Versionsspeicher, und ein gespeichertes Merkmal bleibt, bis ein Speichervorgang die letzte
  Kasse löscht (siehe „Entfernen"). Grund: Ein Rechner direkt nach dem
  Update kennt den Kopf, aber noch keine Kasse; aus seinem Modell abgeleitet, würde er die Sperre entfernen. Die
  Folge, die die Regel dort verhindern will (eine Einheit entsperrt sich nie), wird hingenommen: Ein Kopf kann das
  Merkmal behalten, obwohl alle Kassen gelöscht sind oder es nie eine gab; das Event bleibt dann für v1.1 gesperrt.
- **Ersatz für ein altes Feld ohne „altes Feld gewinnt".** Dort gewinnt das alte Feld, solange ältere Versionen es
  schreiben, sonst `requires` „on the unit". Hier steht `requires` am Kopf, nicht an der Vorstellung, deren alte
  Felder ersetzt werden. Es wirkt gleich: v1.1 speichert nichts unter einem Kopf, den es für zu neu hält, also auch
  keine Vorstellung.

### Modell

Neue Klasse `model/CashRegister`, aufgebaut wie die anderen gespeicherten Klassen (`ignoreUnknown`, `UnknownFields`
mit `putUnitField`, `copyFrom`). Sie überschreibt `equals` nicht: Die Kennung ist nur innerhalb eines Events
eindeutig (`_event` gibt es in jedem Event mit gemeinsamer Kasse). Gespeicherte Felder:

| Feld | Typ | Bedeutung |
|---|---|---|
| `id` | Text | Kennung, siehe oben |
| `name` | Text oder `null` | eigener Name; `null` heißt Vorgabename |
| `allPresentations` | Wahrheitswert | freie Kasse: gilt für alle Vorstellungen |
| `presentationIds` | Liste von Texten | freie Kasse: die ausgewählten Vorstellungen |
| `actualCashAmount` | ganze Zahl (Cent) | gezählter Bar-Betrag |
| `cashDifferenceComment` | Text oder `null` | Kommentar zur Differenz |

`allPresentations` und `presentationIds` haben nur bei der freien Kasse eine Bedeutung; bei den anderen Arten
bleiben sie `false` und leer.

`Event` bekommt die Liste `cashRegisters` (`null` in einer Datei lädt als leere Liste, wie `presentations`). Sie
steht im JSON des Events, damit der `ChangeTracker` Änderungen an Kassen sieht. `Event.copyFrom` lässt sie
unberührt, wie die Vorstellungen. Die Liste enthält nur Kassen, die es als Objekt gibt; die Kasse einer Vorstellung
entsteht darin erst mit ihrer ersten Änderung. Sie ist immer nach der Kennung geordnet (beim Zusammensetzen, beim
Einfügen einer fremden und einer neuen Kasse), damit das JSON des Events auf jedem Rechner gleich ausfällt; die
Reihenfolge der Anzeige ist davon unabhängig (siehe „Sichtbare Kassen").

Die Felder `actualCashAmount` und `cashDifferenceComment` der Vorstellung bleiben gespeicherte Felder mit ihrer
Feldgruppe. Sie heißen im Folgenden **alte Felder**.

Alle Regeln stehen an einer Stelle im Modell (Vorschlag: `model/CashRegisters`, statische Methoden wie bei
`SaleDates`), ohne JavaFX:

- **Wirksamer Stand der Kasse einer Vorstellung:** Enthält das Event die Vorstellungskasse als Objekt, gelten deren
  Ist-Betrag und Kommentar. Sonst gelten die alten Felder der Vorstellung. So zeigt ein Bestand aus v1.1 dieselben
  Zahlen, ohne dass etwas übernommen wird.
- **Zum Ändern holen:** Gibt es die Vorstellungskasse nicht als Objekt, wird sie angelegt und bekommt Ist-Betrag und
  Kommentar aus den alten Feldern, und die alten Felder werden auf 0 und `null` gesetzt. Gab es das Objekt schon,
  bleiben die alten Felder stehen, außer bei einer Änderung an Ist-Betrag oder Kommentar: Die leert sie (siehe
  „Zweiter Ist-Betrag"). Jede Änderung an einer Vorstellungskasse (Ist-Betrag, Kommentar, Name) läuft über diesen
  Schritt.
- **Eigene Daten** hat eine Vorstellungskasse, wenn ihr Ist-Betrag nicht 0 oder ihr Kommentar nicht leer ist, oder
  wenn ihr Name gesetzt ist und das Event ihre Vorstellung enthält. Die gemeinsame Kasse und freie Kassen haben immer
  eigene Daten.
- **Gemeinsame Kasse gewählt** ist, wenn die Liste die Kasse `_event` enthält. Die Oberfläche behandelt eine
  gesperrte Einheit `_event`, die im Modell fehlt, ebenso (siehe „Gesperrte Kassen" in der Event-Verwaltung).
- **Sichtbare Kassen** eines Events, in dieser Reihenfolge: die gemeinsame Kasse; je Vorstellung (in der Reihenfolge
  der Vorstellungen) ihre Kasse, wenn keine gemeinsame Kasse gewählt ist oder der wirksame Stand einen Ist-Betrag
  ungleich 0, einen Kommentar oder einen Namen hat; freie Kassen nach Name; verwaiste Vorstellungskassen mit
  Ist-Betrag ungleich 0 oder Kommentar nach Name.
- **Vorgabename:** „Kasse <Name der Vorstellung>" für die Vorstellungskasse, „Gemeinsame Kasse" für die gemeinsame.
  Eine verwaiste Kasse ohne eigenen Namen heißt „Kasse ohne Vorstellung".
- **Eine Vorstellung entfällt:** Hat der wirksame Stand ihrer Kasse einen Ist-Betrag ungleich 0 oder einen
  Kommentar, wird die Kasse vorher zum Ändern geholt und bekommt, wenn sie keinen eigenen Namen hat, ihren
  Vorgabenamen als Namen; sie bleibt danach als verwaiste Kasse sichtbar. Hat sie beides nicht, wird ein vorhandenes
  Kassen-Objekt aus der Liste genommen. Die Regel gilt beim Löschen von Hand (`Event.deletePresentation`) und für
  jede Vorstellung, die ein Wiederherstellen entfernt (siehe „Wiederherstellen"). Ein zweiter Ist-Betrag in den
  alten Feldern (siehe unten) geht dabei bewusst ohne Hinweis mit der Vorstellung: Für ihn galt schon vorher die
  Kasse, und er bleibt im letzten Stand der Vorstellung erhalten.
- **Zweiter Ist-Betrag:** Gibt es die Vorstellungskasse als Objekt und steht in den alten Feldern der Vorstellung
  trotzdem ein Ist-Betrag ungleich 0 oder ein Kommentar (ein Rechner mit v1.1 hat nach der ersten Speicherung der
  Kasse noch geschrieben, oder ein Speichervorgang brach nach der Kasse ab), gilt die Kasse. Die Prüfung `KAS-005`
  und der Kassenabgleich nennen den zweiten Betrag, bis jemand dort Ist-Betrag oder Kommentar ändert oder den
  zweiten Betrag verwirft; erst das leert die alten Felder.

### Speicherung

- **Einheit:** neue Art `UnitType.CASH_REGISTER` (`hasChildren` falsch), Pfad
  `events/<eventId>/cashRegisters/<Kennung>`. `UnitKey` bekommt eine Komponente für den Ordnernamen der Kasse und
  eine Fabrik; zulässig ist eine Kennung nach dem bestehenden Muster, wahlweise mit führendem Unterstrich.
  `parent` ist der Event-Kopf. `VersionStore.eventUnits` zählt die Kassen eines Events mit auf; damit erfassen
  Index-Neuaufbau und Wiederherstellen sie. Der Vollabgleich bildet seine Abschnitte selbst: `FullSyncRun.begin`
  bekommt je Event einen Abschnitt für die Kassen, deren Kennungen wie die der Vorstellungen über
  `FullSync.childrenOfBoth` mit dem Ordner `cashRegisters` gefunden werden. Der Abschnitt wird ohne Teilbaum
  abgeglichen, wie der des Kopfs (`FullSyncRun.sync` mit `tree = null`): Scheitert er, kommt das Event nicht in
  `skippedTrees`, und die Auflösung seiner Vorstellungen und Buchungen wird nicht zurückgestellt. Das Auflisten
  des Ordners `cashRegisters` steht in einem eigenen `try`, getrennt von dem der Vorstellungen: Scheitert es, fehlt
  nur der Abschnitt der Kassen in diesem Lauf, und weder `Section.presentationsOf` noch `skipTree(event)` greift.
  `StoreCodec.FORMAT` und `INDEX_FORMAT` bleiben unverändert.
- **Zerlegen und Schreiben** (`UnitMapper.decompose`, `UnitMapper.changes`): Der Kopf enthält weder `presentations`
  noch `cashRegisters`. Jede Kasse der Liste ergibt eine Einheit, mit einer Ausnahme: Für eine Vorstellungskasse
  ohne eigene Daten wird keine Einheit **neu angelegt**. Ist ihre Einheit schon gespeichert, wird sie mit ihrem
  Stand weitergeschrieben; ein auf 0 gesetzter Ist-Betrag ist damit eine gewöhnliche Feldänderung und keine
  Löschung. Gelöscht wird eine Kassen-Einheit nur, wenn ihr Objekt die Liste verlässt: eine freie oder die
  gemeinsame Kasse von Hand, eine Vorstellungskasse nach der Regel „Eine Vorstellung entfällt". `decompose` kennt
  den Vergleichsstand nicht; die Ausnahme gilt deshalb überall gleich, wo das Repository die Einheiten eines Events
  aus dem Modell bildet (Speichern, „lokal verändert" in `StoreEventRepository.localChanges`, die nach dem Speichern
  als angezeigt geltenden Einheiten, der Zielstand beim Wiederherstellen): Eine Vorstellungskasse ohne eigene Daten
  zählt genau dann als Einheit des Events, wenn der Vergleichsstand ihre Einheit lebend kennt. Ein Kassen-Objekt
  ohne eigene Daten und ohne Einheit (Betrag getippt und wieder auf 0 gesetzt) gilt also nicht als lokal verändert.
  Dasselbe gilt für die öffentlichen `StoreEventRepository.payloadOf` und `payloadsOf` (genutzt von
  `ui/ConflictActions`); beim Wiederherstellen ist der Maßstab der frisch gelesene Stand.
- **Reihenfolge im Speichervorgang:** `SyncService.append` schreibt die Änderungen nacheinander, und nach einem
  Fehler bleiben die schon geschriebenen liegen. Deshalb gilt:
  1. Kassen-Einheiten, die angelegt oder geändert werden, stehen vor jeder Vorstellung und vor den Löschungen, die
     `UnitMapper.changes` beim Wiederbeleben eines Kopfs voranstellt. Diese Löschungen folgen danach, vor allem
     Übrigen.
  2. Der Kopf steht vor den Kassen, wenn er lebt und das Merkmal bekommt oder behält. Wird er wiederbelebt, steht er
     wie bisher nach den vorangestellten Löschungen; bis dahin ist alles darunter unsichtbar. Verliert er das
     Merkmal, steht er nach den Löschungen der Kassen.

  So hat beim Wiederherstellen eines gelöschten Events eine Vorstellung, die entfällt, ihre Kasse schon, bevor ihre
  Löschung geschrieben wird; bricht der Vorgang dazwischen ab, wendet ein zweiter Versuch die Regel erneut an.

  Bricht der Vorgang ab, bleibt höchstens einer dieser Zustände: ein Kopf mit Merkmal ohne Kasse (die alten Felder
  gelten weiter, „Kassen noch nicht abgeglichen" endet mit dem Vollabgleich); ein zweiter Ist-Betrag in den alten
  Feldern (`KAS-005`); ein Kopf, der das Merkmal noch trägt, obwohl die letzte Kasse gelöscht ist (bekannte Grenze).
  Außer bei einem Kopf, der sich nicht schreiben lässt (bekannte Grenzen), öffnet v1.1 damit nie ein Event, dessen
  alte Felder geleert sind.
- Die `switch`-Anweisungen über die Art in `EventMerge.apply`, `EventMerge.subtree` und `UnitMapper.comparable`
  übersetzen auch ohne den neuen Zweig; der Compiler meldet ihn dort nicht. `EventMerge.subtree` nimmt für den Kopf
  die Kassen mit auf, und `ui/ConflictActions.inUse` prüft auch Kassen in Bearbeitung; sonst bliebe eine Kasse,
  deren Name gerade bearbeitet wird, bei einer fremden Löschung des Kopfs und bei den Konfliktaktionen
  unberücksichtigt.
- **Zusammensetzen** (`UnitMapper.assembleEvents`): ein weiterer Durchlauf für Kassen. Eine Kasse unter einem
  gelöschten Kopf ist unsichtbar, eine ohne lesbaren Kopf zählt zu den übergangenen Einheiten. Die Kennung im Inhalt
  muss zum Ordner passen, wie bei den anderen Arten. `comparable`, `readUnit` und `modelClass` bekommen ihren Zweig;
  im Zweig des Kopfs entfernt `comparable` neben `presentations` auch `cashRegisters` und `requires`, sonst gälte
  jeder Kopf als lokal verändert.
- **Feldgruppen** (`FieldGroups`): `name`; `scope` mit `allPresentations` und `presentationIds`; `cash` mit
  `actualCashAmount` und `cashDifferenceComment`; `id`. Der Kopf behält seine Gruppen.
- **Konfliktregel** (neue `CashRegisterPolicy`, in `ConflictPolicies` eingetragen): „Gleich" und „unlesbar" wie bei
  der Vorstellung, danach „Löschen gewinnt" mit einer Ausnahme, danach der Vergleich je Feldgruppe. **Ausnahme:**
  Ist eine Seite eine Löschung und hat die andere die Gruppe `cash` gegenüber der Basis geändert (ohne Basis: trägt
  sie einen Ist-Betrag ungleich 0 oder einen Kommentar), gilt die nicht gelöschte Seite. Ist „Meine" die Löschung,
  gilt „Deren" mit dem vorhandenen Vermerk `UNMERGED` (`PolicyRules.unmerged`). Ist „Meine" die Zählung, gilt
  „Meine" ohne Vermerk; der andere Rechner sieht die Kasse mit dem Betrag wieder. Eine neue Art von Vermerk entsteht
  nicht, der `store` bleibt unverändert. Begründung: Gelöscht wird nur eine Kasse ohne Ist-Betrag und Kommentar; hat
  die andere Seite inzwischen gezählt, darf das nicht verloren gehen, und dem löschenden Rechner geht nichts
  verloren außer der Löschung einer leeren Kasse. In allen anderen Fällen gewinnt die Löschung wie bei der
  Vorstellung, mit ihrem Vermerk.
- **Fremde Stände** (`EventMerge`): Ein fremder Stand einer Kasse wird in das vorhandene Objekt übernommen
  (`copyFrom`), eine neue Kasse kommt in die Liste, eine gelöschte verlässt sie; das Zurücknehmen bei einem Fehler
  (`Undo`) sichert die Kassen mit. `LiveViews.touches` zählt eine Kassen-Einheit für jede gezeigte Vorstellung ihres
  Events. In `StoreEventRepository` bekommen die Wege der Konfliktaktionen (`applyFields`, `applyGroup`,
  `restoreFields`, die heute nur `presentations` und `bookings` aus dem Inhalt nehmen) und die Kopie für den
  Änderungsmelder (`rebuild`, die heute nur `presentations` setzt) die Kassen dazu. `ConflictLabels` nennt eine
  Kasse „<Event> › <Anzeigename der Kasse>" und baut für sie keinen Schlüssel einer Vorstellung;
  `LiveUpdates.label` und `EntityLabels` kennen die Kasse.
- **Löschen des Events:** wie bisher nur am Kopf. Die Kassen bleiben liegen und sind unsichtbar. Eine gesperrte
  Kasse verhindert das Löschen des Events, wie heute eine gesperrte Vorstellung oder Buchung
  (`StoreEventRepository.deleteEvent` über `requireNothingLockedUnder`; `docs/decisions.md`, „Permanent locks").
- **Kopie eines Events:** ohne Kassen; die Kopie beginnt mit einer Kasse je Vorstellung.

### Sperre für v1.1

- `Requires.KNOWN` enthält das Merkmal `cashRegisters`.
- **Setzen:** Ein Speichervorgang schreibt den Kopf mit `"requires": ["cashRegisters"]`, wenn der gespeicherte
  Kopf das Merkmal nicht trägt und der Vorgang eine Kassen-Einheit anlegt oder ändert (nicht: löscht) oder der
  Versionsspeicher für das Event schon eine lebende Kassen-Einheit kennt. Maßgeblich ist der Stand im Versionsspeicher (Index; `UnitMapper.changes`
  bekommt ihn schon als `eventStates`), nicht der Vergleichsstand: So zählen auch Kassen, die nicht im Modell stehen
  (gesperrt, unlesbar, zurückgestellt).
- **Entfernen:** Der Kopf verliert das Merkmal in einem Speichervorgang, der selbst eine Kassen-Einheit löscht und
  nach dem im Versionsspeicher keine lebende Kassen-Einheit des Events mehr steht. Sonst bleibt ein gespeichertes
  Merkmal stehen, auch wenn dieser Rechner keine Kasse des Events kennt, auch im Einzelbetrieb: Am gemeinsamen
  Ordner kann eine Kasse noch unterwegs sein (ein Cloud-Ordner überträgt Dateien einzeln, ein Anspruch kann
  fehlen), und ein Rechner, der früher verbunden war und getrennt wurde, kann Kassen verpasst haben. Kein Rechner
  darf die Sperre deshalb entfernen, nur weil er keine Kasse kennt.
- **Schreiben:** Die Ableitung geschieht dort, wo Modell und Stand des Versionsspeichers zusammenkommen
  (`UnitBaseline.save` mit `UnitMapper.changes`), nicht in `decompose`. Der Kopf wird dafür mitgeschrieben, auch
  wenn sich sonst nichts an ihm geändert hat. Lässt er sich nicht schreiben (gesperrt oder wartend), unterbleibt
  nur dieses Mitschreiben; der übrige Speichervorgang läuft wie heute, und der nächste holt es nach. Fehlend,
  `null` und `[]` gelten beim Vergleich als „kein Merkmal".
- **Konflikte:** `EventHeadPolicy` nimmt `requires` aus dem Vergleich je Feldgruppe heraus und setzt im Ergebnis
  das Merkmal, wenn eine der beiden Seiten es trägt. Ein Vermerk entsteht dafür nicht; die Konfliktliste und die
  Zahl der offenen Konflikte in der Fußzeile kennen keine Gruppe `requires`.
- **Kassen noch nicht abgeglichen:** Dieser Zustand gilt für ein Event, wenn alles Folgende zutrifft: Ein
  gemeinsamer Ordner ist verbunden, auch wenn er gerade nicht erreichbar ist (dann bleibt der Zustand bewusst bis
  zum ersten Vollabgleich nach der Rückkehr); seit dem Programmstart ist noch kein Vollabgleich bis zum Ende gelaufen (ob er
  dabei einzelne Teile übersprungen hat, zählt nicht); der Kopf trägt das Merkmal, bei einem gelöschten Kopf sein
  letzter nicht gelöschter Stand; der Versionsspeicher dieses Rechners kennt keine Kassen-Einheit des Events, auch
  keine gelöschte. Das ist der
  gewöhnliche Zustand eines Rechners direkt nach dem Update von 1.1: Solange er 1.1 fuhr, hat er Listeneinträge zu
  Kassen übersprungen, und die Einheiten kommen erst mit dem ersten Vollabgleich. In diesem Zustand sind für das
  Event der Abschnitt „Kassen" der Event-Verwaltung und Ist-Betrag und Kommentar im Kassenabgleich nicht
  bearbeitbar, mit dem Hinweis „Die Kassen dieses Events werden noch abgeglichen." Ebenso lehnen das Löschen einer
  Vorstellung des Events und das Wiederherstellen des Events mit diesem Hinweis ab; eine später eintreffende Kasse
  stünde sonst ohne ihre Vorstellung da. Die Gesamtbilanz zeigt für das Event als Ist-Betrag und Differenz „–" mit
  demselben Hinweis. Das Repository gibt den Zustand je Event an. Er endet, sobald eine Kassen-Einheit des Events eintrifft
  oder der erste Vollabgleich seit dem Start bis zum Ende gelaufen ist; im Einzelbetrieb gibt es ihn nie. Endet er, ohne dass eine Einheit eintrifft, meldet das Repository das seinen Listenern wie eine
  fremde Änderung am Kopf des Events, damit offene Ansichten die Sperre aufheben. Ein Kopf mit Merkmal ohne
  Kassen-Einheit gilt danach als Event ohne gespeicherte Kassen (etwa nach einem abgebrochenen ersten Speichern
  eines neuen Events); das Merkmal bleibt.
- **Vergleichen:** Für „lokal verändert" (`StoreEventRepository.localChanges` und die Vergleiche, die es nutzt)
  wird der Kopf auf beiden Seiten ohne `requires` verglichen. Ein Kopf gilt also nie allein wegen `requires` als vom
  Nutzer geändert. Beim Schreiben in `UnitMapper.changes` ist das anders: Dort entscheidet `sameAsModel`, ob der
  Kopf geschrieben wird, und vergleicht den Kopf mit dem abgeleiteten Merkmal; weicht nur `requires` ab, wird er
  geschrieben (siehe „Schreiben"). Beim Lesen ins Modell wird `requires` weiter verworfen
  (`UnknownFields.putUnitField`), und es wird nie in eine andere Einheit kopiert.
- Folge: Mit der ersten gespeicherten Kasse wird im selben Speichervorgang der Kopf geschrieben, und Rechner mit
  v1.1 sperren das Event (SYS-009). Löscht ein Speichervorgang die letzte lebende Kassen-Einheit, verliert der Kopf
  das Merkmal in diesem Vorgang wieder.
- `ContentRules.rulesId()` ändert sich; beim ersten Start mit 1.2 löst der Start-Index alle Einheiten einmal neu
  auf. Die Tests mit fester Erwartung an `rulesId` und an ein leeres `KNOWN` werden angepasst (`FormatGuardTest`,
  `ContentRulesTest`, `UnknownFieldsTest`).
- Bekannte Grenzen, bewusst hingenommen:
  - Ein Rechner mit v1.1, der den Kopf noch nicht abgeholt hat (etwa offline), kann den Ist-Betrag einer
    Vorstellung noch in die alten Felder schreiben. Hat 1.2 die alten Felder beim ersten Speichern der Kasse
    geleert, entscheidet die Konfliktregel der Vorstellung mit Vermerk. Waren sie schon 0, gewinnt der Wert von
    v1.1 ohne Vermerk in den alten Feldern; es gilt trotzdem die Kasse, und `KAS-005` macht den zweiten Betrag
    sichtbar.
  - Der Kopf kann vorübergehend ohne `requires` sein, obwohl Kassen gespeichert sind, wenn ein Rechner die letzte
    Kasse löscht, während ein anderer eine anlegt und den Kopf deshalb nicht schreibt. Der nächste
    Speichervorgang eines Rechners mit 1.2 in diesem Event, der die Kasse im Versionsspeicher kennt, setzt das
    Merkmal wieder. Bis dahin kann ein Rechner mit v1.1 das Event öffnen; er sieht die Kassen nicht. Derselbe
    Zustand entsteht, wenn ein beschädigter Kopf (SYS-008) über „Zurücksetzen" roh auf einen lesbaren Stand vor der
    ersten Kasse zurückgesetzt wird (`SyncService.resetToLastReadable`, nicht über das gewöhnliche Speichern).
  - Ein Kopf mit Merkmal ohne Kasse (etwa nach einem abgebrochenen ersten Speichern eines neuen Events) behält das
    Merkmal, bis jemand eine Kasse anlegt und wieder löscht. Am gemeinsamen Ordner gilt auf jedem Rechner nach
    jedem Start bis zum ersten Vollabgleich „Kassen noch nicht abgeglichen", und v1.1 bleibt ausgesperrt.
  - Wird ein Rechner direkt nach dem Update vom gemeinsamen Ordner getrennt, bevor sein erster Vollabgleich
    durchlief, kennt er die Kassen nicht; im Einzelbetrieb gilt „Kassen noch nicht abgeglichen" nicht, er zeigt
    für gezählte Vorstellungen 0 und lässt neu zählen. Das Merkmal bleibt (siehe „Entfernen"). Wird er wieder
    verbunden, treffen seine neuen Kassen auf die vorhandenen und werden nach der Konfliktregel der Kasse aufgelöst,
    mit Vermerk.
  - Kommen die Kassen eines Events nach dem Update nur zum Teil an (der Vollabgleich läuft über mehrere Takte),
    greift „Kassen noch nicht abgeglichen" nicht mehr, und eine noch fehlende Vorstellungskasse zeigt kurz 0. Eine
    Zählung in diesem Fenster trifft später auf die fremde Einheit und wird nach der Konfliktregel der Kasse
    aufgelöst, mit Vermerk. Dasselbe gilt, wenn sich im ersten Vollabgleich der Ordner `cashRegisters` eines Events
    nicht auflisten lässt.
  - Ein Rechner mit v1.1 sieht ein gesperrtes Event nicht. Seine Prüfung „Halle wird noch verwendet" findet dessen
    Vorstellungen deshalb nicht, und er kann eine Halle löschen, die das Event in 1.2 benutzt. Das ist eine
    allgemeine Folge von `requires`, hier zum ersten Mal wirksam; die Patchnotes zum gemeinsamen Umstieg decken es
    ab.
  - Lässt sich der Kopf nicht schreiben (beschädigt-gesperrt oder wartend), wird die erste Kasse ohne das Merkmal
    gespeichert. Bis der Kopf wieder schreibbar ist und das nächste Speichern es nachholt, kann ein Rechner mit v1.1
    das Event öffnen und zeigt im Kassenabgleich die geleerten alten Felder.
  - Scheitert das Mitschreiben des Kopfs in dem Speichervorgang, der die letzte Kasse löscht, behält der Kopf das
    Merkmal auf Dauer. Das Event bleibt für v1.1 gesperrt; für 1.2 ändert sich nichts.
  - **Wiederherstellen eines gelöschten Events an einem Rechner mit v1.1.** Eine Löschung trägt keinen Inhalt
    (`StoreCodec` schreibt für eine gelöschte Version ein leeres `payload`, Zeile 60), also auch kein `requires`,
    und v1.1 prüft beim Wiederherstellen nur den heutigen Stand und das Ziel-Glied
    (`RestorePlanner.requireRestorable`). Stellt jemand an v1.1 ein Event, das in 1.2 Kassen hatte und gelöscht
    wurde, auf einen Stand vor der ersten Kasse wieder her, entsteht ein lebender Kopf ohne Merkmal mit den alten
    Feldern dieses Zeitpunkts, und v1.1 zeigt sie als gültig. 1.2 sieht danach die Kassen wieder (sie lagen
    unsichtbar unter dem gelöschten Kopf), `KAS-005` nennt die alten Beträge, und das nächste Speichern in 1.2 setzt
    das Merkmal. Technisch verhindern ließe sich das nur mit einem Inhalt an der Löschung, also mit einer Änderung
    an der Hülle der Versionen; das bleibt außen vor, weil der gewählte Betrieb ohnehin den gemeinsamen Umstieg
    verlangt.
  - Kommen die Versionen eines Speichervorgangs an einem anderen Rechner in anderer Reihenfolge an als geschrieben
    (ein Cloud-Ordner überträgt Dateien einzeln), kann ein Rechner mit v1.1 kurz die geleerten alten Felder einer
    Vorstellung zeigen, bevor der Kopf mit dem Merkmal eintrifft.
  - Ein laufender v1.1-Rechner startet bei jedem Listeneintrag zu einer Kasse einen Vollabgleich.

### Wiederherstellen

Regel für die Bedienung: Wiederherstellen setzt Vorstellungen und Buchungen zurück; Kassen bleiben, wie sie sind.

„Heute" meint im Folgenden den frisch gelesenen Stand der Einheiten im Versionsspeicher, nicht das Modell: Eine
Vorstellung lebt heute, wenn der aktuelle Stand ihrer Einheit nicht gelöscht ist, auch unter einem gelöschten Kopf.
Die Regeln gelten damit ebenso, wenn ein gelöschtes Event wiederhergestellt wird.

- **Kassen-Einheiten:** Im Zielstand (`StoreEventRepository.targetOf`) steht für jede lesbare Kassen-Einheit des
  Events ihr heutiger Stand, nicht das Glied zum Zeitpunkt, auch für Kassen, die es zum Zeitpunkt noch nicht gab
  und für Kassen unter einem gelöschten Kopf. Ist-Betrag, Kommentar und „gilt für" einer vorhandenen Kasse ändert
  das Wiederherstellen nie.
- **Alte Felder:** Für jede Vorstellung des Zielstands, die heute lebt und lesbar ist, bekommen die alten Felder
  ihren heutigen Wert. Für eine Vorstellung, die das Wiederherstellen zurückbringt, werden sie geleert, wenn die
  Einheit ihrer Kasse heute lebt (nicht gelöscht; eine gesperrte oder unlesbare Einheit lebt); sonst bekommen sie den Wert aus dem letzten nicht gelöschten Stand ihrer Einheit,
  nicht den aus dem Zielstand. Der Zielstand kann eine Zählung zeigen, die in 1.2 vor dem Löschen schon
  zurückgenommen war.
- **Vorstellungen, die das Wiederherstellen entfernt** (heute lebend, im Zielstand nicht enthalten): Für jede gilt
  die Regel „Eine Vorstellung entfällt" am heutigen Stand. Hat ihre Kasse einen Ist-Betrag oder Kommentar, steht sie
  danach als verwaiste Kasse mit ihrem Vorgabenamen im Ziel-Event. Das sind die einzigen Fälle, in denen ein
  Wiederherstellen eine Kassen-Einheit schreibt: Es legt die Einheit einer Vorstellungskasse an, deren Betrag bisher
  nur in den alten Feldern stand, oder setzt an einer Kasse, die dabei verwaist und keinen eigenen Namen hat, den
  Namen. Ein Kassen-Objekt ohne Ist-Betrag und Kommentar, dessen Vorstellung entfällt, wird gelöscht. Ist die
  Kassen-Einheit einer entfallenden Vorstellung gesperrt, wird die Kasse weder zum Ändern geholt noch umbenannt noch
  gelöscht, und die alten Felder der Vorstellung bleiben unberührt; das Wiederherstellen scheitert daran nicht.
- **Gesperrte Kassen:** Eine gesperrte, unlesbare oder wartende Kassen-Einheit verhindert das Wiederherstellen
  nicht und wird dabei nicht geschrieben (`RestorePlanner.requireRestorable` und `RestorePlanner.single` lehnen heute ab, sobald
  eine Einheit des Events gesperrt ist oder das Zusammensetzen eine Warnung meldet; für Kassen-Einheiten gilt das
  nicht). Hat sie einen lesbaren letzten Stand, steht sie mit ihm im Ziel-Event, wie heute im Modell; sonst fehlt
  sie darin, ohne als angezeigt zu gelten. Für `requires` am Kopf zählt sie weiter mit.
- **Speicherstände:** Die Ketten der Kassen-Einheiten zählen für `savePoints` nicht. Ein Speichervorgang, der nur
  Kassen-Einheiten schrieb, erscheint nicht; bei den anderen zählen Kassen nicht in „n Änderungen". Ein
  Speichervorgang, der neben einer Kasse auch den Kopf (`requires`) oder eine Vorstellung (alte Felder geleert)
  schrieb, erscheint weiter: Das ist beim ersten Speichern einer Kasse des Events so und bei der ersten Zählung
  einer Vorstellung aus einem Bestand von v1.1.
- **Rückfrage:** `EventBackupCatalog.restoreWarning` lautet „Spätere Änderungen und Verkäufe, auch die anderer
  Rechner, werden mit zurückgesetzt. Kassen bleiben, wie sie sind: Was gezählt wurde, bleibt gezählt." Gibt es
  sichtbare Kassen, deren wirksamer Ist-Betrag heute von dem zum gewählten Zeitpunkt abweicht (für eine
  Vorstellungskasse zum Zeitpunkt: ihre Einheit, sonst die alten Felder der Vorstellung, sonst 0), folgt:

  ```
  Seit diesem Stand gezählt:
  • Kasse 1. Sitzung: 1.250,00 €
  Zeigt der Kassenabgleich danach eine Differenz, sind Verkäufe nachzutragen.
  ```

  Die Zeile nennt den heutigen Betrag, auch „0,00 €", wenn er seit dem Stand auf 0 gesetzt wurde; eine Änderung
  nur am Kommentar zählt nicht. Dieselbe Liste steht nach dem Wiederherstellen unter den Hinweisen, eingeleitet mit
  „Kassen wurden nicht zurückgesetzt."
- **Ablauf des Dialogs:** Heute fragt `BackupRestoreDialog` zurück, bevor irgendetwas gelesen wird, und bereitet
  danach im Hintergrund vor. Neu liest ein eigener Schritt im Hintergrund vor der Rückfrage die Einheiten, aus
  denen die Liste entsteht (Kassen und Vorstellungen des Events, heute und zum Zeitpunkt). Der Plan des
  Wiederherstellens wird wie bisher erst nach dem Ja vorbereitet; die Liste stammt nicht aus ihm, damit ein
  fremder Stand, der während der offenen Rückfrage eintrifft, das Wiederherstellen nicht ablehnen lässt.

### Oberfläche: Event-Verwaltung (KARV-158)

In `EventEditView.fxml` kommt unter die Zeile mit Event- und Vorstellungstabelle ein Abschnitt „Kassen" für das
gewählte Event:

```
Kassen     (•) je Vorstellung eine Kasse   ( ) eine gemeinsame Kasse      [+ Kasse hinzufügen]
┌──────────────────────────┬──────────────────────────────┐
│ Name                     │ gilt für                     │
│ Kasse 1. Sitzung         │ 1. Sitzung                   │
│ Kasse 2. Sitzung         │ 2. Sitzung                   │
│ Vorverkauf Rathaus       │ alle Vorstellungen           │
│ Kasse Generalprobe       │ – (Vorstellung gelöscht)     │
└──────────────────────────┴──────────────────────────────┘
```

- Die Zeilen sind die sichtbaren Kassen. Im Anzeigemodus ist der Abschnitt nicht bearbeitbar, ebenso im Zustand
  „Kassen noch nicht abgeglichen".
- Eine verwaiste Kasse zeigt unter „gilt für" „– (Vorstellung gelöscht)", wenn die Einheit ihrer Vorstellung
  gelöscht ist oder es sie nie gab, und „– (Vorstellung nicht verfügbar)", wenn die Vorstellung nur im Modell fehlt
  (gesperrt oder unlesbar). Das Repository gibt an, welcher Fall vorliegt.
- **Gesperrte Kassen:** Das Repository nennt je Event die gesperrten Kassen-Einheiten (SYS-008, SYS-009) und die,
  die noch auf eine Datei warten, am Stand des Versionsspeichers wie `requireNothingLockedUnder`; eine wartende
  Kasse wird hier wie eine gesperrte behandelt. Geprüft wird vor jeder Änderung am Modell, wie bei
  `LockedEntryGuard`: Gelangt eine Änderung an einer gesperrten Einheit ins Modell, scheitert danach jedes Speichern
  des Events, auch im Kartenverkauf.
  - Eine gesperrte Kasse mit lesbarem Stand steht als Zeile da, mit „(gesperrt)" hinter dem Namen.
  - Eine gesperrte Kasse ohne lesbaren Stand ersetzt die Zeile, die es sonst für sie gäbe: bei einer
    Vorstellungskasse „Kasse <Name der Vorstellung> (gesperrt)" mit der Vorstellung unter „gilt für" (fehlt die
    Vorstellung im Modell: „Kasse ohne Vorstellung (gesperrt)" mit dem Text für verwaiste Kassen; diese Zeile
    erscheint immer, weil ihr Inhalt unbekannt ist und eine Zählung enthalten kann), bei der
    gemeinsamen Kasse „Gemeinsame Kasse (gesperrt)", bei einer freien Kasse „Gesperrte Kasse" mit „–". Sie zählt
    nicht in die Gesamtbilanz, auch nicht mit den alten Feldern ihrer Vorstellung; der Hinweis unter dem Feld nennt
    dann die Sperre.
  - An einer gesperrten Kasse lassen sich Name und „gilt für" nicht ändern, und „Kasse löschen" fehlt.
  - Ist die gemeinsame Kasse gesperrt, gilt sie als gewählt, auch wenn sie im Modell fehlt; der Schalter ist nicht
    bedienbar, und der Kassenabgleich verhält sich wie bei gewählter gemeinsamer Kasse.
  - Ist die Kasse einer Vorstellung gesperrt, lässt sich ihr Name nicht ändern; zu Ist-Betrag, Kommentar und
    Löschen der Vorstellung siehe „Kassenabgleich".
- **Name:** im Bearbeiten-Modus in der Zelle änderbar. Ein leerer Name setzt bei Vorstellungskasse und gemeinsamer
  Kasse den Vorgabenamen zurück (`name` = `null`).
- **gilt für:** nur bei freien Kassen änderbar, zwischen „alle Vorstellungen" und einer Auswahl aus den
  Vorstellungen des Events; mehrere gewählte stehen mit Komma getrennt. Eine ausgewählte Vorstellung, die es nicht
  mehr gibt, wird nicht angezeigt und beim nächsten Ändern der Auswahl entfernt.
- **Schalter:** „eine gemeinsame Kasse" legt die gemeinsame Kasse an. „je Vorstellung eine Kasse" entfernt sie; hat
  sie einen Ist-Betrag ungleich 0 oder einen Kommentar, bleibt sie, der Schalter springt zurück, und die Fußzeile
  meldet „Die gemeinsame Kasse hat einen Ist-Betrag oder Kommentar und bleibt bestehen."
- **„+ Kasse hinzufügen"** legt eine freie Kasse „Neue Kasse" für alle Vorstellungen an (bei Namensgleichheit „Neue
  Kasse 2" und so fort) und beginnt die Eingabe des Namens.
- **„Kasse löschen"** (Kontextmenü der Zeile) gibt es nur für freie Kassen, nach der Rückfrage „Soll die Kasse
  "<Name>" wirklich gelöscht werden?". Hat die Kasse einen Ist-Betrag ungleich 0 oder einen Kommentar, wird nicht
  gelöscht, und die Fußzeile meldet „Die Kasse "<Name>" hat einen Ist-Betrag oder Kommentar und kann nicht gelöscht
  werden."
- Der Abschnitt nimmt an allem teil, woran die Vorstellungstabelle teilnimmt: Bearbeiten-Modus, Erkennen
  ungespeicherter Änderungen, Speichern über `EventSaver`, „Nicht speichern", Aktualisieren bei Änderungen anderer
  Rechner (nie während einer offenen Eingabe, Auswahl über die Kennung) und die Meldung „in Bearbeitung".

### Oberfläche: Kassenabgleich

- Ist-Betrag und Kommentar einer gewählten Vorstellung zeigen den wirksamen Stand ihrer Kasse. Eine Eingabe holt die
  Kasse zum Ändern und setzt den Wert dort.
- Ist eine gemeinsame Kasse gewählt, sind Ist-Betrag und Kommentar auch bei gewählter Vorstellung gesperrt, und der
  Hinweis unter dem Feld lautet „Für dieses Event gilt eine gemeinsame Kasse. Der Kassensturz je Kasse folgt."
  (vorläufig bis KARV-146). Ist-Betrag und Differenz der Vorstellung zeigen dann „–" statt 0 und eines
  Fehlbetrags; CSV und PDF lassen beide leer. Eine Vorstellungskasse, die vor dem Umschalten gezählt wurde, bleibt
  in der Liste und zählt in die Gesamtbilanz; bei ihrer Vorstellung zeigen Ist-Betrag, Kommentar und Differenz
  ihren Stand, gesperrt, statt „–". Ändern lässt sie sich bewusst erst wieder nach dem Zurückschalten oder mit dem
  Kassensturz je Kasse (KARV-146).
- Ist die Kassen-Einheit der gewählten Vorstellung gesperrt (SYS-008 oder SYS-009) oder wartet sie noch auf eine
  Datei, sind Ist-Betrag und Kommentar
  gesperrt, und der Hinweis nennt die Meldung der Sperre. Hat sie keinen lesbaren Stand, zeigen Ist-Betrag und
  Differenz „–", und CSV und PDF lassen beide leer; ebenso im Zustand „Kassen noch nicht abgeglichen". Ohne diesen
  Schutz fiele die Anzeige auf die alten Felder zurück, und eine Eingabe ließe das Speichern des ganzen Events
  scheitern. Aus demselben Grund lehnt
  `LockedEntryGuard` das Löschen einer Vorstellung ab, deren Kassen-Einheit gesperrt ist.
- Liegt ein zweiter Ist-Betrag vor (Regel im Modell), steht unter dem Feld „Zweiter Ist-Betrag aus einer älteren
  Programmversion: <Betrag>", bei einem Kommentar mit diesem dahinter. Daneben steht im Bearbeiten-Modus
  „Verwerfen"; das leert die alten Felder. Hinweis und Knopf erscheinen auch, wenn eine gemeinsame Kasse gilt und
  das Feld gesperrt ist, damit sich `KAS-005` immer beheben lässt. Der Hinweis verschwindet auch mit der nächsten
  Änderung an Ist-Betrag oder Kommentar.
- „Gesamtbilanz": Der Ist-Betrag ist die Summe über alle sichtbaren Kassen des Events, der Hinweis unter dem Feld
  lautet „Summe der Ist-Beträge aller Kassen des Events, hier nicht direkt bearbeitbar." Hat das Event keine
  Vorstellung, aber eine sichtbare Kasse, zeigt die Gesamtbilanz deren Summe mit einem Soll von 0, statt die
  Ansicht zu leeren. CSV und PDF behalten ihren Aufbau und nehmen Ist-Betrag und Kommentar aus dem wirksamen Stand;
  heute lesen sie den Kommentar aus dem alten Feld (`CashReconciliationController` Zeile 311 und 362), das nach der
  ersten Zählung in 1.2 leer ist.
- Solange im Kassenabgleich eine Vorstellung in Bearbeitung ist, gilt auch die Einheit ihrer Kasse als in
  Bearbeitung: Ein fremder Stand für sie wird zurückgestellt wie einer für die Vorstellung, auch wenn es die Kasse
  noch nicht als Objekt gibt. Dafür fragt die Prüfung „in Bearbeitung" (`StoreEventRepository.touchedByUser`) bei
  jeder Vorstellungskasse, mit oder ohne eigenes Objekt, auch nach dem Objekt ihrer Vorstellung. Dasselbe gilt in
  der Event-Verwaltung, solange der Name einer solchen Kasse in der Zelle bearbeitet wird.

### Prüfungen (KARV-159)

Neues Enum `validation/CashRegisterIssue` mit dem Kürzel `KAS`, eingetragen in `IssueConventionsTest.allEnums`.
Wo nichts anderes steht, ist `&1` die Bezeichnung der Kasse aus `EntityLabels` („Kasse "<Anzeigename>""). Bei
`KAS-004` gelten die Platzhalter von `RangeCheck.TEMPLATE` wie bei `PRS-005`: `&1` = „Ist-Betrag", `&2` = die
Bezeichnung der Kasse, dann Betrag, Minimum und Maximum.

| Code | Stufe | Text | Wann |
|---|---|---|---|
| KAS-001 | Fehler | Der Name einer Kasse darf nicht leer sein. | freie Kasse ohne Namen |
| KAS-002 | Warnung | Der Kassenname &1 kommt im Event mehrfach vor. | zwei sichtbare, nicht verwaiste Kassen mit gleichem Anzeigenamen (ohne Groß-/Kleinschreibung und Rand-Leerzeichen); &1 = der Name |
| KAS-003 | Warnung | &1 gilt für keine Vorstellung. | freie Kasse ohne „alle" und ohne vorhandene ausgewählte Vorstellung |
| KAS-004 | Fehler | `RangeCheck.TEMPLATE` | wirksamer Ist-Betrag einer sichtbaren Kasse außerhalb von 0 bis 100 000 000 Cent |
| KAS-005 | Warnung | Für &1 liegt aus einer älteren Programmversion ein zweiter Ist-Betrag von &2 vor. Es gilt der Stand der Kasse. | Regel „Zweiter Ist-Betrag" im Modell; &2 = der Betrag in Euro |

- `KAS-002` ist nur eine Warnung, wie doppelte Vorstellungsnamen (`EVT-001`): Gleiche Namen entstehen ohne Zutun
  des Bedieners (zwei gleichnamige Vorstellungen im Bestand, zwei Rechner legen gleichzeitig „Neue Kasse" an), und
  ein Fehler würde jedes Speichern des Events sperren, auch im Kartenverkauf.
- Verwaiste Kassen nehmen an `KAS-002` und `KAS-003` nicht teil. Die Liste kennzeichnet sie schon, und bis KARV-146
  könnte der Bediener nichts gegen eine Warnung tun, die bei jedem Speichern wiederkäme.
- `PRS-005` wird nicht mehr ausgegeben und bleibt reserviert, nach dem Muster von `PRS-001`; den Ist-Betrag prüft
  `KAS-004`, auch solange er in den alten Feldern steht.
- Die Prüfungen hängen am `EventValidator` (die Namensgleichheit als Vergleich unter Geschwistern dort, der Rest in
  einem `CashRegisterValidator`).
- Kassen, deren Einheit gesperrt ist oder wartet und die keinen lesbaren Stand haben, nehmen an `KAS-004` und
  `KAS-005` nicht teil; die alten Felder ihrer Vorstellung werden dann nicht geprüft. Die Prüfung kennt den
  Versionsspeicher nicht und erfährt die Kennungen über einen Haken wie `PresentationValidator.useHallLocks`, den
  die Anwendung beim Start setzt.

### Tests

- Modell: wirksamer Stand mit und ohne Kassen-Objekt; zum Ändern holen leert die alten Felder; eigene Daten;
  sichtbare Kassen in beiden Stellungen des Schalters; Löschen einer Vorstellung mit und ohne Ist-Betrag;
  `CopyFromTest`, `JsonKeysTest`, `FormatGuardTest`, `UnknownFieldsTest` um `CashRegister` und `cashRegisters`
  ergänzt.
- `UnitKeyTest`: Pfad, Eltern-Einheit, `parse` und abgewiesene Namen der neuen Art.
- Zerlegen und Zusammensetzen: Rundlauf eines Events mit allen drei Arten von Kassen; für eine Vorstellungskasse
  ohne eigene Daten wird keine Einheit angelegt; eine gespeicherte Vorstellungskasse, deren Ist-Betrag auf 0 gesetzt
  wird, wird als Änderung geschrieben und nicht gelöscht; ein geladenes, unverändertes Event mit Kassen gilt nicht
  als geändert, und Speichern schreibt nichts.
- `requires`: Der Kopf wird mit der ersten Kassen-Einheit mitgeschrieben und trägt das Merkmal; er verliert es in
  dem Speichervorgang, der die letzte löscht; ein Kopf mit Merkmal ohne bekannte Kassen-Einheit behält es bei jedem
  Speichern (Rechner nach dem Update), und der Zustand „Kassen noch nicht abgeglichen" ist gemeldet; eine
  Kassen-Einheit, die nur im Versionsspeicher steht (gesperrt, zurückgestellt), hält es; ein Kopf ohne Merkmal bei
  gespeicherter Kasse wird beim nächsten Speichern berichtigt; ein gesperrter Kopf lässt das Speichern einer Buchung
  darunter weiter zu; `"requires": []` führt zu keiner Version; `requires` allein macht den Kopf nicht „lokal
  verändert".
- Ein Kassen-Objekt ohne eigene Daten und ohne Einheit gilt nicht als lokal verändert und nach dem Speichern nicht
  als angezeigt.
- Schutztests, die mit den neuen Zweigen angepasst werden: `ArchitectureGuardTest` (Zahl der `treeToValue`-Aufrufe
  je Methode, etwa `StoreEventRepository#applyFields`), `FieldGroupsTest` (der Kopf ist das Event ohne
  `presentations` und `cashRegisters`; Gruppen der Kasse).
- Bestand aus v1.1: Alle vorhandenen Formatmuster laden unverändert (`FormatSamplesTest`); ein Event mit
  Ist-Betrag in den alten Feldern zeigt denselben wirksamen Stand, und Speichern ohne Änderung schreibt nichts. Die
  erste Änderung am Ist-Betrag schreibt Kasse, Vorstellung (alte Felder leer) und Kopf (mit `requires`) in einem
  Speichervorgang.
- Sperre: Eine Inhaltsregel ohne das Merkmal `cashRegisters` nennt einen Kopf mit `requires: ["cashRegisters"]`
  inhaltlich zu neu.
- Konfliktregel: je Feldgruppe; Löschung gegen eine Seite, die gezählt hat, in beiden Rollen (die Zählung bleibt;
  `UNMERGED`, wenn „Meine" die Löschung ist, sonst kein Vermerk); Löschung gegen eine Seite, die nur umbenannt hat
  (die Löschung gewinnt).
- Zwei Rechner (`TestSyncServices`): Änderungen an zwei verschiedenen Kassen kommen ohne Konflikt an; eine fremde
  Kasse erscheint im Modell eines geöffneten Events.
- Wiederherstellen: auf einen Stand vor dem Anlegen einer Kasse (Kasse bleibt); auf einen Stand mit anderem
  Ist-Betrag in den alten Feldern (heutiger Wert bleibt); eines gelöschten Events mit Kassen (Kassen sind wieder da,
  keine neue Kassen-Version); auf einen Stand, in dem eine heute lebende Vorstellung fehlt, einmal mit Ist-Betrag
  nur in den alten Feldern und einmal mit gespeicherter Kasse (beide Male steht die Kasse danach verwaist mit Betrag
  und Namen da); mit gesperrter Kassen-Einheit (wird nicht abgelehnt, die Einheit bleibt unberührt); die zweite
  Zählung derselben Kasse erscheint nicht als Speicherstand, die erste erscheint; die Liste „seit diesem Stand
  gezählt".
- Prüfungen: je Code ein Fall; zwei gleichnamige Vorstellungen aus einem Bestand von v1.1 sperren das Speichern
  nicht; dazu `IssueConventionsTest`. `PresentationAndTableValidatorTest` erwartet heute `PRS-005` und wird
  umgestellt.
- Abgebrochener Speichervorgang (`FaultyFileLayer`): Fehler nach der Kassen-Einheit bei der ersten Zählung eines
  Bestands aus v1.1; nach dem Neuladen gilt die Kasse, und `KAS-005` nennt den Betrag der alten Felder.
- „Kassen noch nicht abgeglichen": gemeldet bei Kopf mit Merkmal ohne Kassen-Einheit vor dem ersten Vollabgleich;
  nicht im Einzelbetrieb; endet mit dem ersten abgeschlossenen Vollabgleich (mit Meldung an die Listener) und mit
  dem Eintreffen einer Kasse; Löschen einer Vorstellung und Wiederherstellen werden solange abgelehnt, auch für
  ein gelöschtes Event, dessen letzter lebender Kopf das Merkmal trug; ein scheiternder Abschnitt der Kassen im
  Vollabgleich stellt die Auflösung der übrigen Einheiten des Events nicht zurück.
- Das Merkmal bleibt, wenn der Rechner keine Kasse des Events kennt: am gemeinsamen Ordner, wenn der Kopf vor der
  Kasse eintrifft, und im Einzelbetrieb nach dem Trennen vor dem ersten Vollabgleich.
- Reihenfolge: Bei der ersten Zählung eines Bestands aus v1.1 steht der Kopf vor der Kasse und die Kasse vor der
  Vorstellung; beim Löschen der letzten Kasse steht der Kopf nach ihr. Abbruch nach jeder Stelle ergibt einen der
  im Entwurf genannten Zustände.
- Gesperrte Kassen in der Event-Verwaltung: Umbenennen, „gilt für" und Löschen werden vor der Änderung am Modell
  abgelehnt; eine gesperrte gemeinsame Kasse gilt als gewählt; das Event bleibt speicherbar.
- Wiederherstellen einer Vorstellung, deren Ist-Betrag in 1.2 auf 0 gesetzt und die danach gelöscht wurde: Die
  alten Felder kommen mit 0 zurück, nicht mit dem Betrag des Zielstands.
- `FormatSampleGenerator` (`src/test/.../store/`) erzeugt für das Formatmuster 1.2 auch Kassen: je eine der drei
  Arten, eine Vorstellung mit Ist-Betrag nur in den alten Feldern und den Kopf mit `requires`. Sonst deckt das
  Muster, das nach `docs/decisions.md`, „Release steps", damit entsteht, die neue Einheit nicht ab.
- Oberfläche, soweit ohne Toolkit prüfbar: Zeilen und Texte des Abschnitts „Kassen", Regeln für Schalter und
  Löschen.

### Dokumentation und Patchnotes

- `docs/decisions.md`: neuer Abschnitt zu Kassen als eigener Einheit (Speicherort, gedachte Vorstellungskasse,
  Sperre über `requires`, Wiederherstellen, alte Felder); in „Forward-compatible data format" das erste bekannte
  Merkmal und die drei Punkte aus „Abweichungen von `docs/decisions.md`" als geänderte Regeln.
- `docs/architecture.md` (Modell, Einheiten, Ordnerbild, Wiederherstellen), `docs/structure.md`, `docs/testing.md`,
  `docs/usage.md` (Event-Verwaltung, Kassenabgleich, „Restore points", Tabelle der Kürzel).
- Patchnotes 1.2.0, vorläufig: ein Eintrag unter „Neu" zu den Kassen in der Event-Verwaltung und einer dazu, dass
  beim Wiederherstellen eines Events Kassen stehen bleiben. Dazu als Tatsache, ohne Aufforderung zum Aktualisieren:
  Ein Event, in dem eine Kasse gespeichert ist, lässt sich an Rechnern mit Version 1.1 nicht mehr öffnen
  (`.github/CONTRIBUTING.md`, „Versionen": Ob ein Release den gemeinsamen Umstieg verlangt, steht in seinen
  Patchnotes), und am selben Ordner darf kein Rechner mehr mit 1.0.x arbeiten (`docs/decisions.md`, „Boundary to
  1.0.x": 1.0.x kennt `requires` nicht und verwirft es beim Speichern). Den endgültigen Wortlaut gibt der
  Maintainer vor dem Release frei.
- Confluence „Plausibilitätsprüfungen": KAS-001 bis KAS-005; `PRS-005` als nicht mehr ausgegeben.

### Für die Folgetickets vermerkt

- **KARV-199 und KARV-145:** Das Wiederherstellen entfernt Buchungen samt ihren Zahlungen. Sobald Zahlungen eigene
  Einträge mit Kasse sind, ist gesondert zu entscheiden, ob es das darf; die Rückfrage nennt dann die Summe der
  verschwindenden Zahlungen. Eine Kasse, auf die eine Zahlung zeigt, wird angezeigt und zählt in den Summen mit,
  auch wenn sie gelöscht war; das Wiederherstellen schreibt sie nicht. „Leer" heißt ab dann auch: keine Zahlung
  verweist auf die Kasse.
- **KARV-146:** Ist-Betrag für gemeinsame und freie Kassen; Zeitpunkt und Bediener der Zählung stehen schon in
  jeder Version. Der vorläufige Hinweis im Kassenabgleich entfällt.
- **KARV-148:** Grundsatz „Wiederherstellen darf nichts, was von Hand nicht erlaubt wäre": Hinter einen
  abgeschlossenen Kassensturz geht es nicht zurück, solange er nicht wieder geöffnet ist.
- **Neues Ticket in v1.2:** Ein Wiederherstellen ist in den Versionen nicht als solches erkennbar. Soll der
  Kassenbericht (KARV-151) es aufführen, braucht jede so geschriebene Version ein zusätzliches Feld; nachtragen
  lässt es sich nicht.

## Der Umsetzung überlassen

- Namen und Schnitt der Hilfsklassen (`CashRegisters`, `CashRegisterValidator`), solange die Regeln an einer Stelle
  im Modell stehen und ohne JavaFX testbar sind.
- Aufbau des Abschnitts „Kassen" im Einzelnen: Abstände, Spaltenbreiten, die Form der Auswahl für „gilt für"
  (Aufklappliste mit Häkchen oder Dialog), Tastaturbedienung.
- Reihenfolge der Kassen in `eventUnits` und ihr Abschnitt im Vollabgleich.
- Wie die Liste „seit diesem Stand gezählt" zur Rückfrage gelangt (eigene Methode am Repository oder Teil des
  Plans), solange sie nicht auf dem Oberflächen-Thread liest.
- Die Texte der Konfliktliste für die Feldgruppen der Kasse („Name", „gilt für", „Kasse").
- Verhalten des Konfliktverzeichnisses bei einer fremden Löschung des Kopfs: wie bei einer Vorstellung.
- Wie das Repository beim Speichern erfährt, ob der Versionsspeicher Kassen-Einheiten des Events kennt, solange der
  Aufwand je Speichervorgang nicht mit der Größe des Index wächst (`UnitIndex.eventSnapshots` läuft heute über alle
  Einträge; ein Verkauf darf dadurch nicht langsamer werden).

## Abnahme

- In der Event-Verwaltung lässt sich im Bearbeiten-Modus eine freie Kasse anlegen, umbenennen, einer Auswahl von
  Vorstellungen zuordnen und löschen; der Schalter legt die gemeinsame Kasse an und entfernt sie. Nach „Speichern"
  und Neustart ist alles wieder da.
- Ein Bestand aus v1.1 mit Ist-Beträgen zeigt im Kassenabgleich dieselben Soll-, Ist- und Differenzwerte wie in
  1.1.2. Nach dem Laden gilt kein Event als geändert, und im Datenordner ist keine neue Versionsdatei entstanden.
- Nach der ersten Änderung eines Ist-Betrags in 1.2 liegt unter `events/<eventId>/cashRegisters/` eine Einheit, der
  Kopf des Events trägt `requires: ["cashRegisters"]`, und ein Rechner mit 1.1.2 am selben Ordner zeigt das Event
  nicht mehr und meldet SYS-009.
- Zwei Rechner mit 1.2 am selben Ordner sehen dieselben Kassen. Ändern sie gleichzeitig zwei verschiedene Kassen,
  steht danach nichts in der Konfliktliste.
- Wird eine Vorstellung mit gezähltem Ist-Betrag gelöscht, steht ihre Kasse weiter in der Liste, mit „–
  (Vorstellung gelöscht)", und ihr Betrag zählt in der Gesamtbilanz mit.
- Wiederherstellen eines Events auf einen Stand vor einer Zählung: Der Ist-Betrag steht danach unverändert im
  Kassenabgleich, und die Rückfrage nannte die Kasse mit ihrem Betrag. Wird dieselbe Kasse ein zweites Mal gezählt
  und sonst nichts geändert, kommt in der Liste der Speicherstände kein Eintrag dazu.
- Wiederherstellen auf einen Stand, in dem eine inzwischen angelegte und gezählte Vorstellung fehlt: Ihre Kasse
  steht danach mit Betrag und Namen in der Liste, mit „– (Vorstellung gelöscht)".
- Ein Bestand mit zwei gleichnamigen Vorstellungen lässt sich in 1.2 speichern; `KAS-002` erscheint als Warnung.
- Eine gesperrte Kasse lässt sich in der Event-Verwaltung weder umbenennen noch löschen, und das Event bleibt
  speicherbar, auch im Kartenverkauf.
- `KAS-001` bis `KAS-005` erscheinen in den beschriebenen Fällen; `PRS-005` erscheint nicht mehr.
- `./mvnw test` ist grün; die unter „Tests" genannten Fälle sind vorhanden.
- Die unter „Dokumentation und Patchnotes" genannten Stellen sind nachgezogen.

## Prüfvermerk

**Runde 1, 26.11.2026.** Umsetzer: 1 kritisch, 3 groß, 5 klein. Gegenprüfer: 1 kritisch, 2 groß, 5 klein. Die
Ausgangslage hielt bis auf Ergänzungen. Behoben:

- Kritisch (Umsetzer) und groß (Gegenprüfer): Ein Wiederherstellen, das eine heute lebende Vorstellung entfernt,
  hätte einen Ist-Betrag in ihren alten Feldern gelöscht. Neu: Regel „Eine Vorstellung entfällt" gilt auch beim
  Wiederherstellen; die Kasse bleibt als verwaiste Kasse mit Namen.
- Kritisch (Gegenprüfer) und groß (Umsetzer): `KAS-002` als Fehler hätte jedes Speichern gesperrt, sobald Namen
  ohne Zutun doppelt sind. Neu: Warnung, verwaiste Kassen ausgenommen.
- Groß (beide): Die erste Zählung schreibt auch Kopf und Vorstellung und erscheint deshalb als Speicherstand; die
  Abnahme verlangte das Gegenteil. Neu: Regel, Tests und Abnahme sagen, welche Speichervorgänge entfallen.
- Groß (Umsetzer): kein Ausweg für eine verwaiste Kasse mit Betrag und Dauerwarnung `KAS-003`. Neu: als Nicht-Ziel
  bis KARV-146 benannt, `KAS-003` gilt für verwaiste Kassen nicht, eine verwaiste Kasse ohne Betrag und Kommentar
  entfällt.
- Klein, eingearbeitet: `requires` wird am Vergleichsstand abgeleitet und der Kopf ohne `requires` verglichen
  (vorher hing es am Modell und ging verloren, wenn die einzige Kasse dort fehlte); eine geleerte Vorstellungskasse
  wird nicht mehr gelöscht, und die Konfliktregel lässt die Löschung nur gegen eine Zählung verlieren, mit Vermerk;
  „Zum Ändern holen" leert die alten Felder immer; `KAS-005` für einen zweiten Ist-Betrag aus v1.1; gesperrte
  Kassen beim Wiederherstellen (`RestorePlanner.single`); `rebuild`, `applyFields`, `restoreFields`, `EditingUnits`
  und die Gesamtbilanz ohne Vorstellung; Patchnotes zum gemeinsamen Umstieg und zu 1.0.x.

**Runde 2, 26.11.2026** (neues Paar). Umsetzer: 0 kritisch, 1 groß, 5 klein. Gegenprüfer: 0 kritisch, 1 groß, 5
klein. Behoben:

- Groß (Gegenprüfer): Ein Rechner direkt nach dem Update von 1.1 kennt den Kopf mit `requires`, aber noch keine
  Kassen-Einheit; er hätte 0 statt des gezählten Betrags gezeigt und beim nächsten Speichern das Merkmal entfernt.
  Neu: Das Merkmal wird nur in dem Speichervorgang entfernt, der die letzte Kasse löscht; der Zustand „Kassen noch
  nicht abgeglichen" sperrt die Eingabe.
- Groß (Umsetzer), klein (Gegenprüfer): Für „Löschung weicht einer Zählung" gab es keinen passenden Vermerk. Neu:
  `UNMERGED`, wenn „Meine" die Löschung ist, sonst kein Vermerk; keine neue Art, der `store` bleibt unverändert.
- Klein, eingearbeitet: Maßstab für `requires` ist der Stand im Versionsspeicher, nicht der Vergleichsstand; ein
  gesperrter Kopf verhindert kein Speichern darunter; die Ausnahme für leere Vorstellungskassen gilt bei jedem
  Bilden der Einheiten; „heute" beim Wiederherstellen eines gelöschten Events; Ablauf des Dialogs mit der Liste vor
  der Rückfrage; gesperrte Kasse mit lesbarem Stand bleibt im Ziel-Event; `ArchitectureGuardTest` und
  `FieldGroupsTest`; `CashRegister` ohne eigene Gleichheit; `ConflictLabels` in der Ausgangslage genauer.

**Runde 3, 26.11.2026** (neues Paar). Umsetzer: 0 kritisch, 1 groß, 5 klein. Gegenprüfer: 0 kritisch, 1 groß, 5
klein. Die Ausgangslage hielt vollständig. Danach geändert, **von keinem Prüfer mehr gelesen**:

- Groß (Umsetzer): Bricht ein Speichervorgang zwischen den Einheiten ab, konnte ein gezählter Betrag verloren
  gehen oder das Event dauerhaft in „Kassen noch nicht abgeglichen" hängen. Neu: feste Reihenfolge (Kasse vor
  Vorstellung und Kopf), und der Zustand endet mit dem ersten abgeschlossenen Vollabgleich und gilt nie im
  Einzelbetrieb.
- Groß (Gegenprüfer): Der Entwurf wich bei `requires`, beim Ersatz der alten Felder und bei der neuen Art von
  Einheit von `docs/decisions.md` ab, ohne es zu sagen. Neu: Abschnitt „Abweichungen von `docs/decisions.md`" mit
  Begründung und dem Auftrag, die Regeln dort nachzuziehen. Am Entwurf selbst verlangte der Befund keine Änderung.
- Klein, eingearbeitet: der zweite Ist-Betrag wird mit Betrag genannt und erst durch eine Änderung an Ist-Betrag
  oder Kommentar verworfen; Schutz im Kassenabgleich und beim Löschen einer Vorstellung, wenn die Kassen-Einheit
  gesperrt ist; „Vorstellung nicht verfügbar" statt „gelöscht", wenn sie nur im Modell fehlt; Ordnung der Liste
  `cashRegisters`; „Setzen" ohne Löschungen; weitere bekannte Grenzen; `payloadOf`/`payloadsOf`,
  `PresentationAndTableValidatorTest`, Aufwand der Abfrage beim Speichern.

**Nach Runde 3.** Jede Runde meldete noch Großes; die Prüfung endete damit nach `.github/CONTRIBUTING.md`,
„Spec-Prüfung", und der Maintainer entschied am 27.11.2026 für eine vierte Runde, damit die Änderungen nach Runde 3
gelesen werden.

**Runde 4, 27.11.2026** (neues Paar). Umsetzer: 0 kritisch, 1 groß, 4 klein. Gegenprüfer: 0 kritisch, 0 groß, 5
klein; er hat die Änderungen nach Runde 3 am Code nachgeprüft und hält sie für tragfähig. Danach geändert, **von
keinem Prüfer mehr gelesen**:

- Groß (Umsetzer): In der Event-Verwaltung fehlte der Schutz für gesperrte Kassen; eine Umbenennung oder das
  Abschalten einer gesperrten gemeinsamen Kasse hätte jedes Speichern des Events scheitern lassen, auch im
  Kartenverkauf. Neu: Abschnitt „Gesperrte Kassen" in der Event-Verwaltung, geprüft vor der Änderung am Modell; eine
  gesperrte gemeinsame Kasse gilt als gewählt.
- Klein (beide), eingearbeitet: Reihenfolge im Speichervorgang neu (Kopf vor Kasse, Kasse vor Vorstellung; beim
  Entfernen des Merkmals Kopf zuletzt), weil nach der Fassung aus Runde 3 ein Abbruch nach der Kasse v1.1 ein Event
  mit altem Ist-Betrag öffnen ließ; „Kassen noch nicht abgeglichen" meldet sein Ende und sperrt auch Löschen einer
  Vorstellung und Wiederherstellen; alte Felder einer zurückgeholten Vorstellung aus ihrem letzten lebenden Stand;
  zweiter Ist-Betrag lässt sich verwerfen, auch bei gemeinsamer Kasse; Exporte aus dem wirksamen Stand, „–" bei
  gemeinsamer Kasse; Ausgangslage zum Vollabgleich berichtigt; `FormatSampleGenerator`.

**Runde 5, 27.11.2026** (neues Paar, vom Maintainer verlangt). Umsetzer: 0 kritisch, 0 groß, 5 klein. Gegenprüfer:
0 kritisch, 1 groß, 4 klein; er hat die Ausgangslage Zeile für Zeile nachgeprüft und keine Abweichung gefunden.
Danach geändert, **von keinem Prüfer mehr gelesen**:

- Groß (Gegenprüfer): Ein Rechner mit v1.1 kann ein in 1.2 gelöschtes Event mit Kassen auf einen Stand vor der
  ersten Kasse wiederherstellen und zeigt dann veraltete Ist-Beträge. Eine Löschung trägt im Versionsspeicher
  keinen Inhalt und damit kein `requires` (`StoreCodec` Zeile 60), technisch verhindern ließe es sich nur mit einer
  Änderung an der Hülle der Versionen. Wie vom Prüfer als Mindestes genannt: als bekannte Grenze aufgenommen, das
  Ziel eingeschränkt. 1.2 heilt den Zustand beim nächsten Speichern, `KAS-005` zeigt die alten Beträge.
- Klein, eingearbeitet: Kassen-Einheiten stehen auch vor den Löschungen beim Wiederbeleben eines Kopfs; einheitliche
  Bedingung für „Kassen noch nicht abgeglichen" (auch gelöschte Einheiten zählen als angekommen, der Abschnitt der
  Kassen meldet ein Scheitern mit dem Event); neue Regel „Entfernen ohne Kasse", damit der Zustand nicht nach jedem
  Start wiederkehrt; eine gesperrte Kasse ohne lesbaren Stand ersetzt ihre Zeile statt eine zweite zu bilden und
  zeigt im Kassenabgleich „–"; eine gesperrte Kasse einer entfallenden Vorstellung wird beim Wiederherstellen nicht
  zum Ändern geholt; „in Bearbeitung" für Kassen ohne Objekt über die Vorstellung; eine gesperrte Kasse verhindert
  das Löschen des Events; Übertragung in anderer Reihenfolge als bekannte Grenze.

**Runde 6, 27.11.2026** (neues Paar; der Maintainer hat verlangt, Runden zu wiederholen, bis keine mehr Kritisches
oder Großes findet). Umsetzer: 0 kritisch, 1 groß, 4 klein. Gegenprüfer: 0 kritisch, 1 groß, 5 klein. Behoben:

- Groß (Umsetzer): Die in Runde 5 neue Regel „Entfernen ohne Kasse" hätte am gemeinsamen Ordner die Sperre entfernt,
  wenn der Kopf vor der Kasse ankommt. Neu: Die Regel gilt nur im Einzelbetrieb; am gemeinsamen Ordner bleibt das
  Merkmal, und der wiederkehrende Zustand nach einem abgebrochenen ersten Speichern ist eine bekannte Grenze.
- Groß (Gegenprüfer): „Event vollständig abgeglichen" hätte das Event in `skippedTrees` gebracht und die Auflösung
  aller seiner Einheiten zurückgestellt. Neu: Der Abschnitt der Kassen läuft ohne Teilbaum wie der des Kopfs, und
  der Zustand „Kassen noch nicht abgeglichen" endet mit dem ersten Vollabgleich, der bis zum Ende gelaufen ist.
- Klein, eingearbeitet: Bedingung für „Kassen noch nicht abgeglichen" am letzten lebenden Stand eines gelöschten
  Kopfs, damit auch das Wiederherstellen gelöschter Events gesperrt ist; Gesamtbilanz zeigt in diesem Zustand „–";
  `EventMerge.subtree` und `ConflictActions.inUse` kennen Kassen; die Gruppe `requires` erscheint nicht in der
  Konfliktliste; Rücksetzen eines beschädigten Kopfs als weiterer Weg zum Verlust des Merkmals; „lebt" bei
  gesperrten Kassen-Einheiten; Text für eine gesperrte verwaiste Kasse; gezählte Vorstellungskassen nach dem
  Umschalten auf die gemeinsame Kasse.

**Runde 7, 27.11.2026** (neues Paar). Umsetzer: 0 kritisch, 1 groß, 4 klein. Gegenprüfer: 0 kritisch, 0 groß, 5
klein. Behoben:

- Groß (Umsetzer), klein (Gegenprüfer): Auch auf den Einzelbetrieb beschränkt, entfernte „Entfernen ohne Kasse" die
  Sperre an einem Rechner, der vor seinem ersten Vollabgleich getrennt wurde, und trug das beim erneuten Verbinden
  in den Ordner. Neu: Die Regel ist gestrichen. Das Merkmal fällt nur noch in dem Speichervorgang, der die letzte
  Kasse löscht; der Rest steht unter den bekannten Grenzen.
- Klein, eingearbeitet: „in Bearbeitung" für jede Vorstellungskasse über ihre Vorstellung, auch mit eigenem Objekt;
  wartende Kassen-Einheiten wie gesperrte im Kassenabgleich und beim Wiederherstellen; Auflisten der Kassen im
  Vollabgleich in einem eigenen `try`; `comparable` entfernt am Kopf `cashRegisters` und `requires`; ein
  FIELDS-Eintrag nur mit `requires` entfällt; gesperrte verwaiste Kasse ohne lesbaren Stand erscheint immer;
  `KAS-004` und `KAS-005` übergehen Kassen ohne lesbaren Stand; Differenz „–" in der Gesamtbilanz im Zustand
  „Kassen noch nicht abgeglichen".

**Runde 8, 27.11.2026** (neues Paar). Umsetzer: 0 kritisch, 0 groß, 4 klein. Gegenprüfer: 0 kritisch, 0 groß, 5
klein; er hat die Ausgangslage am Code nachgeprüft und keine Abweichung gefunden (`release/1.2.0` steht inzwischen
auf 26fd213 mit KARV-140; das berührt keine Stelle, die das Spec nennt). **Erste Runde ohne kritischen oder großen
Befund; die Prüfung ist damit beendet.** Danach eingearbeitet, nur kleine Befunde und von keinem Prüfer mehr
gelesen: `sameAsModel` beim Schreiben vom Vergleich für „lokal verändert" getrennt; `EventHeadPolicy` nimmt
`requires` aus dem Feldvergleich und setzt es, wenn eine Seite es trägt (vorher ein Vermerk, den die Fußzeile ohne
Eintrag gezählt hätte); Offline-Betrieb und gescheitertes Auflisten der Kassen bei „Kassen noch nicht
abgeglichen"; Löschen einer Halle an v1.1 als bekannte Grenze; wartende Kassen in der Event-Verwaltung wie
gesperrte; gezählte Vorstellungskasse bei gemeinsamer Kasse zeigt ihren Stand gesperrt; zweiter Ist-Betrag beim
Löschen einer Vorstellung ausdrücklich hingenommen.

**Ergebnis.** Acht Runden; die achte meldete nichts Kritisches oder Großes. Kein Befund ist strittig: Alle
kritischen und großen Befunde sind im Text behoben oder, beim großen Befund der Runde 5, wie vom Prüfer
vorgeschlagen als bekannte Grenze benannt; keiner wurde abgelehnt oder herabgestuft. Die großen Befunde der Runden
2 bis 7 lagen in zwei Teilen: Sperre für v1.1 mit dem Übergang der alten Felder (Runden 2, 3, 5, 6 und 7) und
gesperrte Kassen (Runde 4).

**Nicht belegt:**

- Das Verhalten von v1.1 ist am Quelltext der Tags 1.1.0 bis 1.1.2 gelesen (`store`, `repository` und `model` sind
  zwischen 1.1.2 und b155b57 gleich), nicht an einem laufenden Programm erprobt: SYS-009 am Event, Übergehen des
  Ordners `cashRegisters`, Vollabgleich je Listeneintrag.
- Wie lange der erste Vollabgleich nach einem Update dauert, also wie lange „Kassen noch nicht abgeglichen"
  anhält.
- Ob sich der Abschnitt „Kassen" in JavaFX wie beschrieben bedienen lässt (Eingabe in der Zelle, Auswahl für
  „gilt für").
- Verhalten von 1.0.x gegenüber Kassen; es ist nach `docs/decisions.md` ohnehin ausgeschlossen.

**Freigabe:** vom Maintainer im Gespräch am 27.11.2026 erteilt (`.github/CONTRIBUTING.md`, „Freigabe").
