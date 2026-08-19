// Audio Transkription Manager - Code.js
// Zuletzt geaendert: 2026-08-14 (alle wartenden Dateien statt nur die
// neueste, Fehler-Mail bei endgueltigem Scheitern, Executive Summary +
// Konfidenz-Warnung in der Mail, kurzer Themen-Hinweis im Betreff).

// --- Konfiguration ---
const CONFIG = {
  FOLDER_WORK_NAME: 'Transcription-Arbeit',
  FOLDER_ARCHIVE_ID: '1kewm3N-zFUSa0MPLNc4iVgjYtTfBwLCB',
  FOLDER_TRANSCRIPT_ID: '17sNcoP2dYGkH9DhGazb_UmBLBZW2-Tzt',
  TARGET_EMAIL: 'christof.schmerbeck@audemarspiguet.com'
};

const LOW_CONFIDENCE_THRESHOLD = 0.7;

// Wird in der PWA-Fusszeile angezeigt. Bei jedem Deploy mit spuerbarer
// Aenderung hochzaehlen - der Zusatz daneben ("Direktweg aktiv") prueft sich
// dagegen selbst und kann nicht vergessen werden.
const SERVER_VERSION = '2026-08-19 · Direkt-Upload';

function doGet(e) {
  if (e && e.parameter && e.parameter.action === 'status' && e.parameter.file) {
    return getUploadStatus_(e.parameter.file);
  }
  if (e && e.parameter && e.parameter.action === 'version') {
    return versionResponse_();
  }
  return HtmlService.createHtmlOutputFromFile('Index')
    .setTitle('Audio Transkription Manager')
    .setXFrameOptionsMode(HtmlService.XFrameOptionsMode.ALLOWALL)
    .addMetaTag('viewport', 'width=device-width, initial-scale=1');
}

/**
 * Schlanker Status-Endpoint (?action=status&file=<name>) fuer die
 * Share-Target-PWA, damit sie live anzeigen kann: hochgeladen -> Transkription
 * laeuft -> fertig (Mail versendet). Liefert absichtlich nur den groben
 * Zustand, keine internen Details (Trigger, AssemblyAI-IDs etc.).
 */
function getUploadStatus_(fileName) {
  const archiveFolder = DriveApp.getFolderById(CONFIG.FOLDER_ARCHIVE_ID);
  if (archiveFolder.getFilesByName(fileName).hasNext()) {
    return statusResponse_('done');
  }

  if (getJobState(fileName)) {
    return statusResponse_('transcribing');
  }

  const workFolders = DriveApp.getFoldersByName(CONFIG.FOLDER_WORK_NAME);
  if (workFolders.hasNext() && workFolders.next().getFilesByName(fileName).hasNext()) {
    // Datei liegt noch da, aber kein Job-Status mehr -> endgueltig fehlgeschlagen
    // (checkAllPendingStatuses_ loescht den Job-Status, wenn es aufgibt).
    return statusResponse_('error');
  }

  return statusResponse_('unknown');
}

/**
 * Selbstauskunft der DEPLOYTEN Skriptversion (?action=version).
 *
 * "directUpload" ist bewusst keine gepflegte Konstante, sondern liest den
 * tatsaechlich deployten Code: handleUploadAction_ muss existieren UND in
 * doPost aufgerufen werden. Genau diese Kombination fehlte am 19.08.2026 -
 * die Datei war da, der Aufruf nicht, und von aussen sah alles korrekt aus.
 */
function versionResponse_() {
  let routed = false;
  try {
    routed = String(doPost).indexOf('handleUploadAction_') !== -1;
  } catch (err) {
    routed = false;
  }

  return ContentService.createTextOutput(JSON.stringify({
    server: SERVER_VERSION,
    directUpload: typeof handleUploadAction_ === 'function' && routed
  })).setMimeType(ContentService.MimeType.JSON);
}

function statusResponse_(state) {
  return ContentService.createTextOutput(JSON.stringify({ state: state }))
    .setMimeType(ContentService.MimeType.JSON);
}

/**
 * Ruft den API-Key aus den Skripteigenschaften ab.
 */
