# 0019. Audit events: persisted to Postgres, backend-only

Date: 2026-08-07

Status: Accepted

## Context

Issue #28 ("Audit events") asked to "introduce audit event logging for trading and session activity, to support compliance/traceability needs," without specifying how or where the trail lives, or whether it should be visible anywhere in the app. Given the real tradeoffs and lasting consequences, this was flagged to @liccioni up front, per the same standard as ADR 0011/0013/0015/0016/0017/0018. Two forks were presented:

1. **Storage** — persist to Postgres (a new `audit_events` table, following the `Trade` precedent from MVP 0.4), or write structured log lines only (no new persistence, using Logback which ships with Spring Boot but which this codebase has never actually used).
2. **Exposure** — backend-only for now (no way to view the trail from the app), or also add a query envelope and a minimal frontend view (mirroring `GET_TRADE_HISTORY`/`TradeBlotter`).

## Decision

* **Persisted to Postgres.** A new `AuditEvent` record (`id`, `sessionId` nullable, `username`, `eventType`, `detail`, `timestamp`) is saved via R2DBC to a new `audit_events` table, using the same `Persistable<String>`-always-new treatment `Trade` needed (see [docs/testing.md](../testing.md)'s Persistable gotcha) since the id is app-assigned. Chosen over log-only because "compliance/traceability" implies an audit trail should survive a restart — a log line that's never shipped anywhere is a weaker compliance story than a durable row, and MVP 0.4 already established the persistence pattern to follow.
* **Backend-only for now.** No new envelope, no frontend view. Matches the issue's literal framing ("introduce audit event logging") as infrastructure; a way to actually query or view the trail (a compliance officer's screen, say) is real, additional scope for a later issue if a concrete need shows up.
* **What gets recorded**, one `AuditService.record(sessionId, username, eventType, detail)` call per event:
  * `LOGIN_SUCCESS` / `LOGIN_FAILURE` — from `AuthService.login()`, before any `Session` exists, so `sessionId` is `null` here.
  * `SESSION_STARTED` — from `SdpWebSocketHandler.handle()`, once a connection's token has been validated and a `Session` created.
  * `TRADE_EXECUTED` / `TRADE_CANCELLED` / `TRADE_REJECTED` — from `TradeService`'s `confirmTrade`/`cancelTrade`/`requestTrade`, each carrying the submitting `Session`'s id and username.
  * Deliberately not recorded: the initial `CREATE_TRADE`/pending step (nothing compliance-relevant has happened yet — no money moved, no position changed), and `SUBSCRIBE`/`UNSUBSCRIBE` (too frequent, not compliance-relevant). `SESSION_ENDED` (on WebSocket disconnect) was also considered and left out — nothing else in the app currently hooks connection-close for any purpose (see issue #79's backlog note making the same call for pending-trade cleanup), and adding it now would be new infrastructure for a fact ("a session ended") with no established value yet.
* **`Trade`/`TradeRejected` themselves stay unchanged** — no submitter identity added to the wire/DB shape those types already have. Audit attribution happens at the point of the service call (`TradeService` methods now take a `Session` parameter), not by embedding identity into the trade record itself. This keeps the decision ADR 0017/0018 explicitly deferred ("Trade and TradeRejected carry no submitter identity yet") still open for whoever picks it up — this issue answers "is the activity traceable," not "does the trade record know who submitted it."

## Consequences

* `TradeService.requestTrade`/`confirmTrade`/`cancelTrade` all now take a `Session` parameter and are uniformly reactive (`cancelTrade` was previously synchronous `Optional<PendingTrade>`; auditing is I/O, so it needed to become `Mono<PendingTrade>` to compose the write correctly rather than firing it off unmanaged).
* `AuthService` and `SdpWebSocketHandler` both gain a new dependency on `AuditService`. `com.sdp.trade` and `com.sdp.auth` now depend on the new `com.sdp.audit` package; no cycle results, since `com.sdp.audit` depends on nothing else in the app.
* Login and trade confirmation/cancellation/rejection each now include one extra R2DBC round-trip before completing. Not expected to be noticeable at this app's scale, but it's a real latency cost that wasn't there before.
* The audit trail has no reader yet — nothing in the app queries `audit_events` after writing to it. This is intentional (see Decision), but means the only way to verify it today is a direct repository query in a test or a manual `psql` check, not anything the running app itself surfaces.
* A future "view the audit trail" feature (a `GET_AUDIT_LOG` envelope, or a proper admin UI) is real, deferred work — not built here, not assumed to be trivial when it happens (real systems usually want pagination, filtering, and access control a raw `findAll()` doesn't provide).
