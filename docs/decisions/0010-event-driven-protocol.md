# 0010. Use an event-driven, envelope-based protocol

Date: 2026-08-05

Status: Accepted

## Context

CLAUDE.md's "Event-driven communication" principle and [protocol.md](../protocol.md) establish that all WebSocket traffic is modeled as named events (`PRICE_TICK`, `CREATE_TRADE`, `TRADE_CREATED`) wrapped in a `{type, payload}` envelope, but no ADR ever recorded the decision itself — only the addressing details that follow from it ([ADR 0003](0003-protocol-addressing-and-test-tooling.md)) and an implementation walkthrough ([ADR 0006](0006-hello-world-walking-skeleton.md)). Worth its own record because this shape is what every future feature's wire format builds on, and CLAUDE.md makes a specific forward-looking claim — that this allows future integration with Redis Pub/Sub or Kafka "with minimal protocol changes" — that deserves its reasoning on record rather than asserted in passing.

## Decision

Model all cross-boundary communication as named events carried in a uniform envelope of `{type: string, payload: object}`, rather than a REST-style request/response API, an RPC-style method-call protocol, or a separate socket/endpoint per message type ([ADR 0008](0008-use-raw-websockets.md) covers the transport choice this rides on). A new event type is added by extending the `type` enumeration and its payload shape — never by changing the transport, connection model, or adding a new endpoint.

## Consequences

- One WebSocket connection carries every event type (per ADR 0003), so a new event type never needs a new endpoint or a client reconnect.
- The envelope shape maps directly onto a future message broker's topic-plus-payload model (a Kafka topic or Redis Pub/Sub channel per event type), so CLAUDE.md's "minimal protocol changes" claim for eventual Kafka/Redis adoption is structurally true now, not just aspirational.
- WebSocket handling stays free of business logic (per [architecture.md](../architecture.md)'s separation of concerns): `SdpWebSocketHandler` only switches on `type` and delegates, it doesn't embed RPC-style logic per message.
- There is no compile-time contract between client and server for a given `type`'s payload shape, unlike a typed RPC layer (gRPC, tRPC). Payload shape drift between the frontend's TypeScript types and the backend's Java records is only caught by tests or manual review.
- Every new event type is a manual, duplicated addition — to `protocol.md`, to the backend's envelope-handling code, and to the frontend's type definitions — with no schema-generation tooling to keep them in sync.
