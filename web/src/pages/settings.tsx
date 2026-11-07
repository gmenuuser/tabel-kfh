import { useEffect, useState } from 'react';
import { useApp, useStatus } from '../app.tsx';
import type { QueuedOp } from '../lib/db.ts';
import { download } from '../lib/xlsx.ts';

const ROLES: Record<string, string> = { owner: 'Владелец', manager: 'Управляющий', timesheet: 'Только табель', coal: 'Только уголь',
  livestock: 'Только скот', viewer: 'Только просмотр' };
const ENTITY: Record<string, string> = { employee: 'Сотрудник', hoursMark: 'Часы', coalSale: 'Продажа угля', driver: 'Водитель',
  driverPayment: 'Выплата', deliveryLocation: 'Населённый пункт', coalSettings: 'Цена угля', coalWorkDay: 'Рабочий день',
  livestockCategory: 'Категория скота', livestockOperation: 'Операция со скотом', slaughterRecord: 'Забой', agroCalculation: 'Расчёт' };

export function SettingsPage() {
  const { engine, session, farm, setFarm, signOut } = useApp();
  const status = useStatus();
  const owner = session.role === 'owner';
  const [form, setForm] = useState(farm);
  const [members, setMembers] = useState<{ id: string; login: string; email: string | null; name: string; role: string }[]>([]);
  const [devices, setDevices] = useState<{ id: string; name: string; platform: string; login: string; email: string | null; lastSeenAt: string | null; revoked: boolean; current: boolean }[]>([]);
  const [conflicts, setConflicts] = useState<Awaited<ReturnType<typeof engine.conflicts>>>([]);
  const [failed, setFailed] = useState<QueuedOp[]>([]);
  const [backups, setBackups] = useState<{ id: string; records: number; created_at: string }[]>([]);
  const [helper, setHelper] = useState({ login: '', password: '', role: 'timesheet' });
  const [note, setNote] = useState('');

  async function load() {
    setFailed(await engine.failedOps());
    try {
      setMembers(await engine.call('/members'));
      setDevices(await engine.call('/devices'));
      setConflicts(await engine.conflicts());
      if (owner) setBackups(await engine.call('/backups'));
    } catch { /* offline: the server lists come back with the connection */ }
  }
  useEffect(() => { load(); return engine.subscribe(() => { engine.failedOps().then(setFailed); }); }, [engine]);

  const act = async (f: () => Promise<unknown>, done = '') => {
    try { await f(); if (done) setNote(done); await load(); } catch (e) { setNote((e as Error).message); }
  };

  return (
    <>
      <h2>Настройки</h2>
      {note && <p className="note">{note}</p>}

      <section className="card form">
        <h3>Хозяйство</h3>
        {(['name', 'managerName', 'region', 'timezone'] as const).map((k) => (
          <label key={k}>{{ name: 'Название', managerName: 'Руководитель', region: 'Регион', timezone: 'Часовой пояс' }[k]}
            <input value={form[k]} disabled={!owner} onChange={(e) => setForm({ ...form, [k]: e.target.value })} /></label>
        ))}
        {owner && <button onClick={() => act(async () => { await engine.call('/farm', { method: 'PATCH', body: JSON.stringify(form) }); setFarm(form); }, 'Сохранено')}>Сохранить</button>}
      </section>

      <section className="card">
        <h3>Синхронизация</h3>
        <p>{status.online ? 'Онлайн' : 'Нет интернета'} · ожидают отправки: {status.pending} · ошибок: {status.failed} · конфликтов: {status.conflicts}</p>
        {status.lastSyncAt && <p className="muted">Последняя синхронизация: {new Date(status.lastSyncAt).toLocaleString('ru-RU')}</p>}
        <button onClick={() => act(() => engine.sync(), 'Синхронизировано')}>Синхронизировать сейчас</button>
      </section>

      {conflicts.length > 0 && (
        <section className="card">
          <h3>Конфликты: выберите версию</h3>
          <p className="muted">Одно и то же изменили на двух устройствах. Ничего не потеряно: обе версии сохранены.</p>
          {conflicts.map((c) => (
            <div key={c.id} className="row between line">
              <span>{ENTITY[c.entity] ?? c.entity} · {c.field === '__deleted' ? 'удаление' : c.field}</span>
              <button onClick={() => act(() => engine.resolve(c.id, 'server'), 'Оставлена версия сервера')}>Оставить: {JSON.stringify(c.serverValue)}</button>
              <button onClick={() => act(() => engine.resolve(c.id, 'client'), 'Принята версия устройства')}>Взять: {JSON.stringify(c.clientValue)}</button>
            </div>
          ))}
        </section>
      )}

      {failed.length > 0 && (
        <section className="card">
          <h3>Не принято сервером</h3>
          {failed.map((o) => (
            <div key={o.opId} className="row between line">
              <span>{ENTITY[o.entity] ?? o.entity}: {o.lastError}</span>
              <button onClick={() => act(() => engine.retry(o.opId))}>Отправить снова</button>
              <button className="danger" onClick={() => act(() => engine.discard(o.opId))}>Отменить изменение</button>
            </div>
          ))}
        </section>
      )}

      {owner && (
        <section className="card">
          <h3>Помощники</h3>
          {members.filter((m) => m.role !== 'owner').map((m) => (
            <div key={m.id} className="row between line">
              <span>{m.login || m.email}</span>
              <select value={m.role} onChange={(e) => act(() => engine.call(`/members/${m.id}`, { method: 'PATCH', body: JSON.stringify({ role: e.target.value }) }), 'Роль изменена')}>
                {Object.entries(ROLES).filter(([k]) => k !== 'owner').map(([k, v]) => <option key={k} value={k}>{v}</option>)}
              </select>
              <button onClick={() => {
                const password = prompt(`Новый пароль для ${m.login || m.email} (не короче 8 символов)`);
                if (password) act(() => engine.call(`/members/${m.id}/password`, { method: 'POST', body: JSON.stringify({ password }) }), 'Пароль изменён');
              }}>Новый пароль</button>
              <button className="danger" onClick={() => { if (confirm(`Отключить ${m.login || m.email}?`)) act(() => engine.call(`/members/${m.id}`, { method: 'DELETE' }), 'Помощник отключён'); }}>Отключить</button>
            </div>
          ))}
          <form className="row" onSubmit={(e) => {
            e.preventDefault();
            if (!helper.login.trim()) { setNote('Введите логин помощника'); return; }
            act(async () => {
              await engine.call('/members', { method: 'POST', body: JSON.stringify({ ...helper, login: helper.login.trim() }) });
              setHelper({ login: '', password: '', role: 'timesheet' });
            }, 'Помощник добавлен');
          }}>
            <input placeholder="Логин" autoCapitalize="none" value={helper.login} onChange={(e) => setHelper({ ...helper, login: e.target.value })} required />
            <input type="password" placeholder="Пароль" autoComplete="new-password" minLength={8} value={helper.password} onChange={(e) => setHelper({ ...helper, password: e.target.value })} required />
            <select value={helper.role} onChange={(e) => setHelper({ ...helper, role: e.target.value })}>
              {Object.entries(ROLES).filter(([k]) => k !== 'owner').map(([k, v]) => <option key={k} value={k}>{v}</option>)}
            </select>
            <button>Добавить помощника</button>
          </form>
          <p className="muted">Помощник войдёт на iPhone или здесь по логину и паролю. После смены роли или пароля ему нужно войти заново.</p>
        </section>
      )}

      <section className="card">
        <h3>Подключённые устройства</h3>
        {devices.map((d) => (
          <div key={d.id} className="row between line">
            <span>{d.name || d.platform} · {d.login || d.email} {d.current ? '(это устройство)' : ''} {d.revoked ? '— отключено' : ''}</span>
            <span className="muted">{d.lastSeenAt ? new Date(d.lastSeenAt).toLocaleString('ru-RU') : ''}</span>
            {!d.revoked && !d.current && <button className="danger" onClick={() => { if (confirm('Отозвать доступ этого устройства?')) act(() => engine.call(`/devices/${d.id}/revoke`, { method: 'POST' }), 'Доступ отозван'); }}>Отозвать доступ</button>}
          </div>
        ))}
      </section>

      <section className="card">
        <h3>Резервные копии</h3>
        <p className="muted">Синхронизация — не замена копии. Копия на этом устройстве — файл со всеми данными, которые здесь есть.</p>
        <button onClick={async () => {
          const entities = Object.keys(ENTITY);
          const data: Record<string, unknown> = {};
          for (const e of entities) data[e] = await engine.list(e);
          download(`КФХ-копия-${new Date().toISOString().slice(0, 10)}.json`, new TextEncoder().encode(JSON.stringify({ format: 'kfh-web-backup', farm, data })), 'application/json');
        }}>Сохранить копию на устройство</button>
        {owner && (
          <>
            <button onClick={() => act(() => engine.call('/backups', { method: 'POST' }), 'Копия на сервере создана')}>Создать копию на сервере</button>
            {backups.map((b) => (
              <div key={b.id} className="row between line">
                <span>{new Date(b.created_at).toLocaleString('ru-RU')} · записей: {b.records}</span>
                <button onClick={() => act(async () => { const r = await engine.call(`/backups/${b.id}/verify`); setNote(r.ok ? 'Копия цела' : 'Копия повреждена'); })}>Проверить</button>
                <button className="danger" onClick={() => { if (confirm('Восстановить эту копию? Текущие данные хозяйства на всех устройствах будут заменены.')) act(() => engine.call(`/backups/${b.id}/restore`, { method: 'POST', body: JSON.stringify({ confirm: true }) }).then(() => engine.sync()), 'Восстановлено'); }}>Восстановить</button>
              </div>
            ))}
          </>
        )}
      </section>

      <button className="danger" onClick={() => {
        if (status.pending && !confirm(`Есть ${status.pending} неотправленных изменений. Если выйти, они останутся на этом устройстве до следующего входа. Выйти?`)) return;
        engine.call('/auth/logout', { method: 'POST' }).catch(() => {}).finally(signOut);
      }}>Выйти</button>
    </>
  );
}
