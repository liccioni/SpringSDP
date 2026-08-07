# Roadmap

⸻

## MVP 0.1 – Streaming skeleton ✅ Done

See [retro 0001](retros/0001-mvp-0.1.md) for what shipped, what was verified, and what was learned.

A user can:

1. Open the application
2. See live FX price ticks
3. Double-click a price
4. Create a trade
5. See the trade appear in the trade blotter

That is the entire MVP.

Scope:

* WebSocket connection
* fake FX price generator
* price grid
* trade creation
* trade blotter

⸻

## MVP 0.2 – Trading flow ✅ Done

See [retro 0002](retros/0002-mvp-0.2.md) for what shipped, what was verified, and what was learned.

* quantity entry
* buy/sell actions
* execution confirmations
* trade validation

⸻

## MVP 0.3 – Reactive architecture ✅ Done

See [retro 0003](retros/0003-mvp-0.3.md) for what shipped, what was verified, and what was learned.

* event bus
* reactive services
* subscription management
* improved state handling

⸻

## MVP 0.4 – Persistence ✅ Done

See [retro 0004](retros/0004-mvp-0.4.md) for what shipped, what was verified, and what was learned.

* PostgreSQL
* R2DBC
* trade history
* application restart recovery

⸻

## MVP 0.5 – Dealer platform foundation ✅ Done

See [retro 0005](retros/0005-mvp-0.5.md) for what shipped, what was verified, and what was learned.

* authentication
* sessions
* market data subscriptions
* execution workflows
* audit events

⸻

## MVP 0.6 – Identity & session

Reverses two of this project's original "start simple" constraints (no framework auth, no Redis) the same way MVP 0.5 lifted "no authentication" — see CLAUDE.md's Core philosophy section.

* Spring Security + Keycloak (authorization code grant), a realm with `trader`/`viewer` roles, replacing the hand-rolled auth from ADR 0016
* Spring Session backed by Redis
* config externalized via env vars

⸻

## MVP 0.7 – Service topology & messaging

Reverses "no microservices." Splits the monolith into three services, connected via RabbitMQ and Spring Cloud Stream binders, migrated incrementally (strangler-fig, not a big-bang cutover) so the app stays runnable at every stage.

* a pure WebSocket gateway
* a market data service
* a backend/trading service with database access (trade service, order service, etc.)

⸻

## What's next

MVP 0.1 through 0.5 are done. MVP 0.6 and 0.7 are planned (12 issues across both, see the [MVP 0.6](https://github.com/liccioni/SpringSDP/milestone/6) and [MVP 0.7](https://github.com/liccioni/SpringSDP/milestone/7) GitHub milestones) but not yet started. Backlog issues [#78](https://github.com/liccioni/SpringSDP/issues/78) (simulated execution venues) and [#79](https://github.com/liccioni/SpringSDP/issues/79) (cancel pending trades on disconnect) are intended to land on the new backend/trading service once MVP 0.7 is done.
