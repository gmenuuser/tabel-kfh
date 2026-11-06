// The record types every platform syncs, which module each belongs to, which roles may change it, and the
// server-side checks that keep money and the herd consistent whatever the client sends.

export type Role = 'owner' | 'manager' | 'timesheet' | 'coal' | 'livestock' | 'viewer';
export type Module = 'timesheet' | 'coal' | 'livestock' | 'agro' | 'farm';

export const ROLES: Role[] = ['owner', 'manager', 'timesheet', 'coal', 'livestock', 'viewer'];

/** Entity name → module. Names and field names are those of the iPhone models (camelCase). */
export const ENTITIES: Record<string, Module> = {
  employee: 'timesheet',
  hoursMark: 'timesheet',
  attendanceRecord: 'timesheet',
  timesheetSnapshot: 'timesheet',
  farmSettings: 'farm',
  coalSettings: 'coal',
  deliveryLocation: 'coal',
  driver: 'coal',
  coalSale: 'coal',
  driverPayment: 'coal',
  coalWorkDay: 'coal',
  livestockCategory: 'livestock',
  livestockOperation: 'livestock',
  slaughterRecord: 'livestock',
  livestockSettings: 'livestock',
  agroCalculation: 'agro',
};

/** Money, sales, payments, slaughter and herd movements: never last-writer-wins. */
export const CAREFUL_ENTITIES = new Set(['coalSale', 'driverPayment', 'coalWorkDay', 'livestockOperation', 'slaughterRecord']);

export function canWrite(role: Role, entity: string): boolean {
  const module = ENTITIES[entity];
  if (!module) return false;
  switch (role) {
    case 'owner':
    case 'manager':
      return true;
    case 'timesheet':
      return module === 'timesheet';
    case 'coal':
      return module === 'coal';
    case 'livestock':
      return module === 'livestock';
    case 'viewer':
      return false;
  }
}

export function canRead(role: Role, entity: string): boolean {
  if (!ENTITIES[entity]) return false;
  if (role === 'owner' || role === 'manager' || role === 'viewer') return true;
  return canWrite(role, entity) || ENTITIES[entity] === 'farm';
}

type Data = Record<string, unknown>;

const isInt = (v: unknown) => typeof v === 'number' && Number.isInteger(v);
const nonNegativeInt = (v: unknown) => isInt(v) && (v as number) >= 0;
const positiveInt = (v: unknown) => isInt(v) && (v as number) > 0;

/** Checks of one record as it will be stored; returns the Russian messages of what is wrong. */
export function validate(entity: string, d: Data): string[] {
  const errors: string[] = [];
  const need = (ok: boolean, message: string) => { if (!ok) errors.push(message); };
  switch (entity) {
    case 'hoursMark':
      need(typeof d.employeeId === 'string', 'Не указан сотрудник');
      // null = the mark was removed (the same stable record id is used again when the day is marked anew).
      need(d.hours === null || [0, 4, 8, 12].includes(d.hours as number), 'Часы — 0, 4, 8 или 12');
      break;
    case 'coalSale':
      need(positiveInt(d.kilograms), 'Количество угля — целое число килограммов больше нуля');
      for (const k of ['coalAmount', 'deliveryPrice', 'totalAmount', 'driverPaymentAmount']) {
        need(nonNegativeInt(d[k]), `Сумма «${k}» — целое число копеек`);
      }
      need(d.totalAmount === (d.coalAmount as number) + (d.deliveryPrice as number), 'Итого не равно углю и доставке');
      if (d.vehicleType === 'hired') need(typeof d.driverId === 'string', 'У наёмной машины должен быть водитель');
      if (d.vehicleType === 'own') need(d.driverPaymentAmount === 0, 'Собственная машина не создаёт долга водителю');
      need(d.vehicleType === 'hired' || d.vehicleType === 'own', 'Неизвестный тип машины');
      break;
    case 'driverPayment':
      need(positiveInt(d.amount), 'Сумма выплаты — целое число копеек больше нуля');
      need(typeof d.driverId === 'string', 'Не указан водитель');
      break;
    case 'livestockOperation':
      need(positiveInt(d.heads), 'Количество голов — целое число больше нуля');
      need(typeof d.categoryId === 'string', 'Не указана категория');
      need(['initial', 'purchase', 'birth', 'transfer', 'slaughter', 'sale', 'death', 'other'].includes(d.kind as string),
        'Неизвестный тип операции');
      if (d.kind === 'transfer') need(typeof d.targetCategoryId === 'string' && d.targetCategoryId !== d.categoryId,
        'Перевод — в другую категорию');
      break;
  }
  return errors;
}

/** The herd may never go below zero in date order (the same rule as on the iPhone). */
export function firstHerdShortage(ops: Data[]): { categoryId: string; date: string } | null {
  const sorted = [...ops].sort((a, b) =>
    String(a.date).localeCompare(String(b.date)) || String(a.createdAt ?? '').localeCompare(String(b.createdAt ?? '')));
  const balance = new Map<string, number>();
  const add = (c: string, n: number) => balance.set(c, (balance.get(c) ?? 0) + n);
  for (const o of sorted) {
    const heads = o.heads as number;
    const category = o.categoryId as string;
    if (o.kind === 'transfer') {
      add(category, -heads);
      add(o.targetCategoryId as string, heads);
    } else if (['initial', 'purchase', 'birth'].includes(o.kind as string)) {
      add(category, heads);
    } else {
      add(category, -heads);
    }
    for (const c of [category, o.targetCategoryId as string | undefined]) {
      if (c && (balance.get(c) ?? 0) < 0) return { categoryId: c, date: String(o.date) };
    }
  }
  return null;
}
