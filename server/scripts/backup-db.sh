#!/usr/bin/env bash
# The whole database (every farm) as a compressed pg_dump in server/backups/, named by date and time.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p backups
file="backups/kfh-$(date +%Y-%m-%d_%H-%M).dump"
docker compose exec -T db pg_dump -U kfh -d kfh -Fc > "$file"
echo "saved $file ($(wc -c < "$file") bytes)"
# Restore: docker compose exec -T db pg_restore -U kfh -d kfh --clean < backups/<file>.dump
