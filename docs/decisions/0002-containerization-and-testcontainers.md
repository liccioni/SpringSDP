# 0002. Containerization and Testcontainers

Date: 2026-08-05

Status: Partially superseded by [ADR 0005](0005-jib-for-backend-image.md) — the backend now uses Jib instead of a hand-written Dockerfile. The Docker Compose and frontend-Dockerfile decisions below are still accepted as originally written.

## Context

The app currently has no defined way to run backend and frontend consistently across machines, and no plan for how backend integration tests will handle real infrastructure once it's introduced (PostgreSQL in MVP 0.4). We want both requirements captured now, ahead of the MVP 0.1 scaffolding and MVP 0.4 persistence work, so those issues are built against the requirement from the start rather than retrofitted.

## Decision

* **Docker & Docker Compose**: the backend and frontend each get a `Dockerfile` (multi-stage: build stage → slim runtime stage), and a root-level `docker-compose.yml` runs both together for local/dev use. This is packaging and a consistent run experience, not a move toward microservices or additional production infrastructure — it doesn't relax the "no Kafka / no Redis / no database / no microservices" constraints in CLAUDE.md's core philosophy for MVP 0.1.
* **Testcontainers**: backend integration tests that need real infrastructure use Testcontainers-managed containers instead of mocks, starting with PostgreSQL once it's introduced in MVP 0.4. Until then there's no infrastructure to test against, so no immediate implementation is needed.
* No changes to the CI workflow (`.github/workflows/ci.yml`) are required to support Testcontainers: GitHub Actions' `ubuntu-latest` runners have Docker preinstalled, and `./gradlew integrationTest` will be able to start containers as-is.

## Consequences

* Docker becomes a required local dev-time dependency (in addition to Java 21 and Node).
* The backend/frontend `Dockerfile`s and `docker-compose.yml` are added alongside the MVP 0.1 scaffolding issues, once there's actual code to containerize — not before.
* The Testcontainers adoption is scoped to MVP 0.4 (Persistence), tracked as its own issue rather than folded silently into the PostgreSQL/R2DBC work.
