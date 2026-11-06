import { useMemo, useState } from 'react';
import { useApp, useRecords } from '../app.tsx';
import type { LocalRecord } from '../lib/db.ts';
import { HOURS, hoursTotals } from '../lib/domain.ts';
import { stableId } from '../lib/ids.ts';
import { MONTHS, WEEKDAYS_SHORT, addDays, dayKey, dayStartISO, daysInMonth, longDate, todayKey, weekday } from '../lib/time.ts';
import { xlsx, download, type Cell } from '../lib/xlsx.ts';

export const fullName = (e: LocalRecord) =>
  [e.data.lastName, e.data.firstName, e.data.patronymic].filter(Boolean).join(' ');

export const shortName = (e: LocalRecord) => {
  const [last, ...rest] = fullName(e).split(' ');
  return [last, ...rest.map((p) => `${p[0]}.`)].join(' ');
};

/** Employed on the farm day `key`: hired on or before it, not dismissed before it. */
export function employedOn(e: LocalRecord, key: string): boolean {
  const hire = e.data.hireDate ? dayKey(String(e.data.hireDate)) : '0000-00-00';
  const dismissal = e.data.dismissalDate ? dayKey(String(e.data.dismissalDate)) : '9999-99-99';
  return hire <= key && key <= dismissal;
}

export const markId = (employeeId: string, key: string) => stableId(`hoursMark:${employeeId}:${key}`);

/** hoursMark records by «employeeId:day»; a mark with hours null has been removed. */
function markIndex(marks: LocalRecord[]) {
  const m = new Map<string, number>();
  for (const r of marks) if (r.data.hours !== null && r.data.hours !== undefined) m.set(`${r.data.employeeId}:${dayKey(String(r.data.day))}`, Number(r.data.hours));
  return m;
}

export function useHours() {
  const { engine } = useApp();
  return async (employeeId: string, key: string, hours: number | null) => {
    await engine.save('hoursMark', markId(employeeId, key), { employeeId, day: dayStartISO(key), hours });
  };
}

const hoursClass = (h: number | undefined) => (h === undefined ? '' : `h${h}`);

/** «Табель работников»: the day and four buttons per person. */
export function TimesheetPage({ go }: { go: (r: string) => void }) {
  const employees = useRecords('employee');
  const marks = useRecords('hoursMark');
  const setHours = useHours();
  const [day, setDay] = useState(todayKey());
  const [search, setSearch] = useState('');
  const today = todayKey();
  const index = useMemo(() => markIndex(marks), [marks]);
  const people = employees.filter((e) => !e.data.isArchived && employedOn(e, day))
    .sort((a, b) => fullName(a).localeCompare(fullName(b), 'ru'))
    .filter((e) => !search.trim() || fullName(e).toLowerCase().includes(search.trim().toLowerCase()));
  const marked = people.filter((e) => index.has(`${e.id}:${day}`));
  const total = marked.reduce((s, e) => s + (index.get(`${e.id}:${day}`) ?? 0), 0);

  return (
    <>
      <div className="row between">
        <h2>Табель работников</h2>
        <div className="row">
          <button onClick={() => go('timesheet/month')}>Табель за месяц</button>
          <button onClick={() => go('employees')}>Сотрудники</button>
        </div>
      </div>
      <section className="card">
        <h3 className="accent">{longDate(day)}</h3>
        <div className="segmented">
          {[['Вчера', -1], ['Сегодня', 0], ['Завтра', 1]].map(([t, n]) => {
            const k = addDays(today, n as number);
            return <button key={t as string} className={day === k ? 'active' : ''} onClick={() => setDay(k)}>{t}</button>;
          })}
        </div>
        <label className="inline">Другая дата <input type="date" value={day} onChange={(e) => e.target.value && setDay(e.target.value)} /></label>
      </section>
      {people.length > 0 && (
        <section className="card row between">
          <strong>Отмечено: {marked.length} из {people.length}</strong>
          <strong className="accent">Часов: {total}</strong>
          {marked.length < people.length && (
            <button onClick={() => people.filter((e) => !index.has(`${e.id}:${day}`)).forEach((e) => setHours(e.id, day, 8))}>
              Всем неотмеченным — 8 ч
            </button>
          )}
        </section>
      )}
      {employees.length > 5 && <input className="search" placeholder="Поиск по фамилии" value={search} onChange={(e) => setSearch(e.target.value)} />}
      {!people.length && <p className="muted">В этот день нет работающих сотрудников.</p>}
      {people.map((e) => {
        const h = index.get(`${e.id}:${day}`);
        return (
          <section key={e.id} className="card person">
            <div>
              <div className="name">{fullName(e)}</div>
              <div className={`status ${hoursClass(h)}`}>{h === undefined ? 'Не отмечен' : `Отмечено: ${h} ч`}</div>
            </div>
            <div className="hours">
              {HOURS.map((x) => (
                <button key={x} className={`hbtn h${x} ${h === x ? 'on' : ''}`} aria-pressed={h === x}
                        onClick={() => setHours(e.id, day, h === x ? null : x)}>{x} ч</button>
              ))}
            </div>
          </section>
        );
      })}
    </>
  );
}

