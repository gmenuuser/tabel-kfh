// «Очистка периода» на сайте: владелец выбирает день или месяц и стирает записи раздела. Месяц требует
// набрать слово «очистить» — промахнуться датой на телефоне дорого, а отменить очистку нельзя.

import { useState } from 'react';
import { useApp, useRecords } from '../app.tsx';
import type { LocalRecord } from '../lib/db.ts';
import {
  CLEAR_TARGETS, CLEAR_TITLES, clearPeriod, dayRange, monthRange, recordsInPeriod, type ClearSection,
} from '../lib/clear.ts';
import { longDate, todayKey } from '../lib/time.ts';

const WORD = 'очистить';

export function ClearPeriod({ section }: { section: ClearSection }) {
  const { engine, session } = useApp();
  // Хуки идут всегда в одном порядке: раздел выбирает, какие из этих записей считать.
  const marks = useRecords('hoursMark');
  const sales = useRecords('coalSale');
  const days = useRecords('coalWorkDay');
  const payments = useRecords('driverPayment');
  const ops = useRecords('livestockOperation');
  const slaughters = useRecords('slaughterRecord');
  const calcs = useRecords('agroCalculation');
  const [mode, setMode] = useState<'day' | 'month'>('month');
  const [day, setDay] = useState(todayKey());
  const [month, setMonth] = useState(todayKey().slice(0, 7));
  const [typed, setTyped] = useState('');
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);

  const owner = session.role === 'owner';
  const [from, to] = mode === 'day' ? dayRange(day) : monthRange(month);
  const rows: Record<string, LocalRecord[]> = {
    hoursMark: marks, coalSale: sales, coalWorkDay: days, driverPayment: payments,
    livestockOperation: ops, slaughterRecord: slaughters, agroCalculation: calcs,
  };
  let count = 0;
  for (const t of CLEAR_TARGETS[section]) count += recordsInPeriod(rows[t.entity] ?? [], t, from, to).length;
  if (section === 'livestock') {
    const cleared = new Set(recordsInPeriod(ops, CLEAR_TARGETS.livestock[0], from, to).map((o) => o.id));
    count += slaughters.filter((s) => cleared.has(String(s.data.operationId ?? ''))).length;
  }

  if (!owner) return null;

  const period = mode === 'day' ? longDate(day) : month;
  const ready = count > 0 && (mode === 'day' || typed.trim().toLowerCase() === WORD);

  async function clear() {
    if (!ready || busy) return;
    if (mode === 'day' && !confirm(`Очистить ${CLEAR_TITLES[section]} за ${period}? Отменить это будет нельзя.`)) return;
    setBusy(true);
    setNote('');
    try {
      const n = await clearPeriod(engine, section, from, to);
      setNote(`Удалено записей: ${n}. Телефоны получат это при следующей синхронизации.`);
      setTyped('');
    } catch (e) {
      setNote(`Не удалось очистить: ${(e as Error).message}`);
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="card">
      <h3 className="accent">Очистка {CLEAR_TITLES[section]}</h3>
      <div className="segmented">
        <button className={mode === 'day' ? 'active' : ''} onClick={() => { setMode('day'); setNote(''); }}>День</button>
        <button className={mode === 'month' ? 'active' : ''} onClick={() => { setMode('month'); setNote(''); }}>Месяц</button>
      </div>
      {mode === 'day'
        ? <label className="inline">Дата <input type="date" value={day} onChange={(e) => { if (e.target.value) { setDay(e.target.value); setNote(''); } }} /></label>
        : <label className="inline">Месяц <input type="month" value={month} onChange={(e) => { if (e.target.value) { setMonth(e.target.value); setNote(''); } }} /></label>}
      <p className="muted">
        {count > 0 ? `Будет удалено записей: ${count}` : 'За этот период записей нет'}
      </p>
      {mode === 'month' && count > 0 && (
        <label className="inline">
          Наберите «{WORD}», чтобы подтвердить
          <input value={typed} onChange={(e) => setTyped(e.target.value)} placeholder={WORD} />
        </label>
      )}
      <div className="row">
        <button className="danger" disabled={!ready || busy} onClick={clear}>
          {busy ? 'Чищу…' : `Очистить ${mode === 'day' ? 'день' : 'месяц'}`}
        </button>
      </div>
      {note && <p className="muted">{note}</p>}
    </section>
  );
}
