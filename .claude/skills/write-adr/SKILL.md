---
name: write-adr
description: Write a new Architecture Decision Record for this repo using docs/decisions/template.md, auto-numbered and matching the existing ADRs' style. Use when asked to "write an ADR", "document this decision", "record why we did X", or after making a significant architecture/tooling/protocol choice worth preserving.
---

# write-adr

This repo already has six ADRs in `docs/decisions/` covering real decisions
(Spring Boot version, Jib vs. Dockerfile, WebSocket addressing, etc.) — read
two or three of the existing ones first to match tone: concrete, dated,
willing to say "this was wrong, corrected in ADR NNNN" rather than silently
editing history.

## 1. Only write one for a real decision

An ADR is for a choice a reasonable person could have made differently, with
consequences that outlive the PR that made it — a technology pick, a protocol
convention, a tradeoff between two real options. It is not for routine
implementation detail already obvious from the code. If in doubt, check
`docs/decisions/*.md` for the shape of what's already been recorded.

## 2. Number it

```sh
ls docs/decisions/*.md | grep -oE '[0-9]{4}' | sort -n | tail -1
```

Next number is that plus one, zero-padded to 4 digits.

## 3. Write it from the template

Base it on `docs/decisions/template.md` (Context / Decision / Consequences).
Match the existing ADRs' register:

- **Date**: today, `YYYY-MM-DD`.
- **Status**: `Accepted` unless the decision is still open for debate
  (`Proposed`) or explicitly replaces an earlier one (`Superseded by [NNNN](NNNN-slug.md)`).
- **Context**: what was ambiguous or at risk of being invented ad hoc by
  whichever issue touched it first — this repo's ADRs consistently frame it
  this way (see ADR 0003's opening).
- **Decision**: the concrete choice, as bullet points where there are several
  related sub-decisions (see ADR 0006's numbered list style for a multi-part
  decision).
- **Consequences**: what this commits future work to, and what it makes
  harder — not just upside.

## 4. Cross-link both directions if this corrects or supersedes an earlier ADR

Don't just add the new file — go back and edit the old ADR's `Status:` line
to point at the new one (see how ADR 0002's status line references ADR 0005,
and ADR 0003 carries an inline "Correction (see ADR 0006)" note). A reader
who opens the old ADR should immediately learn it's been superseded, not have
to discover that by chance.

## 5. Update current-state docs if they were wrong

If the decision fixes something `docs/architecture.md`, `docs/protocol.md`,
or another current-state doc got wrong or now contradicts, fix that doc too
in the same PR — the ADR is the historical record of *why*, the docs/ files
are what's true *now*. Don't leave the wrong claim live in the doc while only
the ADR admits it changed (this happened once already: CLAUDE.md's protocol
section repeated a claim ADR 0006 had already corrected, until issue #42).

## 6. Save and reference

Save to `docs/decisions/NNNN-slug.md`. If a current-state doc should point at
it (the way `docs/protocol.md` links to ADR 0003/0006), add that link too.
