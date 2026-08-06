# 0013. Market data subscriptions default to nothing until SUBSCRIBEd

Date: 2026-08-06

Status: Accepted

## Context

Issue #19 asked for per-connection subscribe/unsubscribe to specific symbols' price streams, "rather than receiving a broadcast of everything" — but left open what a connection receives *before* it sends any `SUBSCRIBE`. Two credible defaults existed, with a real tradeoff between them (the same class of decision as [ADR 0011](0011-use-bigdecimal-for-money.md)'s `double`-vs-`BigDecimal` call, so it was flagged to @liccioni before implementing rather than picked silently):

* **Nothing until subscribed.** Matches the issue's wording literally. But the existing frontend never sent a `SUBSCRIBE` message (it didn't exist yet), so adopting this as the default would silently break `PriceGrid` — the price grid would render nothing, regressing MVP 0.1's "see live FX price ticks" requirement — unless the frontend was updated in the same change.
* **Everything, narrowed by `SUBSCRIBE`/`UNSUBSCRIBE`.** Backend-only, zero frontend risk, smaller PR. But since the frontend would never exercise it, the feature would only be proven by new integration tests, not by the actual running app.

## Decision

Default to **nothing until subscribed**, and update the frontend in the same PR so the app stays runnable:

* Every WebSocket connection starts with no symbol subscriptions. `SUBSCRIBE` and `UNSUBSCRIBE` (payload `{ "symbol": "EUR/USD" }`) add/remove entries in a `Set<String>` created fresh inside `SdpWebSocketHandler.handle()` — connection-local state, not a field on the shared handler bean, and deliberately not a session registry. Session identity/addressing is still meant to land in MVP 0.5, per [protocol.md](../protocol.md); this doesn't pull that forward.
* Only `PRICE_TICK` is filtered by this. `TRADE_CREATED` and `TRADE_REJECTED` are unaffected and stay broadcast to every session.
* `PriceGrid` now sends `SUBSCRIBE` for a hardcoded `KNOWN_SYMBOLS` list once the WebSocket's `open` event fires (`socket.ts` gained an optional `onOpen` hook for this), so the grid populates exactly as before from the user's perspective.

## Consequences

* The wire protocol gains two new client → server message types, `SUBSCRIBE`/`UNSUBSCRIBE`, documented in [protocol.md](../protocol.md).
* `KNOWN_SYMBOLS` in the frontend duplicates `MarketDataService`'s tradable symbol set on the backend, with no symbol-discovery message to keep them in sync. If the backend's tradable symbols ever change, the frontend constant has to be updated by hand or the grid will silently miss a symbol. Flagged in a comment at the point of duplication; adding a symbol-discovery message was considered out of scope for this issue.
* The feature is exercised by the real running app, not just integration tests, since `PriceGrid` now depends on `SUBSCRIBE` actually working for its own price grid to populate — verified live over both documented run paths (Docker Compose and local dev servers) during the MVP 0.3 retro.
* [ADR 0012](0012-in-process-event-bus.md)'s stated consequence that "every event, including `TRADE_REJECTED`, is still broadcast to every connected session" is now only true for `TRADE_CREATED`/`TRADE_REJECTED` — `PRICE_TICK` is the exception this ADR introduces. ADR 0012 has been updated to point here.
