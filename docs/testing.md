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

⸻

## Known gotchas

* **mock-socket state can leak across tests in the same file.** Sequential tests that each create their own `Server`/`WebSocket` mock against the same URL can interfere with each other if they reuse identical fixture data (the same symbol *and* the same price/payload) — a later test can end up observing a message sent by an earlier test's server. This isn't a bug to work around with extra synchronization; give each test in a file distinct fixture values instead of realistic-looking shared constants, and the tests stay independent.
* **Rendered components don't unmount between frontend tests.** Vitest's `globals` option is off (see `vite.config.ts`), so React Testing Library's automatic `afterEach(cleanup)` registration never fires — it only self-registers when it detects a global `afterEach`. Every earlier test's `render()` output stays in the DOM for the rest of that file. This was invisible until a component introduced a stable `id`/label (`PriceGrid`'s quantity input): a later test's `getByLabelText` silently resolved to an *earlier* test's stale element instead of its own render, because duplicate ids all point at the first match. `src/setupTests.ts` now explicitly imports `afterEach` from `vitest` and calls Testing Library's `cleanup()` — don't remove it, and don't rely on per-file `afterEach(cleanup)` calls instead, since the global one already covers every file.
* **`Sinks.Many#tryEmitNext` requires the caller to serialize concurrent producers, and fails silently if you don't.** Reactor's contract is that `onNext`-equivalent signals must not be concurrent; a `Sinks.Many` doesn't enforce this for you, it detects the violation and returns `FAIL_NON_SERIALIZED` from `tryEmitNext` — which is easy to never notice, since nothing throws unless you check the returned `EmitResult`. `EventBus` had exactly this bug from MVP 0.1 onward (fixed in issue #20): `MarketDataService` publishes from its own ticker thread while `TradeService` publishes from whichever WebSocket connection thread handled a `CREATE_TRADE`, so concurrent `publish()` calls were the normal case, not an edge case, and events were being dropped under real concurrent load. A single-threaded test suite won't catch this — proving it (and proving a fix) needs a test that actually races multiple threads against the sink, e.g. an `ExecutorService` with N producer threads gated by a `CountDownLatch`, publishing simultaneously, then asserting every event arrived (see `EventBusTest`). Any new `Sinks`-backed state fed from more than one thread needs either the same external-synchronization treatment or this style of test proving it doesn't need it.
