# 0027. Single shared WebSocket connection with bounded retry before login redirect

Date: 2026-08-25

Status: Accepted

## Context

Issue #128: swapping the gateway from a Docker container to a local
`./gradlew bootRun` process (same Redis/Postgres/RabbitMQ/Keycloak
containers) while a browser tab held a live session produced an infinite
OAuth2 redirect loop. Server-side, every `GET /ws` upgrade failed with
`IllegalStateException: Session was invalidated`
(`ReactiveRedisSessionRepository.lambda$save$0`). `socket.ts` treated any
handshake that closed before opening as "not authenticated" and redirected
to `/oauth2/authorization/keycloak`; Keycloak still had a live SSO session,
so it silently re-authenticated and bounced straight back, hitting the same
failure again. Not reproduced on a clean `docker compose restart gateway` -
narrower than "any gateway restart breaks sessions," and not root-caused
inside `ReactiveRedisSessionRepository` itself (see the issue body).

Two things about the frontend's own connection handling made this worse
than it needed to be:

* Every component that needed socket data (`Greeting`, `PriceGrid`,
  `TradeBlotter`, `ExecutionConfirmation`) called `connect()` independently,
  each opening its own WebSocket. A single page load fired up to four
  concurrent handshakes against the same session - concurrent load this
  project's own testing gotchas (`docs/testing.md`) already flag as the kind
  of thing that turns a rare race into a near-certainty, not an edge case.
* A handshake that closed before ever opening was treated as a definitive
  "this session is not valid" signal, with no retry - one failed upgrade was
  enough to trigger the login redirect.

## Decision

* **One shared WebSocket connection for the whole app.** `socket.ts` now
  owns a single connection; components call `subscribe(type, handler)` for
  the envelope types they care about (returning an unsubscribe function) and
  `send(message)` to write to it, instead of each calling `connect()` and
  managing their own `WebSocket` instance. This removes the concurrent-
  handshake pile-up that made the Redis session race far more likely to
  trigger in the first place.
* **Bounded retry with backoff before concluding "not authenticated."** A
  handshake that closes before opening is retried up to `MAX_CONNECT_ATTEMPTS
  = 3` times, with delays of 300ms then 900ms, before falling back to the
  Keycloak login redirect. This gives a transient, self-resolving failure
  (the observed behavior wasn't reproducible on every gateway swap) room to
  clear rather than escalating on the very first failed attempt. A send
  issued before the connection has opened is queued and flushed in order
  once it does, so callers don't need to coordinate with connection state
  themselves.
* **Not a fix inside `ReactiveRedisSessionRepository`.** This doesn't change
  any backend session-handling code - the exact trigger inside Spring
  Session's Redis repository remains unconfirmed, consistent with the issue
  body's own account. What changes is the frontend's tolerance for a
  handshake failure: instead of one failed attempt immediately producing an
  unrecoverable redirect loop, a bounded number of retries gives a
  transient failure a chance to resolve on its own, and only an upgrade that
  keeps failing past that bound falls back to the login redirect - a single
  redirect, not an infinite loop, since a fresh Keycloak login always
  produces a new session.

## Consequences

* Components no longer own or clean up their own `WebSocket` - `Greeting`,
  `PriceGrid`, `TradeBlotter`, and `ExecutionConfirmation` all lost their
  `connect()`/`socket.close()` pairs in favor of `subscribe()`'s returned
  unsubscribe function, which only removes that component's handler from
  the shared connection rather than tearing the connection down.
* A genuinely invalid session (e.g. actually logged out, or Keycloak's SSO
  session also expired) still redirects to login - just after up to ~1.2s of
  retries rather than immediately. This is an accepted trade-off: slightly
  slower recovery for the common "really not authenticated" case, in
  exchange for not escalating a transient race into an infinite loop.
* If the underlying `ReactiveRedisSessionRepository` race is later root-
  caused on the backend, this frontend resilience isn't wasted work - a
  bounded retry against a transient handshake failure is reasonable
  independent of this specific bug, and the single-shared-connection change
  is a straightforward simplification either way.
* The "redirect to Keycloak once retries are exhausted" path itself isn't
  covered by an automated test: jsdom's `window.location` can't be
  reassigned or spied on (a documented jsdom limitation), so this was
  live-verified manually instead (see the PR) rather than in
  `socket.test.ts`. The retry mechanics leading up to that point are
  covered with a hand-rolled controllable fake `WebSocket`, since timing
  mock-socket's own internal open/close sequencing to land on "closes
  without ever opening" isn't practical.
