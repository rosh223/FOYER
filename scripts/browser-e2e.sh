#!/usr/bin/env bash
# Real-Chromium end-to-end tests (official Playwright image) against a running stack.
#   ./scripts/browser-e2e.sh            screenshots land in ./e2e/screenshots
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p e2e/screenshots
docker run --rm --network host --ipc=host -e FOYER_URL="${FOYER_URL:-http://localhost:3005}" -e SHOP_URL="${SHOP_URL:-http://localhost:3006}" \
  -v "$PWD/e2e:/e2e" mcr.microsoft.com/playwright:v1.64.1-noble sh -c \
  "mkdir -p /work && cd /work && cp /e2e/browser.e2e.mjs . && npm init -y >/dev/null && npm install --silent --no-audit --no-fund playwright@1.64.1 && OUT=/e2e/screenshots node browser.e2e.mjs"
