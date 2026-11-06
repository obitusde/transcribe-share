/**
 * DirectUpload.gs - Schnittstelle fuer die Android-App "Aufnahme".
 * Version 5 (2026-11-06)
 *
 * Neu in Version 5:
 *   - Die Executive Summary in Mail und .txt ist immer auf Englisch, egal in
 *     welcher Sprache gesprochen wurde. Sie entsteht im selben Aufruf wie der
 *     Titel aus dem ganzen Transkript (Themen, Entscheidungen, offene Punkte,
 *     Aufgaben mit Verantwortlichen). Faellt der Aufruf aus, bleibt die
 *     Zusammenfassung von AssemblyAI (in der Sprache des Gespraechs).
 *   - Der Titel bleibt in der Sprache des Gespraechs.
 *   - Code.gs unveraendert gegenueber Version 4.
 *
 * Version 4:
 *   - Kurzer Titel pro Gespraech, damit man in der Mail sofort sieht, welches
 *     Gespraech es war. Ein kleines Sprachmodell ueber das LLM Gateway von
 *     AssemblyAI (gleicher API-Key, kein neuer Dienst) liest Zusammenfassung
 *     und Transkript und liefert 3-8 Woerter in der Sprache des Gespraechs.
 *     Faellt das aus: erste Ueberschrift der AssemblyAI-Zusammenfassung,
 *     sonst der Kontext aus der App.
 *   - Mail-Betreff "Mo 02.11.2026 09:27 · Titel" (Wochentag, Datum, Uhrzeit
 *     der Aufnahme, ohne Sekunden). Dafuer aendert sich in Code.gs die
 *     Funktion buildMeetingSubjectAndHeader (komplette Code.gs separat).
 *   - Der Titel steht auch in der ersten Zeile der .txt ("Titel: ..."), die
 *     App zeigt ihn in der Liste an.
 *
 * Version 3:
 *   - Aufnahmen im Archivordner werden 28 Tage nach dem Upload in den
 *     Drive-Papierkorb gelegt (Google leert ihn nach 30 Tagen endgueltig).
 *     Das gilt fuer ALLE Audiodateien im Archiv, auch alte aus dem Google
 *     Recorder. Transkripte (.txt, Mails) bleiben. Ein taeglicher Trigger
 *     (cleanupArchive, nachts um 3) erledigt das; er richtet sich beim
 *     ersten "hello" der App selbst ein. Von Hand: cleanupArchiveNow().
 *   - Code.gs bleibt gegenueber Version 2 unveraendert.
 *
 * Version 2 (ersetzte Version 1):
 *   - Geheimer Schluessel (Skripteigenschaft UPLOAD_SECRET). Ist er gesetzt,
 *     wird JEDE POST-Anfrage ohne passenden Schluessel abgelehnt - auch der
 *     alte Base64-Weg in doPost(), der sonst ungeschuetzt weiterliefe.
 *   - Kontext pro Aufnahme (z. B. "1:1 mit Emmy"). Er geht als Zusatz in den
 *     AssemblyAI-Prompt und steht oben im Transkript.
 *   - Mail-Auswahl pro Aufnahme: Arbeit, Privat oder keine Mail.
 *     Die Adressen stehen NUR in den Skripteigenschaften (MAIL_WORK,
 *     MAIL_PRIVATE), nie im Code. Fehlt MAIL_WORK, gilt wie bisher
 *     CONFIG.TARGET_EMAIL aus Code.gs.
 *   - Jedes fertige Transkript wird zusaetzlich als .txt im Drive-Ordner
 *     "Transkripte" abgelegt (neben "Archive"). Von dort holt die App es ab.
 *   - Transkribierte Minuten pro Monat werden mitgezaehlt (Skripteigenschaft
 *     USAGE_JJJJ-MM, von AssemblyAI gemeldete Audiolaenge). Die App zeigt
 *     den laufenden Monat an. Zaehlt auch Aufnahmen, die nicht ueber die App
 *     kamen.
 *
 * Kontext und Mail-Auswahl reisen in der Drive-Beschreibung der Audiodatei
 * mit (letzte Zeile "#app mail=..."). So gehen sie nicht verloren, egal ob
 * completeUpload oder FolderWatcher die Transkription startet. Dateien ohne
 * diese Zeile (z. B. aus dem Google Recorder per Drive geteilt) laufen genau
 * wie bisher.
 *
 * EINBAU: siehe apps-script/ANLEITUNG-APP-V2.md (erstmalig) und
 * apps-script/ANLEITUNG-APP-V3.md (Update von Version 2).
 * Code.gs braucht zwei Ein-Zeilen-Aenderungen, die applyAppOptions_() und
 * deliverTranscript_() aus dieser Datei aufrufen.
 */

