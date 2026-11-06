import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import type { FastifyInstance } from 'fastify';
import { createPool, migrate, type Db } from '../src/db.ts';
import { buildApp } from '../src/app.ts';

/** A test database: TEST_DATABASE_URL, by default the container of `npm run test:db`. */
export const TEST_URL = process.env.TEST_DATABASE_URL ?? 'postgres://kfh:kfh@127.0.0.1:55432/kfh_test';

export interface Harness {
  app: FastifyInstance;
  db: Db;
  logs: string[];
  close(): Promise<void>;
}

export async function harness(authAttemptsPerMinute = 10_000): Promise<Harness> {
  const db = createPool(TEST_URL);
  await migrate(db, join(import.meta.dirname, '..', 'sql'));
  await db.query(`TRUNCATE users, farms, memberships, devices, invites, password_resets, records, processed_ops,
                  conflicts, files, backups, audit_log CASCADE`);
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

export async function register(app: FastifyInstance, extra: Record<string, unknown> = {}): Promise<Device> {
  counter += 1;
  const r = await app.inject({ method: 'POST', url: '/auth/register', payload: {
    email: `owner${counter}-${Date.now()}@example.com`, password: 'пароль-12345', name: 'Зубахин Иван',
    farmName: 'КФХ Зубахин', deviceName: 'iPhone', platform: 'ios', ...extra } });
  if (r.statusCode !== 201) throw new Error(r.body);
  return r.json();
}

export async function login(app: FastifyInstance, email: string, password: string, platform = 'android'): Promise<Device> {
  const r = await app.inject({ method: 'POST', url: '/auth/login', payload: { email, password, deviceName: platform, platform } });
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
