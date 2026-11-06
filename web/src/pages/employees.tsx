import { useState } from 'react';
import { useApp, useRecords } from '../app.tsx';
import type { LocalRecord } from '../lib/db.ts';
import { dayKey, dayStartISO, todayKey } from '../lib/time.ts';
import { fullName } from './timesheet.tsx';

interface Draft { lastName: string; firstName: string; patronymic: string; position: string; hire: string; dismissal: string }

const empty = (): Draft => ({ lastName: '', firstName: '', patronymic: '', position: '', hire: todayKey(), dismissal: '' });

/** «Сотрудники»: add, edit, archive and back; archiving keeps every mark in the timesheets. */
export function EmployeesPage() {
  const { engine } = useApp();
  const employees = useRecords('employee');
  const [showArchived, setShowArchived] = useState(false);
  const [editing, setEditing] = useState<{ id: string | null; d: Draft } | null>(null);
  const [error, setError] = useState('');
  const list = employees.filter((e) => !!e.data.isArchived === showArchived).sort((a, b) => fullName(a).localeCompare(fullName(b), 'ru'));

  const open = (e: LocalRecord | null) => setEditing(e ? { id: e.id, d: {
    lastName: String(e.data.lastName ?? ''), firstName: String(e.data.firstName ?? ''), patronymic: String(e.data.patronymic ?? ''),
    position: String(e.data.position ?? ''), hire: e.data.hireDate ? dayKey(String(e.data.hireDate)) : todayKey(),
    dismissal: e.data.dismissalDate ? dayKey(String(e.data.dismissalDate)) : '' } } : { id: null, d: empty() });

  async function save() {
    if (!editing) return;
    const d = editing.d;
    if (!d.lastName.trim() || !d.firstName.trim()) { setError('Укажите фамилию и имя'); return; }
    if (d.dismissal && d.dismissal < d.hire) { setError('Дата увольнения раньше даты приёма'); return; }
    await engine.save('employee', editing.id, {
      lastName: d.lastName.trim(), firstName: d.firstName.trim(), patronymic: d.patronymic.trim(), position: d.position.trim(),
      hireDate: dayStartISO(d.hire), dismissalDate: d.dismissal ? dayStartISO(d.dismissal) : null,
      ...(editing.id ? {} : { isArchived: false, workSchedule: 'fiveTwo', standardWorkHours: 8, defaultBreakMinutes: 60 }),
    });
    setEditing(null);
    setError('');
  }

  const field = (k: keyof Draft, title: string, type = 'text') => (
    <label>{title}<input type={type} value={editing!.d[k]} onChange={(e) => setEditing({ ...editing!, d: { ...editing!.d, [k]: e.target.value } })} /></label>
  );

  return (
    <>
      <div className="row between">
        <h2>Сотрудники</h2>
        <button className="big" onClick={() => open(null)}>+ Добавить сотрудника</button>
      </div>
      <div className="segmented">
        <button className={!showArchived ? 'active' : ''} onClick={() => setShowArchived(false)}>Работают</button>
        <button className={showArchived ? 'active' : ''} onClick={() => setShowArchived(true)}>Архив</button>
      </div>
      {!list.length && <p className="muted">{showArchived ? 'В архиве никого нет.' : 'Сотрудников пока нет.'}</p>}
      {list.map((e) => (
        <section key={e.id} className="card row between">
          <div>
            <div className="name">{fullName(e)}</div>
            <div className="muted">{[e.data.position, e.data.hireDate ? `с ${dayKey(String(e.data.hireDate)).split('-').reverse().join('.')}` : ''].filter(Boolean).join(' · ')}</div>
          </div>
          <div className="row">
            <button onClick={() => open(e)}>Изменить</button>
            <button onClick={() => engine.save('employee', e.id, { isArchived: !e.data.isArchived })}>
              {e.data.isArchived ? 'Вернуть из архива' : 'В архив'}
            </button>
          </div>
        </section>
      ))}
      {editing && (
        <div className="modal" onClick={() => setEditing(null)}>
          <form className="card form" onClick={(ev) => ev.stopPropagation()} onSubmit={(ev) => { ev.preventDefault(); save(); }}>
            <h3>{editing.id ? 'Сотрудник' : 'Новый сотрудник'}</h3>
            {field('lastName', 'Фамилия')}
            {field('firstName', 'Имя')}
            {field('patronymic', 'Отчество')}
            {field('position', 'Должность')}
            {field('hire', 'Дата приёма', 'date')}
            {field('dismissal', 'Дата увольнения', 'date')}
            {error && <div className="error">{error}</div>}
            <div className="row">
              <button className="big">Сохранить</button>
              <button type="button" onClick={() => setEditing(null)}>Отмена</button>
            </div>
          </form>
        </div>
      )}
    </>
  );
}
