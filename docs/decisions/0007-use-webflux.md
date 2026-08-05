# 0007. Use Spring WebFlux over Spring MVC for the backend

Date: 2026-08-05

Status: Accepted

## Context

CLAUDE.md and [architecture.md](../architecture.md) name Spring WebFlux as the backend stack and say reactive programming should be used "at the edges" (WebSocket communication, streaming market data, async event processing), but no ADR ever recorded the decision itself — it was assumed from the project's outset (issue #1, backend scaffolding) rather than chosen against an alternative. Worth recording now because it's a foundational choice: it shapes the entire backend's threading and concurrency model, which test tooling applies (`StepVerifier`, per [ADR 0003](0003-protocol-addressing-and-test-tooling.md)), and how cleanly MVP 0.3's "reactive architecture" milestone and MVP 0.4's R2DBC persistence build on top of what exists rather than requiring a rewrite.

## Decision

Use Spring WebFlux, not Spring MVC, for the entire backend — not just the WebSocket layer. Reactor (`Flux`/`Mono`) is the concurrency model throughout: `MarketDataService.priceTicks()` is a `Flux<PriceTick>` driven by `Flux.interval`, `TradeService.tradeCreated()` is backed by a `Sinks.Many`, and `SdpWebSocketHandler` composes both into the WebSocket session's outbound stream. This matches CLAUDE.md's "reactive at the edges" principle and keeps a single, consistent non-blocking model end-to-end rather than mixing WebFlux for the WebSocket layer with blocking Spring MVC controllers elsewhere.

## Consequences

- A natural, unforced path to MVP 0.4's R2DBC persistence: the backend is already all-reactive, so adding a reactive database driver doesn't require bridging a blocking data-access layer into reactive request handling.
- Test tooling follows suit: Reactor Test's `StepVerifier` for reactive unit tests, `ReactorNettyWebSocketClient` for WebSocket integration tests (`WebTestClient` itself can't assert on WebSocket messages, as noted in [ADR 0006](0006-hello-world-walking-skeleton.md)).
- If a future feature needs a library with no reactive equivalent (a blocking SDK, a legacy client), it can't just be called inline — it needs an explicit bounded-elastic scheduler hop, which is more ceremony than a blocking-everywhere codebase would need.
- Reactive code has a steeper learning curve and a less direct debugging experience (stack traces span operator chains, not a single call stack) than imperative Spring MVC code — a real cost for a project whose CLAUDE.md also asks for "readability over cleverness."
