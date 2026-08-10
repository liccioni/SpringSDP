# SDP – Single Dealer Platform

A modern FX single dealer platform built with Spring Boot WebFlux, raw WebSockets, and React — starting as a minimal streaming skeleton and evolving incrementally into a reactive, event-driven trading platform.

**[CLAUDE.md](CLAUDE.md) is the authoritative project directive** — vision, architecture, coding standards, roadmap, and developer workflow all live there (split across [docs/](docs/)). This README is just a quick orientation.

## Status

MVP 0.1-0.6 done: streaming price ticks and trading flow (see the [MVP 0.1](docs/retros/0001-mvp-0.1.md)/[0.2](docs/retros/0002-mvp-0.2.md) retros) on top of a reactive, event-driven backend architecture (see the [MVP 0.3 retro](docs/retros/0003-mvp-0.3.md)), PostgreSQL persistence ([MVP 0.4 retro](docs/retros/0004-mvp-0.4.md)), and Keycloak/Redis-backed identity and sessions ([MVP 0.5](docs/retros/0005-mvp-0.5.md)/[0.6](docs/retros/0006-mvp-0.6.md) retros). Currently on MVP 0.7 (service topology & messaging). Work is tracked as [milestones and issues](https://github.com/liccioni/SpringSDP/milestones) on this repo, following the [Roadmap](docs/roadmap.md).

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
docker compose up -d postgres redis keycloak   # backend needs all three reachable, even here
cd backend && ./gradlew bootRun                # backend on :8080
npm --prefix frontend run dev                  # frontend on :5173
```

Backend and frontend can run as native processes, but Postgres, Redis, and Keycloak still run via Docker Compose — see [ADR 0014](docs/decisions/0014-postgresql-r2dbc-connectivity.md) for why "without Docker" doesn't mean without Postgres, and [ADR 0020](docs/decisions/0020-keycloak-oauth2-redis-session.md) for the same reasoning extended to Redis and Keycloak since MVP 0.6. The backend itself starts fine without them (nothing calls out to either at startup), but logging in fails as soon as you try it: without Redis, `/oauth2/authorization/keycloak` 500s (can't persist the OAuth2 authorization request to a session); without Keycloak, the browser has nowhere to redirect to.

## Contributing

See [Developer workflow](docs/workflow.md): GitHub Flow branching, PR-based merges, and CI checks. See [Testing](docs/testing.md) for the unit/integration test tiers.
