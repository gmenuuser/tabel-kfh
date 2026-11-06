// The device's own copy of the farm (IndexedDB): records, the queue of changes not yet confirmed by the server,
// and small settings (session, sync cursor). Everything survives closing the browser.

export interface LocalRecord {
  key: string; // `${entity}:${id}`
  entity: string;
  id: string;
  /** The server version this copy is based on; 0 = never confirmed by the server. */
  version: number;
  data: Record<string, unknown>;
  deleted: boolean;
}

export type OpState = 'pending' | 'sending' | 'error';

export interface QueuedOp {
  opId: string;
  entity: string;
  recordId: string;
  kind: 'upsert' | 'delete';
  baseVersion: number;
  fields?: Record<string, unknown>;
  createdAt: string;
  state: OpState;
  attempts: number;
  lastError?: string;
  /** Monotonic order of the queue. */
  seq: number;
}

const DB_NAME = 'kfh';
const DB_VERSION = 1;

function req<T>(r: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    r.onsuccess = () => resolve(r.result);
    r.onerror = () => reject(r.error);
  });
}

function done(t: IDBTransaction): Promise<void> {
  return new Promise((resolve, reject) => {
    t.oncomplete = () => resolve();
    t.onerror = () => reject(t.error);
    t.onabort = () => reject(t.error);
  });
}

export class LocalDb {
  private db: IDBDatabase;

  private constructor(db: IDBDatabase) {
    this.db = db;
  }

  static async open(factory: IDBFactory = indexedDB, name = DB_NAME): Promise<LocalDb> {
    const open = factory.open(name, DB_VERSION);
    open.onupgradeneeded = () => {
      const db = open.result;
      if (!db.objectStoreNames.contains('records')) {
        const s = db.createObjectStore('records', { keyPath: 'key' });
        s.createIndex('entity', 'entity');
      }
      if (!db.objectStoreNames.contains('outbox')) db.createObjectStore('outbox', { keyPath: 'opId' });
      if (!db.objectStoreNames.contains('meta')) db.createObjectStore('meta');
    };
    return new LocalDb(await req(open));
  }

  close() { this.db.close(); }

  async all(entity: string): Promise<LocalRecord[]> {
    const t = this.db.transaction('records', 'readonly');
    return req(t.objectStore('records').index('entity').getAll(entity)) as Promise<LocalRecord[]>;
  }

  async get(entity: string, id: string): Promise<LocalRecord | undefined> {
    const t = this.db.transaction('records', 'readonly');
    return req(t.objectStore('records').get(`${entity}:${id}`)) as Promise<LocalRecord | undefined>;
  }

  /** Writes the record and (when given) queues the change, in one transaction: never one without the other. */
  async write(record: LocalRecord, op?: QueuedOp): Promise<void> {
    const t = this.db.transaction(['records', 'outbox'], 'readwrite');
    t.objectStore('records').put(record);
    if (op) t.objectStore('outbox').put(op);
    await done(t);
  }

  async putRecords(records: LocalRecord[]): Promise<void> {
    const t = this.db.transaction('records', 'readwrite');
    for (const r of records) t.objectStore('records').put(r);
    await done(t);
  }

  async queue(): Promise<QueuedOp[]> {
    const t = this.db.transaction('outbox', 'readonly');
    const ops = (await req(t.objectStore('outbox').getAll())) as QueuedOp[];
    return ops.sort((a, b) => a.seq - b.seq);
  }

  async putOps(ops: QueuedOp[]): Promise<void> {
    const t = this.db.transaction('outbox', 'readwrite');
    for (const o of ops) t.objectStore('outbox').put(o);
    await done(t);
  }

  async removeOps(ids: string[]): Promise<void> {
    const t = this.db.transaction('outbox', 'readwrite');
    for (const id of ids) t.objectStore('outbox').delete(id);
    await done(t);
  }

  async meta<T>(key: string): Promise<T | undefined> {
    const t = this.db.transaction('meta', 'readonly');
    return req(t.objectStore('meta').get(key)) as Promise<T | undefined>;
  }

  async setMeta(key: string, value: unknown): Promise<void> {
    const t = this.db.transaction('meta', 'readwrite');
    if (value === undefined) t.objectStore('meta').delete(key);
    else t.objectStore('meta').put(value, key);
    await done(t);
  }

  /** Signing out of the farm: the local copy goes, unless changes are still waiting (the caller checks). */
  async clearAll(): Promise<void> {
    const t = this.db.transaction(['records', 'outbox', 'meta'], 'readwrite');
    for (const s of ['records', 'outbox', 'meta']) t.objectStore(s).clear();
    await done(t);
  }
}
