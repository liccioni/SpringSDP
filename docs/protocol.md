# WebSocket protocol

⸻

## Authentication

Before opening the WebSocket connection, the client authenticates via Keycloak (OAuth2 authorization code grant): a top-level browser navigation to `GET /oauth2/authorization/keycloak` redirects to Keycloak's hosted login page, which redirects back once the user logs in. There is no `POST /login` or custom login form — see [ADR 0020](decisions/0020-keycloak-oauth2-redis-session.md), superseding [ADR 0016](decisions/0016-authentication.md).

Login establishes a Redis-backed Spring Session, identified by a cookie for the `localhost` domain. Cookies aren't port-scoped, so this cookie is attached automatically to the WebSocket handshake even though the frontend and the Gateway run on different ports. `SdpWebSocketHandler` requires an authenticated session on `/ws` (enforced by Spring Security's filter chain, before `handle()` is ever invoked) — an unauthenticated upgrade attempt fails with a non-`101` response (Spring Security's default entry point, a redirect toward Keycloak), never reaching a HELLO.

## Session

A `Session` is created once a connection's authenticated principal is resolved from the WebSocket handshake, pairing the username with that WebSocket connection's own id and owning that connection's market data subscriptions. It is 1:1 with the connection: created when the connection is accepted, gone when the connection closes, with no reconnect continuity. See [ADR 0017](decisions/0017-session-scope.md) for why, and for what further work (trade attribution) is expected to build on top of it. Note this is a different, connection-scoped concept from the Redis-backed Spring Session above (HTTP-level, survives across requests) — see ADR 0020 for the distinction.

## Audit trail

Session starts (`SESSION_STARTED`) and terminal trading outcomes (`TRADE_EXECUTED`/`TRADE_CANCELLED`/`TRADE_REJECTED`) are persisted as audit events, each carrying the acting session's id (when one exists yet) and username. This is not part of the wire protocol — there's no envelope for it, and nothing in the app reads it back. See [ADR 0019](decisions/0019-audit-events.md) for why it's backend-only for now and what's deliberately not recorded (the `CREATE_TRADE`/pending step, `SUBSCRIBE`/`UNSUBSCRIBE`, session end).

`LOGIN_SUCCESS` fires from `oauth2Login`'s authentication success handler, and `LOGIN_ERROR` (not `LOGIN_FAILURE`) fires from its failure handler — both with a `null` session id, since no `Session` exists yet at this point in the flow. Neither carries the username `LOGIN_FAILURE` used to (a bcrypt check against a config-based password list, ADR 0016): once Keycloak owns the credential check, this app never observes a raw "wrong password" attempt — only whether the OAuth2 callback itself succeeded or errored (consent denied, code exchange failed, token verification failed, etc.), so `LOGIN_ERROR` records that failure's detail against a fixed `"unknown"` username rather than reusing the old event name for a check that no longer happens here. This is a deliberate, accepted narrowing — see [ADR 0020](decisions/0020-keycloak-oauth2-redis-session.md); Keycloak's own admin console/logs still cover failed password attempts.

## Message envelope

All messages are JSON, wrapped in an envelope with a `type` field. See [ADR 0010](decisions/0010-event-driven-protocol.md) for why the protocol is event-driven and envelope-shaped, and [ADR 0008](decisions/0008-use-raw-websockets.md) for why it rides on raw WebSockets rather than a higher-level messaging framework.

## Server → client

* HELLO — payload: a personalized greeting string (e.g. `"Hello, trader1!"`) built from the connection's Session. Sent once, immediately after a connection authenticates; never broadcast.
* PRICE_TICK
* TRADE_PENDING — payload: a `PendingTrade` (`id`, `symbol`, `side`, `price`, `quantity`, `timestamp`). Sent only to the connection that submitted the `CREATE_TRADE`, in reply to it; never broadcast. See [ADR 0018](decisions/0018-two-step-trade-confirmation.md) for the two-step execution workflow this starts.
* TRADE_CREATED — payload: a `Trade`, with the same `id` as the `PendingTrade` it was confirmed from. Sent in reply to `CONFIRM_TRADE`.
* TRADE_CANCELLED — payload: the cancelled `PendingTrade`. Sent only to the connection that requested the cancellation, in reply to `CANCEL_TRADE`; never broadcast.
* TRADE_REJECTED
* TRADE_HISTORY — payload: an array of `Trade`, oldest first. Sent only to the connection that requested it, in reply to `GET_TRADE_HISTORY`; never broadcast.

## Client → server

* CREATE_TRADE — no longer executes immediately (see ADR 0018): validates and prices the trade, then replies with `TRADE_PENDING` holding it for confirmation. An invalid request still replies with a broadcast `TRADE_REJECTED`, unchanged from before.
* CONFIRM_TRADE — payload `{ "id": "<pending trade id>" }`. Executes a previously requested pending trade, triggering the broadcast `TRADE_CREATED`. An unknown or already-resolved id is a silent no-op.
* CANCEL_TRADE — payload `{ "id": "<pending trade id>" }`. Discards a previously requested pending trade and replies with a targeted `TRADE_CANCELLED`. An unknown or already-resolved id is a silent no-op.
* SUBSCRIBE — payload `{ "symbol": "EUR/USD" }`. Starts delivery of `PRICE_TICK` for that symbol to this connection.
* UNSUBSCRIBE — same payload shape. Stops delivery of `PRICE_TICK` for that symbol to this connection.
* GET_TRADE_HISTORY — no payload. Answered with a `TRADE_HISTORY` envelope.

⸻

## Broadcast semantics

`TRADE_CREATED` and `TRADE_REJECTED` are broadcast to every connected session. A rejection is only meaningful to the session that submitted the trade, so whether `TRADE_REJECTED` (and any future submitter-only event) becomes targeted delivery instead of a broadcast remains a decision for a later issue — `Trade` and `TradeRejected` carry no submitter identity yet, even though a Session now exists to attribute one to (see [ADR 0017](decisions/0017-session-scope.md)).

`PRICE_TICK` is the exception: a connection receives no price ticks at all until it sends `SUBSCRIBE` for a symbol, and stops receiving ticks for a symbol once it sends `UNSUBSCRIBE`. Subscriptions are owned by the connection's `Session` (a `SymbolSubscription`, `com.sdp.market`) rather than being anonymous handler state — but since a Session is 1:1 with its connection (ADR 0017), this is still, in effect, per-connection: subscriptions don't survive a reconnect. See [ADR 0013](decisions/0013-subscription-default-nothing-until-subscribed.md) for why "nothing until subscribed" was chosen over defaulting to broadcast-all-narrowed-by-unsubscribe.

`TRADE_HISTORY`, `TRADE_PENDING`, and `TRADE_CANCELLED` are neither broadcast nor subscription-filtered: each is a direct reply to one connection's own request (`GET_TRADE_HISTORY`, `CREATE_TRADE`, `CANCEL_TRADE` respectively), delivered through that connection's own per-connection sink rather than the shared `EventBus`. Unlike `PRICE_TICK`'s subscription state, this isn't connection-local *filtering* of a shared stream — no other connection ever sees one of these messages that wasn't theirs to begin with. `TRADE_CREATED` (sent in reply to `CONFIRM_TRADE`) is the odd one out: even though confirmation is itself a targeted, one-connection request, its result is still broadcast to everyone, unchanged from before the two-step workflow existed — see [ADR 0018](decisions/0018-two-step-trade-confirmation.md).

⸻

## Endpoint

The Gateway exposes a WebSocket endpoint at `/ws` on port 8080 (Spring Boot default) — one connection carries every event type via the envelope's `type` field, rather than a socket per event type — plus Spring Security's own OAuth2 login endpoints (`/oauth2/authorization/keycloak`, `/login/oauth2/code/keycloak`) on the same port. The Gateway is the only service exposed to the browser (see [ADR 0022](decisions/0022-service-topology.md)); Market Data Service and Backend/Trading Service are reachable only from other containers on the internal Docker network (see [ADR 0021](decisions/0021-rabbitmq-network-segmentation.md)).

The frontend connects directly to the WebSocket URL (no dev-server proxy) via a `VITE_WS_URL` environment variable, defaulting to `ws://localhost:8080/ws` for local `npm run dev` — identity now rides on the session cookie, not a query parameter. This same default also applies when running under Docker Compose: the *browser*, not the frontend container, opens the WebSocket connection, so it needs the Gateway's host-published port rather than a Docker-internal service name. If the connection fails to authenticate, `socket.ts` redirects the browser to `VITE_LOGIN_URL` (defaulting to `http://localhost:8080/oauth2/authorization/keycloak`), for the same host-published-port reason.

⸻

See [ADR 0003](decisions/0003-protocol-addressing-and-test-tooling.md) and [ADR 0006](decisions/0006-hello-world-walking-skeleton.md) for the history behind these addressing decisions.
