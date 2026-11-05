# Script-Update für App 3.1 (Titel im Mail-Betreff)

Zwei Dateien ändern sich: `DirectUpload` und `Code`. Die Skripteigenschaften
bleiben wie sie sind.

## Was neu ist

- Mail-Betreff: **`Mo 02.11.2026 09:27 · QBR-Folien mit Ralph`**:
  Wochentag, Datum und Uhrzeit der Aufnahme (ohne Sekunden), dann ein kurzer
  Titel, der das ganze Gespräch zusammenfasst.
- Den Titel schreibt ein kleines Sprachmodell (Claude Haiku, sonst Gemini Flash
  Lite) über das **LLM Gateway von AssemblyAI**: gleicher API-Key, kein neues
  Konto. Es liest die Zusammenfassung und das Transkript (bei langen Gesprächen
  Anfang und Ende) und antwortet in der Sprache des Gesprächs. Kosten: etwa
  1–2 Cent pro Aufnahme.
- Klappt das nicht, nimmt das Script die erste Überschrift der
  AssemblyAI-Zusammenfassung, sonst den Kontext aus der App.
- Der Titel steht auch in der ersten Zeile der TXT („Titel: …“). Die App zeigt
  ihn in der Liste an.

## Schritte am Rechner

1. Datei **DirectUpload**: alles ersetzen durch
   [`apps-script/DirectUpload.gs`](DirectUpload.gs) → Speichern.
2. Datei **Code**: alles ersetzen durch die `Code.gs`, die du im Chat bekommen
   hast (sie enthält deine Arbeitsadresse und liegt deshalb nicht bei GitHub)
   → Speichern.
3. **Bereitstellen → Bereitstellungen verwalten** → die Bereitstellung, deren
   URL auf `…E5KRHWc8ig/exec` endet → Stift → **Neue Version** →
   **Bereitstellen**.
4. App öffnen: unten steht „Server: aktuell ✔“.

Reihenfolge egal: Code.gs funktioniert auch mit der alten DirectUpload
(dann ohne Wochentag im Betreff), und umgekehrt.