function getApiKey() {
  const key = PropertiesService.getScriptProperties().getProperty('ASSEMBLYAI_API_KEY');
  if (!key) {
    throw new Error('Systemfehler: ASSEMBLYAI_API_KEY ist in den Skripteigenschaften nicht konfiguriert.');
  }
  return key;
}

// --- Warteschlange / wartende Dateien ---

/**
 * Liefert alle wartenden Audiodateien (ohne Archiv), neueste zuerst.
 */
function getPendingAudioFiles() {
  const folders = DriveApp.getFoldersByName(CONFIG.FOLDER_WORK_NAME);
  if (!folders.hasNext()) return [];

  const folder = folders.next();
  const archiveFolder = DriveApp.getFolderById(CONFIG.FOLDER_ARCHIVE_ID);
  const files = folder.getFiles();
  const result = [];

  while (files.hasNext()) {
    const file = files.next();
    if (isAudioFile(file.getName()) && !isFileInArchive(file, archiveFolder)) {
      result.push(file);
    }
  }

  result.sort((a, b) => b.getLastUpdated().getTime() - a.getLastUpdated().getTime());
  return result;
}

function getLatestAudioFile() {
  const pending = getPendingAudioFiles();
  return pending.length > 0 ? pending[0] : null;
}

/**
 * Aufbereitete Liste für die Oberfläche.
 */
function buildPendingList() {
  const tz = 'Europe/Zurich';
  return getPendingAudioFiles().map((file, idx) => ({
    name: file.getName(),
    date: Utilities.formatDate(file.getLastUpdated(), tz, 'dd.MM.yyyy HH:mm'),
    isNext: idx === 0
  }));
}

// --- Hauptablauf: Start ---

/**
 * Startet die Transkription fuer EINE Datei (Kernlogik, ohne Lock/Liste -
 * wird sowohl vom manuellen UI-Button als auch vom automatischen
 * Massen-Start ueber startAllPendingTranscriptions_() genutzt).
 */
function startTranscriptionForFile_(file) {
  const originalName = file.getName();
  const lastUpdated = file.getLastUpdated();

  const parts = normalizeAudioFilename(originalName);
  const stamp = buildCanonicalStamp(parts, lastUpdated);
  let normalizedName = `${stamp}${parts.ext}`;

  // Kollisionsschutz: wenn beim Verarbeiten mehrerer Dateien in einem
  // Durchlauf zwei ohne erkennbaren Zeitstempel im Namen auf denselben
  // Stamp faellen (z.B. beide "00" Sekunden), Namen eindeutig machen -
  // sonst wuerde die zweite Datei faelschlich als "laeuft schon" gelten
  // und nie wirklich transkribiert werden.
  const parentFolders = file.getParents();
  const parentFolder = parentFolders.hasNext() ? parentFolders.next() : null;
  if (parentFolder) {
    normalizedName = ensureUniqueFileName_(parentFolder, normalizedName, file.getId());
  }

  if (originalName !== normalizedName) {
    try {
      file.setName(normalizedName);
      Logger.log(`Datei umbenannt: "${originalName}" -> "${normalizedName}"`);
    } catch (e) {
      Logger.log(`Umbenennung fehlgeschlagen: ${e.message}`);
    }
  }

  const existing = getJobState(normalizedName);
  if (existing) {
    return { status: 'processing', fileId: file.getId(), fileName: normalizedName, message: 'Transkription läuft bereits im Hintergrund.' };
  }

  const fileUrl = file.getDownloadUrl();
  if (!fileUrl) {
    return { status: 'error', fileId: file.getId(), fileName: normalizedName, message: 'Datei-URL konnte nicht abgerufen werden.' };
  }

  const apiKey = getApiKey();
  const payloadData = {
    'speech_models': ['universal-3-5-pro', 'universal-2'],
    'audio_url': fileUrl,
    'language_detection': true,
    'punctuate': true,
    'format_text': true,
    'speaker_labels': true,
    'temperature': 0.1,
    'speech_understanding': {
      'request': {
        'summarization': {
          'summary_type': 'bullets',
          'effort': 'low'
        },
        'speaker_identification': {
          'speaker_type': 'role',
          'known_values': [
            'Procurement Specialist',
            'Procurement Manager',
            'IT Specialist',
            'IT Lead',
            'HR Specialist',
            'Employee',
            'Vendor Representative'
          ]
        }
      }
    },
    'prompt': 'Always: Transcribe speech with your best guess based on context in all possible scenarios where speech is present in the audio. Context: Conversations involving procurement, internal IT discussions, HR meetings, or private discussions. Required: Preserve the original language(s) and script as spoken, including code-switching. Use digits for all numbers, percentages, and measurements. If a segment is truly unintelligible, write (unclear). Non-negotiable: Use standard spelling and the most contextually correct spelling of all proper nouns, specifically the names "Audemars Piguet", iValua, Hanane, Emmy and IT terminology.'
  };

  const response = UrlFetchApp.fetch('https://api.assemblyai.com/v2/transcript', {
    method: 'POST',
    headers: {
      'authorization': apiKey,
      'content-type': 'application/json'
    },
    payload: JSON.stringify(payloadData),
    muteHttpExceptions: true
  });

  if (response.getResponseCode() !== 200) {
    return { status: 'error', fileId: file.getId(), fileName: normalizedName, message: `API Fehler beim Starten: ${response.getContentText()}` };
  }

  const data = JSON.parse(response.getContentText());
  if (!data.id) {
    return { status: 'error', fileId: file.getId(), fileName: normalizedName, message: 'Keine Transkriptions-ID von AssemblyAI erhalten.' };
  }

  const saved = saveJobState(normalizedName, { transcriptId: data.id, emailed: false });
  if (!saved) {
    return { status: 'error', fileId: file.getId(), fileName: normalizedName, message: 'Transkriptions-ID konnte nicht im System gespeichert werden.' };
  }

  return { status: 'processing', fileId: file.getId(), fileName: normalizedName, message: 'Transkription erfolgreich gestartet.' };
}

