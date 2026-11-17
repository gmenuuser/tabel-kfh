import Fastify, { type FastifyInstance, type FastifyReply, type FastifyRequest } from 'fastify';
import { randomInt } from 'node:crypto';
import { mkdirSync, writeFileSync, readFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import type { Db } from './db.ts';
import { inTransaction } from './db.ts';
import { hashPassword, verifyPassword, newToken, sha256, sessionOf, validPassword, normalEmail, type Session } from './auth.ts';
import { redeemKey, normalCode, createKey, KEY_ROLES, type KeyRole } from './keys.ts';
import { ENTITIES, canRead, type Role } from './entities.ts';
import { push, pull, openConflicts, resolveConflict, type PushOp } from './sync.ts';

export interface AppOptions {
  db: Db;
  /** Where uploaded files and backups are kept. */
  dataDir: string;
  /** Reset codes are written here instead of e-mailed (no mail server yet). */
  log?: (line: string) => void;
  /** Sign-in attempts per address and minute (20). */
  authAttemptsPerMinute?: number;
  /** A phone must present an admin-issued code; off during the roll-out, when old builds may still register. */
  keysRequired?: boolean;
  /** The built site (web/dist): served from the same address, so no CORS and no second server. */
  webDir?: string;
}

const STATIC_TYPES: Record<string, string> = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.webmanifest': 'application/manifest+json', '.json': 'application/json',
  '.woff2': 'font/woff2', '.ico': 'image/x-icon',
};

declare module 'fastify' {
  interface FastifyRequest { session: Session }
}

const PLATFORMS = new Set(['ios', 'android', 'web', 'test']);
/** A malformed id in a path is a record that does not exist (404), never a database error (500). */
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** A small per-address limit (default window: a minute). */
function limiter(max: number, windowMs = 60_000) {
  const hits = new Map<string, { n: number; until: number }>();
  let nextSweep = Date.now() + windowMs;
  return (key: string) => {
    const now = Date.now();
    // Expired entries are dropped once per window, so the map cannot grow forever.
    if (now >= nextSweep) {
      for (const [k, v] of hits) if (v.until < now) hits.delete(k);
      nextSweep = now + windowMs;
    }
    const h = hits.get(key);
    if (!h || h.until < now) { hits.set(key, { n: 1, until: now + windowMs }); return true; }
    h.n += 1;
    return h.n <= max;
  };
}

