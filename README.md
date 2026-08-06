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
./startUpDocker.sh   # builds the backend + frontend images, starts both containers, opens http://localhost:5173
./stopAllDocker.sh   # stops and removes the containers
```

The backend is reachable directly at `ws://localhost:8080/ws`.

`startUpDocker.sh` always rebuilds both images before starting, which avoids two easy-to-hit caveats of running `docker compose up` by hand: Compose can't build the backend image itself, since it's built via [Jib](docs/decisions/0005-jib-for-backend-image.md) rather than a Dockerfile (see [ADR 0006](docs/decisions/0006-hello-world-walking-skeleton.md)) — the script runs `jibDockerBuild` first; and Compose otherwise reuses whatever frontend image already exists rather than rebuilding it, which is why the script always passes `--build`.

### Without Docker

```sh
cd backend && ./gradlew bootRun    # backend on :8080
npm --prefix frontend run dev      # frontend on :5173
```

## Contributing

See [Developer workflow](docs/workflow.md): GitHub Flow branching, PR-based merges, and CI checks. See [Testing](docs/testing.md) for the unit/integration test tiers.
