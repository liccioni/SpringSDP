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

The first implementation avoided unnecessary infrastructure by design: no Kafka, no Redis, no authentication, no database, no microservices — everything ran locally with minimal dependencies.

Those five constraints were MVP 0.1's starting point, not a permanent ruleset. Each is lifted deliberately, exactly when a milestone's actual goal needs it — never preemptively — and each reversal is recorded in the Current milestone section below rather than by silently rewriting this list. As of the current milestone: authentication, Redis, a database, and microservices are all in place (MVP 0.5/0.6/0.7). Kafka remains the one constraint still untouched as originally stated — MVP 0.7 used RabbitMQ instead (see [ADR 0021](docs/decisions/0021-rabbitmq-network-segmentation.md)), so "no Kafka" specifically still holds even though "minimal messaging infrastructure" no longer does.

What endures isn't the specific list above — it's the process: avoid premature complexity, add infrastructure only when a concrete milestone need justifies it, and keep the application runnable at every stage.

Preserve architecture

Although the implementation starts simple, the architecture should be designed so that future infrastructure can be introduced without major refactoring.

Domain boundaries matter from the beginning.

⸻

## Current milestone

MVP 0.1 through MVP 0.8 are done (see [retro 0006](docs/retros/0006-mvp-0.6.md) for MVP 0.6, [retro 0007](docs/retros/0007-mvp-0.7.md) for MVP 0.7, and [retro 0008](docs/retros/0008-mvp-0.8.md) for MVP 0.8). MVP 0.6 (identity & session — Keycloak, Spring Security, Redis-backed Spring Session) reversed this file's Core philosophy section's "no framework auth" and "no Redis" constraints the same deliberate way "no authentication" was lifted for MVP 0.5. MVP 0.7 (service topology & messaging) reversed "no microservices" the same way: the monolith split into a WebSocket gateway (`gateway/`), a market data service, and a backend/trading service over RabbitMQ (#89-#93), and the original monolithic `backend/` module was deleted once every flow had migrated off it (#94). MVP 0.8 (session lifecycle & cleanup) closed the two gaps left over from earlier milestones: a real logout flow (#102, [ADR 0023](docs/decisions/0023-oidc-rp-initiated-logout.md)) and automatic cleanup of pending trades left dangling on disconnect (#79, [ADR 0024](docs/decisions/0024-cancel-pending-trades-on-disconnect.md)). MVP 0.9 (role enforcement & blotter usability) is now in progress: the `trader`/`viewer` Keycloak roles defined since MVP 0.6 were never actually enforced anywhere in the stack (#117), and the trade blotter has no filtering or pagination as trade volume grows (#118) — see [Roadmap](docs/roadmap.md) for both issues and the backlog issues deliberately left unscheduled alongside it.

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
