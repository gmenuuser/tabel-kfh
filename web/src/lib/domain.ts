// The farm's rules, the same as on the iPhone (CoalMath, CoalReport, LivestockLedger, HoursTotals):
// money in kopecks, mass in kilograms, half-up rounding to a kopeck, the herd computed from operations.

export type Data = Record<string, unknown>;

// MARK: Money and tons

export const NBSP = ' ';

function grouped(n: number): string {
  const s = String(Math.trunc(Math.abs(n)));
  let out = '';
  for (let i = 0; i < s.length; i += 1) {
    if (i > 0 && (s.length - i) % 3 === 0) out += NBSP;
    out += s[i];
  }
  return out;
}

/** «14 000 ₽», «16 250,50 ₽». */
export function rubles(kopecks: number): string {
  const sign = kopecks < 0 ? '−' : '';
  const v = Math.abs(kopecks);
  const cents = v % 100;
  return `${sign}${grouped(Math.floor(v / 100))}${cents ? ',' + String(cents).padStart(2, '0') : ''}${NBSP}₽`;
}

/** «6500», «1200,50» for an input field. */
export function rublesField(kopecks: number): string {
  const cents = kopecks % 100;
  return `${Math.floor(kopecks / 100)}${cents ? ',' + String(cents).padStart(2, '0') : ''}`;
}

/** «2», «2,5», «0,125». */
export function tons(kg: number): string {
  let frac = String(Math.abs(kg) % 1000).padStart(3, '0');
  while (frac.endsWith('0')) frac = frac.slice(0, -1);
  return `${kg < 0 ? '−' : ''}${grouped(Math.floor(Math.abs(kg) / 1000))}${frac ? ',' + frac : ''}`;
}

function parseFixed(text: string, decimals: number): number | null {
  const c = text.replace(/[\s  ]/g, '').replace(',', '.');
  if (!c || !/^\d*\.?\d*$/.test(c) || c === '.') return null;
  const [w, f = ''] = c.split('.');
  if (f.length > decimals || w.length > 12) return null;
  return Number(w || '0') * 10 ** decimals + Number((f + '0'.repeat(decimals)).slice(0, decimals) || '0');
}

export const parseTons = (t: string) => parseFixed(t, 3);
export const parseRubles = (t: string) => parseFixed(t, 2);

/** Tons × price per ton, half up to a kopeck. */
export function coalAmount(kg: number, pricePerTon: number): number {
  if (kg <= 0 || pricePerTon <= 0) return 0;
  return Math.floor((kg * pricePerTon + 500) / 1000);
}

export const plural = (n: number, one: string, few: string, many: string) => {
  const t = Math.abs(n) % 100, u = Math.abs(n) % 10;
  if (t >= 11 && t <= 14) return many;
  if (u === 1) return one;
  if (u >= 2 && u <= 4) return few;
  return many;
};

// MARK: Coal

export interface CoalTotals {
  sales: number; kilograms: number; revenue: number; coal: number; delivery: number;
  driverPayments: number; ownDelivery: number; hired: number; own: number;
}

export function coalTotals(sales: Data[]): CoalTotals {
  const t: CoalTotals = { sales: 0, kilograms: 0, revenue: 0, coal: 0, delivery: 0, driverPayments: 0, ownDelivery: 0, hired: 0, own: 0 };
  for (const s of sales) {
    t.sales += 1;
    t.kilograms += Number(s.kilograms ?? 0);
    t.revenue += Number(s.totalAmount ?? 0);
    t.coal += Number(s.coalAmount ?? 0);
    t.delivery += Number(s.deliveryPrice ?? 0);
    if (s.vehicleType === 'hired') { t.hired += 1; t.driverPayments += Number(s.driverPaymentAmount ?? 0); }
    else { t.own += 1; t.ownDelivery += Number(s.deliveryPrice ?? 0); }
  }
  return t;
}

export interface DriverAccount { driverId: string; name: string; debtBefore: number; deliveries: number; accrued: number; paid: number; debtAfter: number }

