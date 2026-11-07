#!/usr/bin/env bash
# Local check of restic-repo.sh and the tar step of kfh-backup.sh with fake files (no ssh, nothing real touched):
#   bash server/deploy/test-deploy-scripts.sh
set -euo pipefail
. "$(dirname "$0")/restic-repo.sh"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
fail=0
eq() { if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FAIL $1: got '$2', want '$3'"; fail=1; fi; }

# Cubely's env file holds the address; a commented line above it is not taken.
cat > "$T/cubely-restic.env" <<'EOF'
# RESTIC_REPOSITORY=s3:https://old.example/old-bucket
export RESTIC_REPOSITORY="s3:https://s3.example.ru/cubely-backup/vds"
RESTIC_PASSWORD_FILE=/root/.cubely-restic-pass
EOF
cat > "$T/script" <<'EOF'
#!/bin/sh
. /root/.cubely-restic.env
restic backup --tag system /etc
EOF
eq "env file first, comments skipped" "$(kfh_find_restic_repo "$T/cubely-restic.env" "$T/script")" "s3:https://s3.example.ru/cubely-backup/vds"
eq "missing env file: falls back to the script" "$(kfh_find_restic_repo "$T/nope" "$T/script")" ""

cat > "$T/script2" <<'EOF'
#   restic -r s3:https://commented.example/x backup
RESTIC_REPOSITORY='s3:https://s3.example.ru/b2/path'; export RESTIC_REPOSITORY
EOF
eq "script assignment, single quotes" "$(kfh_find_restic_repo "$T/nope" "$T/script2")" "s3:https://s3.example.ru/b2/path"

cat > "$T/script3" <<'EOF'
# RESTIC_REPOSITORY=s3:https://commented.example/x
restic --repo s3:https://s3.example.ru/b3 backup /etc
EOF
eq "script --repo flag" "$(kfh_find_restic_repo "$T/nope" "$T/script3")" "s3:https://s3.example.ru/b3"

printf 'RESTIC_REPOSITORY="$REPO"\n' > "$T/script4"
eq "variable reference is not an address" "$(kfh_find_restic_repo "$T/script4")" ""
printf '# only a comment\n# RESTIC_REPOSITORY=s3:https://x/y\n' > "$T/script5"
eq "only commented lines: nothing" "$(kfh_find_restic_repo "$T/script5")" ""

touch "$T/pass"
printf 'RESTIC_REPOSITORY=s3:https://a/b\nRESTIC_PASSWORD_FILE=%s\n. %s\n' "$T/pass" "$T/s3.env" > "$T/kfh.env"
eq "missing secret files listed" "$(kfh_restic_env_missing_files "$T/kfh.env")" "$T/s3.env"
touch "$T/s3.env"
eq "all secret files present" "$(kfh_restic_env_missing_files "$T/kfh.env")" ""
eq "mask" "$(echo 's3:https://s3.example.ru/bucket/secret/path' | kfh_mask_repo)" "s3:https://s3.example.ru/bucket/..."

# Guarded, quoted and commented source lines.
cat > "$T/kfh2.env" <<EOF
RESTIC_PASSWORD_FILE="$T/pass" # Cubely's password
. '$T/s3.env'   # keys
[ -r $T/extra.env ] && . $T/extra.env
test -f "$T/extra2.env" && . "$T/extra2.env"
# . $T/commented.env
EOF
eq "quotes and trailing comments: nothing missing" "$(kfh_restic_env_missing_files "$T/kfh2.env")" ""
eq "guarded missing files are optional" "$(kfh_restic_env_optional_missing_files "$T/kfh2.env")" "$T/extra.env
$T/extra2.env"
rm "$T/s3.env"
eq "quoted required file missing" "$(kfh_restic_env_missing_files "$T/kfh2.env")" "$T/s3.env"
touch "$T/s3.env"

eq "mask rest credentials" "$(echo 'rest:https://kfh:p4ss@backup.example.ru:8000/repo' | kfh_mask_repo)" "rest:https://***@backup.example.ru:8000/..."
eq "mask https credentials" "$(echo 'https://u:p@h.example/x/y' | kfh_mask_repo)" "https://***@h.example/..."
eq "mask sftp user" "$(echo 'sftp:backup@host.example:/srv/restic' | kfh_mask_repo)" "sftp:***@host.example:/..."
eq "mask local path untouched" "$(echo '/srv/restic-repo' | kfh_mask_repo)" "/srv/restic-repo"

# The tar step of kfh-backup.sh, cut out of it, with a fake `dc` exiting with a given code.
BLOCK="$(sed -n '/^rc=0$/,/^mv "\$DIR\/files-/p' "$(dirname "$0")/kfh-backup.sh")"
mkdir -p "$T/data"; head -c 5120 /dev/urandom > "$T/data/a"; head -c 5120 /dev/urandom > "$T/data/b"
tar_step() { # exit-code whole|truncated|boundary -> prints "<script exit> <archive kept 0/1>"
  local RC="$1" MODE="$2" DIR="$T/out" STAMP=x r=0
  rm -rf "$DIR"; mkdir -p "$DIR"
  dc() {
    case "$MODE" in
      whole) tar -C "$T/data" -cf - ./a ./b ;;
      truncated) tar -C "$T/data" -cf - ./a ./b | head -c 2000 ;;
      boundary) tar -C "$T/data" -cf - ./a ./b | head -c 5632 ;; # exactly the first member: it lists fine
    esac
    return "$RC"
  }
  ( eval "$BLOCK" ) 2>/dev/null || r=$?
  echo "$r $([ -f "$DIR/files-x.tar" ] && echo 1 || echo 0)"
}
eq "tar exit 0: archive kept" "$(tar_step 0 whole)" "0 1"
eq "tar exit 1, archive whole: warning, archive kept" "$(tar_step 1 whole)" "0 1"
eq "tar exit 1, archive broken: backup fails" "$(tar_step 1 truncated)" "1 0"
eq "tar exit 1, cut at a member boundary (no end marker): backup fails" "$(tar_step 1 boundary)" "1 0"
eq "tar exit 2: backup fails" "$(tar_step 2 whole)" "2 0"

exit "$fail"

