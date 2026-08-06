---
name: milestone-retro
description: Close out a completed GitHub milestone — verify it's actually done, audit docs/ADRs for drift, write a retrospective, and flag new skill candidates. Use when asked to "close out MVP N", "is mvp N really done", "run the milestone retro", or once the last issue in a milestone merges.
---

# milestone-retro

Runs once a GitHub milestone's issues are all closed, to catch what
issue-by-issue work misses: whether the milestone's promised story actually
works end to end, whether decisions made along the way got written down,
and whether current-state docs still agree with reality. Reads
[docs/workflow.md](../../docs/workflow.md), [docs/testing.md](../../docs/testing.md),
[docs/roadmap.md](../../docs/roadmap.md), and `CLAUDE.md` rather than
restating them — this skill only encodes the *sequence*.

## 1. Confirm the milestone is closed

```sh
gh api repos/{owner}/{repo}/milestones/<N> --jq '"\(.open_issues) open, \(.closed_issues) closed"'
```

If issues are still open, stop and say so — don't run the rest of this
against a milestone that isn't actually finished.

## 2. Re-verify from a clean `main`, not the closed-issue count

Closed issues and green CI on individual PRs don't prove the milestone's
*promised story* works once everything is merged together:

```sh
git checkout main && git pull --ff-only
./gradlew clean test integrationTest   # backend, fresh, no cache
npm run test:unit --prefix frontend && npm run test:integration --prefix frontend
```

Then live-walk the exact user story in this milestone's
[docs/roadmap.md](../../docs/roadmap.md) heading in a real browser, through
**every way of running the app the README documents** — not just whichever
one is fastest to iterate on locally. This project's README currently
documents two (local dev servers, and Docker Compose); verifying only the
former is exactly how a stale, silently-reused Docker image serving an old
frontend bundle went unnoticed through an entire retro (see the postscript on
[retro 0001](../../docs/retros/0001-mvp-0.1.md)). Use the `claude-in-chrome`
tools to perform the actual steps a user would, not just exercise the
automated coverage. If anything in the story doesn't hold up on any
documented path, that's a real gap — fix it before writing a retrospective
that claims otherwise.

## 3. Audit ADR coverage

For each issue/PR merged in this milestone, ask: did it make a real
tech/protocol/domain decision — a choice a reasonable person could have made
differently, with consequences that outlive the PR? Check whether
[docs/decisions/](../../docs/decisions/) already has an entry for it. This
skill only does the *discovery*; invoke `write-adr` to actually write each
gap found. Don't assume an issue being "just" a domain-model or protocol
change means no decision was made — check commit messages and PR bodies for
reasoning that was recorded informally but never promoted to an ADR.

## 4. Mine merged PRs for undocumented lessons

```sh
gh pr list --state merged --search "milestone:\"<milestone title>\""
```

Skim bodies and commit messages for gotchas, bugs, or workarounds that
aren't yet reflected anywhere durable. If a lesson only lives in a PR
description, fold it into the right doc: [docs/testing.md](../../docs/testing.md)
for testing gotchas, [docs/code-style.md](../../docs/code-style.md) for
conventions, or a new ADR (via `write-adr`) for an actual decision.

## 5. Check for doc/pointer drift

Compare `CLAUDE.md`'s "Current milestone" section against
[docs/roadmap.md](../../docs/roadmap.md). If the milestone that's now done
is still named as current, update both — advance `CLAUDE.md`'s pointer to
the next milestone, and mark the completed one done in roadmap.md — through
the normal branch + PR flow per
[docs/workflow.md](../../docs/workflow.md)'s rule that even CLAUDE.md
changes go through a PR. Never self-merge without being asked, same
boundary as `ship-issue` step 8.

## 6. Write the retrospective

Number it like an ADR:

```sh
ls docs/retros/*.md | grep -oE '[0-9]{4}' | sort -n | tail -1
```

Next number is that plus one, zero-padded to 4 digits. Write
`docs/retros/NNNN-slug.md` from `docs/retros/template.md`, covering: what
shipped, what was verified and how, bugs/gotchas found and fixed, deviations
from plan, which docs/artifacts got updated, and any new skill candidates
(see step 7). Persist this — don't just summarize in chat. The point of this
skill is that lessons stop living only in a PR description or a person's
memory.

## 7. Surface, never auto-create, new skill candidates

List any manual behavior that recurred across this milestone's issues but
isn't codified in an existing skill, as a suggestion in the retrospective's
own section for it. Whether to formalize a new skill (or extend an existing
one) is the user's call, same as flagging a domain decision in `ship-issue`
— don't write a new `SKILL.md` unprompted.

## 8. Report; don't merge automatically

Same rule as `ship-issue`/`write-adr`: report that the retro's PR(s) are up
and CI is green, and merge only when asked.
