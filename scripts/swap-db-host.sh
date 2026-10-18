#!/usr/bin/env bash
# Points staging's two database URLs at another Neon host (ST-08's restore drill, deploy/README.md).
# Takes the URLs from deploy/staging/.env.staging, changes only the host, and sends each to Vercel
# on stdin. Prints the hosts, never a URL. The env file itself keeps main's host for the switch back.
#   scripts/swap-db-host.sh <new pooled host>     # point staging at the branch
#   scripts/swap-db-host.sh main                  # back to the env file's host
set -euo pipefail
new=${1:?usage: scripts/swap-db-host.sh <host>|main}
env=deploy/staging/.env.staging
for name in DATABASE_URL DATABASE_URL_PLATFORM; do
  url=$(grep "^$name=" "$env" | head -1 | cut -d= -f2-)
  [[ -n $url ]] || { echo "$name not in $env" >&2; exit 1; }
  [[ $url == *sslmode=verify-full* ]] || { echo "$name: sslmode is not verify-full" >&2; exit 1; }
  rest=${url#*@}
  host=${rest%%[:/]*}
  target=$host
  [[ $new == main ]] || target=$new
  echo "$name: $host -> $target"
  printf '%s' "${url%%@*}@$target${rest#"$host"}" | vercel env update "$name" production --yes >/dev/null
done
echo "Done. Now: vercel deploy --prod"
