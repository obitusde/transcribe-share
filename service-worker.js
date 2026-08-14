// service-worker.js
// Version: 1 (2026-08-14)
// Faengt den Web-Share-Target-POST ab, liest die geteilte Audiodatei, kodiert
// sie als Base64 und schickt sie im selben Muster wie diagnose-upload-test.html
// an die Apps-Script-doPost-URL weiter.

const CACHE_VERSION = 'transcribe-share-v1';
const SCRIPT_URL = 'https://script.google.com/macros/s/AKfycbxkkdYTJi24p-f0BY9M3SbnpPYEBQcxjclpJ3K1pUKbolUqb4DpuaM7XfAwE5KRHWc8ig/exec';

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

  // Navigation: network-first, damit Aenderungen an index.html nicht ewig im
  // Cache haengen bleiben (bekannter Fallstrick aus einem frueheren Projekt).
  if (event.request.mode === 'navigate') {
    event.respondWith(
      fetch(event.request).catch(() => caches.match('./index.html'))
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
      return redirectWithStatus('error', 'Keine Datei empfangen.');
    }

    const base64 = await blobToBase64(file);

    const response = await fetch(SCRIPT_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'text/plain;charset=utf-8' },
      body: JSON.stringify({
        filename: file.name || 'share-upload.m4a',
        mimeType: file.type || 'audio/mp4',
        data: base64
      })
    });

    const text = await response.text();
    let ok = false;
    try {
      ok = JSON.parse(text).status === 'ok';
    } catch (e) {
      // Antwort war kein JSON - als Fehler behandeln, Rohtext zeigen.
    }

    return redirectWithStatus(ok ? 'ok' : 'error', text);

  } catch (error) {
    return redirectWithStatus('error', error.message);
  }
}

function redirectWithStatus(status, message) {
  const safeMessage = (message || '').slice(0, 400);
  const target = `./?status=${encodeURIComponent(status)}&message=${encodeURIComponent(safeMessage)}`;
  return Response.redirect(target, 303);
}

function blobToBase64(blob) {
  return blob.arrayBuffer().then((buffer) => {
    let binary = '';
    const bytes = new Uint8Array(buffer);
    const chunkSize = 0x8000;
    for (let i = 0; i < bytes.length; i += chunkSize) {
      binary += String.fromCharCode.apply(null, bytes.subarray(i, i + chunkSize));
    }
    return btoa(binary);
  });
}
