# 0016. Authentication: HTTP login issuing a token, hand-rolled, config-based users

Date: 2026-08-07

Status: Superseded by [0020](0020-keycloak-oauth2-redis-session.md)

## Context

CLAUDE.md's "no authentication" constraint was always scoped to "the first implementation" — MVP 0.5 ("dealer platform foundation") is where it's meant to lift, and issue #24 explicitly asked for the scope/approach to be decided in an ADR before implementation rather than invented ad hoc. Three real forks existed, each flagged to @liccioni up front per the same standard as prior domain-shaping decisions ([ADR 0011](0011-use-bigdecimal-for-money.md), [ADR 0013](0013-subscription-default-nothing-until-subscribed.md), [ADR 0015](0015-trade-history-db-backed-schema-sql.md)):

1. **Where authentication happens, protocol-wise.** [ADR 0010](0010-event-driven-protocol.md) established that new capabilities are added as new envelope types over the single existing WebSocket endpoint, never a new endpoint — a `LOGIN` envelope exchanged after connecting would have followed that precedent exactly. The alternative, an HTTP login endpoint issuing a token the browser passes when opening the WebSocket, would be the app's first-ever REST endpoint — a real architectural departure.
2. **Where credentials come from** — a small hardcoded/config-based set of demo users, or a `users` table in Postgres.
3. **Hand-rolled auth, or adopting `spring-security-webflux`** — [ADR 0008](0008-use-raw-websockets.md) already chose to hand-roll WebSocket handling rather than adopt a framework, "for a project whose stated goal is understanding every layer"; the same question applies to authentication.

## Decision

* **HTTP `POST /login`, not a WS envelope.** Accepts `{username, password}`, and on success returns `{token}`. This is a deliberate, acknowledged divergence from ADR 0010's "never a new endpoint" rule: credentials shouldn't ride over an otherwise-unauthenticated WebSocket connection, and a short-lived HTTP request/response is a better fit for a login exchange than a stateful protocol built for streaming events. The browser passes the returned token as a query parameter when opening the WebSocket (`ws://.../ws?token=...`), since browsers cannot set custom headers on a WebSocket handshake. `SdpWebSocketHandler` validates the token before letting the connection proceed, closing connections with a missing or invalid one.
* **Config-based demo users, not a database table.** A small fixed set of users defined in `application.yml`, with BCrypt-hashed passwords. Issue #24 asks for authenticating connections, not user management or self-registration — a `users` table would be persistence with no CRUD feature to justify it yet.
* **Hand-rolled, not `spring-security-webflux`.** A plain `AuthService` checks credentials and issues an opaque random token (a UUID) stored server-side in an in-memory token-to-username map — no filter chain, `SecurityContext`, or method-level security machinery. Password hashing itself is not hand-rolled: `spring-security-crypto` (the standalone artifact, not the full `spring-boot-starter-security` framework) provides `BCryptPasswordEncoder` so hashing/verification uses a real, audited implementation rather than something invented for this project.
* **Scoped narrowly to proving identity**, not building out a richer session concept. Issue #25 ("Sessions") is a separate, later issue meant to layer session tracking on top of the identity this establishes. Tokens have no expiry/TTL for now — start simple, revisit if a real need appears.

## Consequences

* This is the app's first HTTP/REST endpoint. The frontend (a different origin/port in local dev) needs CORS configured on the backend to call it — a requirement that didn't exist before, since WebSocket connections aren't subject to the same-origin/CORS model HTTP `fetch` is.
* The frontend needs a new login UI gating the rest of the app, and `socket.ts`'s `connect()` needs to append the token to the WebSocket URL — the app cannot be used at all without logging in first, a real change to "keep the application runnable at every stage" (it's still runnable, just gated).
* The in-memory token store means every session is invalidated on a backend restart, same class of tradeoff as the in-memory `EventBus` before MVP 0.4 — consistent with nothing about auth being durable yet, since durability wasn't asked for.
* A real production system would need token expiry/revocation and a persisted, manageable user store; both are deferred, not solved partially here.
* `SdpWebSocketHandler` gains a dependency on `AuthService` to validate tokens — the first time the WebSocket layer has needed to check anything before proceeding with `handle()`, beyond envelope dispatch.
