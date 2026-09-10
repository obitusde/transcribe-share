// service-worker.js
// Version: 8 (2026-09-10)
// Faengt den Web-Share-Target-POST ab, reicht die geteilte Datei an die Seite
// weiter und leitet SOFORT dorthin um (statt den kompletten Upload
// abzuwarten, bevor irgendwas angezeigt wird - das fuehrte zu einem
// eingefrorenen weissen Bildschirm waehrend des Uploads).
//
// v3: Navigation nutzt cache:'no-store', damit Aenderungen an index.html
// IMMER sofort ankommen (network-first allein reichte nicht, weil fetch()
// sonst still aus dem normalen HTTP-Cache bedient werden kann).
//
// v8: Die Datei geht NICHT mehr durch die Cache API. Grund: cache.put() mit
// einer langen Aufnahme (56 Minuten) lief in einen QuotaExceededError, und
// Chrome liefert bei dieser DOMException eine LEERE .message - der alte
// Code hat nur error.message weitergereicht, die Seite zeigte deshalb ein
// nacktes "Fehler beim Hochladen" ohne jeden Hinweis, und der Upload-Code
// wurde nie erreicht.
//
// Stattdessen: die File-Referenz wandert direkt per postMessage an die Seite
// (kein Kopieren, keine Quota - das File-Objekt ist nur ein Handle auf die
// Datei, die Android ohnehin schon auf der Platte hat). Als Absicherung fuer
// den Fall, dass der Service Worker zwischen Redirect und Seitenaufbau
// beendet wird, wird zusaetzlich im Hintergrund nach IndexedDB geschrieben -
// scheitert das, ist es egal, solange der Speicherweg traegt.

const CACHE_VERSION = 'transcribe-share-v8';
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
    const formData = await event.request.formData();
    const file = formData.get('audio');

    if (!file) {
      return redirectWithError('Keine Datei empfangen.');
    }

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
