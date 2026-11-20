import { useState } from 'react';
import { useApp, useRecords } from '../app.tsx';
import { canEdit } from '../lib/roles.ts';
import { areaHa, culturesOf, fieldErrors, parseAreaSqM, sortFields } from '../lib/fields.ts';
import type { LocalRecord } from '../lib/db.ts';

interface Draft { name: string; area: string; culture: string; note: string }

const empty = (): Draft => ({ name: '', area: '', culture: '', note: '' });

/** «Поля»: the farm's fields — name, area, culture, note. The agro calculators take the area from here. */
export function FieldsPage() {
  const { engine, session } = useApp();
  const editable = canEdit(session.role);
  const fields = sortFields(useRecords('field'));
  const [editing, setEditing] = useState<{ id: string | null; d: Draft } | null>(null);
  const cultures = culturesOf(fields);

  const open = (f: LocalRecord | null) => setEditing(f ? { id: f.id, d: {
    name: String(f.data.name ?? ''), area: areaHa(Number(f.data.areaSqM ?? 0)),
    culture: String(f.data.culture ?? ''), note: String(f.data.note ?? '') } } : { id: null, d: empty() });

  const sqM = editing ? parseAreaSqM(editing.d.area) : null;
  const errors = editing ? fieldErrors({ name: editing.d.name, areaSqM: sqM ?? 0, culture: editing.d.culture, note: editing.d.note }) : [];

  async function save() {
    if (!editing || sqM === null || errors.length) return;
    await engine.save('field', editing.id, {
      name: editing.d.name.trim(), areaSqM: sqM,
      culture: editing.d.culture.trim(), note: editing.d.note.trim(),
    });
    setEditing(null);
  }

  const field = (k: keyof Draft, title: string, unit = '') => (
    <label>{title}
      <span className={unit ? 'unit-input' : undefined}>
        <input value={editing!.d[k]} list={k === 'culture' ? 'field-cultures' : undefined}
               onChange={(e) => setEditing({ ...editing!, d: { ...editing!.d, [k]: e.target.value } })} />
        {unit && <span>{unit}</span>}
      </span>
    </label>
  );

  return (
    <>
      <div className="row between">
        <h2>Поля</h2>
        {editable && <button className="big" onClick={() => open(null)}>+ Добавить поле</button>}
      </div>
      {!fields.length && <p className="muted">Полей пока нет. Добавьте поле — калькуляторы возьмут из него площадь.</p>}
      {fields.map((f) => (
        <section key={f.id} className="card row between">
          <div>
            <div className="name">{String(f.data.name ?? '')}</div>
            <div className="muted">{[f.data.culture, f.data.note].filter(Boolean).join(' · ')}</div>
          </div>
          <div className="row">
            <strong>{areaHa(Number(f.data.areaSqM ?? 0))} га</strong>
            {editable && <button onClick={() => open(f)}>Изменить</button>}
            {editable && <button className="link danger" onClick={() => { if (confirm('Удалить поле? Расчёты, где оно выбрано, останутся.')) engine.remove('field', f.id); }}>Удалить</button>}
          </div>
        </section>
      ))}
      {editing && (
        <div className="modal" onClick={() => setEditing(null)}>
          <form className="card form" onClick={(ev) => ev.stopPropagation()} onSubmit={(ev) => { ev.preventDefault(); save(); }}>
            <h3>{editing.id ? 'Поле' : 'Новое поле'}</h3>
            {field('name', 'Название или номер')}
            {field('area', 'Площадь', 'га')}
            {field('culture', 'Культура (необязательно)')}
            {field('note', 'Заметка (необязательно)')}
            <datalist id="field-cultures">{cultures.map((c) => <option key={c} value={c} />)}</datalist>
            {errors.map((e) => <div key={e} className="error">{e}</div>)}
            <div className="row">
              <button className="big" disabled={errors.length > 0}>Сохранить</button>
              <button type="button" onClick={() => setEditing(null)}>Отмена</button>
            </div>
          </form>
        </div>
      )}
    </>
  );
}
