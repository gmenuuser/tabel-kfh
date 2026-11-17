import { test } from 'node:test';
import assert from 'node:assert/strict';
import { harness, owner as seedFarm, auth, pushOps, upsert, randomUUID } from './helpers.ts';
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

// Task 4: the admin's keys and people.

const ownerHeaders = (farm: { token: string }) => bearer(farm.token);

test('владелец заводит ключ и видит его в списке без самого кода', async () => {
  const h = await harness({ keysRequired: true });
  try {
    const farm = await seedFarm(h.app, h.db);
    const created = await h.app.inject({ method: 'POST', url: '/keys', headers: ownerHeaders(farm),
      payload: { personName: 'Иван', role: 'timesheet', days: 7 } });
    assert.equal(created.statusCode, 201);
    const { id, code } = created.json();
    assert.match(code, /^\d{4}$/);

    const list = (await h.app.inject({ method: 'GET', url: '/keys', headers: ownerHeaders(farm) })).json().keys;
    assert.equal(list.length, 1);
    assert.equal(list[0].id, id);
    assert.equal(list[0].personName, 'Иван');
    assert.equal(list[0].role, 'timesheet');
    assert.equal(list[0].status, 'free');
    assert.equal(JSON.stringify(list).includes(code), false, 'сам код в списке не показывается');

    // Only the owner may look.
    const key = await createKey(h.db, farm.farmId, farm.userId, { personName: 'Пётр', role: 'coal', days: 1 });
    const phone = (await pair(h, { code: key.code }, '10.7.0.1')).json();
    assert.equal((await h.app.inject({ method: 'GET', url: '/keys', headers: bearer(phone.token) })).statusCode, 403);
  } finally {
    await h.close();
  }
});

test('второй код существующему человеку не заводит второго человека', async () => {
  const h = await harness({ keysRequired: true });
  try {
    const farm = await seedFarm(h.app, h.db);
    const first = (await h.app.inject({ method: 'POST', url: '/keys', headers: ownerHeaders(farm),
      payload: { personName: 'Иван', role: 'coal', days: 7 } })).json();
    const phone = (await pair(h, { code: first.code }, '10.7.1.1')).json();

    const people = (await h.app.inject({ method: 'GET', url: '/people', headers: ownerHeaders(farm) })).json().people;
    assert.equal(people.length, 1);
    assert.equal(people[0].name, 'Иван');
    assert.equal(people[0].devices, 1);

    const second = await h.app.inject({ method: 'POST', url: `/people/${people[0].id}/keys`, headers: ownerHeaders(farm),
      payload: { days: 7 } });
    assert.equal(second.statusCode, 201);
    const tablet = (await pair(h, { code: second.json().code }, '10.7.1.2')).json();
    assert.equal(tablet.role, 'coal');

    const after = (await h.app.inject({ method: 'GET', url: '/people', headers: ownerHeaders(farm) })).json().people;
    assert.equal(after.length, 1);
    assert.equal(after[0].devices, 2);
    // The first phone is still connected.
    assert.equal((await h.app.inject({ method: 'GET', url: '/me', headers: bearer(phone.token) })).statusCode, 200);
  } finally {
    await h.close();
  }
});

test('новый код взамен утерянного гасит прежние устройства', async () => {
  const h = await harness({ keysRequired: true });
  try {
    const farm = await seedFarm(h.app, h.db);
    const key = (await h.app.inject({ method: 'POST', url: '/keys', headers: ownerHeaders(farm),
      payload: { personName: 'Иван', role: 'coal', days: 7 } })).json();
    const phone = (await pair(h, { code: key.code }, '10.7.2.1')).json();
    assert.equal((await h.app.inject({ method: 'GET', url: '/me', headers: bearer(phone.token) })).statusCode, 200);

    const re = await h.app.inject({ method: 'POST', url: `/keys/${key.id}/reissue`, headers: ownerHeaders(farm),
      payload: { days: 7 } });
    assert.equal(re.statusCode, 201);
    assert.match(re.json().code, /^\d{4}$/);
    // The lost phone is out; the new code works.
    assert.equal((await h.app.inject({ method: 'GET', url: '/me', headers: bearer(phone.token) })).statusCode, 401);
    assert.equal((await pair(h, { code: re.json().code }, '10.7.2.2')).statusCode, 201);
  } finally {
    await h.close();
  }
});

