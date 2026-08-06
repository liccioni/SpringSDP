# 0012. Introduce an in-process event bus over Reactor Sinks

Date: 2026-08-06

Status: Accepted

## Context

Before this decision, `SdpWebSocketHandler` knew about both domain services directly and called three separate methods — `marketDataService.priceTicks()`, `tradeService.tradeCreated()`, `tradeService.tradeRejected()` — one per event type, each mapped to its own hardcoded envelope `type` string. [ADR 0010](0010-event-driven-protocol.md) already decided the *wire* protocol is event-driven and envelope-shaped, and made a forward-looking claim that this "allows future integration with Redis Pub/Sub or Kafka with minimal protocol changes" — but nothing enforced that in-process, and issue #17 asked directly for an internal event bus to decouple `MarketDataService`/`TradeService` from the WebSocket layer as groundwork for that future move.

A related latent bug existed alongside this: `MarketDataService.priceTicks()` was a cold `Flux` — every WebSocket connection independently subscribed to it, so N connected clients would each run their own `Flux.interval(1s)` tick generator, all mutating the same shared `midPrices` map concurrently. With multiple clients, the price random walk would silently run N times too fast. Never triggered in practice (normally one browser tab), but real, and directly caused by the lack of a single shared publish point — exactly what an event bus fixes as a side effect of decoupling.

## Decision

Introduce an in-process, Reactor-`Sinks`-backed event bus rather than keeping N per-service `Sinks` and having the WebSocket handler subscribe to each by name, or building a heavier typed multi-bus abstraction:

- `DomainEvent` (`com.sdp.eventbus`): a marker interface with one method, `eventType(): String`, giving the WebSocket envelope's `type` string for that event. `PriceTick`, `Trade`, and `TradeRejected` implement it.
- `EventBus` (`com.sdp.eventbus`, `@Component`): wraps one shared `Sinks.Many<DomainEvent>` multicast sink (`autoCancel=false`, the same pattern the pre-existing per-service sinks already used) and exposes `publish(DomainEvent)` / `events(): Flux<DomainEvent>`.
- `MarketDataService` takes `EventBus` in its constructor but starts publishing from a `@PostConstruct` method, not the constructor itself — so a plain `new MarketDataService(eventBus)` in a unit test stays side-effect-free, and the existing `StepVerifier.withVirtualTime` tests calling the now-package-private `priceTicks()` directly are unaffected. In the running app, this also means exactly one shared hot publish loop instead of one cold subscription per WebSocket connection, fixing the N-clients-drift-N-times-faster bug above.
- `TradeService` takes `EventBus` too and calls `eventBus.publish(...)` directly, replacing its own `tradeCreated`/`tradeRejected` sinks and their accessor methods.
- `SdpWebSocketHandler` now depends on `EventBus` and `TradeService` only — not `MarketDataService` — and maps every event generically via `new Envelope(event.eventType(), event)` instead of three separate hardcoded mappings.

## Consequences

- Adding a future domain event (e.g. from #18–#20's reactive-services/subscription-management work) means implementing `DomainEvent` and calling `eventBus.publish(...)` — `SdpWebSocketHandler` needs no change, since it already dispatches every event generically.
- `DomainEvent.eventType()` is a deliberate coupling: the domain model (`com.sdp.common`, `com.sdp.trade`) now knows its own wire-protocol envelope type string, so the WebSocket layer can stay generic. The alternative — an `instanceof` chain or a lookup map living in the handler — would keep the domain model wire-protocol-agnostic, but risks silently falling through when a new event type is added and the chain isn't updated; this makes that mistake a compile error instead (a new `DomainEvent` implementation must provide `eventType()`).
- There is exactly one shared publish point per event category now (one `EventBus` instance, one underlying sink), which is what fixed the multi-client price-drift bug — but it also means every event, including `TRADE_REJECTED`, is still broadcast to every connected session, same as before this change. That's [already flagged as an open question in protocol.md](../protocol.md), unrelated to and not resolved by this ADR. **Update (see [ADR 0013](0013-subscription-default-nothing-until-subscribed.md)):** this is no longer true for `PRICE_TICK`, which issue #19 made connection-local and subscription-filtered. `TRADE_CREATED`/`TRADE_REJECTED` are still broadcast to all, as described here.
- The publish/subscribe shape (`publish`/`events()`) is what would carry over to a future external broker, per ADR 0010's Kafka/Redis claim — swapping `EventBus`'s internal `Sinks.Many` for a Kafka producer/consumer or a Redis Pub/Sub client would not require changing `MarketDataService`, `TradeService`, or `SdpWebSocketHandler`'s call sites, only `EventBus`'s implementation.
- One more manual, duplicated step exists per new event type: implementing `DomainEvent.eventType()`, in addition to the manual additions ADR 0010 already noted (protocol.md, the frontend's TypeScript types).
