import { test, before, beforeEach, after } from 'node:test';
import assert from 'node:assert/strict';
import { harness, resetDb, seedOwner, owner, appDevice, login, auth, pushOps, pullAll, upsert, remove, randomUUID, type Harness } from './helpers.ts';

let h: Harness;
before(async () => { h = await harness(); });
// An app device joins the oldest farm, so every test starts with an empty database.
beforeEach(async () => { await resetDb(h.db); });
after(async () => { await h.close(); });

const sale = (over: object = {}) => ({
  saleDate: '2026-11-06T02:00:00.000Z', kilograms: 2000, locationNameSnapshot: 'Новоцурухайтуй',
  coalPricePerTonSnapshot: 650000, deliveryPriceSnapshot: 100000, coalAmount: 1300000, deliveryPrice: 100000,
  totalAmount: 1400000, vehicleType: 'hired', driverId: randomUUID(), driverNameSnapshot: 'Мунгалов',
  driverPaymentAmount: 100000, comment: '', ...over,
});

test('вход владельца, приложение в том же хозяйстве, чужое хозяйство не видно', async () => {
  const { email, password } = await seedOwner(h.db);
  const iphone = await login(h.app, email, password, 'web');
  assert.equal(iphone.role, 'owner');
  const android = await appDevice(h.app);
  assert.equal(android.farmId, iphone.farmId);
  const bad = await h.app.inject({ method: 'POST', url: '/auth/login', payload: { email, password: 'неверный-пароль', platform: 'web' } });
  assert.equal(bad.statusCode, 401);

  const other = await owner(h.app, h.db);
  await pushOps(h.app, other, [upsert('employee', randomUUID(), { lastName: 'Чужой', firstName: 'Пётр' })]);
  const me = await h.app.inject({ method: 'GET', url: '/me', headers: auth(iphone) });
  assert.equal(me.json().farm.region, 'Приаргунский район, Забайкальский край, Россия');
  assert.equal(me.json().farm.timezone, 'Asia/Chita');
  assert.equal((await pullAll(h.app, iphone)).changes.length, 0, 'данные другого хозяйства недоступны');
  const noToken = await h.app.inject({ method: 'GET', url: '/sync/pull' });
  assert.equal(noToken.statusCode, 401);
});

// Тест 2 и 3: изменения одного устройства приходят на другие, без дублей.
test('работник с iPhone появляется на Android и сайте, правка с сайта — на iPhone', async () => {
  const web = await owner(h.app, h.db);
  const iphone = await appDevice(h.app, 'ios');
  const android = await appDevice(h.app, 'android');
  const employeeId = randomUUID();
  const markId = randomUUID();
  const queue = [
    upsert('employee', employeeId, { lastName: 'Иванов', firstName: 'Иван', hireDate: '2026-01-01T00:00:00.000Z' }),
    upsert('hoursMark', markId, { employeeId, day: '2026-11-06T00:00:00.000Z', hours: 8 }),
    upsert('coalSale', randomUUID(), sale()),
  ];
  const first = await pushOps(h.app, iphone, queue);
  assert.deepEqual(first.map((r) => r.status), ['applied', 'applied', 'applied']);
  // The connection broke before the answer arrived: the same queue is sent again.
  const again = await pushOps(h.app, iphone, queue);
  assert.ok(again.every((r) => r.repeated && r.status === 'applied'));

  for (const d of [android, web]) {
    const got = await pullAll(h.app, d);
    assert.equal(got.changes.length, 3, 'ровно три записи, без дублей');
    assert.equal(got.changes.find((c) => c.entity === 'employee')?.data.lastName, 'Иванов');
  }
  const cursor = (await pullAll(h.app, iphone)).next;
  const edit = await pushOps(h.app, web, [upsert('employee', employeeId, { position: 'Тракторист' }, 1)]);
  assert.equal(edit[0].status, 'applied');
  const update = await pullAll(h.app, iphone, cursor);
  assert.equal(update.changes.length, 1);
  assert.equal(update.changes[0].data.position, 'Тракторист');
  assert.equal(update.changes[0].data.lastName, 'Иванов', 'остальные поля на месте');
  assert.equal(update.changes[0].version, 2);
});

