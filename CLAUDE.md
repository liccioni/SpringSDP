# SDP – Single Dealer Platform

## Project directive

This document is the primary directive for all work on the Single Dealer Platform (SDP) project. Every contributor, AI agent, or subagent should treat this file as the authoritative source for project goals, architecture principles, and development priorities.

The objective is not to build a demo application. The objective is to build a simplified but professionally structured dealer platform that can evolve incrementally into a realistic trading system.

⸻

# Vision

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

# Core philosophy

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

# MVP 0.1 goal

A user can:

1. Open the application
2. See live FX price ticks
3. Double-click a price
4. Create a trade
5. See the trade appear in the trade blotter

That is the entire MVP.

⸻

# Technology stack

Backend

* Java 21
* Spring Boot 3.x
* Spring WebFlux
* Reactor
* Raw WebSockets
* Jackson
* Gradle

Frontend

* React
* TypeScript
* Vite
* AG Grid Community
* Native WebSocket API

Local environment

* Docker
* Docker Compose

Used to run the backend and frontend consistently across machines. This is packaging for local/dev use, not a move toward microservices — it does not conflict with "no microservices" in the core philosophy.

Testing

Backend

* JUnit 5
* Reactor Test (StepVerifier)
* Spring WebFlux Test (WebTestClient)
* Testcontainers — from MVP 0.4 onward, see [ADR 0002](docs/decisions/0002-containerization-and-testcontainers.md)

Frontend

* Vitest
* React Testing Library
* mock-socket — for simulating the backend WebSocket in integration tests

⸻

# Architectural principles

Reactive at the edges

WebFlux and Reactor should be used for:

* WebSocket communication
* streaming market data
* asynchronous event processing

Do not force reactive programming into every internal data structure.

Simplicity is preferred.

Event-driven communication

All communication should be modeled as events.

Examples:

* PRICE_TICK
* CREATE_TRADE
* TRADE_CREATED

This allows future integration with Redis Pub/Sub or Kafka with minimal protocol changes.

Separation of concerns

The project should maintain clear boundaries.

Market data should not create trades.

Trading should not generate prices.

WebSocket handling should not contain business logic.

⸻

# Initial architecture

```text
UI (React + AG Grid)
        |
    WebSocket
        |
Spring WebFlux
     /     \
MarketData  TradeService
   Service
        |
   In-memory state
```

⸻

# Domain model

PriceTick

* symbol
* bid
* ask
* timestamp

Trade

* id
* symbol
* side
* price
* quantity
* timestamp

Side

* BUY
* SELL

⸻

# WebSocket protocol

Server → client

PRICE_TICK

TRADE_CREATED

Client → server

CREATE_TRADE

All messages should be JSON.

Use a message envelope with a type field.

Endpoint

The backend exposes a single WebSocket endpoint at `/ws` on port 8080 (Spring Boot default) — one connection carries every event type via the envelope's `type` field, rather than a socket per event type.

The frontend connects directly to this URL (no dev-server proxy) via a `VITE_WS_URL` environment variable, defaulting to `ws://localhost:8080/ws` for local `npm run dev`. Docker Compose overrides this to the backend service's container address.

⸻

# Project structure

```text
backend/
  config/
  websocket/
  market/
  trade/
  common/
  Dockerfile

frontend/
  components/
  services/
  types/
  Dockerfile

docs/
  architecture.md
  roadmap.md
  protocol.md
  decisions/

docker-compose.yml
```

⸻

# Coding standards

General

Favor readability over cleverness.

Prefer explicit code.

Avoid unnecessary abstraction.

Keep classes focused.

Reactive code

Avoid deeply nested Reactor chains.

Extract meaningful methods.

Keep Flux and Mono pipelines understandable.

Naming

Use trading terminology.

Examples:

* TradeBlotter
* MarketDataService
* ExecutionService
* TradeRequest
* TradeExecution

Avoid generic names such as:

* DataService
* Utils
* Manager

⸻

# Incremental roadmap

MVP 0.1 – Streaming skeleton

* WebSocket connection
* fake FX price generator
* price grid
* trade creation
* trade blotter

