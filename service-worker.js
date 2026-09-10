// service-worker.js
// Version: 12 (2026-09-10)
// Faengt den Web-Share-Target-POST ab, reicht die geteilte Datei an die Seite
// weiter und leitet SOFORT dorthin um (statt den kompletten Upload
// abzuwarten, bevor irgendwas angezeigt wird - das fuehrte zu einem
// eingefrorenen weissen Bildschirm waehrend des Uploads).
//
// v3: Navigation nutzt cache:'no-store', damit Aenderungen an index.html
// IMMER sofort ankommen (network-first allein reichte nicht, weil fetch()
// sonst still aus dem normalen HTTP-Cache bedient werden kann).
//
// v8: Die Datei geht nicht mehr durch die Cache API, sondern per postMessage
// direkt an die Seite (kein Kopieren, keine Quota - das File-Objekt ist nur
// ein Handle auf die Datei, die Android ohnehin auf der Platte hat).
// Absicherung nach IndexedDB, falls der Worker zwischendurch beendet wird.
//
// v10: request.formData() lieferte bei einer 21-MB-Aufnahme eine LEERE
// FormData, ohne zu werfen - die Datei war damit spurlos weg, noch bevor
// irgendein Upload-Code lief. Deshalb jetzt zweigleisig:
//   1. Vor dem Parsen eine Kopie des Requests anlegen.
//   2. Liefert formData() keine Datei, den Multipart-Body selbst parsen.
//   3. Scheitert auch das, im Fehlertext berichten, wie viele Bytes
//      tatsaechlich ankamen - das trennt "Android hat nichts geschickt" von
//      "Chrome konnte es nicht parsen", was von aussen sonst identisch
//      aussieht.

const CACHE_VERSION = 'transcribe-share-v12';
const SHARE_CACHE_KEY = './__shared-file__'; // nur noch fuer Altlasten
const IDB_NAME = 'transcribe-share';
const IDB_STORE = 'shares';

// token -> File. Lebt nur so lange wie diese Service-Worker-Instanz.
const PENDING_SHARES = new Map();

const PRECACHE = [
  './',
  './index.html',
  './manifest.json'
];

self.addEventListener('install', (event) => {
  self.skipWaiting();
  event.waitUntil(
    caches.open(CACHE_VERSION).then((cache) => cache.addAll(PRECACHE))
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((key) => key !== CACHE_VERSION).map((key) => caches.delete(key))))
      .then(() => self.clients.claim())
  );
});

/** Die Seite holt sich die geteilte Datei hierueber ab. */
self.addEventListener('message', (event) => {
  const data = event.data;
  if (!data || data.type !== 'claim-share') return;

  const port = event.ports && event.ports[0];
  if (!port) return;

  const file = PENDING_SHARES.get(data.token);
  if (file) {
    PENDING_SHARES.delete(data.token);
    port.postMessage({ ok: true, file: file, name: file.name, type: file.type });
  } else {
    port.postMessage({ ok: false });
  }
});

self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url);

  if (event.request.method === 'POST' && url.pathname.endsWith('/share-target/')) {
    event.respondWith(handleShareTarget(event));
    return;
  }

  // Navigation: IMMER frisch vom Netz (no-store), damit Aenderungen an
  // index.html sofort ankommen.
  if (event.request.mode === 'navigate') {
    event.respondWith(
      fetch(event.request.url, { cache: 'no-store' }).catch(() => caches.match('./index.html'))
    );
    return;
  }

  event.respondWith(
    caches.match(event.request).then((cached) => cached || fetch(event.request))
  );
});