// Тест 4: одновременное редактирование.
test('разные поля объединяются, одно поле — конфликт, данные не теряются', async () => {
  const iphone = await owner(h.app, h.db);
  const android = await appDevice(h.app);
  const id = randomUUID();
  await pushOps(h.app, iphone, [upsert('employee', id, { lastName: 'Петров', firstName: 'Пётр', position: '' })]);
  // Both saw version 1.
  assert.equal((await pushOps(h.app, iphone, [upsert('employee', id, { position: 'Водитель' }, 1)]))[0].status, 'applied');
  assert.equal((await pushOps(h.app, android, [upsert('employee', id, { firstName: 'Павел' }, 1)]))[0].status, 'applied',
    'другое поле — объединено');
  const clash = await pushOps(h.app, android, [upsert('employee', id, { position: 'Тракторист' }, 1)]);
  assert.equal(clash[0].status, 'conflict');
  assert.deepEqual(clash[0].conflicts, ['position']);
  const rec = (await pullAll(h.app, android)).changes.find((c) => c.id === id)!;
  assert.equal(rec.data.position, 'Водитель', 'значение сервера не перезаписано');
  assert.equal(rec.data.firstName, 'Павел');

  const list = (await h.app.inject({ method: 'GET', url: '/conflicts', headers: auth(iphone) })).json().conflicts;
  assert.equal(list.length, 1);
  assert.equal(list[0].serverValue, 'Водитель');
  assert.equal(list[0].clientValue, 'Тракторист', 'обе версии сохранены');
  const resolved = await h.app.inject({ method: 'POST', url: `/conflicts/${list[0].id}/resolve`, headers: auth(iphone),
    payload: { choice: 'client' } });
  assert.equal(resolved.statusCode, 200);
  const after = (await pullAll(h.app, iphone)).changes.find((c) => c.id === id)!;
  assert.equal(after.data.position, 'Тракторист');
  assert.equal((await h.app.inject({ method: 'GET', url: '/conflicts', headers: auth(iphone) })).json().conflicts.length, 0);
});

test('продажу не сливают по полям: при конфликте не применяется ничего', async () => {
  const a = await owner(h.app, h.db);
  const b = await appDevice(h.app);
  const id = randomUUID();
  await pushOps(h.app, a, [upsert('coalSale', id, sale())]);
  await pushOps(h.app, a, [upsert('coalSale', id, { coalAmount: 1250000, totalAmount: 1350000 }, 1)]);
  const r = await pushOps(h.app, b, [upsert('coalSale', id, { deliveryPrice: 120000, totalAmount: 1420000, comment: 'b' }, 1)]);
  assert.equal(r[0].status, 'conflict');
  const rec = (await pullAll(h.app, b)).changes.find((c) => c.id === id)!;
  assert.equal(rec.data.deliveryPrice, 100000, 'половину изменения не применили');
  assert.equal(rec.data.comment, '');
  assert.equal(rec.data.totalAmount, 1350000);
});

// Тест 5: повторная отправка.
test('повторная отправка не создаёт двойных продаж, выплат и забоя', async () => {
  const d = await owner(h.app, h.db);
  const category = randomUUID();
  const ops = [
    upsert('coalSale', randomUUID(), sale()),
    upsert('driverPayment', randomUUID(), { driverId: randomUUID(), amount: 50000, paymentDate: '2026-11-06T03:00:00.000Z' }),
    upsert('livestockOperation', randomUUID(), { kind: 'initial', categoryId: category, heads: 20, date: '2026-11-01T00:00:00.000Z' }),
    upsert('livestockOperation', randomUUID(), { kind: 'slaughter', categoryId: category, heads: 2, date: '2026-11-06T00:00:00.000Z' }),
  ];
  for (let i = 0; i < 3; i += 1) await pushOps(h.app, d, ops);
  const got = (await pullAll(h.app, d)).changes;
  assert.equal(got.length, 4);
  assert.equal(got.filter((c) => c.entity === 'coalSale').length, 1);
  assert.equal(got.filter((c) => c.entity === 'driverPayment').length, 1);
  const herd = got.filter((c) => c.entity === 'livestockOperation')
    .reduce((n, c) => n + (c.data.kind === 'slaughter' ? -(c.data.heads as number) : (c.data.heads as number)), 0);
  assert.equal(herd, 18, 'забой списан один раз');
});

test('сервер проверяет деньги и поголовье', async () => {
  const d = await owner(h.app, h.db);
  const bad = await pushOps(h.app, d, [upsert('coalSale', randomUUID(), sale({ totalAmount: 1 }))]);
  assert.equal(bad[0].status, 'rejected');
  const own = await pushOps(h.app, d, [upsert('coalSale', randomUUID(), sale({ vehicleType: 'own', driverPaymentAmount: 100000 }))]);
  assert.equal(own[0].status, 'rejected', 'своя машина не создаёт долга');
  const category = randomUUID();
  await pushOps(h.app, d, [upsert('livestockOperation', randomUUID(), { kind: 'initial', categoryId: category, heads: 10, date: '2026-11-01T00:00:00.000Z' })]);
  const tooMany = await pushOps(h.app, d, [upsert('livestockOperation', randomUUID(), { kind: 'slaughter', categoryId: category, heads: 11, date: '2026-11-06T00:00:00.000Z' })]);
  assert.equal(tooMany[0].status, 'rejected');
  assert.match(tooMany[0].errors![0], /Не хватает голов/);
  const hours = await pushOps(h.app, d, [upsert('hoursMark', randomUUID(), { employeeId: randomUUID(), day: '2026-11-06', hours: 6 })]);
  assert.equal(hours[0].status, 'rejected');
});

