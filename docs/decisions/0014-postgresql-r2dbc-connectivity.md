# 0014. PostgreSQL/R2DBC connectivity, without pooling or schema yet

Date: 2026-08-06

Status: Accepted

## Context

[roadmap.md](../roadmap.md) already names PostgreSQL and R2DBC as MVP 0.4's technology picks, and [ADR 0002](0002-containerization-and-testcontainers.md) already anticipated both the Docker Compose service and the Testcontainers-based testing strategy for when this landed. What neither recorded: how the backend actually reaches Postgres from its two different run paths (Docker Compose vs. bare `./gradlew bootRun`), and a real risk this issue surfaced while wiring that up — `spring-boot-starter-data-r2dbc` pulls in `r2dbc-pool` transitively, and Spring Boot pools by default whenever that's on the classpath, eagerly opening connections (`spring.r2dbc.pool.initial-size` defaults to 10) at Spring context startup. Issue #21 adds no entity, repository, or schema — nothing queries the database yet, that's #22 — so an eager connection pool would have made the Spring context, and every plain unit test including the existing `contextLoads()`, require a live Postgres to even start. That would have broken CI's plain `./gradlew test` job, which has no database available (Testcontainers isn't adopted until #34, per ADR 0002's explicit deferral).

## Decision

* **Connection config split by run path, via Spring's standard property/environment-variable override**, not profiles: `application.yml` defaults `spring.r2dbc.url` to `r2dbc:postgresql://localhost:5432/sdp` for the "Without Docker" path (`docker compose up -d postgres` for just the database, then `./gradlew bootRun` natively — see the README update in this PR). `docker-compose.yml`'s `backend` service overrides `SPRING_R2DBC_URL`/`_USERNAME`/`_PASSWORD` to point at the Docker-internal `postgres` service hostname instead. This mirrors the existing `VITE_WS_URL` pattern ([ADR 0003](0003-protocol-addressing-and-test-tooling.md)) of "one default, overridden per environment" rather than inventing a second mechanism.
* **Pooling explicitly disabled** (`spring.r2dbc.pool.enabled: false`) until there's an actual query path (#22) that would benefit from it. Revisit then.
* **No schema, entity, or repository code in this issue.** #21 only proves connectivity is wired correctly; the `trades` table design and its migration mechanism (Flyway vs. Spring Boot's `schema.sql` auto-init vs. something else) is left as an open choice for #22, where it'll actually be exercised.
* **No new automated test hitting a real database.** Verified instead by (a) running the full unit suite with no Postgres reachable at all, confirming the Spring context still starts, and (b) live verification via `./startUpDocker.sh` (fresh image, real Postgres container) — backend logs show no connection errors, and existing app functionality (price ticks, trade execution) is unaffected. An automated integration test against a real Postgres is deferred to whichever of #22/#34 needs it — CI has no Postgres available until Testcontainers (#34) lands, so an automated test here would fail in CI today with no way to run it.
* **Postgres image**: `postgres:17-alpine`, pinned by digest, matching the existing convention in `frontend/Dockerfile` and the backend's Jib config (ADR 0005) of pinning every base image by digest for reproducible builds.
* **Local dev credentials** (`sdp`/`sdp`/`sdp` for db/user/password): plain, non-secret defaults for local development only, consistent with CLAUDE.md's "no authentication" MVP scope — this is database access, not application-level auth, and isn't meant to generalize to a real deployment.
* **Restart-recovery groundwork**: a named Docker volume (`postgres-data`) backs the Postgres container so its data survives a container restart, which #23 (application restart recovery) will depend on.

## Consequences

* `./gradlew test` and CI's plain unit-test job still run with zero infrastructure, same as before this issue — adding R2DBC didn't change that guarantee. Confirmed by running the full suite with no Postgres reachable anywhere.
* Whoever picks up #22 must choose a schema-management approach before writing the first migration; this ADR deliberately leaves that decision open rather than presupposing it.
* The `postgres` service is now a required part of `docker-compose.yml`, so the README's "Without Docker" instructions needed a note that this path still needs Postgres reachable somehow — Docker Compose remains a project dependency (per CLAUDE.md's "Local environment" list) even when backend/frontend run natively.
* Re-enabling pooling later (#22, once there's real query load) is a one-line config flip (`spring.r2dbc.pool.enabled: true` plus sizing), not a redesign — the connection URL and credentials don't change.