/**
 * Manueller Start ueber den Button in der Web-App: nur die neueste Datei.
 */
function startTranscriptionWorkflow() {
  const scriptLock = LockService.getScriptLock();
  if (!scriptLock.tryLock(5000)) {
    return { status: 'error', message: 'Das Skript wird bereits von einem anderen Prozess ausgeführt.', pending: buildPendingList() };
  }

  try {
    const latestFile = getLatestAudioFile();
    if (!latestFile) {
      return { status: 'no_files', message: 'Keine neuen Audiodateien im Ordner gefunden.', pending: [] };
    }

    const result = startTranscriptionForFile_(latestFile);
    return Object.assign({}, result, { pending: buildPendingList() });

  } catch (error) {
    Logger.log(`Fehler in startTranscriptionWorkflow: ${error.message}`);
    return { status: 'error', message: error.message, pending: buildPendingList() };
  } finally {
    scriptLock.releaseLock();
  }
}

/**
 * Automatischer Start ueber doPost(): ALLE wartenden Dateien, nicht nur die
 * neueste. Wird bei jedem Upload aus der Share-Target-PWA aufgerufen, damit
 * auch aeltere, liegengebliebene Dateien mit abgearbeitet werden.
 * Bei einem endgueltigen Fehler pro Datei wird eine Fehler-Mail verschickt,
 * die anderen Dateien in der Liste werden trotzdem weiterverarbeitet.
 */
function startAllPendingTranscriptions_() {
  const scriptLock = LockService.getScriptLock();
  if (!scriptLock.tryLock(10000)) {
    Logger.log('startAllPendingTranscriptions_: Lock nicht erhalten, ueberspringe.');
    return [];
  }

  try {
    const pending = getPendingAudioFiles();
    const results = [];

    pending.forEach((file) => {
      try {
        const result = startTranscriptionForFile_(file);
        results.push(result);
        if (result.status === 'error') {
          notifyTranscriptionError_(result.fileName || file.getName(), result.message);
        }
      } catch (error) {
        Logger.log(`Fehler beim Starten fuer "${file.getName()}": ${error.message}`);
        notifyTranscriptionError_(file.getName(), error.message);
        results.push({ status: 'error', fileId: file.getId(), fileName: file.getName(), message: error.message });
      }
    });

    return results;
  } finally {
    scriptLock.releaseLock();
  }
}

// --- Hauptablauf: Status prüfen ---

