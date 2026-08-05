# 0008. Use raw WebSockets over a higher-level messaging framework

Date: 2026-08-05

Status: Accepted

## Context

[protocol.md](../protocol.md) and CLAUDE.md describe a single `/ws` endpoint carrying every event type through a hand-rolled JSON envelope, but no ADR ever recorded *why* this project uses Spring's raw `WebSocketHandler` instead of a higher-level real-time framework — Spring's STOMP-over-WebSocket messaging abstraction (`@MessageMapping`, destinations, an optional broker relay) or a third-party library like Socket.IO. This shapes why routing by envelope `type` happens by hand in `SdpWebSocketHandler` rather than through a framework-provided destination/topic model, and it constrains how future per-session features (MVP 0.5's market data subscriptions, issue #26) get built.

## Decision

Use Spring's raw reactive `WebSocketHandler` (`org.springframework.web.reactive.socket`) directly, with one endpoint (`/ws`) and a hand-rolled `{type, payload}` JSON envelope for every event, rather than adopting STOMP-over-WebSocket or a third-party framework. `SdpWebSocketHandler` composes and routes messages by inspecting the envelope's `type` field itself — there is no broker abstraction, topic/destination model, or subscription registry in the framework layer.

## Consequences

- No extra protocol-layer dependency and full control over the wire format — valuable for a project whose stated goal is understanding every layer, and there's no framework-imposed destination model to work around when it doesn't fit the domain.
- Extending the protocol (a new `type` value) is a plain code change to the envelope handling and both codebases' type definitions — no framework migration, no broker configuration.
- Nothing is provided for free: reconnection, backpressure, acknowledgements, and broadcast/room semantics don't exist unless hand-built. MVP 0.5's per-session market data subscriptions (issue #26) will need to build subscription tracking from scratch rather than configuring a framework's existing primitive for it.
- No interoperability with off-the-shelf STOMP or Socket.IO client libraries — any client of this backend, present or future, must speak this project's specific envelope protocol.
