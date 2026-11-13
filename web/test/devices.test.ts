import { test } from 'node:test';
import assert from 'node:assert/strict';
import { sortDevices } from '../src/lib/devices.ts';

test('revoked devices go last, the rest keep their order', () => {
  const d = (id: string, revoked: boolean) => ({ id, name: id, platform: 'android', kind: 'app' as const, createdAt: '', lastSeenAt: null, revoked, current: false });
  assert.deepEqual(sortDevices([d('a', true), d('b', false), d('c', true), d('d', false)]).map((x) => x.id), ['b', 'd', 'a', 'c']);
});
