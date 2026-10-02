#!/usr/bin/env bash
# Usage: ./burst/burst.sh <BASE_URL>
# Env:   ADMIN_KEY (default dev-admin-key), TOTAL (default 20000), MAX_IN_FLIGHT, SEED
# Uses a local JDK 21+ if present, otherwise runs inside the eclipse-temurin:21 Docker image.
set -euo pipefail

BASE_URL="${1:?usage: ./burst/burst.sh <BASE_URL>}"
DIR="$(cd "$(dirname "$0")" && pwd)"

# 20k concurrent connections need far more than the default open-file limit
ulimit -n 65535 2>/dev/null || ulimit -n "$(ulimit -Hn)" 2>/dev/null || true

if command -v java >/dev/null 2>&1 && java -version 2>&1 | grep -qE 'version "(2[1-9]|[3-9][0-9])'; then
  exec java -Xmx2g "$DIR/Burst.java" "$BASE_URL"
else
  exec docker run --rm --ulimit nofile=65535:65535 \
    -e ADMIN_KEY -e TOTAL -e MAX_IN_FLIGHT -e SEED \
    -v "$DIR:/b:ro" eclipse-temurin:21 java -Xmx2g /b/Burst.java "$BASE_URL"
fi