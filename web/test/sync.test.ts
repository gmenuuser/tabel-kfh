// The site's sync engine against the real server and a real PostgreSQL (the server's test database).
import 'fake-indexeddb/auto';
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { harness, register, login, type Harness } from '../../server/test/helpers.ts';
import { LocalDb } from '../src/lib/db.ts';
import { SyncEngine, type Session } from '../src/lib/sync.ts';
import { stableId, sha256 } from '../src/lib/ids.ts';
import { dayKey, dayStartISO } from '../src/lib/time.ts';

let h: Harness;
let base = '';
before(async () => {
  h = await harness();
  base = await h.app.listen({ port: 0, host: '127.0.0.1' });
});
after(async () => { await h.close(); });

/** fetch that can be switched off (no internet) or lose the answer after the server did the work. */
function network() {
  const state = { online: true, loseAnswers: 0 };
  const f: typeof fetch = async (input, init) => {
    if (!state.online) throw new TypeError('fetch failed');
    const r = await fetch(input, init);
    if (state.loseAnswers > 0) { state.loseAnswers -= 1; throw new TypeError('connection reset'); }
    return r;
  };
  return { state, fetch: f };
}

let dbCounter = 0;
async function device(session: Session, net = network(), name = `device-${++dbCounter}`) {
  const db = await LocalDb.open(indexedDB, name);
  const engine = new SyncEngine(db, session, net.fetch, base);
  engine.autoSyncDelayMs = 0; // the tests decide when to sync
  await engine.init();
  return { db, engine, net, name };
}

