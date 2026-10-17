#!/bin/sh
# Sends an environment's settings and secrets to the linked Vercel project's Production environment
# (deploy/README.md). Run it yourself from the repository root: the auto-mode safety check blocks
# Claude from writing to a secret store. Prints names only; values go to the CLI on stdin.
#
#   deploy/push-env.sh deploy/staging/staging.env deploy/staging/.env.staging
set -eu

# Read by the operator's machine only, never by the service.
LOCAL_ONLY="DATABASE_URL_MIGRATOR_DIRECT VERCEL_AUTOMATION_BYPASS_SECRET"

[ "$#" -ge 1 ] || { echo "usage: $0 <env file>..." >&2; exit 2; }
[ -f .vercel/project.json ] || { echo "run from the repository root after 'vercel link'" >&2; exit 2; }

for file in "$@"; do
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in ''|'#'*) continue ;; esac
    name=${line%%=*}
    case " $LOCAL_ONLY " in *" $name "*) continue ;; esac
    [ -n "${line#*=}" ] || { echo "$name is empty in $file: fill it first" >&2; exit 1; }
  done < "$file"
done

for file in "$@"; do
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in ''|'#'*) continue ;; esac
    name=${line%%=*}
    case " $LOCAL_ONLY " in *" $name "*) continue ;; esac
    vercel env rm "$name" production --yes </dev/null >/dev/null 2>&1 || true
    printf '%s' "${line#*=}" | vercel env add "$name" production >/dev/null
    echo "set $name"
  done < "$file"
done
