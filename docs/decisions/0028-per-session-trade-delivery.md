# 0028. Per-session trade delivery

Date: 2026-08-26

Status: Accepted

## Context

Issue #152: found while root-causing #128's redirect loop (unrelated to that
bug's actual cause). The gateway's `EventBus` is one shared in-process bus
that every connected session's `SdpWebSocketHandler.handle()` subscribes to.
`PRICE_TICK` is already scoped per-connection via `SymbolSubscription`
(ADR 0013), but `TRADE_CREATED`/`TRADE_REJECTED` were broadcast to every
connected session regardless of who submitted the trade - a gap
`docs/protocol.md`'s Broadcast semantics section already flagged as
deliberately deferred (`Trade`/`TradeRejected` carried no submitter identity,
even though `Session` (ADR 0017) has existed to attribute one to since
MVP 0.6). Every trader currently sees every other trader's executions and
rejections, which a second, independent design review flagged as surprising
- worth closing deliberately rather than leaving indefinitely deferred.

`Session.username` is the only identity concept in play here: ADR 0017
deferred trade attribution explicitly, and ADR 0019 chose to record identity
via a separate `AuditService.record(sessionId, username, ...)` call rather
than embedding it in `Trade`/`TradeRejected`, leaving that decision open for
whoever picked this up. `TradeCommand.submittedBy` already carries this
identity across the RabbitMQ boundary on every `CREATE_TRADE`/`CONFIRM_TRADE`
command - trading-service's `TradeService` already has it in scope at both
points it builds a `Trade` (on `CONFIRM_TRADE`) or a `TradeRejected` (on an
invalid `CREATE_TRADE`), it was simply never threaded into the outgoing
message.

## Decision

* **Add `submittedBy` to `contracts.Trade` and `contracts.TradeRejected`**
  (the RabbitMQ wire types), populated from the originating
  `TradeCommand.submittedBy()` at both publish sites in trading-service's
  `TradeService`. Threaded through the gateway-local `com.sdp.common.Trade`
  and `com.sdp.trade.TradeRejected` `DomainEvent` wrapper types too, since
  the RabbitMQ contract and the `EventBus` payload are separate types.
* **Filter `EventBus` delivery by submitter, mirroring `SymbolSubscription`.**
  A new marker interface, `com.sdp.eventbus.Attributable` (`submittedBy()`),
  is implemented by both gateway-local trade event types.
  `SdpWebSocketHandler` adds a second filter alongside
  `session.subscriptions()::isVisible`: an `Attributable` event is only
  delivered to the session whose `username` matches `submittedBy()`; every
  other event (`PRICE_TICK` included) is unaffected. `TRADE_CREATED` and
  `TRADE_REJECTED` are therefore no longer broadcast - they become targeted
  delivery, same in kind as `PRICE_TICK`'s subscription filtering but keyed
  on identity instead of a symbol.
* **Wire-only identity - not persisted on the `trades` table.** `submittedBy`
  is not added to trading-service's persisted `Trade` entity or `schema.sql`.
  `TradeHistoryQueryService`'s `toTrade(Row)` maps it as `null`, since
  history rows are already a targeted, non-`EventBus` reply and never
  filtered by submitter. This matches ADR 0019's existing precedent
  (`audit_events` already durably records `username` separately) and keeps
  this issue's scope to delivery, not trade history. If trade history later
  needs to show or filter by trader, that's a clean, separately-scoped
  follow-up (a schema migration plus `TradeHistoryQuery`/blotter changes),
  not bundled into this one.

## Consequences

* `Trade`/`TradeRejected` gained a field in three places (the RabbitMQ
  contract type and both gateway-local `EventBus` payload types) that stays
  `null` on the trade-history read path - a record reused across two
  purposes (live delivery and history rows) with a field meaningful only in
  one of them. Accepted as the minimal-scope option; a cleaner split would
  need two distinct wire types for what's currently one `contracts.Trade`.
* `SdpWebSocketHandlerIT`'s existing two-connection broadcast tests
  (`tradeCreatedFromTheBackendTradingServiceReachesEveryConnectedSession`/
  `tradeRejectedFrom...`) were rewritten to prove the opposite: two sessions
  under different usernames (`trader1`/`trader2`), asserting the non-
  submitting session never receives the event (bounded by a short timeout
  rather than blocking indefinitely on a message that, by design, never
  arrives).
* A future `Attributable` event beyond `Trade`/`TradeRejected` gets the same
  submitter-scoped delivery for free by implementing the interface - no
  further `SdpWebSocketHandler` changes needed, the same way a new
  price-like event would reuse `SymbolSubscription`'s pattern.
