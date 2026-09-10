# Apps Script: Einbau des Direkt-Uploads

## Brauchst du alle Dateien im Projekt?

Ja, alle vier — nichts davon ist Altlast:

| Datei | Rolle |
|---|---|
| `Code.gs` | Kern: Warteschlange, AssemblyAI-Start, Statusprüfung, Mailversand, Job-State |
| `checkStatusTrigger.gs` | Einmal-Trigger, der `checkAllPendingStatuses_()` wiederholt aufruft, bis die Mail raus ist |
| `doPost-diagnose-v1.gs` | Der produktive Upload-Endpoint der PWA. Nur der **Name** ist irreführend, der Inhalt ist nicht Diagnose |
| `Index.html` | Web-UI mit „Transkription starten"-Button, geladen von `doGet()` |

Dazu kommt jetzt `DirectUpload.gs` als fünfte Datei.

## Wo die Zeit wirklich hingeht

Der alte `doPost()` macht **zwei** teure Dinge synchron, bevor er antwortet:

1. `Utilities.base64Decode()` über ~22,6 MB Text plus `folder.createFile()`
   mit dem 17-MB-Blob.
2. `startAllPendingTranscriptions_()` für **alle** wartenden Dateien — pro
   Datei mehrere Drive-Roundtrips (Ordner scannen, umbenennen,
   `ensureUniqueFileName_`, `getJobState`, `saveJobState` mit Rück-Lesen und
   Retry-Sleeps von 2–6 s) plus ein AssemblyAI-API-Call.

Bei 141,6 Mbit/s Upload und 9 ms Latenz gehen von den ~90 s bis zum Timeout
nur ~1,3 s auf die Übertragung. Der Rest ist Punkt 1 + Punkt 2.

## Was sich ändert

Punkt 1 fällt komplett weg: der Browser schiebt die Bytes als rohes Binary
direkt in eine Drive-**Resumable-Upload-Session**.

Punkt 2 bleibt bewusst synchron, nur eben ohne die Dekodierung davor. Das
ist Absicht — die Antwort muss weiterhin den **normalisierten** Dateinamen
enthalten, auf den die PWA ihr Status-Polling stützt. Der Teil kostet
Sekunden, nicht Minuten.

```
Browser ──{action:'initUpload'}──────────► Apps Script  (winzig)
Browser ◄─{uploadUrl, fileName}────────── Apps Script
Browser ──PUT 17 MB raw binary──────────► Drive API     (schnell, direkt)
Browser ──{action:'completeUpload'}─────► Apps Script
                                          └─ startAllPendingTranscriptions_()
                                          └─ scheduleAutoCheck()
Browser ◄─{transcription:{fileName}}───── Apps Script
Browser ──GET ?action=status (Polling)──► Apps Script  (unverändert)
```

Der OAuth-Token des Skripts bleibt serverseitig — der Browser bekommt nur die
kurzlebige Session-URL für genau diese eine Datei.

## Einbau — nur 2 Handgriffe

### Schritt 1 — Neue Datei anlegen

Im Editor eine **neue** Skriptdatei `DirectUpload` anlegen und den Inhalt von
`DirectUpload.gs` einfügen.

Die Datei definiert bewusst **weder `doPost` noch `doGet`**, damit nichts mit
`Code.gs` oder `doPost-diagnose-v1.gs` kollidiert. Sie benutzt `CONFIG`,
`startAllPendingTranscriptions_()`, `scheduleAutoCheck()` und
`jsonResponse_()` aus den bestehenden Dateien.

### Schritt 2 — Zwei Zeilen in `doPost-diagnose-v1.gs`

Ganz am Anfang von `doPost(e)`, direkt hinter `try {`:

```js
function doPost(e) {
  try {
    var routed = handleUploadAction_(e);   // ← NEU
    if (routed) return routed;             // ← NEU

    if (!e || !e.postData || !e.postData.contents) {
      // ... alles Weitere bleibt unverändert ...
```

`handleUploadAction_()` gibt `null` zurück, wenn im Body keine `action`
steht. Der alte Base64-Pfad bleibt damit voll funktionsfähig — wichtig, weil
der neue Client bei Problemen automatisch darauf zurückfällt.

### Schritt 3 — Deployen

Bereitstellen → Bereitstellungen verwalten → Stift → Version: **Neue
Version** → Bereitstellen.

Die `exec`-URL liefert die **deployte** Version, nicht den gespeicherten
Editor-Stand. Ohne diesen Schritt ändert sich nichts. Die URL bleibt gleich,
die PWA braucht keine Anpassung.

