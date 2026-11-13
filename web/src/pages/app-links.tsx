import { useState } from 'react';

const LINKS = [
  { href: '/android/', label: 'Android — скачать', download: undefined },
  { href: '/ios/TabelKFH.ipa', label: 'iPhone — скачать .ipa', download: 'TabelKFH.ipa' },
];

/** «Приложения»: where to get the Android and iPhone apps (files served by nginx next to the site), with copy buttons. */
export function AppLinks() {
  const [shown, setShown] = useState('');
  const [copied, setCopied] = useState('');
  async function copy(href: string) {
    const url = new URL(href, window.location.origin).href;
    try { await navigator.clipboard.writeText(url); setCopied(href); setShown(''); }
    catch { setShown(url); setCopied(''); }
  }
  return (
    <section className="card app-links">
      <h3>Приложения</h3>
      {LINKS.map((l) => (
        <div key={l.href} className="app-links-row">
          <a className="button app-link" href={l.href} download={l.download}>{l.label}</a>
          <button type="button" onClick={() => copy(l.href)}>{copied === l.href ? 'Скопировано' : 'Скопировать ссылку'}</button>
        </div>
      ))}
      {shown && <input readOnly value={shown} onFocus={(e) => e.target.select()} ref={(el) => el?.select()} style={{ width: '100%' }} />}
      <div className="muted app-links-hint">
        Android: нужен Android 8.0 или новее, на странице есть инструкция по установке.
        iPhone: файл ставится с компьютера через Sideloadly, подпись продлевать раз в 7 дней.
      </div>
    </section>
  );
}
