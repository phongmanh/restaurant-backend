#!/bin/sh
# Vercel's container runtime does not pass the image's ENV PATH to the process it starts, so `java`
# is not found (exit 127) unless PATH is rebuilt from JAVA_HOME. Found in ktor-sample; see its
# docs/VERCEL.md.
set -eu
PATH="${JAVA_HOME:-/opt/java/openjdk}/bin:${PATH:-/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin}"
export PATH
exec "$@"