/**
 * Prueft den Status EINER Datei (Kernlogik, ohne Lock - wird sowohl vom
 * manuellen UI-Button als auch vom automatischen Massen-Check genutzt).
 * transient:true bedeutet "kurzfristiges Problem, spaeter erneut versuchen"
 * (z.B. Netzwerkfehler); ohne dieses Flag ist der Fehler endgueltig.
 */
function checkSingleFileStatus_(file, fileName, state) {
  const apiKey = getApiKey();
  const statusResponse = UrlFetchApp.fetch(`https://api.assemblyai.com/v2/transcript/${state.transcriptId}`, {
    headers: { 'authorization': apiKey },
    muteHttpExceptions: true
  });

  if (statusResponse.getResponseCode() !== 200) {
    return { status: 'error', fileName: fileName, transient: true, message: 'Fehler beim Abrufen des Status von AssemblyAI.' };
  }

  const statusData = JSON.parse(statusResponse.getContentText());
  const aiStatus = statusData.status;

  if (aiStatus === 'processing' || aiStatus === 'queued') {
    return { status: 'processing', fileName: fileName, message: `Transkription wird verarbeitet (Status: ${aiStatus}).` };
  }

  if (aiStatus === 'completed') {
    return finalizeCompletedTranscript(file, fileName, state, statusData);
  }

  return { status: 'error', fileName: fileName, message: `Transkription fehlgeschlagen oder abgebrochen (Status: ${aiStatus}).` };
}

/**
 * Manueller Status-Check ueber den Button in der Web-App: nur die neueste Datei.
 */
function checkStatusWorkflow() {
  try {
    const latestFile = getLatestAudioFile();
    if (!latestFile) {
      return { status: 'idle', message: 'Keine Audiodateien zur Verarbeitung vorhanden.', pending: [] };
    }

    const fileName = latestFile.getName();
    const state = getJobState(fileName);

    if (!state) {
      return { status: 'idle', fileName: fileName, message: 'Datei gefunden. Bereit zum Starten.', pending: buildPendingList() };
    }

    const result = checkSingleFileStatus_(latestFile, fileName, state);
    return Object.assign({}, result, { pending: buildPendingList() });

  } catch (error) {
    Logger.log(`Fehler in checkStatusWorkflow: ${error.message}`);
    return { status: 'error', message: error.message, pending: buildPendingList() };
  }
}

/**
 * Automatischer Status-Check ueber den Trigger: ALLE Dateien, die gerade
 * einen Job-Status haben (nicht nur die neueste). Bei einem endgueltigen
 * Fehler (AssemblyAI meldet "failed"/"error") wird eine Fehler-Mail
 * verschickt und der Job-Status geloescht, damit nicht endlos weiterprobiert
 * wird. Bei einem transienten Fehler (z.B. Netzwerk) bleibt der Job-Status
 * stehen, es wird beim naechsten Trigger-Lauf erneut versucht.
 */
function checkAllPendingStatuses_() {
  const fileNames = listInFlightFileNames_();
  const results = [];

  fileNames.forEach((fileName) => {
    const state = getJobState(fileName);
    if (!state) return;

    const file = findFileInWorkFolder_(fileName);
    if (!file) {
      deleteJobState(fileName);
      return;
    }

    try {
      const result = checkSingleFileStatus_(file, fileName, state);
      results.push(result);

      if (result.status === 'error' && !result.transient) {
        notifyTranscriptionError_(fileName, result.message);
        deleteJobState(fileName);
      }
    } catch (error) {
      Logger.log(`Fehler beim Pruefen von "${fileName}": ${error.message}`);
      results.push({ status: 'error', fileName: fileName, transient: true, message: error.message });
    }
  });

  return results;
}

function listInFlightFileNames_() {
  const folder = DriveApp.getFolderById(CONFIG.FOLDER_TRANSCRIPT_ID);
  const files = folder.getFiles();
  const names = [];
  while (files.hasNext()) {
    const match = files.next().getName().match(/^transcription_id_(.+)\.txt$/);
    if (match) names.push(match[1]);
  }
  return names;
}

function findFileInWorkFolder_(fileName) {
  const folders = DriveApp.getFoldersByName(CONFIG.FOLDER_WORK_NAME);
  if (!folders.hasNext()) return null;
  const files = folders.next().getFilesByName(fileName);
  return files.hasNext() ? files.next() : null;
}

