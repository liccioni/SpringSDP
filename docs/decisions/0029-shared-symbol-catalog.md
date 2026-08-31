# 0029. Symbol catalog as a shared compile-time constant, not a runtime broadcast

Date: 2026-08-31

Status: Accepted

## Context

Issue #159: the tradable currency-pair catalog was three independently
hardcoded copies - `market-data-service`'s `BASE_PRICES` map (3 pairs, also
the tick-generation seed), `trading-service`'s `KNOWN_SYMBOLS` validation
set (the same 3 pairs), and the frontend's `PriceGrid.tsx` `KNOWN_SYMBOLS`
constant (kept in sync by hand, per its own comment) - a duplication risk
[ADR 0013](0013-subscription-default-nothing-until-subscribed.md) already
flagged and explicitly deferred. The frontend also auto-subscribed to every
known symbol on mount, with no concept of a default subset.

The roadmap's framing of this issue described the target state as "a single
source of truth in market-data-service" with "a symbol-discovery mechanism"
the gateway exposes to the frontend, phrased as if market-data-service
should be the *runtime* owner of the catalog, queried or broadcast from
there. Two ways to satisfy "one source of truth, no new backend infra
needed" (the issue's own constraint) were considered:

1. **Runtime broadcast**: `market-data-service` publishes a `SYMBOL_CATALOG`
   event on a new RabbitMQ fanout exchange (mirroring `price-ticks`) at
   startup; the gateway subscribes, caches it, and relays it to the
   frontend. `trading-service` would need the same broadcast (or its own
   copy) for validation.
2. **Shared compile-time constant**: a new type in `contracts/`, the module
   every one of `gateway`, `market-data-service`, and `trading-service`
   already depends on, holding the catalog as plain Java constants.

Option 1 matches the roadmap's literal wording more closely, but reintroduces
exactly the failure mode already documented in
[testing.md](../testing.md)'s gotchas: a `@PostConstruct`/eager subscriber
publishing before Spring Cloud Stream's messaging channels are marked
"running" is silently dropped, not an error (this bit `market-data-service`'s
own tick generator once already, fixed by moving to
`ApplicationReadyEvent`). A one-shot startup broadcast the gateway must catch
and cache is exactly that shape of bug waiting to happen again, for a catalog
that is, in practice, static business data - not something that needs to
change without a redeploy.

## Decision

Add `com.sdp.contracts.SymbolCatalog` - a record holding `ALL_SYMBOLS` (14
pairs) and `MAJORS` (the 6-pair default-subscribed subset) as public static
constants, plus a `defaultCatalog()` factory returning both as one payload.

* `market-data-service`'s `MarketDataService` keeps its own `BASE_PRICES` map
  (per-symbol seed price is genuinely this service's own concern, not part
  of "the list of tradable symbols"), but a static assertion now fails fast
  if its key set ever drifts from `SymbolCatalog.ALL_SYMBOLS`.
* `trading-service`'s `TradeService.KNOWN_SYMBOLS` is now
  `Set.copyOf(SymbolCatalog.ALL_SYMBOLS)` instead of its own literal.
* `gateway`'s `SdpWebSocketHandler` sends a new `SYMBOLS` envelope
  (`SymbolCatalog.defaultCatalog()`) once, immediately after `HELLO` - no
  RabbitMQ round trip, since the gateway already depends on `contracts` at
  compile time like the other two services.
* The frontend's `PriceGrid.tsx` no longer hardcodes any symbol list: on
  receiving `SYMBOLS`, it sends `SUBSCRIBE` for each symbol in `majors` only
  (not the full catalog), and `UNSUBSCRIBE`s the same set on unmount. This
  is the "majors" default this issue asked for - a fixed, config-in-code
  subset, not persisted across a reconnect, matching `Session`'s existing
  no-reconnect-continuity model ([ADR 0017](0017-session-scope.md)).

This also incidentally fixes `gateway`'s own fourth hardcoded copy - its
`SdpWebSocketHandlerIT` test double, `FakeTradingService`, reimplemented
`trading-service`'s validation set independently for integration tests - not
called out in the issue body, but flagged as a third real list in `CLAUDE.md`.

## Consequences

One real source of truth for *which symbols are tradable* (`SymbolCatalog`
in `contracts/`), reached by every consumer at compile time with zero new
messaging infrastructure, zero new startup-ordering risk, and a single Java
file to edit when the catalog changes (a redeploy of all three services,
which - per [ADR 0022](0022-service-topology.md) - already happens together
in practice). This does mean the catalog is no longer runtime-configurable
without a rebuild; that trade-off matches this project's "add infrastructure
only when a concrete need justifies it" philosophy (`CLAUDE.md`) - nothing
today calls for changing the tradable pairs without a deploy.

`docs/protocol.md` gets a new `SYMBOLS` entry under "Server → client",
documented the same way `HELLO` is: sent once, immediately after connecting,
never broadcast, never subscription-filtered - it isn't part of the shared
`EventBus`/subscription machinery `PRICE_TICK` and the blotter's
`TRADE_CREATED` use, since every connection receives an identical payload
regardless of any subscription state.

The frontend no longer offers a way to see or subscribe to the 8 non-major
pairs the catalog adds (`NZD/USD`, `EUR/GBP`, `EUR/JPY`, `GBP/JPY`,
`EUR/CHF`, `AUD/JPY`, `USD/CNH`, `USD/MXN`) - they exist end-to-end
(tickable, tradable, validated) but nothing in the UI subscribes to them.
This is a deliberate scope cut, not an oversight: the issue and the
roadmap's MVP 1.3 description both stop at "a fixed, config-driven
default-subscribed majors subset," not a symbol picker. A future issue can
add one against the `symbols` half of the same `SYMBOLS` payload, already
sent, without any backend change.
