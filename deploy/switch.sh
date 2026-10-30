#!/bin/sh
# Points an environment's protected domain at one of its deployments: the rollback
# (release-checklist §6), timed until /health answers 200 through the bypass header. On production
# the public domain the client apps call moves too (Q-29). Run it yourself:
# the auto-mode safety check treats a production domain change as a deploy.
#
#   deploy/switch.sh production hgpiz00l3     # the ID in restaurant-production-<ID>-liam-36c7.vercel.app
set -eu

case "${1:-}" in
  staging|production) env=$1 ;;
  *) echo "usage: $0 staging|production <deployment ID>" >&2; exit 2 ;;
esac
[ -n "${2:-}" ] || { echo "usage: $0 staging|production <deployment ID>" >&2; exit 2; }
domain="restaurant-$env-liam-36c7.vercel.app"
target="restaurant-$env-$2-liam-36c7.vercel.app"
bypass=$(sed -n 's/^VERCEL_AUTOMATION_BYPASS_SECRET=//p' "deploy/$env/.env.$env")

start=$(date +%s)
echo "start $(date -u +%FT%TZ)"
vercel alias set "$target" "$domain"
[ "$env" != production ] || vercel alias set "$target" restaurant-production.vercel.app
until [ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 20 \
  -H "x-vercel-protection-bypass: $bypass" "https://$domain/health")" = 200 ]; do sleep 2; done
echo "$domain -> $target, /health 200 after $(( $(date +%s) - start )) s"
