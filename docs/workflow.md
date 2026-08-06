# Developer workflow

⸻

## Issues

Issues should be:

* small
* focused
* independently completable

Each issue should belong to a milestone.

Large features should be decomposed into implementation tasks.

⸻

## Branching

The project uses GitHub Flow. `main` is always deployable.

* Every change happens on a short-lived branch off `main`.
* No `develop`, `release/*`, or `hotfix/*` branches.
* Branch names are tied to the GitHub issue they implement:
  `feature/<issue#>-slug`, `fix/<issue#>-slug`, `docs/<issue#>-slug`, `chore/<issue#>-slug`.

⸻

## Commits

Use lightweight Conventional Commit prefixes: `feat:`, `fix:`, `docs:`, `chore:`, `test:`.

This is a convention, not tool-enforced.

⸻

## Pull requests

Every change, including changes to CLAUDE.md, goes through a pull request into `main`.

* Reference the issue being closed (`Closes #N`).
* Fill in the PR template checklist (tests added, docs updated if architecture changed).
* Required CI checks must be green before merge.
* No mandatory human approval while the project has a single maintainer. Self-merge once CI passes.
* Squash merge only. The source branch is deleted on merge.
* If issue N+1 depends on issue N's still-unmerged code, it's fine to branch N+1 off N's branch rather than `main` — but once N is squash-merged, N+1's base retarget to `main` will show a conflict, since the squash commit shares no history with N's original commits. Fix it with `git rebase origin/main` on N+1's branch (git recognizes the content as already upstream and drops those commits automatically) and force-push, rather than trying to resolve the conflict by hand.
* `gh pr edit` (e.g. `--base`, `--body`) on this repo sometimes surfaces `GraphQL: Projects (classic) is being deprecated...` — this is unrelated to the edit and can be ignored; the edit still goes through. Verify with `gh pr view <N>` if in doubt.

⸻

## Continuous integration

GitHub Actions runs a single `CI` workflow (`.github/workflows/ci.yml`) on every pull request and on push to `main`.

* A `detect` job checks whether `backend/` or `frontend/` exist yet. Until they do, the `backend` and `frontend` jobs report as skipped (passing), so CI stays green during early scaffolding.
* The `backend` job runs `./gradlew test`, `./gradlew integrationTest`, and `./gradlew jacocoTestReport`.
* The `frontend` job runs `npm run test:unit`, `npm run test:integration`, `npm run build`, and `npm run test:coverage`.

Any change that scaffolds the backend or frontend must provide these exact Gradle tasks / npm scripts so CI keeps working without further changes to the workflow file.

See [Testing](testing.md) for what these tasks and scripts mean and how coverage is handled.

⸻

## Claude Code skills

Two Claude Code skills in `.claude/skills/` automate this workflow rather than duplicating it:

* `ship-issue` — carries a GitHub issue through branch → implement → test → commit → PR → CI, per this document, [Testing](testing.md), and [Code style](code-style.md).
* `write-adr` — writes a new entry in `docs/decisions/` from `template.md`, numbered and cross-linked consistently with the existing records.

Both skills read the docs rather than restate them, so they can't drift out of sync with this file.
