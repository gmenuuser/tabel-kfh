import { useState, type FormEvent } from 'react';
import type { Session } from '../lib/sync.ts';

type Mode = 'login' | 'register' | 'join' | 'reset';

async function post(path: string, body: object) {
  const r = await fetch(path, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) });
  const json = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(json.error ?? `Ошибка ${r.status}`);
  return json;
}

export function LoginPage({ onSignedIn }: { onSignedIn: (s: Session) => void }) {
  const [mode, setMode] = useState<Mode>('login');
  const [f, setF] = useState({ email: '', password: '', name: '', farmName: 'КФХ Зубахин', code: '', resetCode: '' });
  const [error, setError] = useState('');
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const set = (k: keyof typeof f) => (e: { target: { value: string } }) => setF({ ...f, [k]: e.target.value });

  async function submit(e: FormEvent) {
    e.preventDefault();
    setError('');
    setBusy(true);
    try {
      const device = { deviceName: `Браузер ${navigator.platform || ''}`.trim(), platform: 'web' };
      if (mode === 'reset') {
        if (!f.resetCode) {
          await post('/auth/reset-request', { email: f.email });
          setNote('Если такая почта есть, код восстановления создан. Пока почтового сервера нет, код виден в журнале сервера (docker compose logs api).');
        } else {
          await post('/auth/reset', { email: f.email, code: f.resetCode, password: f.password });
          setNote('Пароль изменён. Войдите с новым паролем.');
          setMode('login');
        }
        return;
      }
      const path = mode === 'login' ? '/auth/login' : mode === 'register' ? '/auth/register' : '/auth/join';
      const r = await post(path, { ...device, email: f.email, password: f.password, name: f.name, farmName: f.farmName, code: f.code });
      onSignedIn({ token: r.token, farmId: r.farmId, userId: r.userId, deviceId: r.deviceId, role: r.role, email: f.email });
    } catch (err) {
      setError(err instanceof TypeError ? 'Нет связи с сервером' : (err as Error).message);
    } finally {
      setBusy(false);
    }
  }

  const titles: Record<Mode, string> = { login: 'Вход', register: 'Новое хозяйство', join: 'Вход по приглашению', reset: 'Восстановить доступ' };
  return (
    <div className="login">
      <form className="card login-card" onSubmit={submit}>
        <h1>КФХ — Учёт</h1>
        <div className="tabs">
          {(['login', 'register', 'join'] as Mode[]).map((m) => (
            <button type="button" key={m} className={mode === m ? 'tab active' : 'tab'} onClick={() => { setMode(m); setError(''); }}>
              {titles[m]}
            </button>
          ))}
        </div>
        {mode === 'join' && <label>Код приглашения<input value={f.code} onChange={set('code')} required /></label>}
        <label>Почта<input type="email" autoComplete="email" value={f.email} onChange={set('email')} required /></label>
        {mode === 'reset' && <label>Код из журнала сервера (если уже получен)<input value={f.resetCode} onChange={set('resetCode')} /></label>}
        {(mode !== 'reset' || f.resetCode) && (
          <label>{mode === 'reset' ? 'Новый пароль' : 'Пароль'}
            <input type="password" autoComplete={mode === 'login' ? 'current-password' : 'new-password'} minLength={8}
                   value={f.password} onChange={set('password')} required />
          </label>
        )}
        {(mode === 'register' || mode === 'join') && <label>Ваше имя<input value={f.name} onChange={set('name')} /></label>}
        {mode === 'register' && <label>Название хозяйства<input value={f.farmName} onChange={set('farmName')} required /></label>}
        {error && <div className="error">{error}</div>}
        {note && <div className="note">{note}</div>}
        <button className="big" disabled={busy}>{mode === 'reset' ? (f.resetCode ? 'Сменить пароль' : 'Получить код') : titles[mode]}</button>
        {mode !== 'reset'
          ? <button type="button" className="link" onClick={() => setMode('reset')}>Забыли пароль?</button>
          : <button type="button" className="link" onClick={() => setMode('login')}>Назад ко входу</button>}
      </form>
    </div>
  );
}
