import { test } from 'node:test';
import assert from 'node:assert/strict';
import { harness } from './helpers.ts';

test('частые попытки входа ограничиваются', async () => {
  const h = await harness({ authAttemptsPerMinute: 3 });
  try {
    const codes: number[] = [];
    for (let i = 0; i < 5; i += 1) {
      codes.push((await h.app.inject({ method: 'POST', url: '/auth/login', payload: { email: 'x@example.com', password: 'неверный-1', platform: 'web' } })).statusCode);
    }
    assert.deepEqual(codes, [401, 401, 401, 429, 429]);
  } finally {
    await h.close();
  }
});

test('IP клиента берётся из X-Forwarded-For только от доверенного прокси', async () => {
  const h = await harness({ authAttemptsPerMinute: 2 });
  try {
    const login = (remoteAddress: string, xff: string) => h.app.inject({ method: 'POST', url: '/auth/login', remoteAddress,
      headers: { 'x-forwarded-for': xff }, payload: { email: 'x@example.com', password: 'неверный-1', platform: 'web' } }).then((r) => r.statusCode);
    // via nginx (docker bridge): different clients have separate limits
    assert.deepEqual([await login('172.18.0.1', '1.1.1.1'), await login('172.18.0.1', '1.1.1.1'), await login('172.18.0.1', '1.1.1.1')], [401, 401, 429]);
    assert.equal(await login('172.18.0.1', '2.2.2.2'), 401);
    // a direct stranger cannot dodge the limit by forging the header
    assert.deepEqual([await login('8.8.8.8', '3.3.3.3'), await login('8.8.8.8', '4.4.4.4'), await login('8.8.8.8', '5.5.5.5')], [401, 401, 429]);
  } finally {
    await h.close();
  }
});