/** «Табель за месяц»: employees by days, hours in the cells, totals; names and dates stay in place. */
export function MonthPage() {
  const { farm } = useApp();
  const employees = useRecords('employee');
  const marks = useRecords('hoursMark');
  const setHours = useHours();
  const now = todayKey();
  const [ym, setYm] = useState(() => localStorage.getItem('kfh.month') ?? now.slice(0, 7));
  const [editing, setEditing] = useState<{ employee: LocalRecord; key: string } | null>(null);
  const [y, m] = ym.split('-').map(Number);
  const days = daysInMonth(y, m);
  const keys = Array.from({ length: days }, (_, i) => `${ym}-${String(i + 1).padStart(2, '0')}`);
  const index = useMemo(() => markIndex(marks), [marks]);
  const rows = employees.filter((e) => keys.some((k) => employedOn(e, k)))
    .sort((a, b) => fullName(a).localeCompare(fullName(b), 'ru'))
    .map((e) => {
      const cells = keys.map((k) => (employedOn(e, k) ? index.get(`${e.id}:${k}`) ?? null : undefined));
      return { e, cells, t: hoursTotals(cells.filter((c): c is number => typeof c === 'number')) };
    });
  const farmT = hoursTotals(rows.flatMap((r) => r.cells.filter((c): c is number => typeof c === 'number')));
  const shift = (n: number) => {
    const d = new Date(Date.UTC(y, m - 1 + n, 1));
    const v = `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, '0')}`;
    localStorage.setItem('kfh.month', v);
    setYm(v);
  };
  const titles = ['Часов', 'Раб. дней', '0 ч', '4 ч', '8 ч', '12 ч'];
  const tv = (t: ReturnType<typeof hoursTotals>) => [t.hours, t.workedDays, t.zero, t.four, t.eight, t.twelve];

  function exportXlsx() {
    const out: Cell[][] = [
      ['ТАБЕЛЬ УЧЁТА РАБОЧЕГО ВРЕМЕНИ'], [farm.name], [`Месяц: ${MONTHS[m - 1]}    Год: ${y}`], [],
      ['№', 'ФИО', ...keys.map((_, i) => i + 1), ...titles],
      ...rows.map((r, i) => [i + 1, fullName(r.e), ...r.cells.map((c) => (typeof c === 'number' ? c : null)), ...tv(r.t)]),
      ['', 'Итого по хозяйству', ...keys.map(() => null), ...tv(farmT)],
      [], ['Обозначения: число — отработанные часы за день; пустая клетка — отметки нет (это не 0 часов).'],
      [], ['Руководитель КФХ: ___________________', farm.managerName], [], ['Ответственное лицо: ___________________'],
    ];
    download(`Табель_${MONTHS[m - 1]}_${y}.xlsx`, xlsx('Табель', out, [0, 4, 5 + rows.length], [4, 30, ...keys.map(() => 4), 7, 9, 5, 5, 5, 5]));
  }

  return (
    <>
      <div className="row between no-print">
        <button onClick={() => shift(-1)} aria-label="Предыдущий месяц">‹</button>
        <h2 className="accent">{MONTHS[m - 1].toUpperCase()} {y}</h2>
        <button onClick={() => shift(1)} aria-label="Следующий месяц">›</button>
      </div>
      <div className="print-only"><h2>ТАБЕЛЬ УЧЁТА РАБОЧЕГО ВРЕМЕНИ · {farm.name} · {MONTHS[m - 1]} {y}</h2></div>
      <input className="no-print" type="month" value={ym} onChange={(e) => { if (e.target.value) { localStorage.setItem('kfh.month', e.target.value); setYm(e.target.value); } }} />
      {!rows.length ? <p className="muted">В этом месяце нет работающих сотрудников.</p> : (
        <div className="grid-wrap">
          <table className="grid">
            <thead>
              <tr>
                <th className="sticky-col">Сотрудник</th>
                {keys.map((k, i) => <th key={k} className={[0, 6].includes(weekday(k)) ? 'weekend' : ''}>{i + 1}<small>{WEEKDAYS_SHORT[weekday(k)]}</small></th>)}
                {titles.map((t) => <th key={t} className="tot">{t}</th>)}
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.e.id}>
                  <th className="sticky-col">{shortName(r.e)}</th>
                  {r.cells.map((c, i) => (
                    <td key={keys[i]} className={c === undefined ? 'off' : `cell ${typeof c === 'number' ? `h${c}` : ''}`}
                        onClick={() => c !== undefined && setEditing({ employee: r.e, key: keys[i] })}
                        title={`${fullName(r.e)}, ${i + 1}`}>{typeof c === 'number' ? c : ''}</td>
                  ))}
                  {tv(r.t).map((v, i) => <td key={i} className="tot">{v}</td>)}
                </tr>
              ))}
              <tr className="total">
                <th className="sticky-col">Итого</th>
                {keys.map((k) => <td key={k} />)}
                {tv(farmT).map((v, i) => <td key={i} className="tot">{v}</td>)}
              </tr>
            </tbody>
          </table>
        </div>
      )}
      <section className="card">
        <h3>Итоги по хозяйству</h3>
        <p><strong>Всего отработано: {farmT.hours} ч</strong></p>
        <div className="legend">{HOURS.map((h) => <span key={h} className={`chip h${h}`}>Отметок {h} ч: {tv(farmT)[2 + HOURS.indexOf(h)]}</span>)}</div>
        <p className="muted">Пустая клетка — отметки нет (это не 0 часов). Нажмите на клетку, чтобы изменить день.</p>
      </section>
      <div className="row no-print">
        <button className="big" onClick={exportXlsx}>Экспорт в Excel</button>
        <button className="big secondary" onClick={() => window.print()}>Распечатать / PDF</button>
      </div>
      <div className="print-only signatures">
        <p>Руководитель КФХ: ___________________ {farm.managerName}</p>
        <p>Ответственное лицо: ___________________</p>
      </div>
      {editing && (
        <div className="modal" onClick={() => setEditing(null)}>
          <div className="card" onClick={(e) => e.stopPropagation()}>
            <h3>{fullName(editing.employee)}</h3>
            <p>{longDate(editing.key)}</p>
            <div className="hours">
              {HOURS.map((h) => (
                <button key={h} className={`hbtn h${h}`} onClick={() => { setHours(editing.employee.id, editing.key, h); setEditing(null); }}>{h} ч</button>
              ))}
            </div>
            <div className="row">
              <button className="danger" onClick={() => { setHours(editing.employee.id, editing.key, null); setEditing(null); }}>Снять отметку</button>
              <button onClick={() => setEditing(null)}>Отмена</button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
