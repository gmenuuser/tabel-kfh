// Ключи доступа: админ выдаёт человеку 4-значный код. Код одноразовый, живёт ограниченное время и хранится
// только как SHA-256 с «перцем» сервера — сам код после создания показать нельзя.
import { randomInt } from 'node:crypto';
import type { Db } from './db.ts';
import { sha256 } from './auth.ts';

export const CODE_LENGTH = 4;
/** Roles a key may hand out. `owner` is absent on purpose: a phone never changes the farm's settings. */
export const KEY_ROLES = ['manager', 'timesheet', 'coal', 'livestock', 'viewer'] as const;
export type KeyRole = (typeof KEY_ROLES)[number];

/** Ровно четыре цифры; всё остальное — не код. */
export function normalCode(v: unknown): string | null {
  if (typeof v !== 'string') return null;
  const t = v.trim();
  return new RegExp(`^\\d{${CODE_LENGTH}}$`).test(t) ? t : null;
}

/** The stored hash: the farm is part of it, so the same four digits in two farms are different rows. */
export function codeHash(farmId: string, code: string): string {
  return sha256(`${process.env.KEYS_PEPPER ?? ''}:${farmId}:${code}`);
}

export interface KeyRow { id: string; role: KeyRole; personName: string; userId: string | null }

export type Redeem =
  | { ok: true; key: KeyRow }
  | { ok: false; reason: 'wrong' | 'expired' | 'used' };

type Queryable = { query: Db['query'] };

/**
 * Consumes the code: one UPDATE both checks and marks it used, so two phones racing on the same code
 * cannot both win. A code that is used, expired or revoked never comes back as usable.
 */
export async function redeemKey(tx: Queryable, farmId: string, code: string): Promise<Redeem> {
  const hash = codeHash(farmId, code);
  const used = await tx.query(
    `UPDATE access_keys SET used_at = now()
      WHERE farm_id = $1 AND code_hash = $2 AND used_at IS NULL AND revoked_at IS NULL AND expires_at > now()
      RETURNING id, role, person_name, user_id`, [farmId, hash]);
  if (used.rowCount) {
    const r = used.rows[0];
    return { ok: true, key: { id: r.id, role: r.role, personName: r.person_name, userId: r.user_id } };
  }
  // Nothing was consumed: say why, without telling a stranger more than he already knows.
  const why = (await tx.query(
    `SELECT used_at, expires_at, revoked_at FROM access_keys
      WHERE farm_id = $1 AND code_hash = $2 ORDER BY created_at DESC LIMIT 1`, [farmId, hash])).rows[0];
  if (!why || why.revoked_at) return { ok: false, reason: 'wrong' };
  if (why.used_at) return { ok: false, reason: 'used' };
  return { ok: false, reason: 'expired' };
}

/**
 * A new key: a free four-digit code, unique among the farm's live codes. The unique index is the referee —
 * on a collision (23505) another code is drawn.
 */
export async function createKey(tx: Queryable, farmId: string, createdBy: string,
                                o: { personName: string; role: KeyRole; days: number; userId?: string }): Promise<{ id: string; code: string }> {
  const days = Math.min(30, Math.max(1, Math.round(o.days)));
  for (let attempt = 0; attempt < 20; attempt += 1) {
    const code = String(randomInt(0, 10 ** CODE_LENGTH)).padStart(CODE_LENGTH, '0');
    try {
      const r = await tx.query(
        `INSERT INTO access_keys (farm_id, person_name, role, code_hash, expires_at, created_by, user_id)
         VALUES ($1, $2, $3, $4, now() + make_interval(days => $5::int), $6, $7) RETURNING id`,
        [farmId, o.personName, o.role, codeHash(farmId, code), days, createdBy, o.userId ?? null]);
      return { id: r.rows[0].id as string, code };
    } catch (e) {
      if ((e as { code?: string }).code !== '23505') throw e;
    }
  }
  throw new Error('Не удалось подобрать свободный код, попробуйте ещё раз');
}
