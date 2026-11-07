import type { LocalDb, LocalRecord, QueuedOp } from './db.ts';
import { newId } from './ids.ts';

export interface Session {
  token: string;
  farmId: string;
  userId: string;
  deviceId: string;
  role: string;
  email?: string;
}

export interface SyncStatus {
  online: boolean;
  syncing: boolean;
  /** Changes saved here and not yet confirmed by the server. */
  pending: number;
  /** Changes the server refused (shown so they can be looked at). */
  failed: number;
  conflicts: number;
  lastSyncAt?: string;
  lastError?: string;
  signedOut: boolean;
}

interface OpResult { opId: string; status: 'applied' | 'partial' | 'conflict' | 'rejected'; version?: number; errors?: string[] }
interface Change { entity: string; id: string; version: number; deleted: boolean; data: Record<string, unknown> }

/** Offline first: every change is written to the local copy and the queue at once; sync() sends the queue,
 *  then pulls what other devices changed. Nothing is removed from the queue without the server's answer. */
export class SyncEngine {
  status: SyncStatus = { online: true, syncing: false, pending: 0, failed: 0, conflicts: 0, signedOut: false };
  private listeners = new Set<() => void>();
  private running: Promise<void> | null = null;
  private soon: ReturnType<typeof setTimeout> | null = null;
  /** Send a moment after a change (several quick changes go in one push); 0 turns it off (tests). */
  autoSyncDelayMs = 800;
  private seq = Date.now() * 1000;

  private db: LocalDb;
  private session: Session;
  private fetchFn: typeof fetch;
  private base: string;

  constructor(db: LocalDb, session: Session, fetchFn: typeof fetch = fetch.bind(globalThis), base = '') {
    this.db = db;
    this.session = session;
    this.fetchFn = fetchFn;
    this.base = base;
  }

  subscribe(f: () => void): () => void {
    this.listeners.add(f);
    return () => this.listeners.delete(f);
  }

  private emit() { this.listeners.forEach((f) => f()); }

  private async refreshCounts() {
    const q = await this.db.queue();
    this.status = { ...this.status, pending: q.filter((o) => o.state !== 'error').length,
                    failed: q.filter((o) => o.state === 'error').length };
    this.emit();
  }

  async init() { await this.refreshCounts(); }

  // MARK: Local changes

