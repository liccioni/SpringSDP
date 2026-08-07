# SDP – Single Dealer Platform

## Project directive

This document is the primary directive for all work on the Single Dealer Platform (SDP) project. Every contributor, AI agent, or subagent should treat this file — together with the docs it imports below — as the authoritative source for project goals, architecture principles, and development priorities.

The objective is not to build a demo application. The objective is to build a simplified but professionally structured dealer platform that can evolve incrementally into a realistic trading system.

⸻

## Vision

Build a modern FX single dealer platform using Spring Boot WebFlux, WebSockets, and React.

The platform will begin as a minimal streaming application and progressively evolve into a reactive event-driven trading platform with realistic architecture and domain boundaries.

The project should emphasize:

* reactive programming
* streaming market data
* event-driven communication
* clean architecture
* incremental complexity
* production-quality code structure

⸻

## Core philosophy

Start simple

Every milestone should produce a working application.

The first implementation should avoid unnecessary infrastructure.

No Kafka.

No Redis.

No authentication.

No database.

No microservices.

Everything should run locally with minimal dependencies.

Preserve architecture

Although the implementation starts simple, the architecture should be designed so that future infrastructure can be introduced without major refactoring.

Domain boundaries matter from the beginning.

⸻

## Current milestone

MVP 0.1 through MVP 0.5 are done (see [retro 0005](docs/retros/0005-mvp-0.5.md)). MVP 0.6 (identity & session — Keycloak, Spring Security, Redis-backed Spring Session) is next up, followed by MVP 0.7 (service topology & messaging — splitting into a WebSocket gateway, market data service, and backend/trading service over RabbitMQ). Both reverse constraints in this file's Core philosophy section (no framework auth, no Redis, no microservices) the same deliberate way "no authentication" was lifted for MVP 0.5. See [Roadmap](docs/roadmap.md) for the full milestone breakdown and issue list.

⸻

## Documentation

The sections below are split into focused docs and imported here so every session has the full picture. Each is also readable standalone.

@docs/architecture.md
@docs/protocol.md
@docs/roadmap.md
@docs/code-style.md
@docs/workflow.md
@docs/testing.md

Architecture decision records (context, decision, consequences for significant decisions — e.g. use WebFlux, use raw WebSockets, use AG Grid, use event-driven protocol) live in `docs/decisions/`. They're read on demand rather than imported here, since they're historical record rather than current-state reference.

⸻

## Agent instructions

When working on this project:

1. Preserve architecture.
2. Keep implementations simple.
3. Avoid premature optimization.
4. Prefer incremental progress.
5. Document important decisions.
6. Keep the application runnable at every stage.

If a proposed feature increases complexity significantly, create a roadmap issue instead of implementing it immediately.

The project should evolve through many small, working iterations rather than large architectural rewrites.
