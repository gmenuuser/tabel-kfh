// Dates of the farm calendar, the same on every device whatever its own time zone: a day is «YYYY-MM-DD» in the
// farm's zone (Asia/Chita by default); records keep instants as ISO 8601 UTC.

export let farmZone = 'Asia/Chita';
export function setFarmZone(z: string) { farmZone = z; }

function parts(d: Date, zone: string) {
  const f = new Intl.DateTimeFormat('en-CA', { timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' });
  const p = Object.fromEntries(f.formatToParts(d).map((x) => [x.type, x.value]));
  return { y: +p.year, m: +p.month, d: +p.day, h: +p.hour, min: +p.minute, s: +p.second };
}

/** Offset of `zone` from UTC at the instant, in minutes. */
function offsetMinutes(at: Date, zone: string): number {
  const p = parts(at, zone);
  const asUtc = Date.UTC(p.y, p.m - 1, p.d, p.h, p.min, p.s);
  return Math.round((asUtc - Math.floor(at.getTime() / 1000) * 1000) / 60000);
}

/** «2026-11-06» of an instant on the farm calendar. */
export function dayKey(at: Date | string, zone = farmZone): string {
  const p = parts(typeof at === 'string' ? new Date(at) : at, zone);
  return `${p.y}-${String(p.m).padStart(2, '0')}-${String(p.d).padStart(2, '0')}`;
}

/** The instant of midnight of a farm day, as ISO UTC (what the iPhone stores as `day`). */
export function dayStartISO(key: string, zone = farmZone): string {
  const [y, m, d] = key.split('-').map(Number);
  const guess = new Date(Date.UTC(y, m - 1, d));
  const first = guess.getTime() - offsetMinutes(guess, zone) * 60000;
  const exact = guess.getTime() - offsetMinutes(new Date(first), zone) * 60000;
  return new Date(exact).toISOString();
}

export function addDays(key: string, n: number): string {
  const [y, m, d] = key.split('-').map(Number);
  const t = new Date(Date.UTC(y, m - 1, d + n));
  return t.toISOString().slice(0, 10);
}

export function daysInMonth(year: number, month: number): number {
  return new Date(Date.UTC(year, month, 0)).getUTCDate();
}

export const MONTHS = ['Январь', 'Февраль', 'Март', 'Апрель', 'Май', 'Июнь', 'Июль', 'Август', 'Сентябрь', 'Октябрь',
  'Ноябрь', 'Декабрь'];
const MONTHS_GEN = ['января', 'февраля', 'марта', 'апреля', 'мая', 'июня', 'июля', 'августа', 'сентября', 'октября',
  'ноября', 'декабря'];
const WEEKDAYS = ['воскресенье', 'понедельник', 'вторник', 'среда', 'четверг', 'пятница', 'суббота'];
export const WEEKDAYS_SHORT = ['вс', 'пн', 'вт', 'ср', 'чт', 'пт', 'сб'];

export function weekday(key: string): number {
  const [y, m, d] = key.split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, d)).getUTCDay();
}

/** «Пятница, 6 ноября 2026». */
export function longDate(key: string): string {
  const [y, m, d] = key.split('-').map(Number);
  const w = WEEKDAYS[weekday(key)];
  return `${w[0].toUpperCase()}${w.slice(1)}, ${d} ${MONTHS_GEN[m - 1]} ${y}`;
}

/** «06.11.2026». */
export function shortDate(key: string): string {
  const [y, m, d] = key.split('-');
  return `${d}.${m}.${y}`;
}

/** «14:05» of an instant on the farm clock. */
export function timeText(iso: string, zone = farmZone): string {
  const p = parts(new Date(iso), zone);
  return `${String(p.h).padStart(2, '0')}:${String(p.min).padStart(2, '0')}`;
}

export function todayKey(): string { return dayKey(new Date()); }
