import { useState } from 'react';
import { useApp, useRecords } from '../app.tsx';
import { canEdit } from '../lib/roles.ts';
import { KINDS, choices, complete, compute, fields, type Inputs } from '../lib/agro.ts';
import { dayKey, shortDate, timeText } from '../lib/time.ts';

/** «Агрономические калькуляторы»: the result right under the fields; saved calculations are shared. */
export function AgroPage() {
  const { engine, session } = useApp();
  const editable = canEdit(session.role);
  const saved = useRecords('agroCalculation');
  const [kind, setKind] = useState<string | null>(null);
  const [inputs, setInputs] = useState<Inputs>({});
  const [title, setTitle] = useState('');
  const [editingId, setEditingId] = useState<string | null>(null);
  const [note, setNote] = useState('');
  const [search, setSearch] = useState('');

  const open = (k: string, id: string | null = null, i: Inputs = {}, t = '') => {
    setKind(k); setEditingId(id); setTitle(t); setNote('');
    setInputs(id ? i : JSON.parse(localStorage.getItem(`kfh.agro.${k}`) ?? '{}'));
  };
  const change = (k: string, v: string) => {
    const next = { ...inputs, [k]: v };
    setInputs(next);
    if (!editingId && kind) localStorage.setItem(`kfh.agro.${kind}`, JSON.stringify(next)); // draft
  };

  if (!kind) {
    const q = search.trim().toLowerCase();
    return (
      <>
        <h2>Агрономические калькуляторы</h2>
        <div className="tiles small">{KINDS.map(([k, t]) => <button key={k} className="tile green" onClick={() => open(k)}><span className="tile-title">{t}</span></button>)}</div>
        <h3>История расчётов</h3>
        <input className="search" placeholder="Поиск по полю или комментарию" value={search} onChange={(e) => setSearch(e.target.value)} />
        {[...saved].sort((a, b) => String(b.data.createdAt).localeCompare(String(a.data.createdAt)))
          .filter((c) => !q || `${c.data.title} ${KINDS.find(([k]) => k === c.data.kind)?.[1]}`.toLowerCase().includes(q))
          .map((c) => {
            const lines = (c.data.lines ?? []) as { title: string; text: string; main?: boolean }[];
            const main = lines.find((l) => l.main);
            return (
              <div key={c.id} className="row between line">
                <button className="link" onClick={() => open(String(c.data.kind), c.id, (c.data.inputs ?? {}) as Inputs, String(c.data.title ?? ''))}>
                  {KINDS.find(([k]) => k === c.data.kind)?.[1]}{c.data.title ? ` — ${c.data.title}` : ''}
                </button>
                <span>{main ? `${main.title}: ${main.text}` : ''}</span>
                <span className="muted">{shortDate(dayKey(String(c.data.createdAt)))} {timeText(String(c.data.createdAt))}</span>
                <button className="link danger" disabled={!editable} onClick={() => { if (confirm('Удалить расчёт?')) engine.remove('agroCalculation', c.id); }}>Удалить</button>
              </div>
            );
          })}
      </>
    );
  }

  const r = compute(kind, inputs);
  const plots = Math.max(1, Number(inputs.plots ?? 1) || 1);
  async function save(asNew = false) {
    if (!complete(r)) return;
    const clean = Object.fromEntries(Object.entries(inputs).filter(([, v]) => String(v).trim()));
    await engine.save('agroCalculation', asNew ? null : editingId, { kind, title: title.trim(), inputs: clean, lines: r.lines,
      ...(editingId && !asNew ? {} : { createdAt: new Date().toISOString() }), updatedAt: new Date().toISOString() });
    if (!editingId) localStorage.removeItem(`kfh.agro.${kind}`);
    setNote(editingId && !asNew ? 'Изменения сохранены' : 'Расчёт сохранён в истории');
  }
  const text = [KINDS.find(([k]) => k === kind)?.[1] + (title ? ` — ${title}` : ''), ...r.lines.map((l) => `${l.title}: ${l.text}`), ...r.warnings].join('\n');

  return (
    <>
      <div className="row between"><button onClick={() => setKind(null)}>‹ Все калькуляторы</button><h2>{KINDS.find(([k]) => k === kind)?.[1]}</h2></div>
      <section className="card form">
        {choices(kind).map((c) => (
          <div key={c.key}><div className="caption">{c.title}</div>
            <div className="segmented">{c.options.map(([v, t]) => <button key={v} className={(inputs[c.key] ?? c.options[0][0]) === v ? 'active' : ''} onClick={() => change(c.key, v)}>{t}</button>)}</div>
          </div>
        ))}
        {kind === 'area' && Array.from({ length: plots }, (_, i) => i + 1).map((i) => (
          <div key={i} className="row">
            <strong>Участок {i}</strong>
            <input placeholder="Длина, м" inputMode="decimal" value={inputs[`plot.${i}.length`] ?? ''} onChange={(e) => change(`plot.${i}.length`, e.target.value)} />
            ×<input placeholder="Ширина, м" inputMode="decimal" value={inputs[`plot.${i}.width`] ?? ''} onChange={(e) => change(`plot.${i}.width`, e.target.value)} />
            <input placeholder="или площадь, м²" inputMode="decimal" value={inputs[`plot.${i}.area`] ?? ''} onChange={(e) => change(`plot.${i}.area`, e.target.value)} />
          </div>
        ))}
        {kind === 'area' && <button onClick={() => change('plots', String(plots + 1))}>+ Добавить участок</button>}
        {fields(kind, inputs).map((f) => (
          <label key={f.key}>{f.title}{f.optional ? ' (необязательно)' : ''}
            <span className="unit-input"><input inputMode="decimal" value={inputs[f.key] ?? ''} onChange={(e) => change(f.key, e.target.value)} /><span>{f.unit}</span></span>
          </label>
        ))}
        <label>Поле или комментарий<input value={title} onChange={(e) => setTitle(e.target.value)} /></label>
      </section>
      <section className="card result">
        <h3>Результат</h3>
        {r.errors.map((e) => <div key={e} className="error">{e}</div>)}
        {!r.errors.length && r.missing.length > 0 && <p className="muted">Заполните: {r.missing.join(', ')}</p>}
        {r.lines.map((l, i) => <div key={i} className={l.main ? 'line main' : 'line'}><span>{l.title}</span><strong>{l.text}</strong></div>)}
        {r.warnings.map((w) => <p key={w} className="note">{w}</p>)}
      </section>
      <div className="row">
        <button className="big" disabled={!editable || !complete(r)} onClick={() => save()}>{editingId ? 'Сохранить изменения' : 'Сохранить'}</button>
        {editingId && <button disabled={!editable || !complete(r)} onClick={() => save(true)}>Сохранить как новый</button>}
        <button className="big secondary" onClick={() => { setInputs({}); setTitle(''); localStorage.removeItem(`kfh.agro.${kind}`); }}>Очистить</button>
        {complete(r) && <button onClick={() => { navigator.clipboard?.writeText(text); setNote('Результат скопирован'); }}>Копировать результат</button>}
        {complete(r) && 'share' in navigator && <button onClick={() => navigator.share({ text }).catch(() => {})}>Поделиться</button>}
      </div>
      {note && <p className="note">{note}</p>}
    </>
  );
}
