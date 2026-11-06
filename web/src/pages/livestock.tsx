import { useEffect, useState } from 'react';
import { useApp, useRecords, useStatus } from '../app.tsx';
import type { LocalRecord } from '../lib/db.ts';
import { ADDITIONS, DECREASES, OPERATION_KINDS, SPECIES, herdBalances, herdShortage, heads, parseTons, tons } from '../lib/domain.ts';
import { stableId } from '../lib/ids.ts';
import { dayKey, shortDate, todayKey, dayStartISO } from '../lib/time.ts';

const REASONS: [string, string][] = [['meat', 'На мясо'], ['forced', 'Вынужденный забой'], ['other', 'Другое']];

/** The default categories, made once with stable ids (the same rows on every device). */
function useCategories() {
  const { engine } = useApp();
  const status = useStatus();
  const categories = useRecords('livestockCategory');
  useEffect(() => {
    if (categories.length || (!status.lastSyncAt && status.online)) return;
    for (const s of SPECIES) s.categories.forEach((name, i) => engine.save('livestockCategory', stableId(`livestockCategory:${s.id}:${name}`),
      { species: s.id, name, sortOrder: i, isBuiltIn: true, createdAt: new Date().toISOString() }));
  }, [categories.length, status.lastSyncAt, status.online, engine]);
  return [...categories].sort((a, b) => Number(a.data.sortOrder) - Number(b.data.sortOrder));
}

interface Form { kind: string | null; categoryId: string; target: string; heads: string; date: string; comment: string;
                 reason: string; live: string; carcass: string; meat: string; adding: boolean }

