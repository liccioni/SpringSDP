# WebSocket protocol

⸻

## Message envelope

All messages are JSON, wrapped in an envelope with a `type` field.

## Server → client

* PRICE_TICK
* TRADE_CREATED

## Client → server

* CREATE_TRADE

⸻

## Endpoint

The backend exposes a single WebSocket endpoint at `/ws` on port 8080 (Spring Boot default) — one connection carries every event type via the envelope's `type` field, rather than a socket per event type.

The frontend connects directly to this URL (no dev-server proxy) via a `VITE_WS_URL` environment variable, defaulting to `ws://localhost:8080/ws` for local `npm run dev`. This same default also applies when running under Docker Compose: the *browser*, not the frontend container, opens the WebSocket connection, so it needs the backend's host-published port rather than a Docker-internal service name.

⸻

See [ADR 0003](decisions/0003-protocol-addressing-and-test-tooling.md) and [ADR 0006](decisions/0006-hello-world-walking-skeleton.md) for the history behind these addressing decisions.
