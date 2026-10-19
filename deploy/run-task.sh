#!/bin/sh
# Runs a staging task (deploy/README.md "Staging data", "System tests") against an environment through
# its protected domain, with both of its env files exported. A short command on purpose: a long
# pasted line wraps and splits (CLAUDE.md).
#
#   deploy/run-task.sh production seed      # seedStaging
#   deploy/run-task.sh production system    # systemTest
set -eu

case "${1:-}" in
  staging|production) env=$1 ;;
  *) echo "usage: $0 staging|production seed|system" >&2; exit 2 ;;
esac
url="https://restaurant-$env-liam-36c7.vercel.app"

for f in "deploy/$env/$env.env" "deploy/$env/.env.$env"; do
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in ''|'#'*) ;; *) export "$line" ;; esac
  done < "$f"
done

case "${2:-}" in
  seed) exec ./gradlew --no-daemon seedStaging --args="$url" ;;
  system) exec ./gradlew --no-daemon systemTest -PstagingUrl="$url" ;;
  *) echo "usage: $0 staging|production seed|system" >&2; exit 2 ;;
esac
