import pg from 'pg';
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

export type Db = pg.Pool;
export type Tx = pg.PoolClient;

export function createPool(url: string): Db {
  return new pg.Pool({ connectionString: url, max: 10 });
}

/** Applies every sql/NNN_*.sql once, in order. */
export async function migrate(db: Db, sqlDir: string): Promise<void> {
  await db.query('CREATE TABLE IF NOT EXISTS schema_migrations (name text PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now())');
  const done = new Set((await db.query('SELECT name FROM schema_migrations')).rows.map((r) => r.name as string));
  for (const name of readdirSync(sqlDir).filter((f) => f.endsWith('.sql')).sort()) {
    if (done.has(name)) continue;
    const client = await db.connect();
    try {
      await client.query('BEGIN');
      await client.query(readFileSync(join(sqlDir, name), 'utf8'));
      await client.query('INSERT INTO schema_migrations (name) VALUES ($1)', [name]);
      await client.query('COMMIT');
    } catch (e) {
      await client.query('ROLLBACK');
      throw e;
    } finally {
      client.release();
    }
  }
}

export async function inTransaction<T>(db: Db, work: (tx: Tx) => Promise<T>): Promise<T> {
  const tx = await db.connect();
  try {
    await tx.query('BEGIN');
    const result = await work(tx);
    await tx.query('COMMIT');
    return result;
  } catch (e) {
    await tx.query('ROLLBACK');
    throw e;
  } finally {
    tx.release();
  }
}
