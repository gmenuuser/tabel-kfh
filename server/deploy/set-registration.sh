#!/usr/bin/env bash
# Open or close registration of new farms: bash server/deploy/set-registration.sh open|close [ssh-host]
# Sets REGISTRATION_OPEN in /opt/kfh/server/.env and recreates the api container.
set -euo pipefail
MODE="${1:-}"
HOST="${2:-root@37.230.113.40}"
case "$MODE" in open) V=true ;; close) V=false ;; *) echo "usage: $0 open|close [ssh-host]" >&2; exit 2 ;; esac
WIN_SSH="/c/Windows/System32/OpenSSH/ssh.exe"
if [ -x "$WIN_SSH" ]; then ssh() { "$WIN_SSH" "$@"; }; fi

ssh -o BatchMode=yes "$HOST" "V=$V bash -s" <<'REMOTE'
set -euo pipefail
cd /opt/kfh/server
if grep -q '^REGISTRATION_OPEN=' .env; then
  sed -i "s/^REGISTRATION_OPEN=.*/REGISTRATION_OPEN=$V/" .env
else
  echo "REGISTRATION_OPEN=$V" >> .env
fi
docker compose -f docker-compose.yml -f deploy/compose.prod.yml up -d --force-recreate --no-deps api
for i in $(seq 1 30); do curl -fsS http://127.0.0.1:8090/health >/dev/null 2>&1 && break; sleep 2; done
echo "REGISTRATION_OPEN=$V, API:"; curl -fsS http://127.0.0.1:8090/health; echo
REMOTE
