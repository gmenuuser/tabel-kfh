import { test } from 'node:test';
import assert from 'node:assert/strict';
import { harness, owner as seedFarm, auth } from './helpers.ts';
import { createKey, redeemKey, normalCode, codeHash } from '../src/keys.ts';

// Task 1: the schema of access keys, and a person with no e-mail and no password.
// Task 2: the code itself — four digits, hashed, one-time, with an expiry.

test('схема: ключ и человек без почты и логина', async () => {
  const h = await harness();
  try {
    const farm = await seedFarm(h.app, h.db);

    // A person the admin paired earlier: a name, no e-mail, no login, an empty password hash.
    const u = await h.db.query("INSERT INTO users (name, password_hash) VALUES ('Иван', '') RETURNING id");
    await h.db.query('INSERT INTO memberships (farm_id, user_id, role) VALUES ($1, $2, $3)',
      [farm.farmId, u.rows[0].id, 'timesheet']);

    const k = await h.db.query(
      `INSERT INTO access_keys (farm_id, person_name, role, code_hash, expires_at)
       VALUES ($1, 'Пётр', 'coal', 'hash', now() + interval '1 day') RETURNING id`, [farm.farmId]);
    assert.ok(k.rows[0].id);

    // The owner role is never handed out by a key.
    await assert.rejects(
      h.db.query(`INSERT INTO access_keys (farm_id, person_name, role, code_hash, expires_at)
                  VALUES ($1, 'X', 'owner', 'h2', now() + interval '1 day')`, [farm.farmId]));
  } finally {
    await h.close();
  }
});

test('код: четыре цифры, свой у каждого ключа, в базе только хеш', async () => {
  const h = await harness();
  try {
    const farm = await seedFarm(h.app, h.db);
    const a = await createKey(h.db, farm.farmId, farm.userId, { personName: 'Иван', role: 'timesheet', days: 1 });
    const b = await createKey(h.db, farm.farmId, farm.userId, { personName: 'Пётр', role: 'coal', days: 1 });
    assert.match(a.code, /^\d{4}$/);
    assert.notEqual(a.code, b.code);

    assert.equal(normalCode('1234'), '1234');
    assert.equal(normalCode('12'), null);
    assert.equal(normalCode('abcd'), null);
    assert.equal(normalCode(1234), null);

    const stored = (await h.db.query('SELECT code_hash FROM access_keys WHERE id = $1', [a.id])).rows[0].code_hash;
    assert.equal(stored, codeHash(farm.farmId, a.code));
    assert.notEqual(stored, a.code);
  } finally {
    await h.close();
  }
});

test('код: одноразовый, просроченный и чужой различаются', async () => {
  const h = await harness();
  try {
    const farm = await seedFarm(h.app, h.db);
    const key = await createKey(h.db, farm.farmId, farm.userId, { personName: 'Иван', role: 'coal', days: 1 });

    const first = await redeemKey(h.db, farm.farmId, key.code);
    assert.equal(first.ok, true);
    if (first.ok) {
      assert.equal(first.key.role, 'coal');
      assert.equal(first.key.personName, 'Иван');
      assert.equal(first.key.userId, null);
    }

    assert.deepEqual(await redeemKey(h.db, farm.farmId, key.code), { ok: false, reason: 'used' });

    await h.db.query(`INSERT INTO access_keys (farm_id, person_name, role, code_hash, expires_at)
                      VALUES ($1, 'Старый', 'coal', $2, now() - interval '1 hour')`,
      [farm.farmId, codeHash(farm.farmId, '9876')]);
    assert.deepEqual(await redeemKey(h.db, farm.farmId, '9876'), { ok: false, reason: 'expired' });

    const wrongCode = key.code === '0000' ? '0001' : '0000';
    assert.deepEqual(await redeemKey(h.db, farm.farmId, wrongCode), { ok: false, reason: 'wrong' });
  } finally {
    await h.close();
  }
});

// Task 3: pairing a phone with a code instead of open self-registration.

const pair = (h: { app: { inject: Function } }, payload: Record<string, unknown>, ip = '10.3.0.1') =>
  h.app.inject({ method: 'POST', url: '/devices/register', remoteAddress: ip,
                payload: { platform: 'android', name: 'Honor', ...payload } });
const bearer = (token: string) => ({ authorization: `Bearer ${token}` });

