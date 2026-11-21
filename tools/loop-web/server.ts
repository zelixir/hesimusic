// Minimal static server for Loop Lab (no external CDN, no network access).
// Run from the repo root:  bun run tools/loop-web/server.ts
// or from this directory:  bun run dev
import { existsSync, statSync } from 'node:fs';
import { extname, join, normalize, resolve, sep } from 'node:path';

const ROOT = resolve(import.meta.dir, 'public');
const START_PORT = 8899;

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
  '.map': 'application/json; charset=utf-8',
  '.woff2': 'font/woff2',
};

function serveFile(pathname) {
  let rel;
  try {
    rel = decodeURIComponent(pathname);
  } catch {
    return new Response('Bad request', { status: 400 });
  }
  if (rel === '/' || rel === '') rel = '/index.html';
  const clean = normalize(rel).replace(/^([/\\])+/, '');
  const file = resolve(ROOT, clean);
  if (file !== ROOT && !file.startsWith(ROOT + sep)) {
    return new Response('Forbidden', { status: 403 });
  }
  if (!existsSync(file) || !statSync(file).isFile()) {
    return new Response('Not found: ' + rel, { status: 404, headers: { 'content-type': 'text/plain; charset=utf-8' } });
  }
  const type = MIME[extname(file).toLowerCase()] || 'application/octet-stream';
  const body = Bun.file(file);
  return new Response(body, {
    headers: {
      'content-type': type,
      'cache-control': 'no-store',
      'x-content-type-options': 'nosniff',
    },
  });
}

let port = START_PORT;
let server = null;
for (let attempt = 0; attempt < 100; attempt++) {
  try {
    server = Bun.serve({
      port,
      hostname: '127.0.0.1',
      fetch(req) {
        const url = new URL(req.url);
        if (req.method !== 'GET' && req.method !== 'HEAD') {
          return new Response('Method not allowed', { status: 405 });
        }
        return serveFile(url.pathname);
      },
      error(err) {
        console.error('[loop-web] server error:', err);
        return new Response('Internal error', { status: 500 });
      },
    });
    break;
  } catch (err) {
    const code = err && err.code;
    const message = err && err.message ? String(err.message) : String(err);
    if (code === 'EADDRINUSE' || message.includes('EADDRINUSE') || message.includes('in use')) {
      console.warn('[loop-web] port ' + port + ' is busy, trying ' + (port + 1) + '...');
      port++;
      continue;
    }
    throw err;
  }
}
if (!server) {
  console.error('[loop-web] could not find a free port in 8899..8998');
  process.exit(1);
}

console.log('');
console.log('  Loop Lab — multi-algorithm loop analysis');
console.log('  serving: ' + ROOT);
console.log('  open:    http://127.0.0.1:' + port);
console.log('  stop:    Ctrl+C');
console.log('');

process.on('SIGINT', () => {
  console.log('\n[loop-web] shutting down');
  server.stop(true);
  process.exit(0);
});
