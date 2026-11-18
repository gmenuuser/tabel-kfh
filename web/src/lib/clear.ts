// «Очистка периода»: убирает записи одного раздела учёта, у которых деловая дата попадает в выбранный день или
// месяц. Сайт и телефон читают одни и те же поля даты, поэтому чистят одни и те же строки — см.
// `ClearPeriodTargets` в Android (`ru.kfh.core.clear`) и спека docs/superpowers/specs/*-clear-period-design.md.

import type { LocalRecord } from './db.ts';
import { dayKey, daysInMonth } from './time.ts';

export type ClearSection = 'timesheet' | 'coal' | 'livestock' | 'agro';

/**
 * Как тип записи участвует в очистке: `delete` шлёт операцию удаления, `unset-hours` оставляет строку отметки,
 * но обнуляет часы — так приложение понимает «отметки нет» (см. `markIndex` в `pages/timesheet.tsx`).
 */
export interface ClearTarget {
  entity: string;
  /** Поле с деловой датой (ISO-строка). */
  dateField: string;
  mode: 'delete' | 'unset-hours';
}

/** Что чистится в каждом разделе. Справочники (сотрудники, водители, категории, настройки) не трогаем. */
export const CLEAR_TARGETS: Record<ClearSection, ClearTarget[]> = {
  timesheet: [{ entity: 'hoursMark', dateField: 'day', mode: 'unset-hours' }],
  coal: [
    { entity: 'coalSale', dateField: 'saleDate', mode: 'delete' },
    { entity: 'coalWorkDay', dateField: 'day', mode: 'delete' },
    { entity: 'driverPayment', dateField: 'paymentDate', mode: 'delete' },
  ],
  livestock: [{ entity: 'livestockOperation', dateField: 'date', mode: 'delete' }],
  agro: [{ entity: 'agroCalculation', dateField: 'createdAt', mode: 'delete' }],
};

export const CLEAR_TITLES: Record<ClearSection, string> = {
  timesheet: 'табель', coal: 'уголь', livestock: 'скот', agro: 'агро',
};

/** Записи раздела, попадающие в закрытый диапазон дней (`YYYY-MM-DD`, обе границы включительно). */
export function recordsInPeriod(records: LocalRecord[], target: ClearTarget, from: string, to: string): LocalRecord[] {
  const out: LocalRecord[] = [];
  for (const r of records) {
    if (r.deleted) continue;
    if (target.mode === 'unset-hours' && (r.data.hours === null || r.data.hours === undefined)) continue;
    const raw = r.data[target.dateField];
    if (raw === null || raw === undefined || raw === '') continue;
    const key = dayKey(String(raw));
    if (key >= from && key <= to) out.push(r);
  }
  return out;
}

/** Границы одного дня (`YYYY-MM-DD` → тот же день). */
export function dayRange(key: string): [string, string] { return [key, key]; }

/** Границы месяца (`YYYY-MM` → первое и последнее число). */
export function monthRange(month: string): [string, string] {
  const [y, m] = month.split('-').map(Number);
  const last = daysInMonth(y, m);
  return [`${month}-01`, `${month}-${String(last).padStart(2, '0')}`];
}

/** Что этот движок должен уметь от очистки (SyncEngine подходит целиком). */
export interface ClearEngine {
  list(entity: string): Promise<LocalRecord[]>;
  save(entity: string, id: string | null, fields: Record<string, unknown>): Promise<string>;
  remove(entity: string, id: string): Promise<void>;
}

/** Сколько записей раздела попадёт под очистку — для предупреждения до нажатия. */
export async function countInPeriod(engine: ClearEngine, section: ClearSection, from: string, to: string): Promise<number> {
  let n = 0;
  for (const t of CLEAR_TARGETS[section]) n += recordsInPeriod(await engine.list(t.entity), t, from, to).length;
  if (section === 'livestock') n += (await slaughterOfClearedOps(engine, from, to)).length;
  return n;
}

/** Причины забоя привязаны к операции (`operationId`) и своей даты не имеют: уходят вместе с ней. */
async function slaughterOfClearedOps(engine: ClearEngine, from: string, to: string): Promise<LocalRecord[]> {
  const [opTarget] = CLEAR_TARGETS.livestock;
  const ops = recordsInPeriod(await engine.list(opTarget.entity), opTarget, from, to);
  if (!ops.length) return [];
  const ids = new Set(ops.map((o) => o.id));
  return (await engine.list('slaughterRecord')).filter((s) => !s.deleted && ids.has(String(s.data.operationId ?? '')));
}

/**
 * Чистит раздел за период. Возвращает число затронутых записей. Отметки табеля обнуляются (а не удаляются),
 * остальное уходит операциями удаления — сервер и телефон получают это обычной синхронизацией.
 */
export async function clearPeriod(engine: ClearEngine, section: ClearSection, from: string, to: string): Promise<number> {
  // Причины забоя ищутся до удаления операций: после него связь «забой → операция» уже не восстановить.
  const slaughters = section === 'livestock' ? await slaughterOfClearedOps(engine, from, to) : [];
  let touched = 0;
  for (const t of CLEAR_TARGETS[section]) {
    for (const r of recordsInPeriod(await engine.list(t.entity), t, from, to)) {
      if (t.mode === 'unset-hours') await engine.save(t.entity, r.id, { hours: null });
      else await engine.remove(t.entity, r.id);
      touched++;
    }
  }
  for (const s of slaughters) { await engine.remove('slaughterRecord', s.id); touched++; }
  return touched;
}
