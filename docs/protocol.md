# WebSocket protocol

⸻

## Authentication

Before opening the WebSocket connection, the client authenticates via `POST /login` — the app's one HTTP/REST endpoint, alongside the WebSocket protocol below. Request body `{ "username": "...", "password": "..." }`; response body `{ "token": "..." }` on success. See [ADR 0016](decisions/0016-authentication.md) for why this is an HTTP endpoint rather than a WS envelope, and why credentials/tokens are handled this way.

The returned token is passed as a query parameter when opening the WebSocket: `ws://.../ws?token=<token>`. `SdpWebSocketHandler` rejects connections with a missing or invalid token before any envelope is exchanged — there is no unauthenticated HELLO.

## Session

A `Session` is created once a connection's token is validated, pairing the resolved username with that WebSocket connection's own id and owning that connection's market data subscriptions. It is 1:1 with the connection: created on successful authentication, gone when the connection closes, with no reconnect continuity. See [ADR 0017](decisions/0017-session-scope.md) for why, and for what further work (trade attribution, audit events) is expected to build on top of it.

## Message envelope

All messages are JSON, wrapped in an envelope with a `type` field. See [ADR 0010](decisions/0010-event-driven-protocol.md) for why the protocol is event-driven and envelope-shaped, and [ADR 0008](decisions/0008-use-raw-websockets.md) for why it rides on raw WebSockets rather than a higher-level messaging framework.

## Server → client

* HELLO — payload: a personalized greeting string (e.g. `"Hello, trader1!"`) built from the connection's Session. Sent once, immediately after a connection authenticates; never broadcast.
* PRICE_TICK
* TRADE_CREATED
* TRADE_REJECTED
* TRADE_HISTORY — payload: an array of `Trade`, oldest first. Sent only to the connection that requested it, in reply to `GET_TRADE_HISTORY`; never broadcast.

## Client → server

* CREATE_TRADE
* SUBSCRIBE — payload `{ "symbol": "EUR/USD" }`. Starts delivery of `PRICE_TICK` for that symbol to this connection.
* UNSUBSCRIBE — same payload shape. Stops delivery of `PRICE_TICK` for that symbol to this connection.
* GET_TRADE_HISTORY — no payload. Answered with a `TRADE_HISTORY` envelope.

⸻

## Broadcast semantics

`TRADE_CREATED` and `TRADE_REJECTED` are broadcast to every connected session. A rejection is only meaningful to the session that submitted the trade, so whether `TRADE_REJECTED` (and any future submitter-only event) becomes targeted delivery instead of a broadcast remains a decision for a later issue — `Trade` and `TradeRejected` carry no submitter identity yet, even though a Session now exists to attribute one to (see [ADR 0017](decisions/0017-session-scope.md)).

`PRICE_TICK` is the exception: a connection receives no price ticks at all until it sends `SUBSCRIBE` for a symbol, and stops receiving ticks for a symbol once it sends `UNSUBSCRIBE`. Subscriptions are owned by the connection's `Session` (a `SymbolSubscription`, `com.sdp.market`) rather than being anonymous handler state — but since a Session is 1:1 with its connection (ADR 0017), this is still, in effect, per-connection: subscriptions don't survive a reconnect. See [ADR 0013](decisions/0013-subscription-default-nothing-until-subscribed.md) for why "nothing until subscribed" was chosen over defaulting to broadcast-all-narrowed-by-unsubscribe.

`TRADE_HISTORY` is neither broadcast nor subscription-filtered: it's a direct reply to one connection's own request, delivered through that connection's own per-connection sink rather than the shared `EventBus`. Unlike `PRICE_TICK`'s subscription state, this isn't connection-local *filtering* of a shared stream — no other connection ever sees a `TRADE_HISTORY` message that wasn't theirs to begin with.

⸻

## Endpoint

The backend exposes a WebSocket endpoint at `/ws` on port 8080 (Spring Boot default) — one connection carries every event type via the envelope's `type` field, rather than a socket per event type — plus the `POST /login` HTTP endpoint described above, on the same port.

The frontend connects directly to the WebSocket URL (no dev-server proxy) via a `VITE_WS_URL` environment variable, defaulting to `ws://localhost:8080/ws` for local `npm run dev` (the token is appended as a query parameter at connect time, not part of this base URL). This same default also applies when running under Docker Compose: the *browser*, not the frontend container, opens the WebSocket connection, so it needs the backend's host-published port rather than a Docker-internal service name. `POST /login` uses the equivalent HTTP URL (`http://localhost:8080/login` by default) for the same reason.

⸻

See [ADR 0003](decisions/0003-protocol-addressing-and-test-tooling.md) and [ADR 0006](decisions/0006-hello-world-walking-skeleton.md) for the history behind these addressing decisions.
