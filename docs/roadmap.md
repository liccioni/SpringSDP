# Roadmap

⸻

## MVP 0.1 – Streaming skeleton ✅ Done

See [retro 0001](retros/0001-mvp-0.1.md) for what shipped, what was verified, and what was learned.

A user can:

1. Open the application
2. See live FX price ticks
3. Double-click a price
4. Create a trade
5. See the trade appear in the trade blotter

That is the entire MVP.

Scope:

* WebSocket connection
* fake FX price generator
* price grid
* trade creation
* trade blotter

⸻

## MVP 0.2 – Trading flow ✅ Done

See [retro 0002](retros/0002-mvp-0.2.md) for what shipped, what was verified, and what was learned.

* quantity entry
* buy/sell actions
* execution confirmations
* trade validation

⸻

## MVP 0.3 – Reactive architecture ✅ Done

See [retro 0003](retros/0003-mvp-0.3.md) for what shipped, what was verified, and what was learned.

* event bus
* reactive services
* subscription management
* improved state handling

⸻

## MVP 0.4 – Persistence ✅ Done

See [retro 0004](retros/0004-mvp-0.4.md) for what shipped, what was verified, and what was learned.

* PostgreSQL
* R2DBC
* trade history
* application restart recovery

⸻

## MVP 0.5 – Dealer platform foundation ✅ Done

See [retro 0005](retros/0005-mvp-0.5.md) for what shipped, what was verified, and what was learned.

* authentication
* sessions
* market data subscriptions
* execution workflows
* audit events

⸻

## MVP 0.6 – Identity & session ✅ Done

See [retro 0006](retros/0006-mvp-0.6.md) for what shipped, what was verified, and what was learned. Reversed two of this project's original "start simple" constraints (no framework auth, no Redis) the same way MVP 0.5 lifted "no authentication" — see CLAUDE.md's Core philosophy section.

* Spring Security + Keycloak (authorization code grant), a realm with `trader`/`viewer` roles, replacing the hand-rolled auth from ADR 0016
* Spring Session backed by Redis
* config externalized via env vars

⸻

## MVP 0.7 – Service topology & messaging ✅ Done

Reversed "no microservices." Split the monolith into three services, connected via RabbitMQ and Spring Cloud Stream binders, migrated incrementally (strangler-fig, not a big-bang cutover) so the app stayed runnable at every stage — see CLAUDE.md's Core philosophy section.

* a pure WebSocket gateway (`gateway/`), absorbing the monolith's OAuth2 login, session, and WebSocket-handling code as its final step
* a market data service (`market-data-service/`)
* a backend/trading service with database access (`trading-service/`) - trade domain logic and the audit trail
* the original monolithic `backend/` module deleted entirely once every flow had migrated

⸻

## MVP 0.8 – Session lifecycle & cleanup ✅ Done

