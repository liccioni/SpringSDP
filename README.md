# SDP – Single Dealer Platform

A modern FX single dealer platform built with Spring Boot WebFlux, raw WebSockets, and React — starting as a minimal streaming skeleton and evolving incrementally into a reactive, event-driven trading platform.

**[CLAUDE.md](CLAUDE.md) is the authoritative project directive** — vision, architecture, coding standards, roadmap, and developer workflow all live there. This README is just a quick orientation.

## Status

Pre-MVP 0.1: no application code yet. Work is tracked as [milestones and issues](https://github.com/liccioni/SpringSDP/milestones) on this repo, following the [Incremental roadmap](CLAUDE.md#incremental-roadmap) in CLAUDE.md.

## Project layout

```text
backend/    Spring Boot WebFlux service (Java 21, Gradle)
frontend/   React + TypeScript + Vite app
docs/       architecture, protocol, roadmap, and decision records
```

## Running locally

Not available yet — this section will be filled in once the backend and frontend are scaffolded (see the MVP 0.1 milestone). The plan:

```sh
docker compose up   # runs backend + frontend together
```

or run each service independently with `./gradlew bootRun` (backend) and `npm run dev` (frontend).

## Contributing

See CLAUDE.md's [Developer workflow](CLAUDE.md#developer-workflow) section: GitHub Flow branching, PR-based merges, CI checks, and the unit/integration test tiers.
