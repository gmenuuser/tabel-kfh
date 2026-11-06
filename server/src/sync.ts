import type { Db, Tx } from './db.ts';
import { inTransaction } from './db.ts';
import type { Session } from './auth.ts';
import { CAREFUL_ENTITIES, ENTITIES, canRead, canWrite, firstHerdShortage, validate } from './entities.ts';

/** One change made on a device. `baseVersion` is the record version the device last saw (0 for a new record);
 *  `fields` holds only the fields the device changed. */
export interface PushOp {
  opId: string;
  entity: string;
  recordId: string;
  kind: 'upsert' | 'delete';
  baseVersion: number;
  fields?: Record<string, unknown>;
}

export type OpStatus = 'applied' | 'partial' | 'conflict' | 'rejected';

export interface OpResult {
  opId: string;
  status: OpStatus;
  /** The record version after the operation. */
  version?: number;
  /** Fields kept at the server value because another device changed them too. */
  conflicts?: string[];
  errors?: string[];
  /** The same operation was received before: nothing changed now. */
  repeated?: boolean;
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const same = (a: unknown, b: unknown) => JSON.stringify(a ?? null) === JSON.stringify(b ?? null);

function shapeErrors(op: PushOp): string[] {
  const e: string[] = [];
  if (typeof op?.opId !== 'string' || !UUID.test(op.opId)) e.push('opId — UUID');
  if (typeof op?.recordId !== 'string' || !UUID.test(op.recordId)) e.push('recordId — UUID');
  if (!ENTITIES[op?.entity]) e.push(`Неизвестный тип записи «${op?.entity}»`);
  if (op?.kind !== 'upsert' && op?.kind !== 'delete') e.push('kind — upsert или delete');
  if (!Number.isInteger(op?.baseVersion) || op.baseVersion < 0) e.push('baseVersion — целое число ≥ 0');
  if (op?.kind === 'upsert' && (typeof op.fields !== 'object' || op.fields === null || Array.isArray(op.fields))) {
    e.push('fields — объект');
  }
  if (op?.fields && Object.keys(op.fields).some((k) => k.startsWith('__') || k === 'id')) e.push('Служебные поля менять нельзя');
  return e;
}

/** Applies the operations in order, each in its own transaction, so one refused change never blocks the others. */
export async function push(db: Db, s: Session, ops: PushOp[]): Promise<OpResult[]> {
  const results: OpResult[] = [];
  for (const op of ops) results.push(await inTransaction(db, (tx) => applyOp(tx, s, op)));
  return results;
}

async function applyOp(tx: Tx, s: Session, op: PushOp): Promise<OpResult> {
  const shape = shapeErrors(op);
  if (shape.length) return { opId: String(op?.opId ?? ''), status: 'rejected', errors: shape };

  const done = await tx.query('SELECT result FROM processed_ops WHERE farm_id = $1 AND op_id = $2', [s.farmId, op.opId]);
  if (done.rowCount) return { ...(done.rows[0].result as OpResult), repeated: true };

  const result = await decide(tx, s, op);
  await tx.query('INSERT INTO processed_ops (farm_id, op_id, result, device_id) VALUES ($1, $2, $3, $4)',
    [s.farmId, op.opId, JSON.stringify(result), s.deviceId]);
  if (CAREFUL_ENTITIES.has(op.entity) || result.status !== 'applied') {
    await tx.query('INSERT INTO audit_log (farm_id, user_id, device_id, action, details) VALUES ($1, $2, $3, $4, $5)',
      [s.farmId, s.userId, s.deviceId, `sync.${op.kind}.${result.status}`,
       JSON.stringify({ entity: op.entity, recordId: op.recordId, opId: op.opId, conflicts: result.conflicts, errors: result.errors })]);
  }
  return result;
}

async function decide(tx: Tx, s: Session, op: PushOp): Promise<OpResult> {
  if (!canWrite(s.role, op.entity)) return { opId: op.opId, status: 'rejected', errors: ['Нет прав на изменение этих данных'] };
  // One writer per record at a time, also for a record that does not exist yet.
  await tx.query('SELECT pg_advisory_xact_lock(hashtext($1))', [`${s.farmId}:${op.entity}:${op.recordId}`]);
  const found = await tx.query(
    'SELECT version, data, field_versions, deleted FROM records WHERE farm_id = $1 AND entity = $2 AND id = $3',
    [s.farmId, op.entity, op.recordId]);
  const row = found.rows[0] as { version: number; data: Record<string, unknown>; field_versions: Record<string, number>; deleted: boolean } | undefined;

  // A new record.
  if (!row) {
    if (op.kind === 'delete') return { opId: op.opId, status: 'applied', version: 0 };
    const data = { ...op.fields };
    const errors = validate(op.entity, data);
    if (errors.length) return { opId: op.opId, status: 'rejected', errors };
    const herd = await herdErrors(tx, s, op.entity, op.recordId, data);
    if (herd.length) return { opId: op.opId, status: 'rejected', errors: herd };
    const fv = Object.fromEntries(Object.keys(data).map((k) => [k, 1]));
    await tx.query(
      `INSERT INTO records (farm_id, entity, id, version, data, field_versions, deleted, created_by_device, updated_by_device, seq)
       VALUES ($1, $2, $3, 1, $4, $5, false, $6, $6, nextval('change_seq'))`,
      [s.farmId, op.entity, op.recordId, JSON.stringify(data), JSON.stringify(fv), s.deviceId]);
    return { opId: op.opId, status: 'applied', version: 1 };
  }

  // A deleted record never comes back through an edit: the edit becomes a conflict to look at.
  if (row.deleted) {
    if (op.kind === 'delete') return { opId: op.opId, status: 'applied', version: row.version };
    await saveConflict(tx, s, op, '__deleted', true, op.fields ?? {});
    return { opId: op.opId, status: 'conflict', version: row.version, conflicts: ['__deleted'] };
  }

  if (op.kind === 'delete') {
    if (row.version > op.baseVersion) {
      // Changed elsewhere after this device last saw it: deleting would throw that change away.
      await saveConflict(tx, s, op, '__deleted', false, true);
      return { opId: op.opId, status: 'conflict', version: row.version, conflicts: ['__deleted'] };
    }
    const herd = await herdErrors(tx, s, op.entity, op.recordId, null);
    if (herd.length) return { opId: op.opId, status: 'rejected', errors: herd };
    await tx.query(
      `UPDATE records SET deleted = true, version = version + 1, updated_at = now(), updated_by_device = $4,
              seq = nextval('change_seq') WHERE farm_id = $1 AND entity = $2 AND id = $3`,
      [s.farmId, op.entity, op.recordId, s.deviceId]);
    return { opId: op.opId, status: 'applied', version: row.version + 1 };
  }

  const version = row.version + 1;
  const data = { ...row.data };
  const fv = { ...row.field_versions };
  const applied: string[] = [];
  const conflicting: string[] = [];
  for (const [field, value] of Object.entries(op.fields ?? {})) {
    if (same(row.data[field], value)) continue;
    if ((row.field_versions[field] ?? 0) > op.baseVersion) {
      conflicting.push(field);
    } else {
      applied.push(field);
    }
  }
  // Money and herd records change as a whole or not at all: half a sale is worse than a conflict.
  if (conflicting.length && CAREFUL_ENTITIES.has(op.entity)) {
    for (const field of [...conflicting, ...applied]) await saveConflict(tx, s, op, field, row.data[field], op.fields![field]);
    return { opId: op.opId, status: 'conflict', version: row.version, conflicts: [...conflicting, ...applied] };
  }
  for (const field of conflicting) await saveConflict(tx, s, op, field, row.data[field], op.fields![field]);
  if (!applied.length) {
    return { opId: op.opId, status: conflicting.length ? 'conflict' : 'applied', version: row.version,
             ...(conflicting.length ? { conflicts: conflicting } : {}) };
  }
  for (const field of applied) {
    data[field] = op.fields![field];
    fv[field] = version;
  }
  const errors = validate(op.entity, data);
  if (errors.length) return { opId: op.opId, status: 'rejected', errors };
  const herd = await herdErrors(tx, s, op.entity, op.recordId, data);
  if (herd.length) return { opId: op.opId, status: 'rejected', errors: herd };
  await tx.query(
    `UPDATE records SET data = $4, field_versions = $5, version = $6, updated_at = now(), updated_by_device = $7,
            seq = nextval('change_seq') WHERE farm_id = $1 AND entity = $2 AND id = $3`,
    [s.farmId, op.entity, op.recordId, JSON.stringify(data), JSON.stringify(fv), version, s.deviceId]);
  return conflicting.length
    ? { opId: op.opId, status: 'partial', version, conflicts: conflicting }
    : { opId: op.opId, status: 'applied', version };
}

async function saveConflict(tx: Tx, s: Session, op: PushOp, field: string, serverValue: unknown, clientValue: unknown) {
  await tx.query(
    `INSERT INTO conflicts (farm_id, entity, record_id, field, server_value, client_value, base_version, device_id, op_id)
     VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)`,
    [s.farmId, op.entity, op.recordId, field, JSON.stringify(serverValue ?? null), JSON.stringify(clientValue ?? null),
     op.baseVersion, s.deviceId, op.opId]);
}

/** The herd must not go below zero with this operation changed (`data`) or removed (null). */
async function herdErrors(tx: Tx, s: Session, entity: string, id: string, data: Record<string, unknown> | null): Promise<string[]> {
  if (entity !== 'livestockOperation') return [];
  const ops = (await tx.query(
    `SELECT id, data FROM records WHERE farm_id = $1 AND entity = 'livestockOperation' AND NOT deleted AND id <> $2`,
    [s.farmId, id])).rows.map((r) => r.data as Record<string, unknown>);
  if (data) ops.push(data);
  const shortage = firstHerdShortage(ops);
  return shortage ? [`Не хватает голов в категории на ${shortage.date.slice(0, 10)}: списать больше, чем есть, нельзя`] : [];
}

export interface Change {
  entity: string;
  id: string;
  version: number;
  deleted: boolean;
  data: Record<string, unknown>;
  updatedAt: string;
}

/** Everything changed after `since`, in change order, only the entities the role may read. */
export async function pull(db: Db, s: Session, since: number, limit: number) {
  const rows = (await db.query(
    `SELECT entity, id, version, deleted, data, updated_at, seq FROM records
      WHERE farm_id = $1 AND seq > $2 ORDER BY seq LIMIT $3`, [s.farmId, since, limit + 1])).rows;
  const more = rows.length > limit;
  const page = rows.slice(0, limit);
  const changes: Change[] = page.filter((r) => canRead(s.role, r.entity)).map((r) => ({
    entity: r.entity, id: r.id, version: r.version, deleted: r.deleted, data: r.deleted ? {} : r.data,
    updatedAt: new Date(r.updated_at).toISOString(),
  }));
  const next = page.length ? Number(page[page.length - 1].seq) : since;
  return { changes, next, more };
}

export async function openConflicts(db: Db, s: Session) {
  return (await db.query(
    `SELECT id, entity, record_id, field, server_value, client_value, base_version, device_id, op_id, created_at
       FROM conflicts WHERE farm_id = $1 AND resolved_at IS NULL ORDER BY created_at`, [s.farmId])).rows
    .filter((r) => canRead(s.role, r.entity))
    .map((r) => ({ id: r.id, entity: r.entity, recordId: r.record_id, field: r.field, serverValue: r.server_value,
                   clientValue: r.client_value, baseVersion: r.base_version, deviceId: r.device_id, opId: r.op_id,
                   createdAt: new Date(r.created_at).toISOString() }));
}

/** Keeps the server value, or applies the device's value (for money and herd records: the device's whole change). */
export async function resolveConflict(db: Db, s: Session, conflictId: string, choice: 'server' | 'client') {
  return inTransaction(db, async (tx) => {
    const c = (await tx.query('SELECT * FROM conflicts WHERE id = $1 AND farm_id = $2 AND resolved_at IS NULL FOR UPDATE',
      [conflictId, s.farmId])).rows[0];
    if (!c) return { ok: false, error: 'Конфликт не найден или уже решён' };
    if (!canWrite(s.role, c.entity)) return { ok: false, error: 'Нет прав на изменение этих данных' };
    const group = CAREFUL_ENTITIES.has(c.entity) || c.field === '__deleted'
      ? (await tx.query('SELECT * FROM conflicts WHERE farm_id = $1 AND op_id = $2 AND resolved_at IS NULL FOR UPDATE',
          [s.farmId, c.op_id])).rows
      : [c];
    if (choice === 'client') {
      await tx.query('SELECT pg_advisory_xact_lock(hashtext($1))', [`${s.farmId}:${c.entity}:${c.record_id}`]);
      const row = (await tx.query('SELECT version, data, field_versions, deleted FROM records WHERE farm_id = $1 AND entity = $2 AND id = $3',
        [s.farmId, c.entity, c.record_id])).rows[0];
      if (!row) return { ok: false, error: 'Запись не найдена' };
      const version = row.version + 1;
      let data = { ...row.data };
      const fv = { ...row.field_versions };
      let deleted = row.deleted;
      for (const g of group) {
        if (g.field === '__deleted') {
          if (row.deleted) { data = { ...data, ...(g.client_value as object) }; deleted = false; }
          else deleted = true;
          for (const k of Object.keys(g.client_value ?? {})) fv[k] = version;
        } else {
          data[g.field] = g.client_value;
          fv[g.field] = version;
        }
      }
      if (!deleted) {
        const errors = validate(c.entity, data);
        if (errors.length) return { ok: false, error: errors.join('; ') };
      }
      const herd = await herdErrors(tx, s, c.entity, c.record_id, deleted ? null : data);
      if (herd.length) return { ok: false, error: herd.join('; ') };
      await tx.query(
        `UPDATE records SET data = $4, field_versions = $5, version = $6, deleted = $7, updated_at = now(),
                updated_by_device = $8, seq = nextval('change_seq') WHERE farm_id = $1 AND entity = $2 AND id = $3`,
        [s.farmId, c.entity, c.record_id, JSON.stringify(data), JSON.stringify(fv), version, deleted, s.deviceId]);
    }
    for (const g of group) {
      await tx.query('UPDATE conflicts SET resolved_at = now(), resolution = $2 WHERE id = $1', [g.id, choice]);
    }
    await tx.query('INSERT INTO audit_log (farm_id, user_id, device_id, action, details) VALUES ($1, $2, $3, $4, $5)',
      [s.farmId, s.userId, s.deviceId, 'conflict.resolve', JSON.stringify({ conflictId, choice, entity: c.entity, recordId: c.record_id })]);
    return { ok: true, resolved: group.length };
  });
}
