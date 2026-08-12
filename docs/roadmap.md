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

## MVP 0.6 – Identity & session ✅ Done

See [retro 0006](retros/0006-mvp-0.6.md) for what shipped, what was verified, and what was learned. Reversed two of this project's original "start simple" constraints (no framework auth, no Redis) the same way MVP 0.5 lifted "no authentication" — see CLAUDE.md's Core philosophy section.

* Spring Security + Keycloak (authorization code grant), a realm with `trader`/`viewer` roles, replacing the hand-rolled auth from ADR 0016
* Spring Session backed by Redis
* config externalized via env vars

⸻

## MVP 0.7 – Service topology & messaging ✅ Done

Reversed "no microservices." Split the monolith into three services, connected via RabbitMQ and Spring Cloud Stream binders, migrated incrementally (strangler-fig, not a big-bang cutover) so the app stayed runnable at every stage — see CLAUDE.md's Core philosophy section.

* a pure WebSocket gateway (`gateway/`), absorbing the monolith's OAuth2 login, session, and WebSocket-handling code as its final step
* a market data service (`market-data-service/`)
* a backend/trading service with database access (`trading-service/`) - trade domain logic and the audit trail
* the original monolithic `backend/` module deleted entirely once every flow had migrated

⸻

## MVP 0.8 – Session lifecycle & cleanup ✅ Done

See [retro 0008](retros/0008-mvp-0.8.md) for what shipped, what was verified, and what was learned. Closed two gaps left over from earlier milestones: no logout flow existed since Keycloak login shipped in MVP 0.6, and pending trades submitted but never confirmed or cancelled leaked forever once their connection closed (flagged and deliberately deferred during MVP 0.5's execution-workflow work, #27).

* a real logout flow (OIDC RP-Initiated Logout), ending both the app's own Spring Session and Keycloak's SSO session (#102, [ADR 0023](decisions/0023-oidc-rp-initiated-logout.md))
* pending trades cancelled automatically when their owning session's WebSocket connection closes (#79, [ADR 0024](decisions/0024-cancel-pending-trades-on-disconnect.md))

⸻

## What's next

MVP 0.1 through 0.8 are done. No milestone is currently scheduled. Backlog issues [#78](https://github.com/liccioni/SpringSDP/issues/78) (simulated execution venues) and [#103](https://github.com/liccioni/SpringSDP/issues/103) (reverse proxy) remain open, deliberately unscheduled per CLAUDE.md's Core philosophy - no concrete need for either has arrived yet.
