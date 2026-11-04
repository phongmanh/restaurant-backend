#!/bin/sh
# Runs a staging task (deploy/README.md "Staging data", "System tests") against an environment through
# its protected domain, with both of its env files exported. A short command on purpose: a long
# pasted line wraps and splits (CLAUDE.md).
#
#   deploy/run-task.sh production seed      # seedStaging
#   deploy/run-task.sh production system    # systemTest
#   deploy/run-task.sh production migrate   # Flyway migrations (deploy/README.md "Releasing" step 2)
#   deploy/run-task.sh production invite-admin <email> <display name>   # a platform admin (inviteAdmin)
set -eu

case "${1:-}" in
  staging|production) env=$1 ;;
  *) echo "usage: $0 staging|production seed|system|migrate|invite-admin <email> <display name>" >&2; exit 2 ;;
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
  migrate) exec ./gradlew --no-daemon migrate ;;
  invite-admin)
    [ $# -ge 4 ] || { echo "usage: $0 $env invite-admin <email> <display name>" >&2; exit 2; }
    shift 2
    INVITE_ADMIN_EMAIL=$1; shift
    INVITE_ADMIN_NAME="$*"
    export INVITE_ADMIN_EMAIL INVITE_ADMIN_NAME
    exec ./gradlew --no-daemon -q inviteAdmin --args="$url" ;;
  *) echo "usage: $0 staging|production seed|system|migrate|invite-admin <email> <display name>" >&2; exit 2 ;;
esac
