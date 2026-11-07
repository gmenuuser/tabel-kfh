import { test } from 'node:test';
import assert from 'node:assert/strict';
import { canEdit, canOpen, modulesFor } from '../src/lib/roles.ts';

const pages = ['home', 'timesheet', 'employees', 'coal', 'livestock', 'agro', 'reports', 'settings'];
const open = (role: string) => pages.filter((p) => canOpen(role, p));

test('owner, manager and viewer see every page', () => {
  for (const r of ['owner', 'manager', 'viewer']) assert.deepEqual(open(r), pages);
});

test('a helper sees only the pages of its module, reports of it, and settings', () => {
  assert.deepEqual(open('timesheet'), ['home', 'timesheet', 'employees', 'reports', 'settings']);
  assert.deepEqual(open('coal'), ['home', 'coal', 'reports', 'settings']);
  assert.deepEqual(open('livestock'), ['home', 'livestock', 'reports', 'settings']);
  assert.deepEqual(modulesFor('coal'), ['coal']);
  assert.equal(canOpen('coal', 'agro'), false);
  assert.equal(canOpen('coal', 'no-such-page'), true, 'unknown route is the home page');
});

test('only the viewer cannot edit', () => {
  assert.equal(canEdit('viewer'), false);
  for (const r of ['owner', 'manager', 'timesheet', 'coal', 'livestock']) assert.equal(canEdit(r), true);
});
