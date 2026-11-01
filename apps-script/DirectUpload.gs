/**
 * DirectUpload.gs - Schnittstelle fuer die Android-App "Aufnahme".
 * Version 2 (2026-11-01)
 *
 * Ersetzt die Version 1 dieser Datei komplett. Neu gegenueber Version 1:
 *
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
 * EINBAU: siehe apps-script/ANLEITUNG-APP-V2.md
 * Code.gs braucht zwei Ein-Zeilen-Aenderungen, die applyAppOptions_() und
 * deliverTranscript_() aus dieser Datei aufrufen.
 */

var DRIVE_RESUMABLE_INIT_URL =
  'https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&supportsAllDrives=true';

/** Origins, deren Browser (alte PWA) eine Session-URL weiterbenutzen duerfen. */
var ALLOWED_UPLOAD_ORIGINS = ['https://obitusde.github.io'];

/** Die App fragt per "hello" danach und schaltet neue Funktionen erst ab 2 frei. */
var APP_API_VERSION = 2;

var APP_MAIL_CHOICES = ['work', 'private', 'none'];
var APP_CONTEXT_MAX = 500;
var APP_MARKER_RE = /(?:^|\n)#app mail=(work|private|none)\s*$/;
var TRANSCRIPT_FOLDER_NAME = 'Transkripte';
var USAGE_TZ = 'Europe/Zurich';

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
    monthSeconds: Number(props.getProperty(usageKey_(now)) || 0)
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

  try {
    saveTranscriptFile_(fileName, text, summaryInfo);
  } catch (err) {
    Logger.log('Transkript-Datei konnte nicht gespeichert werden: ' + err.message);
  }

  var recipient = resolveRecipient_(opts.mail);
  if (recipient) {
    sendEmailTranscriptOnly(recipient, fileName, text, file.getLastUpdated(), summaryInfo, confidence);
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

function saveTranscriptFile_(fileName, text, summaryInfo) {
  var folder = transcriptFolder_(true);
  var name = transcriptFileName_(fileName);
  var parts = [];
  if (summaryInfo && summaryInfo.text) {
    parts.push('Zusammenfassung:', summaryInfo.text, '', '---', '');
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

/** Header-Namen kommen je nach Aufruf mal gross-, mal kleingeschrieben. */
function findHeader_(headers, wanted) {
  for (var key in headers) {
    if (key.toLowerCase() === wanted) return headers[key];
  }
  return null;
}
