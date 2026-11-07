import { test } from 'node:test';
import assert from 'node:assert/strict';
import { canEdit, canOpen, canWrite, modulesFor } from '../src/lib/roles.ts';
import { ENTITIES, ROLES, canWrite as serverCanWrite } from '../../server/src/entities.ts';

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

test('canWrite is the same rule as on the server, for every role and record type', () => {
  for (const role of ROLES) {
    for (const entity of [...Object.keys(ENTITIES), 'noSuchEntity']) {
      assert.equal(canWrite(role, entity), serverCanWrite(role, entity), `${role} → ${entity}`);
    }
  }
  assert.equal(canWrite('viewer', 'coalSettings'), false, 'the viewer makes no default prices');
  assert.equal(canWrite('coal', 'deliveryLocation'), true);
  assert.equal(canWrite('timesheet', 'livestockCategory'), false);
});
