import { useApp, useRecords } from '../app.tsx';
import { coalTotals, herdBalances, rubles, plural } from '../lib/domain.ts';
import { dayKey, longDate, todayKey } from '../lib/time.ts';

/** Главная панель: today at a glance and the large entries. */
export function HomePage({ go }: { go: (r: string) => void }) {
  const { farm } = useApp();
  const employees = useRecords('employee');
  const marks = useRecords('hoursMark');
  const sales = useRecords('coalSale');
  const ops = useRecords('livestockOperation');
  const today = todayKey();
  const active = employees.filter((e) => !e.data.isArchived);
  const todayMarks = marks.filter((m) => dayKey(String(m.data.day)) === today);
  const worked = todayMarks.filter((m) => Number(m.data.hours) > 0).length;
  const todaySales = coalTotals(sales.filter((s) => dayKey(String(s.data.saleDate)) === today).map((s) => s.data));
  const herd = [...herdBalances(ops.map((o) => o.data)).values()].reduce((a, b) => a + b, 0);
  const tiles: [string, string, string, string][] = [
    ['timesheet', 'ТАБЕЛЬ РАБОТНИКОВ', 'Часы за день, табель за месяц, сотрудники', 'green'],
    ['coal', 'ПРОДАЖА УГЛЯ', 'Продажи, водители, итоги дня', 'orange'],
    ['livestock', 'СКОТ И ЗАБОЙ', 'Поголовье, забой, приплод, история', 'brown'],
    ['agro', 'АГРОНОМИЧЕСКИЕ КАЛЬКУЛЯТОРЫ', 'Посев, удобрения, опрыскивание и другие', 'blue'],
    ['reports', 'ОТЧЁТЫ', 'Табель, продажи угля, выплаты, скот — Excel и печать', 'gray'],
  ];
  return (
    <>
      <section className="card hero">
        <h1>{farm.name}</h1>
        <div className="subtitle">{longDate(today)}</div>
        <div className="muted">{farm.region}</div>
        <ul className="facts">
          <li>{active.length ? `Отработали сегодня: ${worked} из ${active.length}` : 'Сотрудников пока нет'}</li>
          <li>{todaySales.sales ? `Уголь: ${todaySales.sales} ${plural(todaySales.sales, 'продажа', 'продажи', 'продаж')} на ${rubles(todaySales.revenue)}` : 'Продаж угля сегодня нет'}</li>
          {herd > 0 && <li>Скот: {herd} {plural(herd, 'голова', 'головы', 'голов')}</li>}
        </ul>
      </section>
      <div className="tiles">
        {tiles.map(([id, title, sub, color]) => (
          <button key={id} className={`tile ${color}`} onClick={() => go(id)}>
            <span className="tile-title">{title}</span>
            <span className="tile-sub">{sub}</span>
          </button>
        ))}
      </div>
    </>
  );
}
