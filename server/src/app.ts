import Fastify, { type FastifyInstance, type FastifyReply, type FastifyRequest } from 'fastify';
import { randomInt } from 'node:crypto';
import { mkdirSync, writeFileSync, readFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import type { Db } from './db.ts';
import { inTransaction } from './db.ts';
import { hashPassword, verifyPassword, newToken, sha256, sessionOf, validPassword, normalEmail, normalLogin, type Session } from './auth.ts';
import { ROLES, ENTITIES, canRead, type Role } from './entities.ts';
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
  /** Anyone may open a new farm (`POST /auth/register`). Closed by default: the owner's farm already exists. */
  registrationOpen?: boolean;
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

/** A small per-address limit for the sign-in endpoints. */
function limiter(perMinute: number) {
  const hits = new Map<string, { n: number; until: number }>();
  return (key: string) => {
    const now = Date.now();
    const h = hits.get(key);
    if (!h || h.until < now) { hits.set(key, { n: 1, until: now + 60_000 }); return true; }
    h.n += 1;
    return h.n <= perMinute;
  };
}

export function buildApp(o: AppOptions): FastifyInstance {
  const { db } = o;
  const log = o.log ?? ((l: string) => console.log(l));
  const app = Fastify({ logger: false, bodyLimit: 20 * 1024 * 1024 });
  const allow = limiter(o.authAttemptsPerMinute ?? 20);
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

  async function issueDevice(tx: { query: Db['query'] }, farmId: string, userId: string, name: unknown, platform: unknown) {
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

  app.get('/auth/config', async () => ({ registrationOpen: o.registrationOpen === true }));

  /** A new account and its farm; the device is signed in. */
  app.post('/auth/register', async (req, reply) => {
    if (o.registrationOpen !== true) return fail(reply, 403, 'Регистрация закрыта');
    if (!allow(`auth:${req.ip}`)) return fail(reply, 429, 'Слишком много попыток, подождите минуту');
    const b = (req.body ?? {}) as Record<string, unknown>;
    const email = normalEmail(b.email);
    if (!email) return fail(reply, 400, 'Укажите почту');
    if (!validPassword(b.password)) return fail(reply, 400, 'Пароль — не короче 8 символов');
    const farmName = String(b.farmName ?? '').trim() || 'КФХ';
    const hash = await hashPassword(b.password);
    try {
      const out = await inTransaction(db, async (tx) => {
        const u = await tx.query('INSERT INTO users (email, name, password_hash) VALUES ($1, $2, $3) RETURNING id',
          [email, String(b.name ?? '').trim(), hash]);
        const f = await tx.query(
          `INSERT INTO farms (name, manager_name, region, timezone) VALUES ($1, $2, COALESCE($3, 'Приаргунский район, Забайкальский край, Россия'),
           COALESCE($4, 'Asia/Chita')) RETURNING id`,
          [farmName, String(b.name ?? '').trim(), typeof b.region === 'string' && b.region.trim() ? b.region.trim() : null,
           typeof b.timezone === 'string' && b.timezone ? b.timezone : null]);
        await tx.query("INSERT INTO memberships (farm_id, user_id, role) VALUES ($1, $2, 'owner')", [f.rows[0].id, u.rows[0].id]);
        const d = await issueDevice(tx, f.rows[0].id, u.rows[0].id, b.deviceName, b.platform);
        return { token: d.token, deviceId: d.deviceId, farmId: f.rows[0].id, userId: u.rows[0].id, role: 'owner' };
      });
      await audit({ farmId: out.farmId, userId: out.userId, deviceId: out.deviceId, role: 'owner' }, 'auth.register');
      return reply.code(201).send(out);
    } catch (e: unknown) {
      if ((e as { code?: string }).code === '23505') return fail(reply, 409, 'Эта почта уже зарегистрирована');
      throw e;
    }
  });

  /** Signs a device in to one of the user's farms (the only one, or `farmId`). */
  app.post('/auth/login', async (req, reply) => {
    if (!allow(`auth:${req.ip}`)) return fail(reply, 429, 'Слишком много попыток, подождите минуту');
    const b = (req.body ?? {}) as Record<string, unknown>;
    // `login` (or the older `email`) is an e-mail first, otherwise a helper's login.
    const who = typeof b.login === 'string' ? b.login : b.email;
    const email = normalEmail(who);
    const login = email ? null : normalLogin(who);
    const u = email ? (await db.query('SELECT id, password_hash FROM users WHERE email = $1', [email])).rows[0]
            : login ? (await db.query('SELECT id, password_hash FROM users WHERE login = $1', [login])).rows[0] : undefined;
    if (!u || typeof b.password !== 'string' || !(await verifyPassword(b.password, u.password_hash))) {
      await audit(null, 'auth.login.failed', { email, login });
      return fail(reply, 401, 'Неверный логин или пароль');
    }
    const farms = (await db.query('SELECT farm_id, role FROM memberships WHERE user_id = $1 ORDER BY created_at', [u.id])).rows;
    const m = typeof b.farmId === 'string' ? farms.find((f) => f.farm_id === b.farmId) : farms[0];
    if (!m) return fail(reply, 403, 'Нет доступа к хозяйству');
    const d = await issueDevice(db, m.farm_id, u.id, b.deviceName, b.platform);
    await audit({ farmId: m.farm_id, userId: u.id, deviceId: d.deviceId, role: m.role }, 'auth.login');
    return { token: d.token, deviceId: d.deviceId, farmId: m.farm_id, userId: u.id, role: m.role,
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
    const farm = (await db.query('SELECT id, name, region, manager_name, timezone FROM farms WHERE id = $1', [s.farmId])).rows[0];
    return { userId: s.userId, deviceId: s.deviceId, role: s.role,
             farm: { id: farm.id, name: farm.name, region: farm.region, managerName: farm.manager_name, timezone: farm.timezone } };
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
    if (set.length) await db.query(`UPDATE farms SET ${set.join(', ')} WHERE id = $1`, values);
    await audit(req.session, 'farm.update', b);
    return { ok: true };
  });

  /** The owner creates a helper: a login (no e-mail), a password and a role. */
  app.post('/members', { preHandler: [signedIn, ownerOnly] }, async (req, reply) => {
    const b = (req.body ?? {}) as Record<string, unknown>;
    const login = normalLogin(b.login);
    const role = b.role as Role;
    if (!login) return fail(reply, 400, 'Логин — от 3 до 32 латинских букв, цифр, «_» и «-»');
    if (!validPassword(b.password)) return fail(reply, 400, 'Пароль — не короче 8 символов');
    if (!ROLES.includes(role) || role === 'owner') return fail(reply, 400, 'Роль: manager, timesheet, coal, livestock или viewer');
    const hash = await hashPassword(b.password);
    try {
      const id = await inTransaction(db, async (tx) => {
        const u = await tx.query('INSERT INTO users (login, name, password_hash) VALUES ($1, $2, $3) RETURNING id', [login, login, hash]);
        await tx.query('INSERT INTO memberships (farm_id, user_id, role) VALUES ($1, $2, $3)', [req.session.farmId, u.rows[0].id, role]);
        return u.rows[0].id as string;
      });
      await audit(req.session, 'member.create', { userId: id, login, role });
      return reply.code(201).send({ id, login, role });
    } catch (e: unknown) {
      if ((e as { code?: string }).code === '23505') return fail(reply, 409, 'Такой логин уже есть');
      throw e;
    }
  });

  /** The owner sets a new password for a helper; all the helper's devices of this farm are signed out. */
  app.post('/members/:userId/password', { preHandler: [signedIn, ownerOnly] }, async (req, reply) => {
    const { userId } = req.params as { userId: string };
    const password = (req.body as Record<string, unknown> | undefined)?.password;
    if (!validPassword(password)) return fail(reply, 400, 'Пароль — не короче 8 символов');
    if (userId === req.session.userId) return fail(reply, 400, 'Свой пароль владелец меняет через восстановление доступа');
    const hash = await hashPassword(password);
    const found = await inTransaction(db, async (tx) => {
      // Only a login-only helper of this farm: an account with an e-mail or another farm has a password of its own.
      const m = await tx.query(
        `SELECT 1 FROM memberships m JOIN users u ON u.id = m.user_id
          WHERE m.farm_id = $1 AND m.user_id = $2 AND u.email IS NULL
            AND NOT EXISTS (SELECT 1 FROM memberships o WHERE o.user_id = m.user_id AND o.farm_id <> m.farm_id)`,
        [req.session.farmId, userId]);
      if (!m.rowCount) return false;
      await tx.query('UPDATE users SET password_hash = $2 WHERE id = $1', [userId, hash]);
      await tx.query('UPDATE devices SET revoked_at = now() WHERE user_id = $1 AND revoked_at IS NULL', [userId]);
      return true;
    });
    if (!found) return fail(reply, 404, 'Помощник не найден');
    await audit(req.session, 'member.password', { userId });
    return { ok: true };
  });

  app.get('/members', { preHandler: signedIn }, async (req) => {
    return (await db.query(
      `SELECT u.id, u.login, u.email, u.name, m.role FROM memberships m JOIN users u ON u.id = m.user_id WHERE m.farm_id = $1 ORDER BY m.created_at`,
      [req.session.farmId])).rows;
  });

  app.patch('/members/:userId', { preHandler: [signedIn, ownerOnly] }, async (req, reply) => {
    const role = (req.body as Record<string, unknown> | undefined)?.role as Role;
    const { userId } = req.params as { userId: string };
    if (!ROLES.includes(role) || role === 'owner' || userId === req.session.userId) return fail(reply, 400, 'Неверная роль');
    // The helper signs in again to get the new rights.
    await inTransaction(db, async (tx) => {
      await tx.query('UPDATE memberships SET role = $3 WHERE farm_id = $1 AND user_id = $2', [req.session.farmId, userId, role]);
      await tx.query('UPDATE devices SET revoked_at = now() WHERE farm_id = $1 AND user_id = $2 AND revoked_at IS NULL', [req.session.farmId, userId]);
    });
    await audit(req.session, 'member.role', { userId, role });
    return { ok: true };
  });

  /** Removes a helper from the farm and signs all their devices of this farm out. */
  app.delete('/members/:userId', { preHandler: [signedIn, ownerOnly] }, async (req, reply) => {
    const { userId } = req.params as { userId: string };
    if (userId === req.session.userId) return fail(reply, 400, 'Владельца удалить нельзя');
    await db.query('DELETE FROM memberships WHERE farm_id = $1 AND user_id = $2', [req.session.farmId, userId]);
    await db.query('UPDATE devices SET revoked_at = now() WHERE farm_id = $1 AND user_id = $2 AND revoked_at IS NULL', [req.session.farmId, userId]);
    await audit(req.session, 'member.remove', { userId });
    return { ok: true };
  });

  app.get('/devices', { preHandler: signedIn }, async (req) => {
    const s = req.session;
    const rows = (await db.query(
      `SELECT d.id, d.name, d.platform, d.created_at, d.last_seen_at, d.revoked_at, u.email, u.login
         FROM devices d JOIN users u ON u.id = d.user_id
        WHERE d.farm_id = $1 AND ($2 OR d.user_id = $3) ORDER BY d.created_at`,
      [s.farmId, s.role === 'owner', s.userId])).rows;
    return rows.map((d) => ({ id: d.id, name: d.name, platform: d.platform, email: d.email, login: d.login, createdAt: d.created_at,
                              lastSeenAt: d.last_seen_at, revoked: !!d.revoked_at, current: d.id === s.deviceId }));
  });

  /** «Отозвать доступ» for a lost device: the owner for any device of the farm, a member for their own. */
  app.post('/devices/:id/revoke', { preHandler: signedIn }, async (req, reply) => {
    const s = req.session;
    const { id } = req.params as { id: string };
    const r = await db.query(
      `UPDATE devices SET revoked_at = now() WHERE id = $1 AND farm_id = $2 AND revoked_at IS NULL AND ($3 OR user_id = $4)`,
      [id, s.farmId, s.role === 'owner', s.userId]);
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

  app.post('/conflicts/:id/resolve', { preHandler: signedIn }, async (req, reply) => {
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
