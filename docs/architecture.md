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
* Jib (Gradle plugin) — builds each Java service's container image; no hand-written Dockerfiles for them. See [ADR 0005](decisions/0005-jib-for-backend-image.md).

Used to run the services and frontend consistently across machines. This is packaging for local/dev use, not by itself the reason for splitting into services — see ADR 0022 for that decision.

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

## Service architecture

Three independent Spring Boot services, connected via RabbitMQ (Spring Cloud
Stream) rather than in-process calls — see [ADR 0022](decisions/0022-service-topology.md)
for the service boundaries and the strangler-fig migration (#89-#94) that
got here from the original single-process monolith.

```text
                     UI (React + AG Grid)
                             |
                    WebSocket / OAuth2 login
                             |
                     WebSocket Gateway ------ Keycloak (OAuth2 login)
                       |    |    |
                   Redis    |    RabbitMQ (fanout + request/reply)
                (session)   |         |
                        EventBus      |
                    (in-process,      |
                     WS fan-out)      |
                                      |
                    +-----------------+-----------------+
                    |                                    |
            Market Data Service              Backend/Trading Service
            (price tick generator)         (trade + audit domain logic)
                    |                                    |
                (no state)                          Postgres
                                              (trades, audit_events)
```

Every event crossing a service boundary rides one of RabbitMQ's fanout
exchanges (`PRICE_TICK`, `TRADE_CREATED`/`TRADE_REJECTED`, `SESSION_STARTED`,
`LOGIN_SUCCESS`/`LOGIN_ERROR`/`LOGOUT`) or the correlated `trade-requests`/
`trade-responses` request/reply pair (`CREATE_TRADE`/`CONFIRM_TRADE`/
`CANCEL_TRADE`/`GET_TRADE_HISTORY`) - see [protocol.md](protocol.md) for the
wire shapes and [ADR 0022](decisions/0022-service-topology.md) for why each
shape was chosen. The Gateway's own in-process `EventBus` only fans a
RabbitMQ-relayed event out to the WebSocket sessions subscribed to it -
it never originates an event itself.

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
gateway/               the only service exposed to the browser (issue #94)
  config/              Keycloak/Redis security, WebSocket registration
  websocket/           SdpWebSocketHandler - protocol dispatch
  eventbus/            in-process fan-out to WebSocket sessions
  market/              price-tick relay, subscription state
  trade/               request/reply plumbing to trading-service
  session/             per-connection identity (ADR 0017)
  common/              EventBus payload types

market-data-service/   price tick generation, see ADR 0022
trading-service/       trade + audit domain logic, R2DBC/Postgres access
  trading/
  audit/

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
Gradle builds (not subprojects of one another), each with its own `gradlew`
wrapper, consuming `contracts/` via a Gradle composite build rather than a
multi-module project - see ADR 0022 for why. They started as minimal
skeletons proving the container/network topology worked (#89), absorbed one
message flow at a time from the original monolith (#90-#93), and by #94 the
monolith itself was deleted - `gateway/` ended up with the largest package
list of the three since it inherited everything that was never anyone
else's domain (session/websocket/eventbus), not because it's a second
monolith in disguise: none of those packages hold trading or market-data
domain logic, only transport and fan-out.