test('отзыв гасит устройства и закрывает все ключи человека', async () => {
  const h = await harness({ keysRequired: true });
  try {
    const farm = await seedFarm(h.app, h.db);
    const key = (await h.app.inject({ method: 'POST', url: '/keys', headers: ownerHeaders(farm),
      payload: { personName: 'Иван', role: 'coal', days: 7 } })).json();
    const phone = (await pair(h, { code: key.code }, '10.7.3.1')).json();
    const free = (await h.app.inject({ method: 'POST', url: `/people/${(await h.db.query('SELECT user_id FROM access_keys WHERE id = $1', [key.id])).rows[0].user_id}/keys`,
      headers: ownerHeaders(farm), payload: { days: 7 } })).json();

    const revoked = await h.app.inject({ method: 'POST', url: `/keys/${key.id}/revoke`, headers: ownerHeaders(farm) });
    assert.equal(revoked.statusCode, 200);
    assert.equal((await h.app.inject({ method: 'GET', url: '/me', headers: bearer(phone.token) })).statusCode, 401);
    // A key that was never used dies with the person too.
    assert.equal((await pair(h, { code: free.code }, '10.7.3.2')).statusCode, 403);

    const list = (await h.app.inject({ method: 'GET', url: '/keys', headers: ownerHeaders(farm) })).json().keys;
    assert.equal(list.every((k: { status: string }) => k.status === 'revoked'), true);
  } finally {
    await h.close();
  }
});

test('удаление убирает ключ из списка', async () => {
  const h = await harness({ keysRequired: true });
  try {
    const farm = await seedFarm(h.app, h.db);
    const key = (await h.app.inject({ method: 'POST', url: '/keys', headers: ownerHeaders(farm),
      payload: { personName: 'Иван', role: 'coal', days: 7 } })).json();
    const removed = await h.app.inject({ method: 'DELETE', url: `/keys/${key.id}`, headers: ownerHeaders(farm) });
    assert.equal(removed.statusCode, 200);
    const list = (await h.app.inject({ method: 'GET', url: '/keys', headers: ownerHeaders(farm) })).json().keys;
    assert.equal(list.length, 0);
    // The code is dead too.
    assert.equal((await pair(h, { code: key.code }, '10.7.5.1')).statusCode, 403);
  } finally {
    await h.close();
  }
});

test('безымянные устройства отключаются отдельной кнопкой, сессия владельца жива', async () => {
  const h = await harness();
  try {
    const farm = await seedFarm(h.app, h.db);      // the owner's site session is a device too
    await pair(h, {}, '10.8.0.1');                 // two anonymous phones
    await pair(h, {}, '10.8.0.2');

    const unconfirmed = await h.app.inject({ method: 'POST', url: '/devices/revoke-anonymous',
      headers: ownerHeaders(farm), payload: {} });
    assert.equal(unconfirmed.statusCode, 400);

    const r = await h.app.inject({ method: 'POST', url: '/devices/revoke-anonymous',
      headers: ownerHeaders(farm), payload: { confirm: true } });
    assert.equal(r.statusCode, 200);
    assert.equal(r.json().revoked, 2);
    assert.equal((await h.app.inject({ method: 'GET', url: '/me', headers: ownerHeaders(farm) })).statusCode, 200);
  } finally {
    await h.close();
  }
});

test('в списке устройств видно, чей это телефон', async () => {
  const h = await harness({ keysRequired: true });
  try {
    const farm = await seedFarm(h.app, h.db);
    const key = (await h.app.inject({ method: 'POST', url: '/keys', headers: ownerHeaders(farm),
      payload: { personName: 'Иван', role: 'coal', days: 7 } })).json();
    await pair(h, { code: key.code }, '10.9.0.1');

    const devices = (await h.app.inject({ method: 'GET', url: '/devices', headers: ownerHeaders(farm) })).json();
    const phone = devices.find((d: { platform: string }) => d.platform === 'android');
    assert.equal(phone.personName, 'Иван');
    assert.equal(phone.role, 'coal');
    // A paired phone is an app device; its role is a real one, but the kind follows the platform.
    assert.equal(phone.kind, 'app');
  } finally {
    await h.close();
  }
});

test('новый код закрывает и невыкупленный прежний', async () => {
  const h = await harness({ keysRequired: true });
  try {
    const farm = await seedFarm(h.app, h.db);
    const key = (await h.app.inject({ method: 'POST', url: '/keys', headers: ownerHeaders(farm),
      payload: { personName: 'Иван', role: 'coal', days: 7 } })).json();
    // Nobody typed this code yet: it must die with the reissue, or the paper it is on still works.
    const re = await h.app.inject({ method: 'POST', url: `/keys/${key.id}/reissue`, headers: ownerHeaders(farm),
      payload: { days: 7 } });
    assert.equal(re.statusCode, 201);
    assert.equal((await pair(h, { code: key.code }, '10.10.0.1')).statusCode, 403);
    assert.equal((await pair(h, { code: re.json().code }, '10.10.0.2')).statusCode, 201);
  } finally {
    await h.close();
  }
});