export function buildApp(o: AppOptions): FastifyInstance {
  const { db } = o;
  const log = o.log ?? ((l: string) => console.log(l));
  // Behind the host nginx the peer is loopback or the docker bridge gateway (172.16/12); only they may set X-Forwarded-For,
  // so the per-IP attempt limit sees the real client and cannot be dodged by a forged header.
  const app = Fastify({ logger: false, bodyLimit: 20 * 1024 * 1024, trustProxy: ['127.0.0.1', '::1', '172.16.0.0/12'] });
  const allow = limiter(o.authAttemptsPerMinute ?? 20);
  // Self-registration of app devices: a separate, hourly limit per address.
  const allowRegister = limiter(5, 3_600_000);
  // Pairing with a code: 20 attempts an hour per address, else the address rests a day; 200 wrong codes an hour
  // stop the farm for half an hour. A four-digit code is 10 000 options, so the window has to be short.
  const allowPair = limiter(20, 3_600_000);
  const wrongPair = limiter(200, 3_600_000);
  const pairBlocked = new Map<string, number>();
  const pairPaused = new Map<string, number>();
  const PAIR_LIMIT = 'Слишком много попыток, попробуйте позже';
  mkdirSync(join(o.dataDir, 'files'), { recursive: true });
  mkdirSync(join(o.dataDir, 'backups'), { recursive: true });

  app.addContentTypeParser('application/octet-stream', { parseAs: 'buffer' }, (_r, body, done) => done(null, body));
  app.addContentTypeParser(/^image\/.*|^application\/pdf$/, { parseAs: 'buffer' }, (_r, body, done) => done(null, body));

  const fail = (reply: FastifyReply, code: number, error: string) => reply.code(code).send({ error });

  async function signedIn(req: FastifyRequest, reply: FastifyReply) {
    const header = req.headers.authorization ?? '';
    const token = header.startsWith('Bearer ') ? header.slice(7) : undefined;
    const s = await sessionOf(db, token);
    if (!s) return fail(reply, 401, 'Нужно войти заново: устройство не подключено или доступ отозван');
    req.session = s;
  }
  const ownerOnly = (req: FastifyRequest, reply: FastifyReply, done: () => void) => {
    if (req.session.role !== 'owner') { fail(reply, 403, 'Это может только владелец хозяйства'); return; }
    done();
  };
  const audit = (s: Session | null, action: string, details: object = {}) =>
    db.query('INSERT INTO audit_log (farm_id, user_id, device_id, action, details) VALUES ($1, $2, $3, $4, $5)',
      [s?.farmId || null, s?.userId || null, s?.deviceId || null, action, JSON.stringify(details)]);

  async function issueDevice(tx: { query: Db['query'] }, farmId: string, userId: string | null, name: unknown, platform: unknown) {
    const token = newToken();
    const p = typeof platform === 'string' && PLATFORMS.has(platform) ? platform : '';
    const r = await tx.query('INSERT INTO devices (farm_id, user_id, name, platform, token_hash) VALUES ($1, $2, $3, $4, $5) RETURNING id',
      [farmId, userId, String(name ?? '').slice(0, 100), p, sha256(token)]);
    return { token, deviceId: r.rows[0].id as string };
  }

  if (o.webDir && existsSync(join(o.webDir, 'index.html'))) {
    const webDir = o.webDir;
    const sendFile = (reply: FastifyReply, rel: string) => {
      const path = join(webDir, rel);
      if (!path.startsWith(webDir) || !existsSync(path)) return fail(reply, 404, 'Не найдено');
      const ext = rel.slice(rel.lastIndexOf('.'));
      // index.html and the service worker are always checked again; the hashed assets are kept for a year.
      const cache = rel.startsWith('assets/') ? 'public, max-age=31536000, immutable' : 'no-cache';
      return reply.header('content-type', STATIC_TYPES[ext] ?? 'application/octet-stream').header('cache-control', cache)
        .send(readFileSync(path));
    };
    app.get('/', (_req, reply) => sendFile(reply, 'index.html'));
    app.get('/assets/:file', (req, reply) => sendFile(reply, `assets/${(req.params as { file: string }).file}`));
    for (const f of ['sw.js', 'manifest.webmanifest', 'icon.svg']) app.get(`/${f}`, (_req, reply) => sendFile(reply, f));
  }

  app.get('/health', async () => {
    await db.query('SELECT 1');
    return { ok: true };
  });

  // MARK: Accounts

  /** Registration of farms is gone; old builds still ask. */
  app.get('/auth/config', async () => ({ registrationOpen: false }));

  /** The owner signs in to the site with an e-mail. Phones no longer sign in: they connect themselves (`/devices/register`). */
  app.post('/auth/login', async (req, reply) => {
    if (!allow(`auth:${req.ip}`)) return fail(reply, 429, 'Слишком много попыток, подождите минуту');
    const b = (req.body ?? {}) as Record<string, unknown>;
    // `login` (or the older `email`) must be an e-mail; a login or any platform but the site is the cancelled way.
    const email = normalEmail(typeof b.login === 'string' ? b.login : b.email);
    if (!email || b.platform !== 'web') return fail(reply, 403, 'Вход по логину отменён — скачайте новую версию приложения у владельца');
    const u = (await db.query('SELECT id, password_hash FROM users WHERE email = $1', [email])).rows[0];
    if (!u || typeof b.password !== 'string' || !(await verifyPassword(b.password, u.password_hash))) {
      await audit(null, 'auth.login.failed', { email });
      return fail(reply, 401, 'Неверный логин или пароль');
    }
    const farms = (await db.query("SELECT farm_id, role FROM memberships WHERE user_id = $1 AND role = 'owner' ORDER BY created_at", [u.id])).rows;
    const m = typeof b.farmId === 'string' ? farms.find((f) => f.farm_id === b.farmId) : farms[0];
    if (!m) return fail(reply, 403, 'Вход на сайт — только для владельца хозяйства');
    const d = await issueDevice(db, m.farm_id, u.id, b.deviceName, 'web');
    await audit({ farmId: m.farm_id, userId: u.id, deviceId: d.deviceId, role: 'owner' }, 'auth.login');
    return { token: d.token, deviceId: d.deviceId, farmId: m.farm_id, userId: u.id, role: 'owner',
             farms: farms.map((f) => ({ farmId: f.farm_id, role: f.role })) };
  });

  app.post('/auth/logout', { preHandler: signedIn }, async (req) => {
    await db.query('UPDATE devices SET revoked_at = now() WHERE id = $1', [req.session.deviceId]);
    await audit(req.session, 'auth.logout');
    return { ok: true };
  });

  /** «Восстановить доступ»: a 6-digit code valid for 30 minutes. With no mail server yet it is written to the
   *  server log; the answer never says whether the address exists. */
  app.post('/auth/reset-request', async (req, reply) => {
    if (!allow(`auth:${req.ip}`)) return fail(reply, 429, 'Слишком много попыток, подождите минуту');
    const email = normalEmail((req.body as Record<string, unknown> | undefined)?.email);
    const u = email ? (await db.query('SELECT id FROM users WHERE email = $1', [email])).rows[0] : undefined;
    if (u) {
      const code = String(randomInt(0, 1_000_000)).padStart(6, '0');
      await db.query("INSERT INTO password_resets (code_hash, user_id, expires_at) VALUES ($1, $2, now() + interval '30 minutes')",
        [sha256(`${email}:${code}`), u.id]);
      log(`[reset] code for ${email}: ${code}`);
    }
    return { ok: true };
  });

  /** A new password with the code; every device of the user is signed out. */
  app.post('/auth/reset', async (req, reply) => {
    if (!allow(`auth:${req.ip}`)) return fail(reply, 429, 'Слишком много попыток, подождите минуту');
    const b = (req.body ?? {}) as Record<string, unknown>;
    const email = normalEmail(b.email);
    if (!email || typeof b.code !== 'string' || !validPassword(b.password)) return fail(reply, 400, 'Укажите почту, код и новый пароль');
    const r = await db.query(
      `UPDATE password_resets SET used_at = now() WHERE code_hash = $1 AND used_at IS NULL AND expires_at > now() RETURNING user_id`,
      [sha256(`${email}:${b.code}`)]);
    if (!r.rowCount) return fail(reply, 400, 'Код неверный или устарел');
    await db.query('UPDATE users SET password_hash = $2 WHERE id = $1', [r.rows[0].user_id, await hashPassword(b.password)]);
    await db.query('UPDATE devices SET revoked_at = now() WHERE user_id = $1 AND revoked_at IS NULL', [r.rows[0].user_id]);
    await audit({ farmId: '', userId: r.rows[0].user_id, deviceId: '', role: 'viewer' }, 'auth.reset');
    return { ok: true };
  });

  // MARK: Farm, members, devices

  app.get('/me', { preHandler: signedIn }, async (req) => {
    const s = req.session;
    const farm = (await db.query('SELECT id, name, region, manager_name, timezone, new_devices_allowed FROM farms WHERE id = $1', [s.farmId])).rows[0];
    const isAnon = s.role === 'app';
    // A phone is an app, whatever its role: the platform decides, not the role.
    const isPhone = s.platform === 'ios' || s.platform === 'android';
    return { userId: s.userId, deviceId: s.deviceId,
             // Old app builds know no «app» role: they are told «manager».
             role: isAnon ? 'manager' : s.role, kind: isPhone || isAnon ? 'app' : 'owner',
             farm: { id: farm.id, name: farm.name, region: farm.region, managerName: farm.manager_name, timezone: farm.timezone,
                     ...(s.role === 'owner' ? { newDevicesAllowed: farm.new_devices_allowed } : {}) } };
  });

  /** A phone connects with an admin-issued code: the code carries the person and the role. While `keysRequired`
   *  is off, an older build may still register itself with no code at all. */
  app.post('/devices/register', async (req, reply) => {
    const b = (req.body ?? {}) as Record<string, unknown>;
    if (b.platform !== 'ios' && b.platform !== 'android') return fail(reply, 400, 'Платформа: ios или android');
    // One farm: the oldest one.
    const farm = (await db.query('SELECT id, new_devices_allowed FROM farms ORDER BY created_at LIMIT 1')).rows[0];
    if (!farm) return fail(reply, 503, 'Хозяйство ещё не создано');

    const code = b.code === undefined ? null : normalCode(b.code);
    const byCode = o.keysRequired === true || b.code !== undefined;
    // Not four digits: a slip of the finger, not an attempt at the code.
    if (byCode && !code) return fail(reply, 400, 'Укажите 4-значный код');
    if (byCode) {
      if ((pairBlocked.get(req.ip) ?? 0) > Date.now()) return fail(reply, 429, PAIR_LIMIT);
      if (!allowPair(`pair:${req.ip}`)) {
        pairBlocked.set(req.ip, Date.now() + 24 * 3_600_000);
        return fail(reply, 429, PAIR_LIMIT);
      }
      if ((pairPaused.get(farm.id) ?? 0) > Date.now()) return fail(reply, 429, PAIR_LIMIT);
    } else if (!allowRegister(`register:${req.ip}`)) {
      return fail(reply, 429, 'Слишком много попыток, попробуйте позже');
    }

    if (!code) {
      if (!farm.new_devices_allowed) return fail(reply, 403, 'Владелец сейчас не подключает новые устройства');
      const d = await issueDevice(db, farm.id, null, b.name, b.platform);
      await audit({ farmId: farm.id, userId: null, deviceId: d.deviceId, role: 'app' }, 'device.register', { platform: b.platform });
      return reply.code(201).send({ token: d.token, deviceId: d.deviceId, farmId: farm.id, role: 'manager', kind: 'app' });
    }

    // One transaction: the code is consumed, the person and the membership appear, the device is issued.
    const out = await inTransaction(db, async (tx) => {
      const r = await redeemKey(tx, farm.id, code);
      if (!r.ok) return r;
      const userId = r.key.userId ?? (await tx.query(
        "INSERT INTO users (name, password_hash) VALUES ($1, '') RETURNING id", [r.key.personName])).rows[0].id as string;
      await tx.query(`INSERT INTO memberships (farm_id, user_id, role) VALUES ($1, $2, $3)
                      ON CONFLICT (farm_id, user_id) DO NOTHING`,
        [farm.id, userId, r.key.role]);
      const d = await issueDevice(tx, farm.id, userId, b.name, b.platform);
      await tx.query('UPDATE access_keys SET user_id = $2 WHERE id = $1', [r.key.id, userId]);
      // The membership is the authority: a code made before the role was narrowed must not answer with the old role.
      const role = (await tx.query('SELECT role FROM memberships WHERE farm_id = $1 AND user_id = $2',
        [farm.id, userId])).rows[0].role as Role;
      return { ok: true as const, token: d.token, deviceId: d.deviceId, userId, role, personName: r.key.personName };
    });

    if (!out.ok) {
      if (!wrongPair(`wrong:${farm.id}`)) {
        pairPaused.set(farm.id, Date.now() + 30 * 60_000);
        await db.query("INSERT INTO audit_log (farm_id, action, details) VALUES ($1, 'keys.bruteforce', $2)",
          [farm.id, JSON.stringify({ attempts: 200 })]);
        return fail(reply, 429, PAIR_LIMIT);
      }
      const text = out.reason === 'used' ? 'Код уже использован — попросите новый'
        : out.reason === 'expired' ? 'Код просрочен' : 'Код неверный';
      return fail(reply, 403, text);
    }

    await audit({ farmId: farm.id, userId: out.userId, deviceId: out.deviceId, role: out.role }, 'device.pair',
      { personName: out.personName, platform: b.platform });
    return reply.code(201).send({ token: out.token, deviceId: out.deviceId, farmId: farm.id, role: out.role, kind: 'app' });
  });

  app.patch('/farm', { preHandler: [signedIn, ownerOnly] }, async (req, reply) => {
    const b = (req.body ?? {}) as Record<string, unknown>;
    const set: string[] = [];
    const values: unknown[] = [req.session.farmId];
    for (const [key, column] of [['name', 'name'], ['region', 'region'], ['managerName', 'manager_name'], ['timezone', 'timezone']]) {
      if (typeof b[key] === 'string') {
        if (key === 'timezone' && !Intl.supportedValuesOf('timeZone').includes(b[key] as string)) return fail(reply, 400, 'Неизвестный часовой пояс');
        values.push((b[key] as string).trim());
        set.push(`${column} = $${values.length}`);
      }
    }
    if (typeof b.newDevicesAllowed === 'boolean') {
      values.push(b.newDevicesAllowed);
      set.push(`new_devices_allowed = $${values.length}`);
    }
    if (set.length) await db.query(`UPDATE farms SET ${set.join(', ')} WHERE id = $1`, values);
    await audit(req.session, 'farm.update', b);
    return { ok: true };
  });

  /** Every device of the farm: phones (paired, with a person) and the owner's site sessions. */
  app.get('/devices', { preHandler: [signedIn, ownerOnly] }, async (req) => {
    const s = req.session;
    const rows = (await db.query(
      `SELECT d.id, d.name, d.platform, d.user_id, d.created_at, d.last_seen_at, d.revoked_at,
              u.name AS person_name, m.role AS person_role
         FROM devices d
         LEFT JOIN users u ON u.id = d.user_id
         LEFT JOIN memberships m ON m.farm_id = d.farm_id AND m.user_id = d.user_id
        WHERE d.farm_id = $1 ORDER BY d.created_at`,
      [s.farmId])).rows;
    // The kind follows the platform: a paired phone has a real role but is still an app device.
    return rows.map((d) => ({ id: d.id, name: d.name, platform: d.platform,
                              kind: d.platform === 'ios' || d.platform === 'android' ? 'app' : 'owner',
                              personName: d.person_name ?? undefined, role: d.person_role ?? undefined,
                              createdAt: d.created_at, lastSeenAt: d.last_seen_at, revoked: !!d.revoked_at,
                              current: d.id === s.deviceId }));
  });

  app.patch('/devices/:id', { preHandler: [signedIn, ownerOnly] }, async (req, reply) => {
    const { id } = req.params as { id: string };
    const name = (req.body as Record<string, unknown> | undefined)?.name;
    if (typeof name !== 'string' || name.trim().length < 1 || name.trim().length > 100) return fail(reply, 400, 'Название — от 1 до 100 символов');
    if (!UUID.test(id)) return fail(reply, 404, 'Устройство не найдено');
    const r = await db.query('UPDATE devices SET name = $3 WHERE id = $1 AND farm_id = $2', [id, req.session.farmId, name.trim()]);
    if (!r.rowCount) return fail(reply, 404, 'Устройство не найдено');
    await audit(req.session, 'device.rename', { deviceId: id });
    return { ok: true };
  });

  /** «Отключить» a lost or unwanted device. */
  app.post('/devices/:id/revoke', { preHandler: [signedIn, ownerOnly] }, async (req, reply) => {
    const s = req.session;
    const { id } = req.params as { id: string };
    if (!UUID.test(id)) return fail(reply, 404, 'Устройство не найдено');
    const r = await db.query('UPDATE devices SET revoked_at = now() WHERE id = $1 AND farm_id = $2 AND revoked_at IS NULL', [id, s.farmId]);
    if (!r.rowCount) return fail(reply, 404, 'Устройство не найдено');
    await audit(s, 'device.revoke', { deviceId: id });
    return { ok: true };
  });

  /** «Отключить все безымянные устройства»: the phones that paired themselves before codes. Explicit and
   *  confirmed — a phone treats 401 as «wipe the local database», so this is never a side effect of a switch. */
  app.post('/devices/revoke-anonymous', { preHandler: [signedIn, ownerOnly] }, async (req, reply) => {
    if ((req.body as Record<string, unknown> | undefined)?.confirm !== true) {
      return fail(reply, 400, 'Подтвердите: confirm = true');
    }
    const r = await db.query(
      'UPDATE devices SET revoked_at = now() WHERE farm_id = $1 AND user_id IS NULL AND revoked_at IS NULL',
      [req.session.farmId]);
    await audit(req.session, 'devices.revoke.anonymous', { revoked: r.rowCount });
    return { revoked: r.rowCount };
  });

  // MARK: Keys and people

  const KEY_ROLE_SET = new Set<string>(KEY_ROLES);
  const isKeyRole = (v: unknown): v is KeyRole => typeof v === 'string' && KEY_ROLE_SET.has(v);
  /** 1–30 days; anything else is a day. */
  const daysOf = (v: unknown) => {
    const n = Number(v ?? 1);
    return Number.isFinite(n) && n >= 1 ? Math.min(30, Math.round(n)) : 1;
  };
  const ROLE_HINT = 'Роль: manager, timesheet, coal, livestock или viewer';

  /** Every live device of the person goes dark; a revoked key cannot be redeemed again. */
  async function revokePerson(userId: string) {
    await db.query('UPDATE devices SET revoked_at = now() WHERE user_id = $1 AND revoked_at IS NULL', [userId]);
  }

  app.get('/keys', { preHandler: [signedIn, ownerOnly] }, async (req) => {
    const rows = (await db.query(
      `SELECT k.id, k.person_name, k.role, k.expires_at, k.created_at, k.used_at, k.revoked_at, k.user_id,
              d.name AS device_name, d.platform AS device_platform
         FROM access_keys k
         LEFT JOIN LATERAL (SELECT name, platform FROM devices
                             WHERE user_id = k.user_id AND revoked_at IS NULL ORDER BY created_at DESC LIMIT 1) d ON true
        WHERE k.farm_id = $1 ORDER BY k.created_at DESC`, [req.session.farmId])).rows;
    return { keys: rows.map((k) => ({
      id: k.id, personName: k.person_name, role: k.role,
      status: k.revoked_at ? 'revoked' : k.used_at ? 'paired' : new Date(k.expires_at) <= new Date() ? 'expired' : 'free',
      expiresAt: k.expires_at, createdAt: k.created_at, userId: k.user_id,
      deviceName: k.device_name ?? undefined, devicePlatform: k.device_platform ?? undefined })) };
  });

  app.post('/keys', { preHandler: [signedIn, ownerOnly] }, async (req, reply) => {
    const b = (req.body ?? {}) as Record<string, unknown>;
    const name = typeof b.personName === 'string' ? b.personName.trim() : '';
    if (name.length < 1 || name.length > 100) return fail(reply, 400, 'Имя — от 1 до 100 символов');
    if (!isKeyRole(b.role)) return fail(reply, 400, ROLE_HINT);
    const made = await createKey(db, req.session.farmId, req.session.userId ?? '',
      { personName: name, role: b.role, days: daysOf(b.days) });
    await audit(req.session, 'key.create', { keyId: made.id, personName: name, role: b.role });
    return reply.code(201).send(made);
  });

  /** A second code for a person who is already paired (a tablet, a new phone — the old devices stay). */
  app.post('/people/:userId/keys', { preHandler: [signedIn, ownerOnly] }, async (req, reply) => {
    const { userId } = req.params as { userId: string };
    if (!UUID.test(userId)) return fail(reply, 404, 'Человек не найден');
    const m = (await db.query('SELECT role FROM memberships WHERE farm_id = $1 AND user_id = $2',
      [req.session.farmId, userId])).rows[0];
    if (!m || m.role === 'owner' || !isKeyRole(m.role)) return fail(reply, 404, 'Человек не найден');
    const name = (await db.query('SELECT name FROM users WHERE id = $1', [userId])).rows[0]?.name ?? '';
    const made = await createKey(db, req.session.farmId, req.session.userId ?? '',
      { personName: name, role: m.role, days: daysOf((req.body as Record<string, unknown> | undefined)?.days), userId });
    await audit(req.session, 'key.create', { keyId: made.id, personName: name, role: m.role, forUserId: userId });
    return reply.code(201).send(made);
  });

  /** A code instead of a lost phone: every device of the person is cut off, a new code is issued. */
  app.post('/keys/:id/reissue', { preHandler: [signedIn, ownerOnly] }, async (req, reply) => {
    const { id } = req.params as { id: string };
    if (!UUID.test(id)) return fail(reply, 404, 'Ключ не найден');
    const k = (await db.query('SELECT id, person_name, role, user_id FROM access_keys WHERE id = $1 AND farm_id = $2',
      [id, req.session.farmId])).rows[0];
    if (!k) return fail(reply, 404, 'Ключ не найден');
    let role: KeyRole = k.role as KeyRole;
    if (k.user_id) {
      const m = (await db.query('SELECT role FROM memberships WHERE farm_id = $1 AND user_id = $2',
        [req.session.farmId, k.user_id])).rows[0];
      if (m && isKeyRole(m.role)) role = m.role;
      await revokePerson(k.user_id);
    }
    // The old code dies with the reissue: a paper code that was never typed must stop working too.
    await db.query(
      `UPDATE access_keys SET revoked_at = now()
        WHERE farm_id = $1 AND revoked_at IS NULL AND (id = $2 OR ($3::uuid IS NOT NULL AND user_id = $3))`,
      [req.session.farmId, id, k.user_id]);
    const made = await createKey(db, req.session.farmId, req.session.userId ?? '',
      { personName: k.person_name, role, days: daysOf((req.body as Record<string, unknown> | undefined)?.days),
        userId: k.user_id ?? undefined });
    await audit(req.session, 'key.reissue', { keyId: made.id, previousId: id, userId: k.user_id });
    return reply.code(201).send(made);
  });

  app.post('/keys/:id/revoke', { preHandler: [signedIn, ownerOnly] }, async (req, reply) => {
    const { id } = req.params as { id: string };
    if (!UUID.test(id)) return fail(reply, 404, 'Ключ не найден');
    const k = (await db.query('SELECT id, user_id FROM access_keys WHERE id = $1 AND farm_id = $2',
      [id, req.session.farmId])).rows[0];
    if (!k) return fail(reply, 404, 'Ключ не найден');
    await db.query(
      `UPDATE access_keys SET revoked_at = now()
        WHERE farm_id = $1 AND revoked_at IS NULL AND (id = $2 OR ($3::uuid IS NOT NULL AND user_id = $3))`,
      [req.session.farmId, id, k.user_id]);
    if (k.user_id) await revokePerson(k.user_id);
    await audit(req.session, 'key.revoke', { keyId: id, userId: k.user_id });
    return { ok: true };
  });

  app.delete('/keys/:id', { preHandler: [signedIn, ownerOnly] }, async (req, reply) => {
    const { id } = req.params as { id: string };
    if (!UUID.test(id)) return fail(reply, 404, 'Ключ не найден');
    const k = (await db.query('SELECT id, user_id FROM access_keys WHERE id = $1 AND farm_id = $2',
      [id, req.session.farmId])).rows[0];
    if (!k) return fail(reply, 404, 'Ключ не найден');
    if (k.user_id) await revokePerson(k.user_id);
    await db.query('DELETE FROM access_keys WHERE id = $1 AND farm_id = $2', [id, req.session.farmId]);
    await audit(req.session, 'key.revoke', { keyId: id, userId: k.user_id, deleted: true });
    return { ok: true };
  });

  app.get('/people', { preHandler: [signedIn, ownerOnly] }, async (req) => {
    const rows = (await db.query(
      `SELECT u.id, u.name, m.role, count(d.id) FILTER (WHERE d.revoked_at IS NULL)::int AS devices,
              max(d.last_seen_at) AS last_seen
         FROM memberships m JOIN users u ON u.id = m.user_id
         LEFT JOIN devices d ON d.user_id = u.id AND d.farm_id = m.farm_id
        WHERE m.farm_id = $1 AND m.role <> 'owner'
        GROUP BY u.id, u.name, m.role ORDER BY u.name`, [req.session.farmId])).rows;
    return { people: rows.map((p) => ({ id: p.id, name: p.name, role: p.role, devices: p.devices, lastSeenAt: p.last_seen })) };
  });

  app.patch('/people/:id', { preHandler: [signedIn, ownerOnly] }, async (req, reply) => {
    const { id } = req.params as { id: string };
    if (!UUID.test(id)) return fail(reply, 404, 'Человек не найден');
    const b = (req.body ?? {}) as Record<string, unknown>;
    const m = (await db.query('SELECT role FROM memberships WHERE farm_id = $1 AND user_id = $2',
      [req.session.farmId, id])).rows[0];
    if (!m || m.role === 'owner') return fail(reply, 404, 'Человек не найден');
    if (b.name !== undefined) {
      const name = typeof b.name === 'string' ? b.name.trim() : '';
      if (name.length < 1 || name.length > 100) return fail(reply, 400, 'Имя — от 1 до 100 символов');
      await db.query('UPDATE users SET name = $2 WHERE id = $1', [id, name]);
    }
    if (b.role !== undefined) {
      if (!isKeyRole(b.role)) return fail(reply, 400, ROLE_HINT);
      await db.query('UPDATE memberships SET role = $3 WHERE farm_id = $1 AND user_id = $2', [req.session.farmId, id, b.role]);
    }
    await audit(req.session, 'person.update', { userId: id, ...b });
    return { ok: true };
  });

  // MARK: Sync

  app.post('/sync/push', { preHandler: signedIn }, async (req, reply) => {
    const ops = (req.body as { ops?: PushOp[] } | undefined)?.ops;
    if (!Array.isArray(ops) || ops.length > 500) return fail(reply, 400, 'ops — список до 500 изменений');
    return { results: await push(db, req.session, ops) };
  });

  app.get('/sync/pull', { preHandler: signedIn }, async (req) => {
    const q = req.query as { since?: string; limit?: string };
    const since = Math.max(0, Number(q.since ?? 0) || 0);
    const limit = Math.min(1000, Math.max(1, Number(q.limit ?? 500) || 500));
    return pull(db, req.session, since, limit);
  });

  app.get('/conflicts', { preHandler: signedIn }, async (req) => ({ conflicts: await openConflicts(db, req.session) }));

  app.post('/conflicts/:id/resolve', { preHandler: [signedIn, ownerOnly] }, async (req, reply) => {
    const choice = (req.body as Record<string, unknown> | undefined)?.choice;
    if (choice !== 'server' && choice !== 'client') return fail(reply, 400, 'choice — server или client');
    const r = await resolveConflict(db, req.session, (req.params as { id: string }).id, choice);
    return r.ok ? r : fail(reply, 409, r.error ?? 'Не получилось');
  });

  // MARK: Files

  /** Uploads a file once: the same content (SHA-256) of the farm is not stored twice. */
  app.put('/files', { preHandler: signedIn }, async (req, reply) => {
    const body = req.body;
    if (!Buffer.isBuffer(body) || body.length === 0) return fail(reply, 400, 'Пустой файл');
    const hash = sha256(body);
    const s = req.session;
    const existing = (await db.query('SELECT id FROM files WHERE farm_id = $1 AND sha256 = $2', [s.farmId, hash])).rows[0];
    if (existing) return { id: existing.id, sha256: hash, size: body.length, repeated: true };
    const dir = join(o.dataDir, 'files', s.farmId);
    mkdirSync(dir, { recursive: true });
    writeFileSync(join(dir, hash), body);
    const name = decodeURIComponent(String(req.headers['x-file-name'] ?? '')).slice(0, 200);
    const r = await db.query('INSERT INTO files (farm_id, sha256, size, content_type, name, uploaded_by) VALUES ($1, $2, $3, $4, $5, $6) RETURNING id',
      [s.farmId, hash, body.length, String(req.headers['content-type'] ?? 'application/octet-stream'), name, s.deviceId]);
    return reply.code(201).send({ id: r.rows[0].id, sha256: hash, size: body.length });
  });

  app.get('/files/:id', { preHandler: signedIn }, async (req, reply) => {
    const f = (await db.query('SELECT sha256, content_type, name FROM files WHERE id = $1 AND farm_id = $2',
      [(req.params as { id: string }).id, req.session.farmId])).rows[0];
    const path = f ? join(o.dataDir, 'files', req.session.farmId, f.sha256) : '';
    if (!f || !existsSync(path)) return fail(reply, 404, 'Файл не найден');
    return reply.header('content-type', f.content_type)
      .header('content-disposition', `attachment; filename*=UTF-8''${encodeURIComponent(f.name || f.sha256)}`)
      .send(readFileSync(path));
  });

  // MARK: Server backups of the farm

  /** A JSON copy of every record of the farm (deleted ones too) with its SHA-256. */
  app.post('/backups', { preHandler: [signedIn, ownerOnly] }, async (req, reply) => {
    const s = req.session;
    const rows = (await db.query(
      'SELECT entity, id, version, data, field_versions, deleted, created_at, updated_at FROM records WHERE farm_id = $1 ORDER BY entity, id',
      [s.farmId])).rows;
    const content = Buffer.from(JSON.stringify({ format: 'kfh-server-backup', version: 1, farmId: s.farmId,
                                                 createdAt: new Date().toISOString(), records: rows }));
    const hash = sha256(content);
    const r = await db.query('INSERT INTO backups (farm_id, sha256, records, size, created_by) VALUES ($1, $2, $3, $4, $5) RETURNING id, created_at',
      [s.farmId, hash, rows.length, content.length, s.userId]);
    const dir = join(o.dataDir, 'backups', s.farmId);
    mkdirSync(dir, { recursive: true });
    writeFileSync(join(dir, `${r.rows[0].id}.json`), content);
    await audit(s, 'backup.create', { id: r.rows[0].id, records: rows.length });
    return reply.code(201).send({ id: r.rows[0].id, sha256: hash, records: rows.length, createdAt: r.rows[0].created_at });
  });

  app.get('/backups', { preHandler: [signedIn, ownerOnly] }, async (req) => {
    return (await db.query('SELECT id, sha256, records, size, created_at FROM backups WHERE farm_id = $1 ORDER BY created_at DESC',
      [req.session.farmId])).rows;
  });

  /** Checks that the stored copy is whole (its SHA-256 matches). */
  app.get('/backups/:id/verify', { preHandler: [signedIn, ownerOnly] }, async (req, reply) => {
    const b = (await db.query('SELECT id, sha256 FROM backups WHERE id = $1 AND farm_id = $2',
      [(req.params as { id: string }).id, req.session.farmId])).rows[0];
    const path = b ? join(o.dataDir, 'backups', req.session.farmId, `${b.id}.json`) : '';
    if (!b || !existsSync(path)) return fail(reply, 404, 'Копия не найдена');
    return { ok: sha256(readFileSync(path)) === b.sha256 };
  });

  /** Restores the copy: every record comes back as a new version (so every device pulls it); records made after
   *  the copy are marked deleted. Needs `confirm: true`. */
  app.post('/backups/:id/restore', { preHandler: [signedIn, ownerOnly] }, async (req, reply) => {
    if ((req.body as Record<string, unknown> | undefined)?.confirm !== true) return fail(reply, 400, 'Подтвердите восстановление: confirm = true');
    const s = req.session;
    const b = (await db.query('SELECT id, sha256 FROM backups WHERE id = $1 AND farm_id = $2', [(req.params as { id: string }).id, s.farmId])).rows[0];
    const path = b ? join(o.dataDir, 'backups', s.farmId, `${b.id}.json`) : '';
    if (!b || !existsSync(path)) return fail(reply, 404, 'Копия не найдена');
    const content = readFileSync(path);
    if (sha256(content) !== b.sha256) return fail(reply, 409, 'Копия повреждена: контрольная сумма не совпадает');
    const parsed = JSON.parse(content.toString('utf8')) as { records: { entity: string; id: string; data: object; deleted: boolean }[] };
    await inTransaction(db, async (tx) => {
      const keep = new Set(parsed.records.map((r) => `${r.entity}:${r.id}`));
      for (const cur of (await tx.query('SELECT entity, id FROM records WHERE farm_id = $1 AND NOT deleted', [s.farmId])).rows) {
        if (!keep.has(`${cur.entity}:${cur.id}`)) {
          await tx.query(`UPDATE records SET deleted = true, version = version + 1, updated_at = now(), seq = nextval('change_seq')
                          WHERE farm_id = $1 AND entity = $2 AND id = $3`, [s.farmId, cur.entity, cur.id]);
        }
      }
      for (const r of parsed.records) {
        if (!ENTITIES[r.entity]) continue;
        const fv = Object.fromEntries(Object.keys(r.data).map((k) => [k, 1]));
        await tx.query(
          `INSERT INTO records (farm_id, entity, id, version, data, field_versions, deleted, created_by_device, updated_by_device, seq)
           VALUES ($1, $2, $3, 1, $4, $5, $6, $7, $7, nextval('change_seq'))
           ON CONFLICT (farm_id, entity, id) DO UPDATE SET data = $4, deleted = $6, version = records.version + 1,
             field_versions = COALESCE((SELECT jsonb_object_agg(k, records.version + 1) FROM jsonb_object_keys($4::jsonb) k), '{}'::jsonb),
             updated_at = now(), updated_by_device = $7, seq = nextval('change_seq')`,
          [s.farmId, r.entity, r.id, JSON.stringify(r.data), JSON.stringify(fv), r.deleted, s.deviceId]);
      }
    });
    await audit(s, 'backup.restore', { id: b.id });
    return { ok: true, records: parsed.records.length };
  });

  app.get('/audit', { preHandler: [signedIn, ownerOnly] }, async (req) => {
    return (await db.query('SELECT action, details, user_id, device_id, at FROM audit_log WHERE farm_id = $1 ORDER BY id DESC LIMIT 200',
      [req.session.farmId])).rows;
  });

  app.get('/entities', { preHandler: signedIn }, async (req) => ({
    entities: Object.keys(ENTITIES).filter((e) => canRead(req.session.role, e)),
  }));

  return app;
}
