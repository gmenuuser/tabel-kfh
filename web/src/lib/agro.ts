// The eight agronomic calculators, the same formulas as on the iPhone (AgroEngine): values unrounded inside,
// the shown text at most 2 decimals, half away from zero.

export interface AgroField { key: string; title: string; unit: string; optional?: boolean }
export interface AgroChoice { key: string; title: string; options: [string, string][] }
export interface AgroLine { title: string; text: string; main?: boolean }
export interface AgroResult { lines: AgroLine[]; warnings: string[]; errors: string[]; missing: string[] }
export type Inputs = Record<string, string>;

export const KINDS: [string, string][] = [
  ['seeding', 'Посев семян'], ['fertilizer', 'Удобрения'], ['spraying', 'Опрыскивание'], ['yield', 'Урожайность'],
  ['moisture', 'Влажность зерна'], ['fuel', 'Топливо'], ['area', 'Площадь поля'], ['transport', 'Перевозка и хранение зерна'],
];

export function parseNumber(t?: string): number | null {
  if (t == null) return null;
  const c = t.replace(/[\s  ]/g, '').replace(',', '.');
  if (!c || (c.match(/\./g) ?? []).length > 1 || !/^-?[\d.]+$/.test(c)) return null;
  const v = Number(c);
  return Number.isFinite(v) ? v : null;
}

export function fmt(v: number, decimals = 2): string {
  const scale = 10 ** decimals;
  const rounded = Math.round(Math.abs(v) * scale + 1e-9 * scale) ;
  const whole = Math.floor(rounded / scale);
  let frac = String(rounded - whole * scale).padStart(decimals, '0');
  while (frac.endsWith('0')) frac = frac.slice(0, -1);
  const s = String(whole);
  let g = '';
  for (let i = 0; i < s.length; i += 1) { if (i > 0 && (s.length - i) % 3 === 0) g += ' '; g += s[i]; }
  return `${v < 0 && rounded > 0 ? '−' : ''}${g}${frac ? ',' + frac : ''}`;
}
const w = (v: number, unit: string, d = 2) => `${fmt(v, d)} ${unit}`;

export function choices(kind: string): AgroChoice[] {
  switch (kind) {
    case 'fertilizer': return [{ key: 'rateUnit', title: 'Норма', options: [['kg', 'кг/га'], ['c', 'ц/га'], ['t', 'т/га']] },
                               { key: 'priceUnit', title: 'Цена', options: [['kg', 'за кг'], ['t', 'за тонну']] }];
    case 'spraying': return [{ key: 'productUnit', title: 'Препарат', options: [['l', 'л/га'], ['kg', 'кг/га']] }];
    case 'yield': return [{ key: 'weightUnit', title: 'Вес', options: [['t', 'тонны'], ['c', 'центнеры'], ['kg', 'кг']] }];
    case 'fuel': return [{ key: 'mode', title: 'Расчёт', options: [['area', 'По площади'], ['stock', 'По запасу топлива']] }];
    case 'transport': return [{ key: 'grainUnit', title: 'Вес зерна', options: [['t', 'тонны'], ['kg', 'кг']] },
                              { key: 'truckUnit', title: 'Машина', options: [['t', 'тонны'], ['kg', 'кг']] }];
    default: return [];
  }
}

export const choice = (kind: string, key: string, inputs: Inputs) =>
  inputs[key] ?? choices(kind).find((c) => c.key === key)?.options[0][0] ?? '';

