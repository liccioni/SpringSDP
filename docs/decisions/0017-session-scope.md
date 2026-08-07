# 0017. Session scope: tied to the WebSocket connection, not surviving reconnects

Date: 2026-08-07

Status: Accepted

## Context

[ADR 0016](0016-authentication.md) scoped issue #24 narrowly to proving identity, deliberately deferring "a richer session concept" to issue #25. Issue #25's body: "Introduce session management tied to authenticated users, replacing the anonymous connection model used through MVP 0.4." Today, `SdpWebSocketHandler` resolves `authService.username(token)` only to decide whether to close the connection — the username is discarded immediately afterward, and nothing downstream (subscriptions, trade creation, trade history replies) knows which authenticated user owns a given connection. `docs/protocol.md`'s Broadcast semantics section already flags the gap this leaves: a `TRADE_REJECTED` is "only meaningful to the session that submitted the trade," and whether it becomes targeted delivery "is a decision to make [once sessions exist], not assumed here." Issues #26 ("Per-session market data subscriptions"), #27 ("Execution workflows"), and #28 ("Audit events") all build on whatever a Session turns out to be — #26 in particular scopes subscriptions "per session rather than per raw connection," a distinction that only matters if a session is a genuinely separate concept from the connection carrying it.

The real fork, flagged to @liccioni up front per the same standard as ADR 0011/0013/0015/0016: does a Session's lifetime match its WebSocket connection exactly (created on authenticate, ends on close, no reconnect continuity), or can a session persist across a disconnect/reconnect — potentially with multiple concurrent connections attached to one session? The latter is a materially bigger lift: a session registry, an expiry/cleanup policy, and very likely a new protocol mechanism to resume into an existing session — none of which currently exists or is asked for by issues #26-28.

## Decision

* **A `Session` is 1:1 with its WebSocket connection.** `SdpWebSocketHandler` creates one on successful authentication, reusing the underlying `WebSocketSession`'s own `id` rather than minting a redundant UUID, paired with the `username` resolved from the token. It lives only for that `handle()` invocation; reconnecting simply creates a new `Session` with the same username but a different id. Nothing survives a reconnect — the existing protocol.md note that subscriptions "don't survive a reconnect" now holds of the Session concept itself, not just of subscription state.
* New `com.sdp.session` package, `record Session(String id, String username)`, following the project's existing per-domain package structure. Not a Spring bean — per-connection instantiated state, the same shape as the already-planned `SymbolSubscription` (issue #69) and the existing `MarketDataService.symbols()`/`TradeService.blotter()` precedent of plain in-memory state over singleton machinery.
* The immediately visible use: the `HELLO` envelope's payload is now a personalized greeting ("Hello, `<username>`!") instead of a static string — the last piece of the "anonymous connection model" this issue is titled to retire.
* Deliberately out of scope for this issue: attributing `Trade`/`TradeRejected` to a submitting session, and moving subscription state onto `Session` — tracked separately by #26 and #69 respectively. Bundling either in here would blur this issue's actual scope, which is establishing the Session concept itself.

## Consequences

* `SdpWebSocketHandler` now threads a `Session` value through `handle()` (used for the HELLO greeting only, today) that #26/#27/#28 can extend to own subscription state, attribute trades, and emit audit events — without another identity redesign.
* A user with two open connections (two browser tabs, or a reconnect after a dropped connection) now has two independent Sessions, each with its own id, sharing the same username. Nothing in the app currently needs to recognize these as "the same user," so this isn't a regression — but a future feature that does (e.g. "log out all my sessions") will need to key off `username`, not `id`, since there's no registry to enumerate sessions by user.
* No session expiry/cleanup logic is needed: a Session's lifetime is exactly its connection's lifetime, garbage-collected the same way as today's per-connection locals (`subscribedSymbols`, `directMessages`) — there's nothing new to leak or need a TTL for.
* If a future milestone genuinely needs reconnect continuity (resuming a dropped WebSocket without losing subscription state), that's a bigger redesign than this issue — a session registry, an expiry policy, and a protocol-level resume mechanism — and should get its own ADR when a real requirement demands it, rather than being speculatively built now.