test('подключение по коду заводит человека, членство и устройство', async () => {
  const h = await harness({ keysRequired: true });
  try {
    const farm = await seedFarm(h.app, h.db);
    const key = await createKey(h.db, farm.farmId, farm.userId, { personName: 'Иван', role: 'timesheet', days: 1 });

    const r = await pair(h, { code: key.code });
    assert.equal(r.statusCode, 201);
    const d = r.json();
    assert.equal(d.role, 'timesheet');
    assert.equal(d.kind, 'app');
    assert.equal(d.farmId, farm.farmId);

    const row = (await h.db.query(
      `SELECT u.name, m.role FROM devices d JOIN users u ON u.id = d.user_id
        JOIN memberships m ON m.user_id = u.id WHERE d.id = $1`, [d.deviceId])).rows[0];
    assert.deepEqual(row, { name: 'Иван', role: 'timesheet' });

    // A phone is an app, even when its role is a real one; the farm's switch stays owner-only.
    const me = (await h.app.inject({ method: 'GET', url: '/me', headers: bearer(d.token) })).json();
    assert.equal(me.kind, 'app');
    assert.equal(me.role, 'timesheet');
    assert.equal(me.farm.newDevicesAllowed, undefined);

    const auditRow = (await h.db.query("SELECT action, details FROM audit_log WHERE action = 'device.pair'")).rows[0];
    assert.equal(auditRow.details.personName, 'Иван');
  } finally {
    await h.close();
  }
});

test('один код — одно устройство: второй запрос отвечает «Код уже использован»', async () => {
  const h = await harness({ keysRequired: true });
  try {
    const farm = await seedFarm(h.app, h.db);
    const key = await createKey(h.db, farm.farmId, farm.userId, { personName: 'Иван', role: 'coal', days: 1 });
    assert.equal((await pair(h, { code: key.code }, '10.3.1.1')).statusCode, 201);
    const again = await pair(h, { code: key.code }, '10.3.1.2');
    assert.equal(again.statusCode, 403);
    assert.equal(again.json().error, 'Код уже использован — попросите новый');
    const devices = (await h.db.query("SELECT count(*)::int AS n FROM devices WHERE platform = 'android'")).rows[0].n;
    assert.equal(devices, 1);
    const people = (await h.db.query('SELECT count(*)::int AS n FROM users')).rows[0].n;
    assert.equal(people, 2); // the owner and Иван
  } finally {
    await h.close();
  }
});

test('без кода при KEYS_REQUIRED — 400, мусор — 400, чужой код — 403', async () => {
  const h = await harness({ keysRequired: true });
  try {
    await seedFarm(h.app, h.db);
    const noCode = await pair(h, {});
    assert.equal(noCode.statusCode, 400);
    assert.equal(noCode.json().error, 'Укажите 4-значный код');
    assert.equal((await pair(h, { code: '12' }, '10.3.2.1')).statusCode, 400);
    assert.equal((await pair(h, { code: 'abcd' }, '10.3.2.2')).statusCode, 400);
    const wrong = await pair(h, { code: '0000' }, '10.3.2.3');
    assert.equal(wrong.statusCode, 403);
    assert.equal(wrong.json().error, 'Код неверный');
  } finally {
    await h.close();
  }
});

test('KEYS_REQUIRED=false: работает и старый путь без кода, и код', async () => {
  const h = await harness();
  try {
    const farm = await seedFarm(h.app, h.db);
    const open = await pair(h, {}, '10.3.3.1');
    assert.equal(open.statusCode, 201);
    assert.equal(open.json().kind, 'app');
    assert.equal(open.json().role, 'manager');

    const key = await createKey(h.db, farm.farmId, farm.userId, { personName: 'Пётр', role: 'livestock', days: 1 });
    const byCode = await pair(h, { code: key.code }, '10.3.3.2');
    assert.equal(byCode.statusCode, 201);
    assert.equal(byCode.json().role, 'livestock');
  } finally {
    await h.close();
  }
});

test('лимит: 21-я попытка с одного IP — 429', async () => {
  const h = await harness({ keysRequired: true });
  try {
    await seedFarm(h.app, h.db);
    for (let i = 0; i < 20; i += 1) {
      assert.equal((await pair(h, { code: '0000' }, '10.3.4.7')).statusCode, 403, `попытка ${i + 1}`);
    }
    assert.equal((await pair(h, { code: '0001' }, '10.3.4.7')).statusCode, 429);
  } finally {
    await h.close();
  }
});

test('лимит: 201 неверная попытка за час останавливает хозяйство на 30 минут', async () => {
  const h = await harness({ keysRequired: true });
  try {
    const farm = await seedFarm(h.app, h.db);
    for (let i = 0; i < 200; i += 1) {
      const r = await pair(h, { code: '0000' }, `10.5.${Math.floor(i / 250)}.${(i % 250) + 1}`);
      assert.equal(r.statusCode, 403, `попытка ${i + 1}`);
    }
    const pause = await pair(h, { code: '0000' }, '10.6.0.1');
    assert.equal(pause.statusCode, 429);

    const key = await createKey(h.db, farm.farmId, farm.userId, { personName: 'Иван', role: 'coal', days: 1 });
    assert.equal((await pair(h, { code: key.code }, '10.6.0.2')).statusCode, 429);

    const logged = (await h.db.query("SELECT count(*)::int AS n FROM audit_log WHERE action = 'keys.bruteforce'")).rows[0].n;
    assert.equal(logged, 1);
  } finally {
    await h.close();
  }
});