export function fields(kind: string, inputs: Inputs): AgroField[] {
  const c = (k: string) => choice(kind, k, inputs);
  switch (kind) {
    case 'seeding': return [{ key: 'area', title: 'Площадь поля', unit: 'га' }, { key: 'rate', title: 'Норма высева', unit: 'кг/га' },
      { key: 'reserve', title: 'Дополнительный запас', unit: '%', optional: true }, { key: 'bag', title: 'Вес мешка', unit: 'кг', optional: true }];
    case 'fertilizer': return [{ key: 'area', title: 'Площадь поля', unit: 'га' },
      { key: 'rate', title: 'Норма внесения', unit: ({ kg: 'кг/га', c: 'ц/га', t: 'т/га' } as Record<string, string>)[c('rateUnit')] },
      { key: 'price', title: 'Цена', unit: c('priceUnit') === 't' ? '₽/т' : '₽/кг', optional: true },
      { key: 'package', title: 'Вес упаковки', unit: 'кг', optional: true }];
    case 'spraying': return [{ key: 'area', title: 'Площадь обработки', unit: 'га' },
      { key: 'rate', title: 'Норма препарата (по этикетке)', unit: c('productUnit') === 'kg' ? 'кг/га' : 'л/га' },
      { key: 'water', title: 'Расход воды', unit: 'л/га' }, { key: 'tank', title: 'Объём бака', unit: 'л', optional: true }];
    case 'yield': return [{ key: 'area', title: 'Убранная площадь', unit: 'га' },
      { key: 'weight', title: 'Собранный вес', unit: ({ t: 'т', c: 'ц', kg: 'кг' } as Record<string, string>)[c('weightUnit')] }];
    case 'moisture': return [{ key: 'weight', title: 'Вес зерна', unit: 'кг' }, { key: 'actual', title: 'Фактическая влажность', unit: '%' },
      { key: 'target', title: 'Целевая влажность', unit: '%' }];
    case 'fuel': return c('mode') === 'stock'
      ? [{ key: 'fuel', title: 'Есть топлива', unit: 'л' }, { key: 'rate', title: 'Расход топлива', unit: 'л/га' }]
      : [{ key: 'area', title: 'Площадь обработки', unit: 'га' }, { key: 'rate', title: 'Расход топлива', unit: 'л/га' },
         { key: 'price', title: 'Цена дизельного топлива', unit: '₽/л', optional: true }];
    case 'transport': return [{ key: 'grain', title: 'Общий вес зерна', unit: c('grainUnit') === 'kg' ? 'кг' : 'т' },
      { key: 'truck', title: 'Вместимость машины', unit: c('truckUnit') === 'kg' ? 'кг' : 'т' },
      { key: 'bag', title: 'Вместимость мешка', unit: 'кг', optional: true }];
    default: return [];
  }
}

export function compute(kind: string, inputs: Inputs): AgroResult {
  const r: AgroResult = { lines: [], warnings: [], errors: [], missing: [] };
  if (kind === 'area') return area(inputs);
  const v: Record<string, number> = {};
  for (const f of fields(kind, inputs)) {
    const t = (inputs[f.key] ?? '').trim();
    if (!t) { if (!f.optional) r.missing.push(f.title); continue; }
    const n = parseNumber(t);
    if (n === null) { r.errors.push(`«${f.title}»: введите число, например 12,5`); continue; }
    if (n < 0 || (n === 0 && f.key !== 'reserve')) { r.errors.push(`«${f.title}» должно быть больше нуля`); continue; }
    v[f.key] = n;
  }
  if (r.errors.length || r.missing.length) return r;
  const c = (k: string) => choice(kind, k, inputs);
  switch (kind) {
    case 'seeding': {
      const reserve = v.reserve ?? 0;
      if (reserve > 100) { r.errors.push('Запас — не больше 100 %'); break; }
      const kg = v.area * v.rate * (1 + reserve / 100);
      r.lines = [{ title: 'Нужно семян', text: w(kg, 'кг'), main: true }, { title: 'В центнерах', text: w(kg / 100, 'ц') },
                 { title: 'В тоннах', text: w(kg / 1000, 'т', 3) }];
      if (reserve > 0) r.lines.push({ title: 'Без запаса', text: w(v.area * v.rate, 'кг') });
      if (v.bag) r.lines.push({ title: `Мешков по ${fmt(v.bag)} кг`, text: String(Math.ceil(kg / v.bag)) });
      break;
    }
    case 'fertilizer': {
      const kg = v.area * v.rate * ({ kg: 1, c: 100, t: 1000 } as Record<string, number>)[c('rateUnit')];
      r.lines = [{ title: 'Нужно удобрения', text: w(kg, 'кг'), main: true }, { title: 'В тоннах', text: w(kg / 1000, 'т', 3) }];
      if (v.package) r.lines.push({ title: `Упаковок по ${fmt(v.package)} кг`, text: String(Math.ceil(kg / v.package)) });
      if (v.price) r.lines.push({ title: 'Стоимость', text: w(c('priceUnit') === 't' ? kg / 1000 * v.price : kg * v.price, '₽'), main: true });
      break;
    }
    case 'spraying': {
      const liquid = c('productUnit') !== 'kg';
      const product = v.area * v.rate, water = v.area * v.water, solution = water + (liquid ? product : 0);
      r.lines = [{ title: 'Препарата', text: w(product, liquid ? 'л' : 'кг'), main: true }, { title: 'Воды', text: w(water, 'л') },
                 { title: 'Рабочего раствора', text: w(solution, 'л'), main: true }];
      if (!liquid) r.warnings.push('Препарат в килограммах: в объём раствора он не добавлен — для этого нужна его плотность.');
      if (v.tank) {
        const full = Math.floor(solution / v.tank), rest = solution - full * v.tank;
        r.lines.push({ title: 'Полных заправок', text: String(full), main: true },
                     { title: 'Остаток на последнюю заправку', text: rest > 1e-9 ? w(rest, 'л') : 'нет' });
      }
      r.warnings.push('Норму препарата берите только с этикетки: приложение дозировки не назначает.');
      break;
    }
    case 'yield': {
      const t = v.weight / ({ t: 1, c: 10, kg: 1000 } as Record<string, number>)[c('weightUnit')];
      const perHa = t / v.area;
      r.lines = [{ title: 'Урожайность', text: w(perHa * 10, 'ц/га'), main: true }, { title: 'Урожайность', text: w(perHa, 'т/га', 3), main: true },
                 { title: 'Собрано всего', text: w(t, 'т', 3) }];
      break;
    }
    case 'moisture': {
      if (v.actual >= 100 || v.target >= 100) { r.errors.push('Влажность — меньше 100 %'); break; }
      if (v.target >= v.actual) { r.errors.push('Целевая влажность должна быть меньше фактической'); break; }
      const after = v.weight * (100 - v.actual) / (100 - v.target);
      r.lines = [{ title: 'Исходный вес', text: w(v.weight, 'кг') }, { title: 'Вес после сушки', text: w(after, 'кг'), main: true },
                 { title: 'Потеря массы', text: w(v.weight - after, 'кг'), main: true }, { title: 'Удалить воды (расчётно)', text: w(v.weight - after, 'кг') }];
      r.warnings.push('Это расчёт теоретической массы при сохранении сухого вещества, без учёта технологических потерь и изменения состава зерна.');
      break;
    }
    case 'fuel': {
      if (c('mode') === 'stock') { r.lines = [{ title: 'Можно обработать', text: w(v.fuel / v.rate, 'га'), main: true }]; break; }
      const l = v.area * v.rate;
      r.lines = [{ title: 'Нужно топлива', text: w(l, 'л'), main: true }];
      if (v.price) r.lines.push({ title: 'Стоимость топлива', text: w(l * v.price, '₽'), main: true });
      break;
    }
    case 'transport': {
      const g = v.grain * (c('grainUnit') === 'kg' ? 1 : 1000), t = v.truck * (c('truckUnit') === 'kg' ? 1 : 1000);
      const full = Math.floor(g / t + 1e-9), rest = Math.max(0, g - full * t);
      r.lines = [{ title: 'Полных рейсов', text: String(full), main: true },
                 { title: 'Остаток на последний рейс', text: rest > 1e-6 ? w(rest, 'кг') : 'нет' },
                 { title: 'Всего рейсов', text: String(full + (rest > 1e-6 ? 1 : 0)) }];
      if (v.bag) {
        const bags = Math.floor(g / v.bag + 1e-9), left = Math.max(0, g - bags * v.bag);
        r.lines.push({ title: `Полных мешков по ${fmt(v.bag)} кг`, text: String(bags), main: true },
                     { title: 'Остаток зерна', text: left > 1e-6 ? w(left, 'кг') : 'нет' });
      }
      break;
    }
  }
  return r;
}