async function handleShareTarget(event) {
  try {
    const request = event.request;
    const contentType = request.headers.get('content-type') || '(kein Content-Type)';
    const contentLength = request.headers.get('content-length') || '(keine Angabe)';

    // Kopie anlegen, BEVOR formData() den Body verbraucht - der Body laesst
    // sich nur einmal lesen.
    const rawCopy = request.clone();

    let formData = null;
    let formDataError = null;
    try {
      formData = await request.formData();
    } catch (error) {
      formDataError = describeError(error);
    }

    let file = formData ? pickSharedFile(formData) : null;

    // Chrome liefert bei manchen Multipart-Bodies eine leere FormData, ohne
    // zu werfen. Dann selbst parsen statt aufzugeben.
    if (!file) {
      const rawBytes = await readBytes(rawCopy);
      file = parseMultipartFromBytes(rawBytes, contentType);

      if (!file) {
        // Ausfuehrlich in die Konsole, damit ein DevTools-Blick auf den
        // Service Worker mehr hergibt als der kurze Text auf der Seite.
        console.error('[share-target] Keine Datei im POST.');
        console.log('[share-target] Content-Type:', contentType);
        console.log('[share-target] Content-Length-Header:', contentLength);
        console.log('[share-target] tatsaechlich gelesen:', rawBytes ? rawBytes.length : 'null', 'Bytes');
        console.log('[share-target] formData()-Fehler:', formDataError || 'keiner');
        console.log('[share-target] Felder:', formData ? describeEntries(formData) : 'keine FormData');
        console.log('[share-target] Body als Text:',
          rawBytes ? new TextDecoder().decode(rawBytes.subarray(0, 2000)) : '(nichts)');

        return redirectWithError(
          buildFailureReport(formData, formDataError, contentType, contentLength, rawBytes)
        );
      }

      console.warn('[share-target] formData() lieferte nichts, eigener Parser hat die Datei gerettet.');
    }

    console.log('[share-target] Datei erkannt:', file.name, '|', file.type, '|', file.size, 'Bytes');

    const token = Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 10);
    PENDING_SHARES.set(token, file);

    // Nur Absicherung, nicht der Hauptweg: darf scheitern (z.B. Quota), ohne
    // den Share kaputtzumachen.
    event.waitUntil(persistShare(token, file).catch(() => {}));

    return Response.redirect('./?share=pending&token=' + encodeURIComponent(token), 303);

  } catch (error) {
    return redirectWithError(describeError(error));
  }
}

function redirectWithError(message) {
  return Response.redirect('./?status=error&message=' + encodeURIComponent(message), 303);
}

/**
 * Nimmt die erste echte Datei aus dem Formular, egal unter welchem Feldnamen.
 *
 * Verlaesst sich bewusst nicht allein auf "audio": liefert Android die Datei
 * unter einem anderen Namen, landete sie vorher stillschweigend im Nichts.
 * Dateien mit 0 Bytes werden abgelehnt - die wuerden sonst als leere
 * Aufnahme in Drive landen und eine sinnlose Transkription starten.
 */
function pickSharedFile(formData) {
  const named = formData.get('audio');
  if (named && typeof named !== 'string' && named.size > 0) return named;

  for (const value of formData.values()) {
    if (value && typeof value !== 'string' && value.size > 0) return value;
  }

  return null;
}

async function readBytes(request) {
  try {
    const buffer = await request.arrayBuffer();
    return new Uint8Array(buffer);
  } catch (error) {
    return null;
  }
}

/**
 * Eigener Multipart-Parser als Rueckfallebene fuer den Fall, dass Chromes
 * request.formData() den Body nicht hergibt.
 *
 * Arbeitet absichtlich auf Bytes statt auf Text: der Dateiinhalt ist binaer
 * und wuerde beim Umweg ueber einen String zerstoert.
 */
