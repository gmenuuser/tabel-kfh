import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { harness, owner as seedFarm, auth, pushOps, pullAll, upsert, randomUUID, type Device, type Harness } from './helpers.ts';
import { sha256, newToken } from '../src/auth.ts';

const SQL_003 = join(import.meta.dirname, '..', 'sql', '003_open_apps.sql');

function registerApp(h: Harness, extra: Record<string, unknown> = {}, remoteAddress = '10.0.0.1') {
  return h.app.inject({ method: 'POST', url: '/devices/register', remoteAddress, payload: { platform: 'android', name: 'Honor', ...extra } });
}

const asDevice = (d: { token: string; farmId: string; deviceId: string }): Device =>
  ({ token: d.token, farmId: d.farmId, userId: '', deviceId: d.deviceId, role: 'manager' });

test('регистрация устройства без входа', async () => {
  const h = await harness();
  try {
    await seedFarm(h.app, h.db); // the farm and its owner
    const r = await registerApp(h);
    assert.equal(r.statusCode, 201);
    const d = r.json();
    assert.equal(d.kind, 'app');
    assert.equal(d.role, 'manager');
    const dev = asDevice(d);
    const me = (await h.app.inject({ method: 'GET', url: '/me', headers: auth(dev) })).json();
    assert.equal(me.kind, 'app');
    assert.equal(me.role, 'manager');
    assert.equal(me.userId, null);
    await pullAll(h.app, dev);
    const mark = await pushOps(h.app, dev, [upsert('hoursMark', randomUUID(), { employeeId: randomUUID(), hours: 8 })]);
    assert.equal(mark[0].status, 'applied');
    const settings = await pushOps(h.app, dev, [upsert('coalSettings', randomUUID(), { x: 1 })]);
    assert.equal(settings[0].status, 'rejected');
    assert.equal((await registerApp(h, { platform: 'web' }, '10.0.0.2')).statusCode, 400);
    assert.equal((await registerApp(h, { name: 'x'.repeat(300) }, '10.0.0.3')).statusCode, 201);
    const longest = (await h.db.query('SELECT max(length(name))::int AS n FROM devices')).rows[0].n;
    assert.equal(longest, 100);
  } finally {
    await h.close();
  }
});

test('регистрация без хозяйства даёт 503', async () => {
  const h = await harness();
  try {
    const r = await registerApp(h);
    assert.equal(r.statusCode, 503);
    assert.equal(r.json().error, 'Хозяйство ещё не создано');
  } finally {
    await h.close();
  }
});

test('переключатель закрывает новые устройства', async () => {
  const h = await harness();
  try {
    const owner = await seedFarm(h.app, h.db);
    const before = (await registerApp(h)).json();
    const meOwner = (await h.app.inject({ method: 'GET', url: '/me', headers: auth(owner) })).json();
    assert.equal(meOwner.kind, 'owner');
    assert.equal(meOwner.farm.newDevicesAllowed, true);
    const off = await h.app.inject({ method: 'PATCH', url: '/farm', headers: auth(owner), payload: { newDevicesAllowed: false } });
    assert.equal(off.statusCode, 200);
    const closed = await registerApp(h);
    assert.equal(closed.statusCode, 403);
    assert.equal(closed.json().error, 'Владелец сейчас не подключает новые устройства');
    const old = asDevice(before);
    assert.equal((await h.app.inject({ method: 'GET', url: '/sync/pull?since=0', headers: auth(old) })).statusCode, 200);
    // an app device may not change the farm
    assert.equal((await h.app.inject({ method: 'PATCH', url: '/farm', headers: auth(old), payload: { newDevicesAllowed: true } })).statusCode, 403);
  } finally {
    await h.close();
  }
});

test('не больше 5 регистраций в час с адреса', async () => {
  const h = await harness();
  try {
    await seedFarm(h.app, h.db);
    const codes: number[] = [];
    for (let i = 0; i < 6; i += 1) codes.push((await registerApp(h, {}, '7.7.7.7')).statusCode);
    assert.deepEqual(codes, [201, 201, 201, 201, 201, 429]);
    assert.equal((await registerApp(h, {}, '7.7.7.8')).statusCode, 201);
  } finally {
    await h.close();
  }
});

