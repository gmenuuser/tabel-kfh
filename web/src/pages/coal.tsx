import { useEffect, useMemo, useState } from 'react';
import { useApp, useRecords, useStatus } from '../app.tsx';
import type { LocalRecord } from '../lib/db.ts';
import { coalAmount, coalTotals, driverAccounts, driverDebt, parseRubles, parseTons, rubles, rublesField, tons, type Data } from '../lib/domain.ts';
import { stableId } from '../lib/ids.ts';
import { addDays, dayKey, dayStartISO, longDate, shortDate, timeText, todayKey } from '../lib/time.ts';

export const SETTINGS_ID = stableId('coalSettings');
const DEFAULT_LOCATIONS: [string, number][] = [['Новоцурухайтуй', 100000], ['Приаргунск', 120000]];
const driverName = (d: LocalRecord) => [d.data.lastName, d.data.firstName, d.data.middleName].filter(Boolean).join(' ');

/** The price and the owner's two villages, made once for the farm (stable ids: every device makes the same rows). */
function useCoalDefaults() {
  const { engine } = useApp();
  const status = useStatus();
  const settings = useRecords('coalSettings');
  const locations = useRecords('deliveryLocation');
  useEffect(() => {
    if (!status.lastSyncAt && status.online) return; // wait for the first sync to see what the farm already has
    if (!settings.length) engine.save('coalSettings', SETTINGS_ID, { pricePerTonKopecks: 650000 });
    if (!locations.length) {
      for (const [name, price] of DEFAULT_LOCATIONS) {
        engine.save('deliveryLocation', stableId(`deliveryLocation:${name}`), { name, deliveryPriceKopecks: price,
          driverPaymentKopecks: null, isActive: true, createdAt: new Date().toISOString() });
      }
    }
  }, [settings.length, locations.length, status.lastSyncAt, status.online, engine]);
  return { price: Number(settings[0]?.data.pricePerTonKopecks ?? 650000), locations: locations.filter((l) => l.data.isActive !== false) };
}

const QUICK = ['0,5', '1', '1,5', '2', '2,5', '3'];

