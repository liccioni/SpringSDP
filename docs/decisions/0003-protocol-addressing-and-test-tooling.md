# 0003. WebSocket addressing convention and test tooling

Date: 2026-08-05

Status: Accepted

## Context

Two details were left implicit and would otherwise be invented independently by whichever issue happened to touch them first: where the backend's WebSocket endpoint lives (path/port), how the frontend finds it, and which test libraries fill in the "unit + integration" tiers already defined in CLAUDE.md's Developer workflow section (JUnit 5/Reactor/WebTestClient were implied but unstated for the backend; a WebSocket-mocking library was never chosen for frontend integration tests).

## Decision

* **WebSocket endpoint**: single endpoint at `/ws` on port 8080 (Spring Boot default). All event types (`PRICE_TICK`, `CREATE_TRADE`, `TRADE_CREATED`) share this one connection via the envelope's `type` field — no per-event-type sockets.
* **Frontend addressing**: the frontend reads the WebSocket URL from a `VITE_WS_URL` environment variable, defaulting to `ws://localhost:8080/ws` for local `npm run dev`. No Vite dev-server proxy — the frontend connects to the backend directly. Docker Compose supplies its own value pointing at the backend service's container address.
* **Backend test tooling**: JUnit 5, Reactor Test (`StepVerifier`) for reactive unit tests, Spring WebFlux Test (`WebTestClient`) for integration tests against the WebSocket/HTTP layer, and Testcontainers from MVP 0.4 (per ADR 0002).
* **Frontend test tooling**: Vitest (already chosen) plus React Testing Library for component tests, and `mock-socket` to simulate the backend WebSocket in frontend integration tests without a real backend running.

## Consequences

* The MVP 0.1 backend and frontend scaffolding issues can be implemented without inventing these details ad hoc, and later issues won't disagree with each other on the endpoint address.
* Changing the endpoint path/port later is a protocol change and should get its own ADR superseding this one, not a silent edit.
* `mock-socket` and React Testing Library become direct frontend dev dependencies once the frontend is scaffolded.
