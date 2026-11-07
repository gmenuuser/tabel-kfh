#!/usr/bin/env bash
# Restore the newest dump into a temporary database and count the rows of `records`. Runs on the VDS as root:
#   ssh root@37.230.113.40 'bash /opt/kfh/server/deploy/check-backup.sh'
# Also lists the latest restic snapshots tagged kfh (if the restic environment file is known).
set -euo pipefail
cd /opt/kfh/server
DC="docker compose -f docker-compose.yml -f deploy/compose.prod.yml"
DUMP="$(ls -1t /var/backups/kfh/kfh-*.dump | head -n 1)"
echo "dump: $DUMP"
TMPDB="kfh_check_$$"
cleanup() { $DC exec -T db dropdb -U kfh --if-exists "$TMPDB" >/dev/null 2>&1 || true; }
trap cleanup EXIT
$DC exec -T db createdb -U kfh "$TMPDB"
$DC exec -T db pg_restore -U kfh -d "$TMPDB" --no-owner < "$DUMP"
echo -n "records in the dump:  "; $DC exec -T db psql -U kfh -d "$TMPDB" -Atc 'select count(*) from records'
echo -n "records in live base: "; $DC exec -T db psql -U kfh -d kfh -Atc 'select count(*) from records'

ENVFILE="${RESTIC_ENV_FILE:-$(cat /etc/kfh/restic-env-path 2>/dev/null || true)}"
if [ -n "$ENVFILE" ] && [ -r "$ENVFILE" ]; then
  set -a; . "$ENVFILE"; set +a
  restic snapshots --tag kfh --latest 3
else
  echo "restic environment file unknown - snapshots not listed" >&2
fi