MVP 0.2 – Trading flow

* quantity entry
* buy/sell actions
* execution confirmations
* trade validation

MVP 0.3 – Reactive architecture

* event bus
* reactive services
* subscription management
* improved state handling

MVP 0.4 – Persistence

* PostgreSQL
* R2DBC
* trade history
* application restart recovery

MVP 0.5 – Dealer platform foundation

* authentication
* sessions
* market data subscriptions
* execution workflows
* audit events

⸻

# Architecture decision records

Significant decisions should be documented in:

docs/decisions/

Format:

* context
* decision
* consequences

Examples:

* use WebFlux
* use raw WebSockets
* use AG Grid
* use event-driven protocol

⸻

# Developer workflow

Issues should be:

* small
* focused
* independently completable

Each issue should belong to a milestone.

Large features should be decomposed into implementation tasks.

Branching

The project uses GitHub Flow. `main` is always deployable.

* Every change happens on a short-lived branch off `main`.
* No `develop`, `release/*`, or `hotfix/*` branches.
* Branch names are tied to the GitHub issue they implement:
  `feature/<issue#>-slug`, `fix/<issue#>-slug`, `docs/<issue#>-slug`, `chore/<issue#>-slug`.

Commits

Use lightweight Conventional Commit prefixes: `feat:`, `fix:`, `docs:`, `chore:`, `test:`.

This is a convention, not tool-enforced.

Pull requests

Every change, including changes to this file, goes through a pull request into `main`.

* Reference the issue being closed (`Closes #N`).
* Fill in the PR template checklist (tests added, docs updated if architecture changed).
* Required CI checks must be green before merge.
* No mandatory human approval while the project has a single maintainer. Self-merge once CI passes.
* Squash merge only. The source branch is deleted on merge.

Continuous integration

GitHub Actions runs a single `CI` workflow (`.github/workflows/ci.yml`) on every pull request and on push to `main`.

* A `detect` job checks whether `backend/` or `frontend/` exist yet. Until they do, the `backend` and `frontend` jobs report as skipped (passing), so CI stays green during early scaffolding.
* The `backend` job runs `./gradlew test`, `./gradlew integrationTest`, and `./gradlew jacocoTestReport`.
* The `frontend` job runs `npm run test:unit`, `npm run test:integration`, `npm run build`, and `npm run test:coverage`.

Any change that scaffolds the backend or frontend must provide these exact Gradle tasks / npm scripts so CI keeps working without further changes to the workflow file.

Test levels

Two tiers, for both backend and frontend:

* Unit tests — isolated, no Spring context / no real DOM dependencies beyond a component under test. Fast, run on every change.
  * Backend: JUnit 5 tests without the `integration` tag, run via the Gradle `test` task.
  * Frontend: Vitest files matching `*.test.ts(x)`, excluding `*.integration.test.ts(x)`, run via `npm run test:unit`.
* Integration tests — exercise real wiring across a boundary (WebFlux/WebSocket endpoints, real component trees).
  * Backend: JUnit 5 tests tagged `@Tag("integration")`, run via the Gradle `integrationTest` task. Tests that need real infrastructure (starting with PostgreSQL from MVP 0.4) use Testcontainers rather than mocks. GitHub Actions' `ubuntu-latest` runners have Docker preinstalled, so this needs no CI workflow changes.
  * Frontend: Vitest files matching `*.integration.test.ts(x)`, run via `npm run test:integration`.

There is no end-to-end tier yet. Add one only when there's a real UI worth exercising that way.

Code coverage

Coverage is measured and reported on every PR (Jacoco for the backend, Vitest's `v8` coverage provider for the frontend), and uploaded as a CI artifact. It is not gated — CI does not fail on low coverage. Revisit this once the codebase and test suite are established.

⸻

# Agent instructions

When working on this project:

1. Preserve architecture.
2. Keep implementations simple.
3. Avoid premature optimization.
4. Prefer incremental progress.
5. Document important decisions.
6. Keep the application runnable at every stage.

If a proposed feature increases complexity significantly, create a roadmap issue instead of implementing it immediately.

The project should evolve through many small, working iterations rather than large architectural rewrites.