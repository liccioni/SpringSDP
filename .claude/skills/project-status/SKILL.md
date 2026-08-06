---
name: project-status
description: Check where the SDP project stands right now — per-milestone issue counts, current-milestone drift, and the next 5 tasks to work on — and report it. Use when asked "what's the current progress", "where are we", "what's next", at the start of a new session, or before picking up work with `ship-issue`.
---

# project-status

Answers "where does this project stand and what's next" without changing
anything. Reads [docs/roadmap.md](../../docs/roadmap.md),
[docs/workflow.md](../../docs/workflow.md), and `CLAUDE.md` rather than
restating them — this skill only encodes the *sequence* for turning GitHub's
issue/milestone state into a status report. It's read-only: picking an issue
to actually implement is `ship-issue`'s job; closing out a finished milestone
is `milestone-retro`'s job.

## 1. Read the intended milestone order

Read `CLAUDE.md`'s "Current milestone" line and
[docs/roadmap.md](../../docs/roadmap.md) top to bottom — the milestone
headings there are the canonical order (MVP 0.1, 0.2, 0.3, ...), and each
heading's bullet list is the intended order of work *within* that milestone.

## 2. Pull milestone and issue state from GitHub

```sh
gh api repos/{owner}/{repo}/milestones --jq '.[] | "\(.title): \(.open_issues) open, \(.closed_issues) closed"'
gh issue list --state open --json number,title,milestone
```

Group the open issues by milestone, in the roadmap order from step 1 — not
issue number order, since issues can be filed out of roadmap order (e.g. a
scope addition).

## 3. Check for drift

Compare `CLAUDE.md`'s "Current milestone" pointer against which milestone
actually still has open issues, in roadmap order. If they disagree, say so
in the report — don't silently correct it. Fixing drift between `CLAUDE.md`
and `docs/roadmap.md` is `milestone-retro`'s job (its step 5), not this
skill's.

## 4. Derive the next 5 tasks

Starting from the current milestone's remaining open issues, in the order
they're listed in that milestone's roadmap bullets:

- List each remaining open issue in that order.
- If the current milestone runs out of issues before reaching 5, the next
  task is **"run `milestone-retro` to close out `<milestone>`"** — a
  milestone should be formally closed (re-verified from clean `main`, ADRs
  audited, retrospective written) before starting the next one, per
  [docs/workflow.md](../../docs/workflow.md). Only after that does the next
  milestone's first issue(s) get listed.
- If an issue's body names a dependency on another still-open issue (check
  `gh issue view <N> --json body` if it's not obvious from the title), order
  it after that dependency even if the roadmap bullet order suggests
  otherwise.

## 5. Report

Keep it short:

- Current milestone (and any drift noted in step 3).
- One line per milestone: title, open/closed issue counts.
- Next 5 tasks, numbered, each with a one-line rationale (why it's next, not
  just what it is).

Don't branch, implement, or open anything — this skill only reports.
