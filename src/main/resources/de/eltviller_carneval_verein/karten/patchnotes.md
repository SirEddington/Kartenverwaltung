# Patchnotes

## 1.2.0

### Neu
- Die Abrechnung wird ausgebaut, mit Kassen am Event, Kassensturz je Kasse und Bilanz.
- In der Event-Verwaltung gibt es unter den Tabellen den Abschnitt „Kassen“. Wie bisher hat jede
  Vorstellung ihre eigene Kasse. Stattdessen lässt sich eine gemeinsame Kasse für alle
  Vorstellungen wählen, und mit „+ Kasse hinzufügen“ kommen weitere Kassen mit eigenem Namen
  dazu, etwa ein Vorverkauf. Kassen lassen sich umbenennen; freie Kassen lassen sich auch wieder
  löschen, solange nichts gezählt ist. Der Kassenabgleich zeigt weiter dieselben Beträge wie bisher.
  Gezählt wird vorerst nur die Kasse einer Vorstellung; für die gemeinsame Kasse folgt der
  Kassensturz in einer späteren Version.
- Beim Wiederherstellen eines Events bleiben die Kassen, wie sie sind: Was gezählt wurde, bleibt
  gezählt. Die Rückfrage nennt die Beträge, die seit dem gewählten Stand gezählt wurden; zeigt der
  Kassenabgleich danach eine Differenz, sind Verkäufe nachzutragen.
- Ein Event, in dem eine Kasse gespeichert ist, lässt sich an Rechnern mit Version 1.1 nicht mehr
  öffnen. Am selben gemeinsamen Ordner darf kein Rechner mehr mit Version 1.0.x arbeiten.
- Buchungen lassen sich im Bearbeitungsmodus auf andere Plätze derselben Vorstellung verschieben
  oder mit anderen tauschen, auch mehrere auf einmal: Rechtsklick auf die Buchung, dann
  „Buchung verschieben…“.

### Verbessert
- Die Saalansicht öffnet schneller, auch beim ersten Mal nach dem Start. Auch der Wechsel zwischen
  den Ansichten des Programms geht etwas zügiger.

## 1.1.2

### Neu
- Im Kartenverkauf steht über dem Auswahlfeld „Vorstellung“ jetzt immer, wie viele Karten noch frei
  sind, zum Beispiel „frei: 42 von 180“. Bei „Alle Vorstellungen“ ist es die Summe; fährt man mit
  der Maus darüber, steht dort die Zahl je Vorstellung. Gesperrte Plätze und Buchungen ohne Platz
  zählen nicht mit.

### Verbessert
- Ist eine Karte für 0 € als bezahlt markiert, erscheint die Warnung „… ist als bezahlt markiert,
  der Preis beträgt aber 0,00 €“ (SEA-006) nicht mehr: Bei 0 € ist nichts zu bezahlen.

### Behoben
- Verkaufen zwei Kassen denselben Platz, bevor der Abgleich den ersten Verkauf gebracht hat,
  bleiben jetzt auch dann beide Buchungen erhalten, wenn der Platz vorher schon einmal gebucht war,
  etwa weil beide Kassen ihn storniert und neu verkauft haben oder weil er noch einen alten
  Kommentar trug: Eine behält den Platz, die andere steht unter „Ohne Platz“, und ihre Kasse
  bekommt einen Hinweis. Bisher verschwand die zweite Buchung in diesem Fall ohne Hinweis.
- In seltenen Fällen konnte beim Abgleich zweier Kassen eine Buchung ohne Hinweis wegfallen oder
  überschrieben werden: wenn die gespeicherten Daten eines Platzes beschädigt waren oder aus einer
  neueren Programmversion stammten. Jetzt bleibt die Buchung in diesen Fällen erhalten, oder die
  betroffene Kasse bekommt einen Hinweis.

## 1.1.1

### Neu
- Beim Auswählen von Plätzen steht neben dem Mauszeiger, wie viele Karten gewählt sind und was sie
  zusammen kosten, in der Saalansicht und in der Tabelle. Im Menü „Anzeige“ unter „Auswahl am
  Mauszeiger“ wählen Sie, ob der Hinweis über oder unter dem Mauszeiger steht, oder schalten ihn ab.
  Ohne „Am Rand ausweichen“ springt er am Rand nicht auf die andere Seite des Mauszeigers. Die
  Saalansicht zeigt beim Überfahren eines Platzes dafür keinen Tooltip mit Platz und Status mehr.
- Über dem Suchfeld steht, wie viele Treffer die Tabelle bzw. die Saalansicht gerade zeigt. Fährt
  man mit der Maus darüber, steht dort, wie viele davon auf welchen Namen gebucht sind, bei „Alle
  Vorstellungen“ auch je Vorstellung. So lässt sich schnell prüfen, ob ein Kunde die richtige
  Anzahl Karten hat.

