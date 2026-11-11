#!/usr/bin/env bash
# Pulls the review samples of the app from the connected device (emulator) into a folder:
#   review-samples/   the report PDFs, XLSXs and page PNGs of ReportSamplesTest and the walkthrough screenshots
#                     (screens/NN-name.png) — the app's external files dir
#   shots/            the `shots=true` pictures of the flow tests of Tasks 9–15 — the app's internal files dir
# Usage: bash scripts/pull-review-samples.sh [out-dir]   (default build/review-samples)
# The apks must still be installed: run the tests with -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true.
set -u
out=${1:-build/review-samples}
pkg=ru.kfh.tabel
mkdir -p "$out"

ext=/sdcard/Android/data/$pkg/files/review-samples
if ! adb pull "$ext" "$out/" 2>/dev/null; then
  # Android 11+ keeps /sdcard/Android/data from the shell user; the app itself (run-as) reads its own folder.
  echo "adb pull of $ext refused, copying file by file through run-as"
  adb shell "run-as $pkg find /storage/emulated/0/Android/data/$pkg/files/review-samples -type f" | tr -d '\r' | while read -r f; do
    [ -n "$f" ] || continue
    rel=${f#*/files/}
    mkdir -p "$out/$(dirname "$rel")"
    adb exec-out "run-as $pkg cat '$f'" > "$out/$rel"
  done
fi

# The internal files/shots of the flow tests (written only with the instrumentation argument shots=true).
adb shell "run-as $pkg ls files/shots" 2>/dev/null | tr -d '\r' | while read -r f; do
  [ -n "$f" ] || continue
  mkdir -p "$out/shots"
  adb exec-out "run-as $pkg cat files/shots/$f" > "$out/shots/$f"
done

echo "review samples in $out:"
find "$out" -type f | sort
