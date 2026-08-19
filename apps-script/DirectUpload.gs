/**
 * DirectUpload.gs — direkter Drive-Upload statt Base64-durch-Apps-Script.
 *
 * WARUM
 * -----
 * Der alte Weg schickte die komplette Audiodatei als Base64-String im
 * JSON-Body an doPost(). Bei einer 17-MB-Aufnahme sind das ~22,6 MB Text,
 * die Apps Script erst puffern, dann per Utilities.base64Decode() dekodieren
 * und schliesslich nach Drive schreiben muss - alles synchron, bevor
 * ueberhaupt eine Antwort zurueckgeht. Das dauert typischerweise deutlich
 * laenger als eine Minute und hat mit der Netzgeschwindigkeit des Nutzers
 * nichts zu tun.
 *
 * Neuer Weg: Apps Script eroeffnet nur noch eine "resumable upload"-Session
 * bei der Drive-API und gibt deren URL zurueck. Die eigentlichen Bytes
 * schiebt der Browser dann als ROHES BINARY direkt zu Google - ohne Umweg
 * ueber die Apps-Script-Sandbox und ohne Base64-Aufblaehung. Apps Script
 * sieht danach nur noch winzige JSON-Nachrichten.
 *
 * Die Session-URL ist ein kurzlebiges Capability-Token fuer genau diese eine
 * Datei. Der OAuth-Token des Skripts selbst wird NIE an den Browser
 * herausgegeben.
 *
 * EINBAU (3 Schritte, Details in apps-script/README.md)
 * ----------------------------------------------------
 * 1. Diese Datei als NEUE Skriptdatei "DirectUpload" ins Projekt einfuegen.
 *    Nicht die bestehende Code.gs ueberschreiben.
 * 2. In der bestehenden doPost(e) ganz oben zwei Zeilen einfuegen:
 *
 *      function doPost(e) {
 *        var routed = handleUploadAction_(e);
 *        if (routed) return routed;
 *        // ... bestehender Code bleibt unveraendert ...
 *      }
 *
 * 3. Unten startTranscriptionForDriveFile_() an die eigene Transkriptions-
 *    und Mail-Logik anschliessen (siehe TODO dort).
 */

/** Zielordner in Drive. Leer lassen = Drive-Wurzel. */
var UPLOAD_FOLDER_ID = '';

var DRIVE_RESUMABLE_INIT_URL =
  'https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&supportsAllDrives=true';

/**
 * Router fuer die neuen Upload-Actions.
 *
 * Gibt einen fertigen TextOutput zurueck, wenn die Anfrage zu diesem Modul
 * gehoert - sonst null, damit der bestehende (Base64-)Pfad unveraendert
 * weiterlaeuft. Dadurch bleibt die alte Client-Version funktionsfaehig,
 * waehrend die neue schon ausgerollt wird.
 */
function handleUploadAction_(e) {
  var data;
  try {
    data = JSON.parse(e.postData.contents);
  } catch (err) {
    return null; // Kein JSON -> nicht unsere Anfrage.
  }

  if (!data || !data.action) return null;

  try {
    if (data.action === 'initUpload') return jsonOut_(initUpload_(data));
    if (data.action === 'completeUpload') return jsonOut_(completeUpload_(data));
  } catch (err) {
    return jsonOut_({ status: 'error', message: String(err && err.message || err) });
  }

  return null;
}

/**
 * Schritt 1: Resumable-Upload-Session bei Drive eroeffnen.
 *
 * Antwort an den Client: { status:'ok', uploadUrl, fileName }
 * uploadUrl ist die Session-URI, an die der Browser die Bytes per PUT schickt.
 */