### Behoben
- Im Anzeigemodus übernimmt die Saalansicht keine Auswahl mehr aus der Tabelle. Bisher blieben
  die Plätze dort markiert und ließen sich erst im Bearbeitungsmodus wieder abwählen.
- Ist die Kartentabelle sortiert oder gefiltert, bleiben nach dem Wechsel aus der Saalansicht und
  nach Änderungen anderer Kassen dieselben Buchungen gewählt. Bisher konnte die Auswahl auf andere
  Plätze springen, und „Verkaufen“ oder „Stornieren“ traf dann die falschen.
- Unter Linux erkennt die Kartenverwaltung jetzt auch, wenn ein Nextcloud-Ordner keine Verbindung
  zum Server hat. Ohne Internet bleiben Verkäufe dann vorläufig, statt als hochgeladen zu gelten.
- Unter Linux bekommt eine neu eingerichtete Kasse den Namen des Computers statt „Rechner“. Kassen,
  die schon „Rechner“ heißen, benennen Sie unter Einstellungen, „Gemeinsamer Ordner“, mit „Ändern“
  um.

## 1.1.0

### Neu
- Die Kartenverwaltung sucht selbst nach Updates. Gibt es eine neue Version, erscheint ein Hinweis
  in der Fußzeile. Aktualisiert wird nur, wenn Sie zustimmen.
- Kassen ohne eigenes Internet können über den gemeinsamen Ordner aktualisieren: Eine Kasse mit
  Internet legt das Update dort ab, die anderen bieten es dann an.
- Nutzt eine andere Kasse am gemeinsamen Ordner schon eine neuere Version, weist die Fußzeile
  darauf hin. Unter Einstellungen, „Info und Updates“, stehen alle Kassen mit ihrer Version.
- Nach einem Update zeigt die Kartenverwaltung einmal, was sich geändert hat. Unter Einstellungen,
  „Info und Updates“, lässt sich das jederzeit wieder ansehen. Vor einem Update führt der Link
  „Was ist neu in Version …?“ zur Beschreibung der neuen Version.
- Unter Einstellungen, „Info und Updates“, stehen die installierte Version und der Stand der
  Update-Prüfung.
- Die Saalansicht zeigt den Sitzstatus nicht mehr nur über die Farbe: Freie Plätze sind weiß mit
  grünem Ring, reservierte gelb gestreift, verkaufte rot, gesperrte grau mit Kreuz. Eine Legende
  unter dem Plan erklärt das. So bleiben die Plätze auch bei Rot-Grün-Schwäche gut zu
  unterscheiden. Im Menü „Anzeige“ lässt sich das unter „Barrierefreie Sitzdarstellung“
  ausschalten.
- Die Auswahl im Menü „Anzeige“ (Reihen, Tische, Platznummern, Hallenobjekte, barrierefreie
  Sitzdarstellung, Lage der Kaufzusammenfassung) gilt gemeinsam für Saalansicht, Kundenfenster und
  Hallen-Editor und bleibt nach einem Neustart erhalten. Abschalten lässt sich das unter
  Einstellungen, „Anzeige“.
- Tische lassen sich beschriften wie Hallenobjekte: Text, Größe, Ausrichtung und Drehung.
- „Reihe füllen…“ fragt auch Tischbreite, Tischhöhe und Sitzdurchmesser ab. Maße und Beschriftung
  lassen sich für alle Tische ganzer Reihen auf einmal ändern.
- Der Hallen-Editor warnt, wenn sich Tische oder Hallenobjekte überlappen oder über den Rand der
  Halle ragen. Speichern bleibt trotzdem möglich.

### Verbessert
- Sitze sind Kreise mit einem Durchmesser und stehen gleichmäßig verteilt am Tisch, auch dort, wo
  zwei Tische aneinanderstoßen.
- Neue Bestuhlungen beginnen mit Tischen von 50 x 320 cm und Sitzen mit 45 cm Durchmesser.

### Behoben
- Ein Tisch bleibt an seinem Platz, wenn er eine andere Nummer bekommt. „Nummern neu vergeben“
  nummeriert nach dem Platz.
- Die Kartenverwaltung startet zuverlässig, auch wenn ein Virenscanner wie Norton aktiv ist.

### Hinter den Kulissen
- Daten, die eine neuere Version geschrieben hat, bleiben beim Speichern erhalten. Einträge, die
  eine neuere Version brauchen, bleiben gesperrt, bis diese Kasse aktualisiert ist.
- Gespeicherte Verkäufe überstehen auch einen Stromausfall direkt nach dem Speichern.
- Alle Kassen, die denselben gemeinsamen Ordner nutzen, bitte gleichzeitig auf 1.1 aktualisieren
  und danach jede Halle mit Bestuhlung einmal im Hallen-Editor öffnen und speichern.
