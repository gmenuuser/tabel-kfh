import { StrictMode, useCallback, useEffect, useState } from 'react';
import { createRoot } from 'react-dom/client';
import './styles.css';
import { AppProvider, SyncBadge, loadSession, saveSession, useApp } from './app.tsx';
import type { Session } from './lib/sync.ts';
import { LoginPage } from './pages/login.tsx';
import { HomePage } from './pages/home.tsx';
import { TimesheetPage, MonthPage } from './pages/timesheet.tsx';
import { EmployeesPage } from './pages/employees.tsx';
import { CoalPage, DriversPage, PricesPage } from './pages/coal.tsx';
import { LivestockPage } from './pages/livestock.tsx';
import { AgroPage } from './pages/agro.tsx';
import { ReportsPage } from './pages/reports.tsx';
import { SettingsPage } from './pages/settings.tsx';
import { canEdit, canOpen } from './lib/roles.ts';

const NAV: [string, string][] = [
  ['home', 'Главная'], ['timesheet', 'Табель'], ['coal', 'Уголь'], ['livestock', 'Скот'], ['agro', 'Калькуляторы'],
  ['reports', 'Отчёты'], ['settings', 'Админка'],
];

function useRoute(): [string, (r: string) => void] {
  const read = () => location.hash.replace(/^#\/?/, '') || 'home';
  const [route, setRoute] = useState(read);
  useEffect(() => {
    const on = () => setRoute(read());
    window.addEventListener('hashchange', on);
    return () => window.removeEventListener('hashchange', on);
  }, []);
  return [route, (r) => { location.hash = `/${r}`; }];
}

function Shell() {
  const { farm, session } = useApp();
  const [rawRoute, go] = useRoute();
  // A page the role may not see is the home page.
  const route = canOpen(session.role, rawRoute.split('/')[0]) ? rawRoute : 'home';
  const page = route.split('/')[0];
  const readOnly = !canEdit(session.role) && ['employees', 'coal/drivers', 'coal/prices', 'livestock'].includes(route);
  const body = (() => {
    switch (route) {
      case 'timesheet': return <TimesheetPage go={go} />;
      case 'timesheet/month': return <MonthPage />;
      case 'employees': return <EmployeesPage />;
      case 'coal': return <CoalPage go={go} />;
      case 'coal/drivers': return <DriversPage />;
      case 'coal/prices': return <PricesPage />;
      case 'livestock': return <LivestockPage />;
      case 'agro': return <AgroPage />;
      case 'reports': return <ReportsPage />;
      case 'settings': return <SettingsPage />;
      default: return <HomePage go={go} />;
    }
  })();
  return (
    <div className="app">
      <header className="top no-print">
        <div className="brand">{farm.name}</div>
        <nav>
          {NAV.filter(([id]) => canOpen(session.role, id)).map(([id, title]) => (
            <a key={id} href={`#/${id}`} className={page === id ? 'active' : ''}>{title}</a>
          ))}
        </nav>
      </header>
      <div className="no-print"><SyncBadge /></div>
      <main className="page">{readOnly ? <fieldset disabled style={{ border: 0, padding: 0, margin: 0, minWidth: 0 }}>{body}</fieldset> : body}</main>
    </div>
  );
}

function Root() {
  const [session, setSession] = useState<Session | null>(loadSession);
  const signOut = useCallback(() => { saveSession(null); setSession(null); }, []);
  if (!session) return <LoginPage onSignedIn={(s) => { saveSession(s); setSession(s); }} />;
  return <AppProvider key={session.deviceId} session={session} onSignedOut={signOut}><Shell /></AppProvider>;
}

createRoot(document.getElementById('root')!).render(<StrictMode><Root /></StrictMode>);

// The app opens without internet once it has been opened with it (secure origins only: https or localhost).
if ('serviceWorker' in navigator && window.isSecureContext) {
  navigator.serviceWorker.register('/sw.js').catch(() => {});
}