function initUpload_(data) {
  var fileName = uniqueFileName_(data.filename || 'aufnahme.m4a');
  var mimeType = data.mimeType || 'audio/mp4';

  var metadata = { name: fileName, mimeType: mimeType };
  if (UPLOAD_FOLDER_ID) metadata.parents = [UPLOAD_FOLDER_ID];

  var headers = {
    Authorization: 'Bearer ' + ScriptApp.getOAuthToken(),
    'X-Upload-Content-Type': mimeType
  };
  // Groesse ist optional, hilft Drive aber beim Vorab-Reservieren.
  if (data.size) headers['X-Upload-Content-Length'] = String(data.size);

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
    throw new Error('Drive lieferte keine Location-Header fuer die Upload-Session.');
  }

  setTranscriptionState_(fileName, 'uploading');

  return { status: 'ok', uploadUrl: uploadUrl, fileName: fileName };
}

/**
 * Schritt 2: Der Browser meldet, dass die Bytes in Drive angekommen sind.
 *
 * Antwortform bleibt exakt wie beim alten Pfad, damit das Status-Polling im
 * Client unveraendert weiterfunktioniert.
 */
function completeUpload_(data) {
  var fileId = data.fileId;
  var fileName = data.fileName;

  if (!fileId || !fileName) {
    return { status: 'error', message: 'fileId oder fileName fehlt.' };
  }

  setTranscriptionState_(fileName, 'transcribing');

  // Ab hier laeuft alles so wie beim alten Pfad, nur dass die Datei schon
  // in Drive liegt statt gerade erst dekodiert worden zu sein.
  startTranscriptionForDriveFile_(fileId, fileName);

  return {
    status: 'ok',
    transcription: { status: 'processing', fileName: fileName }
  };
}

/**
 * TODO (einmalig anpassen):
 * Hier die BESTEHENDE Transkriptions- und Mail-Logik aufrufen.
 *
 * In der alten doPost() steht der entsprechende Code direkt hinter dem
 * DriveApp.createFile(...)-Aufruf. Genau dieser Teil gehoert hierher - nur
 * bekommt er die Datei jetzt fertig aus Drive, statt sie selbst anzulegen:
 *
 *   var file = DriveApp.getFileById(fileId);
 *   ... vorhandener Transkriptions-Aufruf mit "file" ...
 *   ... vorhandener MailApp.sendEmail(...) ...
 *   setTranscriptionState_(fileName, 'done');
 *
 * Wichtig: am Ende IMMER den Status setzen ('done' bzw. 'error'), sonst
 * pollt der Client bis zum Timeout ins Leere.
 */
function startTranscriptionForDriveFile_(fileId, fileName) {
  throw new Error(
    'startTranscriptionForDriveFile_() ist noch nicht angeschlossen. ' +
    'Siehe apps-script/README.md, Schritt 3.'
  );
}

/**
 * Statusspeicher.
 *
 * ACHTUNG: Wenn im bestehenden Skript bereits eine eigene Status-Verwaltung
 * existiert (die doGet(?action=status) bedient), dann diese beiden
 * Funktionen hier LOESCHEN und stattdessen die vorhandenen aufrufen - sonst
 * schreiben zwei Speicher aneinander vorbei und das Polling sieht den
 * Fortschritt nie.
 */
function setTranscriptionState_(fileName, state) {
  PropertiesService.getScriptProperties()
    .setProperty('state:' + fileName, state);
}

function getTranscriptionState_(fileName) {
  return PropertiesService.getScriptProperties()
    .getProperty('state:' + fileName) || 'unknown';
}

/** Kollisionen vermeiden, wenn mehrere Aufnahmen gleich heissen. */
function uniqueFileName_(original) {
  var stamp = Utilities.formatDate(new Date(), Session.getScriptTimeZone(), 'yyyyMMdd-HHmmss');
  var dot = original.lastIndexOf('.');
  if (dot <= 0) return original + '-' + stamp;
  return original.slice(0, dot) + '-' + stamp + original.slice(dot);
}

/** Header-Namen kommen je nach Aufruf mal gross-, mal kleingeschrieben. */
function findHeader_(headers, wanted) {
  for (var key in headers) {
    if (key.toLowerCase() === wanted) return headers[key];
  }
  return null;
}

function jsonOut_(obj) {
  return ContentService
    .createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}