var DRIVE_RESUMABLE_INIT_URL =
  'https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&supportsAllDrives=true';

/** Origins, deren Browser (alte PWA) eine Session-URL weiterbenutzen duerfen. */
var ALLOWED_UPLOAD_ORIGINS = ['https://obitusde.github.io'];

/** Die App fragt per "hello" danach und schaltet neue Funktionen erst ab 2 frei. */
var APP_API_VERSION = 5;

var APP_MAIL_CHOICES = ['work', 'private', 'none'];
var APP_CONTEXT_MAX = 500;
var APP_MARKER_RE = /(?:^|\n)#app mail=(work|private|none)\s*$/;
var TRANSCRIPT_FOLDER_NAME = 'Transkripte';
var USAGE_TZ = 'Europe/Zurich';

/** So viele Tage nach dem Upload bleibt eine Aufnahme im Drive-Archiv. */
var ARCHIVE_KEEP_DAYS = 28;
var ARCHIVE_CLEANUP_HANDLER = 'cleanupArchive';
/** Ein Lauf darf hoechstens so lange dauern (Apps-Script-Limit: 6 Min.). */
var ARCHIVE_CLEANUP_MAX_MS = 4 * 60 * 1000;

/** Titel und englische Executive Summary ueber das LLM Gateway von AssemblyAI. */
var LLM_GATEWAY_URL = 'https://llm-gateway.assemblyai.com/v1/chat/completions';
/** Der Reihe nach probiert; das erste brauchbare Ergebnis gilt. */
var TITLE_MODELS = ['claude-haiku-4-5-20251001', 'gemini-2.5-flash-lite'];
var TITLE_MAX = 60;
var SUMMARY_MAX_BULLETS = 10;
/**
 * So viel Transkript geht ans Modell (Zeichen). 300.000 Zeichen sind etwa
 * 5 Stunden Gespraech; nur noch laengere werden in der Mitte gekuerzt.
 */
var LLM_TRANSCRIPT_HEAD = 260000;
var LLM_TRANSCRIPT_TAIL = 40000;
var LLM_MAX_TOKENS = 1200;
var WEEKDAYS_DE = ['So', 'Mo', 'Di', 'Mi', 'Do', 'Fr', 'Sa'];

/**
 * Router, aufgerufen ganz oben in doPost().
 *
 * Gibt einen fertigen TextOutput zurueck, wenn die Anfrage zu diesem Modul
 * gehoert oder abgelehnt wird - sonst null, damit doPost() weitermacht.
 */
function handleUploadAction_(e) {
  var raw = e && e.postData && e.postData.contents;
  var data = null;
  if (raw) {
    try {
      data = JSON.parse(raw);
    } catch (err) {
      data = null;
    }
  }

  var secret = getUploadSecret_();
  if (secret && !(data && typeof data.secret === 'string' && data.secret === secret)) {
    return jsonResponse_({ status: 'error', code: 'auth', message: 'Schluessel fehlt oder ist falsch.' });
  }

  if (!data || !data.action) return null;

  try {
    switch (data.action) {
      case 'hello': return jsonResponse_(hello_());
      case 'initUpload': return jsonResponse_(initUpload_(data));
      case 'completeUpload': return jsonResponse_(completeUpload_(data));
      case 'startPending': return jsonResponse_(startPending_());
      case 'transcript': return jsonResponse_(transcriptAction_(data));
    }
  } catch (err) {
    return jsonResponse_({ status: 'error', message: String((err && err.message) || err) });
  }

  return null;
}

function getUploadSecret_() {
  var value = PropertiesService.getScriptProperties().getProperty('UPLOAD_SECRET');
  return value ? String(value).trim() : '';
}

