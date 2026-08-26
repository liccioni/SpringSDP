# 0028. Scoped trade delivery: correlated submitter ack + subscription-filtered blotter broadcast

Date: 2026-08-26

Status: Accepted

## Context

Issue #152: the gateway's in-process `EventBus` broadcast `TRADE_CREATED`
and `TRADE_REJECTED` to every connected WebSocket session unconditionally -
every trader saw every other trader's executions and rejections. This was
already a known, deliberately deferred gap noted in `docs/protocol.md`'s
"Broadcast semantics" section (`Trade`/`TradeRejected` carried no submitter
identity, even though `Session` (ADR 0017) existed to attribute one). It was
found while root-causing #128's redirect loop (unrelated to that bug's
actual cause) and closed deliberately once a second person independently
flagged it as surprising in design review.

A first implementation (PR #157, discarded) bolted a `submittedBy` identity
field onto the existing broadcast and filtered `EventBus` delivery by it.
That approach re-invented a mechanism the codebase already had: the
correlated request/reply pattern `TRADE_PENDING`/`TRADE_CANCELLED`/
`TRADE_HISTORY` already use, where a reply is delivered only to the
connection whose request triggered it, via that connection's own
per-connection sink rather than the shared, broadcast `EventBus`.

Reframing this as "just use correlated replies for everything" turned out to
be incomplete. Two genuinely different requirements exist:

1. The **submitting connection's own UI** depends on reliably receiving
   `TRADE_CREATED` for its own trade - `PriceGrid.tsx`'s pending-trade
   prompt (Buy/Sell disabled until resolved) and `ExecutionConfirmation.tsx`'s
   toast both wait for it, matched by trade id. This must work regardless of
   any subscription state.
2. The **trade blotter's live cross-user refresh** is legitimate, desired
   behavior, not the bug: `TradeBlotter.tsx` reacts to any `TRADE_CREATED` by
   refetching (AG Grid's Infinite Row Model re-applies whatever filter is
   already active server-side) - other traders watching the blotter should
   still see a colleague's trade land live. The bug was that this had no
   subscription concept at all, unlike `PRICE_TICK` (ADR 0013), not that it
   updated other sessions per se.

## Decision

Two independent delivery mechanisms, matching which of the two concerns
above a message serves:

**Correlated reply** (submitter-only, always): `CONFIRM_TRADE` now gets a
real reply on `trade-responses` (previously fire-and-forget) carrying the
resolved `Trade`, delivered via the submitting connection's own
`directMessages` sink in `SdpWebSocketHandler` - the same mechanism
`TRADE_PENDING`/`TRADE_CANCELLED`/`TRADE_HISTORY` already use.
`CREATE_TRADE`'s rejection reply now carries the real `TradeRejected`
payload (previously `null`) over the same channel. `TRADE_REJECTED` never
broadcasts at all: a rejected trade is never persisted, so there is nothing
for any other session to see.

**Subscription-filtered broadcast** (blotter viewers): a new
`BlotterSubscription` (`gateway/src/main/java/com/sdp/trade/`), structurally
identical in spirit to `SymbolSubscription` (ADR 0013) but a plain
subscribed/unsubscribed flag rather than per-symbol filtering - confirmed
`TradeBlotter.tsx` never inspects the trade payload, it just triggers a
refetch, so criteria-matching would be pure overhead. New client→server
message types `SUBSCRIBE_BLOTTER`/`UNSUBSCRIBE_BLOTTER`, sent by
`TradeBlotter.tsx` on mount/unmount. The pre-existing `trade-created`
RabbitMQ fanout exchange and the gateway's relay onto `EventBus` are kept
unchanged; only the *downstream* visibility changes - `SdpWebSocketHandler`'s
outbound event filter now also gates `Trade` events on
`session.blotterSubscription().isSubscribed()`. The `trade-rejected` fanout
exchange, its gateway consumer, and the gateway-local `TradeRejected`
`DomainEvent` class are deleted entirely - nothing publishes to it anymore.

When the submitting session is also blotter-subscribed (the normal case,
since the blotter is presumably always mounted), it receives `TRADE_CREATED`
twice for its own trade - once via each channel. This is accepted as
harmless: both `PriceGrid`'s id-matching handler and `TradeBlotter`'s
debounced refetch already tolerate a duplicate delivery safely.

## Consequences

Supersedes the reasoning in [ADR 0018](0018-two-step-trade-confirmation.md)
("`CONFIRM_TRADE` persists the trade and publishes the existing broadcast
`TRADE_CREATED`, unchanged from before") and in
[ADR 0022](0022-service-topology.md)'s "Update (issue #92)" section
("`CONFIRM_TRADE` gets none [no reply] at all... its only real effect, the
`TRADE_CREATED` broadcast, already exists via #91") for the specific claims
about `TRADE_CREATED`/`TRADE_REJECTED` delivery - both ADRs' other content
(the two-step execution workflow itself; the service topology and
correlated request/reply mechanism generally) still holds.

`docs/protocol.md`'s "Broadcast semantics" section is rewritten: it no
longer lists `TRADE_CREATED` as "the odd one out... still broadcast to
everyone." `PRICE_TICK` and the blotter-refresh half of `TRADE_CREATED` now
follow the identical pattern - broadcast, then filtered by an explicit,
opt-in, per-connection subscription - rather than one broadcasting
unconditionally and the other not existing at all.

`CLAUDE.md`'s MVP 1.2 description ("scopes that delivery to the submitting
session only") is a slight simplification of what actually shipped -
`TRADE_CREATED` also reaches blotter subscribers, deliberately. Worth a
one-line correction when the milestone formally closes.

This is the same kind of design mistake the discarded PR #157 made, one
level up: jumping to a mechanism (either "add an identity filter" or "make
everything correlated") before confirming what the actual delivery
requirements were. The submitter-ack and blotter-refresh needs are
genuinely different problems and only became clear by reading how the
frontend actually consumes `TRADE_CREATED` today.