## Was ausdrücklich NICHT angefasst wird

- **Statusverwaltung.** `getUploadStatus_()` leitet den Zustand aus Drive ab
  (Datei im Archiv → `done`, Job-State-Datei vorhanden → `transcribing`,
  Datei noch im Arbeitsordner → `error`). `DirectUpload.gs` schreibt bewusst
  keinen eigenen Statusspeicher — das würde an dieser Logik vorbeilaufen.
- **Dateinamen.** Das Umbenennen auf `YYYY-MM-DD_HH-mm-ss.ext` macht
  weiterhin `startTranscriptionForFile_()`. Der Upload legt die Datei unter
  dem Originalnamen im Arbeitsordner ab, damit nicht zwei Namenslogiken
  gegeneinander laufen.
- **Mailversand, AssemblyAI-Aufruf, Trigger-Kette.** Unverändert.

## Scopes

Neu hinzu kommt ein `UrlFetchApp`-Aufruf gegen `googleapis.com` mit
`ScriptApp.getOAuthToken()`. Beide nötigen Scopes hat das Projekt durch
`DriveApp` und die bestehenden AssemblyAI-Calls bereits — es sollte also
keine neue Freigabe nötig sein. Falls Drive den Init-Call trotzdem mit
HTTP 401/403 ablehnt, in `appsscript.json` ergänzen:

```json
"oauthScopes": [
  "https://www.googleapis.com/auth/drive",
  "https://www.googleapis.com/auth/script.external_request",
  "https://www.googleapis.com/auth/gmail.send"
]
```

Danach einmal neu autorisieren.

## Rückfallebene

Der Client versucht immer zuerst den Direktweg. Schlägt `initUpload` fehl
oder scheitert der PUT endgültig (z. B. weil die Session abgelaufen ist),
wechselt er automatisch auf den alten Base64-Weg und zeigt „Ersatzweg" an.
Die App wird zu keinem Zeitpunkt unbenutzbar.

Läuft der Direktweg, zeigt die Seite echten Fortschritt in Prozent und MB/s.

## Chunked Upload mit Resume (seit App-Version v7)

Der ursprüngliche Direktweg (v3–v6) hat die Resumable-Session zwar eröffnet,
aber nie tatsächlich als "resumable" genutzt: `putBlobWithProgress` hat die
komplette Datei in **einem** `xhr.send(blob)` geschickt, mit einem einzigen
Timeout fürs Ganze und ohne jeden Retry. Bei kurzen Testdateien (Sekunden
Übertragungszeit) fiel das nicht auf. Bei einer 56-minütigen Aufnahme dauert
die Übertragung auf dem Handy deutlich länger, und in diesem Fenster reicht
ein einziger kurzer Netzwackler (WLAN↔LTE-Wechsel, Screen-Lock, App kurz im
Hintergrund), um den kompletten Upload mit "Fehler beim Hochladen" abzubrechen
— unabhängig von der Dateigröße an sich, sondern von der *Dauer* der
Übertragung.

Seit v7 läuft der Upload in 4-MiB-Chunks (`putChunk_`):

- Scheitert ein Chunk (Netzwerkfehler, Timeout, 5xx), wird er bis zu 5x mit
  exponentiellem Backoff wiederholt, **bevor** aufgegeben wird.
- Scheitert das endgültig, fragt `queryUploadStatus_()` beim Server nach
  (offizielles Drive-Resumable-Protokoll: `PUT` mit
  `Content-Range: bytes */<total>` und leerem Body), wie viele Bytes
  tatsächlich schon angekommen sind, und der Upload setzt dort fort —
  statt die komplette, womöglich sehr große Datei noch einmal zu senden
  oder den Upload als gescheitert zu melden.
- Nur wenn auch das scheitert (Server antwortet z. B. mit 404 — Session
  wirklich verloren) gibt es endgültig auf, und der Client fällt auf den
  Base64-Ersatzweg zurück.

## Datei-Übergabe ohne Cache API (seit App-Version v8)

Bis v7 hat der Service Worker die geteilte Datei per `cache.put()` in die
Cache API gelegt und die Seite hat sie dort wieder herausgeholt. Bei einer
56-minütigen Aufnahme lief das in einen `QuotaExceededError` — und Chrome
liefert bei dieser DOMException eine **leere `.message`**. Der Service
Worker hat nur `error.message` weitergereicht, die Seite zeigte deshalb ein
nacktes „Fehler beim Hochladen" ohne jede Detailzeile, und der komplette
Upload-Code wurde nie erreicht. Das Chunking aus v7 konnte daran
nichts ändern, weil es nie zur Ausführung kam.

