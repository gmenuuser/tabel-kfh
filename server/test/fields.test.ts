import { test, before, beforeEach, after } from 'node:test';
import assert from 'node:assert/strict';
import { harness, resetDb, owner, appDevice, auth, pushOps, pullAll, upsert, randomUUID, type Device, type Harness } from './helpers.ts';
import { sha256, newToken } from '../src/auth.ts';

let h: Harness;
before(async () => { h = await harness(); });
beforeEach(async () => { await resetDb(h.db); });
after(async () => { await h.close(); });

const field = (over: object = {}) => ({ name: 'Поле 3', areaSqM: 125000, culture: 'Пшеница', note: '', ...over });

/** A phone of a person with [role]: the site signs in only the owner, so helpers and viewers come as paired devices. */
async function deviceFor(farmId: string, role: string): Promise<Device> {
  const u = await h.db.query('INSERT INTO users (name, password_hash) VALUES ($1, $2) RETURNING id', [role, '']);
  await h.db.query('INSERT INTO memberships (farm_id, user_id, role) VALUES ($1, $2, $3)', [farmId, u.rows[0].id, role]);
  const token = newToken();
  const d = await h.db.query("INSERT INTO devices (farm_id, user_id, name, platform, token_hash) VALUES ($1, $2, 'Телефон', 'android', $3) RETURNING id",
    [farmId, u.rows[0].id, sha256(token)]);
  return { token, farmId, userId: u.rows[0].id, deviceId: d.rows[0].id, role };
}

test('владелец заводит поле, запись приходит на телефон', async () => {
  const web = await owner(h.app, h.db);
  const phone = await appDevice(h.app);
  const id = randomUUID();
  const r = await pushOps(h.app, web, [upsert('field', id, field())]);
  assert.equal(r[0].status, 'applied');
  const got = (await pullAll(h.app, phone)).changes.find((c) => c.entity === 'field');
  assert.equal(got?.data.name, 'Поле 3');
  assert.equal(got?.data.areaSqM, 125000);
});

test('поле без названия и без площади отклоняется', async () => {
  const web = await owner(h.app, h.db);
  const r = await pushOps(h.app, web, [
    upsert('field', randomUUID(), field({ name: '   ' })),
    upsert('field', randomUUID(), field({ areaSqM: 0 })),
    upsert('field', randomUUID(), field({ areaSqM: 12.5 })),
    upsert('field', randomUUID(), field({ areaSqM: 1_000_000_001 })),
  ]);
  assert.deepEqual(r.map((x) => x.status), ['rejected', 'rejected', 'rejected', 'rejected']);
  assert.ok(r[0].errors?.some((e) => e.includes('Название поля')));
  for (const i of [1, 2, 3]) assert.ok(r[i].errors?.some((e) => e.includes('Площадь')), `площадь, случай ${i}`);
});

test('помощник «только табель» поля не видит и менять не может', async () => {
  const web = await owner(h.app, h.db);
  const helper = await deviceFor(web.farmId, 'timesheet');
  const entities = await h.app.inject({ method: 'GET', url: '/entities', headers: auth(helper) });
  assert.equal(entities.json().entities.includes('field'), false);
  const r = await pushOps(h.app, helper, [upsert('field', randomUUID(), field())]);
  assert.equal(r[0].status, 'rejected');
  assert.ok(r[0].errors?.some((e) => e.includes('Нет прав')));
});

test('«просмотр» читает поля, но не меняет; телефон-приложение меняет', async () => {
  const web = await owner(h.app, h.db);
  const viewer = await deviceFor(web.farmId, 'viewer');
  const entities = await h.app.inject({ method: 'GET', url: '/entities', headers: auth(viewer) });
  assert.equal(entities.json().entities.includes('field'), true, 'поля видны просмотру');
  const denied = await pushOps(h.app, viewer, [upsert('field', randomUUID(), field())]);
  assert.equal(denied[0].status, 'rejected');

  const phone = await appDevice(h.app);
  const allowed = await pushOps(h.app, phone, [upsert('field', randomUUID(), field())]);
  assert.equal(allowed[0].status, 'applied', 'телефон заводит поля');
});