test('невыкупленный код не возвращает роль, которую уже сузили', async () => {
  const h = await harness({ keysRequired: true });
  try {
    const farm = await seedFarm(h.app, h.db);
    const first = (await h.app.inject({ method: 'POST', url: '/keys', headers: ownerHeaders(farm),
      payload: { personName: 'Иван', role: 'manager', days: 7 } })).json();
    await pair(h, { code: first.code }, '10.10.1.1');

    const people = (await h.app.inject({ method: 'GET', url: '/people', headers: ownerHeaders(farm) })).json().people;
    // The second code is made while the role is still «всё», and typed only after the narrowing.
    const second = (await h.app.inject({ method: 'POST', url: `/people/${people[0].id}/keys`, headers: ownerHeaders(farm),
      payload: { days: 7 } })).json();
    const narrowed = await h.app.inject({ method: 'PATCH', url: `/people/${people[0].id}`, headers: ownerHeaders(farm),
      payload: { role: 'viewer' } });
    assert.equal(narrowed.statusCode, 200);

    const tablet = (await pair(h, { code: second.code }, '10.10.1.2')).json();
    assert.equal(tablet.role, 'viewer');
    const me = (await h.app.inject({ method: 'GET', url: '/me', headers: bearer(tablet.token) })).json();
    assert.equal(me.role, 'viewer');
  } finally {
    await h.close();
  }
});

test('телефон не меняет настройки хозяйства, даже с ролью «всё»', async () => {
  const h = await harness({ keysRequired: true });
  try {
    const farm = await seedFarm(h.app, h.db);
    const key = (await h.app.inject({ method: 'POST', url: '/keys', headers: ownerHeaders(farm),
      payload: { personName: 'Иван', role: 'manager', days: 7 } })).json();
    const d = (await pair(h, { code: key.code }, '10.10.2.1')).json();
    const phone = { token: d.token, farmId: d.farmId, userId: '', deviceId: d.deviceId, role: d.role };

    for (const entity of ['farmSettings', 'coalSettings', 'livestockSettings']) {
      const r = await pushOps(h.app, phone, [upsert(entity, randomUUID(), { any: 1 })]);
      assert.equal(r[0].status, 'rejected', entity);
    }
    // The work itself goes through.
    const mark = await pushOps(h.app, phone, [upsert('hoursMark', randomUUID(), { employeeId: randomUUID(), hours: 8 })]);
    assert.equal(mark[0].status, 'applied');
    // And the owner on the site keeps the right to change the settings.
    const own = await pushOps(h.app, farm, [upsert('coalSettings', randomUUID(), { price: 100 })]);
    assert.equal(own[0].status, 'applied');
  } finally {
    await h.close();
  }
});

test('после смены роли телефон пишет только в свой модуль', async () => {
  const h = await harness({ keysRequired: true });
  try {
    const farm = await seedFarm(h.app, h.db);
    const key = (await h.app.inject({ method: 'POST', url: '/keys', headers: ownerHeaders(farm),
      payload: { personName: 'Иван', role: 'coal', days: 7 } })).json();
    const d = (await pair(h, { code: key.code }, '10.7.4.1')).json();
    const phone = { token: d.token, farmId: d.farmId, userId: '', deviceId: d.deviceId, role: d.role };

    const people = (await h.app.inject({ method: 'GET', url: '/people', headers: ownerHeaders(farm) })).json().people;
    const patched = await h.app.inject({ method: 'PATCH', url: `/people/${people[0].id}`, headers: ownerHeaders(farm),
      payload: { role: 'timesheet', name: 'Иван Петрович' } });
    assert.equal(patched.statusCode, 200);

    const me = (await h.app.inject({ method: 'GET', url: '/me', headers: bearer(d.token) })).json();
    assert.equal(me.role, 'timesheet');

    const hours = await pushOps(h.app, phone, [upsert('hoursMark', randomUUID(), { employeeId: randomUUID(), hours: 8 })]);
    assert.equal(hours[0].status, 'applied');
    const coal = await pushOps(h.app, phone, [upsert('coalSale', randomUUID(), { amount: 100 })]);
    assert.equal(coal[0].status, 'rejected');

    const renamed = (await h.app.inject({ method: 'GET', url: '/people', headers: ownerHeaders(farm) })).json().people[0];
    assert.equal(renamed.name, 'Иван Петрович');
  } finally {
    await h.close();
  }
});