/** Die App prueft damit, welche Funktionen das Script schon kann. */
function hello_() {
  var props = PropertiesService.getScriptProperties();
  var now = new Date();
  return {
    status: 'ok',
    api: APP_API_VERSION,
    secretRequired: !!getUploadSecret_(),
    mailPrivate: !!props.getProperty('MAIL_PRIVATE'),
    month: Utilities.formatDate(now, USAGE_TZ, 'yyyy-MM'),
    monthSeconds: Number(props.getProperty(usageKey_(now)) || 0),
    archiveDays: ARCHIVE_KEEP_DAYS,
    archiveCleanup: ensureArchiveCleanupTrigger_()
  };
}

/**
 * Schritt 1: Resumable-Upload-Session bei Drive eroeffnen.
 *
 * Die Datei wird unter ihrem ORIGINALNAMEN angelegt; das Umbenennen macht
 * weiterhin startTranscriptionForFile_(). Kontext und Mail-Auswahl kommen in
 * die Drive-Beschreibung.
 */
function initUpload_(data) {
  var fileName = data.filename || 'share-upload.m4a';
  var mimeType = data.mimeType || 'audio/mp4';

  var folders = DriveApp.getFoldersByName(CONFIG.FOLDER_WORK_NAME);
  if (!folders.hasNext()) {
    throw new Error('Ordner "' + CONFIG.FOLDER_WORK_NAME + '" wurde nicht gefunden.');
  }

  var metadata = {
    name: fileName,
    mimeType: mimeType,
    parents: [folders.next().getId()]
  };
  var description = buildAppDescription_(data.context, data.mail);
  if (description) metadata.description = description;

  var headers = {
    Authorization: 'Bearer ' + ScriptApp.getOAuthToken(),
    'X-Upload-Content-Type': mimeType
  };
  if (data.size) headers['X-Upload-Content-Length'] = String(data.size);

  // Nur fuer die alte Browser-PWA noetig (CORS beim spaeteren PUT).
  if (data.origin && ALLOWED_UPLOAD_ORIGINS.indexOf(data.origin) !== -1) {
    headers['Origin'] = data.origin;
  }

  var res = UrlFetchApp.fetch(DRIVE_RESUMABLE_INIT_URL, {
    method: 'post',
    contentType: 'application/json; charset=UTF-8',
    headers: headers,
    payload: JSON.stringify(metadata),
    muteHttpExceptions: true
  });

  if (res.getResponseCode() >= 300) {
    throw new Error('Drive lehnte die Upload-Session ab (HTTP ' +
      res.getResponseCode() + '): ' + res.getContentText().slice(0, 300));
  }

  var uploadUrl = findHeader_(res.getAllHeaders(), 'location');
  if (!uploadUrl) {
    throw new Error('Drive lieferte keinen Location-Header fuer die Upload-Session.');
  }

  return { status: 'ok', uploadUrl: uploadUrl, fileName: fileName };
}

/** Schritt 2: Die Bytes sind in Drive. Transkription sofort starten. */
function completeUpload_(data) {
  var uploadedFileId = data.fileId;
  if (!uploadedFileId) {
    return { status: 'error', message: 'fileId fehlt im Request.' };
  }

  var file;
  try {
    file = DriveApp.getFileById(uploadedFileId);
  } catch (err) {
    return { status: 'error', message: 'Hochgeladene Datei nicht gefunden: ' + err.message };
  }

  var transcriptionResult = null;
  try {
    var allResults = startAllPendingTranscriptions_();
    transcriptionResult = allResults.filter(function (r) {
      return r.fileId === uploadedFileId;
    })[0] || null;

    if (allResults.some(function (r) { return r.status === 'processing'; })) {
      scheduleAutoCheck();
    }
  } catch (startError) {
    transcriptionResult = {
      status: 'error',
      message: 'Transkriptionsstart fehlgeschlagen: ' + startError.message
    };
  }

  return {
    status: 'ok',
    message: 'Datei erfolgreich hochgeladen.',
    fileId: uploadedFileId,
    fileName: (transcriptionResult && transcriptionResult.fileName) || file.getName(),
    fileSize: file.getSize(),
    transcription: transcriptionResult
  };
}

/** Startet alles, was schon im Arbeitsordner liegt (Knopf der alten PWA). */
function startPending_() {
  var results = startAllPendingTranscriptions_();

  if (!results.length) {
    return {
      status: 'empty',
      message: 'Keine wartende Aufnahme im Ordner "' + CONFIG.FOLDER_WORK_NAME + '".'
    };
  }

  var processing = results.filter(function (r) { return r.status === 'processing'; });
  if (processing.length) scheduleAutoCheck();

  return {
    status: 'ok',
    started: processing.length,
    total: results.length,
    transcription: processing[0] || results[0]
  };
}

