# Trade history query engine — design

Date: 2026-08-13

Implements [issue #130](https://github.com/liccioni/SpringSDP/issues/130), the backend half of MVP 1.0
("Trade history at scale" — see `docs/roadmap.md`). Gateway relay (#131), frontend AG Grid wiring
(#132), and ADR/protocol docs (#133) are separate issues in the same milestone and out of scope here.

⸻

## Problem

`TradeService.handleGetTradeHistory` currently calls `tradeRepository.findAllByOrderByTimestampAsc()` —
no `LIMIT`/`OFFSET`, no filtering, no sorting — and returns the entire `trades` table as one bare
`List<Trade>` on every `GET_TRADE_HISTORY` request. This was flagged and deliberately deferred in
MVP 0.9's retro (PR #125) and becomes unsustainable once trade volume grows into the thousands or
millions of rows.

This issue replaces that with a real query engine: cursor (keyset) pagination, server-side filtering,
and server-side sorting, backed by a new allow-listed, parameterized SQL query rather than a derived
Spring Data repository method (which can't express dynamic optional filters/sort/keyset predicates).

⸻

## Architecture

No change to service topology. This is entirely internal to `trading-service`:

* Three new records in `contracts/src/main/java/com/sdp/contracts/` — the request/reply shapes for
  `GET_TRADE_HISTORY`'s payload, riding the existing `TradeCommand`/`TradeCommandResult` correlated
  request/reply pair (ADR 0022's update) unchanged.
* One new class, `TradeHistoryQueryService` (`com.sdp.trading`), backed by Spring Data R2DBC's
  `DatabaseClient` — the first use of `DatabaseClient` directly in this codebase (everywhere else uses
  `ReactiveCrudRepository`-derived queries).
* `TradeService.handleGetTradeHistory` rewired to deserialize the incoming query and delegate.
* One new index in `trading-service/src/main/resources/schema.sql`.

`TradeRepository.save()`/`findById()` and `Trade`'s `Persistable<String>`/app-assigned-id behavior
(`docs/testing.md`'s R2DBC gotcha) are untouched — this issue only touches reads.

⸻

## Contract types (`contracts/`)

```java
record TradeHistoryQuery(int pageSize, String cursor, TradeSort sort, List<TradeFilter> filters) {}
record TradeSort(String column, boolean descending) {}
record TradeFilter(String column, String type, String value, String valueTo) {}
record TradeHistoryPage(List<Trade> rows, String nextCursor, boolean hasMore) {}
```

* `cursor` is `null` for the first page.
* `TradeFilter.type` mirrors AG Grid's own filter model: `contains` / `equals` / `startsWith` /
  `lessThan` / `greaterThan` / `inRange`. Single condition per column for v1 — no AND/OR compounds.
  `valueTo` is only meaningful for `inRange`.
* No explicit `sort` → default is `timestamp DESC` (newest-first) — a documented change from today's
  wire-level "oldest first" ordering, matching what the frontend has always *displayed* via its own
  client-side sort. `docs/protocol.md` is updated for this in #133, not here.

⸻

## `TradeHistoryQueryService`

### Column allow-list

A fixed `Map<String, String>` of logical column name → SQL column, covering exactly the five
sortable/filterable columns: `symbol`, `side`, `price`, `quantity`, `timestamp`. No client-supplied
column name is ever interpolated into SQL directly — only names present in this map reach the query
string; every value (filter values, cursor fields, pageSize) is a bound parameter.

### Per-column valid filter types

| Column | Valid `type`s |
|---|---|
| `symbol` | `contains`, `startsWith`, `equals` |
| `side` | `equals` |
| `price`, `quantity` | `equals`, `lessThan`, `greaterThan`, `inRange` |
| `timestamp` | `equals`, `lessThan`, `greaterThan`, `inRange` |

### Cursor encoding

Base64url of a JSON object: `{"ts": "<ISO instant>", "id": "<uuid>", "sv": "<sort column's value>"}`.
`sv` is omitted when sorting by `timestamp` itself (redundant with `ts`). A JSON envelope (rather than
a raw delimited string) so it can grow without a breaking format change later. `sv` is encoded as the
sort column's natural string form (`BigDecimal.toString()` for `price`/`quantity`, the enum name for
`side`, the raw string for `symbol`) and parsed back into that column's type on decode.

### Query shape

All values bound as parameters; only the allow-listed column name/sort direction are string-built:

```sql
SELECT id, symbol, side, price, quantity, timestamp FROM trades
WHERE 1=1 [AND <filter predicates from the allow-list>]
  [AND (<sortColumn>, timestamp, id) > (:sv, :ts, :id)]  -- keyset predicate, flipped to < when descending
ORDER BY <sortColumn> [ASC|DESC], timestamp [ASC|DESC], id [ASC|DESC]
LIMIT :pageSizePlusOne
```

Fetches `pageSize + 1` rows and trims the extra one to compute `hasMore`, avoiding a separate
`COUNT(*)` query. `nextCursor` is built from the last row of the trimmed page.

### Input handling (fail-open)

Every one of these is a graceful degrade, not an error reply — `GET_TRADE_HISTORY` still only ever
gets a `TRADE_HISTORY` reply; nothing new is added to the wire protocol. Each drop logs a `WARN` with
the offending field/value for diagnosability.

* **Unknown sort/filter column** (not in the allow-list): dropped. An unknown sort column falls back
  to the default (`timestamp DESC`); an unknown filter column is simply omitted from `WHERE`.
* **Filter type invalid for its column, or a value that fails to parse** for the column's type (e.g.
  `"abc"` on a numeric `price` filter): that one filter condition is dropped; the rest of the query
  still runs.
* **Malformed cursor** (bad base64, unparseable JSON, corrupted field): treated as `cursor = null` —
  i.e. restart from the first page.
* **`pageSize`**: clamped server-side, since it's bound straight into `LIMIT`. `pageSize <= 0` →
  default `100`. `pageSize > 500` → capped at `500`.

⸻

## `TradeService` changes

`handleGetTradeHistory` deserializes `command.payload()` into `TradeHistoryQuery` (mirroring the
existing `objectMapper.convertValue` pattern used for `TradeRequest`/`PendingTradeId`), calls
`TradeHistoryQueryService`, and replies `TRADE_HISTORY` with the resulting `TradeHistoryPage` instead
of a bare list.

`TradeRepository.findAllByOrderByTimestampAsc()` is deleted once nothing calls it.

⸻

## Schema change

```sql
CREATE INDEX IF NOT EXISTS idx_trades_timestamp_id ON trades (timestamp, id);
```

Added to `trading-service/src/main/resources/schema.sql`, required so the keyset predicate doesn't
force a full sort per page. Symbol/side secondary indexes are deferred until profiling shows a need.
This is still one idempotent `CREATE INDEX IF NOT EXISTS` with no data migration, so it doesn't trigger
[ADR 0015](../decisions/0015-trade-history-db-backed-schema-sql.md)'s Flyway-reconsideration trigger.

⸻

## Testing

* **`TradeServiceTest`** (unit): rewrite `getTradeHistoryRepliesWithThePersistedHistory` to mock
  `TradeHistoryQueryService` instead of `TradeRepository.findAllByOrderByTimestampAsc`.
* **`TradeServiceIT`** (Testcontainers Postgres): rewrite the equivalent case to send a real
  `TradeHistoryQuery` and assert on `TradeHistoryPage.rows()`. Stays focused on request/reply plumbing,
  not exhaustive query-engine behavior.
* **New `TradeHistoryQueryServiceIT`** (Testcontainers Postgres, per `docs/testing.md`'s
  `PostgresIntegrationTest` convention — remembering its container/table is shared across the whole
  test run, so assertions must check that *this test's* rows are present, not that the table is empty
  beforehand): a dedicated class directly exercising the query engine —
  * Keyset correctness across two pages of more than `pageSize` rows — seed explicit timestamps, not
    `Instant.now()`, so tie-breaking is deterministic and actually tested.
  * One case per filter type (`contains`, `equals`, `startsWith`, `lessThan`, `greaterThan`, `inRange`).
  * Both sort directions, with a real tie (two rows sharing a sort-column value) to prove the
    `timestamp`/`id` tiebreaker actually breaks it.
  * The four fail-open behaviors: unknown column, invalid filter type/unparseable value, malformed
    cursor, and `pageSize` clamping at both ends (`<= 0` and `> 500`).

⸻

## Out of scope

Gateway relay, frontend AG Grid wiring, and ADR/`docs/protocol.md` updates are separate issues
(#131, #132, #133) in this milestone. Multi-column sort and compound (AND/OR) per-column filters are
v1 scope cuts — not implemented here, and not without a follow-up issue.
