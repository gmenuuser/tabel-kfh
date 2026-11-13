import { createContext, useContext, useEffect, useMemo, useState, useSyncExternalStore, type ReactNode } from 'react';
import { LocalDb, type LocalRecord } from './lib/db.ts';
import { SyncEngine, type Session } from './lib/sync.ts';
import { setFarmZone } from './lib/time.ts';

export interface Farm { id: string; name: string; region: string; managerName: string; timezone: string; newDevicesAllowed?: boolean }

interface Ctx { engine: SyncEngine; session: Session; farm: Farm; signOut: () => void; setFarm: (f: Farm) => void }
const AppContext = createContext<Ctx | null>(null);

export function useApp(): Ctx {
  const c = useContext(AppContext);
  if (!c) throw new Error('no app');
  return c;
}

export function useStatus() {
  const { engine } = useApp();
  return useSyncExternalStore((f) => engine.subscribe(f), () => engine.status);
}

/** The live records of an entity (not deleted); reloads after every local change and every sync. */
export function useRecords(entity: string): LocalRecord[] {
  const { engine } = useApp();
  const [rows, setRows] = useState<LocalRecord[]>([]);
  useEffect(() => {
    let alive = true;
    const load = () => { engine.list(entity).then((r) => { if (alive) setRows(r); }); };
    load();
    const off = engine.subscribe(load);
    return () => { alive = false; off(); };
  }, [engine, entity]);
  return rows;
}

const SESSION_KEY = 'kfh.session';

export function loadSession(): Session | null {
  try { return JSON.parse(localStorage.getItem(SESSION_KEY) ?? 'null'); } catch { return null; }
}
export function saveSession(s: Session | null) {
  if (s) localStorage.setItem(SESSION_KEY, JSON.stringify(s)); else localStorage.removeItem(SESSION_KEY);
}

export function AppProvider({ session, onSignedOut, children }: { session: Session; onSignedOut: () => void; children: ReactNode }) {
  const [state, setState] = useState<{ engine: SyncEngine; farm: Farm } | null>(null);
  const [error, setError] = useState('');

  useEffect(() => {
    let engine: SyncEngine | null = null;
    let timer: ReturnType<typeof setInterval> | undefined;
    const kick = () => { engine?.sync(); };
    (async () => {
      const db = await LocalDb.open(indexedDB, `kfh-${session.farmId}-${session.userId}`);
      engine = new SyncEngine(db, session);
      await engine.init();
      const cachedFarm = await db.meta<Farm>('farm');
      let farm = cachedFarm;
      try {
        const me = await engine.call('/me');
        farm = me.farm;
        await db.setMeta('farm', farm);
      } catch {
        // No internet: the farm as it was last seen.
      }
      if (!farm) { setError('Нет связи с сервером. Подключитесь к интернету для первого входа.'); return; }
      setFarmZone(farm.timezone);
      setState({ engine, farm });
      engine.sync();
      // Sync when the connection comes back, when the tab is shown again, and every minute.
      window.addEventListener('online', kick);
      document.addEventListener('visibilitychange', kick);
      timer = setInterval(kick, 60_000);
    })();
    return () => {
      window.removeEventListener('online', kick);
      document.removeEventListener('visibilitychange', kick);
      if (timer) clearInterval(timer);
    };
  }, [session]);

  const ctx = useMemo<Ctx | null>(() => state && {
    engine: state.engine, session, farm: state.farm,
    signOut: onSignedOut, setFarm: (farm) => setState((s) => (s ? { ...s, farm } : s)),
  }, [state, session, onSignedOut]);

  if (error) return <div className="page"><div className="card error">{error}</div></div>;
  if (!ctx) return <div className="page"><div className="muted">Загрузка…</div></div>;
  return <AppContext.Provider value={ctx}>{children}</AppContext.Provider>;
}

/** The sync line under the title: what is saved where. */
export function SyncBadge() {
  const { engine } = useApp();
  const s = useStatus();
  let text: string;
  let cls = 'ok';
  if (s.signedOut) { text = 'Нужно войти заново'; cls = 'bad'; }
  else if (s.syncing) { text = 'Синхронизация…'; cls = 'busy'; }
  else if (!s.online) { text = `Нет интернета. Все изменения сохранены на устройстве${s.pending ? `. Ожидают отправки: ${s.pending}` : ''}`; cls = 'warn'; }
  else if (s.failed) { text = `Ошибка синхронизации: ${s.failed} — см. «Админку»`; cls = 'bad'; }
  else if (s.pending) { text = `Есть несинхронизированные данные: ${s.pending}`; cls = 'warn'; }
  else if (s.conflicts) { text = `Конфликтов: ${s.conflicts} — выберите версию в «Админке»`; cls = 'warn'; }
  else if (s.lastError) { text = `Ошибка: ${s.lastError}`; cls = 'bad'; }
  else { text = 'Все данные синхронизированы'; }
  return (
    <div className={`sync ${cls}`} role="status">
      <span>{text}</span>
      <button className="link" onClick={() => engine.sync()} disabled={s.syncing}>Синхронизировать сейчас</button>
    </div>
  );
}

export function money(kopecks: number) { return kopecks; }
