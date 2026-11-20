// The rules of the «Поля» directory, the same as on Android (`ru.kfh.core.fields.FieldText`): the area is whole
// square metres, the name is 1..60 characters, the culture and the note are optional. Money and mass in this
// product are integers too; a decimal area would be the only float in the data.

export const FIELD_NAME_MAX = 60;
export const FIELD_CULTURE_MAX = 60;
export const FIELD_NOTE_MAX = 200;

/** A farm field is at most 100 000 га; the cap keeps m² a safe integer on every client. */
export const AREA_MAX_SQ_M = 1_000_000_000;

export const AREA_ERROR = 'Площадь — целое число м² больше нуля, не больше 100 000 га';

export interface FieldRow { id: string; data: Record<string, unknown> }

/** «12,5» → 125000 м²; «0,0001» → 1; пусто, «0», мусор и больше четырёх знаков — null. */
export function parseAreaSqM(text: string): number | null {
  const clean = text.replace(/[\s\u00a0\u202f]/g, '').replace(',', '.');
  if (!clean || clean === '.' || !/^\d*\.?\d*$/.test(clean)) return null;
  const [whole = '', frac = ''] = clean.split('.');
  if (frac.length > 4 || whole.length > 12) return null;
  const sqM = Number(whole || '0') * 10000 + Number((frac + '0000').slice(0, 4));
  return sqM > 0 && sqM <= AREA_MAX_SQ_M ? sqM : null;
}

/** 125000 → «12,5»; 10000 → «1»; 12345 → «1,2345». */
export function areaHa(sqM: number): string {
  const whole = Math.floor(sqM / 10000);
  let frac = String(sqM % 10000).padStart(4, '0');
  while (frac.endsWith('0')) frac = frac.slice(0, -1);
  return `${whole}${frac ? ',' + frac : ''}`;
}

/** «Поле 3 — 12,5 га». */
export function fieldLabel(name: string, sqM: number): string {
  return `${name} — ${areaHa(sqM)} га`;
}

/** The same texts as the server's `validate('field')`, so a refused change explains itself the same way. */
export function fieldErrors(d: Record<string, unknown>): string[] {
  const errors: string[] = [];
  const name = typeof d.name === 'string' ? d.name.trim() : '';
  if (name.length === 0 || name.length > FIELD_NAME_MAX) errors.push(`Название поля — от 1 до ${FIELD_NAME_MAX} символов`);
  const area = d.areaSqM;
  if (typeof area !== 'number' || !Number.isSafeInteger(area) || area <= 0 || area > AREA_MAX_SQ_M) errors.push(AREA_ERROR);
  if (d.culture != null && (typeof d.culture !== 'string' || d.culture.length > FIELD_CULTURE_MAX)) {
    errors.push(`Культура — до ${FIELD_CULTURE_MAX} символов`);
  }
  if (d.note != null && (typeof d.note !== 'string' || d.note.length > FIELD_NOTE_MAX)) {
    errors.push(`Заметка — до ${FIELD_NOTE_MAX} символов`);
  }
  return errors;
}

const nameOf = (r: { data: Record<string, unknown> }) => String(r.data.name ?? '');

/** By name, in Russian — the order the owner reads a list of fields in. */
export function sortFields<T extends { data: Record<string, unknown> }>(rows: T[]): T[] {
  return [...rows].sort((a, b) => nameOf(a).localeCompare(nameOf(b), 'ru'));
}

/** The cultures already in use, for the form's hint: unique, trimmed, in Russian order. */
export function culturesOf(rows: { data: Record<string, unknown> }[]): string[] {
  const set = new Set<string>();
  for (const r of rows) {
    const c = typeof r.data.culture === 'string' ? r.data.culture.trim() : '';
    if (c) set.add(c);
  }
  return [...set].sort((a, b) => a.localeCompare(b, 'ru'));
}
