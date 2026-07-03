#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

echo "== Server unit tests =="
npm test --prefix "$ROOT_DIR/server"

echo "== Local sync HTTP smoke =="
npm run sync:local-smoke --prefix "$ROOT_DIR"

echo "== Android CloudSyncClient HTTP smoke =="
npm run sync:android-smoke --prefix "$ROOT_DIR"

echo "== Mac CloudSyncClient HTTP smoke =="
npm run sync:mac-smoke --prefix "$ROOT_DIR"

echo "OK: sync smoke suite passed"
