import { useEffect, useState, type FormEvent } from 'react';
import type { Session } from '../lib/sync.ts';

type Mode = 'login' | 'register' | 'reset';

async function post(path: string, body: object) {
  const r = await fetch(path, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) });
  const json = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(json.error ?? `Ошибка ${r.status}`);
  return json;
}

export function LoginPage({ onSignedIn }: { onSignedIn: (s: Session) => void }) {
  const [mode, setMode] = useState<Mode>('login');
  const [f, setF] = useState({ email: '', password: '', name: '', farmName: 'КФХ Зубахин', resetCode: '' });
  const [registrationOpen, setRegistrationOpen] = useState(false);
  const [error, setError] = useState('');
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const set = (k: keyof typeof f) => (e: { target: { value: string } }) => setF({ ...f, [k]: e.target.value });

  useEffect(() => {
    fetch('/auth/config').then((r) => r.json()).then((c) => setRegistrationOpen(c.registrationOpen === true)).catch(() => {});
  }, []);

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
      const r = mode === 'login'
        ? await post('/auth/login', { ...device, login: f.email.trim(), password: f.password })
        : await post('/auth/register', { ...device, email: f.email, password: f.password, name: f.name, farmName: f.farmName });
      onSignedIn({ token: r.token, farmId: r.farmId, userId: r.userId, deviceId: r.deviceId, role: r.role, email: f.email });
    } catch (err) {
      setError(err instanceof TypeError ? 'Нет связи с сервером' : (err as Error).message);
    } finally {
      setBusy(false);
    }
  }

  const titles: Record<Mode, string> = { login: 'Вход', register: 'Новое хозяйство', reset: 'Восстановить доступ' };
  return (
    <div className="login">
      <form className="card login-card" onSubmit={submit}>
        <h1>КФХ — Учёт</h1>
        {registrationOpen && <div className="tabs">
          {(['login', 'register'] as Mode[]).map((m) => (
            <button type="button" key={m} className={mode === m ? 'tab active' : 'tab'} onClick={() => { setMode(m); setError(''); }}>
              {titles[m]}
            </button>
          ))}
        </div>}
        {mode === 'login'
          ? <label>Почта или логин<input autoComplete="username" autoCapitalize="none" value={f.email} onChange={set('email')} required /></label>
          : <label>Почта<input type="email" autoComplete="email" value={f.email} onChange={set('email')} required /></label>}
        {mode === 'reset' && <label>Код из журнала сервера (если уже получен)<input value={f.resetCode} onChange={set('resetCode')} /></label>}
        {(mode !== 'reset' || f.resetCode) && (
          <label>{mode === 'reset' ? 'Новый пароль' : 'Пароль'}
            <input type="password" autoComplete={mode === 'login' ? 'current-password' : 'new-password'} minLength={8}
                   value={f.password} onChange={set('password')} required />
          </label>
        )}
        {mode === 'register' && <label>Ваше имя<input value={f.name} onChange={set('name')} /></label>}
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
