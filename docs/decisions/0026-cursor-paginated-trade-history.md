# 0026. Server-side filtering/sorting and cursor-based pagination for trade history

Date: 2026-08-21

Status: Accepted

## Context

`GET_TRADE_HISTORY` loaded the entire `trades` table, unbounded, on every
connection's connect, and the gateway relayed it as one WebSocket frame - a
gap PR #125 explicitly deferred rather than fixed. This doesn't scale once
trade volume grows into the thousands or millions of rows: every connect
pays for the full table, and the frontend's `TradeBlotter` filtered/sorted/
paginated only after receiving all of it (MVP 0.9, issue #118). MVP 1.0
moved filtering, sorting, and pagination server-side (issues #130-#132).

## Decision

* **Cursor/keyset pagination, not offset/page-number.** An `OFFSET n`
  query's cost grows with `n` - Postgres still has to scan and discard every
  skipped row - so it decays as the table grows, the opposite of what a
  "trade history at scale" milestone needs. Offset pagination is also
  incorrect under concurrent inserts: a new trade inserted ahead of the
  current page shifts every subsequent row's offset by one, causing skipped
  or duplicated rows across pages. Keyset pagination instead carries the
  last-seen row's own sort key forward as the next page's lower/upper bound
  (`WHERE (timestamp, id) < (:cursorTimestamp, :cursorId)` for the
  descending default), so a concurrent insert elsewhere in the table can
  never shift an already-issued cursor's meaning.
* **`(sortColumn, timestamp, id)` as the actual keyset**, not just
  `(timestamp, id)` - `TradeHistoryQueryService.appendKeysetPredicate`
  prepends whichever column the client sorted by (when it isn't `timestamp`
  itself) ahead of the `(timestamp, id)` tiebreaker pair, since a sort on
  `symbol`/`side`/`price`/`quantity` needs its own value in the row
  comparison to stay correct, not just a tiebreaker for ties on that column.
  `id` (a UUID, already the table's primary key) is the final tiebreaker in
  every case, since `timestamp` alone isn't guaranteed unique.
* **The cursor is opaque to the client**: a base64url-encoded JSON object
  (`TradeHistoryQueryService.Cursor`: `{ts, id, sv}` - `sv` only populated
  when the sort column isn't `timestamp`), round-tripped through
  `TradeHistoryQuery.cursor()`/`TradeHistoryPage.nextCursor()` as a plain
  `String`. The frontend never inspects or constructs one - it just stores
  whatever `nextCursor` it was last given
  (`tradeHistoryDatasource.ts`'s `cursorByBlockStart`) and passes it back
  verbatim on the next request.
* **A generic `Envelope.correlationId` field**, not a payload field scoped
  to `TradeHistoryQuery` alone (issue #131). AG Grid's Infinite Row Model
  can have more than one `getRows()` call in flight at once per connection
  (e.g. a fast scroll issuing overlapping block requests), and the existing
  envelope carried no way to match a `TRADE_HISTORY` reply back to the
  request that triggered it. Promoting the field to `Envelope` itself, over
  keeping it inside the query payload, means any future request/reply
  message type can reuse the same mechanism without another protocol
  change. It is currently populated only for `GET_TRADE_HISTORY`/
  `TRADE_HISTORY` - every other envelope type constructs `Envelope` through
  the two-argument constructor, which defaults it to `null`. The
  gateway itself does no correlation-based routing of its own for this
  field end-to-end: `SdpWebSocketHandler` reads the inbound envelope's
  `correlationId` and echoes it back on the `TRADE_HISTORY` reply, while
  `TradeService.history()` separately reuses it (generating one if absent)
  as the RabbitMQ-level `TradeCommand.correlationId()` for its own
  request/reply matching against `trading-service` - two independent uses
  of the same value, not one mechanism spanning both hops.
* **AG Grid Community's Infinite Row Model**, not Enterprise's Server-Side
  Row Model - re-confirming [ADR 0009](0009-use-ag-grid.md)'s Community-tier
  choice under this milestone's actual server-side filtering/sorting/
  pagination requirement, not just the original static blotter. The
  Infinite Row Model is documented as Community-tier and is sufficient for
  this project's single-grid, no-grouping use case; see AG Grid's
  [row models documentation](https://www.ag-grid.com/react-data-grid/row-models/)
  for the tier boundary. The Server-Side Row Model (row grouping, tree data,
  pivoting) would be Enterprise-only and is more capability than a flat
  trade blotter needs.
* **v1 scope cuts, deliberate:**
  * Single-column sort only - `TradeSort` is one `(column, descending)`
    pair, not a list. AG Grid's own UI defaults to single-column sort
    without extra configuration, so this needed no frontend restriction, only
    `TradeHistoryQuery` shaping its wire type as one `TradeSort` rather than
    a list.
  * Single-condition filters only - one `TradeFilter` per column, no AND/OR
    compounds (AG Grid's "simple" filter mode, not its multi-condition
    "complex" mode).
  * `timestamp DESC` (newest-first) as the default order when no sort is
    given - matches what a trader opening the blotter wants to see first,
    and matches the AG Grid Infinite Row Model's own default absent an
    explicit `sortModel`.
* **The `TradeHistoryQuery`/`TradeSort`/`TradeFilter`/`TradeHistoryPage`
  contract types** (`contracts/src/main/java/com/sdp/contracts/`) carry
  their own Javadoc describing each field's wire meaning - see those files
  directly rather than duplicating the shapes here.

## Consequences

* **The Infinite-Row-Model/keyset jump-scroll tension is a real,
  user-visible limitation, not a footnote.** Keyset pagination only knows
  how to step forward/backward from a cursor it already issued - it has no
  way to compute "the cursor for row 50,000" without walking there. AG
  Grid's Infinite Row Model, however, lets a user drag the scrollbar
  directly to an arbitrary block far from any block already fetched.
  `tradeHistoryDatasource.ts`'s `cursorByBlockStart` only records a cursor
  for blocks reached by normal top-to-bottom scroll; a request for a
  `startRow` with no recorded cursor (a scrollbar-drag jump) discards the
  cache and restarts from `cursor=null` - the request silently resolves to
  page 1 rather than the dragged-to position. Accepted for v1: a live
  trade blotter is scrolled far more often than scrollbar-dragged to a
  specific offset, and fixing this properly would mean either falling back
  to offset pagination for jumps (reintroducing the correctness/performance
  problems this ADR exists to avoid) or a materially more complex
  windowed-cursor cache. Revisit only if this degradation turns out to
  matter in practice.
* Every sortable/filterable column is allow-listed
  (`TradeHistoryQueryService.SORTABLE_COLUMNS`) and every value is bound as
  a parameter, never interpolated - no client-supplied column name or value
  ever reaches raw SQL. A future column added to the blotter needs an
  explicit entry here before it can be sorted or filtered on; omitting it
  fails closed (`IllegalArgumentException`), not open.
* `TradeRepository.findAllByOrderByTimestampAsc` (the unbounded query this
  replaces) and its Spring Data derived-query mechanism are no longer used
  for history at all - `TradeHistoryQueryService` goes directly through
  `DatabaseClient`, since Spring Data repository methods can't express a
  dynamic optional filter/sort/keyset predicate. `TradeRepository` itself
  still exists for `TradeService`'s write path (`save`/`findById` for the
  pending-trade lifecycle).
* `docs/decisions/0015-trade-history-db-backed-schema-sql.md` gets a
  one-line Consequences addendum for the new `idx_trades_timestamp_id`
  index this milestone added - it still fits `schema.sql`'s existing
  "one idempotent script" mechanism, not a trigger for reconsidering Flyway
  (that ADR's own stated trigger is a real data migration, not an added
  index).
