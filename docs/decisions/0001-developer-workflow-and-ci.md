# 0001. Developer workflow and continuous integration

Date: 2026-08-05

Status: Accepted

## Context

The project had a roadmap and domain model (CLAUDE.md) and GitHub milestones/issues, but no defined branching model, pull request process, or CI pipeline. Before MVP 0.1 implementation issues land, we need a lightweight but real workflow so changes are reviewable, mergeable safely, and verified automatically — without adding process overhead disproportionate to a solo-maintained, pre-code project.

## Decision

* **Branching**: GitHub Flow. `main` is always deployable; every change happens on a short-lived branch (`feature/`, `fix/`, `docs/`, `chore/`, each suffixed with the issue number and a slug). No `develop`/`release`/`hotfix` branches.
* **Pull requests**: All changes, including changes to CLAUDE.md itself, go through a PR into `main`. No mandatory human approval while there is a single maintainer — self-merge is allowed once required CI checks pass. Squash merge only, source branch deleted on merge.
* **Continuous integration**: A single GitHub Actions workflow (`.github/workflows/ci.yml`) runs on every PR and on push to `main`. It detects whether `backend/` or `frontend/` exist yet and skips (passes) the corresponding job until they do, so CI stays green through early scaffolding. Once present:
  * Backend: `./gradlew test` (unit), `./gradlew integrationTest` (integration), `./gradlew jacocoTestReport` (coverage).
  * Frontend: `npm run test:unit`, `npm run test:integration`, `npm run build`, `npm run test:coverage`.
* **Test levels**: Unit and integration tiers only, for both backend and frontend. Backend integration tests are tagged `@Tag("integration")` and run as a separate Gradle task; frontend integration tests are named `*.integration.test.ts(x)` and run via a separate Vitest invocation. No end-to-end tier yet.
* **Code coverage**: Measured and reported (Jacoco / Vitest v8 coverage) and uploaded as a CI artifact on every run, but not gated. CI does not fail on low coverage.

## Consequences

* Future MVP 0.1 issues that scaffold `backend/` and `frontend/` must implement the exact Gradle tasks / npm scripts above, or CI will fail rather than skip.
* Because there's no required review, code quality relies on CI checks and the maintainer's own discipline — revisit required-approval count when a second contributor joins.
* Coverage thresholds are not enforced yet; this should be revisited once the test suite is established enough to set a meaningful baseline.
* The workflow is intentionally text-file-based (this ADR, CLAUDE.md, the CI YAML) rather than relying on GitHub UI configuration alone, so it survives repo migrations and is visible in code review.
