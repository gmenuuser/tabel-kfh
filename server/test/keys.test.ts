import { test } from 'node:test';
import assert from 'node:assert/strict';
import { harness, owner as seedFarm } from './helpers.ts';

// Task 1: the schema of access keys, and a person with no e-mail and no password.

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
