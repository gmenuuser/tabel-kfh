#!/usr/bin/env bash
# Deploy the committed HEAD to the VDS (default root@37.230.113.40) into /opt/kfh.
#
#   bash server/deploy/deploy.sh [ssh-host] --preflight     read-only look at the VDS, changes nothing
#   bash server/deploy/deploy.sh [ssh-host] --first-time    first deploy: nginx site, certificate, restic env, backup timer
#   bash server/deploy/deploy.sh [ssh-host]                 update
#
# Ships `git archive HEAD` over ssh (no rsync on this PC; no node_modules/.git/build in the archive),
# keeps /opt/kfh/server/.env (created once with a random POSTGRES_PASSWORD, mode 600),
# runs docker compose (project "kfh") with deploy/compose.prod.yml and waits for /health on 127.0.0.1:8090.
# `up --build` builds on the VDS: deploy at a quiet hour.
set -euo pipefail

HOST="root@37.230.113.40"
FIRST=0
PRE=0
for a in "$@"; do
  case "$a" in
    --first-time) FIRST=1 ;;
    --preflight) PRE=1 ;;
    *) HOST="$a" ;;
  esac
done
HERE="$(cd "$(dirname "$0")" && pwd)"
WIN_SSH="/c/Windows/System32/OpenSSH/ssh.exe"
if [ -x "$WIN_SSH" ]; then ssh() { "$WIN_SSH" "$@"; }; fi

if [ "$PRE" = 1 ]; then
  echo "==> Preflight on $HOST (read-only)"
  # the read-only helpers first, then the look itself
  { cat "$HERE/restic-repo.sh"; cat <<'REMOTE'
echo "--- restic"
restic version || echo "restic NOT installed"
echo "--- Cubely backup: forget / group-by / prune / retry-lock lines"
grep -nE 'forget|group-by|prune|retry-lock|RESTIC_REPOSITORY|(^|[[:space:]])(-r|--repo)[ =]' /usr/local/sbin/cubely-system-backup
echo "--- restic repository found (in /root/.cubely-restic.env, then in Cubely's script)"
REPO="$(kfh_find_restic_repo)"
if [ -n "$REPO" ]; then echo "$REPO" | kfh_mask_repo; else echo "NOT FOUND - --first-time will stop"; fi
echo "--- secret files the KFH backup relies on (existence only)"
for s in /root/.cubely-restic.env /root/.cubely-restic-pass /root/.cubely-s3.env; do
  if [ -r "$s" ]; then echo "present: $s"; else echo "MISSING: $s"; fi
done
echo "--- Cubely monitor: unit and restic lines"
systemctl cat cubely-monitor.service --no-pager
for s in $(systemctl cat cubely-monitor.service --no-pager | sed -nE 's/^ExecStart=(-|@|\+)?([^ ]+).*/\2/p'); do
  echo "--- restic lines in $s"; grep -n restic "$s" || echo "(none)"
done
echo "--- nginx listen lines"
grep -Rn listen /etc/nginx/sites-enabled   # -R: the entries there are symlinks
echo "--- docker compose projects"
docker compose ls
echo "--- memory"
free -m
echo "--- port 8090 in use?"
(ss -ltn | grep -E ':8090\b') || echo "free"
REMOTE
  } | ssh -o BatchMode=yes "$HOST" bash -s
  exit 0
fi

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
dc() { docker compose -f docker-compose.yml -f deploy/compose.prod.yml "$@"; }

if [ ! -f .env ]; then
  (umask 077; printf 'POSTGRES_PASSWORD=%s\nREGISTRATION_OPEN=false\n' "$(openssl rand -hex 24)" > .env)
  echo "==> created .env"
