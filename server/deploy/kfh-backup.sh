#!/usr/bin/env bash
# Nightly: pg_dump + uploaded files of KFH -> /var/backups/kfh (last 14 of each kept) -> restic (Cubely's repository, tag kfh).
# Runs on the VDS as root (installed as /usr/local/sbin/kfh-backup, fired by kfh-backup.timer).
# Restic settings come from /etc/kfh/restic.env (created by deploy.sh --first-time).
set -euo pipefail

DIR=/var/backups/kfh
cd /opt/kfh/server
dc() { docker compose -f docker-compose.yml -f deploy/compose.prod.yml "$@"; }

mkdir -p "$DIR"; chmod 700 "$DIR"
rm -f "$DIR"/*.part
STAMP="$(date +%Y-%m-%d_%H%M)"

dc exec -T db pg_dump -U kfh -d kfh -Fc > "$DIR/kfh-$STAMP.dump.part"
mv "$DIR/kfh-$STAMP.dump.part" "$DIR/kfh-$STAMP.dump"
# An upload landing mid-backup makes tar exit 1 (GNU: "file changed as we read it"; busybox, which the api image
# has, also uses 1 for a file that vanished): the file comes with the next night, so that is a warning - but only
# if the archive itself reads back whole (busybox exits 1 on fatal errors too). Anything else fails the backup.
rc=0
dc exec -T api tar -C /data -cf - . > "$DIR/files-$STAMP.tar.part" || rc=$?
if [ "$rc" = 1 ] && tar -tf "$DIR/files-$STAMP.tar.part" >/dev/null; then
  echo "warning: files changed while archiving them (tar exit 1); the archive reads back whole and is kept" >&2
elif [ "$rc" != 0 ]; then
  echo "files archive failed (tar exit $rc)" >&2
  exit "$rc"
fi
mv "$DIR/files-$STAMP.tar.part" "$DIR/files-$STAMP.tar"
echo "dump:  $(du -h "$DIR/kfh-$STAMP.dump" | cut -f1)  files: $(du -h "$DIR/files-$STAMP.tar" | cut -f1)"
ls -1t "$DIR"/kfh-*.dump | tail -n +15 | xargs -r rm -f
ls -1t "$DIR"/files-*.tar | tail -n +15 | xargs -r rm -f

[ -r /etc/kfh/restic.env ] || { echo "/etc/kfh/restic.env missing; dump kept locally only" >&2; exit 1; }
set -a
# shellcheck disable=SC1091
. /etc/kfh/restic.env
set +a
restic backup --retry-lock 30m --tag kfh "$DIR"
# no --prune here: Cubely's own backup prunes the shared repository
restic forget --retry-lock 30m --tag kfh --keep-daily 14 --keep-weekly 4
restic snapshots --retry-lock 30m --tag kfh --latest 1
