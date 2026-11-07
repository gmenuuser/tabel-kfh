# Read-only helpers for Cubely's restic repository, sourced by deploy.sh on the VDS (and by a local test harness).
# Nothing here writes or prints a secret.

# Prints the restic repository address Cubely uses, or nothing. Looks in the files given, in order
# (default: /root/.cubely-restic.env, where Cubely keeps it, then Cubely's backup script). Commented lines are skipped;
# a value that is a variable reference is not an address.
kfh_find_restic_repo() {
  local f repo
  [ "$#" -gt 0 ] || set -- /root/.cubely-restic.env /usr/local/sbin/cubely-system-backup
  for f in "$@"; do
    [ -r "$f" ] || continue
    repo="$( { grep -vE '^[[:space:]]*#' "$f" || true; } \
      | { grep -m1 -E '(^|[[:space:];])(export[[:space:]]+)?RESTIC_REPOSITORY=' || true; } \
      | sed -E 's/^.*RESTIC_REPOSITORY=//; s/^["'"'"']//; s/["'"'"'; ].*$//')"
    if [ -z "$repo" ]; then
      repo="$( { grep -vE '^[[:space:]]*#' "$f" || true; } \
        | { grep -m1 -E '(^|[[:space:]])(-r|--repo)[ =]' || true; } \
        | sed -E 's/^.*(-r|--repo)[ =]+//; s/^["'"'"']//; s/["'"'"' ;].*$//')"
    fi
    case "$repo" in *'$'*) repo="" ;; esac
    if [ -n "$repo" ]; then printf '%s\n' "$repo"; return 0; fi
  done
  return 0
}

# Prints, one per line, the secret files a restic env file relies on (RESTIC_PASSWORD_FILE=..., `. file`)
# that do not exist or cannot be read.
kfh_restic_env_missing_files() {
  local env="$1" s
  for s in $(sed -nE 's/^RESTIC_PASSWORD_FILE=//p; s/^\.[[:space:]]+//p' "$env"); do
    [ -r "$s" ] || printf '%s\n' "$s"
  done
  return 0
}

# The address with the bucket path cut off, for logs.
kfh_mask_repo() { sed -E 's#(s3:https?://[^/]+/[^/]+).*#\1/...#'; }
