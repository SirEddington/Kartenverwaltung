# Usage guide

The application is in German; button and menu names below are quoted as they appear on screen.

## Starting

1. Start *Kartenverwaltung – Eltviller Carneval Verein*. A loading view ("Bestand wird geladen …") appears while
   the data and, if configured, the shared folder are read.
2. **"Wer verkauft?"** – choose or type the operator name. It is mandatory and is stored in every saved version
   (visible in restore points and conflicts). "Beenden" or closing the dialog quits the app.
3. The main menu offers **Abrechnung**, **Kartenverkauf**, **Verwaltung** and **Einstellungen**.

If the application is already open on this computer, a second start shows an error and stops (SYS-010).

## Typical setup order

```
Verwaltung → Hallen-Verwaltung → hall + seating plan
Verwaltung → Event-Verwaltung  → event + presentations (hall, seating plan, price)
Kartenverkauf                  → sell
Abrechnung                     → reconcile cash, statistics, export
```

## Halls and seating plans (*Verwaltung → Hallen-Verwaltung*)

1. **"+ Neue Halle"**, then edit the hall details: name, description, hall width and height in cm.
2. **Hall objects** ("+ Hallenobjekt hinzufügen"): stage, bar, doors etc. as rectangle or other shapes with position,
   size, color and an optional text (size, alignment, rotation; "Text automatisch").
3. **Seating plans** ("+ Bestuhlung hinzufügen"): a hall can have several, e.g. a theater and a gala layout.
   - **"+ Reihe"** adds a table row with a letter, start point, angle, table rotation, table gap and an optional
     aisle after *n* tables.
   - **"Reihe füllen…"** creates the tables of a row with a given number of seats per table, table width and
     height and seat diameter (default 50 × 320 cm, seat 45 cm). The sizes become the seating plan's defaults for
     tables and seats added later one by one.
   - Seats are circles and have only a diameter. They are spread in equal sections along the table, so the spacing
     stays the same where two tables meet; a seat's center lies its radius + 5 cm beside the table edge.
   - With one or more rows selected, the row panel also sets **table width, table height and seat diameter** and the
     **table labels** for all tables of these rows ("verschieden" marks differing values).
   - Table labels work like hall object texts ("Text automatisch", size, alignment, rotation). The rotation counts
     from the table axis, and the label never stands upside down.
   - Tables and seats can be added, deleted, positioned manually ("Position zurücksetzen" returns to the automatic
     layout), and marked with a category or as wheelchair place.
   - Tables stay in the order they were created; a table number is only a label, so changing it (even to a
     duplicate, which blocks saving) never moves the table. **"Nummern neu vergeben"** renumbers tables by their place.
4. **"Speichern"** (or the save shortcut). Validation errors appear in the footer and block saving.

Changing or deleting a seating plan that presentations use never deletes a booking with data: bookings whose seat
disappeared are kept as **"Ohne Platz"** (unassigned).

## Events and presentations (*Verwaltung → Event-Verwaltung*)

1. **"+ Neues Event"** or **"+ Als Vorlage verwenden"** (copy of an existing event).
2. **"+ Vorstellung hinzufügen"** for each show: name, date, time, description, hall, seating plan and
   **"Standardpreis (€)"**.
3. Switching the seating plan of a presentation with bookings opens a dialog that shows which bookings will lose
   their seat.
4. **"Archiviert"** removes an event from the sales selection; accounting shows it only with "Archivierte
   einschließen".
5. **"Wiederherstellen"** in the overview opens the restore dialog (see below).

## Selling tickets (*Kartenverkauf*)

Select **Event** and **Vorstellung** in the header. Two views:

- **"Tabelle"** – one row per booking (or all presentations of the event). Bookings without a seat are grouped as
  "Ohne Platz" at the top; **"Platz zuweisen…"** assigns one to a free seat.
- **"Saalansicht"** – the seating plan; seat colors show free, reserved, sold and blocked seats.

Selling:

1. Switch to **"Bearbeiten"** (the button toggles back to "Anzeigen"). Without edit mode everything is read-only.
2. Click a seat to open the booking popup. In edit mode, **Ctrl+click** or dragging a rectangle selects
   several seats for a bulk sale; fields show "verschieden" where the selected bookings differ, and only changed
   fields are applied to all.
3. Enter last/first name, e-mail, comment, optional individual price, and the payment status: **Offen**, **Bar**,
   **Karte** or **Überweisung**; tick **"Abgeholt"** when the ticket is handed out.
   Reservation, payment and collection dates and the paid amount are filled in automatically.