Seit v8 wandert die File-Referenz direkt per `postMessage` vom Service
Worker an die Seite. Das kopiert nichts und verbraucht keine Quota — das
File-Objekt ist nur ein Handle auf die Datei, die Android ohnehin auf der
Platte liegen hat. Als Absicherung (falls der Service Worker zwischen
Redirect und Seitenaufbau beendet wird) schreibt er zusätzlich im
Hintergrund nach IndexedDB; scheitert das, ist es folgenlos.

Zusätzlich liefert `describeError()` auf beiden Seiten jetzt **nie** einen
leeren Text: fehlt die `.message`, wird der `.name` der Exception angezeigt.
Genau diese Zeile hat vorher gefehlt, um das Problem überhaupt sehen zu
können.

## Manueller Weg über Drive (wenn das Teilen scheitert)

Der Android-Share-Target liefert die Aufnahme zeitweise nicht aus: der POST
kommt mit einem leeren Multipart-Body an (nachgewiesen — 75 Bytes, exakt die
Länge des Abschlussmarkers, null Teile). Das passiert im Übergang
Android → Chrome → WebAPK und ist aus der PWA heraus nicht behebbar.

Der Auswahl-Button in der PWA hilft dabei **nicht**, wenn die Quelle der
Google Recorder ist: dessen Aufnahmen liegen im app-privaten Speicher und
erscheinen im System-Auswahldialog gar nicht.

Was stattdessen funktioniert, ohne jede Codeänderung:

1. Im Recorder die Aufnahme teilen → **Drive** wählen (die native Drive-App,
   nicht die PWA). Das ist ein ganz anderer Weg als der Web-Share-Target und
   von dessen Problem nicht betroffen.
2. Als Zielordner `Transcription-Arbeit` wählen (der Ordner aus
   `CONFIG.FOLDER_WORK_NAME`).
3. Die Apps-Script-Weboberfläche öffnen — das ist die `exec`-URL **ohne**
   Parameter. `doGet()` liefert dort `Index.html` aus.
4. Auf **„Transkription starten"** tippen.

`startTranscriptionWorkflow()` nimmt die neueste Datei aus dem
Arbeitsordner, und die Warteschlange auf der Seite zeigt, was sonst noch
ansteht.

### Lücke dabei: die Mail bleibt aus, wenn die Seite geschlossen wird

`startTranscriptionWorkflow()` ruft **kein** `scheduleAutoCheck()` auf — im
Gegensatz zu `doPost()` bzw. `completeUpload_()`. Ohne diesen Trigger gibt es
keinen serverseitigen Status-Check; abgeschlossen (und damit die Mail
versendet) wird die Transkription nur durch das 15-Sekunden-Polling der
geöffneten Weboberfläche. Wer die Seite zumacht, bekommt keine E-Mail, und
die Datei bleibt im Arbeitsordner liegen.

Ein Einzeiler in `Code.gs` behebt das — in `startTranscriptionWorkflow()`,
direkt vor dem `return`:

```js
    const result = startTranscriptionForFile_(latestFile);
    if (result.status === 'processing') scheduleAutoCheck();   // ← NEU
    return Object.assign({}, result, { pending: buildPendingList() });
```

Danach `clasp push` und neu deployen (`clasp deploy -i <deploymentId>`).
Ab dann verhält sich der manuelle Weg wie der Upload über die PWA: die Mail
kommt automatisch, egal ob die Seite offen bleibt.

## Getestet

Der Direktweg lief in der Praxis bereits erfolgreich (bestätigt über den
`?action=version`-Endpoint und die Fusszeile "Direktweg aktiv" in der PWA).
Der CORS-Fix (Origin an `initUpload_` durchreichen, siehe
`ALLOWED_UPLOAD_ORIGINS` in `DirectUpload.gs`) war dafür notwendig und ist
bereits deployt. Die Chunked-Upload-Erweiterung selbst ist neu und noch
nicht mit einer sehr langen Aufnahme gegengetestet — falls "Fehler beim
Hochladen" trotzdem wieder auftritt, bitte den genauen Text unter der
Überschrift (die kleine graue Detailzeile) mitschicken, der jetzt den
tatsächlichen HTTP-Status bzw. die Fehlerursache enthält statt nur
"Netzwerk- oder CORS-Fehler".