export function CoalPage({ go }: { go: (r: string) => void }) {
  const { engine } = useApp();
  const { price, locations } = useCoalDefaults();
  const drivers = useRecords('driver').filter((d) => !d.data.isArchived);
  const sales = useRecords('coalSale');
  const payments = useRecords('driverPayment');
  const days = useRecords('coalWorkDay');
  const [tonsText, setTons] = useState('');
  const [locationId, setLocation] = useState<string | null>(null);
  const [vehicle, setVehicle] = useState<string | null>(null); // 'own' or a driver id
  const [comment, setComment] = useState('');
  const [saved, setSaved] = useState('');
  const [error, setError] = useState('');
  const [closing, setClosing] = useState(false);
  const today = todayKey();
  const dayId = stableId(`coalWorkDay:${today}`);
  const closed = days.some((d) => d.id === dayId && d.data.isClosed);
  const todaySales = sales.filter((s) => dayKey(String(s.data.saleDate)) === today)
    .sort((a, b) => String(b.data.saleDate).localeCompare(String(a.data.saleDate)));
  const totals = coalTotals(todaySales.map((s) => s.data));
  const kg = parseTons(tonsText);
  const location = locations.find((l) => l.id === locationId);
  const coal = coalAmount(kg ?? 0, price);
  const delivery = Number(location?.data.deliveryPriceKopecks ?? 0);
  const driver = drivers.find((d) => d.id === vehicle);
  const driverPay = driver ? Number(location?.data.driverPaymentKopecks ?? location?.data.deliveryPriceKopecks ?? 0) : 0;

  async function save() {
    const errors = [];
    if (!kg || kg <= 0) errors.push('Укажите количество угля, например 1,5');
    else if (kg > 100000) errors.push('Не больше 100 тонн в одной продаже');
    if (!location) errors.push('Выберите, куда везти');
    if (!vehicle) errors.push('Выберите машину: свою или водителя');
    if (errors.length) { setError(errors.join('. ')); return; }
    await engine.save('coalSale', null, {
      saleDate: new Date().toISOString(), kilograms: kg, locationNameSnapshot: location!.data.name,
      coalPricePerTonSnapshot: price, deliveryPriceSnapshot: delivery, coalAmount: coal, deliveryPrice: delivery,
      totalAmount: coal + delivery, vehicleType: driver ? 'hired' : 'own', driverId: driver?.id ?? null,
      driverNameSnapshot: driver ? driverName(driver) : '', driverPaymentAmount: driverPay, comment: comment.trim(),
      createdAt: new Date().toISOString(),
    });
    setSaved(`Сохранено: ${tons(kg!)} т, ${location!.data.name}, ${rubles(coal + delivery)}`);
    setTons(''); setLocation(null); setVehicle(null); setComment(''); setError('');
  }

  async function closeDay() {
    await engine.save('coalWorkDay', dayId, { date: dayStartISO(today), isClosed: true, closedAt: new Date().toISOString(),
      totalRevenue: totals.revenue, totalKilograms: totals.kilograms, totalDriverPayments: totals.driverPayments,
      ownVehicleDeliveryRevenue: totals.ownDelivery, createdAt: new Date().toISOString() });
    setClosing(false);
  }

  const names = new Map(drivers.map((d) => [d.id, driverName(d)]));
  const accounts = driverAccounts(sales.map((s) => s.data), payments.map((p) => p.data), names,
    dayStartISO(today), dayStartISO(addDays(today, 1)));

  return (
    <>
      <div className="row between">
        <h2>Продажа угля</h2>
        <div className="row"><button onClick={() => go('coal/drivers')}>Водители и выплаты</button><button onClick={() => go('coal/prices')}>Цены</button></div>
      </div>
      <section className="card stats">
        <div><span>Цена угля</span><strong>{rubles(price)} за тонну</strong></div>
        <div><span>Продаж сегодня</span><strong>{totals.sales}</strong></div>
        <div><span>Выручка за день</span><strong className="accent">{rubles(totals.revenue)}</strong></div>
      </section>

      {closed ? (
        <section className="card">
          <h3 className="accent">Рабочий день завершён</h3>
          <button onClick={() => engine.save('coalWorkDay', dayId, { isClosed: false })}>Открыть день для исправлений</button>
        </section>
      ) : (
        <section className="card form">
          <h3>Новая продажа</h3>
          <label>1. Сколько тонн<input className="huge" inputMode="decimal" value={tonsText} onChange={(e) => setTons(e.target.value)} placeholder="0" /></label>
          <div className="chips">{QUICK.map((q) => <button key={q} className={tonsText === q ? 'on' : ''} onClick={() => setTons(q)}>{q} т</button>)}</div>
          <div className="caption">2. Куда везти</div>
          <div className="choices">{[...locations].sort((a, b) => String(a.data.name).localeCompare(String(b.data.name), 'ru')).map((l) => (
            <button key={l.id} className={locationId === l.id ? 'on' : ''} onClick={() => setLocation(l.id)}>
              <strong>{String(l.data.name)}</strong><span>{rubles(Number(l.data.deliveryPriceKopecks))}</span>
            </button>
          ))}</div>
          <div className="caption">3. Чья машина</div>
          <div className="choices">
            <button className={vehicle === 'own' ? 'on own' : 'own'} onClick={() => setVehicle('own')}><strong>СВОЯ МАШИНА</strong><span>без оплаты водителю</span></button>
            {[...drivers].sort((a, b) => driverName(a).localeCompare(driverName(b), 'ru')).map((d) => (
              <button key={d.id} className={vehicle === d.id ? 'on hired' : 'hired'} onClick={() => setVehicle(d.id)}>
                <strong><span className="letter">{String(d.data.lastName ?? '?')[0]?.toUpperCase()}</span> {String(d.data.lastName)}</strong>
              </button>
            ))}
          </div>
          <label>Комментарий<input value={comment} onChange={(e) => setComment(e.target.value)} /></label>
          <div className="calc">
            <div><span>Уголь: {tons(kg ?? 0)} т × {rubles(price)}</span><span>{rubles(coal)}</span></div>
            <div><span>Доставка{location ? `: ${location.data.name}` : ''}</span><span>{rubles(delivery)}</span></div>
            {driver && <div className="hired-text"><span>Заработок водителя {driverName(driver)}</span><span>{rubles(driverPay)}</span></div>}
            <div className="total"><span>ИТОГО</span><span>{rubles(coal + delivery)}</span></div>
          </div>
          {error && <div className="error">{error}</div>}
          <button className="big" onClick={save}>СОХРАНИТЬ ПРОДАЖУ</button>
          {saved && <div className="note">{saved}</div>}
        </section>
      )}

      <h3>Продажи за сегодня</h3>
      {!todaySales.length && <p className="muted">Сегодня продаж пока нет.</p>}
      {todaySales.map((s) => (
        <section key={s.id} className={`card sale ${s.data.vehicleType}`}>
          <div className="row between"><span className="muted">{timeText(String(s.data.saleDate))}</span><strong>{rubles(Number(s.data.totalAmount))}</strong></div>
          <div className="name">{tons(Number(s.data.kilograms))} т · {String(s.data.locationNameSnapshot)}</div>
          <div>{s.data.vehicleType === 'hired' ? `Наёмная: ${s.data.driverNameSnapshot}` : 'Собственная машина КФХ'}</div>
          {!closed && <button className="link danger" onClick={() => { if (confirm('Отменить продажу?')) engine.remove('coalSale', s.id); }}>Отменить продажу</button>}
        </section>
      ))}

      {!closed && <button className="big orange" onClick={() => setClosing(true)}>ЗАВЕРШИТЬ РАБОЧИЙ ДЕНЬ</button>}
      {closing && (
        <div className="modal" onClick={() => setClosing(false)}>
          <div className="card form" onClick={(e) => e.stopPropagation()}>
            <h3>Итоги рабочего дня · {longDate(today)}</h3>
            <div className="calc">
              <div><span>Всего продано угля</span><span>{tons(totals.kilograms)} т</span></div>
              <div className="total"><span>Общая выручка</span><span>{rubles(totals.revenue)}</span></div>
              {accounts.map((a) => <div key={a.driverId} className="hired-text"><span>{a.name}: к выплате</span><span>{rubles(a.debtAfter)}</span></div>)}
              <div><span>Всего выплатить наёмным водителям</span><span>{rubles(accounts.reduce((s, a) => s + a.debtAfter, 0))}</span></div>
              <div><span>Доход от собственной машины</span><span>{rubles(totals.ownDelivery)}</span></div>
              <div className="total"><span>Остаток после выплат водителям</span><span>{rubles(totals.revenue - totals.driverPayments)}</span></div>
            </div>
            <p className="muted">Остаток — это ещё не прибыль. Деньги водителям этим не выплачиваются.</p>
            <button className="big" onClick={closeDay}>ПОДТВЕРДИТЬ ЗАВЕРШЕНИЕ ДНЯ</button>
          </div>
        </div>
      )}
    </>
  );
}