/**
 * Schließt eine fertige Transkription ab.
 *
 * Wichtige Reihenfolge (verhindert Datenverlust und Doppelversand):
 *   1) E-Mail senden  ->  2) "emailed" merken  ->  3) archivieren  ->  4) Job-Status löschen
 *
 * Der Job-Status (mit Transkriptions-ID) bleibt erhalten, bis ALLES erledigt ist.
 * Dadurch wird der Ablauf beim erneuten Öffnen der App automatisch fortgesetzt,
 * falls er zwischendurch unterbrochen wurde – ohne dass das Transkript verloren geht.
 */
function finalizeCompletedTranscript(file, fileName, state, statusData) {
  const scriptLock = LockService.getScriptLock();
  if (!scriptLock.tryLock(5000)) {
    // Eine andere Ausführung finalisiert gerade -> nicht doppelt verarbeiten.
    return { status: 'processing', fileName: fileName, message: 'Transkription abgeschlossen, wird finalisiert ...' };
  }

  try {
    // Zustand frisch laden – kann sich seit dem Polling geändert haben.
    const current = getJobState(fileName) || state;

    // 1) E-Mail nur senden, wenn noch nicht geschehen.
    if (!current.emailed) {
      const transcriptText = extractTranscriptText(statusData);
      const summaryInfo = extractSummaryInfo_(statusData);
      const confidence = typeof statusData.confidence === 'number' ? statusData.confidence : null;
      sendEmailTranscriptOnly(CONFIG.TARGET_EMAIL, fileName, transcriptText, file.getLastUpdated(), summaryInfo, confidence);

      // 2) Sofort merken, dass die Mail raus ist -> verhindert Doppelversand bei erneutem Lauf.
      current.emailed = true;
      saveJobState(fileName, current);
    }

    // 3) Erst nach erfolgreichem Versand archivieren.
    moveFileToArchive(file);

    // 4) Zuletzt den Job-Status löschen (bis hierher ist ein Wiederanlauf jederzeit möglich).
    deleteJobState(fileName);

    return { status: 'completed', fileName: fileName, message: 'Transkription abgeschlossen. E-Mail versendet und Datei archiviert.' };

  } catch (error) {
    Logger.log(`Fehler beim Finalisieren: ${error.message}`);
    // transient: der eigentliche Job war fertig, nur der letzte Schritt
    // (Mail/Archiv) ist gescheitert - beim naechsten Versuch weitermachen.
    return { status: 'error', fileName: fileName, transient: true, message: `Abschluss fehlgeschlagen: ${error.message}` };
  } finally {
    scriptLock.releaseLock();
  }
}

/**
 * Baut den Transkript-Text aus den AssemblyAI-Daten.
 */
function extractTranscriptText(statusData) {
  if (statusData.utterances && statusData.utterances.length > 0) {
    return statusData.utterances.map(utterance => {
      const roleLabel = (utterance.speaker_identification && utterance.speaker_identification.value)
        ? utterance.speaker_identification.value
        : `Sprecher ${utterance.speaker}`;
      return `${roleLabel}: ${utterance.text}`;
    }).join('\n\n');
  }
  if (statusData.text) {
    return statusData.text;
  }
  return 'Transkript nicht verfügbar.';
}

/**
 * Liest die von AssemblyAI generierte Executive Summary aus. Nutzt den
 * neueren speech_understanding.request.summarization-Weg (NICHT das
 * deprecated Top-Level summarization:true, das nur mit dem alten
 * universal-2-Modell funktioniert und mit universal-3-5-pro schweigend
 * nichts liefert).
 *
 * Echte Antwortform (verifiziert per Test):
 * speech_understanding.response.summarization = {
 *   status: 'success', summary_type: 'bullets', effort: 'low',
 *   summary: [ { start, end, bullets: [...], headline: '...' }, ... ]
 * }
 */
