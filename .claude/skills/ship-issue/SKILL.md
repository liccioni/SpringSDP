---
name: ship-issue
description: Implement a GitHub issue for this repo end-to-end following the project's GitHub Flow — branch, implement, test, commit, PR, wait for CI. Use when asked to "work on issue #N", "implement issue N", "pick up the next task", "start #N", or "ship #N". Also use for "what's the next task" style questions to pick the right issue first.
---

# ship-issue

Carries one GitHub issue through this repo's full workflow, as defined in
[docs/workflow.md](../../docs/workflow.md), [docs/testing.md](../../docs/testing.md), and
[docs/code-style.md](../../docs/code-style.md). Read those three files at the
start of every run — they are the source of truth; this skill only encodes the
*sequence*, not the conventions themselves, so it can't drift out of sync with
them.

## 1. Pick the issue

If no issue number was given, find the right one instead of guessing:

```sh
gh issue list --state open --json number,title,milestone
```

Pick the lowest-numbered open issue in the earliest open milestone, unless its
body says it depends on another still-open issue — check `gh issue view <N>
--json body` for prerequisites called out in the text (this project doesn't
use GitHub's native issue-dependency links). If two candidate issues are
genuinely independent and it's not obvious which the user wants, ask.

## 2. Read the issue and its context

```sh
gh issue view <N> --json number,title,body,milestone
```

Skim the relevant existing code (`Explore` agent for anything non-trivial)
before writing anything — don't assume the issue body is exhaustive about
what already exists.

**Flag domain-shaping decisions before implementing them.** If the issue
leaves an ambiguous choice with a real tradeoff — a data type, a protocol
shape, anything that's costly to unwind once other code builds on it — ask
the user rather than defaulting silently. (This bit us on issue #3: `double`
vs `BigDecimal` for money fields.) Routine choices with an obvious answer
(matching an existing pattern already in the codebase) don't need this.

## 3. Branch

Off an up-to-date `main`:

```sh
git checkout main && git pull --ff-only
git checkout -b <type>/<N>-<slug>
```

`<type>` is `feature`, `fix`, `docs`, or `chore` — infer from the issue title,
default to `feature`. `<slug>` is a short kebab-case summary of the title.

## 4. Implement

- Follow [docs/code-style.md](../../docs/code-style.md): explicit code over
  clever code, no unnecessary abstraction, trading terminology for names
  (`TradeBlotter`, not `DataService`/`Manager`/`Utils`).
- Follow [docs/architecture.md](../../docs/architecture.md)'s separation of
  concerns: market data doesn't create trades, trading doesn't generate
  prices, WebSocket handling doesn't contain business logic.
- Keep the issue's scope — don't bundle unrelated cleanup or fixes into the
  same branch.

## 5. Test

Per [docs/testing.md](../../docs/testing.md): unit tests by default (backend:
JUnit 5 + StepVerifier for reactive code, no `@Tag("integration")`; frontend:
`*.test.tsx` via Vitest). Only add an integration test when the change
genuinely crosses a boundary (WebSocket endpoint, real component tree).

Run the relevant suite locally before committing — don't rely on CI to find
a broken test for the first time:

```sh
./gradlew test                 # backend unit
./gradlew integrationTest      # backend integration, if applicable
npm run test:unit --prefix frontend
npm run test:integration --prefix frontend   # if applicable
```

## 6. Commit

Conventional Commit prefix (`feat:`, `fix:`, `docs:`, `chore:`, `test:`), one
logical change per commit, trailer referencing the issue:

```
<prefix>: <summary>

<optional body explaining why>

Closes #<N>
```

## 7. Push and open the PR

```sh
git push -u origin <branch>
gh pr create --title "<prefix>: <summary>" --body "<filled-in .github/pull_request_template.md>"
```

Fill in the PR template honestly — check the boxes that are actually true,
leave the rest unchecked rather than pre-checking everything.

## 8. Wait for CI, then stop and report

```sh
gh pr checks <PR#>
```

If checks are still running, arm a `Monitor` loop against `gh pr checks`
rather than polling with sleeps, and keep working on other things in the
meantime.

**Do not merge automatically.** Merging into `main` is a shared, hard-to-undo
action — per this project's own workflow doc, self-merge is *allowed* once CI
is green, not that it should happen unprompted. Report that CI passed and
merge only when the user asks for this PR (up front, or once told "merge once
CI passes" for it specifically — that authorization is per-PR, not standing
across future issues).