/** Liefert das fertige Transkript als Text (fuer den TXT-Download der App). */
function transcriptAction_(data) {
  if (!data.fileId) return { status: 'error', message: 'fileId fehlt im Request.' };

  var file = DriveApp.getFileById(data.fileId);
  var txt = findTranscriptFile_(file.getName());
  if (!txt) return { status: 'missing' };

  return { status: 'ok', name: txt.getName(), text: txt.getBlob().getDataAsString('UTF-8') };
}

// --- Kontext und Mail-Auswahl -------------------------------------------

function buildAppDescription_(context, mail) {
  var hasMail = APP_MAIL_CHOICES.indexOf(mail) !== -1;
  var ctx = typeof context === 'string'
    ? context.replace(/\s+/g, ' ').trim().slice(0, APP_CONTEXT_MAX)
    : '';
  if (!hasMail && !ctx) return '';
  return (ctx ? ctx + '\n' : '') + '#app mail=' + (hasMail ? mail : 'work');
}

/**
 * Liest Kontext und Mail-Auswahl aus der Drive-Beschreibung. Nur wenn die
 * Markierungszeile da ist - eine selbst geschriebene Beschreibung einer
 * anderen Datei wird nie als Kontext verwendet.
 */
function readAppOptions_(file) {
  var description = '';
  try {
    description = file.getDescription() || '';
  } catch (err) {
    description = '';
  }
  var match = description.match(APP_MARKER_RE);
  if (!match) return { context: '', mail: null };
  return { context: description.slice(0, match.index).trim(), mail: match[1] };
}

/**
 * Aufruf aus startTranscriptionForFile_() in Code.gs, direkt bevor der
 * Auftrag an AssemblyAI geht. Haengt den Kontext an den Prompt an.
 * Darf nie den Start verhindern.
 */
function applyAppOptions_(file, payload) {
  try {
    var opts = readAppOptions_(file);
    if (opts.context) {
      payload.prompt = (payload.prompt ? payload.prompt + ' ' : '') +
        'Context for this specific recording, given by the person who recorded it: ' +
        opts.context;
    }
  } catch (err) {
    Logger.log('applyAppOptions_: ' + err.message);
  }
}

/**
 * Aufruf aus finalizeCompletedTranscript() in Code.gs anstelle des direkten
 * sendEmailTranscriptOnly(CONFIG.TARGET_EMAIL, ...).
 *
 * Legt zuerst die .txt in Drive ab (darf scheitern, ohne die Mail zu
 * verhindern) und schickt dann die Mail an die gewaehlte Adresse - oder gar
 * keine. Wirft, wenn die Mail scheitert: dann versucht der Status-Trigger es
 * spaeter erneut, genau wie bisher. Die Minuten werden erst NACH der Mail
 * gezaehlt, damit ein Wiederholungslauf sie nicht doppelt zaehlt.
 */
function deliverTranscript_(file, fileName, transcriptText, summaryInfo, confidence, statusData) {
  var opts = readAppOptions_(file);
  var text = opts.context ? 'Kontext: ' + opts.context + '\n\n' + transcriptText : transcriptText;

  // Titel (Sprache des Gespraechs) und Executive Summary (Englisch) fuer
  // Betreff, Mail, .txt und App. Darf nie die Mail verhindern.
  var extras = buildMailExtras_(transcriptText, summaryInfo, opts.context);
  var title = extras.title;
  var mailSummary = { text: extras.summary || null, headline: title || null };

  try {
    saveTranscriptFile_(fileName, text, mailSummary, title);
  } catch (err) {
    Logger.log('Transkript-Datei konnte nicht gespeichert werden: ' + err.message);
  }

  var recipient = resolveRecipient_(opts.mail);
  if (recipient) {
    sendEmailTranscriptOnly(recipient, fileName, text, file.getLastUpdated(), mailSummary, confidence);
  } else {
    Logger.log('Keine Mail fuer "' + fileName + '" (so in der App gewaehlt).');
  }

  addUsage_(statusData);
}

// --- Minuten pro Monat ---------------------------------------------------

function usageKey_(date) {
  return 'USAGE_' + Utilities.formatDate(date, USAGE_TZ, 'yyyy-MM');
}

