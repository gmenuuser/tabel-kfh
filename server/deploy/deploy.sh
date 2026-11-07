#!/usr/bin/env bash
# Deploy the committed HEAD to the VDS (default root@37.230.113.40) into /opt/kfh.
#
#   bash server/deploy/deploy.sh [ssh-host] [--first-time]
#
# Ships `git archive HEAD` over ssh (no rsync on this PC; no node_modules/.git/build in the archive),
# keeps /opt/kfh/server/.env (created once with a random POSTGRES_PASSWORD, mode 600),
# runs docker compose with deploy/compose.prod.yml and waits for /health on 127.0.0.1:8090.
# --first-time also installs the nginx site (as a symlink!), the certificate and the nightly backup timer.
set -euo pipefail

HOST="root@37.230.113.40"
FIRST=0
for a in "$@"; do
  case "$a" in
    --first-time) FIRST=1 ;;
    *) HOST="$a" ;;
  esac
done
WIN_SSH="/c/Windows/System32/OpenSSH/ssh.exe"
if [ -x "$WIN_SSH" ]; then ssh() { "$WIN_SSH" "$@"; }; fi
cd "$(git rev-parse --show-toplevel)"

if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
  echo "Uncommitted changes - commit first. Only committed code is deployed." >&2
  exit 1
fi
REV="$(git rev-parse --short HEAD)"
echo "==> Deploying $REV to $HOST (first-time=$FIRST)"

# Code goes into a fresh directory, then replaces the old one; .env and the docker volumes stay.
git archive --format=tar HEAD | ssh -o BatchMode=yes "$HOST" "
  set -e
  rm -rf /opt/kfh.new
  mkdir -p /opt/kfh /opt/kfh.new
  tar -x -C /opt/kfh.new
  if [ -f /opt/kfh/server/.env ]; then cp -p /opt/kfh/server/.env /opt/kfh.new/server/.env; fi
  rm -rf /opt/kfh.old
  mv /opt/kfh /opt/kfh.old
  mv /opt/kfh.new /opt/kfh
  echo $REV > /opt/kfh/REVISION
"

ssh -o BatchMode=yes "$HOST" "FIRST=$FIRST bash -s" <<'REMOTE'
set -euo pipefail
cd /opt/kfh/server
if [ ! -f .env ]; then
  umask 077
  printf 'POSTGRES_PASSWORD=%s\nREGISTRATION_OPEN=false\n' "$(openssl rand -hex 24)" > .env
  echo "==> created .env"
fi
chmod 600 .env
chmod +x deploy/*.sh

if [ "$FIRST" = 1 ]; then
  echo "==> nginx site"
  SITE=/etc/nginx/sites-available/kfh.cubely.ru
  # certbot edits this file later; do not overwrite it if it is already there
  [ -e "$SITE" ] || cp deploy/nginx-kfh.conf "$SITE"
  # a symlink, never a copy (a copied symlink once blanked a live Cubely config)
  [ -L /etc/nginx/sites-enabled/kfh.cubely.ru ] || ln -s "$SITE" /etc/nginx/sites-enabled/kfh.cubely.ru
  nginx -t
  systemctl reload nginx
fi

echo "==> docker compose up"
docker compose -f docker-compose.yml -f deploy/compose.prod.yml up -d --build

echo "==> waiting for /health"
for i in $(seq 1 60); do
  if curl -fsS http://127.0.0.1:8090/health; then echo; break; fi
  if [ "$i" = 60 ]; then echo "API did not become healthy" >&2; docker compose logs --tail 50 api >&2; exit 1; fi
  sleep 2
done

if [ "$FIRST" = 1 ]; then
  echo "==> certificate"
  certbot --nginx -d kfh.cubely.ru --redirect -n
  nginx -t
  systemctl reload nginx

  echo "==> backup timer"
  mkdir -p /var/backups/kfh /etc/kfh
  # Cubely's own script is only READ to find the restic environment file it sources.
  if [ ! -s /etc/kfh/restic-env-path ]; then
    grep -E '^[[:space:]]*(source|\.)[[:space:]]+/' /usr/local/sbin/cubely-system-backup | head -n 1 \
      | sed -E 's/^[[:space:]]*(source|\.)[[:space:]]+//; s/[[:space:]].*$//' | tr -d '"'"'" > /etc/kfh/restic-env-path || true
    echo "restic env file: $(cat /etc/kfh/restic-env-path)"
  fi
  install -m 755 deploy/kfh-backup.sh /usr/local/sbin/kfh-backup
  install -m 644 deploy/kfh-backup.service /etc/systemd/system/kfh-backup.service
  install -m 644 deploy/kfh-backup.timer /etc/systemd/system/kfh-backup.timer
  systemctl daemon-reload
  systemctl enable --now kfh-backup.timer
  systemctl list-timers kfh-backup.timer --no-pager
fi
docker image prune -f >/dev/null
echo "==> done"
REMOTE
