# 0018. Execution workflow: two-step confirm/cancel, no automatic expiry

Date: 2026-08-07

Status: Accepted

## Context

Issue #27 ("Execution workflows") asked to introduce "more realistic execution workflows... beyond the simple create-trade flow from MVP 0.1/0.2," giving examples rather than a spec: multi-step confirmation, execution venues, or a state machine. Today, `TradeService.createTrade()` validates and persists a trade in one step, broadcasting `TRADE_CREATED`/`TRADE_REJECTED` immediately — there is no confirmation step, no pending state, and no venue concept.

Given the range of plausible scopes and the lasting protocol/domain consequences of each, this was flagged to @liccioni up front, per the same standard as ADR 0011/0013/0015/0016/0017. Two forks were presented:

1. **Workflow shape** — a two-step confirm/cancel flow (CREATE_TRADE holds a trade as PENDING, requiring an explicit confirm before it executes); a state-machine-only change with no observable behavior difference; or simulated execution venues.
2. **Pending-trade cleanup** — whether a PENDING trade should expire automatically (e.g. on its session's connection closing) or persist until explicitly resolved.

## Decision

* **Two-step confirm/cancel**, chosen over the state-machine-only option (too thin — no real behavior change) and simulated venues (more infrastructure than this milestone needs). `CREATE_TRADE` now validates and prices a trade, holds it in-memory as a `PendingTrade` keyed by a fresh id, and replies with a new `TRADE_PENDING` envelope sent **only to the submitting connection** (not broadcast — nobody else was ever told this trade exists). The client sends a new `CONFIRM_TRADE` or `CANCEL_TRADE` envelope (payload `{id}`) to resolve it:
  * `CONFIRM_TRADE` persists the trade and publishes the existing broadcast `TRADE_CREATED`, unchanged from before.
  * `CANCEL_TRADE` removes the pending trade and replies with a targeted (non-broadcast) `TRADE_CANCELLED` to the submitter only.
  * An unknown/already-resolved id on either envelope is a silent no-op — no new error envelope type for this edge case, since nothing observable was ever promised for an id nobody else knows about.
* **No automatic expiry.** A `PendingTrade` stays pending until explicitly confirmed or cancelled; there's no timer, scheduler, or connection-close cleanup. An orphaned pending trade is inert in-memory state, not a resource leak nothing else depends on — consistent with "avoid unnecessary infrastructure."
* **Simulated execution venues** and **cancel-pending-on-disconnect** were both real alternatives the user wants to keep exploring later. Captured as milestone-less backlog issues (#78, #79) rather than left as prose here, so they don't get lost — same pattern as issue #69's earlier backlog entry.

## Consequences

* `TradeService` splits into three operations instead of one: `requestTrade` (validate + hold pending, synchronous — no I/O happens until confirmation, so no `Mono` needed), `confirmTrade` (persist + broadcast, reactive), and `cancelTrade` (remove pending, synchronous). This is a bigger internal restructuring than most prior issues, but keeps "reactive at the edges" — only the method that actually touches Postgres stays a `Mono`.
* The protocol grows two new client→server envelope types (`CONFIRM_TRADE`, `CANCEL_TRADE`) and two new server→client ones (`TRADE_PENDING`, `TRADE_CANCELLED`), the latter two joining `TRADE_HISTORY` as targeted-only, never-broadcast deliveries through a connection's own direct sink.
* The frontend's buy/sell flow is no longer one-shot: `PriceGrid` (which already owns the WebSocket connection and quantity/symbol/side state for a trade ticket) must hold a pending trade in local state after `CREATE_TRADE` and render a confirm/cancel prompt before the trade is real. `ExecutionConfirmation` and `TradeBlotter` are unaffected until `TRADE_CREATED` actually fires, since they only ever learn about a trade once it's real.
* Pending trades are in-memory only, same durability tradeoff as auth tokens and the pre-MVP-0.4 `EventBus` — a backend restart silently drops any trade someone hasn't confirmed yet. Not a regression, since nothing about pending trades was ever meant to be durable.
