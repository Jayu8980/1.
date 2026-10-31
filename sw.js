// Offline cache (network-first so updates are picked up)
const CACHE = 'voxforge-v1';
const FILES = ['./', 'index.html', 'css/style.css', 'manifest.json', 'icons/icon.svg',
  'js/app.js', 'js/arrange.js', 'js/engine.js', 'js/inspector.js', 'js/io.js', 'js/lyrics.js', 'js/midi.js',
  'js/paramlane.js', 'js/pianoroll.js', 'js/singers.js', 'js/state.js', 'js/synth.js', 'js/tools.js', 'js/ui.js', 'js/util.js', 'js/wav.js'];
self.addEventListener('install', (e) => { e.waitUntil(caches.open(CACHE).then((c) => c.addAll(FILES))); self.skipWaiting(); });
self.addEventListener('activate', (e) => { e.waitUntil(caches.keys().then((ks) => Promise.all(ks.filter((k) => k !== CACHE).map((k) => caches.delete(k))))); self.clients.claim(); });
self.addEventListener('fetch', (e) => {
  if (e.request.method !== 'GET') return;
  e.respondWith(fetch(e.request).then((r) => { const cp = r.clone(); caches.open(CACHE).then((c) => c.put(e.request, cp)); return r; }).catch(() => caches.match(e.request)));
});
