# 0009. Use AG Grid Community for the price grid and trade blotter

Date: 2026-08-05

Status: Accepted

## Context

[architecture.md](../architecture.md) names AG Grid Community as the frontend grid library, but no ADR ever recorded why, and issues #9 (price grid) and #11 (trade blotter) implemented it before any decision record existed. Worth capturing now: real, non-obvious integration gotchas surfaced while building `PriceGrid` and `TradeBlotter` that the next person touching either component (or adding a new grid) needs to know rather than rediscover.

## Decision

Use `ag-grid-community` + `ag-grid-react` (v36) for both `PriceGrid` and `TradeBlotter`, instead of a lighter table library (e.g. TanStack Table, a hand-rolled `<table>`) or a full UI kit's data grid (e.g. MUI DataGrid). AG Grid Community is free and sufficient for MVP 0.1/0.2's needs (sorting, column display, live row updates); Enterprise-only features (row grouping, pivoting, Excel export) are explicitly out of scope unless a future milestone calls for them.

Three integration details, non-obvious enough to record:

1. **Module registration is mandatory.** v36 uses AG Grid's modular architecture — `ModuleRegistry.registerModules([AllCommunityModule])` must run once before any grid renders, or the grid throws at runtime. Both `PriceGrid` and `TradeBlotter` call this at module load time.
2. **Rows are identified by domain identity, not array index**, via `getRowId` — `symbol` for `PriceGrid` (each tick upserts its symbol's row in place), trade `id` for `TradeBlotter` (each trade is a new, immutable row, prepended most-recent-first). Getting this wrong means new data either duplicates rows or fails to update them in place.
3. **`domLayout="autoHeight"` instead of a fixed-height viewport.** AG Grid's default row model decides which rows to render based on real pixel measurements of the viewport. jsdom has no layout engine, so those measurements are always zero — a fixed-height grid rendered zero rows under Vitest, passing only by observation-timing accident. `autoHeight` sizes to content instead, sidestepping the problem, and is also just the right fit for this project's small, non-virtualized row counts (3 symbols, a modest trade blotter).

## Consequences

- A full-featured grid (sorting, resizing, accessible `role="grid"` markup) without hand-rolling grid interaction logic, and a natural fit for near-term future use (MVP 0.2's trade ticket UI) without a rewrite.
- AG Grid's Theming API and modular architecture have changed significantly across major versions; the registration and theming approach recorded here may need revisiting on a future AG Grid upgrade.
- `domLayout="autoHeight"` doesn't scale to a large, virtualized row count. It's the right choice for today's 3-symbol price grid and a modest trade blotter, but MVP 0.4's persisted trade history will likely need a fixed-viewport, virtualized grid instead — a follow-up decision when that milestone arrives, not a change to make preemptively now.
