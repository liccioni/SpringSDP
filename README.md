# SDP – Single Dealer Platform

A modern FX single dealer platform built with Spring Boot WebFlux, raw WebSockets, and React — starting as a minimal streaming skeleton and evolving incrementally into a reactive, event-driven trading platform.

**[CLAUDE.md](CLAUDE.md) is the authoritative project directive** — vision, architecture, coding standards, roadmap, and developer workflow all live there (split across [docs/](docs/)). This README is just a quick orientation.

## Status

MVP 0.1 in progress: a minimal WebSocket "hello world" flows end-to-end from backend to frontend, dockerized. Work is tracked as [milestones and issues](https://github.com/liccioni/SpringSDP/milestones) on this repo, following the [Roadmap](docs/roadmap.md).

## Project layout

```text
backend/    Spring Boot WebFlux service (Java 21, Gradle)
frontend/   React + TypeScript + Vite app
docs/       architecture, protocol, roadmap, and decision records
```

## Running locally

### Docker Compose

```sh
./gradlew -p backend jibDockerBuild   # builds the backend image (once, or after backend changes)
docker compose up                     # starts both services; rebuilds the frontend automatically
```

Open http://localhost:5173. The backend is reachable directly at `ws://localhost:8080/ws`.

Note: `docker compose up` alone won't pick up backend code changes — Compose can't build the backend image itself, since it's built via [Jib](docs/decisions/0005-jib-for-backend-image.md) rather than a Dockerfile (see [ADR 0006](docs/decisions/0006-hello-world-walking-skeleton.md)). Re-run `jibDockerBuild` after backend changes, then `docker compose up` again.

### Without Docker

```sh
./gradlew -p backend bootRun   # backend on :8080
npm --prefix frontend run dev  # frontend on :5173
```

## Contributing

See [Developer workflow](docs/workflow.md): GitHub Flow branching, PR-based merges, and CI checks. See [Testing](docs/testing.md) for the unit/integration test tiers.
