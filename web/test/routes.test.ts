import { test } from 'node:test';
import assert from 'node:assert/strict';
import { gate } from '../src/lib/routes.ts';

test('без сессии: админка — форма входа, всё остальное — публичная страница', () => {
  assert.equal(gate('', false), 'public');
  assert.equal(gate('#/', false), 'public');
  assert.equal(gate('#/timesheet', false), 'public');
  assert.equal(gate('#/admin', false), 'login');
  assert.equal(gate('#/admin/anything', false), 'login');
});

test('с сессией владельца открывается приложение', () => {
  assert.equal(gate('', true), 'app');
  assert.equal(gate('#/admin', true), 'app');
});
