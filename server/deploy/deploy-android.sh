#!/usr/bin/env bash
# Put the Android app on the VDS: https://kfh.cubely.ru/android/tabel-kfh.apk + version.json.
#
#   bash server/deploy/deploy-android.sh                      latest green android.yml run on main
#   bash server/deploy/deploy-android.sh 43012345678          that run (the number in the Actions URL)
#   bash server/deploy/deploy-android.sh android              latest green run on that branch
#   bash server/deploy/deploy-android.sh [target] --local DIR  no VDS: download, verify, write the two files into DIR
#   bash server/deploy/deploy-android.sh [target] root@host   another ssh host (default root@37.230.113.40)
#   bash server/deploy/deploy-android.sh --apk FILE [...]     this signed .apk instead of the CI artifact
#
# Downloads the CI artifact `tabel-kfh-apk` (GitHub API; the token comes from `git credential fill` and is never
# printed), takes app-release.apk out of it (a debug-only artifact — secrets not set in CI — is refused), checks
# with apksigner that it is signed by the kfh release key (fingerprint below), reads versionCode/versionName from
# the apk (aapt) or falls back to the run number, then copies both files to /opt/kfh/android/ on the VDS and
# asks nginx for them. The nginx location comes with deploy.sh (snippets/kfh-android.conf): run deploy.sh first.
# Only after the owner's «выкладывай».
set -euo pipefail

REPO=gmenuuser/tabel-kfh
API=https://api.github.com/repos/$REPO
ARTIFACT=tabel-kfh-apk
SITE=https://kfh.cubely.ru
APK_URL=$SITE/android/tabel-kfh.apk
REMOTE_DIR=/opt/kfh/android
# SHA-256 of the kfh release certificate (keytool -list -v / apksigner verify --print-certs). Public, not a secret.
KFH_CERT_SHA256=f3ab556ccd7ef9013708a71e3a50aea21aa91acfe857f88826411f7cdcc16949

# --- helpers (test-deploy-scripts.sh sources this file and checks them) ------------------------------------------

# kfh_android_version_json <code> <name>: the version.json text the app's «О приложении» reads.
kfh_android_version_json() {
  printf '{"versionCode": %s, "versionName": "%s", "url": "%s"}' "$1" "$2" "$APK_URL"
}

# kfh_android_pick_apk <dir>: the app-release.apk inside the unpacked artifact; exit 1 with a reason when there is none.
kfh_android_pick_apk() {
  local rel
  rel="$(find "$1" -type f -name app-release.apk | head -1)"
  if [ -n "$rel" ]; then echo "$rel"; return 0; fi
  if [ -n "$(find "$1" -type f -name app-debug.apk | head -1)" ]; then
    echo "the artifact holds only app-debug.apk: CI built without the signing secrets (KFH_* in the repository settings)." >&2
  else
    echo "no .apk in the artifact" >&2
  fi
  return 1
}

# kfh_android_parse_target [arg]: "run <id>" for a number, "branch <name>" otherwise (default main).
kfh_android_parse_target() {
  local a="${1:-main}"
  case "$a" in
    ''|*[!0-9]*) echo "branch $a" ;;
    *) echo "run $a" ;;
  esac
}

# kfh_first_existing <path>...: the first path that exists (nothing when none does; never a failure).
kfh_first_existing() {
  local f
  for f in "$@"; do if [ -e "$f" ]; then echo "$f"; return 0; fi; done
  return 0
}

# stdin: `aapt dump badging` -> "<versionCode> <versionName>" (empty when not found).
kfh_android_badging_version() {
  sed -nE "s/^package: .*versionCode='([0-9]+)' versionName='([^']*)'.*/\1 \2/p" | head -1
}

# Sourced by the tests: the functions above are all they need.
if [ "${BASH_SOURCE[0]}" != "$0" ]; then return 0; fi

# --- the deploy -------------------------------------------------------------------------------------------------

