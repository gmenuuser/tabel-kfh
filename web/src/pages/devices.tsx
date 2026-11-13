import { useEffect, useState } from 'react';
import { useApp } from '../app.tsx';
import { sortDevices, type Device } from '../lib/devices.ts';

const PLATFORM: Record<string, string> = { android: 'Android', ios: 'iPhone', web: 'Сайт' };

/** «Устройства»: the devices of the farm and the «new devices» switch. Owner only (the admin page). */
export function DevicesSection({ note }: { note: (text: string) => void }) {
  const { engine, farm, setFarm } = useApp();
  const [devices, setDevices] = useState<Device[]>([]);
  const [editing, setEditing] = useState<{ id: string; name: string } | null>(null);
  const allowed = farm.newDevicesAllowed !== false;

  async function load() {
    try { setDevices(sortDevices(await engine.call('/devices'))); } catch { /* offline: the list comes back with the connection */ }
  }
  useEffect(() => { load(); }, [engine]);

  const act = async (f: () => Promise<unknown>, done = '') => {
    try { await f(); if (done) note(done); await load(); return true; } catch (e) { note((e as Error).message); return false; }
  };

  return (
    <section className="card">
      <h3>Устройства</h3>
      <label className="inline switch">
        <input type="checkbox" checked={allowed} onChange={(e) => {
          const value = e.target.checked;
          act(async () => {
            await engine.call('/farm', { method: 'PATCH', body: JSON.stringify({ newDevicesAllowed: value }) });
            setFarm({ ...farm, newDevicesAllowed: value });
          }, value ? 'Новые устройства пускаются' : 'Новые устройства не пускаются');
        }} />
        Новые устройства: пускать / не пускать
      </label>
      <p className="muted">Когда выключено, приложение на новом телефоне не подключится. Уже подключённые продолжают работать.</p>
      {devices.length === 0 && <p className="muted">Устройств пока нет.</p>}
      {devices.map((d) => (
        <div key={d.id} className="device-row line" style={d.revoked ? { opacity: 0.5 } : undefined}>
          {editing?.id === d.id ? (
            <form className="row" onSubmit={async (e) => {
              e.preventDefault();
              const name = editing.name.trim();
              if (!name) { note('Введите название устройства'); return; }
              if (await act(() => engine.call(`/devices/${d.id}`, { method: 'PATCH', body: JSON.stringify({ name }) }), 'Название сохранено')) setEditing(null);
            }}>
              <input value={editing.name} maxLength={100} autoFocus onChange={(e) => setEditing({ id: d.id, name: e.target.value })} />
              <button>Сохранить</button>
              <button type="button" onClick={() => setEditing(null)}>Отмена</button>
            </form>
          ) : (
            <span>
              <strong>{d.name || PLATFORM[d.platform] || d.platform}</strong>
              {' · '}{PLATFORM[d.platform] ?? d.platform}
              {d.current ? ' · это устройство' : ''}{d.revoked ? ' · отключено' : ''}
            </span>
          )}
          <span className="muted device-seen">{d.lastSeenAt ? `был(о) на связи ${new Date(d.lastSeenAt).toLocaleString('ru-RU')}` : 'не было на связи'}</span>
          {!d.revoked && !d.current && editing?.id !== d.id && (
            <span className="device-actions">
              <button onClick={() => setEditing({ id: d.id, name: d.name })}>Переименовать</button>
              <button className="danger" onClick={() => {
                if (confirm(`Отключить «${d.name || d.platform}»? Устройство потеряет доступ.`)) act(() => engine.call(`/devices/${d.id}/revoke`, { method: 'POST' }), 'Устройство отключено');
              }}>Отключить</button>
            </span>
          )}
        </div>
      ))}
    </section>
  );
}
