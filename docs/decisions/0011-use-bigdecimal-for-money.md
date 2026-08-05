# 0011. Use BigDecimal, not double, for money fields

Date: 2026-08-05

Status: Accepted

## Context

The initial domain model (issue #3) first used `double` for `PriceTick`'s `bid`/`ask` and `Trade`'s `price`/`quantity`, then switched to `BigDecimal` before that PR merged — the reasoning was recorded in the commit message (`cef5fcc`) but never promoted to an ADR, despite `write-adr`'s own `SKILL.md` citing this exact decision as the precedent example for "flag domain-shaping decisions before implementing them." Worth recording now, retroactively, since every price and trade field in the system depends on this choice and it's exactly the kind of type decision that's costly to unwind once other code (arithmetic, comparisons, serialization, frontend types) builds on it.

## Decision

Use `java.math.BigDecimal` for every money-related field — `PriceTick.bid`/`ask`, `Trade.price`/`quantity`, `TradeRequest.price`/`quantity` — never `double` or `float`. `double` is binary floating-point and can't exactly represent decimal fractions (e.g. `0.1` has no exact binary representation), which compounds into rounding errors across arithmetic and comparisons — a well-known pitfall for money-related values, and one this project hit directly: `MarketDataService`'s random-walk price generation and `TradeService`'s trade creation both do repeated arithmetic on these fields.

## Consequences

- Price and quantity values are exact and safe to compare, add, and persist without accumulating floating-point drift, which matters more as MVP 0.4 introduces persistence and MVP 0.2 introduces trade validation logic that will compare and combine these values.
- `BigDecimal` arithmetic is more verbose than primitive arithmetic (`.add()`/`.subtract()`/`.multiply()` instead of `+`/`-`/`*`, explicit `RoundingMode` for scale-changing operations like division) — already visible in `MarketDataService.randomWalk()`.
- The frontend cannot mirror this exactly: `PriceTick`/`TradeRequest`/`Trade`'s TypeScript types use plain `number` (see `frontend/src/types/`), since JSON has no decimal type and Jackson serializes `BigDecimal` as a JSON number. This is an accepted, unavoidable boundary — precision-sensitive computation only ever happens on the backend; the frontend just displays and round-trips values it received.