  /** Saves the fields of a record here and queues them. Returns the record id. */
  async save(entity: string, id: string | null, fields: Record<string, unknown>): Promise<string> {
    const recordId = id ?? newId();
    const existing = await this.db.get(entity, recordId);
    const changed: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(fields)) {
      if (!existing || JSON.stringify(existing.data[k] ?? null) !== JSON.stringify(v ?? null)) changed[k] = v;
    }
    if (existing && !Object.keys(changed).length && !existing.deleted) return recordId;
    const record: LocalRecord = { key: `${entity}:${recordId}`, entity, id: recordId, version: existing?.version ?? 0,
                                  data: { ...(existing?.data ?? {}), ...changed }, deleted: false };
    // A queued change of the same record never sent takes these fields too: fewer operations, same result.
    // One that was sent (attempts > 0) may have been applied with the answer lost: the server would answer a repeat
    // with the old result and drop new fields, so those go in a new operation.
    const queued = (await this.db.queue()).find((o) => o.entity === entity && o.recordId === recordId
      && o.state === 'pending' && o.attempts === 0 && o.kind === 'upsert');
    const op: QueuedOp = queued
      ? { ...queued, fields: { ...queued.fields, ...changed } }
      : { opId: newId(), entity, recordId, kind: 'upsert', baseVersion: record.version, fields: changed,
          createdAt: new Date().toISOString(), state: 'pending', attempts: 0, seq: this.seq++ };
    await this.db.write(record, op);
    await this.refreshCounts();
    this.scheduleSync();
    return recordId;
  }

  async remove(entity: string, id: string): Promise<void> {
    const existing = await this.db.get(entity, id);
    if (!existing) return;
    const op: QueuedOp = { opId: newId(), entity, recordId: id, kind: 'delete', baseVersion: existing.version,
                           createdAt: new Date().toISOString(), state: 'pending', attempts: 0, seq: this.seq++ };
    await this.db.write({ ...existing, deleted: true }, op);
    await this.refreshCounts();
    this.scheduleSync();
  }

  private scheduleSync() {
    if (!this.autoSyncDelayMs) return;
    if (this.soon) clearTimeout(this.soon);
    this.soon = setTimeout(() => { this.soon = null; this.sync(); }, this.autoSyncDelayMs);
  }

  async list(entity: string): Promise<LocalRecord[]> {
    return (await this.db.all(entity)).filter((r) => !r.deleted);
  }

  /** A refused change is dropped by the owner; the next pull brings the server's version back. */
  async discard(opId: string) {
    const queue = await this.db.queue();
    const op = queue.find((o) => o.opId === opId);
    await this.db.removeOps([opId]);
    if (op) {
      // A new record the server never accepted disappears here; any other comes back with the next pull.
      const rec = await this.db.get(op.entity, op.recordId);
      const others = queue.some((o) => o.opId !== opId && o.entity === op.entity && o.recordId === op.recordId);
      if (rec && rec.version === 0 && !others) await this.db.putRecords([{ ...rec, deleted: true }]);
      await this.db.setMeta('cursor', 0);
    }
    await this.refreshCounts();
  }

  async retry(opId: string) {
    const op = (await this.db.queue()).find((o) => o.opId === opId);
    if (op) await this.db.putOps([{ ...op, state: 'pending', opId: newId(), attempts: 0, lastError: undefined }]);
    if (op) await this.db.removeOps([opId]);
    await this.refreshCounts();
  }

  // MARK: Sync

  private again = false;

  /** One sync at a time; a request during a sync (a change just made) runs one more right after it. */
  sync(): Promise<void> {
    if (this.running) {
      this.again = true;
      return this.running;
    }
    this.running = (async () => {
      do {
        this.again = false;
        await this.run();
      } while (this.again && this.status.online && !this.status.signedOut);
    })().finally(() => { this.running = null; });
    return this.running;
  }

  private async request(path: string, init: RequestInit = {}) {
    const r = await this.fetchFn(this.base + path, { ...init, headers: { 'content-type': 'application/json',
      authorization: `Bearer ${this.session.token}`, ...(init.headers ?? {}) } });
    if (r.status === 401) {
      this.status = { ...this.status, signedOut: true };
      throw new Error('Нужно войти заново');
    }
    if (!r.ok) throw new Error(`Сервер ответил ${r.status}`);
    return r.json();
  }

  private async run() {
    this.status = { ...this.status, syncing: true };
    this.emit();
    try {
      await this.pushAll();
      await this.pullAll();
      const c = await this.request('/conflicts');
      this.status = { ...this.status, online: true, conflicts: c.conflicts.length, lastSyncAt: new Date().toISOString(),
                      lastError: undefined };
    } catch (e) {
      const offline = e instanceof TypeError; // fetch failed: no network or no server
      this.status = { ...this.status, online: !offline, lastError: offline ? undefined : (e as Error).message };
    } finally {
      this.status = { ...this.status, syncing: false };
      await this.refreshCounts();
    }
  }

  /** Sends the queue in order, at most one op per record in a push: a later op of a record goes after the answer
   *  to the earlier one, with the version that answer gave (sent together, it would conflict with its own earlier op).
   *  The batch stops at the first op of a record already in it: nothing queued after that op goes before it
   *  (the server checks the herd against what it already has, so order across records matters too). */
  private async pushAll() {
    for (;;) {
      const seen = new Set<string>();
      const batch: QueuedOp[] = [];
      for (const o of await this.db.queue()) {
        if (o.state === 'error') continue;
        const key = `${o.entity}:${o.recordId}`;
        if (seen.has(key) || batch.length >= 200) break;
        seen.add(key);
        batch.push(o);
      }
      if (!batch.length) return;
      await this.db.putOps(batch.map((o) => ({ ...o, state: 'sending' as const })));
      let results: OpResult[];
      try {
        results = (await this.request('/sync/push', { method: 'POST', body: JSON.stringify({ ops: batch.map((o) => ({
          opId: o.opId, entity: o.entity, recordId: o.recordId, kind: o.kind, baseVersion: o.baseVersion, fields: o.fields,
        })) }) })).results;
      } catch (e) {
        // No answer: the operations stay queued and go again next time (the server ignores repeats).
        await this.db.putOps(batch.map((o) => ({ ...o, state: 'pending' as const, attempts: o.attempts + 1,
                                                 lastError: (e as Error).message })));
        throw e;
      }
      const confirmed: string[] = [];
      for (const r of results) {
        const op = batch.find((o) => o.opId === r.opId);
        if (!op) continue;
        if (r.status === 'rejected') {
          await this.db.putOps([{ ...op, state: 'error', attempts: op.attempts + 1, lastError: (r.errors ?? []).join('; ') }]);
          continue;
        }
        confirmed.push(op.opId);
        if ((r.status === 'applied' || r.status === 'partial') && r.version) {
          const rec = await this.db.get(op.entity, op.recordId);
          if (rec) await this.db.putRecords([{ ...rec, version: r.version }]);
          // Later changes of this record were made on top of this one.
          const later = (await this.db.queue()).filter((o) => o.entity === op.entity && o.recordId === op.recordId
            && o.opId !== op.opId && o.seq > op.seq);
          if (later.length) await this.db.putOps(later.map((o) => ({ ...o, baseVersion: r.version! })));
        }
      }
      await this.db.removeOps(confirmed);
      if (results.length < batch.length) return;
    }
  }

  private async pullAll() {
    let since = (await this.db.meta<number>('cursor')) ?? 0;
    for (;;) {
      const page: { changes: Change[]; next: number; more: boolean } = await this.request(`/sync/pull?since=${since}`);
      const queue = await this.db.queue();
      const records: LocalRecord[] = [];
      for (const c of page.changes) {
        const mine = queue.filter((o) => o.entity === c.entity && o.recordId === c.id);
        // The server's version, with this device's unsent changes kept on top.
        let data = c.data;
        for (const o of mine) if (o.kind === 'upsert') data = { ...data, ...o.fields };
        const deletedHere = mine.some((o) => o.kind === 'delete');
        records.push({ key: `${c.entity}:${c.id}`, entity: c.entity, id: c.id, version: c.version, data,
                       deleted: c.deleted || deletedHere });
      }
      await this.db.putRecords(records);
      since = page.next;
      await this.db.setMeta('cursor', since);
      if (!page.more) return;
    }
  }

  async conflicts() { return (await this.request('/conflicts')).conflicts as {
    id: string; entity: string; recordId: string; field: string; serverValue: unknown; clientValue: unknown; createdAt: string }[]; }

  async resolve(id: string, choice: 'server' | 'client') {
    await this.request(`/conflicts/${id}/resolve`, { method: 'POST', body: JSON.stringify({ choice }) });
    await this.sync();
  }

  async failedOps() { return (await this.db.queue()).filter((o) => o.state === 'error'); }

  async call(path: string, init: RequestInit = {}) { return this.request(path, init); }
}