HOST="root@37.230.113.40"
LOCAL_DIR=""
APK_FILE=""
TARGET=""
while [ $# -gt 0 ]; do
  case "$1" in
    --local) LOCAL_DIR="${2:?--local needs a directory}"; shift 2 ;;
    --apk) APK_FILE="${2:?--apk needs a file}"; shift 2 ;;
    *@*) HOST="$1"; shift ;;
    *) TARGET="$1"; shift ;;
  esac
done
WIN_SSH="/c/Windows/System32/OpenSSH/ssh.exe"
if [ -x "$WIN_SSH" ]; then ssh() { "$WIN_SSH" "$@"; }; fi

WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
RUN_NUMBER=""
if [ -n "$APK_FILE" ]; then
  [ -f "$APK_FILE" ] || { echo "no such file: $APK_FILE" >&2; exit 1; }
  APK="$APK_FILE"
  echo "==> local file $APK_FILE, $(wc -c < "$APK") bytes (no CI run)"
else

T=$(printf 'protocol=https\nhost=github.com\n\n' | git credential fill 2>/dev/null | sed -n 's/^password=//p')
[ -n "$T" ] || { echo "no github credential (git credential fill)" >&2; exit 2; }
gh_api() { curl -sfL -H "Authorization: Bearer $T" -H "Accept: application/vnd.github+json" "$@"; }
json() { node -e "let s='';process.stdin.on('data',d=>s+=d).on('end',()=>{const j=JSON.parse(s);console.log(($1)(j))})"; }

# 1. the run
read -r KIND WHAT <<<"$(kfh_android_parse_target "$TARGET")"
if [ "$KIND" = run ]; then
  RUN_JSON="$(gh_api "$API/actions/runs/$WHAT")"
else
  RUN_JSON="$(gh_api "$API/actions/workflows/android.yml/runs?branch=$WHAT&status=success&per_page=1" | json 'j=>JSON.stringify(j.workflow_runs[0]||null)')"
  [ "$RUN_JSON" != null ] || { echo "no green android.yml run on branch $WHAT" >&2; exit 1; }
fi
RUN_ID="$(echo "$RUN_JSON" | json 'j=>j.id')"
RUN_NUMBER="$(echo "$RUN_JSON" | json 'j=>j.run_number')"
RUN_SHA="$(echo "$RUN_JSON" | json 'j=>j.head_sha.slice(0,7)')"
RUN_STATE="$(echo "$RUN_JSON" | json 'j=>j.status+" "+(j.conclusion||"")')"
RUN_BRANCH="$(echo "$RUN_JSON" | json 'j=>j.head_branch')"
echo "==> run $RUN_ID (#$RUN_NUMBER, $RUN_BRANCH @ $RUN_SHA, $RUN_STATE): https://github.com/$REPO/actions/runs/$RUN_ID"
[ "$RUN_STATE" = "completed success" ] || { echo "that run is not green" >&2; exit 1; }

# 2. the artifact
ART_ID="$(gh_api "$API/actions/runs/$RUN_ID/artifacts" | json "j=>(j.artifacts.find(a=>a.name==='$ARTIFACT')||{}).id||''")"
[ -n "$ART_ID" ] || { echo "run $RUN_ID has no artifact $ARTIFACT (expired? artifacts live 90 days)" >&2; exit 1; }
gh_api -o "$WORK/art.zip" "$API/actions/artifacts/$ART_ID/zip"
mkdir -p "$WORK/art"
if command -v unzip >/dev/null; then unzip -q "$WORK/art.zip" -d "$WORK/art"
elif command -v python >/dev/null; then python -I -c "import sys,zipfile; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])" "$WORK/art.zip" "$WORK/art"
else powershell -NoProfile -Command "Expand-Archive -Path '$(cygpath -w "$WORK/art.zip")' -DestinationPath '$(cygpath -w "$WORK/art")'"
fi
APK="$(kfh_android_pick_apk "$WORK/art")"
echo "==> $(basename "$APK"), $(wc -c < "$APK") bytes"

fi