/** What the farm owes each hired driver over [start, end): debt carried in + accrued − paid (ISO instants). */
export function driverAccounts(sales: Data[], payments: Data[], names: Map<string, string>, start: string, end: string): DriverAccount[] {
  const acc = new Map<string, DriverAccount>();
  const get = (id: string, fallback: string) => {
    let a = acc.get(id);
    if (!a) { a = { driverId: id, name: names.get(id) ?? fallback, debtBefore: 0, deliveries: 0, accrued: 0, paid: 0, debtAfter: 0 }; acc.set(id, a); }
    return a;
  };
  for (const s of sales) {
    if (s.vehicleType !== 'hired' || !s.driverId || String(s.saleDate) >= end) continue;
    const a = get(String(s.driverId), String(s.driverNameSnapshot ?? ''));
    if (String(s.saleDate) < start) a.debtBefore += Number(s.driverPaymentAmount ?? 0);
    else { a.deliveries += 1; a.accrued += Number(s.driverPaymentAmount ?? 0); }
  }
  for (const p of payments) {
    if (String(p.paymentDate) >= end) continue;
    const a = get(String(p.driverId), String(p.driverNameSnapshot ?? ''));
    if (String(p.paymentDate) < start) a.debtBefore -= Number(p.amount ?? 0);
    else a.paid += Number(p.amount ?? 0);
  }
  return [...acc.values()].map((a) => ({ ...a, debtAfter: a.debtBefore + a.accrued - a.paid }))
    .filter((a) => a.deliveries || a.paid || a.debtBefore)
    .sort((x, y) => x.name.localeCompare(y.name, 'ru'));
}

export function driverDebt(driverId: string, sales: Data[], payments: Data[]): { earned: number; paid: number; debt: number; deliveries: number } {
  let earned = 0, paid = 0, deliveries = 0;
  for (const s of sales) if (s.vehicleType === 'hired' && s.driverId === driverId) { earned += Number(s.driverPaymentAmount ?? 0); deliveries += 1; }
  for (const p of payments) if (p.driverId === driverId) paid += Number(p.amount ?? 0);
  return { earned, paid, debt: earned - paid, deliveries };
}

// MARK: Hours

export const HOURS = [0, 4, 8, 12];

export interface HoursTotals { hours: number; workedDays: number; zero: number; four: number; eight: number; twelve: number }

export function hoursTotals(values: number[]): HoursTotals {
  const t: HoursTotals = { hours: 0, workedDays: 0, zero: 0, four: 0, eight: 0, twelve: 0 };
  for (const h of values) {
    t.hours += h;
    if (h > 0) t.workedDays += 1;
    if (h === 0) t.zero += 1; else if (h === 4) t.four += 1; else if (h === 8) t.eight += 1; else if (h === 12) t.twelve += 1;
  }
  return t;
}

// MARK: Livestock

export const SPECIES: { id: string; title: string; categories: string[] }[] = [
  { id: 'cattle', title: 'КРС', categories: ['Коровы', 'Быки', 'Телята', 'Молодняк', 'Другие'] },
  { id: 'goats', title: 'Козы', categories: ['Козы', 'Козлы', 'Козлята', 'Молодняк', 'Другие'] },
  { id: 'pigs', title: 'Свиньи', categories: ['Свиноматки', 'Хряки', 'Поросята', 'Откормочные', 'Другие'] },
];

export const OPERATION_KINDS: Record<string, string> = {
  initial: 'Начальное поголовье', purchase: 'Поступление', birth: 'Приплод', transfer: 'Перевод',
  slaughter: 'Забой', sale: 'Продажа', death: 'Падёж', other: 'Прочее списание',
};
export const ADDITIONS = ['purchase', 'birth', 'initial'];
export const DECREASES = ['slaughter', 'sale', 'death', 'transfer', 'other'];

export function delta(op: Data, category: string): number {
  const heads = Number(op.heads ?? 0);
  if (op.kind === 'transfer') return (op.categoryId === category ? -heads : 0) + (op.targetCategoryId === category ? heads : 0);
  if (op.categoryId !== category) return 0;
  return ADDITIONS.includes(String(op.kind)) ? heads : -heads;
}

export function herdBalances(ops: Data[], before?: string): Map<string, number> {
  const b = new Map<string, number>();
  for (const o of ops) {
    if (before && String(o.date) >= before) continue;
    for (const c of new Set([o.categoryId, o.targetCategoryId].filter(Boolean) as string[])) b.set(c, (b.get(c) ?? 0) + delta(o, c));
  }
  return b;
}

/** The first category that goes below zero in date order; null when the history is consistent. */
export function herdShortage(ops: Data[]): string | null {
  const sorted = [...ops].sort((a, b) => String(a.date).localeCompare(String(b.date)) || String(a.createdAt ?? '').localeCompare(String(b.createdAt ?? '')));
  const run = new Map<string, number>();
  for (const o of sorted) {
    for (const c of new Set([o.categoryId, o.targetCategoryId].filter(Boolean) as string[])) {
      run.set(c, (run.get(c) ?? 0) + delta(o, c));
      if ((run.get(c) ?? 0) < 0) return c;
    }
  }
  return null;
}

export const heads = (n: number) => `${n} ${plural(n, 'голова', 'головы', 'голов')}`;
