import Fastify, { type FastifyInstance, type FastifyReply, type FastifyRequest } from 'fastify';
import { randomInt } from 'node:crypto';
import { mkdirSync, writeFileSync, readFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import type { Db } from './db.ts';
import { inTransaction } from './db.ts';
import { hashPassword, verifyPassword, newToken, sha256, sessionOf, validPassword, normalEmail, type Session } from './auth.ts';
import { ENTITIES, canRead } from './entities.ts';
import { push, pull, openConflicts, resolveConflict, type PushOp } from './sync.ts';

export interface AppOptions {
  db: Db;
  /** Where uploaded files and backups are kept. */
  dataDir: string;
  /** Reset codes are written here instead of e-mailed (no mail server yet). */
  log?: (line: string) => void;
  /** Sign-in attempts per address and minute (20). */
  authAttemptsPerMinute?: number;
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
    const isApp = s.role === 'app';
    return { userId: s.userId, deviceId: s.deviceId,
             // Old app builds know no «app» role: they are told «manager».
             role: isApp ? 'manager' : s.role, kind: isApp ? 'app' : 'owner',
             farm: { id: farm.id, name: farm.name, region: farm.region, managerName: farm.manager_name, timezone: farm.timezone,
                     ...(s.role === 'owner' ? { newDevicesAllowed: farm.new_devices_allowed } : {}) } };
  });

  /** A phone connects itself: no account, no password. The owner can close this with `newDevicesAllowed`. */
  app.post('/devices/register', async (req, reply) => {
    if (!allowRegister(`register:${req.ip}`)) return fail(reply, 429, 'Слишком много попыток, попробуйте позже');
    const b = (req.body ?? {}) as Record<string, unknown>;
    if (b.platform !== 'ios' && b.platform !== 'android') return fail(reply, 400, 'Платформа: ios или android');
    // One farm: the oldest one.
    const farm = (await db.query('SELECT id, new_devices_allowed FROM farms ORDER BY created_at LIMIT 1')).rows[0];
    if (!farm) return fail(reply, 503, 'Хозяйство ещё не создано');
    if (!farm.new_devices_allowed) return fail(reply, 403, 'Владелец сейчас не подключает новые устройства');
    const d = await issueDevice(db, farm.id, null, b.name, b.platform);
    await audit({ farmId: farm.id, userId: null, deviceId: d.deviceId, role: 'app' }, 'device.register', { platform: b.platform });
    return reply.code(201).send({ token: d.token, deviceId: d.deviceId, farmId: farm.id, role: 'manager', kind: 'app' });
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

  /** Every device of the farm: phones (`app`, no account) and the owner's site sessions (`owner`). */
  app.get('/devices', { preHandler: [signedIn, ownerOnly] }, async (req) => {
    const s = req.session;
    const rows = (await db.query(
      `SELECT id, name, platform, user_id, created_at, last_seen_at, revoked_at FROM devices WHERE farm_id = $1 ORDER BY created_at`,
      [s.farmId])).rows;
    return rows.map((d) => ({ id: d.id, name: d.name, platform: d.platform, kind: d.user_id ? 'owner' : 'app', createdAt: d.created_at,
                              lastSeenAt: d.last_seen_at, revoked: !!d.revoked_at, current: d.id === s.deviceId }));
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
