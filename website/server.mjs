import http from 'node:http';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createReadStream } from 'node:fs';
import { stat } from 'node:fs/promises';

const root = fileURLToPath(new URL('./dist/', import.meta.url));
const port = Number(process.env.SWEEP_SITE_PORT || 4177);
const types = { '.html': 'text/html; charset=utf-8', '.css': 'text/css; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png', '.jpg': 'image/jpeg', '.ttf': 'font/ttf',
  '.txt': 'text/plain; charset=utf-8', '.apk': 'application/vnd.android.package-archive' };
const server = http.createServer(async (req, res) => {
  try {
    if (!['GET', 'HEAD'].includes(req.method)) { res.writeHead(405); return res.end(); }
    const pathname = decodeURIComponent(new URL(req.url, 'http://localhost').pathname);
    const file = path.resolve(root, '.' + (pathname.endsWith('/') ? pathname + 'index.html' : pathname));
    if (!file.startsWith(root)) { res.writeHead(403); return res.end('Forbidden'); }
    const info = await stat(file);
    if (!info.isFile()) { res.writeHead(404); return res.end('Not found'); }
    res.writeHead(200, { 'Content-Type': types[path.extname(file)] || 'application/octet-stream', 'Content-Length': info.size,
      'X-Content-Type-Options': 'nosniff', 'Referrer-Policy': 'strict-origin-when-cross-origin', 'Cache-Control': 'no-cache' });
    if (req.method === 'HEAD') return res.end();
    createReadStream(file).pipe(res);
  } catch { res.writeHead(404); res.end('Not found'); }
});
server.listen(port, '127.0.0.1', () => process.stdout.write(`Sweep TV Local: http://127.0.0.1:${port}\n`));
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => server.close(() => process.exit(0)));
