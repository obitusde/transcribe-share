# Script-Update für App 3.1 (Titel im Mail-Betreff)

Zwei Dateien ändern sich: `DirectUpload` und `Code`. Die Skripteigenschaften
bleiben wie sie sind.

## Was neu ist

- Mail-Betreff: **`Mo 02.11.2026 09:27 · QBR-Folien mit Ralph`**:
  Wochentag, Datum und Uhrzeit der Aufnahme (ohne Sekunden), dann ein kurzer
  Titel, der das ganze Gespräch zusammenfasst.
- Den Titel schreibt ein kleines Sprachmodell (Claude Haiku, sonst Gemini Flash
  Lite) über das **LLM Gateway von AssemblyAI**: gleicher API-Key, kein neues
  Konto. Es liest das ganze Transkript (erst ab etwa 5 Stunden wird in der
  Mitte gekürzt) und schreibt den Titel in der Sprache des Gesprächs. Kosten:
  etwa 2–4 Cent pro Stunde Aufnahme.
- Klappt das nicht, nimmt das Script die erste Überschrift der
  AssemblyAI-Zusammenfassung, sonst den Kontext aus der App.
- Der Titel steht auch in der ersten Zeile der TXT („Titel: …“). Die App zeigt
  ihn in der Liste an.
- **Executive Summary immer auf Englisch** (seit DirectUpload Version 5): Im
  selben Aufruf schreibt das Modell aus dem ganzen Transkript 4–8 Stichpunkte
  (Themen, Entscheidungen, offene Punkte, Aufgaben mit Verantwortlichen). Der
  Titel bleibt in der Sprache des Gesprächs. Klappt der Aufruf nicht, steht wie
  bisher die Zusammenfassung von AssemblyAI in der Mail.

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

Schon auf Version 4? Für die englische Summary nur Schritt 1 und 3 (Code.gs
bleibt).

Reihenfolge egal: Code.gs funktioniert auch mit der alten DirectUpload
(dann ohne Wochentag im Betreff), und umgekehrt.
