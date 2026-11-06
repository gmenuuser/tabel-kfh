import { test } from 'node:test';
import assert from 'node:assert/strict';
import { harness } from './helpers.ts';

test('частые попытки входа ограничиваются', async () => {
  const h = await harness(3);
  try {
    const codes: number[] = [];
    for (let i = 0; i < 5; i += 1) {
      codes.push((await h.app.inject({ method: 'POST', url: '/auth/login', payload: { email: 'x@example.com', password: 'неверный-1' } })).statusCode);
    }
    assert.deepEqual(codes, [401, 401, 401, 429, 429]);
  } finally {
    await h.close();
  }
});
