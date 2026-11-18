# Langfristige Roadmap

Stand: 18.11.2026, ausgehend von Release 1.1.0.

Dieses Dokument legt die **Reihenfolge** der längerfristigen Vorhaben fest und begründet sie. Es ist kein
Umsetzungsplan: Jede Stufe bekommt einen eigenen Spec unter `docs/specs/`, wenn sie an der Reihe ist. Der Ablauf dafür
steht in `.github/CONTRIBUTING.md`. Die offenen Fragen je Stufe sind schon gesammelt.

Die Versionsnummern folgen den Fix-Versionen in Jira (Projekt KARV, abgeglichen am 16.11.2026). Die Roadmap nennt
Themen, keine einzelnen Tickets. Zu welcher Stufe ein Ticket gehört, steht in Jira an der Fix-Version. Kleinere Punkte
kommen über neue Tickets dazu und brauchen hier keinen Eintrag.

## Absicht

Das Programm ist als reines ECV-Projekt entstanden und läuft. Was der ECV-Alltag bisher verlangt, steckt in
Release 1.2. Darüber hinaus gibt es eine Liste von Ideen ohne Termin (Archivierung, Warteliste, Kundenmails,
Bestellannahme, Customizing, Übersetzung, Konten und Rechte, Export, Stehplätze, Ausbau der Saalplanung, Rückgängig
und Wiederholen, Serverbetrieb mit Self-Hosting, Weitergabe an
andere Vereine). Die Roadmap ordnet diese Liste so, dass nichts doppelt angefasst und nichts auf Verdacht gebaut
wird.

## Ziele

- Der ECV bleibt der erste und wichtigste Nutzer. Sein Bedarf geht vor.
- Das Programm wird Schritt für Schritt so, dass ein anderer Verein es ohne Eingriff in den Code nutzen kann.
- Jedes Release bringt etwas, das man an der Kasse oder in der Verwaltung sieht.
- Der Serverbetrieb kommt als **Zusatz**: Was ohne Server funktionieren kann, funktioniert weiter ohne Server.

## Nicht-Ziele

- Termine. Die Roadmap legt nur die Reihenfolge fest.
- Ein Geschäftsmodell. Wie das Programm oder ein Service Geld einbringt, ist bewusst offen (siehe unten).
- Ein Online-Ticketshop für Endkunden.

## Anti-Ziele

- Ein Verein, der heute ohne Server arbeitet, wird durch ein späteres Release gezwungen, einen zu betreiben.
- Umbauten für Features, die dann nie kommen.
- Ein Release, das die Zusagen des Datenformats bricht (unbekannte Felder überleben, nichts geht verloren,
  Arbeitsplätze mit älterer Version beschädigen keine Daten).

## Rahmenbedingungen

- Ein Entwickler, Freizeitprojekt. Der Code gehört dem Entwickler, Lizenz GPL-3.0.
- Die Bauweise bleibt: Desktop-App, vollständige Daten auf jedem Arbeitsplatz, Austausch über einen gemeinsamen
  Ordner, unveränderliche Versionsdateien (`docs/decisions.md`).
- Ehrenamtliche bedienen das Programm. Neue Funktionen dürfen den Verkauf nicht komplizierter machen.

## Wege

| Weg | Idee | Vorteil | Nachteil |
|---|---|---|---|
| A – Nutzen zuerst | Features in der Reihenfolge ihres sichtbaren Nutzens, Umbauten erst wenn erzwungen | jedes Release sichtbar | Umbauten unter Druck und mehrfach |
| B – Fundament zuerst | ein Release nur mit den Umbauten, dann Features | technisch am saubersten | ein Release ohne sichtbaren Gewinn, Vorbau auf Verdacht |
| **C – Paarweise** | je Release ein Umbau mit dem Feature, das ihn braucht | kein Umbau auf Verdacht, jedes Release sichtbar | Umbauten verteilen sich über mehrere Releases |

**Gewählt: C** (Entscheidung des Entwicklers am 13.11.2026).

Die drei Umbauten, um die es geht:

1. **Löschen können.** Der Versionsspeicher bewahrt jede Fassung für immer auf. Namen und E-Mail-Adressen liegen
   schon heute darin (`Booking.lastName`, `firstName`, `eMail`).
2. **Bestellung und Kunde als eigenes Ding.** Heute ist eine Buchung genau ein Platz, der Kunde steht an jedem Platz
   einzeln.
