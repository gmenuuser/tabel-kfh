import { useState } from 'react';
import { useApp, useRecords } from '../app.tsx';
import { SPECIES, OPERATION_KINDS, coalTotals, driverAccounts, herdBalances, rubles, tons, delta, type Data } from '../lib/domain.ts';
import { MONTHS, addDays, dayKey, dayStartISO, daysInMonth, shortDate, timeText, todayKey } from '../lib/time.ts';
import { download, xlsx, type Cell } from '../lib/xlsx.ts';

type Report = 'coal' | 'payments' | 'herd';
const kop = (v: number) => v / 100;

/** «Отчёты»: coal sales, payments to drivers, the herd — on screen, as Excel, and printed (or saved as PDF). */
export function ReportsPage() {
  const { farm } = useApp();
  const sales = useRecords('coalSale');
  const payments = useRecords('driverPayment');
  const drivers = useRecords('driver');
  const ops = useRecords('livestockOperation');
  const categories = useRecords('livestockCategory');
  const [report, setReport] = useState<Report>('coal');
  const [period, setPeriod] = useState<'day' | 'month'>('day');
  const [day, setDay] = useState(todayKey());
  const start = period === 'day' ? day : `${day.slice(0, 7)}-01`;
  const end = period === 'day' ? addDays(day, 1)
    : addDays(start, daysInMonth(Number(day.slice(0, 4)), Number(day.slice(5, 7))));
  const [startISO, endISO] = [dayStartISO(start), dayStartISO(end)];
  const title = period === 'day' ? shortDate(day) : `${MONTHS[Number(day.slice(5, 7)) - 1]} ${day.slice(0, 4)}`;
  const inPeriod = sales.map((s) => s.data).filter((s) => String(s.saleDate) >= startISO && String(s.saleDate) < endISO)
    .sort((a, b) => String(a.saleDate).localeCompare(String(b.saleDate)));
  const t = coalTotals(inPeriod);
  const names = new Map(drivers.map((d) => [d.id, [d.data.lastName, d.data.firstName].filter(Boolean).join(' ')]));
  const accounts = driverAccounts(sales.map((s) => s.data), payments.map((p) => p.data), names, startISO, endISO);
  const periodPayments = payments.map((p) => p.data).filter((p) => String(p.paymentDate) >= startISO && String(p.paymentDate) < endISO);
  const opsData = ops.map((o) => o.data);

  function coalXlsx() {
    const rows: Cell[][] = [[`ОТЧЁТ ПО ПРОДАЖЕ УГЛЯ · ${title}`], [farm.name], [],
      ['№', 'Дата, время', 'Тонн', 'Населённый пункт', 'Машина', 'Водитель', 'Уголь, ₽', 'Доставка, ₽', 'Итого, ₽'],
      ...inPeriod.map((s, i): Cell[] => [i + 1, `${shortDate(dayKey(String(s.saleDate)))} ${timeText(String(s.saleDate))}`, Number(s.kilograms) / 1000,
        String(s.locationNameSnapshot ?? ''), s.vehicleType === 'hired' ? 'Наёмная' : 'Своя', String(s.driverNameSnapshot || '—'),
        kop(Number(s.coalAmount)), kop(Number(s.deliveryPrice)), kop(Number(s.totalAmount))]),
      ['', 'Итого', t.kilograms / 1000, `${t.sales} продаж`, '', '', kop(t.coal), kop(t.delivery), kop(t.revenue)], [],
      ['А. Общая выручка хозяйства, ₽', kop(t.revenue)], ['Б. Стоимость проданного угля, ₽', kop(t.coal)],
      ['В. Общая стоимость доставки, ₽', kop(t.delivery)], ['Г. Выплаты наёмным водителям (начислено), ₽', kop(t.driverPayments)],
      ['Д. Доход от собственной машины, ₽', kop(t.ownDelivery)], ['Е. Остаток после выплат водителям, ₽', kop(t.revenue - t.driverPayments)],
      ['Остаток после выплат — не чистая прибыль: себестоимость и прочие расходы не учтены.'], [],
      ['Водитель', 'Доставок', 'Долг на начало, ₽', 'Начислено, ₽', 'Выплачено, ₽', 'К выплате, ₽'],
      ...accounts.map((a): Cell[] => [a.name, a.deliveries, kop(a.debtBefore), kop(a.accrued), kop(a.paid), kop(a.debtAfter)]),
      [], ['Руководитель КФХ: ___________________', farm.managerName]];
    download(`Уголь_${title.replace(/\s/g, '_')}.xlsx`, xlsx('Отчёт', rows, [0, 3, 4 + inPeriod.length, 14 + inPeriod.length], [5, 18, 8, 22, 10, 22, 12, 12, 12]));
  }

  function herdXlsx() {
    const rows: Cell[][] = [[`ДВИЖЕНИЕ ПОГОЛОВЬЯ · ${title}`], [farm.name], [],
      ['Вид', 'Категория', 'На начало', 'Поступило', 'Приплод', 'Перевод +', 'Перевод −', 'Забой', 'Продажа', 'Падёж', 'Прочее', 'На конец']];
    for (const r of movement()) rows.push([r.species, r.name, r.start, r.purchase, r.birth, r.tin, r.tout, r.slaughter, r.sale, r.death, r.other, r.end]);
    rows.push([], ['Руководитель КФХ: ___________________', farm.managerName]);
    download(`Движение_поголовья_${title.replace(/\s/g, '_')}.xlsx`, xlsx('Поголовье', rows, [0, 3], [8, 18, 10, 10, 9, 10, 10, 8, 9, 8, 8, 10]));
  }

  function movement() {
    const before = herdBalances(opsData, startISO);
    return categories.map((c) => {
      const r = { species: SPECIES.find((s) => s.id === c.data.species)?.title ?? '', name: String(c.data.name), start: before.get(c.id) ?? 0,
                  purchase: 0, birth: 0, tin: 0, tout: 0, slaughter: 0, sale: 0, death: 0, other: 0, end: 0 };
      for (const o of opsData) {
        if (String(o.date) < startISO || String(o.date) >= endISO || delta(o, c.id) === 0) continue;
        const n = Number(o.heads);
        switch (o.kind) {
          case 'initial': case 'purchase': r.purchase += n; break;
          case 'birth': r.birth += n; break;
          case 'transfer': if (o.categoryId === c.id) r.tout += n; else r.tin += n; break;
          case 'slaughter': r.slaughter += n; break;
          case 'sale': r.sale += n; break;
          case 'death': r.death += n; break;
          default: r.other += n;
        }
      }
      r.end = r.start + r.purchase + r.birth + r.tin - r.tout - r.slaughter - r.sale - r.death - r.other;
      return r;
    }).filter((r) => r.start || r.end || r.purchase || r.birth || r.slaughter || r.sale || r.death || r.other || r.tin);
  }

  return (
    <>
      <div className="no-print">
        <h2>Отчёты</h2>
        <div className="segmented">
          {([['coal', 'Продажи угля'], ['payments', 'Выплаты водителям'], ['herd', 'Скот']] as [Report, string][]).map(([k, v]) =>
            <button key={k} className={report === k ? 'active' : ''} onClick={() => setReport(k)}>{v}</button>)}
          <a className="button" href="#/timesheet/month">Табель работников</a>
        </div>
        <div className="row">
          <div className="segmented"><button className={period === 'day' ? 'active' : ''} onClick={() => setPeriod('day')}>За день</button>
            <button className={period === 'month' ? 'active' : ''} onClick={() => setPeriod('month')}>За месяц</button></div>
          <input type="date" value={day} onChange={(e) => e.target.value && setDay(e.target.value)} />
        </div>
      </div>
      <h2>{report === 'coal' ? 'Отчёт по продаже угля' : report === 'payments' ? 'Выплаты водителям' : 'Движение поголовья'} · {title}</h2>
      <p className="muted">{farm.name}</p>

      {report === 'coal' && (
        <>
          <table className="report"><thead><tr><th>№</th><th>Время</th><th>Тонн</th><th>Пункт</th><th>Машина</th><th>Водитель</th><th>Уголь</th><th>Доставка</th><th>Итого</th></tr></thead>
            <tbody>{inPeriod.map((s, i) => <tr key={i}><td>{i + 1}</td><td>{period === 'day' ? timeText(String(s.saleDate)) : shortDate(dayKey(String(s.saleDate)))}</td>
              <td>{tons(Number(s.kilograms))}</td><td>{String(s.locationNameSnapshot ?? '')}</td><td>{s.vehicleType === 'hired' ? 'Наёмная' : 'Своя'}</td>
              <td>{String(s.driverNameSnapshot || '—')}</td><td>{rubles(Number(s.coalAmount))}</td><td>{rubles(Number(s.deliveryPrice))}</td><td>{rubles(Number(s.totalAmount))}</td></tr>)}
              <tr className="total"><td /><td>Итого</td><td>{tons(t.kilograms)}</td><td>{t.sales} продаж</td><td /><td /><td>{rubles(t.coal)}</td><td>{rubles(t.delivery)}</td><td>{rubles(t.revenue)}</td></tr>
            </tbody></table>
          <Totals t={t} />
          <Accounts accounts={accounts} />
          <div className="row no-print"><button className="big" onClick={coalXlsx}>Экспорт в Excel</button><button className="big secondary" onClick={() => window.print()}>Распечатать / PDF</button></div>
        </>
      )}
      {report === 'payments' && (
        <>
          <Accounts accounts={accounts} />
          <table className="report"><thead><tr><th>Дата</th><th>Водитель</th><th>Сумма</th><th>Комментарий</th></tr></thead>
            <tbody>{periodPayments.map((p: Data, i) => <tr key={i}><td>{shortDate(dayKey(String(p.paymentDate)))}</td><td>{String(p.driverNameSnapshot)}</td><td>{rubles(Number(p.amount))}</td><td>{String(p.comment ?? '')}</td></tr>)}</tbody></table>
          <div className="row no-print"><button className="big" onClick={() => download(`Выплаты_${title.replace(/\s/g, '_')}.xlsx`, xlsx('Выплаты', [
            [`ВЫПЛАТЫ ВОДИТЕЛЯМ · ${title}`], [farm.name], [], ['Водитель', 'Доставок', 'Долг на начало, ₽', 'Начислено, ₽', 'Выплачено, ₽', 'К выплате, ₽'],
            ...accounts.map((a): Cell[] => [a.name, a.deliveries, kop(a.debtBefore), kop(a.accrued), kop(a.paid), kop(a.debtAfter)]), [],
            ['Дата', 'Водитель', 'Сумма, ₽', 'Комментарий'], ...periodPayments.map((p): Cell[] => [shortDate(dayKey(String(p.paymentDate))), String(p.driverNameSnapshot), kop(Number(p.amount)), String(p.comment ?? '')])],
            [0, 3], [24, 10, 14, 14, 14, 14]))}>Экспорт в Excel</button><button className="big secondary" onClick={() => window.print()}>Распечатать / PDF</button></div>
        </>
      )}
      {report === 'herd' && (
        <>
          <table className="report"><thead><tr><th>Вид</th><th>Категория</th><th>На начало</th><th>Поступило</th><th>Приплод</th><th>Перевод +</th><th>Перевод −</th><th>Забой</th><th>Продажа</th><th>Падёж</th><th>Прочее</th><th>На конец</th></tr></thead>
            <tbody>{movement().map((r, i) => <tr key={i}><td>{r.species}</td><td>{r.name}</td><td>{r.start}</td><td>{r.purchase}</td><td>{r.birth}</td><td>{r.tin}</td><td>{r.tout}</td><td>{r.slaughter}</td><td>{r.sale}</td><td>{r.death}</td><td>{r.other}</td><td><strong>{r.end}</strong></td></tr>)}</tbody></table>
          <p className="muted">Операций за период: {opsData.filter((o) => String(o.date) >= startISO && String(o.date) < endISO).length}. {Object.values(OPERATION_KINDS).length} видов операций учитываются.</p>
          <div className="row no-print"><button className="big" onClick={herdXlsx}>Экспорт в Excel</button><button className="big secondary" onClick={() => window.print()}>Распечатать / PDF</button></div>
        </>
      )}
      <div className="print-only signatures"><p>Руководитель КФХ: ___________________ {farm.managerName}</p></div>
    </>
  );
}

