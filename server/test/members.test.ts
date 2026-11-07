import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { harness, register, login, auth, pullAll, pushOps, upsert, randomUUID, type Harness } from './helpers.ts';

let h: Harness;
before(async () => { h = await harness({ registrationOpen: true }); });
after(async () => { await h.close(); });

const createHelper = (owner: Parameters<typeof auth>[0], payload: object) =>
  h.app.inject({ method: 'POST', url: '/members', headers: auth(owner), payload });

test('регистрация закрыта, пока не включена', async () => {
  const closed = await harness();
  try {
    const r = await closed.app.inject({ method: 'POST', url: '/auth/register', payload: { email: 'a@example.com', password: 'пароль-12345' } });
    assert.equal(r.statusCode, 403);
    assert.equal(r.json().error, 'Регистрация закрыта');
    assert.deepEqual((await closed.app.inject({ method: 'GET', url: '/auth/config' })).json(), { registrationOpen: false });
  } finally {
    await closed.close();
  }
});

test('владелец создаёт помощника с логином без почты', async () => {
  assert.deepEqual((await h.app.inject({ method: 'GET', url: '/auth/config' })).json(), { registrationOpen: true });
  const owner = await register(h.app);
  const r = await createHelper(owner, { login: 'Brigadir', password: 'помощник-123', role: 'timesheet' });
  assert.equal(r.statusCode, 201);
  assert.equal(r.json().login, 'brigadir');
  assert.equal(r.json().role, 'timesheet');
  const signIn = await h.app.inject({ method: 'POST', url: '/auth/login', payload: {
    login: 'BRIGADIR', password: 'помощник-123', platform: 'ios' } });
  assert.equal(signIn.statusCode, 200);
  assert.equal(signIn.json().role, 'timesheet');
  assert.equal(signIn.json().farmId, owner.farmId);
  const row = (await h.db.query('SELECT email FROM users WHERE id = $1', [r.json().id])).rows[0];
  assert.equal(row.email, null);

  const members = (await h.app.inject({ method: 'GET', url: '/members', headers: auth(owner) })).json();
  assert.equal(members.length, 2);
  assert.equal(members.find((m: { id: string }) => m.id === r.json().id).login, 'brigadir');
  const devices = (await h.app.inject({ method: 'GET', url: '/devices', headers: auth(owner) })).json();
  assert.ok(devices.some((d: { login: string }) => d.login === 'brigadir'));
});

test('повторный логин, плохой логин и не владелец', async () => {
  const owner = await register(h.app);
  assert.equal((await createHelper(owner, { login: 'dup-login', password: 'помощник-123', role: 'coal' })).statusCode, 201);
  const dup = await createHelper(owner, { login: 'DUP-login', password: 'помощник-123', role: 'coal' });
  assert.equal(dup.statusCode, 409);
  assert.equal(dup.json().error, 'Такой логин уже есть');
  assert.equal((await createHelper(owner, { login: 'ab', password: 'помощник-123', role: 'coal' })).statusCode, 400);
  assert.equal((await createHelper(owner, { login: 'иван', password: 'помощник-123', role: 'coal' })).statusCode, 400);
  assert.equal((await createHelper(owner, { login: 'okname', password: 'short', role: 'coal' })).statusCode, 400);
  assert.equal((await createHelper(owner, { login: 'okname', password: 'помощник-123', role: 'owner' })).statusCode, 400);
  const helper = await login(h.app, 'dup-login', 'помощник-123');
  assert.equal((await createHelper(helper, { login: 'third', password: 'помощник-123', role: 'viewer' })).statusCode, 403);
});

test('владелец меняет пароль помощника: все его устройства отключаются', async () => {
  const owner = await register(h.app);
  const id = (await createHelper(owner, { login: 'pass-helper', password: 'помощник-123', role: 'timesheet' })).json().id;
  const old = await login(h.app, 'pass-helper', 'помощник-123');
  const r = await h.app.inject({ method: 'POST', url: `/members/${id}/password`, headers: auth(owner), payload: { password: 'новый-пароль-9' } });
  assert.equal(r.statusCode, 200);
  assert.equal((await h.app.inject({ method: 'GET', url: '/me', headers: auth(old) })).statusCode, 401);
  const bad = await h.app.inject({ method: 'POST', url: '/auth/login', payload: { login: 'pass-helper', password: 'помощник-123' } });
  assert.equal(bad.statusCode, 401);
  assert.equal(bad.json().error, 'Неверный логин или пароль');
  assert.equal((await h.app.inject({ method: 'POST', url: '/auth/login', payload: { login: 'pass-helper', password: 'новый-пароль-9' } })).statusCode, 200);
  assert.equal((await h.app.inject({ method: 'POST', url: `/members/${id}/password`, headers: auth(owner), payload: { password: 'коротко' } })).statusCode, 400);
  assert.equal((await h.app.inject({ method: 'POST', url: `/members/${randomUUID()}/password`, headers: auth(owner), payload: { password: 'новый-пароль-9' } })).statusCode, 404);
});

