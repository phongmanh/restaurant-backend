#!/usr/bin/env bash
# Sends the alert rules and dashboards to Grafana Cloud (architecture §13; deploy/README.md,
# "Alerts and dashboards"). Run it from the repository root after any change to rules.yaml or
# dashboards/, with Docker running:
#
#   deploy/observability/load.sh deploy/observability/.env.grafana
#
# The env file (git-ignored, owner-only) holds:
#   GRAFANA_URL          the stack, https://<stack>.grafana.net
#   GRAFANA_SA_TOKEN     a service account token with the Editor role (dashboards)
#   GRAFANA_PROM_URL     the stack's Prometheus endpoint, https://prometheus-….grafana.net
#   GRAFANA_PROM_USER    its user (instance) ID, a number
#   GRAFANA_RULES_TOKEN  an access policy token with rules:read and rules:write on the stack
#
# Tokens reach mimirtool and curl through the environment and stdin, never the command line, so they
# don't show in `ps` or the shell history. The script prints names and HTTP statuses only.
set -euo pipefail

env_file=${1:?usage: load.sh <env file>}
here=$(cd "$(dirname "$0")" && pwd)

while IFS= read -r line; do
  case "$line" in '' | '#'*) ;; *) export "${line?}" ;; esac
done <"$env_file"
for name in GRAFANA_URL GRAFANA_SA_TOKEN GRAFANA_PROM_URL GRAFANA_PROM_USER GRAFANA_RULES_TOKEN; do
  [ -n "${!name:-}" ] || { echo "missing $name in $env_file" >&2; exit 1; }
done

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# Rules: Mimir's ruler keeps them per namespace. mimirtool wants the namespace in the file, and
# promtool (AlertRulesTest) refuses it there, so it is added here.
{ echo "namespace: restaurant-backend"; cat "$here/rules.yaml"; } >"$work/rules.yaml"
MIMIR_ADDRESS=$GRAFANA_PROM_URL MIMIR_TENANT_ID=$GRAFANA_PROM_USER MIMIR_API_KEY=$GRAFANA_RULES_TOKEN \
  docker run --rm -e MIMIR_ADDRESS -e MIMIR_TENANT_ID -e MIMIR_API_KEY -v "$work:/work:ro" \
  grafana/mimirtool:2.17.0 rules sync /work/rules.yaml
echo "rules: synced namespace restaurant-backend"

# Dashboards: replaced by uid, so a second run updates them in place.
revision=$(git -C "$here" rev-parse --short HEAD 2>/dev/null || echo unknown)
for file in "$here"/dashboards/*.json; do
  python3 -I -c 'import json, sys
d = json.load(open(sys.argv[1])); d["id"] = None
json.dump({"dashboard": d, "overwrite": True, "message": "load.sh at " + sys.argv[2]}, sys.stdout)' \
    "$file" "$revision" >"$work/body.json"
  status=$(printf 'header = "Authorization: Bearer %s"\n' "$GRAFANA_SA_TOKEN" |
    curl --silent --show-error --config - --output "$work/answer.json" --write-out '%{http_code}' \
      -H 'Content-Type: application/json' --data-binary "@$work/body.json" "${GRAFANA_URL%/}/api/dashboards/db")
  echo "dashboard $(basename "$file"): HTTP $status"
  [ "$status" = 200 ] || { python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1])).get("message"))' "$work/answer.json" >&2; exit 1; }
done
