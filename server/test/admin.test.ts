import { test } from 'node:test';
import assert from 'node:assert/strict';
import { harness, seedOwner, owner, appDevice, auth, randomUUID } from './helpers.ts';

const OLD_LOGIN = 'Вход по логину отменён — скачайте новую версию приложения у владельца';

test('старый вход по логину и из приложения отклоняется', async () => {
  const h = await harness();
  try {
    const { email, password } = await seedOwner(h.db);
    const byLogin = await h.app.inject({ method: 'POST', url: '/auth/login', payload: { login: 'brigadir', password, deviceName: 'x', platform: 'android' } });
    assert.equal(byLogin.statusCode, 403);
    assert.equal(byLogin.json().error, OLD_LOGIN);
    const webLogin = await h.app.inject({ method: 'POST', url: '/auth/login', payload: { login: 'brigadir', password, deviceName: 'x', platform: 'web' } });
    assert.equal(webLogin.statusCode, 403);
    const ios = await h.app.inject({ method: 'POST', url: '/auth/login', payload: { login: email, password, deviceName: 'x', platform: 'ios' } });
    assert.equal(ios.statusCode, 403);
    assert.equal(ios.json().error, OLD_LOGIN);
    const noPlatform = await h.app.inject({ method: 'POST', url: '/auth/login', payload: { email, password } });
    assert.equal(noPlatform.statusCode, 403);
    const wrong = await h.app.inject({ method: 'POST', url: '/auth/login', payload: { login: email, password: 'неверный-пароль', platform: 'web' } });
    assert.equal(wrong.statusCode, 401);
    const ok = await h.app.inject({ method: 'POST', url: '/auth/login', payload: { login: email, password, deviceName: 'Chrome', platform: 'web' } });
    assert.equal(ok.statusCode, 200);
    assert.equal(ok.json().role, 'owner');
  } finally {
    await h.close();
  }
});

test('приложение не открывает админские запросы', async () => {
  const h = await harness();
  try {
    const site = await owner(h.app, h.db);
    const phone = await appDevice(h.app);
    const calls: [string, string, object?][] = [
      ['GET', '/devices'], ['PATCH', `/devices/${site.deviceId}`, { name: 'x' }], ['POST', `/devices/${site.deviceId}/revoke`],
      ['PATCH', '/farm', { name: 'x' }], ['GET', '/backups'], ['GET', '/audit'], ['POST', `/conflicts/${randomUUID()}/resolve`, { choice: 'client' }],
    ];
    for (const [method, url, payload] of calls) {
      const r = await h.app.inject({ method: method as 'GET', url, headers: auth(phone), payload });
      assert.equal(r.statusCode, 403, `${method} ${url}`);
    }
    // the owner's own session was not touched
    assert.equal((await h.app.inject({ method: 'GET', url: '/devices', headers: auth(site) })).statusCode, 200);
  } finally {
    await h.close();
  }
});

test('владелец переименовывает и отключает устройство', async () => {
  const h = await harness();
  try {
    const site = await owner(h.app, h.db);
    const phone = await appDevice(h.app, 'ios');
    const list = async () => (await h.app.inject({ method: 'GET', url: '/devices', headers: auth(site) })).json() as
      { id: string; name: string; platform: string; kind: string; revoked: boolean; current: boolean }[];
    const first = await list();
    assert.equal(first.length, 2);
    assert.equal(first.find((d) => d.id === site.deviceId)!.kind, 'owner');
    assert.equal(first.find((d) => d.id === site.deviceId)!.current, true);
    assert.equal(first.find((d) => d.id === phone.deviceId)!.kind, 'app');

    const patch = (id: string, name: unknown) => h.app.inject({ method: 'PATCH', url: `/devices/${id}`, headers: auth(site), payload: { name } });
    assert.equal((await patch(phone.deviceId, '  Телефон Ивана  ')).statusCode, 200);
    assert.equal((await list()).find((d) => d.id === phone.deviceId)!.name, 'Телефон Ивана');
    assert.equal((await patch(phone.deviceId, '   ')).statusCode, 400);
    assert.equal((await patch(phone.deviceId, 'x'.repeat(101))).statusCode, 400);
    assert.equal((await patch(phone.deviceId, 5)).statusCode, 400);
    assert.equal((await patch(randomUUID(), 'x')).statusCode, 404);
    assert.equal((await patch('не-uuid', 'x')).statusCode, 404);
    // a device of another farm is not found
    const other = await h.db.query("INSERT INTO farms (name) VALUES ('Чужое') RETURNING id");
    const foreign = await h.db.query("INSERT INTO devices (farm_id, name, platform, token_hash) VALUES ($1, 'чужой', 'ios', 'h') RETURNING id", [other.rows[0].id]);
    assert.equal((await patch(foreign.rows[0].id, 'x')).statusCode, 404);
    assert.equal((await h.app.inject({ method: 'POST', url: `/devices/${foreign.rows[0].id}/revoke`, headers: auth(site) })).statusCode, 404);

    assert.equal((await h.app.inject({ method: 'GET', url: '/sync/pull?since=0', headers: auth(phone) })).statusCode, 200);
    assert.equal((await h.app.inject({ method: 'POST', url: `/devices/${phone.deviceId}/revoke`, headers: auth(site) })).statusCode, 200);
    assert.equal((await h.app.inject({ method: 'GET', url: '/sync/pull?since=0', headers: auth(phone) })).statusCode, 401);
    assert.equal((await list()).find((d) => d.id === phone.deviceId)!.revoked, true);
  } finally {
    await h.close();
  }
});

test('регистрации хозяйства больше нет', async () => {
  const h = await harness();
  try {
    const r = await h.app.inject({ method: 'POST', url: '/auth/register', payload: { email: 'new@example.com', password: 'пароль-12345' } });
    assert.equal(r.statusCode, 404);
    const cfg = await h.app.inject({ method: 'GET', url: '/auth/config' });
    assert.deepEqual(cfg.json(), { registrationOpen: false });
    const site = await owner(h.app, h.db);
    for (const [method, url] of [['GET', '/members'], ['POST', '/members'], ['DELETE', `/members/${randomUUID()}`]]) {
      assert.equal((await h.app.inject({ method: method as 'GET', url, headers: auth(site) })).statusCode, 404, `${method} ${url}`);
    }
  } finally {
    await h.close();
  }
});

test('выход из приложения и с сайта работает', async () => {
  const h = await harness();
  try {
    const site = await owner(h.app, h.db);
    const phone = await appDevice(h.app);
    assert.equal((await h.app.inject({ method: 'POST', url: '/auth/logout', headers: auth(phone) })).statusCode, 200);
    assert.equal((await h.app.inject({ method: 'GET', url: '/sync/pull', headers: auth(phone) })).statusCode, 401);
    assert.equal((await h.app.inject({ method: 'POST', url: '/auth/logout', headers: auth(site) })).statusCode, 200);
  } finally {
    await h.close();
  }
});
