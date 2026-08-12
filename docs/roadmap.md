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

## MVP 0.9 – Role enforcement & blotter usability 🚧 In progress

Closes a real gap found while working through the platform end to end: the `viewer` Keycloak role (defined correctly since MVP 0.6, with a demo user assigned it) was never actually enforced anywhere - no code path, frontend or backend, checked it. Also addresses the trade blotter's lack of filtering/pagination before trade volume makes it unwieldy.

* Keycloak `trader`/`viewer` realm roles enforced on trade creation, authoritatively in `trading-service` ([#117](https://github.com/liccioni/SpringSDP/issues/117))
* filtering and pagination added to the trade blotter, client-side over AG Grid Community's existing capabilities ([#118](https://github.com/liccioni/SpringSDP/issues/118))

⸻

## What's next

MVP 0.1 through 0.8 are done; MVP 0.9 is in progress (see above). Backlog issues [#78](https://github.com/liccioni/SpringSDP/issues/78) (simulated execution venues), [#103](https://github.com/liccioni/SpringSDP/issues/103) (reverse proxy), [#119](https://github.com/liccioni/SpringSDP/issues/119) (swappable messaging transport), [#120](https://github.com/liccioni/SpringSDP/issues/120) (Kubernetes manifests), [#121](https://github.com/liccioni/SpringSDP/issues/121) (cloud deployment options), and [#122](https://github.com/liccioni/SpringSDP/issues/122) (real market data providers) remain open, deliberately unscheduled per CLAUDE.md's Core philosophy - no concrete need for any of them has arrived yet. A multi-cloud Terraform setup with a CLI installer to pick the provider was also considered and deliberately dropped rather than filed - it's a large amount of infrastructure for a single-maintainer project with no cloud deployment yet at all; #121 above covers picking one direction with a cost rationale instead.
