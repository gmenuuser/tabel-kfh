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

# Prints "required <file>" / "optional <file>" for each file a restic env file relies on: RESTIC_PASSWORD_FILE=...
# and `. file` / `source file` are required; a guarded `... && . file` is optional. Commented lines, trailing
# ` # comments` and quotes are dropped (paths with spaces are not supported).
kfh__restic_env_files() {
  local line kind file
  while IFS= read -r line || [ -n "$line" ]; do
    line="$(printf '%s' "$line" | sed -E 's/^[[:space:]]+//; s/[[:space:]]+#.*$//; s/[[:space:]]+$//')"
    case "$line" in ''|'#'*) continue ;; esac
    kind=""; file=""
    if printf '%s' "$line" | grep -qE '&&[[:space:]]*(\.|source)[[:space:]]+'; then
      kind=optional
      file="$(printf '%s' "$line" | sed -E 's/^.*&&[[:space:]]*(\.|source)[[:space:]]+//')"
    elif printf '%s' "$line" | grep -qE '^(\.|source)[[:space:]]+'; then
      kind=required
      file="$(printf '%s' "$line" | sed -E 's/^(\.|source)[[:space:]]+//')"
    elif printf '%s' "$line" | grep -qE '^(export[[:space:]]+)?RESTIC_PASSWORD_FILE='; then
      kind=required
      file="$(printf '%s' "$line" | sed -E 's/^(export[[:space:]]+)?RESTIC_PASSWORD_FILE=//')"
    fi
    [ -n "$kind" ] || continue
    file="$(printf '%s' "$file" | sed -E 's/[[:space:]].*$//; s/;$//; s/^["'"'"']//; s/["'"'"']$//')"
    [ -n "$file" ] && printf '%s %s\n' "$kind" "$file"
  done < "$1"
  return 0
}

# Prints, one per line, the required files of a restic env file that do not exist or cannot be read.
kfh_restic_env_missing_files() {
  local kind f
  kfh__restic_env_files "$1" | while read -r kind f; do
    [ "$kind" = required ] && [ ! -r "$f" ] && printf '%s\n' "$f"
  done
  return 0
}

# Prints the optional (guarded) files of a restic env file that are not there: worth a look, not a failure.
kfh_restic_env_optional_missing_files() {
  local kind f
  kfh__restic_env_files "$1" | while read -r kind f; do
    [ "$kind" = optional ] && [ ! -r "$f" ] && printf '%s\n' "$f"
  done
  return 0
}

# The address for logs: credentials (user:pass@) of any scheme hidden, the path cut off (s3 keeps the bucket).
kfh_mask_repo() {
  sed -E 's#(://)[^@/]*@#\1***@#; s#^(sftp:)[^@/:]*@#\1***@#' \
    | sed -E 's#^(s3:https?://[^/]+/[^/]+).*#\1/...#; t
              s#^(([a-z0-9+.-]+:)?[a-z][a-z0-9+.-]*://[^/]+)/.+#\1/...#; t
              s#^(sftp:[^:/]+:)/.+#\1/...#'
}

# Reads `restic snapshots --json --latest 1 ...` on stdin and prints the id of the first snapshot, or nothing.
# Uses jq when it is installed (KFH_NO_JQ=1 forces the sed fallback); "short_id" is never taken for "id".
kfh_snapshot_id() {
  # The first "id" in restic's JSON: works for the flat list and the grouped form (--latest may group by host/paths).
  { grep -oE '"id"[[:space:]]*:[[:space:]]*"[0-9a-f]{64}"' || true; } | head -n 1 | sed -E 's/.*"([0-9a-f]+)"$/\1/'
}