export function LivestockPage() {
  const { engine } = useApp();
  const categories = useCategories();
  const ops = useRecords('livestockOperation');
  const slaughters = useRecords('slaughterRecord');
  const [species, setSpecies] = useState('cattle');
  const [form, setForm] = useState<Form | null>(null);
  const [error, setError] = useState('');
  const [filter, setFilter] = useState('');
  const balances = herdBalances(ops.map((o) => o.data));
  const total = (s: string) => categories.filter((c) => c.data.species === s).reduce((n, c) => n + (balances.get(c.id) ?? 0), 0);
  const shown = categories.filter((c) => c.data.species === species);
  const name = new Map(categories.map((c) => [c.id, String(c.data.name)]));
  const speciesOf = new Map(categories.map((c) => [c.id, String(c.data.species)]));

  const open = (categoryId: string, adding: boolean, kind: string | null = adding ? 'purchase' : null) =>
    setForm({ kind, categoryId, target: '', heads: '', date: todayKey(), comment: '', reason: 'meat', live: '', carcass: '', meat: '', adding });

  async function save() {
    if (!form) return;
    const n = Number(form.heads);
    const errors: string[] = [];
    if (!form.kind) errors.push('Выберите причину уменьшения');
    if (!Number.isInteger(n) || n <= 0) errors.push('Количество голов — целое число больше нуля');
    if (form.kind === 'transfer' && !form.target) errors.push('Выберите, в какую категорию перевести');
    const weights = [form.live, form.carcass, form.meat].map((w) => (w.trim() ? parseTons(w) : null));
    if (form.kind === 'slaughter' && [form.live, form.carcass, form.meat].some((w, i) => w.trim() && weights[i] === null)) errors.push('Вес — число килограммов');
    if (errors.length) { setError(errors.join('. ')); return; }
    // Today: the moment of saving (so it comes after this morning's operations); another day: its start.
    const op = { kind: form.kind, date: form.date === todayKey() ? new Date().toISOString() : dayStartISO(form.date),
      categoryId: form.categoryId, targetCategoryId: form.kind === 'transfer' ? form.target : null, heads: n,
      comment: form.comment.trim(), createdAt: new Date().toISOString() };
    if (herdShortage([...ops.map((o) => o.data), op])) { setError('Не хватает голов: списать больше, чем есть, нельзя'); return; }
    if (form.kind === 'slaughter') {
      const label = `${heads(n)} ${SPECIES.find((s) => s.id === speciesOf.get(form.categoryId))?.title} (${name.get(form.categoryId)})`;
      if (!confirm(`Будет списано: ${label}. Продолжить?`)) return;
    }
    const id = await engine.save('livestockOperation', null, op);
    if (form.kind === 'slaughter') {
      await engine.save('slaughterRecord', null, { operationId: id, reason: form.reason, liveWeightGrams: weights[0],
        carcassWeightGrams: weights[1], meatWeightGrams: weights[2], createdAt: new Date().toISOString() });
    }
    setForm(null); setError('');
  }

  async function removeOp(o: LocalRecord) {
    const rest = ops.filter((x) => x.id !== o.id).map((x) => x.data);
    if (herdShortage(rest)) { alert('Удалить нельзя: на эти головы уже записаны другие операции'); return; }
    if (!confirm('Удалить операцию? Поголовье пересчитается.')) return;
    await engine.remove('livestockOperation', o.id);
    const s = slaughters.find((x) => x.data.operationId === o.id);
    if (s) await engine.remove('slaughterRecord', s.id);
  }

  const history = [...ops].filter((o) => !filter || o.data.kind === filter)
    .sort((a, b) => String(b.data.date).localeCompare(String(a.data.date)));

  return (
    <>
      <h2>Скот и забой</h2>
      <section className="card stats">
        {SPECIES.map((s) => <div key={s.id} className={`sp-${s.id}`}><span>{s.title}</span><strong>{total(s.id)}</strong></div>)}
        <div><span>Всего животных</span><strong>{SPECIES.reduce((n, s) => n + total(s.id), 0)}</strong></div>
      </section>
      <div className="segmented">{SPECIES.map((s) => <button key={s.id} className={species === s.id ? 'active' : ''} onClick={() => setSpecies(s.id)}>{s.title}</button>)}</div>
      {shown.map((c) => (
        <section key={c.id} className="card row between">
          <div><div className="name">{String(c.data.name)}</div></div>
          <strong className={`count sp-${species}`}>{balances.get(c.id) ?? 0}</strong>
          <div className="row">
            <button onClick={() => open(c.id, true)}>Добавить</button>
            <button disabled={(balances.get(c.id) ?? 0) === 0} onClick={() => open(c.id, false)}>Уменьшить</button>
            <button className="red" disabled={(balances.get(c.id) ?? 0) === 0} onClick={() => open(c.id, false, 'slaughter')}>Забой</button>
          </div>
        </section>
      ))}
      <form className="card row" onSubmit={(e) => { e.preventDefault(); const v = (e.currentTarget.elements.namedItem('cat') as HTMLInputElement).value.trim();
        if (v) engine.save('livestockCategory', null, { species, name: v, sortOrder: 100 + shown.length, isBuiltIn: false, createdAt: new Date().toISOString() });
        e.currentTarget.reset(); }}>
        <input name="cat" placeholder="Своя категория" /><button>Добавить категорию</button>
      </form>

      <h3>История операций</h3>
      <select value={filter} onChange={(e) => setFilter(e.target.value)}>
        <option value="">Все операции</option>
        {Object.entries(OPERATION_KINDS).map(([k, t]) => <option key={k} value={k}>{t}</option>)}
      </select>
      {history.map((o) => {
        const s = slaughters.find((x) => x.data.operationId === o.id);
        const sign = ADDITIONS.includes(String(o.data.kind)) ? '+' : o.data.kind === 'transfer' ? '→' : '−';
        return (
          <div key={o.id} className="row between line">
            <span>{shortDate(dayKey(String(o.data.date)))} · {OPERATION_KINDS[String(o.data.kind)]} · {SPECIES.find((x) => x.id === speciesOf.get(String(o.data.categoryId)))?.title} {name.get(String(o.data.categoryId))}
              {o.data.targetCategoryId ? ` → ${name.get(String(o.data.targetCategoryId))}` : ''}
              {s?.data.meatWeightGrams ? ` · мясо ${tons(Number(s.data.meatWeightGrams))} кг` : ''}{o.data.comment ? ` · ${o.data.comment}` : ''}</span>
            <span className="row"><strong>{sign}{String(o.data.heads)}</strong><button className="link danger" onClick={() => removeOp(o)}>Удалить</button></span>
          </div>
        );
      })}

      {form && (
        <div className="modal" onClick={() => setForm(null)}>
          <div className="card form" onClick={(e) => e.stopPropagation()}>
            <h3>{name.get(form.categoryId)}: {form.adding ? 'добавить' : form.kind === 'slaughter' ? 'записать забой' : 'уменьшить'}</h3>
            <p className="muted">Сейчас: {heads(balances.get(form.categoryId) ?? 0)}</p>
            {form.kind !== 'slaughter' && (
              <label>{form.adding ? 'Как поступили' : 'Причина'}
                <select value={form.kind ?? ''} onChange={(e) => setForm({ ...form, kind: e.target.value || null })}>
                  {!form.adding && <option value="">Выберите</option>}
                  {(form.adding ? ADDITIONS : DECREASES).map((k) => <option key={k} value={k}>{OPERATION_KINDS[k]}</option>)}
                </select>
              </label>
            )}
            {form.kind === 'transfer' && (
              <label>Перевести в<select value={form.target} onChange={(e) => setForm({ ...form, target: e.target.value })}>
                <option value="">Выберите</option>
                {categories.filter((c) => c.data.species === speciesOf.get(form.categoryId) && c.id !== form.categoryId).map((c) => <option key={c.id} value={c.id}>{String(c.data.name)}</option>)}
              </select></label>
            )}
            <label>Голов<input className="huge" inputMode="numeric" value={form.heads} onChange={(e) => setForm({ ...form, heads: e.target.value })} /></label>
            <label>Дата<input type="date" value={form.date} onChange={(e) => setForm({ ...form, date: e.target.value })} /></label>
            {form.kind === 'slaughter' && (
              <>
                <label>Причина забоя<select value={form.reason} onChange={(e) => setForm({ ...form, reason: e.target.value })}>
                  {REASONS.map(([k, t]) => <option key={k} value={k}>{t}</option>)}</select></label>
                <label>Живой вес, кг (необязательно)<input inputMode="decimal" value={form.live} onChange={(e) => setForm({ ...form, live: e.target.value })} /></label>
                <label>Вес после забоя, кг<input inputMode="decimal" value={form.carcass} onChange={(e) => setForm({ ...form, carcass: e.target.value })} /></label>
                <label>Вес мяса, кг<input inputMode="decimal" value={form.meat} onChange={(e) => setForm({ ...form, meat: e.target.value })} /></label>
              </>
            )}
            <label>Комментарий<input value={form.comment} onChange={(e) => setForm({ ...form, comment: e.target.value })} /></label>
            {error && <div className="error">{error}</div>}
            <button className={form.kind === 'slaughter' ? 'big red' : 'big'} onClick={save}>{form.kind === 'slaughter' ? 'СОХРАНИТЬ ЗАБОЙ' : 'СОХРАНИТЬ'}</button>
          </div>
        </div>
      )}
    </>
  );
}
