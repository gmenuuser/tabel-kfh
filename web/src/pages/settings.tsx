import { useEffect, useState } from 'react';
import { useApp, useStatus } from '../app.tsx';
import type { QueuedOp } from '../lib/db.ts';
import { download } from '../lib/xlsx.ts';
import { AppLinks } from './app-links.tsx';
import { DevicesSection } from './devices.tsx';

const ENTITY: Record<string, string> = { employee: 'Сотрудник', hoursMark: 'Часы', coalSale: 'Продажа угля', driver: 'Водитель',
  driverPayment: 'Выплата', deliveryLocation: 'Населённый пункт', coalSettings: 'Цена угля', coalWorkDay: 'Рабочий день',
  livestockCategory: 'Категория скота', livestockOperation: 'Операция со скотом', slaughterRecord: 'Забой', agroCalculation: 'Расчёт' };

export function SettingsPage() {
  const { engine, session, farm, setFarm, signOut } = useApp();
  const status = useStatus();
  const owner = session.role === 'owner';
  const [form, setForm] = useState(farm);
  const [conflicts, setConflicts] = useState<Awaited<ReturnType<typeof engine.conflicts>>>([]);
  const [failed, setFailed] = useState<QueuedOp[]>([]);
  const [backups, setBackups] = useState<{ id: string; records: number; created_at: string }[]>([]);
  const [note, setNote] = useState('');

  async function load() {
    setFailed(await engine.failedOps());
    try {
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
      <h2>Админка</h2>
      {note && <p className="note">{note}</p>}

      <section className="card form">
        <h3>Хозяйство</h3>
        {(['name', 'managerName', 'region', 'timezone'] as const).map((k) => (
          <label key={k}>{{ name: 'Название', managerName: 'Руководитель', region: 'Регион', timezone: 'Часовой пояс' }[k]}
            <input value={form[k]} disabled={!owner} onChange={(e) => setForm({ ...form, [k]: e.target.value })} /></label>
        ))}
        {owner && <button onClick={() => act(async () => { await engine.call('/farm', { method: 'PATCH', body: JSON.stringify({ name: form.name, managerName: form.managerName, region: form.region, timezone: form.timezone }) }); setFarm({ ...farm, name: form.name, managerName: form.managerName, region: form.region, timezone: form.timezone }); }, 'Сохранено')}>Сохранить</button>}
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

      <DevicesSection note={setNote} />

      <AppLinks />

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
