#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd -- "$ROOT"
if [[ ! -f .build/server.jar ]]; then
    printf '%s\n' 'Falta .build/server.jar. Ejecuta bash scripts/build.sh primero.' >&2
    exit 1
fi
exec java -jar .build/server.jar "$@"
