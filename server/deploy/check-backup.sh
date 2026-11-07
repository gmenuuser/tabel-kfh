#!/usr/bin/env bash
# Checks the backups, on the VDS as root:
#   ssh root@37.230.113.40 'bash /opt/kfh/server/deploy/check-backup.sh'
# 1. restores the newest local dump into a temporary database and counts `records`;
# 2. checks the newest files archive lists;
# 3. restores the newest dump and files archive of the newest restic snapshot tagged kfh into a temporary folder
#    and does the same with them.
set -euo pipefail
cd /opt/kfh/server
dc() { docker compose -f docker-compose.yml -f deploy/compose.prod.yml "$@"; }
TMPDIR_R="$(mktemp -d)"
DBS=()
cleanup() {
  for d in "${DBS[@]:-}"; do [ -n "$d" ] && dc exec -T db dropdb -U kfh --if-exists "$d" >/dev/null 2>&1 || true; done
  rm -rf "$TMPDIR_R"
}
trap cleanup EXIT

check() { # label dump tar
  local label="$1" dump="$2" tarf="$3" db="kfh_check_$$_$RANDOM"
  echo "== $label: $dump"
  DBS+=("$db")
  dc exec -T db createdb -U kfh "$db"
  dc exec -T db pg_restore -U kfh -d "$db" --no-owner < "$dump"
  echo -n "records in the dump: "; dc exec -T db psql -U kfh -d "$db" -Atc 'select count(*) from records'
  tar -tf "$tarf" >/dev/null && echo "files archive ok: $tarf ($(tar -tf "$tarf" | wc -l) entries)"
}

LOCAL_DUMP="$(ls -1t /var/backups/kfh/kfh-*.dump | head -n 1)"
LOCAL_TAR="$(ls -1t /var/backups/kfh/files-*.tar | head -n 1)"
check "local" "$LOCAL_DUMP" "$LOCAL_TAR"
echo -n "records in live base: "; dc exec -T db psql -U kfh -d kfh -Atc 'select count(*) from records'

[ -r /etc/kfh/restic.env ] || { echo "/etc/kfh/restic.env missing - restic not checked" >&2; exit 1; }
set -a; . /etc/kfh/restic.env; set +a
restic snapshots --retry-lock 30m --tag kfh --latest 3
# Only the newest dump and files archive of the snapshot (it holds 14 of each): the names sort by their stamp.
LISTING="$(restic ls latest --retry-lock 30m --tag kfh)"
S_DUMP="$(printf '%s\n' "$LISTING" | grep -E '^/var/backups/kfh/kfh-[^/]*\.dump$' | sort | tail -n 1 || true)"
S_TAR="$(printf '%s\n' "$LISTING" | grep -E '^/var/backups/kfh/files-[^/]*\.tar$' | sort | tail -n 1 || true)"
[ -n "$S_DUMP" ] && [ -n "$S_TAR" ] || { echo "no dump or files archive in the latest kfh snapshot" >&2; exit 1; }
restic restore latest --retry-lock 30m --tag kfh --target "$TMPDIR_R" --include "$S_DUMP" --include "$S_TAR"
R_DUMP="$TMPDIR_R$S_DUMP"
R_TAR="$TMPDIR_R$S_TAR"
check "restic" "$R_DUMP" "$R_TAR"
echo "OK"
