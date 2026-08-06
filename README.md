# SDP – Single Dealer Platform

A modern FX single dealer platform built with Spring Boot WebFlux, raw WebSockets, and React — starting as a minimal streaming skeleton and evolving incrementally into a reactive, event-driven trading platform.

**[CLAUDE.md](CLAUDE.md) is the authoritative project directive** — vision, architecture, coding standards, roadmap, and developer workflow all live there (split across [docs/](docs/)). This README is just a quick orientation.

## Status

MVP 0.1 done: live FX price ticks, double-click to create a trade, and a trade blotter, all flowing end-to-end over one WebSocket connection. See the [MVP 0.1 retro](docs/retros/0001-mvp-0.1.md) for what shipped. Currently on MVP 0.2 (trading flow). Work is tracked as [milestones and issues](https://github.com/liccioni/SpringSDP/milestones) on this repo, following the [Roadmap](docs/roadmap.md).

## Project layout

```text
backend/    Spring Boot WebFlux service (Java 21, Gradle)
frontend/   React + TypeScript + Vite app
docs/       architecture, protocol, roadmap, and decision records
```

## Running locally

### Docker Compose

```sh
cd backend && ./gradlew jibDockerBuild && cd ..   # builds the backend image (once, or after backend changes)
docker compose up --build                         # starts both services, rebuilding the frontend image first
```

Open http://localhost:5173. The backend is reachable directly at `ws://localhost:8080/ws`.

Two caveats, both because Compose only builds an image automatically when one doesn't exist yet — it never checks whether the source changed:

* `docker compose up` alone won't pick up **backend** code changes — Compose can't build the backend image itself, since it's built via [Jib](docs/decisions/0005-jib-for-backend-image.md) rather than a Dockerfile (see [ADR 0006](docs/decisions/0006-hello-world-walking-skeleton.md)). Re-run `jibDockerBuild` after backend changes, then `docker compose up` again.
* `docker compose up` alone also won't pick up **frontend** code changes, even though the frontend has a real Dockerfile — Compose reuses whatever `springsdp-frontend` image already exists rather than rebuilding it. Always pass `--build` (as above) to force a rebuild, or the frontend container will keep serving an old bundle indefinitely.

### Without Docker

```sh
cd backend && ./gradlew bootRun    # backend on :8080
npm --prefix frontend run dev      # frontend on :5173
```

## Contributing

See [Developer workflow](docs/workflow.md): GitHub Flow branching, PR-based merges, and CI checks. See [Testing](docs/testing.md) for the unit/integration test tiers.
