import { useEffect, useState } from 'react';
import { useApp } from '../app.tsx';

export interface AccessKey {
  id: string; personName: string; role: string;
  status: 'free' | 'paired' | 'expired' | 'revoked';
  expiresAt: string; createdAt: string; userId?: string;
  deviceName?: string; devicePlatform?: string;
}

const ROLE: Record<string, string> = {
  manager: 'Всё, кроме настроек', timesheet: 'Табель', coal: 'Уголь', livestock: 'Скот', viewer: 'Только чтение',
};
const STATUS: Record<string, string> = { free: 'свободен', paired: 'привязан', expired: 'просрочен', revoked: 'отозван' };
const PLATFORM: Record<string, string> = { android: 'Android', ios: 'iPhone', web: 'Сайт' };

/**
 * «Ключи доступа»: a 4-digit code per person. The code itself is shown once, right after it is made; afterwards
 * only its state is visible, and a lost code is replaced («Новый код») — that also cuts the old phones off.
 */
export function KeysSection({ note }: { note: (text: string) => void }) {
  const { engine } = useApp();
  const [keys, setKeys] = useState<AccessKey[]>([]);
  const [form, setForm] = useState({ personName: '', role: 'manager', days: 1 });
  const [shown, setShown] = useState<{ code: string; for: string } | null>(null);
  const [busy, setBusy] = useState(false);

  async function load() {
    try { setKeys((await engine.call('/keys')).keys as AccessKey[]); } catch { /* offline: the list comes back with the connection */ }
  }
  useEffect(() => { load(); }, [engine]);

  async function run(f: () => Promise<unknown>, done = '') {
    setBusy(true);
    try { await f(); if (done) note(done); await load(); }
    catch (e) { note((e as Error).message); }
    finally { setBusy(false); }
  }

  const create = () => run(async () => {
    const name = form.personName.trim();
    const r = await engine.call('/keys', { method: 'POST', body: JSON.stringify({ ...form, personName: name }) });
    setShown({ code: r.code, for: name });
    setForm({ personName: '', role: form.role, days: form.days });
  }, 'Ключ создан');

  const second = (k: AccessKey) => run(async () => {
    const r = await engine.call(`/people/${k.userId}/keys`, { method: 'POST', body: JSON.stringify({ days: form.days }) });
    setShown({ code: r.code, for: k.personName });
  }, 'Код для второго устройства создан');

  const reissue = (k: AccessKey) => {
    if (!confirm(`Выдать новый код «${k.personName}»? Все устройства этого человека отключатся.`)) return;
    run(async () => {
      const r = await engine.call(`/keys/${k.id}/reissue`, { method: 'POST', body: JSON.stringify({ days: form.days }) });
      setShown({ code: r.code, for: k.personName });
    }, 'Новый код создан');
  };

  const revoke = (k: AccessKey) => {
    if (!confirm(`Отозвать доступ «${k.personName}»? Устройства отключатся, код перестанет работать.`)) return;
    run(() => engine.call(`/keys/${k.id}/revoke`, { method: 'POST' }), 'Доступ отозван');
  };

  return (
    <section className="card">
      <h3>Ключи доступа</h3>
      <p className="muted">Человек вводит 4 цифры в приложении. Код одноразовый и действует ограниченное время.</p>

      {shown && (
        <div className="note key-code">
          <div>Код для «{shown.for}»: <strong className="key-code-value">{shown.code}</strong></div>
          <div className="key-code-actions">
            <button onClick={() => { navigator.clipboard?.writeText(shown.code).catch(() => {}); note('Код скопирован'); }}>Скопировать</button>
            <button onClick={() => setShown(null)}>Готово</button>
          </div>
          <div className="muted">Показывается один раз — передайте человеку сейчас.</div>
        </div>
      )}

      <div className="form key-form">
        <label>Имя
          <input value={form.personName} maxLength={100} placeholder="Например, Иван"
                 onChange={(e) => setForm({ ...form, personName: e.target.value })} />
        </label>
        <label>Роль
          <select value={form.role} onChange={(e) => setForm({ ...form, role: e.target.value })}>
            {Object.entries(ROLE).map(([id, title]) => <option key={id} value={id}>{title}</option>)}
          </select>
        </label>
        <label>Срок, дней
          <input type="number" min={1} max={30} value={form.days}
                 onChange={(e) => setForm({ ...form, days: Math.min(30, Math.max(1, Number(e.target.value) || 1)) })} />
        </label>
        <button disabled={busy || !form.personName.trim()} onClick={create}>Создать код</button>
      </div>

      {keys.length === 0 && <p className="muted">Ключей пока нет.</p>}
      {keys.map((k) => (
        <div key={k.id} className="key-row line" style={k.status === 'revoked' ? { opacity: 0.5 } : undefined}>
          <span>
            <strong>{k.personName}</strong>
            {' · '}{ROLE[k.role] ?? k.role}
            {' · '}{STATUS[k.status]}
            {k.deviceName ? ` · ${k.deviceName} (${PLATFORM[k.devicePlatform ?? ''] ?? k.devicePlatform})` : ''}
            {k.status === 'free' ? ` · до ${new Date(k.expiresAt).toLocaleString('ru-RU')}` : ''}
          </span>
          <span className="key-actions">
            {k.status === 'paired' && k.userId && <button onClick={() => second(k)}>Ещё устройство</button>}
            {k.status !== 'revoked' && <button onClick={() => reissue(k)}>Новый код</button>}
            {k.status !== 'revoked' && <button className="danger" onClick={() => revoke(k)}>Отозвать</button>}
          </span>
        </div>
      ))}
    </section>
  );
}
