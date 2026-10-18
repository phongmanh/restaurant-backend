#!/bin/sh
# Sends an environment's settings and secrets to its Vercel project's Production environment
# (deploy/README.md). Run it yourself from the repository root: the auto-mode safety check blocks
# Claude from writing to a secret store. Prints names only; values go to the CLI on stdin.
#
#   deploy/push-env.sh deploy/staging/staging.env deploy/staging/.env.staging
#
# The project is restaurant-<APP_ENV>, read from the files, never the linked one: the repository root
# is linked to one project at a time, and on 2026-10-19 a deploy meant for staging went to production.
set -eu

# Read by the operator's machine only, never by the service.
LOCAL_ONLY="DATABASE_URL_MIGRATOR_DIRECT VERCEL_AUTOMATION_BYPASS_SECRET"

[ "$#" -ge 1 ] || { echo "usage: $0 <env file>..." >&2; exit 2; }

app_env=$(cat "$@" | sed -n 's/^APP_ENV=//p' | sort -u)
case "$app_env" in
  staging|production) project="restaurant-$app_env" ;;
  *) echo "the files must set APP_ENV once, to staging or production (found: ${app_env:-none})" >&2; exit 2 ;;
esac
echo "project $project"

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
    vercel env rm "$name" production --project "$project" --yes </dev/null >/dev/null 2>&1 || true
    printf '%s' "${line#*=}" | vercel env add "$name" production --project "$project" >/dev/null
    echo "set $name"
  done < "$file"
done
