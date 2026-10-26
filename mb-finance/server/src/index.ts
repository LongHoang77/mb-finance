import path from 'node:path';
import { openDb } from './db.js';
import { createApp } from './app.js';

const port = Number(process.env.PORT ?? 8080);
const host = process.env.HOST ?? '0.0.0.0'; // trong container; cổng ra ngoài chỉ mở trên 127.0.0.1
const db = openDb();
const app = createApp(db, { webDir: process.env.WEB_DIR ?? path.resolve('public') });

const server = app.listen(port, host, () => console.log(`[server] listening on ${host}:${port}`));

for (const sig of ['SIGINT', 'SIGTERM'] as const) {
  process.on(sig, () => {
    server.close(() => { db.close(); process.exit(0); });
  });
}
