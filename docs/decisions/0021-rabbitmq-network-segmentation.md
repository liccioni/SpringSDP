# 0021. RabbitMQ + Docker Compose network segmentation

Date: 2026-08-10

Status: Accepted

## Context

MVP 0.7 splits the monolith into a WebSocket Gateway, a Market Data service, and a Backend/Trading service, connected via RabbitMQ (#89-#93). Before any of that splitting happens, this issue (#88) has to decide the trust boundary those services will share once they're separate containers talking to each other over a network instead of Java method calls: do internal services verify each message's caller (e.g. propagating the Keycloak JWT through message headers), or do they trust anything reachable on their network segment?

Left undecided, whichever future issue scaffolds the Gateway first would end up inventing this ad hoc.

## Decision

* **Network segmentation is the trust boundary, not per-message authentication.** A service reachable only on the `internal` Docker network is trusted by its peers there; nothing revalidates a Keycloak JWT per RabbitMQ message. Propagating the JWT through message headers for defense-in-depth was discussed and explicitly parked, not decided against — revisit if a concrete need arises (e.g. a security review, or actually running this outside a single trusted Docker host).
* **Two Compose networks: `public` and `internal`.** `public` carries the WebSocket Gateway (today's monolithic `backend`, until #89 splits it out) and Keycloak (needed for the browser-driven OAuth2 redirect) — both reachable from the host. `internal` carries Postgres, Redis, RabbitMQ, and the future Market Data/Backend services. `backend` sits on both networks for now, since it's standing in for a Gateway that doesn't exist as a separate container yet.
* **`internal` is a plain Compose network, not `internal: true`.** That flag looked like the obvious choice, but it does more than block a container's outbound route to other networks: Compose disables host port publishing *entirely* for any service solely attached to an `internal: true` network, confirmed empirically while implementing this (every `ports:` entry on such a service is silently dropped — `docker compose ps`/`docker port` show no host mapping at all, regardless of what `docker-compose.yml` says). That's exactly wrong for two of this file's own services (see below), so "internal" here is a naming convention this file's services follow — no `ports:` entry, reached only by other containers on this network — rather than something Compose enforces structurally.
* **RabbitMQ's AMQP port (5672) is not published; its management UI (15672) is**, for the same local-debugging convenience this project already extends to Keycloak's own admin console. Nothing outside this Compose network speaks AMQP yet, and once something does (#90+), it'll be one of this project's own internal services, reached over the `internal` network rather than the host.
* **Postgres and Redis keep their host-published ports (5432, 6379), as an explicit, narrower exception to "internal services have no host-published ports."** Removing these two specific published ports would break `README.md`'s "Without Docker" native dev path (`./gradlew bootRun` reaching Postgres/Redis as host processes, with no other route to them) — a real, currently-documented workflow, fixed as recently as MVP 0.6's own retro after it silently broke once before (see [retro 0006](../retros/0006-mvp-0.6.md)). The native dev path isn't the threat model this segmentation protects against (only the developer's own machine can reach a Docker-published `localhost` port); that threat model is inter-container trust once services actually run as separate, independently-deployed processes — achieved here simply by *not* publishing a port, the same mechanism (not a network-level one) that keeps RabbitMQ's AMQP port and the future Market Data/Backend services unreachable from the host too.

## Consequences

* Once #89 scaffolds the Gateway, Market Data, and Backend services as their own containers, only the actual Gateway joins `public`; Market Data and Backend join `internal` only, with no published ports at all — `backend`'s current dual-network membership is a temporary stand-in, not the end state.
* A compromised or misconfigured container on the `internal` network can publish or consume any message on any exchange/queue — there's no per-message identity check to stop it. Acceptable for this project's scale and single-Docker-host deployment model, per the issue's own explicit parking of the JWT-propagation alternative.
* Postgres and Redis remain the two exceptions to "internal means no host-published ports" — a deliberate, narrower carve-out for developer convenience, not an oversight. If the native "Without Docker" dev path is ever retired, these two `ports:` entries should go with it.
* `docker network inspect` on the `internal` network shows no host-published ports for RabbitMQ or the (future) Market Data/Backend services, satisfying this issue's own verification criterion for everything except the two named, documented exceptions above.
