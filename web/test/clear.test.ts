import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  CLEAR_TARGETS, clearPeriod, countInPeriod, dayRange, monthRange, recordsInPeriod, type ClearEngine,
} from '../src/lib/clear.ts';
import type { LocalRecord } from '../src/lib/db.ts';

const rec = (entity: string, id: string, data: Record<string, unknown>): LocalRecord =>
  ({ key: `${entity}:${id}`, entity, id, version: 1, data, deleted: false });

test('месяц: первое и последнее число, включая февраль високосного года', () => {
  assert.deepEqual(monthRange('2026-11'), ['2026-11-01', '2026-11-30']);
  assert.deepEqual(monthRange('2026-02'), ['2026-02-01', '2026-02-28']);
  assert.deepEqual(monthRange('2028-02'), ['2028-02-01', '2028-02-29']);
  assert.deepEqual(dayRange('2026-11-17'), ['2026-11-17', '2026-11-17']);
});

test('табель: обнулённые отметки не считаются, границы месяца включительные', () => {
  const [target] = CLEAR_TARGETS.timesheet;
  const marks = [
    rec('hoursMark', 'a', { employeeId: 'e1', day: '2026-11-01T00:00:00.000Z', hours: 8 }),
    rec('hoursMark', 'b', { employeeId: 'e1', day: '2026-11-30T00:00:00.000Z', hours: 4 }),
    rec('hoursMark', 'c', { employeeId: 'e1', day: '2026-12-01T00:00:00.000Z', hours: 8 }),
    rec('hoursMark', 'd', { employeeId: 'e1', day: '2026-11-10T00:00:00.000Z', hours: null }),
  ];
  const [from, to] = monthRange('2026-11');
  assert.deepEqual(recordsInPeriod(marks, target, from, to).map((r) => r.id), ['a', 'b']);
});

test('удалённая запись не чистится второй раз', () => {
  const [target] = CLEAR_TARGETS.coal;
  const rows = [{ ...rec('coalSale', 's1', { saleDate: '2026-11-05T10:00:00.000Z' }), deleted: true }];
  assert.deepEqual(recordsInPeriod(rows, target, '2026-11-01', '2026-11-30'), []);
});

test('запись без даты не попадает ни в какой период', () => {
  const [target] = CLEAR_TARGETS.agro;
  assert.deepEqual(recordsInPeriod([rec('agroCalculation', 'c1', { title: 'без даты' })], target, '2026-01-01', '2026-12-31'), []);
});

function fakeEngine(seed: Record<string, LocalRecord[]>) {
  const data = new Map(Object.entries(seed).map(([k, v]) => [k, [...v]]));
  const removed: string[] = [];
  const saved: string[] = [];
  const engine: ClearEngine = {
    async list(entity) { return (data.get(entity) ?? []).filter((r) => !r.deleted); },
    async save(entity, id, fields) {
      saved.push(`${entity}:${id}`);
      const row = (data.get(entity) ?? []).find((r) => r.id === id)!;
      row.data = { ...row.data, ...fields };
      return id!;
    },
    async remove(entity, id) {
      removed.push(`${entity}:${id}`);
      const row = (data.get(entity) ?? []).find((r) => r.id === id);
      if (row) row.deleted = true;
    },
  };
  return { engine, removed, saved };
}

test('уголь за день: продажи, рабочий день и выплаты уходят удалением', async () => {
  const { engine, removed, saved } = fakeEngine({
    coalSale: [rec('coalSale', 's1', { saleDate: '2026-11-17T09:00:00.000Z' }),
               rec('coalSale', 's2', { saleDate: '2026-11-18T09:00:00.000Z' })],
    coalWorkDay: [rec('coalWorkDay', 'd1', { day: '2026-11-17T00:00:00.000Z' })],
    driverPayment: [rec('driverPayment', 'p1', { paymentDate: '2026-11-17T12:00:00.000Z' })],
  });
  const n = await clearPeriod(engine, 'coal', '2026-11-17', '2026-11-17');
  assert.equal(n, 3);
  assert.deepEqual(removed.sort(), ['coalSale:s1', 'coalWorkDay:d1', 'driverPayment:p1']);
  assert.deepEqual(saved, []);
});

test('табель за месяц: отметки получают hours: null, строки остаются', async () => {
  const { engine, removed, saved } = fakeEngine({
    hoursMark: [rec('hoursMark', 'm1', { day: '2026-11-03T00:00:00.000Z', hours: 8 }),
                rec('hoursMark', 'm2', { day: '2026-12-03T00:00:00.000Z', hours: 8 })],
  });
  assert.equal(await countInPeriod(engine, 'timesheet', '2026-11-01', '2026-11-30'), 1);
  assert.equal(await clearPeriod(engine, 'timesheet', '2026-11-01', '2026-11-30'), 1);
  assert.deepEqual(saved, ['hoursMark:m1']);
  assert.deepEqual(removed, []);
});

test('скот: причина забоя уходит вместе со своей операцией, даже если у неё другая дата', async () => {
  const { engine, removed } = fakeEngine({
    livestockOperation: [rec('livestockOperation', 'o1', { kind: 'slaughter', date: '2026-11-10T00:00:00.000Z', heads: 1 }),
                         rec('livestockOperation', 'o2', { kind: 'purchase', date: '2026-12-10T00:00:00.000Z', heads: 2 })],
    slaughterRecord: [rec('slaughterRecord', 'sl1', { operationId: 'o1', createdAt: '2026-11-10T05:00:00.000Z' }),
                      rec('slaughterRecord', 'sl2', { operationId: 'o2', createdAt: '2026-12-10T05:00:00.000Z' })],
  });
  assert.equal(await countInPeriod(engine, 'livestock', '2026-11-01', '2026-11-30'), 2);
  assert.equal(await clearPeriod(engine, 'livestock', '2026-11-01', '2026-11-30'), 2);
  assert.deepEqual(removed.sort(), ['livestockOperation:o1', 'slaughterRecord:sl1']);
});
