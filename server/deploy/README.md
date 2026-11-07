# Выкладка сервера на VDS (kfh.cubely.ru)

Сервер работает на том же VDS, что и Cubely (37.230.113.40), в `/opt/kfh`: API на `127.0.0.1:8090`, база наружу не открыта,
снаружи — nginx с сертификатом. Файлы и конфиги Cubely не трогаются.

**Первая выкладка делается только после слова владельца «выкладывай».**

## Первая выкладка

1. На Рег.ру: A-запись `kfh` → `37.230.113.40`. Проверка: `nslookup kfh.cubely.ru 8.8.8.8`.
2. Всё закоммичено (выкладывается только то, что в `HEAD`), затем:
   `bash server/deploy/deploy.sh root@37.230.113.40 --first-time`
   Скрипт создаст `/opt/kfh/server/.env` со случайным паролем базы, поднимет контейнеры, подключит сайт в nginx
   (ссылкой в `sites-enabled`), получит сертификат и включит ночной бэкап.
3. Проверка: `curl -fsS https://kfh.cubely.ru/health` → `{"ok":true}`; `https://cubely.ru` и `https://panel.cubely.ru` отвечают как раньше.

## Обновление

`bash server/deploy/deploy.sh` (хост по умолчанию `root@37.230.113.40`). Данные и `.env` сохраняются.

## Регистрация хозяйств

По умолчанию закрыта. Чтобы владелец зарегистрировал хозяйство:

    bash server/deploy/set-registration.sh open
    (регистрация на https://kfh.cubely.ru)
    bash server/deploy/set-registration.sh close

## Бэкап

Каждую ночь в 03:30 (МСК) `kfh-backup.timer` делает `pg_dump` в `/var/backups/kfh` (хранятся 14 последних) и отправляет
папку в restic-репозиторий Cubely (тег `kfh`). Проверка:

    ssh root@37.230.113.40 '/usr/local/sbin/kfh-backup'                       # сделать бэкап сейчас
    ssh root@37.230.113.40 'bash /opt/kfh/server/deploy/check-backup.sh'      # восстановить во временную базу и сравнить число записей
    ssh root@37.230.113.40 'systemctl list-timers kfh-backup.timer'