3. **Texte auslagern.** Alle Texte stehen fest auf Deutsch in 17 FXML-Dateien und im Java-Code. Es gibt keine
   Sprachdateien.

Nicht jede Stufe braucht einen Umbau. 1.2 und 1.4 kommen ohne aus: Sie bündeln, was der Alltag verlangt und an keinem
der drei Umbauten hängt.

## Versionsnummern

Das Versionsschema steht in [.github/CONTRIBUTING.md](../.github/CONTRIBUTING.md), Abschnitt „Versionen": Die
Version ist dreiteilig, und was die zweite von der dritten Stelle trennt, ist die Frage, ob alle Rechner eines
gemeinsamen Ordners zusammen umsteigen müssen. Warum es keine vierte Stelle gibt, steht in
[decisions.md](decisions.md#development-workflow). 1.0.1 (Offline-Erkennung) passt bereits in dieses Schema.

Die Stufen unten sind Releases der zweiten Stelle (1.2 steht für 1.2.0). In Jira heißen sie v1.2, v1.3 und so weiter.
Ein Zwischenrelease der dritten Stelle bekommt bei Bedarf eine eigene Fix-Version, zum Beispiel v1.1.1. Ein Kandidat
dafür ist die Offline-Erkennung unter Linux (siehe unten).

## Stufen

### 1.2 – ECV-Alltag (in Planung, nicht Teil dieser Roadmap)

Kassen am Event anlegen und verwalten, Kassenbewegungen (Einlage, Entnahme), Kassensturz je Kasse mit Soll/Ist für
alle Zahlungsmittel und mit Abschluss (gezählt am, von), Bilanz je Vorstellung und Gesamtbilanz des Events mit einer
Zeile je Kasse, Erstattung beim Storno, Export für die Kassenprüfer (Kassenbericht und Einzelnachweis), Buchungen auf
andere Sitze verschieben, Preis je Kategorie und Vorstellung. Wenn Platz bleibt: Auswertung nach Tag.

Dazu als Pflege: Die Saalansicht öffnet auch beim ersten Mal nach dem Start ohne spürbares Einfrieren.

Bezug zur Roadmap: Die Kasse ist die Vorstufe der späteren Rollen ("wer sitzt an welcher Kasse" → "wer darf was").

### 1.3 – Archivierung und Export (Umbau: Löschen können)

**Inhalt**
- Archivierung abgeschlossener Events. Ein `archived`-Kennzeichen am Event gibt es bereits. Hallen und Bestuhlungen,
  die nicht mehr gebraucht werden, lassen sich ebenfalls archivieren statt löschen.
- Löschkonzept: Kundendaten eines Events lassen sich endgültig entfernen oder unkenntlich machen.
- Aufbewahrung: Alte Versionen und Sicherungen werden nach einer gemeinsamen Regel aufgeräumt. Wie lange Sicherungen
  bleiben, ist einstellbar.
- Export von Hallenplänen (PDF oder Bild) und von Buchungen (Liste je Vorstellung, Türliste je Tisch).

**Warum zuerst:** Es ist der kleinste der drei Umbauten, hängt von keinem anderen ab und wird mit jeder Saison
dringender, weil der Datenbestand nur wächst. Der Export ist das sichtbare Gegenstück und für sich nützlich. Das
Aufräumen alter Versionen gehört dazu, weil es denselben Mechanismus braucht wie das Löschen von Kundendaten: eine
Regel, die jeder Arbeitsplatz gleich anwendet.

**Offen für den eigenen Spec**
- Löschen in einem Speicher, der bewusst nie löscht: Wie erfahren alle Arbeitsplätze davon, und wie wird verhindert,
  dass ein Arbeitsplatz die gelöschten Dateien wieder hochlädt? Sync-Clients geben Löschungen nicht zuverlässig weiter.
  Für das Aufräumen alter Versionen ist schon eine Löschliste im gemeinsamen Ordner vorgesehen. Das Löschen von
  Kundendaten kann denselben Mechanismus nutzen. Das Aufräumen löscht aber nach Alter und behält die aktuelle Version
  immer. Für Kundendaten reicht das nicht.
- Löschen oder unkenntlich machen? Die Abrechnung muss stimmig bleiben.
- Was ist ein Archiv: ein Zustand im Speicher oder eine ausgelagerte Datei?
- Archivierte Hallen und Bestuhlungen: in den Auswahllisten ausblenden, bereits zugewiesene weiter anzeigen. Bleibt
  das Löschen daneben möglich?
- Wiederherstellungspunkte und Löschpflicht widersprechen sich. Welche Seite gewinnt wann?
- Hallenplan-Export als Grundlage für Flucht- und Rettungspläne: Der Export liefert die Zeichnung. Ein normgerechter
  Plan hat eigene Vorgaben, die das Programm nicht erfüllen soll.
- Eintrittskarten drucken: Gehört der Kartendruck hierher, oder reicht zunächst die Türliste? Ein Kartendruck braucht
  Vorlagen je Verein und hängt damit am Customizing (1.6).

### 1.4 – Saal und Verkauf (kein Umbau)

**Inhalt**
- Platzvorschlag: Das Programm schlägt eine gewünschte Zahl zusammenhängender freier Plätze vor.
- Saalplanung: Gang als eigenes Objekt in der Bestuhlung (auch Seitengänge und Zugänge), einzelne Tische je
  Vorstellung deaktivieren, Vorgabewerte einer neuen Reihe aus der vorigen ableiten.
- Rückgängig und Wiederholen für Änderungen an Events und Hallen.
- Bedienung: Fenstergröße und -position merken.

**Warum hier:** Diese Punkte verlangt der Alltag, sie hängen an keinem der drei Umbauten und sind zum Teil schon
ausgearbeitet. Gebündelt ergeben sie ein Release zwischen zwei schweren Umbauten (Löschen, Bestellung). 1.3 und 1.4
hängen nicht voneinander ab und lassen sich tauschen. Der Platz nach der Archivierung ist gewählt, weil die
Archivierung mit dem Ende der ersten Saison gebraucht wird, die Saalplanung erst vor der nächsten (vom Entwickler am
16.11.2026 bestätigt).

**Offen für den eigenen Spec**
- Platzvorschlag: nur je Tisch oder über Tische hinweg (z. B. bei Bankreihen)?
- Gang: Geometrie bei gedrehten Reihen, und ob er den heutigen Mittelgang an der Reihe ablöst oder ergänzt.
- Deaktivierte Tische: Wie zählen sie in Statistik und Abrechnung, und was gilt, wenn an einem Tisch schon Buchungen
  hängen?
- Rückgängig: Der Ansatz (Zustandsabbilder) ist im Oktober 2026 ausgearbeitet worden, vor dem Versionsspeicher. Er ist
  gegen den heutigen Stand zu prüfen.

### 1.5 – Warteliste und Kundenmail (Umbau: Bestellung und Kunde)

**Inhalt**
- Eine Bestellung fasst mehrere Plätze eines Kunden zusammen.
- Stehplätze: Karten ohne festen Platz, mit Höchstzahl je Vorstellung. Sie stellen dieselbe Frage wie die Bestellung, weil mehrere
  Buchungen zusammengehören und die Buchung heute am Sitz hängt. Beide werden zusammen entworfen.
- Warteliste je Vorstellung.
- Einfache Kundenmail von der Kasse aus (z. B. Bestätigung, Platz frei geworden), ohne Server.

**Warum hier:** Warteliste, Kundenmail und die spätere Bestellannahme brauchen alle dasselbe Fundament. Das
Verschieben von Buchungen aus 1.2 zeigt vorher, wie sich mehrere zusammengehörige Plätze im Alltag verhalten.

**Offen für den eigenen Spec**
- Die Buchung je Platz ist der Kern der Konfliktbehandlung (`bookings/<seatId>`). Wie kommt die Bestellung dazu, ohne
  dass zwei Kassen, die verschiedene Plätze verkaufen, plötzlich in Konflikt geraten?
- Gibt es einen Kundenstamm über Events hinweg? Das hängt am Löschkonzept aus 1.3. Stammkundenverwaltung und
  Warteliste sind in Jira noch als Idee vermerkt, über die nicht entschieden ist.
- Stehplätze: Kommt die Höchstzahl aus der Halle (Stehplatzbereich in der Bestuhlung) oder als Kontingent je
  Vorstellung? Zwei Kassen können gleichzeitig die letzte Karte verkaufen; der Überverkauf muss erkannt und gemeldet
  werden. Abrechnung und Statistik zählen Stehplätze getrennt.
- Benachrichtigung auf anderen Wegen als Mail (SMS, Telefon)?
- Warteliste: je Vorstellung oder je Kategorie, und was passiert bei einem Storno (Hinweis an die Kasse oder
  automatisches Nachrücken)? Nicht zugeordnete Buchungen (`unassigned`) sind bereits ein verwandter Zustand.
- Mailversand bei mehreren Kassen: genau eine verschickt, auch nach Offline-Phasen. Wo liegen die Zugangsdaten zum
  Postfach, wenn nicht im gemeinsamen Ordner?

### 1.6 – Customizing und Englisch (Umbau: Texte auslagern)

**Inhalt**
- Alle Texte in Sprachdateien, Deutsch und Englisch.
- Customizing: Farben, Logos, Meldungstexte, Meldungshärte (Fehler oder Warnung je Meldungscode).
- ECV-Spezifisches aus dem Programm: Name, Logo, Datenpfad `EltvillerCarnevalVerein`, Paketierung.

**Warum hier:** Ohne ausgelagerte Texte gibt es weder Übersetzung noch anpassbare Meldungen. Diese Stufe macht das
Programm vorzeigbar für einen fremden Verein und steht deshalb direkt vor dem Testlauf.

**Offen für den eigenen Spec**
- Gilt das Customizing je Arbeitsplatz oder für den ganzen Verein (dann im gemeinsamen Ordner, als neue Einheit)?
- Meldungshärte: Welche Meldungen darf ein Verein abschwächen, welche schützen die Daten und bleiben fest?
- Umzug des Datenpfads auf bestehenden Installationen.
- Bleibt der Code selbst deutsch? Annahme: ja, übersetzt wird nur die Oberfläche.

### Beigabe: macOS (kein eigenes Release)

Mac-Nutzer gibt es, vor allem zum Vorbereiten und Auswerten zu Hause, nicht an der Kasse. Ziel ist, den Mac so
weit zu unterstützen, wie es **ohne Apple-Entwicklerkonto** geht (Entscheidung des Entwicklers: rund 99 $ im Jahr
sind es derzeit nicht wert). Die Mac-Unterstützung ändert nichts am Datenformat und kann deshalb mit jedem Release
mitkommen. Vorgeschlagen ist 1.6, weil dort ohnehin die Windows- und ECV-spezifischen Stellen angefasst werden.

**Inhalt**
- Mac-Pakete für Intel und Apple Silicon aus dem bestehenden Release-Lauf (zwei weitere Fat-JARs, Ziele in
  `conveyor.conf`).
- Offline-Erkennung für Cloud-Ordner auf dem Mac (`CloudFolders` kennt bisher nur die Windows-Orte).
- Bedienung: Cmd statt Strg bei der Mehrfachauswahl und in den Texten.
- Datenpfad nach Mac-Konvention.
- Anleitung für die einmalige Freigabe unter Systemeinstellungen → Datenschutz & Sicherheit.

**Grenzen ohne Apple-Konto**
- Das Paket ist nicht notarisiert. macOS blockiert den ersten Start, der Nutzer gibt die App von Hand frei.
  Vermutlich nach jedem Update erneut (nicht geprüft).
- Update nur per Hinweis und von Hand, wie bei Linux.
- Für Kassen mit Ehrenamtlichen wird der Mac deshalb nicht empfohlen.

**Zuerst zu klären (kurzer Versuch vor jeder Code-Arbeit)**
- Baut Conveyor ohne Apple-Zertifikat ein startbares Mac-Paket, auch für Apple Silicon?
- Wer testet auf einem echten Mac (zweiter Bildschirm, Menüleiste, Schrift, Installation)?

**Später:** Kommt über andere Vereine Geld herein, sind Signierung und Notarisierung eine der ersten sinnvollen
Ausgaben.

### Vorgezogen: Offline-Erkennung unter Linux

Beim Prüfen für den Mac aufgefallen, nur im Code gelesen, nicht unter Linux ausprobiert: `CloudFolders` findet
Nextcloud nur über `%APPDATA%` und OneDrive nur über Windows-Umgebungsvariablen. Im heutigen Linux-Paket gilt ein
Nextcloud-Ordner damit immer als erreichbar, und Verkäufe ohne Internet zählen als hochgeladen. Das ist der Fall,
den 1.0.1 unter Windows behoben hat. Es betrifft ein bestehendes Paket und gehört deshalb als eigenes Ticket vor
die Mac-Arbeit, sobald geprüft ist, dass es wirklich so auftritt.

### Testlauf bei einem Nachbarverein

Nach der ersten echten ECV-Saison und nach 1.6 probiert ein befreundeter Verein das Programm aus. Ziel ist Lernen,
nicht Vertrieb: Was fehlt, was ist unverständlich, was braucht die Einrichtung ohne den Entwickler? Das Ergebnis
bestimmt, was in 2.0 zuerst kommt und ob der Vertrieb weiterverfolgt wird.

Vermutung, zu prüfen: Vereine, die heute Excel-Listen führen, wollen ihre Daten übernehmen (Import).

### 2.0 – Server als Zusatz

**Inhalt**
- Nutzerkonten (Name, Passwort) und Berechtigungen.
- Bestellannahme über eine Schnittstelle (Mail oder andere), auch wenn keine Kasse läuft.
- Automatische Kundenmails.
- Self-Hosting.

**Auslöser:** Der Entwickler entscheidet, dass der Serverbetrieb angegangen wird. Voraussetzung ist, dass das Programm
aktiv im Einsatz ist.

**Offen für den eigenen Spec**
- Ist der Server ein weiterer, immer laufender Teilnehmer am bestehenden Versionsspeicher oder eine echte zentrale
  Instanz?
- Rechte sind nur dann ein Schutz, wenn nicht jeder Arbeitsplatz alle Dateien hat. Im Ordnerbetrieb wären sie eine
  Sperre in der Oberfläche. Reicht das dort, oder gibt es Rechte nur mit Server?
- Betrieb, Updates und Datensicherung bei Vereinen ohne IT.

## Ohne eigene Stufe

Diese Themen bekommen keine Stufe. Sie werden bei der Planung eines Releases dazugenommen, wenn Platz ist oder wenn sie
drängen:

- **Versionsspeicher:** Robustheit und Geschwindigkeit (Prüflauf im Hintergrund, Startzeit bei großem Bestand, große
  Stapel, seltene Fälle rund um die Rechner-Uhr).
- **Innere Ordnung:** große Controller aufteilen, Logik ohne Oberfläche testbar machen.
- **Fehler:** gehen in das nächste Release oder in ein Zwischenrelease der dritten Stelle. Offen ist die
  Offline-Erkennung unter Linux (siehe oben).
- **Ideen ohne Entscheidung:** Prüfungen nach Kontext ordnen (Hallenprüfungen nur in der Hallenverwaltung) und ein
  Formular, über das Nutzer Vorschläge und Fehler melden. Das Formular wäre spätestens zum Testlauf nützlich.

## Leitplanken ab sofort

- Keine neuen ECV-spezifischen Namen, Logos oder Pfade im Code (Zusage des Entwicklers).
- Die Regeln des vorwärtskompatiblen Datenformats gelten für jede Stufe.
- Kassen in 1.2 so anlegen, dass später eine Rolle daran hängen kann.

## Monetarisierung

Offen. Mit GPL-3.0 vereinbar sind Spenden, bezahlter Support, Einrichtung und später Hosting. Lizenzgebühren würden
eine Lizenzänderung brauchen. Die ist möglich, weil die Rechte beim Entwickler liegen. Vorher ist zu prüfen, ob die
Paketierung mit Conveyor dann kostenpflichtig wird. Entschieden wird frühestens nach dem Testlauf.

## Annahmen des Verfassers (zu prüfen)

Diese Punkte sind im Gespräch nicht entschieden worden, sondern Vorschläge:

- Neue Fenster in 1.2 bis 1.5 bekommen ihre Texte weiter fest auf Deutsch. Ausgelagert wird einmal, in 1.6. Wenn bis
  dahin sehr viele Fenster dazukommen, lohnt es sich, das Auslagern vorzuziehen.
- Die einfache Kundenmail gehört zu 1.5, die automatische zu 2.0.
- Die Bestellannahme gehört zu 2.0. Braucht der ECV sie früher, wäre ein händischer Import in 1.5 der Zwischenschritt.
- Der Export von Hallenplänen und Buchungen gehört zu 1.3.
- Die Aufbewahrung alter Versionen und Sicherungen gehört zu 1.3.
