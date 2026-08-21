# 0015. Trade history is DB-backed only, schema managed via schema.sql

Date: 2026-08-07

Status: Accepted

## Context

Issue #22 ("Persist trade history") left two things open, both flagged to @liccioni before implementing per the same standard as [ADR 0011](0011-use-bigdecimal-for-money.md)/[ADR 0013](0013-subscription-default-nothing-until-subscribed.md):

* The issue's own wording — persist "instead of (or in addition to)" the existing in-memory state — left open whether `TradeService`'s `CopyOnWriteArrayList` blotter should be retired or kept alongside the new `TradeRepository`.
* [ADR 0014](0014-postgresql-r2dbc-connectivity.md) wired up R2DBC connectivity but deliberately deferred choosing a schema-management approach until there was an actual table to manage — issue #22 is that point.

## Decision

* **DB-backed only.** `TradeService.blotter()` (the in-memory list) is removed; `TradeService.createTrade`/`history()` go through `TradeRepository` exclusively. It had zero production callers — the frontend already builds its blotter purely from the `TRADE_CREATED` WebSocket stream — so keeping both would have meant two divergent copies of the same data for no consumer of the in-memory one.
* **Schema managed via `schema.sql`** (`spring.sql.init.mode: always`), not Flyway. For a single, first table with no migration history yet, this needed no new dependency and no JDBC-for-migrations-only setup (Flyway doesn't support R2DBC natively — it always needs a JDBC URL/driver even in an otherwise-R2DBC app). Revisit this once the schema needs actual versioned migrations rather than one static `CREATE TABLE IF NOT EXISTS`.

## Consequences

* Whoever adds a second table or a real migration (a column rename, a backfill) should reconsider Flyway then, not extend `schema.sql` indefinitely — a single idempotent script stops being the simpler option once there's real migration history to track.
* `Trade` (the R2DBC entity) needed `Persistable<String>` with `isNew()` always `true`, since its id is assigned in Java (a UUID) rather than by the database — Spring Data's default null-id "is this new" check would otherwise treat every save as an `UPDATE` matching zero rows, silently persisting nothing. See [docs/testing.md](../testing.md)'s gotcha entry for the full mechanism. Any future entity with an app-assigned id needs the same treatment.
* `GET_TRADE_HISTORY`/`TRADE_HISTORY` (the query mechanism issue #22 also added) is a new envelope type per [ADR 0010](0010-event-driven-protocol.md), not a new endpoint — that part wasn't actually an open decision, ADR 0010 already settled it.
* MVP 1.0's `idx_trades_timestamp_id` index (issue #130, [ADR 0026](0026-cursor-paginated-trade-history.md)) still fits this file's "one idempotent script" mechanism unchanged — an added index isn't the real-migration trigger this ADR's Consequences names for reconsidering Flyway.
