#!/usr/bin/env bash
# Builds every Jib image (backend, and the gateway/market-data-service/
# trading-service skeletons from #89), brings up all containers via Docker
# Compose, and opens the UI once it's responding. See README.md's "Docker
# Compose" section for why both the Jib build and --build are needed.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$REPO_ROOT"

FRONTEND_URL="http://localhost:5173"
BACKEND_URL="http://localhost:8080"

echo "==> Building backend image (Jib)"
(cd backend && ./gradlew jibDockerBuild)

echo "==> Building gateway, market-data-service, trading-service images (Jib)"
(cd gateway && ./gradlew jibDockerBuild)
(cd market-data-service && ./gradlew jibDockerBuild)
(cd trading-service && ./gradlew jibDockerBuild)

echo "==> Starting containers"
docker compose up --build -d

echo "==> Waiting for backend and frontend to respond"
for _ in $(seq 1 30); do
  backend_up=false
  frontend_up=false
  # -s (no -f): any response counts as "up", including the backend's 404 on /
  curl -s -o /dev/null "$BACKEND_URL" && backend_up=true || true
  curl -s -o /dev/null "$FRONTEND_URL" && frontend_up=true || true

  if $backend_up && $frontend_up; then
    break
  fi
  sleep 1
done

echo "==> Opening $FRONTEND_URL"
if command -v open >/dev/null 2>&1; then
  open "$FRONTEND_URL"
elif command -v xdg-open >/dev/null 2>&1; then
  xdg-open "$FRONTEND_URL"
else
  echo "Open $FRONTEND_URL in your browser."
fi

echo
echo "Backend:  $BACKEND_URL (ws://localhost:8080/ws)"
echo "Frontend: $FRONTEND_URL"
echo "Logs:     docker compose logs -f"
echo "Stop:     ./stopAllDocker.sh"