function area(inputs: Inputs): AgroResult {
  const r: AgroResult = { lines: [], warnings: [], errors: [], missing: [] };
  const n = Math.max(1, Number(inputs.plots ?? 1) || 1);
  let total = 0, counted = 0;
  for (let i = 1; i <= n; i += 1) {
    const l = (inputs[`plot.${i}.length`] ?? '').trim(), wd = (inputs[`plot.${i}.width`] ?? '').trim(), a = (inputs[`plot.${i}.area`] ?? '').trim();
    if (!l && !wd && !a) continue;
    let sq: number;
    if (a) { const x = parseNumber(a); if (x === null || x <= 0) { r.errors.push(`Участок ${i}: площадь — число больше нуля`); continue; } sq = x; }
    else { const x = parseNumber(l), y = parseNumber(wd); if (x === null || y === null || x <= 0 || y <= 0) { r.errors.push(`Участок ${i}: укажите длину и ширину больше нуля`); continue; } sq = x * y; }
    total += sq; counted += 1;
    r.lines.push({ title: `Участок ${i}`, text: `${w(sq, 'м²')} = ${w(sq / 10000, 'га', 4)}` });
  }
  if (!counted && !r.errors.length) r.missing.push('Длина и ширина участка');
  if (r.errors.length || !counted) { r.lines = []; return r; }
  r.lines.push({ title: 'Всего', text: w(total, 'м²') }, { title: 'Площадь поля', text: w(total / 10000, 'га', 4), main: true });
  r.warnings.push('Длина × ширина верна только для прямоугольника. Поле сложной формы разбейте на части и сложите их.');
  return r;
}

export const complete = (r: AgroResult) => !r.errors.length && !r.missing.length && r.lines.length > 0;