fi
chmod 600 .env
chmod +x deploy/*.sh

# Never leave a broken nginx config behind: drop our site and stop.
SITE=/etc/nginx/sites-available/kfh.cubely.ru
LINK=/etc/nginx/sites-enabled/kfh.cubely.ru
nginx_reload_or_rollback() {
  if nginx -t; then
    systemctl reload nginx
  else
    echo "nginx -t FAILED - removing the kfh site link and stopping" >&2
    rm -f "$LINK"
    nginx -t || echo "nginx -t still fails WITHOUT our site - look at it by hand" >&2
    exit 1
  fi
}

if [ "$FIRST" = 1 ]; then
  echo "==> restic environment /etc/kfh/restic.env"
  mkdir -p /etc/kfh /var/backups/kfh
  # shellcheck disable=SC1091
  . deploy/restic-repo.sh
  if [ ! -e /etc/kfh/restic.env ]; then
    # Cubely's files are only READ, to learn the repository address: /root/.cubely-restic.env, then its script.
    REPO="$(kfh_find_restic_repo)"
    (umask 077
     {
       [ -n "$REPO" ] && echo "RESTIC_REPOSITORY=$REPO"
       echo "RESTIC_PASSWORD_FILE=/root/.cubely-restic-pass"
       echo ". /root/.cubely-s3.env"
     } > /etc/kfh/restic.env)
  fi
  chmod 600 /etc/kfh/restic.env
  if ! grep -q '^RESTIC_REPOSITORY=' /etc/kfh/restic.env; then
    echo "Could not find the restic repository in /root/.cubely-restic.env or Cubely's script. Put the line" >&2
    echo "  RESTIC_REPOSITORY=<address>" >&2
    echo "into /etc/kfh/restic.env (top of the file) and run --first-time again." >&2
    exit 1
  fi
  MISSING="$(kfh_restic_env_missing_files /etc/kfh/restic.env)"
  if [ -n "$MISSING" ]; then
    echo "/etc/kfh/restic.env relies on files that are not there:" >&2
    echo "$MISSING" | sed 's/^/  /' >&2
    exit 1
  fi
  echo "repository: $(grep '^RESTIC_REPOSITORY=' /etc/kfh/restic.env | kfh_mask_repo)"

  echo "==> nginx site"
  # certbot edits this file later; do not overwrite it if it is already there
  [ -e "$SITE" ] || cp deploy/nginx-kfh.conf "$SITE"
  # a symlink, never a copy (a copied symlink once blanked a live Cubely config)
  [ -L "$LINK" ] || ln -s "$SITE" "$LINK"
  nginx_reload_or_rollback
fi

echo "==> docker compose up"
dc up -d --build

echo "==> waiting for /health"
for i in $(seq 1 60); do
  if curl -fsS http://127.0.0.1:8090/health; then echo; break; fi
  if [ "$i" = 60 ]; then echo "API did not become healthy" >&2; dc logs --tail 50 api >&2; exit 1; fi
  sleep 2
done

if [ "$FIRST" = 1 ]; then
  echo "==> certificate (existing ACME account of this machine)"
  if ! certbot --nginx -d kfh.cubely.ru --redirect -n; then
    echo "certbot failed" >&2
    nginx_reload_or_rollback
    exit 1
  fi
  nginx_reload_or_rollback

  echo "==> backup timer"
  install -m 755 deploy/kfh-backup.sh /usr/local/sbin/kfh-backup
  install -m 644 deploy/kfh-backup.service /etc/systemd/system/kfh-backup.service
  install -m 644 deploy/kfh-backup.timer /etc/systemd/system/kfh-backup.timer
  systemctl daemon-reload
  systemctl enable --now kfh-backup.timer
  systemctl list-timers kfh-backup.timer --no-pager

  echo "==> first real backup (a broken chain must fail here, loudly)"
  /usr/local/sbin/kfh-backup
  set -a; . /etc/kfh/restic.env; set +a
  restic snapshots --retry-lock 30m --tag kfh
fi
# only images of this compose project
docker image prune -f --filter "label=com.docker.compose.project=kfh" >/dev/null
echo "==> done"
REMOTE
