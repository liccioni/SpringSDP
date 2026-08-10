# Architecture

⸻

## Technology stack

### Backend

* Java 21
* Spring Boot 4.x — see [ADR 0004](decisions/0004-spring-boot-4.md)
* Spring WebFlux — see [ADR 0007](decisions/0007-use-webflux.md)
* Reactor
* Raw WebSockets — see [ADR 0008](decisions/0008-use-raw-websockets.md)
* Spring Security + Keycloak (OAuth2 authorization code grant) — see [ADR 0020](decisions/0020-keycloak-oauth2-redis-session.md), superseding [ADR 0016](decisions/0016-authentication.md)
* Spring Session, Redis-backed — see [ADR 0020](decisions/0020-keycloak-oauth2-redis-session.md)
* Jackson
* Gradle

### Frontend

* React
* TypeScript
* Vite
* AG Grid Community — see [ADR 0009](decisions/0009-use-ag-grid.md)
* Native WebSocket API

### Local environment

* Docker
* Docker Compose
* Jib (Gradle plugin) — builds the backend's container image; no hand-written backend Dockerfile. See [ADR 0005](decisions/0005-jib-for-backend-image.md).

Used to run the backend and frontend consistently across machines. This is packaging for local/dev use, not a move toward microservices — it does not conflict with "no microservices" in CLAUDE.md's core philosophy.

See [Testing](testing.md) for the testing stack.

⸻

## Architectural principles

### Reactive at the edges

WebFlux and Reactor should be used for:

* WebSocket communication
* streaming market data
* asynchronous event processing

Do not force reactive programming into every internal data structure.

Simplicity is preferred.

### Event-driven communication

All communication should be modeled as events.

Examples:

* PRICE_TICK
* CREATE_TRADE
* TRADE_CREATED

This allows future integration with Redis Pub/Sub or Kafka with minimal protocol changes. See [ADR 0010](decisions/0010-event-driven-protocol.md) for the wire protocol this produces, and [ADR 0012](decisions/0012-in-process-event-bus.md) for the in-process event bus that backs it — domain services publish events, the WebSocket layer only subscribes and dispatches generically.

### Separation of concerns

The project should maintain clear boundaries.

Market data should not create trades.

Trading should not generate prices.

WebSocket handling should not contain business logic.

⸻

## Initial architecture

```text
UI (React + AG Grid)
        |
    WebSocket
        |
Spring WebFlux
        |
    EventBus
     /     \
MarketData  TradeService
   Service
        |
   In-memory state
```

⸻

## Domain model

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

## Project structure

```text
backend/               the monolith - still serves the full app until #94
  config/
  websocket/
  eventbus/
  market/
  trade/
  session/
  audit/
  common/

gateway/               skeleton from #89 - see ADR 0022
market-data-service/   skeleton from #89 - see ADR 0022
trading-service/       skeleton from #89 - see ADR 0022
contracts/             shared message-contract types, see ADR 0022

frontend/
  components/
  services/
  theme/
  types/
  Dockerfile

docs/
  architecture.md
  protocol.md
  roadmap.md
  code-style.md
  workflow.md
  testing.md
  decisions/

docker-compose.yml
```

`gateway/`, `market-data-service/`, and `trading-service/` are independent
Gradle builds (not subprojects of `backend/`), each with its own `gradlew`
wrapper, consuming `contracts/` via a Gradle composite build rather than a
multi-module project - see ADR 0022 for why. As of MVP 0.7's start, they are
minimal skeletons proving the container/network topology works; the package
layout under `backend/` above is what they'll eventually absorb, one flow at
a time (#90-#93), before the monolith is decommissioned (#94).
