/** «Приложения»: where to get the Android and iPhone apps (files served by nginx next to the site). */
export function AppLinks() {
  return (
    <section className="card app-links">
      <h2>Приложения</h2>
      <div className="app-links-row">
        <a className="button app-link" href="/android/">Android — скачать</a>
        <a className="button app-link" href="/ios/TabelKFH.ipa" download="TabelKFH.ipa">iPhone — скачать .ipa</a>
      </div>
      <div className="muted app-links-hint">
        Android: нужен Android 8.0 или новее, на странице есть инструкция по установке.
        iPhone: файл ставится с компьютера через Sideloadly, подпись продлевать раз в 7 дней.
      </div>
    </section>
  );
}