function Totals({ t }: { t: ReturnType<typeof coalTotals> }) {
  return (
    <section className="card calc">
      <div className="total"><span>А. Общая выручка хозяйства</span><span>{rubles(t.revenue)}</span></div>
      <div><span>Б. Стоимость проданного угля</span><span>{rubles(t.coal)}</span></div>
      <div><span>В. Общая стоимость доставки</span><span>{rubles(t.delivery)}</span></div>
      <div className="hired-text"><span>Г. Выплаты наёмным водителям</span><span>{rubles(t.driverPayments)}</span></div>
      <div><span>Д. Доход от собственной машины</span><span>{rubles(t.ownDelivery)}</span></div>
      <div className="total"><span>Е. Остаток после выплат водителям</span><span>{rubles(t.revenue - t.driverPayments)}</span></div>
      <p className="muted">Остаток — не чистая прибыль: себестоимость угля и прочие расходы не учтены.</p>
    </section>
  );
}

function Accounts({ accounts }: { accounts: ReturnType<typeof driverAccounts> }) {
  if (!accounts.length) return <p className="muted">Начислений наёмным водителям нет.</p>;
  return (
    <table className="report"><thead><tr><th>Водитель</th><th>Доставок</th><th>Долг на начало</th><th>Начислено</th><th>Выплачено</th><th>К выплате</th></tr></thead>
      <tbody>{accounts.map((a) => <tr key={a.driverId}><td>{a.name}</td><td>{a.deliveries}</td><td>{rubles(a.debtBefore)}</td><td>{rubles(a.accrued)}</td><td>{rubles(a.paid)}</td><td><strong>{rubles(a.debtAfter)}</strong></td></tr>)}
        <tr className="total"><td>ИТОГО К ВЫПЛАТЕ</td><td>{accounts.reduce((s, a) => s + a.deliveries, 0)}</td><td /><td /><td /><td>{rubles(accounts.reduce((s, a) => s + a.debtAfter, 0))}</td></tr></tbody></table>
  );
}
