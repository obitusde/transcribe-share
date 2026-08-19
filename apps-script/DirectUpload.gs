/**
 * DirectUpload.gs — direkter Drive-Upload statt Base64 durch Apps Script.
 *
 * WARUM
 * -----
 * Der alte doPost() macht zwei teure Dinge synchron, bevor er antwortet:
 *
 *   1. Utilities.base64Decode() ueber ~22,6 MB Text (die 17-MB-Aufnahme,
 *      um 33% aufgeblaeht) + folder.createFile() mit dem 17-MB-Blob.
 *   2. startAllPendingTranscriptions_() fuer ALLE wartenden Dateien - pro
 *      Datei mehrere Drive-Roundtrips (Ordner scannen, umbenennen,
 *      ensureUniqueFileName_, getJobState, saveJobState mit Rueck-Lesen und
 *      Retry-Sleeps) plus ein AssemblyAI-API-Call.
 *
 * Schritt 1 faellt hier komplett weg: der Browser schiebt die Bytes als
 * rohes Binary direkt zu Drive. Apps Script sieht nur noch winzige
 * JSON-Nachrichten.
 *
 * Schritt 2 bleibt bewusst synchron in completeUpload_() - identisch zum
 * alten Verhalten, damit die Antwort weiterhin den normalisierten Dateinamen
 * enthaelt, auf den die PWA ihr Status-Polling stuetzt. Der Teil kostet
 * Sekunden, nicht Minuten.
 *
 * Der OAuth-Token des Skripts wird NIE an den Browser gegeben. Die
 * Session-URL ist ein kurzlebiges Capability-Token fuer genau diese Datei.
 *
 * EINBAU: siehe apps-script/README.md
 */

var DRIVE_RESUMABLE_INIT_URL =
  'https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&supportsAllDrives=true';

/**
 * Origins, deren Browser eine Session-URL weiterbenutzen duerfen.
 *
 * Der Google-Upload-Server merkt sich den Origin aus dem INIT-Call und
 * schickt nur dann Access-Control-Allow-Origin auf die Antwort des PUT.
 * Ohne diesen Eintrag kommen die Bytes zwar an, aber der Browser darf die
 * Antwort nicht lesen - der Upload sieht wie ein Netzwerkfehler aus.
 */
var ALLOWED_UPLOAD_ORIGINS = ['https://obitusde.github.io'];

/**
 * Router fuer die neuen Upload-Actions.
 *
 * Gibt einen fertigen TextOutput zurueck, wenn die Anfrage zu diesem Modul
 * gehoert - sonst null, damit der bestehende Base64-Pfad in doPost()
 * unveraendert weiterlaeuft. Dadurch funktioniert eine alte, noch
 * ausgelieferte Client-Version weiter.
 */
function handleUploadAction_(e) {
  if (!e || !e.postData || !e.postData.contents) return null;

  var data;
  try {
    data = JSON.parse(e.postData.contents);
  } catch (err) {
    return null; // Kein JSON -> nicht unsere Anfrage.
  }

  if (!data || !data.action) return null;

  try {
    if (data.action === 'initUpload') return jsonResponse_(initUpload_(data));
    if (data.action === 'completeUpload') return jsonResponse_(completeUpload_(data));
  } catch (err) {
    return jsonResponse_({ status: 'error', message: String((err && err.message) || err) });
  }

  return null;
}

/**
 * Schritt 1: Resumable-Upload-Session bei Drive eroeffnen.
 *
 * Die Datei wird bewusst unter ihrem ORIGINALNAMEN im Arbeitsordner
 * angelegt. Das Umbenennen auf das kanonische Schema
 * (YYYY-MM-DD_HH-mm-ss.ext) macht weiterhin startTranscriptionForFile_() -
 * hier nichts vorwegnehmen, sonst laufen zwei Namenslogiken gegeneinander.
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

  var headers = {
    Authorization: 'Bearer ' + ScriptApp.getOAuthToken(),
    'X-Upload-Content-Type': mimeType
  };
  if (data.size) headers['X-Upload-Content-Length'] = String(data.size);

  // Ohne Origin HIER liefert der Upload-Server beim spaeteren PUT kein
  // Access-Control-Allow-Origin (nur der OPTIONS-Preflight antwortet
  // korrekt - deshalb sieht der Fehler wie ein reines Netzwerkproblem aus).
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

/**
 * Schritt 2: Der Browser meldet, dass die Bytes in Drive angekommen sind.
 *
 * Ab hier laeuft exakt das, was frueher im Rumpf von doPost() nach
 * createFile() stand - nur ohne die teure Dekodierung davor. Die
 * Antwortform ist absichtlich identisch zur alten, damit die PWA nichts
 * weiter anpassen muss.
 */
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

/** Header-Namen kommen je nach Aufruf mal gross-, mal kleingeschrieben. */
function findHeader_(headers, wanted) {
  for (var key in headers) {
    if (key.toLowerCase() === wanted) return headers[key];
  }
  return null;
}