See [retro 0008](retros/0008-mvp-0.8.md) for what shipped, what was verified, and what was learned. Closed two gaps left over from earlier milestones: no logout flow existed since Keycloak login shipped in MVP 0.6, and pending trades submitted but never confirmed or cancelled leaked forever once their connection closed (flagged and deliberately deferred during MVP 0.5's execution-workflow work, #27).

* a real logout flow (OIDC RP-Initiated Logout), ending both the app's own Spring Session and Keycloak's SSO session (#102, [ADR 0023](decisions/0023-oidc-rp-initiated-logout.md))
* pending trades cancelled automatically when their owning session's WebSocket connection closes (#79, [ADR 0024](decisions/0024-cancel-pending-trades-on-disconnect.md))

⸻

## MVP 0.9 – Role enforcement & blotter usability ✅ Done

See [retro 0009](retros/0009-mvp-0.9.md) for what shipped, what was verified, and what was learned. Closed a real gap found while working through the platform end to end: the `viewer` Keycloak role (defined correctly since MVP 0.6, with a demo user assigned it) was never actually enforced anywhere - no code path, frontend or backend, checked it. Also addressed the trade blotter's lack of filtering/pagination before trade volume makes it unwieldy.

* Keycloak `trader`/`viewer` realm roles enforced on trade creation, authoritatively in `trading-service` ([#117](https://github.com/liccioni/SpringSDP/issues/117), [ADR 0025](decisions/0025-trader-role-enforcement.md))
* filtering and pagination added to the trade blotter, client-side over AG Grid Community's existing capabilities ([#118](https://github.com/liccioni/SpringSDP/issues/118))

⸻

## MVP 1.0 – Trade history at scale ✅ Done

See [retro 0010](retros/0010-mvp-1.0.md) for what shipped, what was verified, and what was learned. Closed a gap PR #125 explicitly deferred: `GET_TRADE_HISTORY` loaded the entire `trades` table, unbounded, on every connection's connect, and the gateway relayed it as one WebSocket frame - unsustainable once trade volume grows into the thousands or millions of rows. Moved filtering, sorting, and pagination server-side, using cursor/keyset pagination (not offset/page-number, for correctness under concurrent inserts and to avoid `OFFSET` performance decay at scale) and AG Grid Community's Infinite Row Model (confirmed Community-tier, not Enterprise).

* cursor-paginated, filterable, sortable trade history query engine in `trading-service`, plus the new `TradeHistoryQuery`/`TradeHistoryPage` contract types ([#130](https://github.com/liccioni/SpringSDP/issues/130))
* a generic `correlationId` field on the WebSocket `Envelope` type, and the gateway's `GET_TRADE_HISTORY` relay updated to use it ([#131](https://github.com/liccioni/SpringSDP/issues/131))
* the trade blotter switched to AG Grid's Infinite Row Model, with a correlation-aware datasource driving server-side filtering/sorting/pagination ([#132](https://github.com/liccioni/SpringSDP/issues/132))
* a new ADR and a `docs/protocol.md` rewrite for the new payload/reply shapes, written against what actually ships in the three issues above ([#133](https://github.com/liccioni/SpringSDP/issues/133))

⸻

## MVP 1.1 – Session resilience ✅ Done

See [retro 0011](retros/0011-mvp-1.1.md) for what shipped, what was verified, and what was learned. Of the three bugs found live-verifying MVP 0.9 through both README run paths, two closed within MVP 1.0 ([#126](https://github.com/liccioni/SpringSDP/issues/126), logout 500s when the session/authentication has already expired; [#127](https://github.com/liccioni/SpringSDP/issues/127), the audit trail and `HELLO` greeting recording the Keycloak `sub` UUID instead of the username). The third, [#128](https://github.com/liccioni/SpringSDP/issues/128) (a gateway process swap with a live Redis session can enter an infinite OAuth2 redirect loop), was reproduced again, unchanged, live-verifying MVP 1.0 on the "Without Docker" path (see [retro 0010](retros/0010-mvp-1.0.md)) - the second consecutive milestone retro to hit it. This milestone closed it: every component used to open its own `WebSocket`, turning a rare Redis-session race into a near-certainty on every page load, so the frontend now shares one connection app-wide and retries a handshake that closes before opening a bounded number of times before falling back to the Keycloak login redirect ([ADR 0027](decisions/0027-single-shared-socket-with-bounded-retry.md)) - a fix for the trigger and the symptom, not a confirmed root-cause fix inside `ReactiveRedisSessionRepository` itself.

* single shared WebSocket connection + bounded retry-with-backoff before the login redirect ([#128](https://github.com/liccioni/SpringSDP/issues/128), [ADR 0027](decisions/0027-single-shared-socket-with-bounded-retry.md))

⸻

## MVP 1.2 – Per-session trade delivery ✅ Done

See [retro 0012](retros/0012-mvp-1.2.md) for what shipped, what was verified, and what was learned. Found while root-causing #128's redirect loop (unrelated to that bug's actual cause): the gateway's `EventBus` broadcast `TRADE_CREATED`/`TRADE_REJECTED` to every connected session regardless of who submitted the trade, so every trader saw every other trader's executions and rejections. This was already an accepted, deferred gap in [protocol.md](protocol.md)'s Broadcast semantics section, but a second person (design review) independently flagging it as surprising made it worth closing deliberately rather than leaving indefinitely deferred.

What shipped is more precise than this milestone's original framing below (kept for history) suggested. A first implementation attempt (PR #157) bolted a submitter-identity filter onto the broadcast — discarded as the wrong mechanism. Live design discussion while planning the redesign surfaced that "submitter identity filter" and even a simpler "make everything correlated" pass were both incomplete: the submitting connection's own UI (`PriceGrid`'s pending-trade prompt, `ExecutionConfirmation`'s toast) needs `TRADE_CREATED` reliably, independent of any subscription state, while the trade blotter's live cross-user refresh is legitimate desired behavior, not the bug, and needed its own explicit opt-in rather than either "broadcast to everyone" or "submitter only." [ADR 0028](decisions/0028-scoped-trade-delivery.md) has the full design.

* `CONFIRM_TRADE` gets a correlated `TRADE_CREATED` reply to the submitting connection (previously fire-and-forget); `CREATE_TRADE`'s rejection reply carries the real `TradeRejected` payload and never broadcasts; a new `BlotterSubscription` (mirrors `SymbolSubscription`/`PRICE_TICK`) gates the pre-existing `trade-created` broadcast so only sessions that sent `SUBSCRIBE_BLOTTER` see other sessions' trades ([#152](https://github.com/liccioni/SpringSDP/issues/152), [ADR 0028](decisions/0028-scoped-trade-delivery.md))

⸻

## MVP 1.3 – Symbol catalog & default subscriptions ✅ Done

See [retro 0013](retros/0013-mvp-1.3.md) for what shipped, what was verified, and what was learned. Raised during MVP 1.2's design discussion (issue #152) but deliberately not bundled into that milestone, per CLAUDE.md's "small, working iterations" philosophy. There were only 3 tradable symbols (`EUR/USD`, `GBP/USD`, `USD/JPY`), hardcoded independently in three places with no shared source of truth — a duplication risk [ADR 0013](decisions/0013-subscription-default-nothing-until-subscribed.md) already flagged and explicitly left out of scope (a fourth, previously unflagged copy in a gateway integration-test double was found and fixed along the way). This milestone expanded the catalog to 14 currency pairs behind a single shared `contracts.SymbolCatalog` constant, added a new `SYMBOLS` discovery message the gateway sends right after `HELLO`, and moved each connection's default from "everything" to a fixed "majors" (6-pair) subset with no persistence across reconnects (matching `Session`'s existing no-reconnect-continuity model, [ADR 0017](decisions/0017-session-scope.md)) — without reopening ADR 0013's "nothing until subscribed" transport-level default, which the default-subscribed-subset model satisfies equally well. [ADR 0029](decisions/0029-shared-symbol-catalog.md) records why the catalog is a compile-time shared constant rather than a runtime broadcast from `market-data-service`.

* expanded the tradable symbol catalog to 14 pairs behind a single shared `contracts.SymbolCatalog`, added a `SYMBOLS` discovery message, and defaulted each connection to a fixed "majors" subscribed subset instead of the frontend's previous subscribe-to-everything behavior ([#159](https://github.com/liccioni/SpringSDP/issues/159), [ADR 0029](decisions/0029-shared-symbol-catalog.md))

⸻

## What's next

MVP 0.1 through 1.3 are done; no milestone is currently in progress. Issue [#119](https://github.com/liccioni/SpringSDP/issues/119) (swappable messaging transport) is resolved without a milestone: it was a research issue, answered by [ADR 0030](decisions/0030-messaging-transport-swappability.md) - the Spring Cloud Stream groundwork already holds and a second binder is technically feasible, but not worth adding with no concrete consumer today. Backlog issues [#78](https://github.com/liccioni/SpringSDP/issues/78) (simulated execution venues), [#103](https://github.com/liccioni/SpringSDP/issues/103) (reverse proxy), [#120](https://github.com/liccioni/SpringSDP/issues/120) (Kubernetes manifests), [#121](https://github.com/liccioni/SpringSDP/issues/121) (cloud deployment options), and [#122](https://github.com/liccioni/SpringSDP/issues/122) (real market data providers) remain open, deliberately unscheduled per CLAUDE.md's Core philosophy - no concrete need for any of them has arrived yet. A new milestone would need one of these (or a newly filed issue) picked deliberately, per this project's "add scope only when a concrete need justifies it" philosophy - the next session should ask rather than assume which. A multi-cloud Terraform setup with a CLI installer to pick the provider was also considered and deliberately dropped rather than filed - it's a large amount of infrastructure for a single-maintainer project with no cloud deployment yet at all; #121 above covers picking one direction with a cost rationale instead.

### Backlog execution strategy (if/when picked up)

No hard dependency exists between any pair of these five issues - confirmed
by re-reading all five issue bodies and every ADR/doc they reference. But
they do split into two independent tracks, and one soft link inside the
infra track is worth recording so a future session doesn't have to
re-derive it:

* **Infra/productionization track: [#121](https://github.com/liccioni/SpringSDP/issues/121) → [#103](https://github.com/liccioni/SpringSDP/issues/103) → [#120](https://github.com/liccioni/SpringSDP/issues/120).** #121 is pure research (a written cloud-provider/cost comparison, no code) and cheapest to resolve first. #120's own body says its in-cluster-vs-managed-services decision "ties into" the cloud-deployment investigation - i.e. #121's outcome. #103 shares #121's own stated trigger ("once TLS/production deployment becomes a real near-term goal") and doesn't depend on which cloud gets picked, so it can land once that shared trigger fires and before #120 needs a settled origin/TLS story. This is a rationale ordering, not a hard gate - no ADR mandates it.
* **Trading-domain track: [#78](https://github.com/liccioni/SpringSDP/issues/78) and [#122](https://github.com/liccioni/SpringSDP/issues/122), independent of each other and of the infra track.** No stated ordering between them. #78's original precondition (needing `trading-service`'s request/reply message shape to exist) is already satisfied since MVP 0.7 shipped - it's unpicked, not blocked.

Trigger conditions worth watching for, so a future session recognizes
activation instead of inventing one: for the infra track, a real need to
run outside "a single trusted Docker host" - [ADR 0021](decisions/0021-rabbitmq-network-segmentation.md)'s own stated trust-model boundary - e.g. a shareable/public demo or a security review; for the trading-domain track, a concrete product reason the single-venue model can't express (#78's own words) or a need for real price realism (#122's own words).

Once a track activates, default to single-issue milestones (matching this
project's established norm) rather than bundling a track's issues into one
milestone - e.g. if the infra track activates, that's MVP 1.4 = #121 alone,
then MVP 1.5 = #103 alone, then MVP 1.6 = #120 alone, each retro'd
independently.

This is a sequencing aid, not a scheduling decision - CLAUDE.md's "ask
rather than assume" norm above still governs actually picking a track or
issue to start.