function extractSummaryInfo_(statusData) {
  const summarization = statusData.speech_understanding
    && statusData.speech_understanding.response
    && statusData.speech_understanding.response.summarization;

  if (!summarization || summarization.status !== 'success' || !Array.isArray(summarization.summary) || summarization.summary.length === 0) {
    return { text: null, headline: null };
  }

  const chapters = summarization.summary;
  const lines = [];
  chapters.forEach((chapter) => {
    if (Array.isArray(chapter.bullets)) {
      chapter.bullets.forEach((bullet) => lines.push(`- ${bullet}`));
    } else if (chapter.paragraph) {
      lines.push(chapter.paragraph);
    }
  });

  if (lines.length === 0) {
    return { text: null, headline: null };
  }

  const headline = chapters[0].headline || null;
  return { text: lines.join('\n'), headline: headline ? headline.trim() : null };
}

/**
 * Kurzer Themen-Hinweis fuer den Mail-Betreff, z.B.
 * "Meeting Transcript 2026-08-14 15-19-00 — Budgetfreigabe Q3".
 * Nutzt die von AssemblyAI gelieferte Headline, wenn vorhanden.
 */
function extractSubjectHint_(headline) {
  if (!headline) return '';
  const MAX_LEN = 60;
  return headline.length > MAX_LEN ? headline.slice(0, MAX_LEN - 1) + '…' : headline;
}

/**
 * Benachrichtigt per Mail, wenn eine Transkription endgueltig gescheitert
 * ist (die Datei bleibt dabei im Arbeitsordner liegen, kann manuell ueber
 * die App erneut versucht werden).
 */
function notifyTranscriptionError_(fileName, reason) {
  try {
    GmailApp.sendEmail(
      CONFIG.TARGET_EMAIL,
      `Transkription fehlgeschlagen: ${fileName}`,
      `Die automatische Transkription von "${fileName}" ist fehlgeschlagen.\n\nGrund: ${reason}\n\nDie Datei liegt weiterhin im "${CONFIG.FOLDER_WORK_NAME}"-Ordner und kann bei Bedarf manuell ueber die App erneut versucht werden.`
    );
  } catch (e) {
    Logger.log('Fehler-Benachrichtigung konnte nicht gesendet werden: ' + e.message);
  }
}

// --- Hilfsfunktionen für Dateinamen ---

function normalizeAudioFilename(rawName) {
  const name = rawName.trim();
  const extMatch = name.match(/\.([a-z0-9]+)$/i);
  let ext = extMatch ? '.' + extMatch[1].toLowerCase() : '.m4a';

  const iso = name.match(/^(\d{4})-(\d{2})-(\d{2})[_ ](\d{2})-(\d{2})-(\d{2})(?:\.[a-z0-9]+)?$/i);
  if (iso) {
    const [, Y, M, D, h, m, s] = iso;
    return {
      normalized: `${Y}-${M}-${D}_${h}-${m}-${s}${ext}`,
      year: Y, month: M, day: D, hours: h, minutes: m, seconds: s, ext
    };
  }

  const ger = name
    .replace(/\s+/g, ' ')
    .match(/^(\d{1,2})\.?\s*([A-Za-zäöüÄÖÜ\.]+)\s*(?:um\s*)?(\d{1,2})-(\d{2})(?:-(\d{2}))?(?:\.[a-z0-9]+)?$/i);

  if (ger) {
    let [, Draw, monRaw, hRaw, mRaw, sRaw] = ger;
    const monthMap = {
      'januar': 1, 'jan': 1, 'jan.': 1,
      'februar': 2, 'feb': 2, 'feb.': 2,
      'märz': 3, 'maerz': 3, 'mrz': 3, 'mrz.': 3, 'märz.': 3,
      'april': 4, 'apr': 4, 'apr.': 4,
      'mai': 5,
      'juni': 6, 'jun': 6, 'jun.': 6,
      'juli': 7, 'jul': 7, 'jul.': 7,
      'august': 8, 'aug': 8, 'aug.': 8,
      'september': 9, 'sep': 9, 'sept': 9, 'sep.': 9, 'sept.': 9,
      'oktober': 10, 'okt': 10, 'okt.': 10,
      'november': 11, 'nov': 11, 'nov.': 11,
      'dezember': 12, 'dez': 12, 'dez.': 12
    };
    const monKey = monRaw.toLowerCase();
    const Mnum = monthMap[monKey];
    if (!Mnum) throw new Error(`Unrecognised month: "${monRaw}" in "${rawName}"`);

    const now = new Date();
    const Y = String(now.getFullYear());
    const D = String(parseInt(Draw, 10)).padStart(2, '0');
    const M = String(Mnum).padStart(2, '0');
    const h = String(parseInt(hRaw, 10)).padStart(2, '0');
    const m = String(parseInt(mRaw, 10)).padStart(2, '0');
    const s = sRaw ? String(parseInt(sRaw, 10)).padStart(2, '0') : '00';

    return {
      normalized: `${Y}-${M}-${D}_${h}-${m}-${s}${ext}`,
      year: Y, month: M, day: D, hours: h, minutes: m, seconds: s, ext
    };
  }

  const timeOnly = name.match(/(\d{1,2})-(\d{2})(?:-(\d{2}))?/);
  if (timeOnly) {
    const [, hRaw, mRaw, sRaw] = timeOnly;
    const h = String(parseInt(hRaw, 10)).padStart(2, '0');
    const m = String(parseInt(mRaw, 10)).padStart(2, '0');
    const s = sRaw ? String(parseInt(sRaw, 10)).padStart(2, '0') : '00';
    return { normalized: null, year: null, month: null, day: null, hours: h, minutes: m, seconds: s, ext };
  }

  return { normalized: null, year: null, month: null, day: null, hours: null, minutes: null, seconds: null, ext };
}

