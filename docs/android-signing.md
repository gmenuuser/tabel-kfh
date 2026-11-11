# Android: ключ подписи, сборка и раздача

Одно приложение — один ключ release на все версии. Новая версия ставится поверх старой только если
подписана тем же ключом; иначе Android требует удалить приложение (вместе с записями на телефоне).
Ключ создан один раз (2026-11-11) и **в репозитории его нет**.

## Где ключ

| Что | Где |
|---|---|
| Keystore `kfh-release.jks` (PKCS12, alias `kfh`, RSA 4096, до 2056 г.) | `C:\Users\Hesya\Desktop\kfh-android-key\` — папка владельца, вне репозитория |
| Пароли (у PKCS12 пароль хранилища и ключа один) | `README.txt` в той же папке |
| Копия для CI | 4 секрета GitHub (ниже) |
| Локальная сборка | `android/keystore.properties` (в `.gitignore`) |

Папку `kfh-android-key` нужно сохранить ещё где-то (флешка, менеджер паролей). Если пропадут и папка, и
секреты GitHub — ключ не восстановить (см. «Если ключ потерян»).

Отпечаток сертификата (публичный, не секрет; его проверяют CI и `deploy-android.sh`):

```
CN=Tabel KFH, O=KFH, C=RU
SHA-256: F3:AB:55:6C:CD:7E:F9:01:37:08:A7:1E:3A:50:AE:A2:1A:A9:1A:CF:E8:57:F8:88:26:41:1F:7C:DC:C1:69:49
```

## Секреты GitHub (добавляет владелец, один раз)

Репозиторий `gmenuuser/tabel-kfh` → **Settings → Secrets and variables → Actions → New repository secret**.
Четыре секрета, имена точно такие:

| Имя | Значение |
|---|---|
| `KFH_KEYSTORE_B64` | содержимое файла `kfh-release.jks.b64.txt` — одна длинная строка |
| `KFH_KEYSTORE_PASSWORD` | `storePassword` из `README.txt` |
| `KFH_KEY_ALIAS` | `kfh` |
| `KFH_KEY_PASSWORD` | `keyPassword` из `README.txt` (тот же, что `storePassword`) |

Как получить base64 keystore: в папке `kfh-android-key` запустить `print-keystore-base64.ps1`
(правой кнопкой → «Выполнить с помощью PowerShell») или `bash print-keystore-base64.sh`. Появится
`kfh-release.jks.b64.txt`: открыть, Ctrl+A, Ctrl+C, вставить в секрет. После этого файл можно удалить.

Что делает CI (`.github/workflows/android.yml`, job `build`):

- все четыре секрета есть → `assembleRelease`, затем `apksigner verify --print-certs` и сравнение отпечатка с
  указанным выше; артефакт `tabel-kfh-apk` содержит `app-release.apk`;
- секретов нет → `assembleDebug` (артефакт с `app-debug.apk`), workflow зелёный, в логе предупреждение;
- часть секретов есть → сборка останавливается с сообщением, каких не хватает.

Проверить после добавления: Actions → android → Run workflow (ветка `main`) → шаг «Signing secrets» пишет
«all four signing secrets are set», шаг «Verify the release signature» печатает сертификат `CN=Tabel KFH`.

## Локальная сборка подписанного .apk

`android/keystore.properties` (файл в `.gitignore`, создаётся руками):

```
storeFile=C:/Users/Hesya/Desktop/kfh-android-key/kfh-release.jks
storePassword=<из README.txt>
keyPassword=<из README.txt>
keyAlias=kfh
```

Затем в `android/`: `GITHUB_RUN_NUMBER=<номер> ./gradlew :app:assembleRelease` → `app/build/outputs/apk/release/app-release.apk`
(`versionCode` = номер, `versionName` = `1.0.<номер>`; без переменной — 1). Те же четыре значения можно дать
переменными окружения `KFH_KEYSTORE_FILE`, `KFH_KEYSTORE_PASSWORD`, `KFH_KEY_ALIAS`, `KFH_KEY_PASSWORD`
(так делает CI) — они главнее файла.

Проверка подписи: `apksigner verify --print-certs app-release.apk` (`build-tools/36.0.0/apksigner.bat`) показывает
`CN=Tabel KFH` и отпечаток выше.

## Раздача: страница и файлы

- `https://kfh.cubely.ru/android/` — страница `web/public/android/index.html` (кнопка «Скачать приложение»,
  требование Android 8.0, как разрешить установку из браузера на Honor/Samsung/Xiaomi/чистом Android).
- `https://kfh.cubely.ru/android/tabel-kfh.apk` — подписанный .apk, `Content-Type: application/vnd.android.package-archive`,
  `Content-Disposition: attachment; filename="TabelKFH.apk"`.
- `https://kfh.cubely.ru/android/version.json` — `{"versionCode": 42, "versionName": "1.0.42", "url": "…/tabel-kfh.apk"}`;
  приложение читает его в «Настройки → О приложении» и показывает «Скачать новую версию», когда `versionCode` больше установленного.

На VDS: nginx отдаёт всё это из `/opt/kfh/web/public/android/` (страница) и `/opt/kfh/android/` (.apk и version.json);
location'ы — в `server/deploy/nginx-kfh-android.conf`, который `deploy.sh` ставит как `/etc/nginx/snippets/kfh-android.conf`
и подключает в сайт `kfh.cubely.ru` (при каждом деплое; .apk и version.json переживают обновление `/opt/kfh`).

Выкладка новой версии (только после «выкладывай»):

```
bash server/deploy/deploy.sh                 # один раз после этого изменения: ставит snippet nginx
bash server/deploy/deploy-android.sh         # .apk последнего зелёного android.yml на main → VDS
bash server/deploy/deploy-android.sh 43012345678   # или конкретный run, или ветка: ... android
curl -I https://kfh.cubely.ru/android/tabel-kfh.apk    # 200, Content-Type application/vnd.android.package-archive
```

`deploy-android.sh` скачивает артефакт через GitHub API (токен — `git credential fill`, не печатается),
отказывается от артефакта без `app-release.apk` (CI без секретов), проверяет отпечаток подписи,
читает версию из .apk (`aapt`) и пишет оба файла на VDS. `--local DIR` делает всё то же без VDS.

До выкладки .apk передаётся файлом: `C:\Users\Hesya\Desktop\TabelKFH.apk` (Telegram / кабель).

## Если ключ потерян

Восстановить нельзя. Создать новый (JDK 17):

```
keytool -genkeypair -v -keystore kfh-release.jks -storetype PKCS12 -alias kfh -keyalg RSA -keysize 4096 \
  -validity 10950 -dname "CN=Tabel KFH, O=KFH, C=RU"
keytool -list -v -keystore kfh-release.jks | grep SHA256
```

Затем: новый `README.txt` с паролем, 4 секрета GitHub заново, новый отпечаток в `.github/workflows/android.yml`
(`want=…`), `server/deploy/deploy-android.sh` (`KFH_CERT_SHA256=…`) и в этом файле. На телефонах приложение
придётся удалить и поставить заново — записи подтянутся с сервера после входа (неотправленные пропадут:
перед удалением сделать синхронизацию).