/** Zaehlt die Audiolaenge eines fertigen Transkripts zum laufenden Monat. */
function addUsage_(statusData) {
  try {
    var seconds = statusData && Number(statusData.audio_duration);
    if (!seconds || seconds <= 0) return;
    var props = PropertiesService.getScriptProperties();
    var key = usageKey_(new Date());
    var before = Number(props.getProperty(key) || 0);
    props.setProperty(key, String(Math.round(before + seconds)));
  } catch (err) {
    Logger.log('Minuten konnten nicht gezaehlt werden: ' + err.message);
  }
}

function resolveRecipient_(mail) {
  if (mail === 'none') return null;
  var props = PropertiesService.getScriptProperties();
  if (mail === 'private') {
    var privateAddress = props.getProperty('MAIL_PRIVATE');
    if (privateAddress) return privateAddress;
    Logger.log('MAIL_PRIVATE fehlt in den Skripteigenschaften - sende an die Arbeitsadresse.');
  }
  return props.getProperty('MAIL_WORK') || CONFIG.TARGET_EMAIL;
}

// --- Transkripte als .txt in Drive ----------------------------------------

function transcriptFileName_(fileName) {
  return String(fileName).replace(/\.[^.]+$/, '') + '.txt';
}

function transcriptFolder_(create) {
  var parent = DriveApp.getFolderById(CONFIG.FOLDER_TRANSCRIPT_ID);
  var found = parent.getFoldersByName(TRANSCRIPT_FOLDER_NAME);
  if (found.hasNext()) return found.next();
  return create ? parent.createFolder(TRANSCRIPT_FOLDER_NAME) : null;
}

function saveTranscriptFile_(fileName, text, summaryInfo, title) {
  var folder = transcriptFolder_(true);
  var name = transcriptFileName_(fileName);
  var parts = [];
  if (title) parts.push('Titel: ' + title, '');
  if (summaryInfo && summaryInfo.text) {
    parts.push('Executive Summary:', summaryInfo.text, '', '---', '');
  }
  parts.push(text);
  var content = parts.join('\n');

  var existing = folder.getFilesByName(name);
  if (existing.hasNext()) {
    existing.next().setContent(content);
  } else {
    folder.createFile(name, content, MimeType.PLAIN_TEXT);
  }
}

function findTranscriptFile_(fileName) {
  var folder = transcriptFolder_(false);
  if (!folder) return null;
  var files = folder.getFilesByName(transcriptFileName_(fileName));
  return files.hasNext() ? files.next() : null;
}

// --- Titel und Betreff -----------------------------------------------------

/**
 * "2026-11-02_09-27-49" -> "Mo 02.11.2026 09:27". Aufruf aus
 * buildMeetingSubjectAndHeader() in Code.gs.
 */
function formatSubjectStamp_(stamp) {
  var m = String(stamp || '').match(/^(\d{4})-(\d{2})-(\d{2})[_ ](\d{2})-(\d{2})/);
  if (!m) return String(stamp || '');
  var weekday = new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3])).getDay();
  return WEEKDAYS_DE[weekday] + ' ' + m[3] + '.' + m[2] + '.' + m[1] + ' ' + m[4] + ':' + m[5];
}

/**
 * Titel und Executive Summary aus der besten verfuegbaren Quelle. Wirft nie.
 *
 *   title:   Sprachmodell, sonst erste Ueberschrift der AssemblyAI-
 *            Zusammenfassung, sonst der Kontext aus der App ("" = keiner).
 *   summary: englische Stichpunkte vom Sprachmodell, sonst die
 *            AssemblyAI-Zusammenfassung ("" = keine).
 */
function buildMailExtras_(transcriptText, summaryInfo, context) {
  var fromLlm = { title: '', summary: '' };
  try {
    fromLlm = askLlm_(transcriptText, summaryInfo, context);
  } catch (err) {
    Logger.log('Sprachmodell nicht verfuegbar: ' + err.message);
  }
  var title = fromLlm.title;
  if (!title && summaryInfo && summaryInfo.headline) title = cleanTitle_(summaryInfo.headline);
  if (!title && context) title = cleanTitle_(context);
  var summary = fromLlm.summary || (summaryInfo && summaryInfo.text) || '';
  return { title: title, summary: summary };
}

