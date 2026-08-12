# 0024. Cancel pending trades on WebSocket disconnect

Date: 2026-08-12

Status: Accepted

## Context

ADR 0018 flagged connection-close cleanup as a real alternative to "no
automatic expiry" for `PendingTrade`, deliberately deferred it, and captured
it as backlog issue #79 - at the time, an orphaned pending trade was inert
in-memory state with no established cost. ADR 0019 later reused that same
backlog note as its own reason not to add a `SESSION_ENDED` audit event or
any WebSocket-disconnect hook: "nothing else in the app currently hooks
connection-close for any purpose." MVP 0.8 (issue #79) turns that theoretical
concern into a scheduled milestone need, closing the gap alongside the
logout flow (ADR 0023).

The pending-trade state itself lives entirely in `trading-service`
(`TradeService.pendingTrades`, keyed by trade id, ADR 0018/0022's service
split), with no session or connection identity attached - only a
`submittedBy` username travels one-way in `TradeCommand`. The gateway's own
`Session` (ADR 0017) is 1:1 with the WebSocket connection but, before this
issue, tracked only market-data subscriptions - nothing about which pending
trades a connection is holding.

## Decision

* **`Session` gains a second piece of owned per-connection state**: a new
  `PendingTradeIds` (mirroring `SymbolSubscription`'s shape - a plain
  `ConcurrentHashMap`-backed set, not a Spring bean). `SdpWebSocketHandler`
  adds a trade's id on a successful `TRADE_PENDING` reply and removes it on
  `CONFIRM_TRADE`/`CANCEL_TRADE`, regardless of whether that resolution
  round-trip actually found a still-pending trade - matching the existing
  "unknown or already-resolved id is a silent no-op" contract.
* **The gateway's WebSocket handler adds its first connection-close hook**:
  `webSocketSession.send(outbound).and(inbound)` gets a `doFinally` that
  fires on every terminal signal (complete, cancel, error) and calls
  `tradeService.cancelTrade(id, session)` for whatever ids are still left in
  the session's `PendingTradeIds`. This reuses the exact `CANCEL_TRADE`
  request/reply path a client-initiated cancel already takes - trading-
  service needs no changes, since it already treats cancelling an unknown or
  already-resolved id as a no-op.
* **Fire-and-forget, not gated on success.** Each `cancelTrade(...)` call is
  subscribed directly rather than awaited; the connection is already gone by
  the time this runs, so there's nowhere left to report a failure to, and no
  reply is ever sent back over the (closed) socket anyway.
* **No new audit event.** This reuses `TradeService.handleCancelTrade`'s
  existing `TRADE_CANCELLED` audit record - there is no separate "cancelled
  because the connection dropped" distinction in the audit trail, since the
  effect (a pending trade being cancelled) is identical either way.

This corrects ADR 0019's "nothing else in the app currently hooks
connection-close for any purpose" - that's no longer true as of this ADR,
though ADR 0019's own conclusion (no `SESSION_ENDED` audit event) still
holds on its own merits, unrelated to pending-trade cleanup.

## Consequences

* `Session` is no longer just an identity+subscription holder; a second
  concern (pending-trade tracking) now piggybacks on its 1:1-with-connection
  lifetime. Both concerns follow the same ownership shape
  (`ConcurrentHashMap`-backed mutable state, discarded when the connection's
  `Mono<Void>` completes), so this doesn't complicate `Session`'s existing
  no-expiry-logic-needed reasoning (ADR 0017).
* A client that disconnects mid-trade (browser closed, network drop) now
  gets its dangling `PendingTrade` cleaned up automatically instead of it
  persisting in `trading-service`'s in-memory map forever - closing the one
  concrete leak MVP 0.5's execution-workflow retro (issue #27) flagged and
  deferred.
* `docs/protocol.md`'s claim that "ADR 0019's decision not to record a
  `SESSION_ENDED` event or hook WebSocket disconnect still holds" needs a
  narrower rewording: the audit-event half still holds, the
  never-hooks-disconnect half no longer does.