function parseMultipartFromBytes(bytes, contentType) {
  if (!bytes || bytes.length === 0) return null;

  const boundaryMatch = /boundary=(?:"([^"]+)"|([^;]+))/i.exec(contentType || '');
  if (!boundaryMatch) return null;

  const boundary = (boundaryMatch[1] || boundaryMatch[2]).trim();
  const encoder = new TextEncoder();
  const delimiter = encoder.encode('--' + boundary);
  const headerSeparator = encoder.encode('\r\n\r\n');

  let searchFrom = 0;

  while (true) {
    const start = indexOfBytes(bytes, delimiter, searchFrom);
    if (start === -1) return null;

    let cursor = start + delimiter.length;

    // "--" direkt nach dem Delimiter markiert das Ende des Bodies.
    if (bytes[cursor] === 0x2d && bytes[cursor + 1] === 0x2d) return null;
    if (bytes[cursor] === 0x0d && bytes[cursor + 1] === 0x0a) cursor += 2;

    const headerEnd = indexOfBytes(bytes, headerSeparator, cursor);
    if (headerEnd === -1) return null;

    const headerText = new TextDecoder('utf-8').decode(bytes.subarray(cursor, headerEnd));
    const contentStart = headerEnd + headerSeparator.length;

    const nextDelimiter = indexOfBytes(bytes, delimiter, contentStart);
    let contentEnd = nextDelimiter === -1 ? bytes.length : nextDelimiter;

    // Das CRLF vor dem naechsten Delimiter gehoert zur Trennung, nicht zum Inhalt.
    if (contentEnd >= 2 && bytes[contentEnd - 2] === 0x0d && bytes[contentEnd - 1] === 0x0a) {
      contentEnd -= 2;
    }

    const filenameMatch = /filename\*?=(?:"([^"]*)"|([^;\r\n]+))/i.exec(headerText);
    if (filenameMatch && contentEnd > contentStart) {
      const filename = (filenameMatch[1] || filenameMatch[2] || 'share-upload.m4a').trim();
      const typeMatch = /content-type:\s*([^\r\n;]+)/i.exec(headerText);
      const type = typeMatch ? typeMatch[1].trim() : 'audio/mp4';
      return new File([bytes.subarray(contentStart, contentEnd)], filename, { type: type });
    }

    searchFrom = contentEnd;
  }
}

function indexOfBytes(haystack, needle, from) {
  const limit = haystack.length - needle.length;

  outer:
  for (let i = from; i <= limit; i++) {
    for (let j = 0; j < needle.length; j++) {
      if (haystack[i + j] !== needle[j]) continue outer;
    }
    return i;
  }

  return -1;
}

/**
 * Berichtet, WAS statt der Datei ankam.
 *
 * Der entscheidende Wert ist die Zahl der tatsaechlich gelesenen Bytes: sie
 * trennt "Android hat gar nichts geschickt" (0 Bytes -> Problem liegt beim
 * Teilen bzw. am WebAPK) von "die Bytes waren da, liessen sich aber nicht
 * zuordnen" (viele Bytes -> Problem liegt beim Parsen). Von aussen sehen
 * beide Faelle identisch aus.
 */
function buildFailureReport(formData, formDataError, contentType, contentLength, rawBytes) {
  const parts = [];

  parts.push('gelesen: ' + (rawBytes ? rawBytes.length + ' Bytes' : 'Body nicht lesbar'));
  parts.push('Content-Length: ' + contentLength);
  parts.push('Content-Type: ' + contentType);

  if (formDataError) parts.push('formData() warf ' + formDataError);

  const entries = formData ? describeEntries(formData) : [];
  parts.push(entries.length ? 'Felder: ' + entries.join(', ') : 'keine Formularfelder');

  return 'Keine verwertbare Datei empfangen. ' + parts.join(' | ');
}

function describeEntries(formData) {
  const parts = [];

  for (const entry of formData.entries()) {
    const key = entry[0];
    const value = entry[1];
    if (typeof value === 'string') {
      parts.push(key + '="' + value.slice(0, 60) + '"');
    } else {
      parts.push(key + '=Datei(' + (value.type || 'ohne Typ') + ', ' + value.size + ' Bytes)');
    }
  }

  return parts;
}

/**
 * Niemals einen leeren Text liefern. Genau daran ist die Diagnose vorher
 * gescheitert: DOMExceptions wie QuotaExceededError haben in Chrome einen
 * aussagekraeftigen .name, aber eine leere .message.
 */
function describeError(error) {
  if (!error) return 'Unbekannter Fehler (kein Fehlerobjekt).';
  const name = error.name || 'Error';
  const message = error.message || '(keine Meldung)';
  return name + ': ' + message;
}

function openShareDb() {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(IDB_NAME, 1);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains(IDB_STORE)) db.createObjectStore(IDB_STORE);
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error || new Error('IndexedDB nicht verfuegbar'));
  });
}

async function persistShare(token, file) {
  const db = await openShareDb();
  await new Promise((resolve, reject) => {
    const tx = db.transaction(IDB_STORE, 'readwrite');
    tx.objectStore(IDB_STORE).put({ file: file, name: file.name, type: file.type }, token);
    tx.oncomplete = resolve;
    tx.onerror = () => reject(tx.error);
    tx.onabort = () => reject(tx.error);
  });
}
