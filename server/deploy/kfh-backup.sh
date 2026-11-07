#!/usr/bin/env bash
# Nightly: pg_dump of the KFH database -> /var/backups/kfh (last 14 kept) -> restic (Cubely's repository, tag kfh).
# Runs on the VDS as root (installed as /usr/local/sbin/kfh-backup, fired by kfh-backup.timer).
# The restic repository variables are only READ from the environment file Cubely's own backup uses:
# its path is in /etc/kfh/restic-env-path (written by deploy.sh --first-time) or in $RESTIC_ENV_FILE.
set -euo pipefail

DIR=/var/backups/kfh
COMPOSE_DIR=/opt/kfh/server
mkdir -p "$DIR"; chmod 700 "$DIR"
OUT="$DIR/kfh-$(date +%Y-%m-%d_%H%M).dump"

cd "$COMPOSE_DIR"
docker compose -f docker-compose.yml -f deploy/compose.prod.yml exec -T db pg_dump -U kfh -d kfh -Fc > "$OUT.part"
mv "$OUT.part" "$OUT"
echo "dump: $OUT ($(du -h "$OUT" | cut -f1))"
ls -1t "$DIR"/kfh-*.dump | tail -n +15 | xargs -r rm -f

ENVFILE="${RESTIC_ENV_FILE:-$(cat /etc/kfh/restic-env-path 2>/dev/null || true)}"
if [ -z "$ENVFILE" ] || [ ! -r "$ENVFILE" ]; then
  echo "restic environment file not found (set RESTIC_ENV_FILE or /etc/kfh/restic-env-path); dump kept locally only" >&2
  exit 1
fi
set -a
# shellcheck disable=SC1090
. "$ENVFILE"
set +a
restic backup --tag kfh "$DIR"
restic snapshots --tag kfh --latest 1