4. **"Stornieren"** cancels the selected bookings after a confirmation.

**Search** ("Suche") matches every word against first name, last name and comment together, case-insensitive and in
any order (e.g. `ns wurs` finds "Hans Wurst").

**"Buchungen sofort speichern"** (setting, on by default): every booking change is saved immediately; the popup's
button is then called "Speichern" instead of "Anwenden". With the setting off, use "Speichern"; leaving the screen
with unsaved changes asks *Speichern / Nicht speichern / Abbrechen*.

**Customer display ("Verkaufsmodus"):** opens a second window, e.g. on a monitor facing the buyer, that shows the
same seating plan without controls. Seats are only green (free) or red (taken); zoom, panning, the current selection
and "Reihen anzeigen" follow the main window.

## Accounting (*Abrechnung*)

- **"Kassenabgleich"** per event/presentation: expected revenue by payment method, number of seats
  (paid / reserved / total), **"Tatsächlicher Kasseninhalt"** (counted cash), the difference to the expected cash
  amount and a comment for deviations.
- **"Statistik"** aggregated per event over all events, independent of the header selection.
- **"Exportieren"** writes the active view as **CSV** or **PDF**; the last used format is remembered.

## Restore points

Every save is kept. In the event or hall overview, **"Wiederherstellen"** lists all events (or halls), including
deleted ones; on the right are their save points grouped by day, with time, workstation, operator and number of
changes. Restoring writes the selected state as a new save, so the restore itself can be undone the same way.

## Working with several workstations

### Connecting

1. *Einstellungen → Gemeinsamer Ordner*: choose the folder with **"Durchsuchen …"** and click **"Verbinden"**. The
   same folder must be selected on every workstation.
2. If both this computer and the folder already contain data, the app asks:
   - **"Mitnehmen"** – merge local data into the shared folder. Where both sides changed the same entry, the
     version that reached the folder first wins and the other becomes a conflict.
   - **"Beiseitelegen"** – move the local data to `data-beiseite-<timestamp>` (never deleted) and start from the
     shared data. Not offered if you already saved something since this program start.
   - **"Abbrechen"**.
3. The first full sync runs with a progress view; only afterwards does the data load.

The same section shows the computer name and operator, and **"Trennen"** disconnects. The folder path cannot be
edited while connected: disconnect first, then connect to the new folder.

### Day-to-day

