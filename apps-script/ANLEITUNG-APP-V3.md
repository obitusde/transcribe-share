# Script-Update für App 3.0 (Löschen in Google Drive nach 28 Tagen)

Nur **eine** Datei ändert sich: `DirectUpload`. `Code.gs` und die
Skripteigenschaften bleiben wie bei Version 2.

## Was neu ist

- Aufnahmen im Drive-Archivordner werden **28 Tage nach dem Upload** in den
  Drive-Papierkorb gelegt. Google leert den Papierkorb nach 30 Tagen endgültig.
- Das gilt für **alle** Audiodateien im Archiv, auch alte aus dem Google
  Recorder. Beim ersten Lauf werden also alle älteren Aufnahmen gelöscht.
- Transkripte (TXT im Ordner „Transkripte“, Mails) bleiben.
- Ein täglicher Trigger (`cleanupArchive`, nachts um 3 Uhr) erledigt das. Er
  richtet sich beim ersten Öffnen der App selbst ein.

## Schritte am Rechner

1. Apps-Script-Projekt öffnen → Datei **DirectUpload** öffnen → alles
   markieren → durch den Inhalt von
   [`apps-script/DirectUpload.gs`](DirectUpload.gs) ersetzen → Speichern.
2. **Bereitstellen → Bereitstellungen verwalten** → die Bereitstellung, deren
   URL auf `…E5KRHWc8ig/exec` endet → Stift → Version **„Neue Version“** →
   **Bereitstellen**.
3. Optional, um sofort aufzuräumen: oben in der Funktionsauswahl
   `cleanupArchiveNow` wählen → **Ausführen**. Im Ausführungsprotokoll steht,
   wie viele Aufnahmen in den Papierkorb kamen. Falls Google nach einer
   Berechtigung fragt: erlauben.
4. App öffnen. Unten steht dann „Server: aktuell ✔“ und
   „Google Drive: Aufnahmen werden 28 Tage nach dem Upload gelöscht“.

Versehentlich gelöscht? Im Drive-Papierkorb liegt die Datei noch 30 Tage und
lässt sich wiederherstellen.
