import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import type { FastifyInstance } from 'fastify';
import { createPool, migrate, type Db } from '../src/db.ts';
import { buildApp } from '../src/app.ts';
import { hashPassword } from '../src/auth.ts';

/** A test database: TEST_DATABASE_URL, by default the container of `npm run test:db`. */
export const TEST_URL = process.env.TEST_DATABASE_URL ?? 'postgres://kfh:kfh@127.0.0.1:55432/kfh_test';

export const resetDb = (db: Db) => db.query(`TRUNCATE users, farms, memberships, devices, invites, password_resets, records,
                  processed_ops, conflicts, files, backups, audit_log CASCADE`);

export interface Harness {
  app: FastifyInstance;
  db: Db;
  logs: string[];
  close(): Promise<void>;
}

export interface HarnessOptions {
  authAttemptsPerMinute?: number;
}

/** `harness(3)` limits sign-in attempts. */
export async function harness(options: number | HarnessOptions = {}): Promise<Harness> {
  const o = typeof options === 'number' ? { authAttemptsPerMinute: options } : options;
  const authAttemptsPerMinute = o.authAttemptsPerMinute ?? 10_000;
  const db = createPool(TEST_URL);
  await migrate(db, join(import.meta.dirname, '..', 'sql'));
  await resetDb(db);
  const logs: string[] = [];
  const app = buildApp({ db, dataDir: mkdtempSync(join(tmpdir(), 'kfh-')), log: (l) => logs.push(l), authAttemptsPerMinute });
  await app.ready();
  return { app, db, logs, close: async () => { await app.close(); await db.end(); } };
}

export interface Device {
  token: string;
  farmId: string;
  userId: string;
  deviceId: string;
  role: string;
}

let counter = 0;

/** The owner of a new farm (user, farm, owner membership). Devices are not created here. */
export async function seedOwner(db: Db): Promise<{ email: string; password: string }> {
  counter += 1;
  const email = `owner${counter}-${Date.now()}@example.com`;
  const password = 'пароль-12345';
  const u = await db.query('INSERT INTO users (email, name, password_hash) VALUES ($1, $2, $3) RETURNING id',
    [email, 'Зубахин Иван', await hashPassword(password)]);
  const f = await db.query("INSERT INTO farms (name, manager_name) VALUES ('КФХ Зубахин', 'Зубахин Иван') RETURNING id");
  await db.query("INSERT INTO memberships (farm_id, user_id, role) VALUES ($1, $2, 'owner')", [f.rows[0].id, u.rows[0].id]);
  return { email, password };
}

/** The owner's site session: a new farm and a login with platform `web`. */
export async function owner(app: FastifyInstance, db: Db): Promise<Device> {
  const { email, password } = await seedOwner(db);
  return login(app, email, password, 'web');
}

/** A phone app: connects itself with `/devices/register` (to the oldest farm), no account. */
export async function appDevice(app: FastifyInstance, platform = 'android'): Promise<Device> {
  counter += 1;
  // Each call comes from its own address, so the hourly registration limit does not get in the way.
  const r = await app.inject({ method: 'POST', url: '/devices/register', remoteAddress: `10.1.${Math.floor(counter / 250)}.${counter % 250}`,
    payload: { name: platform, platform } });
  if (r.statusCode !== 201) throw new Error(r.body);
  const d = r.json();
  return { token: d.token, farmId: d.farmId, userId: '', deviceId: d.deviceId, role: d.role };
}

export async function login(app: FastifyInstance, email: string, password: string, platform = 'web'): Promise<Device> {
  const r = await app.inject({ method: 'POST', url: '/auth/login', payload: { login: email, password, deviceName: platform, platform } });
  if (r.statusCode !== 200) throw new Error(r.body);
  return r.json();
}

export const auth = (d: Device) => ({ authorization: `Bearer ${d.token}` });

export async function pushOps(app: FastifyInstance, d: Device, ops: object[]) {
  const r = await app.inject({ method: 'POST', url: '/sync/push', headers: auth(d), payload: { ops } });
  if (r.statusCode !== 200) throw new Error(`${r.statusCode} ${r.body}`);
  return r.json().results as { opId: string; status: string; version?: number; conflicts?: string[]; errors?: string[]; repeated?: boolean }[];
}

export async function pullAll(app: FastifyInstance, d: Device, since = 0) {
  const r = await app.inject({ method: 'GET', url: `/sync/pull?since=${since}`, headers: auth(d) });
  if (r.statusCode !== 200) throw new Error(`${r.statusCode} ${r.body}`);
  return r.json() as { changes: { entity: string; id: string; version: number; deleted: boolean; data: Record<string, unknown> }[]; next: number; more: boolean };
}

export function upsert(entity: string, recordId: string, fields: object, baseVersion = 0) {
  return { opId: randomUUID(), entity, recordId, kind: 'upsert', baseVersion, fields };
}

export function remove(entity: string, recordId: string, baseVersion: number) {
  return { opId: randomUUID(), entity, recordId, kind: 'delete', baseVersion };
}

export { randomUUID };
