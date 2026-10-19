#!/bin/sh
# Deploys the working tree to an environment's Vercel project and removes its public domain in the
# same run (deploy/README.md). Run it yourself from the repository root: the auto-mode safety check
# blocks Claude from production deploys. With --env it first sends the environment's settings and
# secrets (deploy/push-env.sh).
#
#   deploy/release.sh production --env
#
# One short command on purpose: on 2026-10-19 a long pasted line wrapped, the deploy ran and the alias
# removal didn't, and production's public domain answered 200 until Claude removed it.
set -eu

case "${1:-}" in
  staging) domain=restaurant-staging-ecru.vercel.app ;;
  production) domain=restaurant-production.vercel.app ;;
  *) echo "usage: $0 staging|production [--env]" >&2; exit 2 ;;
esac
env=$1
project="restaurant-$env"

if [ "${2:-}" = "--env" ]; then
  deploy/push-env.sh "deploy/$env/$env.env" "deploy/$env/.env.$env"
fi

# The public domain goes even when the deploy reports a failure, in case it got there.
failed=0
out=$(vercel deploy --prod --project "$project" --json) || failed=1
vercel alias rm "$domain" --yes || true
[ "$failed" = 0 ] || { echo "the deploy failed" >&2; exit 1; }
echo "deployed $(printf '%s' "$out" | jq -r '.deployment.url')"

status=$(curl -s -o /dev/null -w '%{http_code}' --max-time 20 "https://$domain/health")
echo "$domain answers $status without the bypass header"
[ "$status" != 200 ] || { echo "the public domain still answers 200: remove it now" >&2; exit 1; }