The footer shows one sync hint with the most important state: up to date, changes waiting for upload, folder
unreachable, sync error, or a pending decision. Clicking it opens the info window with the last sync time, skipped
entries and **"Jetzt abgleichen"**. Changes from other workstations appear automatically, also in open screens; an
entry you are editing at that moment is updated only after you save or discard ("Nicht speichern" keeps the other
workstation's change).

**Offline:** when the folder is not reachable (SYS-005) you can keep selling. New sales are provisional and are
uploaded when the folder is back. A Nextcloud or OneDrive folder is always present on disk, so for these the app also
checks that the cloud server answers (every sync tick, i.e. every 2 s); without internet the workstation is
offline. If a firewall blocks this check although the internet works, switch off **"Verbindung zum
Cloud-Server prüfen"** in the same settings section; then only the folder itself counts, as before.

### Conflicts

If two workstations change the same entry, the change uploaded first wins; the other is merged automatically:

| Kind | Meaning | Result |
|---|---|---|
| Double sale | The same seat was sold twice | The losing booking is kept as "Ohne Platz" and must be given a seat |
| Fields | The same fields were changed differently | Per field group, the version uploaded first wins; groups changed on one side only are merged |
| Delete wins | One side deleted, the other changed | The deletion stands |
| Unmerged | No automatic merge possible | The other workstation's state stays |

The footer shows the number of open conflicts; the conflict dialog lists them with kind, entry, other workstation,
time, values and a suggested action. Mark each one **"Erledigt"** when handled. Actions are disabled while the
affected event or hall has unsaved changes.

### Locked entries

An entry that is damaged without an intact copy (SYS-008) or was saved by a newer program version (SYS-009) is
locked: it cannot be changed, and its seats appear blocked in the sales views. Use the "gesperrte Einträge" link in
the footer to reset a damaged entry to its last readable state; for SYS-009 update the application on this
computer (*Einstellungen → Info und Updates*, see [Updates](#updates)). Content that this version cannot process
safely (for example a payment status it does not know) is locked the same way (SYS-009), although the file itself
is intact.

- **Deleting or switching** a presentation or event that has locked entries below it is rejected: SYS-009 for
  entries of a newer version, SYS-008 for damaged ones. The same goes for switching its hall or seating plan.
  Reset damaged entries first; newer ones unlock when a newer version has written a readable state.
- **Accounting** shows a note in the footer: "N gesperrte Buchungen sind nicht berücksichtigt" for bookings that are
  missing from the figures, and "N Buchungen sind mit einem älteren Stand gezählt" for bookings counted with an
  older state. A click opens "Gesperrte Einträge".
- **Waiting changes.** The conflict indicator appends "· N Änderungen warten" for own changes that wait for a lock.
  A click opens the conflict dialog, which lists each waiting change with the locked entry it waits for. They are not
  visible until the lock is gone, so do not enter the customer again.
- **Copies** of an event leave out locked presentations that are not shown ("Gesperrte Einträge werden nicht mitkopiert
  (n)."). A locked presentation shown with an older state is copied with that state; bookings are never copied.
- A booking without a seat does not find its seat by itself after the unlock; assign it with "Platz zuweisen…".
- Resetting a damaged entry whose last readable state came from a newer version leads from SYS-008 to SYS-009: it
  stays locked until the application is updated.
- Without a newer workstation (single use, or after a downgrade) locked entries stay locked until a newer version
  is installed. Background: [decisions.md](decisions.md#forward-compatible-data-format).

## Updates

Windows does not install updates in the background. The app checks the latest GitHub release itself: 10 s after
start, then every 4 hours, and on **"Jetzt prüfen"**. It never opens a window on its own.

- **Footer hint:** "Update 1.1.0 verfügbar" appears when a newer version exists. It stays while a check runs or fails.
- **Updating (Windows):** click the hint (or *Einstellungen → Info und Updates → "Jetzt aktualisieren"*). After the
  confirmation, the app saves or asks about unsaved changes like when closing the window, shows "Update wird
  vorbereitet …", loads the update file and closes. The Windows App Installer opens (if not, look in the taskbar);
  click **"Aktualisieren"**. With "Starten, wenn bereit" ticked, the app restarts by itself. To cancel, close the
  App Installer window and start the app again.
- **"Update im Browser laden":** the fallback in the settings when the app cannot reach the update source itself (for
  example antivirus or proxy). The app closes and the browser downloads the update file; the browser may open in its
  own window without your usual settings, and may ask whether to keep the file ("Behalten"). Open the file and click
  "Aktualisieren", then delete the file. Do not open an old update file while the app is running: the App Installer
  closes it without asking. The settings also show the address of the update file to copy.
- **"Update-Prüfung nicht möglich"** in the footer (Windows): the check keeps failing for a systematic reason
  (certificate, proxy, server). Use "Update im Browser laden". Without internet the app stays silent.
- **Several workstations:** update every workstation that uses the same shared folder at the same time; on a
  workstation without the hint yet, use **"Jetzt prüfen"**.
- **"Für diese Version ist eine Neuinstallation nötig."** The new version comes with a different package identity
  (e.g. a new signing certificate); updating in place is not possible.
- **Linux and the Windows ZIP:** the hint appears, but updating works the way the app was installed (e.g. apt) or by
  downloading the new version.

## Messages

| Code | Meaning | What to do |
|---|---|---|
| SYS-001 | Unexpected technical error (dialog) | Report it with the log file from `<AppData>/logs` |
| SYS-005 | Shared folder not reachable | Keep working; check network / sync client |
| SYS-006 | Sync failed for another reason (e.g. no write permission) | Check folder permissions; the app keeps retrying |
| SYS-007 | A damaged file was replaced by a verified copy | Nothing; the damaged file stays as `.corrupt-…` |
| SYS-008 | Entry damaged, no intact copy | Reset via "gesperrte Einträge" |
| SYS-009 | Entry saved by a newer version | Update the application (*Einstellungen → Info und Updates*) |
| SYS-010 | Application already running on this computer | Switch to the open window |
| SYS-011 | Another computer uses the same machine id | Nothing; a new id was assigned |
| SYS-012 | Shared folder has a file with different content | Nothing was overwritten; report it |
| SYS-013 | An entry could not be synced and was skipped | Retried in the next full sync |

Input and validation messages use entity prefixes (`EVT-`, `PRS-`, `HLL-`, `HOB-`, `SPL-`, `ROW-`, `TBL-`,
`SEA-`, `INP-` …) and appear in the footer of the current screen.