export function DriversPage() {
  const { engine } = useApp();
  const drivers = useRecords('driver');
  const sales = useRecords('coalSale');
  const payments = useRecords('driverPayment');
  const [adding, setAdding] = useState({ lastName: '', firstName: '', phone: '' });
  const [paying, setPaying] = useState<{ d: LocalRecord; amount: string; comment: string } | null>(null);
  const [error, setError] = useState('');
  const data = useMemo(() => ({ s: sales.map((x) => x.data), p: payments.map((x) => x.data) }), [sales, payments]);
  const list = [...drivers].sort((a, b) => Number(!!a.data.isArchived) - Number(!!b.data.isArchived) || driverName(a).localeCompare(driverName(b), 'ru'));

  async function pay() {
    if (!paying) return;
    const amount = parseRubles(paying.amount);
    const debt = driverDebt(paying.d.id, data.s, data.p).debt;
    if (!amount || amount <= 0) { setError('Сумма выплаты должна быть больше нуля'); return; }
    if (amount > debt) { setError(`Сумма больше долга перед водителем (${rubles(debt)})`); return; }
    await engine.save('driverPayment', null, { driverId: paying.d.id, driverNameSnapshot: driverName(paying.d), amount,
      paymentDate: new Date().toISOString(), comment: paying.comment.trim(), createdAt: new Date().toISOString() });
    setPaying(null); setError('');
  }

  return (
    <>
      <h2>Водители и выплаты</h2>
      <form className="card row" onSubmit={(e) => { e.preventDefault(); if (!adding.lastName.trim()) return;
        engine.save('driver', null, { lastName: adding.lastName.trim(), firstName: adding.firstName.trim(), middleName: '',
          phone: adding.phone.trim(), comment: '', isArchived: false, createdAt: new Date().toISOString() });
        setAdding({ lastName: '', firstName: '', phone: '' }); }}>
        <input placeholder="Фамилия" value={adding.lastName} onChange={(e) => setAdding({ ...adding, lastName: e.target.value })} required />
        <input placeholder="Имя" value={adding.firstName} onChange={(e) => setAdding({ ...adding, firstName: e.target.value })} />
        <input placeholder="Телефон" value={adding.phone} onChange={(e) => setAdding({ ...adding, phone: e.target.value })} />
        <button className="big">+ Добавить водителя</button>
      </form>
      {list.map((d) => {
        const b = driverDebt(d.id, data.s, data.p);
        return (
          <section key={d.id} className="card row between">
            <div>
              <div className="name">{driverName(d)} {d.data.isArchived ? <span className="muted">(архив)</span> : null}</div>
              <div className="muted">Доставок: {b.deliveries} · заработал {rubles(b.earned)} · получил {rubles(b.paid)}</div>
            </div>
            <div className="row">
              <strong className="hired-text">Долг {rubles(b.debt)}</strong>
              {b.debt > 0 && <button onClick={() => setPaying({ d, amount: rublesField(b.debt), comment: '' })}>Выплатить</button>}
              <button onClick={() => engine.save('driver', d.id, { isArchived: !d.data.isArchived })}>{d.data.isArchived ? 'Из архива' : 'В архив'}</button>
            </div>
          </section>
        );
      })}
      <h3>Выплаты</h3>
      {[...payments].sort((a, b) => String(b.data.paymentDate).localeCompare(String(a.data.paymentDate))).map((p) => (
        <div key={p.id} className="row between line">
          <span>{shortDate(dayKey(String(p.data.paymentDate)))} · {String(p.data.driverNameSnapshot)} {p.data.comment ? `· ${p.data.comment}` : ''}</span>
          <strong>{rubles(Number(p.data.amount))}</strong>
        </div>
      ))}
      {paying && (
        <div className="modal" onClick={() => setPaying(null)}>
          <div className="card form" onClick={(e) => e.stopPropagation()}>
            <h3>Выплата: {driverName(paying.d)}</h3>
            <label>Сумма, ₽<input inputMode="decimal" value={paying.amount} onChange={(e) => setPaying({ ...paying, amount: e.target.value })} /></label>
            <label>Комментарий<input value={paying.comment} onChange={(e) => setPaying({ ...paying, comment: e.target.value })} /></label>
            {error && <div className="error">{error}</div>}
            <button className="big" onClick={pay}>Выплатить</button>
          </div>
        </div>
      )}
    </>
  );
}

