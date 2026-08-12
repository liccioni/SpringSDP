#!/usr/bin/env bash
# Stops and removes the containers + network started by startUpDocker.sh.
# Leaves the built images (sdp-gateway, sdp-market-data-service,
# sdp-trading-service, the frontend image) in place, so the next
# startUpDocker.sh run doesn't have to rebuild from scratch.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$REPO_ROOT"

echo "==> Stopping containers"
docker compose down

echo "==> Done"
