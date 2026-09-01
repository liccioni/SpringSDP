# 0030. Messaging transport swappability: stay on Spring Cloud Stream's groundwork, don't add a second binder yet

Date: 2026-09-01

Status: Accepted

## Context

[ADR 0012](0012-in-process-event-bus.md) introduced the in-process `EventBus`
specifically as groundwork for "swapping the in-process EventBus for a real
broker later, with minimal protocol change," and [ADR 0022](0022-service-topology.md)
cashed that bet in by picking Spring Cloud Stream + RabbitMQ for the
inter-service transport (#89-#94), explicitly choosing a correlation-id-based
async request/reply pattern over `RabbitTemplate`'s synchronous RPC "to keep
the app broker-agnostic, per Spring Cloud Stream's design intent - swapping
the binder later wouldn't mean swapping this interaction pattern." Neither
ADR ever exercised that claim against a second broker - issue #119 asked
directly what it would actually take, since the coupling to RabbitMQ
specifically is real, just not where the wire protocol lives:

* `gateway`, `market-data-service`, and `trading-service` each depend
  directly on `spring-cloud-starter-stream-rabbit:5.0.2` (pinned outside the
  BOM - see ADR 0022's "Update (issue #90)" for why).
* Every `application.yml` carries RabbitMQ-specific binder config
  (`spring.cloud.stream.rabbit.bindings.*.{consumer,producer}.exchangeType:
  fanout`, one block per binding) and `spring.rabbitmq.host`/`port`.
* `docker-compose.yml` runs a `rabbitmq` service and sets
  `SPRING_RABBITMQ_HOST` on all three services.

Against that, `contracts/` is confirmed broker-agnostic in practice, not just
by intent - a plain `java-library` module with zero Spring/AMQP dependency,
holding only wire-shape records. And every message flow in the app today is
either a plain fanout broadcast with no consumer group (single-instance
assumed, per ADR 0022) or the correlation-id request/reply pair described
above - no flow anywhere relies on an AMQP-specific feature (`RabbitTemplate`
RPC, exchange routing keys, etc.) that only RabbitMQ can express.

## Decision

**Confirm the technical premise, don't act on it yet.** Spring Cloud Stream
does allow a broker choice to become genuinely runtime-configurable without
app code caring, and the mechanism is more specific than "it's supposedly
broker-agnostic":

* Multiple binders can coexist in **one build artifact** - both
  `spring-cloud-starter-stream-rabbit` and `spring-cloud-starter-stream-kafka`
  on the classpath at once, with `spring.cloud.stream.defaultBinder` (or a
  per-binding `spring.cloud.stream.bindings.<name>.binder` override) picking
  which one an environment actually uses. No separate build per broker is
  needed - this project's existing single-jar-per-service packaging
  (ADR 0005's Jib approach) would not need to change shape to support it.
* Kafka's semantic equivalent of "no consumer group" fanout is an anonymous
  consumer group: a binding with no explicit `group` property gets its own
  independent, ephemeral consumer group in a publish-subscribe relationship
  with every other group on that topic - the same "every subscriber gets its
  own copy, nothing persists past a live consumer" shape this app's fanout
  exchanges already assume (ADR 0022's single-instance framing). This is a
  reasonable match, not an identical one: Kafka topics need to exist
  (auto-creatable, but a real config knob) and anonymous bindings default to
  the `latest` offset, so there's no message replay after a restart - broadly
  consistent with today's RabbitMQ auto-delete queues, not a guarantee this
  ADR is claiming they're interchangeable in every respect.
* What does **not** carry over: `spring.cloud.stream.rabbit.bindings.*` and
  a hypothetical `spring.cloud.stream.kafka.bindings.*` are separate,
  non-overlapping configuration namespaces - RabbitMQ's `exchangeType` has no
  Kafka equivalent at all (Kafka has no exchanges), and Kafka's own
  broker-specific knobs (partition count, replication factor) have no
  RabbitMQ equivalent either. Supporting both brokers means maintaining two
  parallel binder-config blocks per service, indefinitely - not one
  translatable setting.

**Don't add a second binder now.** Doing so today would mean, in every one of
the three services: a new dependency, a parallel Kafka config block
alongside the existing RabbitMQ one, a Kafka container added to
`docker-compose.yml`, and Kafka-flavored Testcontainers integration tests to
actually prove the second path works rather than merely compiles (per
`docs/testing.md`'s own standard for anything claiming to cross a real
boundary) - ongoing maintenance cost for a capability with no current
consumer. No concrete need exists: this project runs as a single Docker
Compose deployment, and #121 (cloud deployment options) remains deliberately
unscheduled with no direction picked yet. This is CLAUDE.md's Core
philosophy applied directly - "add infrastructure only when a concrete
milestone need justifies it," not preemptively.

This ADR is itself the artifact that makes a future revisit cheap: the
premise is now verified rather than assumed, and the shape of the work (add
the starter, add the Kafka bindings block, add the container, add the
integration tests, decide `defaultBinder` per environment) is written down
so a future concrete need doesn't start from scratch.

## Consequences

* Issue #119 closes with no code or config change. `gateway`,
  `market-data-service`, and `trading-service` keep their single-binder
  RabbitMQ dependency and config exactly as they are today.
* The broker-agnostic parts of the design - `contracts/`'s plain records and
  the correlation-id request/reply pattern - need no changes either; they
  already satisfy what a future Kafka migration would actually require of
  application code. Nothing about this decision asks anyone to keep that
  discipline "just in case" going forward - it's already how the code is
  written, for reasons independent of this ADR.
* A future implementation, once a concrete need exists, has a checklist
  rather than a blank page: add `spring-cloud-starter-stream-kafka` per
  service, add a `spring.cloud.stream.kafka.bindings.*` block per binding
  (dropping `exchangeType`, which has no Kafka meaning), add a Kafka broker
  to `docker-compose.yml`, add Kafka-backed integration tests alongside the
  existing `RabbitMqIntegrationTest` pattern, and decide `defaultBinder`
  per environment.
* [docs/roadmap.md](../roadmap.md)'s "What's next" backlog listing is
  updated in the same PR to drop #119 from the unscheduled list and note how
  it was resolved, so the roadmap doesn't keep advertising a closed issue as
  still-open backlog.