/** Nur der Titel (fuer Aufrufer, die keine Zusammenfassung brauchen). */
function buildConversationTitle_(transcriptText, summaryInfo, context) {
  return buildMailExtras_(transcriptText, summaryInfo, context).title;
}

function llmPrompt_(transcriptText, summaryInfo, context) {
  var text = String(transcriptText || '');
  var excerpt = text.length > LLM_TRANSCRIPT_HEAD + LLM_TRANSCRIPT_TAIL
    ? text.slice(0, LLM_TRANSCRIPT_HEAD) + '\n[...]\n' + text.slice(-LLM_TRANSCRIPT_TAIL)
    : text;

  var parts = [
    'Below is the transcript of a recorded conversation. Reply with JSON only (no code fence, no other text):',
    '{"title": "...", "summary": ["...", "..."]}',
    'title: a very short title in the MAIN LANGUAGE OF THE CONVERSATION, 3 to 8 words, at most ' +
      TITLE_MAX + ' characters, so the reader instantly recognizes which conversation it was. ' +
      'Name the main topic and, if clear, the key people. No date, no quotes, no trailing period.',
    'summary: an executive summary ALWAYS IN ENGLISH, regardless of the language spoken. ' +
      '4 to 8 concise bullet points covering the key topics, decisions, open issues and action items ' +
      '(with owner and due date if mentioned). One bullet per array item, without bullet characters.'
  ];
  if (context) parts.push('Note from the person who recorded it: ' + context);
  if (summaryInfo && summaryInfo.text) parts.push('Automatic summary (may be in another language):\n' + summaryInfo.text);
  parts.push('Transcript:\n' + excerpt);
  return parts.join('\n\n');
}

/** Fragt das Sprachmodell; liefert { title, summary } (leere Strings, wenn nichts kam). */
function askLlm_(transcriptText, summaryInfo, context) {
  var empty = { title: '', summary: '' };
  if (!String(transcriptText || '').trim()) return empty;
  var prompt = llmPrompt_(transcriptText, summaryInfo, context);
  var key = getApiKey();

  for (var i = 0; i < TITLE_MODELS.length; i++) {
    try {
      var res = UrlFetchApp.fetch(LLM_GATEWAY_URL, {
        method: 'post',
        contentType: 'application/json',
        headers: { Authorization: key },
        payload: JSON.stringify({
          model: TITLE_MODELS[i],
          messages: [{ role: 'user', content: prompt }],
          max_tokens: LLM_MAX_TOKENS
        }),
        muteHttpExceptions: true
      });
      if (res.getResponseCode() !== 200) {
        Logger.log('Sprachmodell (' + TITLE_MODELS[i] + '): HTTP ' + res.getResponseCode() + ' ' +
          res.getContentText().slice(0, 200));
        continue;
      }
      var parsed = parseLlmAnswer_(readChatContent_(JSON.parse(res.getContentText())));
      if (parsed.title || parsed.summary) return parsed;
      Logger.log('Sprachmodell (' + TITLE_MODELS[i] + '): Antwort nicht verwertbar.');
    } catch (err) {
      Logger.log('Sprachmodell (' + TITLE_MODELS[i] + '): ' + err.message);
    }
  }
  return empty;
}

/** JSON aus der Modell-Antwort lesen (auch mit Code-Fence oder Text drumherum). */
function parseLlmAnswer_(raw) {
  var empty = { title: '', summary: '' };
  var s = String(raw || '');
  var from = s.indexOf('{');
  var to = s.lastIndexOf('}');
  if (from < 0 || to <= from) return empty;
  var obj;
  try {
    obj = JSON.parse(s.slice(from, to + 1));
  } catch (err) {
    return empty;
  }
  if (!obj || typeof obj !== 'object') return empty;

  var items = Array.isArray(obj.summary) ? obj.summary
    : (typeof obj.summary === 'string' ? obj.summary.split('\n') : []);
  var bullets = items
    .map(function (b) { return String(b || '').replace(/^\s*(?:[-*•]|\d+[.)])\s*/, '').replace(/\s+/g, ' ').trim(); })
    .filter(Boolean)
    .slice(0, SUMMARY_MAX_BULLETS);

  return {
    title: cleanTitle_(typeof obj.title === 'string' ? obj.title : ''),
    summary: bullets.map(function (b) { return '- ' + b; }).join('\n')
  };
}

