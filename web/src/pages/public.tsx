import { LINKS } from './app-links.tsx';

/**
 * The site without a session: what the app is and where to download it. No farm data, no sections — the owner's
 * sign-in lives at `#/admin` behind a discreet link.
 */
export function PublicPage() {
  return (
    <div className="public">
      <div className="card public-card">
        <h1>КФХ — Учёт</h1>
        <p className="muted">Табель, уголь, скот, калькуляторы и отчёты — в приложении на телефоне.</p>
        <section className="app-links">
          <h2>Скачать приложение</h2>
          {LINKS.map((l) => (
            <div key={l.href} className="app-links-row">
              <a className="button app-link" href={l.href} download={l.download}>{l.label}</a>
            </div>
          ))}
          <div className="muted app-links-hint">
            Android: нужен Android 8.0 или новее, на странице есть инструкция по установке.
            iPhone: файл ставится с компьютера через Sideloadly, подпись продлевать раз в 7 дней.
          </div>
        </section>
        <p className="muted public-note">
          Код доступа к приложению выдаёт владелец хозяйства.
        </p>
        <a className="link" href="#/admin">Вход для администратора</a>
      </div>
    </div>
  );
}
