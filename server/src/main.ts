import { join } from 'node:path';
import { createPool, migrate } from './db.ts';
import { buildApp } from './app.ts';

// Settings come from the environment (see .env.example); nothing secret is in the code.
const url = process.env.DATABASE_URL;
if (!url) {
  console.error('DATABASE_URL is not set');
  process.exit(1);
}
const db = createPool(url);
await migrate(db, join(import.meta.dirname, '..', 'sql'));
const app = buildApp({ db, dataDir: process.env.DATA_DIR ?? join(import.meta.dirname, '..', 'data'),
                      webDir: process.env.WEB_DIR ?? join(import.meta.dirname, '..', '..', 'web', 'dist') });
const port = Number(process.env.PORT ?? 8080);
await app.listen({ port, host: '0.0.0.0' });
console.log(`КФХ — Учёт API on :${port}`);