# 3. the signature: only the kfh release key goes to the phones (a debug key would refuse to install over it)
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/AppData/Local/Android/Sdk}}"
BT="$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1)"
APKSIGNER="${KFH_APKSIGNER:-$(kfh_first_existing "$BT"apksigner.bat "$BT"apksigner)}"
[ -n "$APKSIGNER" ] || { echo "apksigner not found under $SDK/build-tools (set ANDROID_HOME or KFH_APKSIGNER)" >&2; exit 1; }
CERTS="$("$APKSIGNER" verify --print-certs "$(cygpath -w "$APK" 2>/dev/null || echo "$APK")")"
GOT="$(echo "$CERTS" | sed -nE 's/^Signer #1 certificate SHA-256 digest: *([0-9a-f]+).*/\1/p' | tr -d '\r' | head -1)"
if [ "$GOT" != "$KFH_CERT_SHA256" ]; then
  echo "the apk is NOT signed by the kfh release key:" >&2
  echo "  got  ${GOT:-nothing}" >&2
  echo "  want $KFH_CERT_SHA256" >&2
  exit 1
fi
echo "==> signed by the kfh release key ($(echo "$CERTS" | sed -n 's/^Signer #1 certificate DN: //p' | tr -d '\r'))"

# 4. the version: from the apk when aapt is there, else the run number (build.gradle.kts: versionCode = run number)
AAPT="$(kfh_first_existing "$BT"aapt.exe "$BT"aapt)"
VER=""
[ -z "$AAPT" ] || VER="$("$AAPT" dump badging "$(cygpath -w "$APK" 2>/dev/null || echo "$APK")" 2>/dev/null | kfh_android_badging_version || true)"
if [ -n "$VER" ]; then read -r CODE NAME <<<"$VER"
elif [ -n "$RUN_NUMBER" ]; then CODE="$RUN_NUMBER"; NAME="1.0.$RUN_NUMBER"
else echo "cannot read the version of $APK: aapt not found under $BT (needed with --apk)" >&2; exit 1
fi
VERSION_JSON="$(kfh_android_version_json "$CODE" "$NAME")"
echo "==> version $NAME ($CODE)"

# 5. local: done
if [ -n "$LOCAL_DIR" ]; then
  mkdir -p "$LOCAL_DIR"
  cp "$APK" "$LOCAL_DIR/tabel-kfh.apk"
  printf '%s\n' "$VERSION_JSON" > "$LOCAL_DIR/version.json"
  echo "==> written: $LOCAL_DIR/tabel-kfh.apk, $LOCAL_DIR/version.json (nothing sent to the VDS)"
  exit 0
fi

# 6. the VDS: the new files appear at once (write .new, then mv); nginx serves them from $REMOTE_DIR
echo "==> uploading to $HOST:$REMOTE_DIR"
ssh -o BatchMode=yes "$HOST" "mkdir -p $REMOTE_DIR && cat > $REMOTE_DIR/tabel-kfh.apk.new && chmod 644 $REMOTE_DIR/tabel-kfh.apk.new && mv $REMOTE_DIR/tabel-kfh.apk.new $REMOTE_DIR/tabel-kfh.apk" < "$APK"
printf '%s\n' "$VERSION_JSON" | ssh -o BatchMode=yes "$HOST" "cat > $REMOTE_DIR/version.json.new && chmod 644 $REMOTE_DIR/version.json.new && mv $REMOTE_DIR/version.json.new $REMOTE_DIR/version.json"

echo "==> checking $APK_URL"
HEAD="$(curl -sSI "$APK_URL" | tr -d '\r')"
echo "$HEAD" | grep -iE '^(HTTP/|content-type:|content-disposition:|content-length:)' | sed 's/^/  /'
echo "$HEAD" | grep -qiE '^content-type: application/vnd\.android\.package-archive' || {
  echo "nginx does not serve the apk with the Android content type yet: run deploy.sh (installs snippets/kfh-android.conf), then look at $APK_URL" >&2
  exit 1
}
echo "  $(curl -sS "$SITE/android/version.json")"
echo "==> done: $SITE/android/ offers $NAME ($CODE)"
