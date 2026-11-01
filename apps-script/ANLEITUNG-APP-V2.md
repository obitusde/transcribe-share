# Script-Update für die Aufnahme-App (Version 2)

Damit funktionieren in der App: **Kontext**, **Mail-Auswahl (Arbeit / Privat / keine)**,
**TXT-Download**, die **Minuten pro Monat** und der **Schlüssel-Schutz**. Ohne dieses Update läuft die App trotzdem.
Dann gehen alle Mails wie bisher an die Arbeitsadresse.

Dauer: etwa 10 Minuten am PC, im Browser auf script.google.com.

> Mit Claude Code und clasp auf dem PC: Gib Claude Code diese Datei und sag
> „Führe die Schritte 2 bis 4 mit clasp aus“. Die Schritte 1 und 5 musst du trotzdem
> selbst im Browser machen.

---

## 1. Skripteigenschaften eintragen

Im Apps-Script-Editor links auf **Projekteinstellungen** (Zahnrad), ganz unten bei
**Skripteigenschaften** auf **Skripteigenschaften bearbeiten** und dann **Skripteigenschaft hinzufügen**:

| Eigenschaft | Wert |
|---|---|
| `UPLOAD_SECRET` | derselbe Schlüssel wie in der App unter „Schlüssel einstellen“ |
| `MAIL_PRIVATE` | deine private Adresse |
| `MAIL_WORK` | *(optional)* deine Arbeitsadresse. Fehlt sie, gilt wie bisher `TARGET_EMAIL` aus `Code.gs`. |

`ASSEMBLYAI_API_KEY` steht dort schon. Nicht ändern.

**Speichern** nicht vergessen.

Hinweis: Sobald `UPLOAD_SECRET` gesetzt ist, lehnt das Script **jede** Anfrage ohne diesen
Schlüssel ab. Die alte Browser-App (PWA) funktioniert dann nicht mehr. Das ist so gewollt.
Der Weg „Recorder → Teilen → Drive“ funktioniert weiter, weil er das Script nicht über
das Internet aufruft.

## 2. Datei `DirectUpload` ersetzen

1. Links die Datei **DirectUpload** öffnen.
2. Alles markieren (Strg+A) und löschen.
3. Den kompletten Inhalt von `apps-script/DirectUpload.gs` aus diesem Repo einfügen:
   https://github.com/obitusde/transcribe-share/blob/master/apps-script/DirectUpload.gs
   (dort oben rechts auf „Copy raw file“ klicken)
4. Speichern (Strg+S).

## 3. `Code.gs`, Änderung 1: Kontext an AssemblyAI

In **Code** mit Strg+F diese Zeile suchen (in `startTranscriptionForFile_`):

```js
  const response = UrlFetchApp.fetch('https://api.assemblyai.com/v2/transcript', {
```

**Direkt darüber** diese neue Zeile einfügen:

```js
  applyAppOptions_(file, payloadData);
```

## 4. `Code.gs`, Änderung 2: Mail an die gewählte Adresse

In **Code** mit Strg+F diese Zeile suchen (in `finalizeCompletedTranscript`):

```js
      sendEmailTranscriptOnly(CONFIG.TARGET_EMAIL, fileName, transcriptText, file.getLastUpdated(), summaryInfo, confidence);
```

Die **ganze Zeile ersetzen** durch:

```js
      deliverTranscript_(file, fileName, transcriptText, summaryInfo, confidence, statusData);
```

Speichern (Strg+S).

Wenn eine der beiden Zeilen nicht genau so zu finden ist, nichts raten. Schick mir die
Funktion, dann passe ich die Anleitung an.

## 5. Neue Version bereitstellen (gleiche URL!)

1. Oben rechts **Bereitstellen → Bereitstellungen verwalten**.
2. Die Bereitstellung wählen, deren Web-App-URL auf **`...E5KRHWc8ig/exec`** endet.
3. Auf den **Stift** (Bearbeiten) klicken.
4. Bei **Version** auf **Neue Version** stellen.
5. **Bereitstellen**.

Wichtig: Nicht „Neue Bereitstellung“ wählen. Die bekäme eine neue URL, und die App würde
weiter die alte ansprechen.

## 6. Prüfen

App öffnen. Unten bei **Server** sollte stehen: **„aktuell ✔“**.

| Anzeige | Bedeutung |
|---|---|
| „altes Script …“ | Schritt 5 fehlt, oder die falsche Bereitstellung wurde aktualisiert |
| „Schlüssel fehlt oder ist falsch“ | Der Schlüssel in der App und `UPLOAD_SECRET` sind nicht gleich |
| „… UPLOAD_SECRET fehlt im Script“ | Schritt 1 nicht gespeichert |
| „… MAIL_PRIVATE fehlt“ | Schritt 1, private Adresse fehlt |

Dann eine kurze Testaufnahme mit Kontext und „Mail an: Privat“ machen.

---

## Was sich im Ablauf ändert

- **Kontext** steht in der Drive-Beschreibung der Audiodatei, in einer Zeile `#app mail=…`
  ganz am Ende. Er geht zusätzlich in den AssemblyAI-Prompt und steht oben im Transkript.
- **Transkripte** liegen zusätzlich als `.txt` im neuen Drive-Ordner **„Transkripte“**
  (neben „Archive“). Von dort holt die App sie, und du kannst sie auch im Browser öffnen.
- **Aufnahmen ohne App** (Recorder → Drive) laufen wie bisher: Mail an die Arbeitsadresse.
- **Fehler-Mails** gehen wie bisher an `TARGET_EMAIL`.
- **Minuten pro Monat** stehen in den Skripteigenschaften als `USAGE_2026-11` usw.
  (in Sekunden). Gezählt wird ab dem Update, auch für Recorder-Aufnahmen über Drive.
