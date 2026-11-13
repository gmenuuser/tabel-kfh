import { useState, type FormEvent } from 'react';
import type { Session } from '../lib/sync.ts';

type Mode = 'login' | 'reset';

async function post(path: string, body: object) {
  const r = await fetch(path, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) });
  const json = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(json.error ?? `Ошибка ${r.status}`);
  return json;
}

/** The site is for the owner only: sign in with the owner's e-mail and password. */
export function LoginPage({ onSignedIn }: { onSignedIn: (s: Session) => void }) {
  const [mode, setMode] = useState<Mode>('login');
  const [f, setF] = useState({ email: '', password: '', resetCode: '' });
  const [error, setError] = useState('');
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const set = (k: keyof typeof f) => (e: { target: { value: string } }) => setF({ ...f, [k]: e.target.value });

  async function submit(e: FormEvent) {
    e.preventDefault();
    setError('');
    setBusy(true);
    try {
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
      const r = await post('/auth/login', { deviceName: `Браузер ${navigator.platform || ''}`.trim(), platform: 'web',
        login: f.email.trim(), password: f.password });
      onSignedIn({ token: r.token, farmId: r.farmId, userId: r.userId, deviceId: r.deviceId, role: r.role, email: f.email });
    } catch (err) {
      setError(err instanceof TypeError ? 'Нет связи с сервером' : (err as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="login">
      <form className="card login-card" onSubmit={submit}>
        <h1>КФХ — Учёт</h1>
        <h2>{mode === 'reset' ? 'Восстановить доступ' : 'Вход'}</h2>
        <label>Почта<input type={mode === 'reset' ? 'email' : 'text'} autoComplete="username" autoCapitalize="none"
                           value={f.email} onChange={set('email')} required /></label>
        {mode === 'reset' && <label>Код из журнала сервера (если уже получен)<input value={f.resetCode} onChange={set('resetCode')} /></label>}
        {(mode !== 'reset' || f.resetCode) && (
          <label>{mode === 'reset' ? 'Новый пароль' : 'Пароль'}
            <input type="password" autoComplete={mode === 'login' ? 'current-password' : 'new-password'} minLength={mode === 'reset' ? 8 : undefined}
                   value={f.password} onChange={set('password')} required />
          </label>
        )}
        {error && <div className="error">{error}</div>}
        {note && <div className="note">{note}</div>}
        <button className="big" disabled={busy}>{mode === 'reset' ? (f.resetCode ? 'Сменить пароль' : 'Получить код') : 'Войти'}</button>
        {mode === 'login'
          ? <button type="button" className="link" onClick={() => { setMode('reset'); setError(''); }}>Забыл пароль?</button>
          : <button type="button" className="link" onClick={() => { setMode('login'); setError(''); }}>Назад ко входу</button>}
      </form>
    </div>
  );
}
