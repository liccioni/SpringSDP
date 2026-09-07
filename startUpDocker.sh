#!/usr/bin/env bash
# Builds every Jib image (gateway, market-data-service, trading-service),
# brings up all containers via Docker Compose, and opens the UI once it's
# responding. See README.md's "Docker Compose" section for why both the
# Jib build and --build are needed.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$REPO_ROOT"

# As of issue #103/ADR 0031, `proxy` unifies the frontend and gateway
# origins onto one port - there's only one URL to wait for and open now.
APP_URL="http://localhost:8080"

echo "==> Building gateway, market-data-service, trading-service images (Jib)"
(cd gateway && ./gradlew jibDockerBuild)
(cd market-data-service && ./gradlew jibDockerBuild)
(cd trading-service && ./gradlew jibDockerBuild)

echo "==> Starting containers"
docker compose up --build -d

echo "==> Waiting for the app to respond"
for _ in $(seq 1 30); do
  # -s (no -f): any response counts as "up", including a 404 on /
  curl -s -o /dev/null "$APP_URL" && break || true
  sleep 1
done

echo "==> Opening $APP_URL"
if command -v open >/dev/null 2>&1; then
  open "$APP_URL"
elif command -v xdg-open >/dev/null 2>&1; then
  xdg-open "$APP_URL"
else
  echo "Open $APP_URL in your browser."
fi

echo
echo "App:  $APP_URL (ws://localhost:8080/ws)"
echo "Logs: docker compose logs -f"
echo "Stop: ./stopAllDocker.sh"
