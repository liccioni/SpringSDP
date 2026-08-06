# WebSocket protocol

⸻

## Message envelope

All messages are JSON, wrapped in an envelope with a `type` field. See [ADR 0010](decisions/0010-event-driven-protocol.md) for why the protocol is event-driven and envelope-shaped, and [ADR 0008](decisions/0008-use-raw-websockets.md) for why it rides on raw WebSockets rather than a higher-level messaging framework.

## Server → client

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

`TRADE_CREATED` and `TRADE_REJECTED` are broadcast to every connected session — there's no per-session concept yet (that lands in MVP 0.5, see [roadmap.md](roadmap.md)). A rejection is only meaningful to the session that submitted the trade, so once sessions exist, whether `TRADE_REJECTED` (and any future submitter-only event) becomes targeted delivery instead of a broadcast is a decision to make then, not assumed here.

`PRICE_TICK` is the exception: a connection receives no price ticks at all until it sends `SUBSCRIBE` for a symbol, and stops receiving ticks for a symbol once it sends `UNSUBSCRIBE`. This is connection-local filtering inside `SdpWebSocketHandler`, not the session concept referenced above — there's no session identity, registry, or addressing from outside the connection's own `handle()` call, and subscriptions don't survive a reconnect. See [ADR 0013](decisions/0013-subscription-default-nothing-until-subscribed.md) for why "nothing until subscribed" was chosen over defaulting to broadcast-all-narrowed-by-unsubscribe.

`TRADE_HISTORY` is neither broadcast nor subscription-filtered: it's a direct reply to one connection's own request, delivered through that connection's own per-connection sink rather than the shared `EventBus`. Unlike `PRICE_TICK`'s subscription state, this isn't connection-local *filtering* of a shared stream — no other connection ever sees a `TRADE_HISTORY` message that wasn't theirs to begin with.

⸻

## Endpoint

The backend exposes a single WebSocket endpoint at `/ws` on port 8080 (Spring Boot default) — one connection carries every event type via the envelope's `type` field, rather than a socket per event type.

The frontend connects directly to this URL (no dev-server proxy) via a `VITE_WS_URL` environment variable, defaulting to `ws://localhost:8080/ws` for local `npm run dev`. This same default also applies when running under Docker Compose: the *browser*, not the frontend container, opens the WebSocket connection, so it needs the backend's host-published port rather than a Docker-internal service name.

⸻

See [ADR 0003](decisions/0003-protocol-addressing-and-test-tooling.md) and [ADR 0006](decisions/0006-hello-world-walking-skeleton.md) for the history behind these addressing decisions.