// Тест 6: удаление.
test('удаление доходит до других устройств, удалённое не возвращается', async () => {
  const a = await owner(h.app, h.db);
  const b = await appDevice(h.app);
  const id = randomUUID();
  await pushOps(h.app, a, [upsert('driver', id, { lastName: 'Мунгалов' })]);
  const cursor = (await pullAll(h.app, b)).next;
  assert.equal((await pushOps(h.app, a, [remove('driver', id, 1)]))[0].status, 'applied');
  const seen = await pullAll(h.app, b, cursor);
  assert.equal(seen.changes.length, 1);
  assert.equal(seen.changes[0].deleted, true);
  // An old edit from a device that has not synced yet does not bring it back.
  const late = await pushOps(h.app, b, [upsert('driver', id, { phone: '+7 914' }, 1)]);
  assert.equal(late[0].status, 'conflict');
  assert.deepEqual(late[0].conflicts, ['__deleted']);
  const final = (await pullAll(h.app, a)).changes.find((c) => c.id === id)!;
  assert.equal(final.deleted, true);
  // Deleting a record that was changed elsewhere is a conflict, not a silent loss.
  const id2 = randomUUID();
  await pushOps(h.app, a, [upsert('driver', id2, { lastName: 'Кайдолов' })]);
  await pushOps(h.app, a, [upsert('driver', id2, { phone: '1' }, 1)]);
  assert.equal((await pushOps(h.app, b, [remove('driver', id2, 1)]))[0].status, 'conflict');
});

test('восстановление доступа кодом выключает старые устройства', async () => {
  const { email, password } = await seedOwner(h.db);
  const d = await login(h.app, email, password, 'web');
  await h.app.inject({ method: 'POST', url: '/auth/reset-request', payload: { email } });
  const code = h.logs.find((l) => l.includes(email))!.split(': ').pop()!;
  const r = await h.app.inject({ method: 'POST', url: '/auth/reset', payload: { email, code, password: 'новый-пароль-1' } });
  assert.equal(r.statusCode, 200);
  assert.equal((await h.app.inject({ method: 'GET', url: '/me', headers: auth(d) })).statusCode, 401);
  await login(h.app, email, 'новый-пароль-1', 'web');
  const again = await h.app.inject({ method: 'POST', url: '/auth/reset', payload: { email, code, password: 'ещё-пароль-12' } });
  assert.equal(again.statusCode, 400, 'код одноразовый');
});

// Тест 7: файлы.
test('файл загружается один раз и открывается с другого устройства', async () => {
  const a = await owner(h.app, h.db);
  const b = await appDevice(h.app);
  const photo = Buffer.from([0xff, 0xd8, 0xff, 1, 2, 3, 4, 5]);
  const up = await h.app.inject({ method: 'PUT', url: '/files', headers: { ...auth(a), 'content-type': 'image/jpeg',
    'x-file-name': encodeURIComponent('поле.jpg') }, payload: photo });
  assert.equal(up.statusCode, 201);
  const again = await h.app.inject({ method: 'PUT', url: '/files', headers: { ...auth(a), 'content-type': 'image/jpeg' }, payload: photo });
  assert.equal(again.json().repeated, true);
  assert.equal(again.json().id, up.json().id);
  const got = await h.app.inject({ method: 'GET', url: `/files/${up.json().id}`, headers: auth(b) });
  assert.equal(got.statusCode, 200);
  assert.deepEqual(got.rawPayload, photo);
  const stranger = await owner(h.app, h.db);
  assert.equal((await h.app.inject({ method: 'GET', url: `/files/${up.json().id}`, headers: auth(stranger) })).statusCode, 404);
});

test('серверная резервная копия: проверка и восстановление', async () => {
  const d = await owner(h.app, h.db);
  const id = randomUUID();
  await pushOps(h.app, d, [upsert('driver', id, { lastName: 'Мунгалов' })]);
  const backup = await h.app.inject({ method: 'POST', url: '/backups', headers: auth(d) });
  assert.equal(backup.statusCode, 201);
  assert.equal((await h.app.inject({ method: 'GET', url: `/backups/${backup.json().id}/verify`, headers: auth(d) })).json().ok, true);
  await pushOps(h.app, d, [upsert('driver', id, { lastName: 'Ошибка' }, 1), upsert('driver', randomUUID(), { lastName: 'Лишний' })]);
  const noConfirm = await h.app.inject({ method: 'POST', url: `/backups/${backup.json().id}/restore`, headers: auth(d), payload: {} });
  assert.equal(noConfirm.statusCode, 400, 'без подтверждения не восстанавливает');
  const restore = await h.app.inject({ method: 'POST', url: `/backups/${backup.json().id}/restore`, headers: auth(d), payload: { confirm: true } });
  assert.equal(restore.statusCode, 200);
  const live = (await pullAll(h.app, d)).changes.filter((c) => !c.deleted);
  assert.deepEqual(live.map((c) => c.data.lastName), ['Мунгалов']);
});
