import { randomBytes, scrypt, timingSafeEqual, createHash } from 'node:crypto';
import type { Db } from './db.ts';
import type { Role } from './entities.ts';

const SCRYPT_KEYLEN = 64;

function scryptAsync(password: string, salt: Buffer): Promise<Buffer> {
  return new Promise((resolve, reject) =>
    scrypt(password, salt, SCRYPT_KEYLEN, { N: 16384, r: 8, p: 1 }, (err, key) => (err ? reject(err) : resolve(key))));
}

/** «scrypt$<salt hex>$<key hex>». */
export async function hashPassword(password: string): Promise<string> {
  const salt = randomBytes(16);
  const key = await scryptAsync(password, salt);
  return `scrypt$${salt.toString('hex')}$${key.toString('hex')}`;
}

export async function verifyPassword(password: string, stored: string): Promise<boolean> {
  const [kind, saltHex, keyHex] = stored.split('$');
  if (kind !== 'scrypt' || !saltHex || !keyHex) return false;
  const key = await scryptAsync(password, Buffer.from(saltHex, 'hex'));
  const expected = Buffer.from(keyHex, 'hex');
  return key.length === expected.length && timingSafeEqual(key, expected);
}

export const sha256 = (s: string | Buffer) => createHash('sha256').update(s).digest('hex');

/** A device token: shown to the device once, stored as its SHA-256. */
export function newToken(): string {
  return randomBytes(32).toString('base64url');
}

export interface Session {
  userId: string;
  farmId: string;
  deviceId: string;
  role: Role;
}

/** The session of a bearer token; null for an unknown or revoked device or a member who was removed. */
export async function sessionOf(db: Db, token: string | undefined): Promise<Session | null> {
  if (!token) return null;
  const r = await db.query(
    `SELECT d.id AS device_id, d.user_id, d.farm_id, m.role
       FROM devices d JOIN memberships m ON m.farm_id = d.farm_id AND m.user_id = d.user_id
      WHERE d.token_hash = $1 AND d.revoked_at IS NULL`, [sha256(token)]);
  if (r.rowCount === 0) return null;
  const row = r.rows[0];
  await db.query('UPDATE devices SET last_seen_at = now() WHERE id = $1', [row.device_id]);
  return { userId: row.user_id, farmId: row.farm_id, deviceId: row.device_id, role: row.role };
}

export function validPassword(p: unknown): p is string {
  return typeof p === 'string' && p.length >= 8 && p.length <= 200;
}

export function normalEmail(e: unknown): string | null {
  if (typeof e !== 'string') return null;
  const t = e.trim().toLowerCase();
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(t) && t.length <= 200 ? t : null;
}