test('stable ids are the same on every platform', () => {
  const hex = Array.from(sha256(new TextEncoder().encode('abc')), (b) => b.toString(16).padStart(2, '0')).join('');
  assert.equal(hex, 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad');
  const id = stableId('hoursMark:3f2c8a8e-0000-4000-8000-000000000001:2026-11-06');
  assert.match(id, /^[0-9a-f]{8}-[0-9a-f]{4}-8[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  assert.equal(id, stableId('hoursMark:3f2c8a8e-0000-4000-8000-000000000001:2026-11-06'));
  assert.equal(dayKey('2026-11-05T15:00:00.000Z', 'Asia/Chita'), '2026-11-06');
  assert.equal(dayStartISO('2026-11-06', 'Asia/Chita'), '2026-11-05T15:00:00.000Z');
});

// Тесты 1–3 и 6.
test('без интернета всё сохраняется, после — уходит на сервер и приходит на другое устройство', async () => {
  const email = `web${Date.now()}@example.com`;
  const owner = await register(h.app, { email, platform: 'web' });
  const a = await device(owner);
  a.net.state.online = false;

  const employeeId = await a.engine.save('employee', null, { lastName: 'Иванов', firstName: 'Иван', hireDate: '2026-01-01T00:00:00.000Z' });
  const markId = stableId(`hoursMark:${employeeId}:2026-11-06`);
  await a.engine.save('hoursMark', markId, { employeeId, day: dayStartISO('2026-11-06'), hours: 8 });
  await a.engine.save('coalSale', null, { saleDate: '2026-11-06T02:00:00.000Z', kilograms: 2000, coalAmount: 1300000,
    deliveryPrice: 100000, totalAmount: 1400000, vehicleType: 'own', driverPaymentAmount: 0, locationNameSnapshot: 'Приаргунск' });
  await a.engine.sync();
  assert.equal(a.engine.status.online, false);
  assert.equal(a.engine.status.pending, 3, 'три изменения ждут отправки');

  // The browser is closed and opened again.
  a.db.close();
  const a2 = await device(owner, a.net, a.name);
  assert.equal(a2.engine.status.pending, 3, 'очередь пережила перезапуск');
  assert.equal((await a2.engine.list('employee'))[0].data.lastName, 'Иванов');

  a.net.state.online = true;
  a.net.state.loseAnswers = 1; // the first answer is lost: everything is sent again
  await a2.engine.sync();
  await a2.engine.sync();
  assert.equal(a2.engine.status.pending, 0, 'всё подтверждено сервером');

  const b = await device(await login(h.app, email, 'пароль-12345', 'android'));
  await b.engine.sync();
  assert.equal((await b.engine.list('employee')).length, 1, 'без дублей');
  assert.equal((await b.engine.list('coalSale')).length, 1);
  assert.equal((await b.engine.list('hoursMark'))[0].data.hours, 8);

  // An edit on B reaches A; a delete on A reaches B.
  await b.engine.save('employee', employeeId, { position: 'Тракторист' });
  await b.engine.sync();
  await a2.engine.sync();
  const onA = (await a2.engine.list('employee'))[0];
  assert.equal(onA.data.position, 'Тракторист');
  assert.equal(onA.data.lastName, 'Иванов');
  await a2.engine.remove('hoursMark', markId);
  await a2.engine.sync();
  await b.engine.sync();
  assert.equal((await b.engine.list('hoursMark')).length, 0, 'удаление дошло');
});

test('the same person and day marked on two devices offline is one record', async () => {
  const email = `web-same${Date.now()}@example.com`;
  const owner = await register(h.app, { email, platform: 'web' });
  const a = await device(owner);
  const b = await device(await login(h.app, email, 'пароль-12345', 'android'));
  const employeeId = await a.engine.save('employee', null, { lastName: 'Петров' });
  await a.engine.sync();
  await b.engine.sync();
  a.net.state.online = false;
  b.net.state.online = false;
  const id = stableId(`hoursMark:${employeeId}:2026-11-06`);
  await a.engine.save('hoursMark', id, { employeeId, day: dayStartISO('2026-11-06'), hours: 8 });
  await b.engine.save('hoursMark', id, { employeeId, day: dayStartISO('2026-11-06'), hours: 12 });
  a.net.state.online = true;
  b.net.state.online = true;
  await a.engine.sync();
  await b.engine.sync();
  await a.engine.sync();
  const marks = await a.engine.list('hoursMark');
  assert.equal(marks.length, 1, 'одна отметка на человека и день');
  assert.equal(marks[0].data.hours, 8, 'второе значение не перезаписало первое');
  const conflicts = await a.engine.conflicts();
  assert.equal(conflicts.length, 1, 'вторая версия сохранена как конфликт');
  assert.equal(conflicts[0].clientValue, 12);
  await a.engine.resolve(conflicts[0].id, 'client');
  assert.equal((await a.engine.list('hoursMark'))[0].data.hours, 12);
});

// Тест 4 со стороны устройства.
test('edits made offline on two devices: different fields merge, local unsent edits survive a pull', async () => {
  const email = `web-merge${Date.now()}@example.com`;
  const owner = await register(h.app, { email, platform: 'web' });
  const a = await device(owner);
  const b = await device(await login(h.app, email, 'пароль-12345', 'android'));
  const id = await a.engine.save('driver', null, { lastName: 'Мунгалов', phone: '' });
  await a.engine.sync();
  await b.engine.sync();
  await a.engine.save('driver', id, { phone: '+7 914' });
  await b.engine.save('driver', id, { comment: 'КамАЗ' });
  await a.engine.sync();
  // B pulls before pushing: its unsent comment stays on screen.
  b.net.state.online = true;
  await b.engine.sync();
  const onB = (await b.engine.list('driver'))[0];
  assert.equal(onB.data.phone, '+7 914');
  assert.equal(onB.data.comment, 'КамАЗ');
  await a.engine.sync();
  assert.equal((await a.engine.list('driver'))[0].data.comment, 'КамАЗ');
  assert.equal(b.engine.status.conflicts, 0);
});

test('a refused change is kept and shown, not lost', async () => {
  const owner = await register(h.app, { email: `web-bad${Date.now()}@example.com`, platform: 'web' });
  const a = await device(owner);
  await a.engine.save('coalSale', null, { kilograms: 2000, coalAmount: 1, deliveryPrice: 1, totalAmount: 5, vehicleType: 'own',
                                          driverPaymentAmount: 0 });
  await a.engine.sync();
  assert.equal(a.engine.status.failed, 1);
  assert.equal(a.engine.status.pending, 0);
  const [failed] = await a.engine.failedOps();
  assert.match(failed.lastError!, /Итого/);
  await a.engine.discard(failed.opId);
  assert.equal(a.engine.status.failed, 0);
});

test('a change made during a sync is sent right after it', async () => {
  const owner = await register(h.app, { email: `web-race${Date.now()}@example.com`, platform: 'web' });
  const a = await device(owner);
  const first = a.engine.sync();
  await a.engine.save('driver', null, { lastName: 'Кайдолов' });
  await Promise.all([first, a.engine.sync()]);
  assert.equal(a.engine.status.pending, 0);
});
