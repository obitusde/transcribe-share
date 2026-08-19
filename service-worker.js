// service-worker.js
// Version: 6 (2026-08-19)
// Faengt den Web-Share-Target-POST ab, legt die geteilte Datei kurz im Cache
// ab und leitet SOFORT zur Seite weiter (statt den kompletten Upload
// abzuwarten, bevor irgendwas angezeigt wird - das fuehrte zu einem
// eingefrorenen weissen Bildschirm waehrend des Uploads). Die eigentliche
// Base64-Kodierung + der Upload passieren danach in index.html, wo eine
// sichtbare Ladeanzeige moeglich ist.
//
// v3: Navigation nutzt jetzt cache:'no-store', damit Aenderungen an
// index.html IMMER sofort ankommen, ohne dass der Nutzer manuell den
// Browser-Cache leeren muss (network-first allein reichte nicht, weil
// fetch() sonst still aus dem normalen HTTP-Cache bedient werden kann).

const CACHE_VERSION = 'transcribe-share-v6';
const SHARE_CACHE_KEY = './__shared-file__';

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

self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url);

  if (event.request.method === 'POST' && url.pathname.endsWith('/share-target/')) {
    event.respondWith(handleShareTarget(event.request));
    return;
  }

  // Navigation: IMMER frisch vom Netz (no-store), damit Aenderungen an
  // index.html sofort ankommen. Reines "network-first" reicht nicht, weil
  // fetch() sonst still aus dem normalen HTTP-Cache bedient werden kann.
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

async function handleShareTarget(request) {
  try {
    const formData = await request.formData();
    const file = formData.get('audio');

    if (!file) {
      return Response.redirect('./?status=error&message=' + encodeURIComponent('Keine Datei empfangen.'), 303);
    }

    const cache = await caches.open(CACHE_VERSION);
    await cache.put(SHARE_CACHE_KEY, new Response(file, {
      headers: {
        'Content-Type': file.type || 'application/octet-stream',
        'X-Shared-Filename': encodeURIComponent(file.name || 'share-upload.m4a')
      }
    }));

    // Schneller Redirect, BEVOR hochgeladen wird - die Seite laedt jetzt
    // sofort und zeigt selbst eine Ladeanzeige waehrend des eigentlichen Uploads.
    return Response.redirect('./?share=pending', 303);

  } catch (error) {
    return Response.redirect('./?status=error&message=' + encodeURIComponent(error.message), 303);
  }
}