function buildCanonicalStamp(parts, lastUpdatedDate) {
  const tz = 'Europe/Zurich';
  const d = lastUpdatedDate || new Date();
  const Y = parts.year || Utilities.formatDate(d, tz, 'yyyy');
  const M = parts.month || Utilities.formatDate(d, tz, 'MM');
  const D = parts.day || Utilities.formatDate(d, tz, 'dd');
  const h = parts.hours || Utilities.formatDate(d, tz, 'HH');
  const m = parts.minutes || Utilities.formatDate(d, tz, 'mm');
  const s = parts.seconds || Utilities.formatDate(d, tz, 'ss');
  return `${Y}-${M}-${D}_${h}-${m}-${s}`;
}

/**
 * Haengt bei Bedarf "-2", "-3", ... an den Dateinamen an, bis er im
 * angegebenen Ordner eindeutig ist (ignoriert die Datei mit excludeFileId
 * selbst, falls sie zufaellig schon den Zielnamen traegt).
 */
function ensureUniqueFileName_(folder, candidateName, excludeFileId) {
  let name = candidateName;
  let suffix = 1;

  while (true) {
    const matches = folder.getFilesByName(name);
    let collision = false;
    while (matches.hasNext()) {
      if (matches.next().getId() !== excludeFileId) {
        collision = true;
      }
    }
    if (!collision) return name;

    suffix += 1;
    const dotIdx = candidateName.lastIndexOf('.');
    name = dotIdx >= 0
      ? `${candidateName.slice(0, dotIdx)}-${suffix}${candidateName.slice(dotIdx)}`
      : `${candidateName}-${suffix}`;
  }
}

function buildMeetingSubjectAndHeader(fileName, lastUpdatedDate, topicHint) {
  const parsed = normalizeAudioFilename(fileName || '');
  const stamp = buildCanonicalStamp(parsed, lastUpdatedDate);
  const humanStamp = stamp.replace('_', ' ');
  const suffix = topicHint ? ` — ${topicHint}` : '';
  const subject = `Meeting Transcript ${humanStamp}${suffix}`;
  const headerLine = `Meeting Transcript ${humanStamp}`;
  return { subject, headerLine, humanStamp };
}

// --- Dateiverwaltung & Infrastruktur ---

function isFileInArchive(file, archiveFolder) {
  const parentFolders = file.getParents();
  while (parentFolders.hasNext()) {
    if (parentFolders.next().getId() === archiveFolder.getId()) {
      return true;
    }
  }
  return false;
}

function isAudioFile(fileName) {
  const audioExtensions = ['.mp3', '.wav', '.m4a', '.ogg'];
  return audioExtensions.some(ext => fileName.toLowerCase().endsWith(ext));
}

