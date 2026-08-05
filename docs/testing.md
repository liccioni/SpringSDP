# Testing

⸻

## Testing stack

### Backend

* JUnit 5
* Reactor Test (StepVerifier)
* Spring WebFlux Test (WebTestClient)
* Testcontainers — from MVP 0.4 onward, see [ADR 0002](decisions/0002-containerization-and-testcontainers.md)

### Frontend

* Vitest
* React Testing Library
* mock-socket — for simulating the backend WebSocket in integration tests

⸻

## Test levels

Two tiers, for both backend and frontend:

* **Unit tests** — isolated, no Spring context / no real DOM dependencies beyond a component under test. Fast, run on every change.
  * Backend: JUnit 5 tests without the `integration` tag, run via the Gradle `test` task.
  * Frontend: Vitest files matching `*.test.ts(x)`, excluding `*.integration.test.ts(x)`, run via `npm run test:unit`.
* **Integration tests** — exercise real wiring across a boundary (WebFlux/WebSocket endpoints, real component trees).
  * Backend: JUnit 5 tests tagged `@Tag("integration")`, run via the Gradle `integrationTest` task. Tests that need real infrastructure (starting with PostgreSQL from MVP 0.4) use Testcontainers rather than mocks. GitHub Actions' `ubuntu-latest` runners have Docker preinstalled, so this needs no CI workflow changes.
  * Frontend: Vitest files matching `*.integration.test.ts(x)`, run via `npm run test:integration`.

There is no end-to-end tier yet. Add one only when there's a real UI worth exercising that way.

⸻

## Code coverage

Coverage is measured and reported on every PR (Jacoco for the backend, Vitest's `v8` coverage provider for the frontend), and uploaded as a CI artifact. It is not gated — CI does not fail on low coverage. Revisit this once the codebase and test suite are established.
