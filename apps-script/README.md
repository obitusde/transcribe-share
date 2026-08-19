# Apps Script: Einbau des Direkt-Uploads

## Problem

Der bisherige Weg schickte die komplette Aufnahme als Base64-String im
JSON-Body an `doPost()`. Bei 17 MB Audio sind das ~22,6 MB Text.

Messung bei 141,6 Mbit/s Upload und 9 ms Latenz:

| Posten | Zeit |
|---|---|
| 22,6 MB über die Leitung | ~1,3 s |
| TLS-Handshake + Redirect | < 0,2 s |
| **Rest: Apps Script serverseitig** | **~88 s** |

Dass die restlichen ~88 s serverseitig liegen, lässt sich am alten Client
ablesen: Sekundenzähler und 90-s-Timeout wurden beide erst **nach**
`await blobToBase64(blob)` aufgesetzt, haben also ausschließlich die Zeit
innerhalb von `fetch()` gemessen. Die Zeit verbrennt Apps Script in
`Utilities.base64Decode()` über einen 22-MB-String plus dem Schreiben nach
Drive — alles synchron, bevor überhaupt geantwortet wird.

## Lösung

Apps Script eröffnet nur noch eine Drive-**Resumable-Upload-Session** und gibt
deren URL zurück. Die Bytes schiebt der Browser als rohes Binary direkt zu
Google. Apps Script sieht danach nur noch winzige JSON-Nachrichten.

```
Browser ──{action:'initUpload'}──────────► Apps Script
Browser ◄─{uploadUrl, fileName}────────── Apps Script   (winzig)
Browser ──PUT 17 MB raw binary──────────► Drive API     (schnell, direkt)
Browser ──{action:'completeUpload'}─────► Apps Script
Browser ◄─{transcription:'processing'}─── Apps Script   (winzig)
Browser ──GET ?action=status (Polling)──► Apps Script   (unverändert)
```

Der OAuth-Token des Skripts wird dabei **nie** an den Browser gegeben. Die
Session-URL ist ein kurzlebiges Capability-Token für genau diese eine Datei.

## Einbau

### Schritt 1 — Neue Skriptdatei anlegen

Im Apps-Script-Editor eine **neue** Datei `DirectUpload` anlegen und den
Inhalt von `DirectUpload.gs` hineinkopieren.

> Die bestehende `Code.gs` **nicht** überschreiben. Die neue Datei definiert
> bewusst weder `doPost` noch `doGet`, damit nichts kollidiert.

Falls die Dateien in einem bestimmten Drive-Ordner landen sollen: oben in
`DirectUpload.gs` die `UPLOAD_FOLDER_ID` setzen (leer = Drive-Wurzel).

### Schritt 2 — Zwei Zeilen in die bestehende `doPost()`

```js
function doPost(e) {
  var routed = handleUploadAction_(e);   // ← NEU, ganz oben
  if (routed) return routed;             // ← NEU

  // ... bestehender Base64-Code bleibt unverändert stehen ...
}
```

`handleUploadAction_()` gibt `null` zurück, wenn die Anfrage keine
`action` enthält. Der alte Pfad läuft dadurch unangetastet weiter — wichtig,
weil der neue Client bei Problemen automatisch darauf zurückfällt.

### Schritt 3 — Transkription anschließen

In `DirectUpload.gs` wirft `startTranscriptionForDriveFile_()` aktuell
absichtlich einen Fehler. Dort gehört der Code hin, der in der alten
`doPost()` **direkt hinter** `DriveApp.createFile(...)` stand:

```js
function startTranscriptionForDriveFile_(fileId, fileName) {
  var file = DriveApp.getFileById(fileId);

  // ... vorhandener Transkriptions-Aufruf mit "file" ...
  // ... vorhandenes MailApp.sendEmail(...) ...

  setTranscriptionState_(fileName, 'done');   // oder 'error' im Fehlerfall
}
```

**Wichtig:** am Ende immer den Status setzen, sonst pollt der Client bis zum
Timeout ins Leere.

### Schritt 4 — Statusspeicher abgleichen

`DirectUpload.gs` bringt eine eigene `setTranscriptionState_()` /
`getTranscriptionState_()` auf Basis von `PropertiesService` mit.

**Wenn im bestehenden Skript bereits eine Statusverwaltung existiert** (die
`doGet(?action=status)` bedient), dann die beiden Funktionen in
`DirectUpload.gs` löschen und stattdessen die vorhandenen verwenden. Sonst
schreiben zwei Speicher aneinander vorbei und das Polling sieht nie ein
`done`.

### Schritt 5 — Scopes prüfen

`ScriptApp.getOAuthToken()` liefert nur die Scopes, die das Projekt bereits
hat. Der Aufruf der Drive-REST-API über `UrlFetchApp` fügt den Drive-Scope
**nicht** automatisch hinzu. In `appsscript.json` (Editor → Projekt­einstellungen
→ „appsscript.json-Manifestdatei im Editor anzeigen") sicherstellen:

```json
"oauthScopes": [
  "https://www.googleapis.com/auth/drive",
  "https://www.googleapis.com/auth/script.external_request",
  "https://www.googleapis.com/auth/script.send_mail"
]
```

Nach einer Scope-Änderung muss die Autorisierung einmal neu bestätigt werden.

### Schritt 6 — Neu deployen (der klassische Stolperstein)

Die `exec`-URL liefert **die deployte Version, nicht den gespeicherten
Editor-Stand**. Also:

Bereitstellen → Bereitstellungen verwalten → Stift-Symbol →
Version: **Neue Version** → Bereitstellen

Die `exec`-URL bleibt dabei gleich, der Client braucht keine Änderung.

## Rückfallebene

Der Client versucht immer zuerst den Direktweg. Schlägt `initUpload` fehl
(z. B. weil Schritt 6 noch nicht gemacht wurde) oder scheitert der PUT an
CORS, wechselt er automatisch auf den alten Base64-Weg und zeigt das als
„Ersatzweg" an. Die App wird dadurch zu keinem Zeitpunkt unbenutzbar.

Sobald der Direktweg läuft, zeigt die Seite echten Fortschritt in Prozent
und MB/s — bei 17 MB und deiner Leitung sollten das wenige Sekunden sein.

## Nicht getestet

Der Direkt-Upload ist hier nicht real erprobt worden: die Umgebung, in der
dieser Code entstanden ist, hat keinen Netzzugang zu `script.google.com` oder
`googleapis.com`, und der Ablauf startet ohnehin erst über das Android-Share-
Target. Der wahrscheinlichste Stolperstein ist CORS beim PUT auf die
Session-URL — genau dafür ist die Rückfallebene eingebaut.