test('миграция 003 переводит устройства', async () => {
  const h = await harness();
  try {
    const sql = readFileSync(SQL_003, 'utf8');
    const q = async (text: string, params: unknown[] = []) => (await h.db.query(text, params)).rows;
    const farm = (await q("INSERT INTO farms (name) VALUES ('КФХ') RETURNING id"))[0].id;
    const owner = (await q("INSERT INTO users (email, name, password_hash) VALUES ('o@example.com', 'Иван', 'x') RETURNING id"))[0].id;
    const helper = (await q("INSERT INTO users (login, name, password_hash) VALUES ('petrov', 'Петров', 'x') RETURNING id"))[0].id;
    await q("INSERT INTO memberships (farm_id, user_id, role) VALUES ($1, $2, 'owner'), ($1, $3, 'manager')", [farm, owner, helper]);
    const dev = async (user: string, platform: string, name: string) => {
      const token = newToken();
      const id = (await q('INSERT INTO devices (farm_id, user_id, name, platform, token_hash) VALUES ($1, $2, $3, $4, $5) RETURNING id',
        [farm, user, name, platform, sha256(token)]))[0].id;
      return { id, token };
    };
    const helperIos = await dev(helper, 'ios', 'iPhone');
    const helperWeb = await dev(helper, 'web', 'Браузер');
    const helperTest = await dev(helper, 'test', 'Тест');
    const helperBlank = await dev(helper, '', 'Без платформы');
    const ownerBlank = await dev(owner, '', 'Старое устройство владельца');
    const ownerIos = await dev(owner, 'ios', 'iPhone владельца');
    const ownerWeb = await dev(owner, 'web', 'Сайт владельца');
    const rec = randomUUID();
    await q(`INSERT INTO records (farm_id, entity, id, version, data, field_versions, created_by_device, seq)
             VALUES ($1, 'employee', $2, 1, '{"name":"А"}', '{"name":1}', $3, 1)`, [farm, rec, helperIos.id]);
    await q("INSERT INTO invites (code, farm_id, role, created_by, expires_at) VALUES ('c', $1, 'viewer', $2, now() + interval '1 day')", [farm, owner]);

    const snapshot = async () => JSON.stringify([
      await q('SELECT id, name, user_id, revoked_at FROM devices ORDER BY id'),
      await q('SELECT id, email FROM users ORDER BY id'),
      await q('SELECT entity, id, created_by_device FROM records'),
      await q('SELECT count(*)::int AS n FROM invites'),
    ]);
    await h.db.query(sql);
    const dr = async (id: string) => (await q('SELECT name, user_id, revoked_at FROM devices WHERE id = $1', [id]))[0];
    assert.equal((await dr(helperIos.id)).name, 'iPhone — Петров');
    assert.equal((await dr(helperIos.id)).user_id, null);
    assert.equal((await dr(helperIos.id)).revoked_at, null);
    assert.notEqual((await dr(helperWeb.id)).revoked_at, null);
    // only phones become app devices: a helper's other sessions end, the owner's stay
    assert.notEqual((await dr(helperTest.id)).revoked_at, null);
    assert.notEqual((await dr(helperBlank.id)).revoked_at, null);
    assert.equal((await dr(ownerBlank.id)).revoked_at, null);
    assert.equal((await dr(ownerIos.id)).user_id, null);
    assert.equal((await dr(ownerIos.id)).name, 'iPhone владельца');
    assert.equal((await dr(ownerWeb.id)).revoked_at, null);
    assert.equal((await q('SELECT 1 FROM users WHERE id = $1', [helper])).length, 0);
    assert.equal((await q('SELECT 1 FROM users WHERE id = $1', [owner])).length, 1);
    assert.equal((await q('SELECT count(*)::int AS n FROM invites'))[0].n, 0);
    assert.equal((await q('SELECT created_by_device FROM records WHERE id = $1', [rec]))[0].created_by_device, helperIos.id);
    const app = { token: helperIos.token, farmId: farm, userId: '', deviceId: helperIos.id, role: 'manager' };
    assert.equal((await h.app.inject({ method: 'GET', url: '/sync/pull?since=0', headers: auth(app) })).statusCode, 200);

    const after = await snapshot();
    await h.db.query(sql);
    assert.equal(await snapshot(), after);
  } finally {
    await h.close();
  }
});
