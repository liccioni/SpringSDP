# 0006. Hello-world walking skeleton over WebSocket, dockerized end-to-end

Date: 2026-08-05

Status: Accepted

## Context

Before building the real price-tick/trade features (issues #5, #6), we wanted a minimal but real proof that every layer of the stack works together: backend WebSocket handler, frontend WebSocket client, and both services running under Docker Compose. This substantially completes issues #4 (protocol/envelope), #7 (WebSocket handler wiring), #8 (frontend WebSocket client), #32 (frontend Dockerfile), and #33 (docker-compose.yml) — not as throwaway demo code, but as the real foundation those issues called for, built minimally.

Three concrete, non-obvious things surfaced while building this that are worth recording:

## Decisions

### 1. Spring Boot 4.1 defaults to Jackson 3, not Jackson 2
`spring-boot-starter-webflux` no longer pulls in classic Jackson (`com.fasterxml.jackson.databind`). Spring Boot 4.1's own auto-configured `ObjectMapper` bean is **Jackson 3** (`tools.jackson.databind.ObjectMapper`, from `tools.jackson.core:jackson-databind`, pulled in via `org.springframework.boot:spring-boot-starter-json`). `jackson-annotations` stayed on the old `com.fasterxml.jackson.core` coordinates as a bridge, but `jackson-core`/`jackson-databind` moved to the new `tools.jackson` groupId and package. Any backend code touching JSON (this handler, and later market/trade DTOs) must import from `tools.jackson.databind`, not `com.fasterxml.jackson.databind`. Confirmed by inspecting the resolved dependency tree and the actual class file inside the resolved jar — this is undocumented enough in general knowledge to be worth stating explicitly here rather than rediscovering it later.

### 2. Node 20 → 24 (CI and Docker)
While wiring up the frontend Dockerfile, we found `.github/workflows/ci.yml`'s `frontend` job was still pinned to Node 20, which reached end-of-life on 2026-04-30 — already unsupported. Bumped CI and the frontend Dockerfile's build stage to Node 24 (Active LTS, supported through April 2028), matching the rigor already applied to Spring Boot (ADR 0004).

### 3. Docker Compose + Jib: a two-command flow, not a single one
Docker Compose's `build:` field only understands Dockerfiles. Jib (ADR 0005) deliberately bypasses Dockerfiles entirely, so Compose cannot build the backend image itself. The real, still-simple flow is:

```sh
./gradlew -p backend jibDockerBuild   # once, or after backend changes
docker compose up                     # frontend rebuilds automatically (it has a Dockerfile); backend uses the cached image
```

This is not a reversal of ADR 0005 — it's an accurate accounting of what Jib does and doesn't do, documented in the README rather than glossed over.

**Correction (found during MVP 0.1 close-out, see [retro 0001](../retros/0001-mvp-0.1.md)):** the claim above that "frontend rebuilds automatically" is wrong. Docker Compose only builds an image automatically when one doesn't exist *yet* for that service — it never checks whether the build context changed on a later `docker compose up`, Dockerfile or not. A `springsdp-frontend` image built once during this walking-skeleton work kept getting silently reused for the rest of MVP 0.1, serving a stale bundle missing the price grid and trade blotter. The README now correctly says to always pass `--build` (`docker compose up --build`) to pick up frontend changes, the same way `jibDockerBuild` must be re-run for backend changes.

### 4. WebSocket addressing under Docker Compose (corrects ADR 0003)
The frontend's `VITE_WS_URL` stays `ws://localhost:8080/ws` even when served from its own container, because the **browser** — not the frontend container — opens the WebSocket connection. It needs the backend's host-published port (`docker-compose.yml` publishes `8080:8080`), not a Docker-internal service name like `backend`, which the host browser cannot resolve. ADR 0003's claim that Compose would override this to "the backend service's container address" was incorrect and is corrected there.

### 5. What was built
- Backend: `Envelope` record (`type`, `payload`), `SdpWebSocketHandler` (sends one `HELLO` envelope on connect, then keeps the session open for future streaming), wired at `/ws` via `WebSocketConfig`. Verified with a `ReactorNettyWebSocketClient`-based integration test (`@Tag("integration")`) — `WebTestClient` itself doesn't support WebSocket message assertions, so this is the correct tool, not what was originally sketched.
- Frontend: `types/envelope.ts`, `services/socket.ts` (connects, parses envelopes), `components/Greeting.tsx` (renders the greeting once received), wired into `App`. Verified with a `mock-socket`-based unit test and an integration test rendering the full `App` tree.
- `frontend/Dockerfile` (multi-stage, digest-pinned `node:24-alpine` → `nginx:alpine`) plus a `.dockerignore` — its absence was initially letting `node_modules`/`dist` (140MB) into the build context and risking the container's own npm-installed `node_modules` being overwritten by the host's platform-specific one via `COPY . .`.
- Root `docker-compose.yml` wiring both services together.

## Consequences

- Any future backend JSON handling must target Jackson 3 (`tools.jackson.*`), not Jackson 2 — a real gotcha for anyone (human or agent) used to classic Jackson APIs.
- The README documents the two-command Docker flow explicitly; `docker compose up` alone will not pick up backend code changes without re-running `jibDockerBuild` first.
- The `HELLO` envelope type is a bootstrap artifact, not part of the real domain protocol (`PRICE_TICK`/`CREATE_TRADE`/`TRADE_CREATED` in CLAUDE.md) — expect it to be removed or repurposed once #5/#7 implement real price-tick streaming.