/** Text aus einer Chat-Completions-Antwort (String oder Liste von Teilen). */
function readChatContent_(data) {
  var msg = data && data.choices && data.choices[0] && data.choices[0].message;
  var content = msg && msg.content;
  if (typeof content === 'string') return content;
  if (Array.isArray(content)) {
    return content.map(function (p) { return (p && p.text) || ''; }).join('');
  }
  return '';
}

/** Erste Zeile, ohne "Titel:", Anfuehrungszeichen, Markdown und Schlusspunkt. */
function cleanTitle_(raw) {
  var lines = String(raw || '').split('\n').map(function (l) { return l.trim(); }).filter(Boolean);
  var line = lines.length ? lines[0] : '';
  line = line.replace(/^(titel|title)\s*:\s*/i, '');
  line = line.replace(/^[\s"'„“”«»*#_`-]+/, '');
  line = line.replace(/[\s"'„“”«»*#_`.]+$/, '');
  line = line.replace(/\s+/g, ' ').trim();
  if (line.length > TITLE_MAX) line = line.slice(0, TITLE_MAX - 1).trim() + '…';
  return line;
}

// --- Drive-Archiv aufraeumen --------------------------------------------

/**
 * Richtet den taeglichen Aufraeum-Trigger ein, falls er fehlt.
 * Gibt true zurueck, wenn er (jetzt) existiert. Wirft nie.
 */
function ensureArchiveCleanupTrigger_() {
  try {
    var exists = ScriptApp.getProjectTriggers().some(function (t) {
      return t.getHandlerFunction() === ARCHIVE_CLEANUP_HANDLER;
    });
    if (!exists) {
      ScriptApp.newTrigger(ARCHIVE_CLEANUP_HANDLER).timeBased().everyDays(1).atHour(3).create();
    }
    return true;
  } catch (err) {
    Logger.log('Aufraeum-Trigger konnte nicht eingerichtet werden: ' + err.message);
    return false;
  }
}

/**
 * Wann die Datei in Drive kam bzw. zuletzt bearbeitet wurde (Umbenennen beim
 * Transkriptionsstart). Gezaehlt wird ab dem spaeteren der beiden Zeitpunkte.
 */
function archiveReferenceTime_(file) {
  var created = file.getDateCreated();
  var updated = file.getLastUpdated();
  return Math.max(created ? created.getTime() : 0, updated ? updated.getTime() : 0);
}

/**
 * Taeglich per Trigger: Audiodateien im Archiv, die aelter als
 * ARCHIVE_KEEP_DAYS sind, in den Papierkorb. Nur Audiodateien (isAudioFile),
 * nur direkt im Archivordner. Gibt die Zahl der geloeschten Dateien zurueck.
 */
function cleanupArchive() {
  var started = Date.now();
  var limit = started - ARCHIVE_KEEP_DAYS * 24 * 60 * 60 * 1000;
  var archive = DriveApp.getFolderById(CONFIG.FOLDER_ARCHIVE_ID);
  var files = archive.getFiles();
  var trashed = 0;
  var kept = 0;

  while (files.hasNext()) {
    if (Date.now() - started > ARCHIVE_CLEANUP_MAX_MS) {
      Logger.log('Zeitlimit erreicht, der Rest folgt beim naechsten Lauf.');
      break;
    }
    var file = files.next();
    try {
      if (!isAudioFile(file.getName())) continue;
      if (archiveReferenceTime_(file) < limit) {
        file.setTrashed(true);
        trashed++;
      } else {
        kept++;
      }
    } catch (err) {
      Logger.log('Konnte "' + file.getName() + '" nicht loeschen: ' + err.message);
    }
  }

  Logger.log('Archiv aufgeraeumt: ' + trashed + ' Aufnahme(n) in den Papierkorb, ' +
    kept + ' juenger als ' + ARCHIVE_KEEP_DAYS + ' Tage bleiben.');
  return trashed;
}

/** Zum einmaligen Ausfuehren im Editor: richtet den Trigger ein und raeumt sofort auf. */
function cleanupArchiveNow() {
  ensureArchiveCleanupTrigger_();
  return cleanupArchive();
}

/** Header-Namen kommen je nach Aufruf mal gross-, mal kleingeschrieben. */
function findHeader_(headers, wanted) {
  for (var key in headers) {
    if (key.toLowerCase() === wanted) return headers[key];
  }
  return null;
}
