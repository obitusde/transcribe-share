# Aufnahme-App (Android)

Ein Tipp startet die Aufnahme, ein Tipp stoppt sie und laedt hoch. Der
Bildschirm darf waehrend der Aufnahme aus sein.

## Ablauf

1. Aufnahme laeuft in einem Foreground Service (Typ `microphone`) als
   Ogg/Opus, mono, 48 kbit/s (etwa 21 MB pro Stunde). Ogg bleibt bei einem
   Absturz bis kurz davor lesbar.
2. Nach dem Stopp: `initUpload` beim Apps Script, Bytes in 8-MB-Stuecken
   direkt zu Drive (Resumable Upload, mit Wiederholung), dann
   `completeUpload`. Ab da laeuft die bestehende Pipeline (AssemblyAI, Mail).
3. Schlaegt nur `completeUpload` fehl, liegt die Datei trotzdem in Drive und
   `FolderWatcher.gs` startet sie spaetestens nach 10 Minuten.
4. Lokale Kopie wird 7 Tage nach erfolgreichem Upload geloescht. Nicht
   hochgeladene Dateien werden nie geloescht.

Kein API-Key in der App: AssemblyAI bleibt im Apps Script.

## Bauen

GitHub Actions (`.github/workflows/android-app.yml`) baut bei jeder
Aenderung unter `android-app/` eine signierte APK und veroeffentlicht sie
als Release. Neueste Version:

https://github.com/obitusde/transcribe-share/releases/latest/download/Aufnahme.apk

## Signierschluessel

`signing/release.jks.b64` ist absichtlich eingecheckt, damit jedes Update
mit demselben Schluessel signiert wird und sich ueber die alte Version
installieren laesst. Da das Repo oeffentlich ist, koennte damit auch jemand
anderes eine "passende" APK bauen. Deshalb: Updates nur von der Release-Seite
oben installieren.