test('смена роли отключает устройства, новый вход получает новую роль', async () => {
  const owner = await register(h.app);
  const id = (await createHelper(owner, { login: 'role-helper', password: 'помощник-123', role: 'timesheet' })).json().id;
  const old = await login(h.app, 'role-helper', 'помощник-123');
  const pushed = await pushOps(h.app, old, [upsert('hoursMark', randomUUID(), { employeeId: randomUUID(), day: '2026-11-06', hours: 8 })]);
  assert.equal(pushed[0].status, 'applied');
  assert.ok((await pullAll(h.app, old, 0)).changes.some((c) => c.entity === 'hoursMark'), 'до смены роли отметка видна');
  assert.equal((await h.app.inject({ method: 'PATCH', url: `/members/${id}`, headers: auth(owner), payload: { role: 'coal' } })).statusCode, 200);
  assert.equal((await h.app.inject({ method: 'GET', url: '/me', headers: auth(old) })).statusCode, 401);
  const fresh = await login(h.app, 'role-helper', 'помощник-123');
  assert.equal(fresh.role, 'coal');
  assert.ok(!(await pullAll(h.app, fresh, 0)).changes.some((c) => c.entity === 'hoursMark'));
});

test('вход по почте владельца работает по-прежнему', async () => {
  const email = `legacy${Date.now()}@example.com`;
  await register(h.app, { email });
  const r = await h.app.inject({ method: 'POST', url: '/auth/login', payload: { email, password: 'пароль-12345' } });
  assert.equal(r.statusCode, 200);
  assert.equal(r.json().role, 'owner');
});

test('частые неверные попытки входа по логину ограничиваются', async () => {
  const limited = await harness(3);
  try {
    const codes: number[] = [];
    for (let i = 0; i < 4; i += 1) {
      codes.push((await limited.app.inject({ method: 'POST', url: '/auth/login', payload: { login: 'nobody', password: 'неверный-1' } })).statusCode);
    }
    assert.deepEqual(codes, [401, 401, 401, 429]);
  } finally {
    await limited.close();
  }
});

test('пароль нельзя менять у учётной записи с почтой или с другим хозяйством', async () => {
  const owner = await register(h.app);
  const other = await register(h.app);
  // The owner of another farm is also a member of this one.
  await h.db.query("INSERT INTO memberships (farm_id, user_id, role) VALUES ($1, $2, 'viewer')", [owner.farmId, other.userId]);
  const before = (await h.db.query('SELECT password_hash FROM users WHERE id = $1', [other.userId])).rows[0].password_hash;
  const r = await h.app.inject({ method: 'POST', url: `/members/${other.userId}/password`, headers: auth(owner), payload: { password: 'взлом-пароль-1' } });
  assert.equal(r.statusCode, 404);
  assert.equal(r.json().error, 'Помощник не найден');
  const after = (await h.db.query('SELECT password_hash FROM users WHERE id = $1', [other.userId])).rows[0].password_hash;
  assert.equal(after, before);
  assert.equal((await h.app.inject({ method: 'GET', url: '/me', headers: auth(other) })).statusCode, 200, 'устройства не отключены');

  // An e-mail account that belongs to this farm only.
  const mail = await h.db.query("INSERT INTO users (email, name, password_hash) VALUES ($1, 'Почта', 'x') RETURNING id", [`m${Date.now()}@example.com`]);
  await h.db.query("INSERT INTO memberships (farm_id, user_id, role) VALUES ($1, $2, 'viewer')", [owner.farmId, mail.rows[0].id]);
  const r2 = await h.app.inject({ method: 'POST', url: `/members/${mail.rows[0].id}/password`, headers: auth(owner), payload: { password: 'взлом-пароль-1' } });
  assert.equal(r2.statusCode, 404);
});