function sendEmailTranscriptOnly(recipient, fileName, transcript, lastUpdatedDate, summaryInfo, confidence) {
  try {
    const safeName = (typeof fileName === 'string' && fileName.trim()) ? fileName : '';
    const topicHint = extractSubjectHint_(summaryInfo && summaryInfo.headline);
    const { subject, headerLine, humanStamp } = buildMeetingSubjectAndHeader(safeName, lastUpdatedDate, topicHint);
    const attachmentName = `${humanStamp} Meeting transcript.txt`;
    const blob = Utilities.newBlob(transcript, 'text/plain', attachmentName);

    const bodyLines = [headerLine, ''];

    if (typeof confidence === 'number' && confidence < LOW_CONFIDENCE_THRESHOLD) {
      bodyLines.push(
        `⚠️ Hinweis: Erkennungsqualität bei dieser Aufnahme niedriger als sonst (Konfidenz: ${Math.round(confidence * 100)}%) - bitte Transkript prüfen.`,
        ''
      );
    }

    if (summaryInfo && summaryInfo.text) {
      bodyLines.push('Executive Summary:', summaryInfo.text);
    }

    GmailApp.sendEmail(recipient, subject, bodyLines.join('\n').trim(), { attachments: [blob] });
    Logger.log('E-Mail erfolgreich versendet.');
  } catch (error) {
    Logger.log(`Fehler beim Versand der E-Mail: ${error.message}`);
    throw error;
  }
}

function moveFileToArchive(file) {
  const archiveFolder = DriveApp.getFolderById(CONFIG.FOLDER_ARCHIVE_ID);
  try {
    // Idempotent: bereits archivierte Dateien werden nicht erneut verschoben.
    if (isFileInArchive(file, archiveFolder)) {
      Logger.log(`Datei bereits im Archiv: ${file.getName()}`);
      return;
    }
    file.moveTo(archiveFolder);
    Logger.log(`Datei ins Archiv verschoben: ${file.getName()}`);
  } catch (error) {
    Logger.log(`Fehler beim Verschieben ins Archiv: ${error.message}`);
    throw error;
  }
}

// --- Job-Status (Transkriptions-ID + "emailed"-Flag) ---

function jobStateFileName(fileName) {
  return `transcription_id_${fileName}.txt`;
}

function saveJobState(fileName, state) {
  const MAX_RETRIES = 3;
  const INITIAL_DELAY = 2000;
  const targetName = jobStateFileName(fileName);
  const content = JSON.stringify(state);

  for (let attempt = 1; attempt <= MAX_RETRIES; attempt++) {
    try {
      const folder = DriveApp.getFolderById(CONFIG.FOLDER_TRANSCRIPT_ID);
      let file;
      const existingFiles = folder.getFilesByName(targetName);
      if (existingFiles.hasNext()) {
        file = existingFiles.next();
        file.setContent(content);
      } else {
        file = folder.createFile(targetName, content, MimeType.PLAIN_TEXT);
      }

      const written = file.getBlob().getDataAsString().trim();
      if (written === content) {
        return true;
      }
    } catch (error) {
      Logger.log(`Fehler bei Speicherversuch ${attempt}: ${error.message}`);
    }
    if (attempt < MAX_RETRIES) {
      Utilities.sleep(INITIAL_DELAY * Math.pow(2, attempt - 1));
    }
  }
  return false;
}

function getJobState(fileName) {
  const folder = DriveApp.getFolderById(CONFIG.FOLDER_TRANSCRIPT_ID);
  const files = folder.getFilesByName(jobStateFileName(fileName));
  if (!files.hasNext()) return null;

  const raw = files.next().getBlob().getDataAsString().trim();
  if (!raw) return null;

  try {
    const obj = JSON.parse(raw);
    if (obj && obj.transcriptId) {
      return { transcriptId: obj.transcriptId, emailed: !!obj.emailed };
    }
  } catch (e) {
    // Abwärtskompatibilität: alte Dateien enthielten nur die reine ID als Text.
    return { transcriptId: raw, emailed: false };
  }
  return null;
}

function deleteJobState(fileName) {
  const folder = DriveApp.getFolderById(CONFIG.FOLDER_TRANSCRIPT_ID);
  const files = folder.getFilesByName(jobStateFileName(fileName));
  while (files.hasNext()) {
    files.next().setTrashed(true);
  }
}
