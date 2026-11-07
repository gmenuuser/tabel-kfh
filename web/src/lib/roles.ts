// Which modules of the site a role may see. The same rule as `canRead` in server/src/entities.ts:
// owner, manager and viewer read everything; a helper reads the module of its role.

export type Module = 'timesheet' | 'coal' | 'livestock' | 'agro';

const ALL: Module[] = ['timesheet', 'coal', 'livestock', 'agro'];

export function modulesFor(role: string): Module[] {
  switch (role) {
    case 'owner': case 'manager': case 'viewer': return ALL;
    case 'timesheet': return ['timesheet'];
    case 'coal': return ['coal'];
    case 'livestock': return ['livestock'];
    default: return [];
  }
}

/** The page ids of the site (first part of the route) and the module each one needs; null = everyone. */
const PAGE_MODULE: Record<string, Module | 'reports' | null> = {
  home: null, settings: null, timesheet: 'timesheet', employees: 'timesheet', coal: 'coal', livestock: 'livestock',
  agro: 'agro', reports: 'reports',
};

/** May this role open the page (the first part of the route)? Unknown pages are the home page, which is open. */
export function canOpen(role: string, page: string): boolean {
  const need = PAGE_MODULE[page];
  if (need === undefined || need === null) return true;
  const modules = modulesFor(role);
  // Reports exist for timesheet, coal and livestock.
  if (need === 'reports') return modules.some((m) => m !== 'agro');
  return modules.includes(need);
}

/** The viewer reads and does not change anything. */
export function canEdit(role: string): boolean { return role !== 'viewer'; }