export function PricesPage() {
  const { engine } = useApp();
  const { price } = useCoalDefaults();
  const locations = useRecords('deliveryLocation').filter((l) => l.data.isActive !== false);
  const [priceText, setPrice] = useState('');
  const [add, setAdd] = useState({ name: '', delivery: '', driver: '' });
  const [error, setError] = useState('');
  useEffect(() => { setPrice(rublesField(price)); }, [price]);

  return (
    <>
      <h2>Цены и населённые пункты</h2>
      <section className="card row">
        <label className="inline">Цена угля за тонну, ₽<input inputMode="decimal" value={priceText} onChange={(e) => setPrice(e.target.value)} /></label>
        <button onClick={() => { const v = parseRubles(priceText); if (v && v > 0) engine.save('coalSettings', SETTINGS_ID, { pricePerTonKopecks: v }); }}>Сохранить цену</button>
      </section>
      <p className="muted">Новая цена действует для новых продаж; сохранённые продажи остаются по своим ценам.</p>
      {locations.map((l) => (
        <section key={l.id} className="card row between">
          <strong>{String(l.data.name)}</strong>
          <span>доставка {rubles(Number(l.data.deliveryPriceKopecks))}{l.data.driverPaymentKopecks != null ? ` · водителю ${rubles(Number(l.data.driverPaymentKopecks))}` : ''}</span>
          <button className="danger" onClick={() => { if (confirm(`Удалить «${l.data.name}»? Прошлые продажи сохранят название и цены.`)) engine.save('deliveryLocation', l.id, { isActive: false }); }}>Удалить</button>
        </section>
      ))}
      <form className="card row" onSubmit={(e) => {
        e.preventDefault();
        const d = parseRubles(add.delivery);
        const dr = add.driver.trim() ? parseRubles(add.driver) : null;
        if (!add.name.trim() || d === null || (add.driver.trim() && dr === null)) { setError('Укажите название и цену доставки'); return; }
        const existing = findLocation(add.name.trim(), locations);
        engine.save('deliveryLocation', existing ?? stableId(`deliveryLocation:${add.name.trim()}`), { name: add.name.trim(),
          deliveryPriceKopecks: d, driverPaymentKopecks: dr, isActive: true, createdAt: new Date().toISOString() });
        setAdd({ name: '', delivery: '', driver: '' }); setError('');
      }}>
        <input placeholder="Населённый пункт" value={add.name} onChange={(e) => setAdd({ ...add, name: e.target.value })} />
        <input placeholder="Доставка, ₽" inputMode="decimal" value={add.delivery} onChange={(e) => setAdd({ ...add, delivery: e.target.value })} />
        <input placeholder="Водителю, ₽ (если иначе)" inputMode="decimal" value={add.driver} onChange={(e) => setAdd({ ...add, driver: e.target.value })} />
        <button className="big">Добавить</button>
      </form>
      {error && <div className="error">{error}</div>}
    </>
  );
}

function findLocation(name: string, list: LocalRecord[]): string | null {
  return list.find((l) => String(l.data.name).toLowerCase() === name.toLowerCase())?.id ?? null;
}

export type { Data };
