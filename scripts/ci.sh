#!/usr/bin/env bash
# CI helper for gmenuuser/tabel-kfh (Windows dev box, no Mac).
#   bash scripts/ci.sh wait [sha]     wait for the workflow run of sha (default HEAD), print result + failed log tail
#   bash scripts/ci.sh log [run_id]   print the failing steps' log of a run (default latest)
#   bash scripts/ci.sh artifacts [run_id] [dir]   download all artifacts of a run into dir (default build/ci)
# The token comes from the stored git credential for github.com and is never printed.
set -euo pipefail
REPO=gmenuuser/tabel-kfh
API=https://api.github.com/repos/$REPO
T=$(printf 'protocol=https\nhost=github.com\n\n' | git credential fill 2>/dev/null | sed -n 's/^password=//p')
[ -n "$T" ] || { echo "no github credential" >&2; exit 2; }
gh_api() { curl -sfL -H "Authorization: Bearer $T" -H "Accept: application/vnd.github+json" "$@"; }
json() { node -e "let s='';process.stdin.on('data',d=>s+=d).on('end',()=>{const j=JSON.parse(s);console.log(($1)(j))})"; }

latest_run() { gh_api "$API/actions/runs?per_page=1${1:+&head_sha=$1}" | json 'j=>j.workflow_runs[0]?j.workflow_runs[0].id:""'; }

print_failed_log() {
  local run=$1
  gh_api "$API/actions/runs/$run/jobs" | json 'j=>j.jobs.filter(x=>x.conclusion==="failure").map(x=>x.id).join(" ")' | tr ' ' '\n' | while read -r job; do
    [ -n "$job" ] || continue
    echo "=== failed job $job (last 150 lines, errors first) ==="
    log=$(gh_api "$API/actions/jobs/$job/logs" || true)
    echo "$log" | grep -E "error:|\*\* (BUILD|TEST) FAILED|XCTAssert|failed \(|Test Case .* failed" | head -60 || true
    echo "--- tail ---"
    echo "$log" | tail -150
  done
}

cmd=${1:-wait}
case $cmd in
  wait)
    sha=$(git rev-parse "${2:-HEAD}")
    echo "waiting for run of $sha"
    run=""
    for _ in $(seq 1 60); do run=$(latest_run "$sha"); [ -n "$run" ] && break; sleep 10; done
    [ -n "$run" ] || { echo "no run appeared for $sha" >&2; exit 3; }
    echo "run $run: https://github.com/$REPO/actions/runs/$run"
    for _ in $(seq 1 240); do
      st=$(gh_api "$API/actions/runs/$run" | json 'j=>j.status+" "+(j.conclusion||"")')
      case $st in completed*) break;; esac
      sleep 15
    done
    echo "result: $st"
    if [ "$st" != "completed success" ]; then print_failed_log "$run"; exit 1; fi
    ;;
  log) print_failed_log "${2:-$(latest_run)}" ;;
  artifacts)
    run=${2:-$(latest_run)}; dir=${3:-build/ci}; mkdir -p "$dir"
    gh_api "$API/actions/runs/$run/artifacts" | json 'j=>j.artifacts.map(a=>a.id+" "+a.name).join("\n")' | while read -r id name; do
      [ -n "$id" ] || continue
      gh_api -o "$dir/$name.zip" "$API/actions/artifacts/$id/zip" && echo "$dir/$name.zip"
    done
    ;;
  *) echo "usage: ci.sh wait|log|artifacts" >&2; exit 2 ;;
esac
