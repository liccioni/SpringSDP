# 0022. Service topology: WebSocket Gateway / Market Data Service / Backend-Trading Service, connected via RabbitMQ + Spring Cloud Stream

Date: 2026-08-10

Status: Accepted

## Context

MVP 0.7 reverses CLAUDE.md's original "no microservices" constraint (see
CLAUDE.md's Core philosophy section) the same deliberate way MVP 0.5/0.6
reversed "no authentication"/"no Redis". [ADR 0010](0010-event-driven-protocol.md)
already made the wire protocol event-driven and envelope-shaped, and
[ADR 0012](0012-in-process-event-bus.md) introduced an in-process `EventBus`
specifically as groundwork for "swapping the in-process EventBus for a real
broker later, with minimal protocol change." Issue #89 is where that bet gets
cashed in: split the monolith into a WebSocket Gateway, a Market Data
Service, and a Backend/Trading Service, connected via RabbitMQ. [ADR 0021](0021-rabbitmq-network-segmentation.md)
already decided the trust boundary (network segmentation, not per-message
auth) and the Docker Compose network layout those services join. This ADR
covers what #89 itself had to decide: the service boundaries, how a shared
message-contract module should exist without one service depending on
another's internals, the RabbitMQ interaction shapes each future flow
migration (#90-#93) will use, and the migration strategy across the whole
milestone.

## Decision

**Three service boundaries**, matching the monolith's existing package
boundaries almost exactly - the split follows domain lines already drawn by
`com.sdp.websocket`/`session` vs `com.sdp.market` vs `com.sdp.trade`/`audit`,
not a new decomposition invented for this issue:

* **WebSocket Gateway** (`gateway/`) - will eventually own `websocket/` and
  `session/`. Pure transport: no Postgres access, the only service exposed to
  the browser once #94 decommissions the monolith. Single instance assumed
  for now (no horizontal scaling, no per-instance reply routing) - revisit
  only if a real scaling need appears.
* **Market Data Service** (`market-data-service/`) - will eventually own
  `market/`'s price generation.
* **Backend/Trading Service** (`trading-service/`) - will eventually own
  `trade/`, `audit/`, and `common/Trade`'s R2DBC/Postgres access. The one
  service with a database; room for more trading domains later (an order
  service, etc.) per issue #89's own framing.

**A shared `contracts/` module for message-contract types**, published as
neither a Maven artifact nor a Gradle subproject of any one service, but
consumed via Gradle's [composite build](https://docs.gradle.org/current/userguide/composite_builds.html)
feature (`includeBuild('../contracts')` in each service's `settings.gradle`,
`implementation 'com.sdp:contracts'` in its `build.gradle`). Gradle
substitutes the dependency automatically by `group:name` match, with no
publish step and no local Maven repository needed. This was chosen over two
alternatives:

* **A true Gradle multi-module build** (one root `settings.gradle` including
  `backend`, `contracts`, and the three new services as subprojects) - would
  force `backend/settings.gradle` to be removed and its `gradlew` wrapper
  relocated, which in turn breaks `.github/workflows/ci.yml`'s
  `working-directory: backend` assumption and `startUpDocker.sh`'s
  `(cd backend && ...)` pattern - real, working infrastructure this issue
  has no reason to disrupt. Composite builds get the shared-dependency
  benefit without touching any of that: `backend/` is completely untouched by
  this issue.
* **Publishing `contracts` as a versioned artifact** (to a local Maven repo
  or a registry) - real infrastructure (a repository, a version/publish
  workflow) with no current need to justify it at this project's scale.

`contracts` is a plain `java-library` module with no Spring, AMQP, or any
other framework dependency - deliberately broker-agnostic, matching Spring
Cloud Stream's whole purpose (the binder is swappable; the payload shape
shouldn't need to know or care). It holds `PriceTick`, `TradeRequest`,
`PendingTrade`, `Trade`, `TradeRejected`, and `Side` - plain records mirroring
today's wire shapes (`com.sdp.common`/`com.sdp.trade` in the monolith), not
the monolith's own types, since those carry Spring Data/`DomainEvent`
coupling (`Persistable`, `@Table`, `eventType()`) that's specific to the
in-process `EventBus` this ADR is retiring, not to the wire shape itself.

**Message contract shapes**, to guide #90-#93's actual RabbitMQ wiring:

* **Fanout exchanges for broadcasts** - `PRICE_TICK` (Market Data Service →
  Gateway), `TRADE_CREATED`/`TRADE_REJECTED` (Backend/Trading Service →
  Gateway). These have exactly one logical "everyone subscribed gets this"
  semantic already (per [docs/protocol.md](../protocol.md)'s broadcast
  semantics), which a fanout exchange models directly.
* **A correlated async pub/sub pair for request/reply** - `CREATE_TRADE` →
  `TRADE_PENDING`, `CONFIRM_TRADE`/`CANCEL_TRADE` → their replies. Explicitly
  **not** `RabbitTemplate`'s synchronous RPC-style
  `convertSendAndReceive` - that call blocks a thread waiting on a reply
  queue per request, which fights WebFlux's whole reactive-at-the-edges
  premise ([architecture.md](../architecture.md)) and reintroduces exactly
  the kind of blocking call the project moved away from adopting WebFlux in
  the first place ([ADR 0007](0007-use-webflux.md)). A correlation-id-based
  async pair (publish a request with a `correlationId` + `replyTo`, consume
  the reply on a separate queue keyed by that id) keeps the whole path
  non-blocking and keeps the app broker-agnostic, per Spring Cloud Stream's
  design intent - swapping the binder later wouldn't mean swapping this
  interaction pattern.

**Strangler-fig migration, not a big-bang cutover**: the three services
stand up in this issue *alongside* the still-running monolith, prove nothing
more than "the multi-service shape works" (health endpoints respond, no
container conflicts, the monolith is untouched), and carry zero real traffic
yet. Flows migrate one at a time in #90-#93, each swapping one piece of the
monolith's `EventBus`-mediated logic for a RabbitMQ-mediated equivalent while
the monolith keeps serving everything else. #94 decommissions the monolith
only once every flow has migrated - the final issue in the milestone, not an
early one.

**Deliberately deferred to #90-#93, not decided here**: none of the three
skeleton services depends on `spring-cloud-stream`, an AMQP client, R2DBC, or
Spring Security yet. Issue #89's own text is explicit that "nothing
migrates yet" - adding broker/database/auth wiring now would be
infrastructure with no concrete flow to prove it against, which is exactly
the premature complexity CLAUDE.md's Core philosophy warns against. Each
service today is `spring-boot-starter-webflux` + `spring-boot-starter-actuator`
+ `contracts`, nothing more.

**Docker Compose placement**, following [ADR 0021](0021-rabbitmq-network-segmentation.md)'s
own stated consequence directly: `gateway` joins `public` (host-published on
`8082`, alongside the monolith's `8080`, since the monolith is still the
actual browser-facing service until #94); `market-data-service` and
`trading-service` join `internal` only, with no published ports at all.

**CI**: one matrix job (`.github/workflows/ci.yml`) covering all three
services, run only when `gateway/build.gradle` exists (mirroring the
existing `backend`/`frontend` detect-then-run pattern) - `./gradlew test` +
`jacocoTestReport` per service, no `integrationTest` step since none of the
three has an integration test yet. Purely additive: no change to branch
protection or the existing `backend`/`frontend` required-check names.

## Consequences

* `backend/` is completely unchanged by this issue - same `settings.gradle`,
  same `gradlew`, same CI job, same Jib image. The monolith keeps serving the
  full app exactly as before, which is #89's own explicit verification
  criterion.
* Three new independent Gradle builds (`gateway/`, `market-data-service/`,
  `trading-service/`) exist alongside `backend/` and `contracts/`, each with
  its own `gradlew` wrapper copied from `backend/`'s. `startUpDocker.sh` now
  builds four Jib images instead of one.
* Whoever picks up #90 (migrate price ticks) is the first to actually add a
  RabbitMQ dependency and pick a Spring Cloud Stream binder configuration -
  this ADR sets the shape (fanout exchange, `contracts.PriceTick` payload)
  but doesn't wire it.
* Whoever picks up #92/#93 (trade request/reply, session-start audit) is the
  first to actually implement the correlated async request/reply pattern
  described above, and the first to add R2DBC/Postgres access to
  `trading-service`.
* Until #94, there are two independent "backend-shaped" things running per
  `docker compose up`: the monolith (serving everything, real traffic) and
  the three skeletons (serving nothing but their own health checks). This is
  intentional strangler-fig overlap, not redundancy to clean up early.
* `contracts`' types intentionally duplicate shapes that already exist in
  the monolith's `com.sdp.common`/`com.sdp.trade` packages. This is accepted
  duplication, not drift risk: `contracts` is the wire shape the *new*
  services will use once a flow migrates, while the monolith's own types
  keep serving the monolith until that migration happens - they're not the
  same type used two ways, they're two intentionally separate definitions
  for two different lifetimes (retired monolith code vs. what replaces it).
