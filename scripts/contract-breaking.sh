#!/bin/sh
# G4 hardening H3: fails when docs/api/openapi.yaml breaks a client of the released contract
# (NFR-COMPAT-01: within /v1, only additive changes). Compares against the contract at a git ref,
# by default the G3 tag where the contract was frozen; a release moves the base to its own tag.
# "Breaking" is oasdiff's ERR level, and WARN fails too (changes that may break a client).
#
# Runs oasdiff's Docker image, so nothing is uploaded: CI runs this script, and so can you.
# Usage: scripts/contract-breaking.sh [base-ref]
set -eu

base="${1:-sdlc/restaurant-ordering/G3}"
image="tufin/oasdiff:v1.33.0@sha256:6263a96dd2ef0726c54e21fea9b8e1607eac4841add0079324b424c1f52b819c"

specs="$(mktemp -d)"
trap 'rm -rf "$specs"' EXIT
git show "$base:docs/api/openapi.yaml" > "$specs/base.yaml"
cp docs/api/openapi.yaml "$specs/revision.yaml"

echo "Breaking changes in docs/api/openapi.yaml against $base:"
docker run --rm -v "$specs:/specs:ro" "$image" breaking /specs/base.yaml /specs/revision.yaml --fail-on WARN
