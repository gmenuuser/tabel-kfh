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
                      webDir: process.env.WEB_DIR ?? join(import.meta.dirname, '..', '..', 'web', 'dist'),
                      // The roll-out switch: phones pair with a code; while it is off an old build may register itself.
                      keysRequired: process.env.KEYS_REQUIRED === 'true' });
const port = Number(process.env.PORT ?? 8080);
await app.listen({ port, host: '0.0.0.0' });
console.log(`КФХ — Учёт API on :${port}`);
// A missing pepper still works, but a database dump would then expose every live 4-digit code by brute force.
if (!process.env.KEYS_PEPPER) {
  console.warn('[keys] KEYS_PEPPER пуст: коды хешируются без «перца» — задайте его в .env');
}
console.log(`[keys] ${process.env.KEYS_REQUIRED === 'true' ? 'вход по коду обязателен' : 'открытая регистрация ещё разрешена (KEYS_REQUIRED != true)'}`);
