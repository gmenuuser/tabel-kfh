import { test } from 'node:test';
import assert from 'node:assert/strict';
import { harness, owner as seedFarm } from './helpers.ts';
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
